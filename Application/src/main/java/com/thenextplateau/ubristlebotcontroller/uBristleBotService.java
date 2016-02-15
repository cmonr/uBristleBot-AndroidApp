/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.thenextplateau.ubristlebotcontroller;

import android.annotation.SuppressLint;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Service for managing the BLE connection between the Activity frontend, and the uBristleBot.
 */
public class uBristleBotService extends Service {
    private final static String TAG = uBristleBotService.class.getSimpleName();

    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private static BluetoothGatt mBluetoothGatt;


    //
    // Service/Activity communication
    //
    public final static String ACTION_BLUETOOTH_IS_DISABLED =
            "com.thenextplateau.ubristlebot.ACTION_BLUETOOTH_IS_DISABLED";
    public final static String ACTION_DEVICE_FOUND =
            "com.thenextplateau.ubristlebot.ACTION_DEVICE_FOUND";
    public final static String ACTION_SCAN_COMPLETE =
            "com.thenextplateau.ubristlebot.ACTION_SCAN_COMPLETE";
    public final static String ACTION_CONNECT_FAILED =
            "com.thenextplateau.ubristlebot.ACTION_CONNECT_FAILED";
    public final static String ACTION_CONNECTING_COMPARING_SERVICES =
            "com.thenextplateau.ubristlebot.ACTION_CONNECTING_COMPARING_SERVICES";
    public final static String ACTION_CONNECTING_READING_CHARACTERISTICS =
            "com.thenextplateau.ubristlebot.ACTION_CONNECTING_READING_CHARACTERISTICS";
    public final static String ACTION_CONNECTED =
            "com.thenextplateau.ubristlebot.ACTION_CONNECTED";
    public final static String ACTION_DEVICE_DISCONNECTED =
            "com.thenextplateau.ubristlebot.ACTION_BLE_DISCONNECTED";

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (mDeviceConnectionState == DEVICE_STATE_CONNECTING) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    mDeviceConnectionState = DEVICE_STATE_CONNECTED;
                    Log.i(TAG, "Connected to Device. Discovering Services...");

