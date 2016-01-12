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

import android.app.Activity;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ExpandableListView;
import android.widget.SeekBar;
import android.widget.TextView;

import org.w3c.dom.Text;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * TODO: Fill this out...
 */
public class ControlUIActivity extends Activity {
    private static final String TAG = ControlUIActivity.class.getSimpleName();


    // uBristleBot Service UUIDs
    private static final UUID S_GENERAL_ACCESS = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb");
    private static final UUID S_BATTERY = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final UUID S_RGB_LED = UUID.fromString("d5d62c0c-6f57-4ac0-bb97-2b694062756e");
    private static final UUID S_MOTORS = UUID.fromString("b8578989-149c-4849-91f0-2852935b1a86");
    private static final UUID S_SAVE_SETTNGS = UUID.fromString("29f0dcfe-bebe-4348-9631-5fbd8e7fcb79");

    private static List<UUID> uBristleBotServices = new ArrayList<>();
    static {
        uBristleBotServices.add(S_GENERAL_ACCESS);
        uBristleBotServices.add(S_BATTERY);
        uBristleBotServices.add(S_RGB_LED);
        uBristleBotServices.add(S_MOTORS);
        uBristleBotServices.add(S_SAVE_SETTNGS);
    }

    private static final UUID C_DEVICE_NAME = UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb");
    private static final UUID C_BATTERY_PERCENT = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    private static final UUID C_LED_RED = UUID.fromString("05664686-5bf2-45a9-83c5-8a927cd2e20c");
    private static final UUID C_LED_GREEN = UUID.fromString("dc203e1a-bfa0-4647-9693-e923b1585cce");
    private static final UUID C_LED_BLUE = UUID.fromString("2aacf813-333d-4b6c-b997-45854b8424b1");
    private static final UUID C_MOTOR_LEFT = UUID.fromString("03957515-5976-41c3-982a-56cb6c4b4a38");
    private static final UUID C_MOTOR_RIGHT = UUID.fromString("537ef040-0e23-4fdc-80eb-eedd837ad98f");
    private static final UUID C_SAVE_CHANGES = UUID.fromString("a0632df5-f8ad-401b-9f0f-80fd1f43edf3");


    // UI Elements
    private SeekBar rightSeekbar;
    private SeekBar leftSeekbar;
    private TextView textIcon;
    private View ledIcon;

    // Intent Filters
    public static final String EXTRAS_DEVICE_NAME = "DEVICE_NAME";
    public static final String EXTRAS_DEVICE_ADDRESS = "DEVICE_ADDRESS";

    // BLE variables
    private String mDeviceName = "";
    private String mDeviceAddress;
    private BLEService mBLEService;
    private ArrayList<ArrayList<BluetoothGattCharacteristic>> mGattCharacteristics =
            new ArrayList<ArrayList<BluetoothGattCharacteristic>>();
    private boolean mConnected = false;
    private BluetoothGattCharacteristic mNotifyCharacteristic;

