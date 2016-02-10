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
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

/**
 * TODO: Fill this out...
 */
public class ControlUIActivity extends Activity {
    private static final String TAG = ControlUIActivity.class.getSimpleName();

    private uBristleBotService uBristleBot;



    // UI Elements
    private SeekBar rightSeekbar;
    private SeekBar leftSeekbar;
    private View ledIcon;

    // Intent Filters
    public static final String EXTRAS_DEVICE_NAME = "DEVICE_NAME";
    public static final String EXTRAS_DEVICE_ADDRESS = "DEVICE_ADDRESS";

    // BLE Management


    // Control which Characteristic is being read
    private static final int REQUESTED_NOTHING = 0;
    private static final int REQUESTED_C_DEVICE_NAME = 1;
    private static final int REQUESTED_C_BATTERY = 2;
    private static final int REQUESTED_C_LED_RED = 3;
    private static final int REQUESTED_C_LED_GREEN = 4;
    private static final int REQUESTED_C_LED_BLUE = 5;
    private static int lastCharacteristicRequested = REQUESTED_NOTHING;

    // Dialogs
    private static AlertDialog mDialog_Settings;
    private static View mDialog_View;
    private static TextView mDialog_Text_DeviceName;
    private static Spinner mDialog_ColorChooser;


    //
    // Handle uBristleBotService connection
    //
    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            uBristleBot = ((uBristleBotService.LocalBinder) service).getService();
            if (uBristleBot.initialize() !=uBristleBotService.INIT_ERROR_NONE) {
                Log.e(TAG, "Unable to initialize uBristleBotService");

                // TODO: Display useful message to user

                onBackPressed();
                return;
            }

            if (! uBristleBot.isConnected()) {
                Log.e(TAG, "uBristleBotService not connected to device");


                // TODO: Display useful message to user

                onBackPressed();
                return;
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            uBristleBot = null;
        }
    };

    // Handles various events fired by the Service.
    // ACTION_GATT_CONNECTED: connected to a GATT server.
    // ACTION_GATT_DISCONNECTED: disconnected from a GATT server.
    // ACTION_GATT_SERVICES_DISCOVERED: discovered GATT services.
    // ACTION_DATA_AVAILABLE: received data from the device.  This can be a result of read
    //                        or notification operations.
    private final BroadcastReceiver mUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
