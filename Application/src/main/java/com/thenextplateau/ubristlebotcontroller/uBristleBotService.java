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
import android.support.annotation.NonNull;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Service for managing connection and data communication with a GATT server hosted on a
 * given Bluetooth LE device.
 */
public class uBristleBotService extends Service {
    private final static String TAG = uBristleBotService.class.getSimpleName();

    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothGatt mBluetoothGatt;


    //
    // Service/Activity communication
    //

    public final static String ACTION_BLUETOOTH_IS_DISABLED =
            "com.thenextplateau.ubristlebot.ACTION_BLUETOOTH_IS_DISABLED";
    public final static String ACTION_CONNECTED =
            "com.thenextplateau.ubristlebot.ACTION_CONNECTED";
    public final static String ACTION_CONNECT_FAILED =
            "com.thenextplateau.ubristlebot.ACTION_CONNECT_FAILED";
    public final static String ACTION_DEVICE_FOUND =
            "com.thenextplateau.ubristlebot.ACTION_DEVICE_FOUND";
    public final static String ACTION_SCAN_COMPLETE =
            "com.thenextplateau.ubristlebot.ACTION_SCAN_COMPLETE";


    public final static String ACTION_BLE_GATT_CONNECTED =
            "com.thenextplateau.ubristlebot.ACTION_GATT_CONNECTED";
    public final static String ACTION_BLE_GATT_DISCONNECTED =
            "com.thenextplateau.ubristlebot.ACTION_GATT_DISCONNECTED";
    public final static String ACTION_BLE_GATT_SERVICES_DISCOVERED =
            "com.thenextplateau.bubristlebot.ACTION_GATT_SERVICES_DISCOVERED";
    public final static String ACTION_DATA_AVAILABLE =
            "com.thenextplateau.ubristlebot.ACTION_DATA_AVAILABLE";
    public final static String ACTION_DATA_UPDATED =
            "com.thenextplateau.ubristlebot.ACTION_DATA_UPDATED";
    public final static String EXTRA_DATA =
            "com.thenextplateau.ubristlebot.EXTRA_DATA";

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (mDeviceConnectionState == DEVICE_STATE_CONNECTING) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    mDeviceConnectionState = DEVICE_STATE_CONNECTED;
                    Log.i(TAG, "Connected to Device. Discovering Services...");

                    // Discover Services
                    mBluetoothGatt.discoverServices();
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
                    broadcastUpdate(ACTION_BLE_GATT_DISCONNECTED);
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (isuBristleBot(gatt.getServices())) {
                    // We've successfully connected to a uBristleBot!
                    mDeviceConnectionState = DEVICE_STATE_CONNECTED;
                    broadcastUpdate(ACTION_CONNECTED);

                    // TODO: Populate characteristics from services
                    List<BluetoothGattService> services = gatt.getServices();



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
                broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            broadcastUpdate(ACTION_DATA_UPDATED, characteristic);
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

    private void broadcastUpdate(final String action,
                                 final BluetoothGattCharacteristic characteristic) {
        final Intent intent = new Intent(action);

        final byte[] data = characteristic.getValue();
        if (data != null && data.length > 0) {
            intent.putExtra(EXTRA_DATA, data);
        }
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
        disconnect();
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

        mScanHandler = new Handler();

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
            mScanHandler.removeCallbacks(null);
            scanForDevices(false);
        }
    }

    // Helper function for API differences
    @SuppressLint("NewApi")
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

        mBluetoothGatt.disconnect();

        // We're either connected or we're not. No in between.
        mBluetoothGatt.close();
        mBluetoothGatt = null;
    }














    /**
     * Request a read on a given {@code BluetoothGattCharacteristic}. The read result is reported
     * asynchronously through the {@code BluetoothGattCallback#onCharacteristicRead(android.bluetooth.BluetoothGatt, android.bluetooth.BluetoothGattCharacteristic, int)}
     * callback.
     *
     * @param characteristic The characteristic to read from.
     */
    public void readCharacteristic(BluetoothGattCharacteristic characteristic) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.readCharacteristic(characteristic);
    }

    /**
     * TODO
     * @param characteristic The characteristic to write to
     * @return If the characteristic was successfully updated
     */
    public boolean writeCharacteristic(BluetoothGattCharacteristic characteristic) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return false;
        }

        return mBluetoothGatt.writeCharacteristic(characteristic);
    }

    /**
     * Enables or disables notification on a give characteristic.
     *
     * @param characteristic Characteristic to act on.
     * @param enabled If true, enable notification.  False otherwise.
     */
    public void setCharacteristicNotification(BluetoothGattCharacteristic characteristic,
                                              boolean enabled) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.setCharacteristicNotification(characteristic, enabled);

        // Reference for magic numbers:
        //  https://developer.bluetooth.org/gatt/descriptors/Pages/DescriptorViewer.aspx?
        //  u=org.bluetooth.descriptor.gatt.client_characteristic_configuration.xml
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(
                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));

        if (enabled) {
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        } else {
            descriptor.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
        }

        mBluetoothGatt.writeDescriptor(descriptor);
    }

    /**
     * Retrieves a list of supported GATT services on the connected device. This should be
     * invoked only after {@code BluetoothGatt#discoverServices()} completes successfully.
     *
     * @return A {@code List} of supported services.
     */
    public List<BluetoothGattService> getSupportedGattServices() {
        if (mBluetoothGatt == null) return null;

        return mBluetoothGatt.getServices();
    }
}