    //
    // Handle BLEService connection
    //
    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            mBLEService = ((BLEService.LocalBinder) service).getService();
            if (!mBLEService.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth");
                finish();
            }
            // Automatically connects to the device upon successful start-up initialization.
            mBLEService.connect(mDeviceAddress);
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mBLEService = null;
        }
    };

    // Handles various events fired by the Service.
    // ACTION_GATT_CONNECTED: connected to a GATT server.
    // ACTION_GATT_DISCONNECTED: disconnected from a GATT server.
    // ACTION_GATT_SERVICES_DISCOVERED: discovered GATT services.
    // ACTION_DATA_AVAILABLE: received data from the device.  This can be a result of read
    //                        or notification operations.
    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            if (BLEService.ACTION_GATT_CONNECTED.equals(action)) {
                // Connected to uBristleBot
                mConnected = true;

                // Don't enable UI here.
                // Enable UI when services discovered match what's expected.
            } else if (BLEService.ACTION_GATT_DISCONNECTED.equals(action)) {
                // Disconnected from uBristleBot
                mConnected = false;

                // Disable UI Elements
                leftSeekbar.setEnabled(false);
                rightSeekbar.setEnabled(false);
                textIcon.setLongClickable(false);
                ledIcon.setLongClickable(false);

                // TODO: Show Dialog requesting reconnect

                // TODO: Test this
                //onBackPressed();


            } else if (BLEService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                // Check list of services discovered against those expected for a uBristleBot
                List<BluetoothGattService> services = mBLEService.getSupportedGattServices();

                if (services.get(services.size() - 1).getUuid().equals(S_SAVE_SETTNGS)) {
                    // The last service discovered was successfully matched.

                    // Populate all characteristics we'll need.
                    BluetoothGattCharacteristic cDeviceName = services.get(0)
                            .getCharacteristic(C_DEVICE_NAME);

                    // Device Name String
                    mBLEService.readCharacteristic(cDeviceName);

                    // LEDs
                    /*mBLEService.readCharacteristic(new BluetoothGattCharacteristic(C_LED_RED,
                            BluetoothGattCharacteristic.PROPERTY_READ,
                            BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PERMISSION_WRITE));
                    mBLEService.readCharacteristic(new BluetoothGattCharacteristic(C_LED_GREEN,
                            BluetoothGattCharacteristic.PROPERTY_READ,
                            BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PERMISSION_WRITE));
                    mBLEService.readCharacteristic(new BluetoothGattCharacteristic(C_LED_BLUE,
                            BluetoothGattCharacteristic.PROPERTY_READ,
                            BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PERMISSION_WRITE));*/

                    // Battery
                    //mBLEService.setCharacteristicNotification(new BluetoothGattCharacteristic(C_BATTERY_PERCENT, true));
                    //mBLEService.readCharacteristic(new BluetoothGattCharacteristic(C_BATTERY_PERCENT));


                    // Enable UI Elements
                    leftSeekbar.setEnabled(true);
                    rightSeekbar.setEnabled(true);
                    textIcon.setLongClickable(true);
                    ledIcon.setLongClickable(true);
                } else {
                    // Not a uBristleBot
                     mBLEService.disconnect();

                    //TODO: Show that device was not a uBristleBot
                    onBackPressed();
                }
            } else if (BLEService.ACTION_DATA_AVAILABLE.equals(action)) {
                //displayData(intent.getStringExtra(BLEService.EXTRA_DATA));

                // TODO: Switch data depending on service
                // Read Device Name
                // Notify on Battery Change
                // Read LEDs

                Log.i(TAG, intent.getStringExtra(BLEService.EXTRA_DATA));
            }
        }
    };


    //
    // UI
    //
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_control_ui);

        final Intent intent = getIntent();
        mDeviceName = intent.getStringExtra(EXTRAS_DEVICE_NAME);
        mDeviceAddress = intent.getStringExtra(EXTRAS_DEVICE_ADDRESS);


        // Setup UI actions
        leftSeekbar = (SeekBar) findViewById(R.id.motor_left);
        leftSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    Log.d("L: " + String.valueOf(progress) + "%", TAG);

                    if (mConnected) {
                        // TODO: sDeviceName.writeToCharacteristic
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                seekBar.setProgress(0);
            }
        });

        rightSeekbar = (SeekBar) findViewById(R.id.motor_right);
        rightSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    Log.d("R: " + String.valueOf(progress) + "%", TAG);

                    if (mConnected) {
                        // TODO: Send BLE Message
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                seekBar.setProgress(0);
            }
        });

        textIcon = (TextView) findViewById(R.id.name_icon);
        textIcon.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                Log.i(TAG, "Name Icon was Long Pressed.");

                // TODO: Launch Text Dialog

                return true;
            }
        });

        ledIcon = findViewById(R.id.led_icon);
        ledIcon.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                Log.i(TAG, "LED Icon was Long Pressed.");

                // TODO: Launch Color Picker Dialog

                return true;
            }
        });

        getActionBar().setTitle(mDeviceName);
        getActionBar().setDisplayHomeAsUpEnabled(true);

        // Connect to BLE Service
        Intent gattServiceIntent = new Intent(this, BLEService.class);
        bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
        if (mBLEService != null) {
            final boolean result = mBLEService.connect(mDeviceAddress);
            Log.d(TAG, "Connect request result=" + result);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(mGattUpdateReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mBLEService.disconnect();
        unbindService(mServiceConnection);
        mBLEService = null;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.gatt_services, menu);
        if (mConnected) {
            menu.findItem(R.id.menu_connect).setVisible(false);
            menu.findItem(R.id.menu_disconnect).setVisible(true);
        } else {
            menu.findItem(R.id.menu_connect).setVisible(true);
            menu.findItem(R.id.menu_disconnect).setVisible(false);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()) {
            case R.id.menu_connect:
                mBLEService.connect(mDeviceAddress);
                return true;
            case R.id.menu_disconnect:
                mBLEService.disconnect();
                return true;
            case android.R.id.home:
                onBackPressed();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }


    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BLEService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BLEService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BLEService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BLEService.ACTION_DATA_AVAILABLE);
        return intentFilter;
    }
}