/*
            if (uBristleBotService.ACTION_GATT_CONNECTED.equals(action)) {
                // Connected to uBristleBot
                mConnected = true;

                // Don't enable UI here.
                // Enable UI when services discovered match what's expected.
            } else if (uBristleBotService.ACTION_GATT_DISCONNECTED.equals(action)) {
                // Disconnected from uBristleBot
                mConnected = false;

                // Disable UI Elements
                leftSeekbar.setEnabled(false);
                rightSeekbar.setEnabled(false);
                ledIcon.setLongClickable(false);

                // TODO: Show Dialog requesting reconnect

                // TODO: Test this
                //onBackPressed();


            } else if (uBristleBotService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                // Check list of services discovered against those expected for a uBristleBot
                List<BluetoothGattService> services = uBristleBot.getSupportedGattServices();

                if (services.get(services.size() - 1).getUuid().equals(S_SAVE_SETTNGS)) {
                    // The last service discovered was successfully matched.

                    // Populate all characteristics we'll need.
                    cDeviceName = services.get(0).getCharacteristic(C_DEVICE_NAME);

                    cBattery = services.get(1).getCharacteristic(C_BATTERY);

                    cLED_R = services.get(2).getCharacteristic(C_LED_RED);
                    cLED_G = services.get(2).getCharacteristic(C_LED_GREEN);
                    cLED_B = services.get(2).getCharacteristic(C_LED_BLUE);

                    cMotor_L = services.get(3).getCharacteristic(C_MOTOR_LEFT);
                    cMotor_R = services.get(3).getCharacteristic(C_MOTOR_RIGHT);

                    cSave = services.get(4).getCharacteristic(C_SAVE_CHANGES);

                    // Enable UI Elements
                    leftSeekbar.setEnabled(true);
                    rightSeekbar.setEnabled(true);
                    ledIcon.setLongClickable(true);

                    // Start requesting values, starting with the Device Name String
                    uBristleBot.readCharacteristic(cDeviceName);
                    lastCharacteristicRequested = REQUESTED_C_DEVICE_NAME;
                } else {
                    // Not a uBristleBot
                     uBristleBot.disconnect();

                    //TODO: Show that device was not a uBristleBot
                    onBackPressed();
                }
            } else if (uBristleBotService.ACTION_DATA_AVAILABLE.equals(action)) {
                // Determine what data we're receiving, and act/save appropriately
                switch(lastCharacteristicRequested) {
                    case REQUESTED_C_DEVICE_NAME:
                        // TODO: Update UI
                        mDeviceName = new String(intent.getByteArrayExtra(uBristleBotService.EXTRA_DATA));
                        Log.d(TAG, mDeviceName);

                        uBristleBot.readCharacteristic(cBattery);
                        lastCharacteristicRequested = REQUESTED_C_BATTERY;

                        // Set Name in Settings Dialog
                        mDialog_Text_DeviceName.setText(mDeviceName);

                        break;
                    case REQUESTED_C_BATTERY:
                        // TODO: Update UI
                        mBatteryLeft = intent.getByteArrayExtra(uBristleBotService.EXTRA_DATA)[0] & 0xFF;
                        Log.d(TAG, String.valueOf(mBatteryLeft));

                        uBristleBot.readCharacteristic(cLED_R);
                        lastCharacteristicRequested = REQUESTED_C_LED_RED;
                        break;
                    case REQUESTED_C_LED_RED:
                        mRGB[0] = intent.getByteArrayExtra(uBristleBotService.EXTRA_DATA)[0] & 0xFF;
                        Log.d(TAG, String.valueOf(mRGB[0]));

                        uBristleBot.readCharacteristic(cLED_G);
                        lastCharacteristicRequested = REQUESTED_C_LED_GREEN;
                        break;
                    case REQUESTED_C_LED_GREEN:
                        mRGB[1] = intent.getByteArrayExtra(uBristleBotService.EXTRA_DATA)[0] & 0xFF;
                        Log.d(TAG, String.valueOf(mRGB[1]));

                        uBristleBot.readCharacteristic(cLED_B);
                        lastCharacteristicRequested = REQUESTED_C_LED_BLUE;
                        break;
                    case REQUESTED_C_LED_BLUE:
                        mRGB[2] = intent.getByteArrayExtra(uBristleBotService.EXTRA_DATA)[0] & 0xFF;
                        Log.d(TAG, String.valueOf(mRGB[2]));


                        // TODO: Update UI
                        //  LED Icon

                        lastCharacteristicRequested = REQUESTED_NOTHING;

                        // Now that we're done, lets start streaming battery info
                        uBristleBot.setCharacteristicNotification(cBattery, true);
                        break;
                    case REQUESTED_NOTHING:
                        // Wat.
                    default:
                        Log.d(TAG, "Somehow, lastCharacteristicRequested got borked.");
                }

                Log.i(TAG, "Data: " + new String(intent.getByteArrayExtra(uBristleBotService.EXTRA_DATA)));
            } else if (uBristleBotService.ACTION_DATA_UPDATED.equals(action)) {
                // Battery information was updated
                // TODO: Update UI

                Log.i(TAG, "Battery: " + String.valueOf(intent.getByteArrayExtra(uBristleBotService.EXTRA_DATA)[0] & 0xFF) + "%");
            }*/
        }
    };


    //
    // Activity Life Cycle
    //
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_control_ui);

        // Connect to uBristleBot Service
        Intent uBristleBotServiceIntent = new Intent(this, uBristleBotService.class);
        bindService(uBristleBotServiceIntent, mServiceConnection, BIND_AUTO_CREATE);


        /*// Setup Immersive View
        View decorView = enterImmersiveView();
        decorView.setOnSystemUiVisibilityChangeListener(new View.OnSystemUiVisibilityChangeListener() {
            @Override
            public void onSystemUiVisibilityChange(int visibility) {
                enterImmersiveView();
            }
        });*/

        // Setup UI actions
        leftSeekbar = (SeekBar) findViewById(R.id.motor_left);
        leftSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // TODO
                //uBristleBot.setMotorLeft(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Reset Seekbar to 0%
                seekBar.setProgress(0);
            }
        });

        rightSeekbar = (SeekBar) findViewById(R.id.motor_right);
        rightSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // TODO
                //uBristleBot.setMotorRight(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Reset Seekbar to 0%
                seekBar.setProgress(0);
            }
        });






        ledIcon = findViewById(R.id.led_icon);
        ledIcon.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                mDialog_Settings.show();
                return true;
            }
        });


        // Setup Settings Dialog`
        mDialog_View = this.getLayoutInflater().inflate(R.layout.dialog_device_settings, null);
        mDialog_Settings = new AlertDialog.Builder(this)
                .setView(mDialog_View)
                .setPositiveButton(R.string.set, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        // TODO
                        //uBristleBot.setName();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .create();

        mDialog_Text_DeviceName = (TextView) mDialog_View.findViewById(R.id.text_device_name);

        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(mDialog_View.getContext(),
                R.array.led_colors, android.R.layout.simple_spinner_dropdown_item);
        mDialog_ColorChooser = (Spinner) mDialog_View.findViewById(R.id.color_dropdown);
        mDialog_ColorChooser.setAdapter(adapter);


        //getActionBar().setTitle();
        //getActionBar().setDisplayHomeAsUpEnabled(true);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Get updates from Service
        registerReceiver(mUpdateReceiver, makeUpdateIntentFilter());
    }

    @Override
    protected void onPause() {
        super.onPause();

        unregisterReceiver(mUpdateReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        unbindService(mServiceConnection);
        uBristleBot = null;
    }




    /*private View enterImmersiveView() {
        View mDecorView = getWindow().getDecorView();
        mDecorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        return mDecorView;
*/
    private static IntentFilter makeUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(uBristleBotService.ACTION_DEVICE_DISCONNECTED);
        return intentFilter;
    }
}