                    // Discover Services
                    mBluetoothGatt.discoverServices();
                    broadcastUpdate(ACTION_CONNECTING_COMPARING_SERVICES);
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    mDeviceConnectionState = DEVICE_STATE_DISCONNECTED;
                    Log.i(TAG, "Could not connect to Device.");
                    broadcastConnectFailedUpdate("Connect Error: Could not connect to Device.");
                }
            } else if (mDeviceConnectionState == DEVICE_STATE_CONNECTED) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.e(TAG, "Something happened that shouldn't have...");
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "Disconnected from Device.");

                    // We're either connected or we're not. No in between.
                    mBluetoothGatt.close();
                    mBluetoothGatt = null;

                    broadcastUpdate(ACTION_DEVICE_DISCONNECTED);
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (isuBristleBot(gatt.getServices())) {
                    // We're connected to a uBristleBot!
                    mDeviceConnectionState = DEVICE_STATE_CONNECTED;

                    // Initialize BLE Characteristics
                    broadcastUpdate(ACTION_CONNECTING_READING_CHARACTERISTICS);
                    initBLECharacteristics(gatt.getServices());

                    // Initialize everything else for the robot
                    robotInit();
                } else {
                    disconnect();
                    Log.i(TAG, "Services Discovered are not those of an uBristBot");
                    broadcastConnectFailedUpdate("Connect Error: Device is not a uBristleBot.");
                }
            } else {
                disconnect();
                Log.i(TAG, "Error discovering services on Device.");
                broadcastConnectFailedUpdate("Connect Error: Services not found.");
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic,
                                         int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Immediately start reading another characteristic
                characteristicReadList.remove(0);
                if (! characteristicReadList.isEmpty()) {
                    mBluetoothGatt.readCharacteristic(characteristicReadList.get(0));
                }

                // Figure out where to put that data
                if (characteristic.getUuid().equals(C_DEVICE_NAME)) {
                    mDeviceName = new String(characteristic.getValue());

                } else if (characteristic.getUuid().equals(C_BATTERY)) {
                    boradcastDeviceBatteryUpdate(characteristic.getValue()[0] & 0xFF);

                } else if (characteristic.getUuid().equals(C_LED_RED)) {
                    mRGB[0] = characteristic.getValue()[0];
                } else if (characteristic.getUuid().equals(C_LED_GREEN)) {
                    mRGB[1] = characteristic.getValue()[0];
                } else if (characteristic.getUuid().equals(C_LED_BLUE)) {
                    mRGB[2] = characteristic.getValue()[0];

                    // That's the last of them!
                    // Complete the remaining device init

                    // Enable Battery Notification
                    mBluetoothGatt.setCharacteristicNotification(cBattery, true);

                    // Reference for magic numbers:
                    //  https://developer.bluetooth.org/gatt/descriptors/Pages/DescriptorViewer.aspx?
                    //  u=org.bluetooth.descriptor.gatt.client_characteristic_configuration.xml
                    BluetoothGattDescriptor descriptor = cBattery.getDescriptor(
                            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));

                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    mBluetoothGatt.writeDescriptor(descriptor);

                    // We're ready for the fun stuff!
                    broadcastUpdate(ACTION_CONNECTED);
                } else {
                    // Wat.
                    Log.e(TAG, "We're not suppose to get here....");
                }
            } else {
                // Immediately retry read (max retries be damned...)
                mBluetoothGatt.readCharacteristic(characteristicReadList.get(0));

                Log.d(TAG, "BLE Characteristic Read failed. Error code: " + status);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                                          BluetoothGattCharacteristic characteristic,
                                          int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "BLE Characteristic Write failed. Error code: " + status);
                // Something went wrong...
                if (characteristic.getUuid().equals(cMotor_L.getUuid()) ||
                        characteristic.getUuid().equals(cMotor_R.getUuid())) {
                    // Only resend if motor was set to 0
                    if (characteristic.getValue()[0] == 0) {
                        Log.d(TAG, "Rewriting 0");
                        mBluetoothGatt.writeCharacteristic(characteristic);
                        return;
                    }

                    // Otherwise, forget about it. The values will change so often
                    //  that if we try to constantly resend in a noisy env,
                    //  responsiveness will degrade.
                } else {
                    // Any other write should always make it through.
                    mBluetoothGatt.writeCharacteristic(characteristic);
                    return;
                }
            }

            // Everything went well. Update the next characteristic if needed.
            characteristicWriteList.remove(0);
            if (! characteristicWriteList.isEmpty()) {
                Log.d(TAG, "BLE Characteristic Write succeeded");
                mBluetoothGatt.writeCharacteristic(characteristicWriteList.get(0));
            }

            // If we've just saved settings, we're about to be disconnected.
            //  Preempt this.
            if (characteristic.getUuid().equals(C_SAVE_CHANGES)) {
                Log.d(TAG, "Disconnecting after saving settings");
                disconnect();
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            boradcastDeviceBatteryUpdate(characteristic.getValue()[0] & 0xFF);
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt gatt,
                                     int rssi,
                                     int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                boradcastDeviceRSSIUpdate(rssi);
            } else {
                Log.e(TAG, "Error reading remote RSSI");
            }
        }
    };

    private void broadcastUpdate(final String action) {
        final Intent intent = new Intent(action);
        sendBroadcast(intent);
    }

    private void broadcastDeviceFoundUpdate(final BluetoothDevice device, final int rssi) {
        final Intent intent = new Intent(ACTION_DEVICE_FOUND);

        intent.putExtra(SCAN_RESULT_DEVICE_NAME, device.getName());
        intent.putExtra(SCAN_RESULT_DEVICE_ADDRESS, device.getAddress());
        intent.putExtra(SCAN_RESULT_DEVICE_RSSI, rssi);

        sendBroadcast(intent);
    }

    private void broadcastConnectFailedUpdate(final String error) {
        final Intent intent = new Intent(ACTION_CONNECT_FAILED);

        intent.putExtra(CONNECT_ERROR, error);

        sendBroadcast(intent);
    }

    private void boradcastDeviceRSSIUpdate(final int rssi) {
        final Intent intent = new Intent(ACTION_DEVICE_RSSI_CHANGED);

        intent.putExtra(DEVICE_RSSI, rssi);

        sendBroadcast(intent);
    }

    private void boradcastDeviceBatteryUpdate(final int batteryPercent) {
        final Intent intent = new Intent(ACTION_DEVICE_BATTERY_CHANGED);

        intent.putExtra(DEVICE_BATTERY, batteryPercent);

        sendBroadcast(intent);
    }



    public class LocalBinder extends Binder {
        uBristleBotService getService() {
            return uBristleBotService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return super.onUnbind(intent);
    }

    private final IBinder mBinder = new LocalBinder();



    //
    // Initialize Bluetooth Connection
    //
    public static final int INIT_ERROR_NONE = 0;
    public static final int INIT_ERROR_BLE_NOT_AVAILABLE = 1;
    public static final int INIT_ERROR_BLUETOOTH_MANAGER_INIT_FAILED = 2;
    public static final int INIT_ERROR_BLUETOOTH_ADAPTER_INIT_FAILED = 3;

    public int initialize() {
        // Initialize Bluetooth Adapter and perform basic checks

        if (! getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Log.e(TAG, "BLE is not available on this device");
            return INIT_ERROR_BLE_NOT_AVAILABLE;
        }


        if (mBluetoothManager == null) {
            mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (mBluetoothManager == null) {
                Log.e(TAG, "Could not initialize BluetoothManager");
                return INIT_ERROR_BLUETOOTH_MANAGER_INIT_FAILED;
            }
        }

        mBluetoothAdapter = mBluetoothManager.getAdapter();
        if (mBluetoothAdapter == null) {
            Log.e(TAG, "Could not initialize BluetoothAdapter");
            return INIT_ERROR_BLUETOOTH_ADAPTER_INIT_FAILED;
        }

        mScanHandler = new Handler(Looper.getMainLooper());

        return INIT_ERROR_NONE;
    }



    //
    // Scan for Devices
    //
    private static Handler mScanHandler;
    private static boolean mIsScanning;

    public final static String SCAN_RESULT_DEVICE_NAME =
            "com.thenextplateau.ubristlebot.scanresult.DEVICE_NAME";
    public final static String SCAN_RESULT_DEVICE_ADDRESS =
            "com.thenextplateau.ubristlebot.scanresult.DEVICE_ADDRESS";
    public final static String SCAN_RESULT_DEVICE_RSSI =
            "com.thenextplateau.ubristlebot.scanresult.DEVICE_RSSI";

    public void scanForBots(boolean startScan) {
        if (! mBluetoothAdapter.isEnabled()) {
            broadcastUpdate(ACTION_BLUETOOTH_IS_DISABLED);
            return;
        }

        if (startScan) {
            // Disconnect before starting scan, if needed
            if (isConnected()) {
                disconnect();
            }

            // Stop scanning after a certain amount of time
            mScanHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    scanForDevices(false);
                }
            }, 10 * 1000);

            // Start scanning for devices
            scanForDevices(true);
        } else {
            mScanHandler.removeCallbacksAndMessages(null);
            mScanHandler = new Handler(Looper.getMainLooper());
            scanForDevices(false);
        }
    }

    // Helper function for API differences
    @SuppressLint("NewApi")
    @SuppressWarnings("deprecation")
    private void scanForDevices(boolean startScan) {
        if (startScan) {
            if (!mIsScanning) {
                Log.i(TAG, "Starting BLE Scan");

                mIsScanning = true;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    mBluetoothAdapter.getBluetoothLeScanner().startScan(mScanCallback);
                } else {
                    mBluetoothAdapter.startLeScan(mLeScanCallback);
                }
            }
        } else {
            if (mIsScanning) {
                Log.i(TAG, "Stopping BLE Scan");

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    mBluetoothAdapter.getBluetoothLeScanner().stopScan(mScanCallback);
                } else {
                    mBluetoothAdapter.stopLeScan(mLeScanCallback);
                }

                mIsScanning = false;

                // Broadcast Update that the scan has stopped
                broadcastUpdate(ACTION_SCAN_COMPLETE);
            }
        }
    }

    @SuppressLint("NewApi")
    private ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, @NonNull ScanResult scanResult) {
            Log.i(TAG, "Found BLE device: " + scanResult.getDevice().getName());

            broadcastDeviceFoundUpdate(scanResult.getDevice(), scanResult.getRssi());
        }

        @Override
        public void onBatchScanResults(@NonNull List<ScanResult> results) {
            for (ScanResult result : results) {
                Log.i(TAG, "Found BLE device: " + result.getDevice().getName());

                broadcastDeviceFoundUpdate(result.getDevice(), result.getRssi());
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "BLE Scan failed. Error code " + String.valueOf(errorCode));
        }
    };

    private BluetoothAdapter.LeScanCallback mLeScanCallback  =
            new BluetoothAdapter.LeScanCallback() {
                @Override
                public void onLeScan(final BluetoothDevice device, int rssi, byte[] scanRecord) {
                    Log.i(TAG, "Found BLE device: " + device.getName());

                    broadcastDeviceFoundUpdate(device, rssi);
                }
            };


    //
    // Connect to Device
    //
    private int mDeviceConnectionState = DEVICE_STATE_DISCONNECTED;

    private static final int DEVICE_STATE_DISCONNECTED = 0;
    private static final int DEVICE_STATE_CONNECTING = 1;
    private static final int DEVICE_STATE_CONNECTED = 2;

    public final static String CONNECT_ERROR =
            "com.thenextplateau.ubristlebot.connecting.CONNECT_ERROR";

    public boolean isConnecting() {
        return mDeviceConnectionState == DEVICE_STATE_CONNECTING;
    }
    public boolean isConnected() {
        return mDeviceConnectionState == DEVICE_STATE_CONNECTED;
    }

    public void connectTo(final String deviceAddress) {
        if (deviceAddress == null) {
            Log.e(TAG, "Failed to connect to device. Empty address string.");
            return;
        }
        if (mBluetoothAdapter == null) {
            Log.e(TAG, "Failed to connect to device. Bluetooth Adapter is uninitialized.");
            return;
        }
        if (! BluetoothAdapter.checkBluetoothAddress(deviceAddress)) {
            Log.e(TAG, "Failed to connect to device. Invalid address.");
            return;
        }

        final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(deviceAddress);

        // Connect to device, disabling Auto Connect and any active scans
        scanForBots(false);
        mBluetoothGatt = device.connectGatt(this, false, mGattCallback);

        Log.d(TAG, "Connecting to device.");
        mDeviceConnectionState = DEVICE_STATE_CONNECTING;
    }

    // uBristleBot Service UUIDs
    private static final UUID S_GENERAL_ACCESS = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb");
    private static final UUID S_BATTERY = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final UUID S_RGB_LED = UUID.fromString("d5d62c0c-6f57-4ac0-bb97-2b694062756e");
    private static final UUID S_MOTORS = UUID.fromString("b8578989-149c-4849-91f0-2852935b1a86");
    private static final UUID S_SAVE_SETTNGS = UUID.fromString("29f0dcfe-bebe-4348-9631-5fbd8e7fcb79");

    private static List<UUID> uBristleBotServiceUUIDs = new ArrayList<>();
    static {
        uBristleBotServiceUUIDs.add(S_GENERAL_ACCESS);
        uBristleBotServiceUUIDs.add(S_BATTERY);
        uBristleBotServiceUUIDs.add(S_RGB_LED);
        uBristleBotServiceUUIDs.add(S_MOTORS);
        uBristleBotServiceUUIDs.add(S_SAVE_SETTNGS);
    }
    private boolean isuBristleBot(List<BluetoothGattService> discoveredServices) {
        if (discoveredServices.size() != uBristleBotServiceUUIDs.size()) {
            return false;
        }

        for (int i=0; i< uBristleBotServiceUUIDs.size(); i++) {
            if (! discoveredServices.get(i).getUuid()
                    .equals(uBristleBotServiceUUIDs.get(i))) {
                return false;
            }
        }

        return true;
    }

    public void disconnect() {
        if (mBluetoothAdapter == null) {
            Log.w(TAG, "Failed to disconnect device. Bluetooth Adapter is uninitialized.");
            return;
        }
        if (mBluetoothGatt == null) {
            Log.w(TAG, "Failed to disconnect device. Bluetooth connection was not formed.");
            return;
        }

        robotDeinit();

        mBluetoothGatt.disconnect();
    }


    //
    // Initialize BLE Characteristics for the device
    //
    private static final UUID C_DEVICE_NAME = UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb");
    private static final UUID C_BATTERY = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    private static final UUID C_LED_RED = UUID.fromString("05664686-5bf2-45a9-83c5-8a927cd2e20c");
    private static final UUID C_LED_GREEN = UUID.fromString("dc203e1a-bfa0-4647-9693-e923b1585cce");
    private static final UUID C_LED_BLUE = UUID.fromString("2aacf813-333d-4b6c-b997-45854b8424b1");
    private static final UUID C_MOTOR_LEFT = UUID.fromString("03957515-5976-41c3-982a-56cb6c4b4a38");
    private static final UUID C_MOTOR_RIGHT = UUID.fromString("537ef040-0e23-4fdc-80eb-eedd837ad98f");
    private static final UUID C_SAVE_CHANGES = UUID.fromString("a0632df5-f8ad-401b-9f0f-80fd1f43edf3");

    // BLE Characteristics
    private static BluetoothGattCharacteristic cDeviceName = null;
    private static BluetoothGattCharacteristic cBattery = null;
    private static BluetoothGattCharacteristic cLED_R = null;
    private static BluetoothGattCharacteristic cLED_G = null;
    private static BluetoothGattCharacteristic cLED_B = null;
    private static BluetoothGattCharacteristic cMotor_L = null;
    private static BluetoothGattCharacteristic cMotor_R = null;
    private static BluetoothGattCharacteristic cSave = null;

    // BLE Characteristic Read/Write lists
    private static ArrayList<BluetoothGattCharacteristic> characteristicReadList;
    private static ArrayList<BluetoothGattCharacteristic> characteristicWriteList;

    // uBristleBot info we care about
    private static String mDeviceName;
    private byte[] mRGB;

    public void initBLECharacteristics(List<BluetoothGattService> servicesDiscovered) {
        // Initialize all internal Characteristics
        cDeviceName = servicesDiscovered.get(0).getCharacteristic(C_DEVICE_NAME);

        cBattery = servicesDiscovered.get(1).getCharacteristic(C_BATTERY);

        cLED_R = servicesDiscovered.get(2).getCharacteristic(C_LED_RED);
        cLED_G = servicesDiscovered.get(2).getCharacteristic(C_LED_GREEN);
        cLED_B = servicesDiscovered.get(2).getCharacteristic(C_LED_BLUE);

        cMotor_L = servicesDiscovered.get(3).getCharacteristic(C_MOTOR_LEFT);
        cMotor_R = servicesDiscovered.get(3).getCharacteristic(C_MOTOR_RIGHT);

        cSave = servicesDiscovered.get(4).getCharacteristic(C_SAVE_CHANGES);


        // Setup Read Queue
        characteristicReadList = new ArrayList<>();
        characteristicReadList.add(cDeviceName);
        characteristicReadList.add(cBattery);
        characteristicReadList.add(cLED_R);
        characteristicReadList.add(cLED_G);
        characteristicReadList.add(cLED_B);

        // Setup Write Queue
        characteristicWriteList = new ArrayList<>();

        // Start reading Characteristics from Server
        mBluetoothGatt.readCharacteristic(characteristicReadList.get(0));
    }

    //
    // uBristleBot
    //
    private static boolean mLeftMotorChanged;
    private static boolean mRightMotorChanged;
    private static int mLeftMotorPercent;
    private static int mRightMotorPercent;
    private static Handler mMotorUpdateHandler;
    private static Runnable updateMotorCharacteristics = new Runnable() {
        @Override
        public void run() {
            // Only write new values once the queue is empty
            if (characteristicWriteList.isEmpty()) {
                if (mLeftMotorChanged) {
                    Log.i(TAG, "Set Left Motor to: " + String.valueOf(mLeftMotorPercent) + "%");
                    mLeftMotorChanged = false;

                    cMotor_L.setValue(mLeftMotorPercent * 255 / 100,
                            BluetoothGattCharacteristic.FORMAT_UINT8,
                            0);

                    characteristicWriteList.add(cMotor_L);
                }
                if (mRightMotorChanged) {
                    Log.i(TAG, "Set Right Motor to: " + String.valueOf(mRightMotorPercent) + "%");
                    mRightMotorChanged = false;

                    cMotor_R.setValue(mRightMotorPercent * 255 / 100,
                            BluetoothGattCharacteristic.FORMAT_UINT8,
                            0);

                    characteristicWriteList.add(cMotor_R);
                }

                // Write a characteristic as long as either changed
                if (! characteristicWriteList.isEmpty()) {
                    Log.i(TAG, "Writing BLE Characteristic");
                    mBluetoothGatt.writeCharacteristic(characteristicWriteList.get(0));
                }
            } else if ((mLeftMotorChanged && mLeftMotorPercent == 0) ||
                    (mRightMotorChanged && mRightMotorPercent == 0)) {
                // A special condition can occur in the UI if messages are backed up
                //  and the user sets the motor to 0.
                //  This can eventually get corrected, but we're going to prioritize that 0.

                // Remove other queued commands aside for the first (since it's already in progress)
                while (characteristicWriteList.size() > 1) {
                    characteristicWriteList.remove(1);
                }

                // Setup new commands
                if (mLeftMotorPercent == 0) {
                    Log.i(TAG, "Special condition found. Zeroing Left Motor");
                    mLeftMotorChanged = false;

                    cMotor_L.setValue(mLeftMotorPercent * 255 / 100,
                            BluetoothGattCharacteristic.FORMAT_UINT8,
                            0);

                    characteristicWriteList.add(cMotor_L);
                }
                if (mRightMotorPercent == 0) {
                    Log.i(TAG, "Special condition found. Zeroing Right Motor");
                    mRightMotorChanged = false;

                    cMotor_R.setValue(mRightMotorPercent * 255 / 100,
                            BluetoothGattCharacteristic.FORMAT_UINT8,
                            0);

                    characteristicWriteList.add(cMotor_R);
                }
            }

            // Do it again!
            mMotorUpdateHandler.postDelayed(updateMotorCharacteristics, 200);
        }
    };
    private static Handler mRSSIUpdateHandler;
    private static Runnable updateRSSI = new Runnable() {
        @Override
        public void run() {
            if (mBluetoothGatt != null) {
                mBluetoothGatt.readRemoteRssi();

                mRSSIUpdateHandler.postDelayed(updateRSSI, 1000);
            }
        }
    };

    public final static String ACTION_DEVICE_RSSI_CHANGED =
            "com.thenextplateau.ubristlebot.ACTION_DEVICE_RSSI_CHANGED";
    public final static String ACTION_DEVICE_BATTERY_CHANGED =
            "com.thenextplateau.ubristlebot.ACTION_DEVICE_BATTERY_CHANGED";
    public final static String DEVICE_RSSI =
            "com.thenextplateau.ubristlebot.DEVICE_BATTERY";
    public final static String DEVICE_BATTERY =
            "com.thenextplateau.ubristlebot.DEVICE_BATTERY";

    private void robotInit() {
        mLeftMotorChanged = false;
        mRightMotorChanged = false;
        mLeftMotorPercent = 0;
        mRightMotorPercent = 0;

        mDeviceName = "";
        mRGB = new byte[3];
        mRGB[0] = mRGB[1] = mRGB[2] = (byte) 255;

        mMotorUpdateHandler = new Handler(Looper.getMainLooper());
        mMotorUpdateHandler.postDelayed(updateMotorCharacteristics, 200);

        mRSSIUpdateHandler = new Handler(Looper.getMainLooper());
        mRSSIUpdateHandler.postDelayed(updateRSSI, 1000);
    }

    private void robotDeinit() {
        if (mMotorUpdateHandler != null) {
            mMotorUpdateHandler.removeCallbacksAndMessages(null);
        }
        mMotorUpdateHandler = new Handler(Looper.getMainLooper());

        if (mRSSIUpdateHandler != null) {
            mRSSIUpdateHandler.removeCallbacksAndMessages(null);
        }
        mRSSIUpdateHandler = new Handler(Looper.getMainLooper());

        mRGB = new byte[3];
        mRGB[0] = mRGB[1] = mRGB[2] = (byte) 255;
        mDeviceName = "";

        mLeftMotorChanged = false;
        mRightMotorChanged = false;
        mLeftMotorPercent = 0;
        mRightMotorPercent = 0;
    }

    public void setName(String name) {
        if (! name.equals(mDeviceName)) {
            mDeviceName = name;

            // Update characteristic
            cDeviceName.setValue(mDeviceName.getBytes());
        }
    }
    public String getName() {
        if (mDeviceName == null)
            return "";
        return mDeviceName;
    }

    public void setColor(int r, int g, int b) {
        // Update characteristics
        cLED_R.setValue(r, BluetoothGattCharacteristic.FORMAT_UINT8, 0);
        cLED_G.setValue(g, BluetoothGattCharacteristic.FORMAT_UINT8, 0);
        cLED_B.setValue(b, BluetoothGattCharacteristic.FORMAT_UINT8, 0);
    }
    public void setLeftMotor(int percent) {
        if (percent < 0 || percent > 100)
            return;

        mLeftMotorPercent = percent;
        mLeftMotorChanged = true;
    }
    public void setRightMotor(int percent) {
        if (percent < 0 || percent > 100)
            return;

        mRightMotorPercent = percent;
        mRightMotorChanged = true;
    }
    public void saveSettingsAndDisconnect() {
        // Disable the motor update handler, just in case
        if (mMotorUpdateHandler != null) {
            mMotorUpdateHandler.removeCallbacksAndMessages(null);
        }
        mMotorUpdateHandler = new Handler(Looper.getMainLooper());

        // Add characteristics to write queue
        characteristicWriteList.clear();
        characteristicWriteList.add(cDeviceName);
        Log.i(TAG, "Setting name to " + new String(cDeviceName.getValue()));

        characteristicWriteList.add(cLED_R);
        characteristicWriteList.add(cLED_G);
        characteristicWriteList.add(cLED_B);
        Log.i(TAG, "Setting LEDs to " + String.valueOf(cLED_R.getValue()[0] & 0xFF) + "," + String.valueOf(cLED_G.getValue()[0] & 0xFF) + "," + String.valueOf(cLED_B.getValue()[0] & 0xFF));

        // Set characteristic that will make these settings stick on the device
        byte[] tmp = new byte[1];
        tmp[0] = (byte) 0x01;
        cSave.setValue(tmp);
        characteristicWriteList.add(cSave);

        // Get things going
        mBluetoothGatt.writeCharacteristic(characteristicWriteList.get(0));
    }
}
