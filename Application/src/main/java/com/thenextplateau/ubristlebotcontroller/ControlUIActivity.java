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
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

/*
 Activity Flow:
 - Connect to uBristleBot Service
   - Failed? Peace out...

 - On Seekbar change, Set Motor Percentage
 - On Icon Long Press, Open Settings Dialog

 - On Settings Dialog confirmation, set name and color, and disconnect
 */
public class ControlUIActivity extends Activity {
    private static final String TAG = ControlUIActivity.class.getSimpleName();

    private uBristleBotService uBristleBot;

    // Dialogs
    private static AlertDialog mDialog_Settings;

    // Text Views
    private static TextView batteryView;
    private static TextView rssiView;

    //
    // Handle uBristleBotService connection
    //
    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            uBristleBot = ((uBristleBotService.LocalBinder) service).getService();
            if (uBristleBot.initialize() != uBristleBotService.INIT_ERROR_NONE) {
                Log.e(TAG, "Unable to initialize uBristleBotService");

                onBackPressed();
                return;
            }

            if (! uBristleBot.isConnected()) {
                Log.e(TAG, "uBristleBotService not connected to device");

                onBackPressed();
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


            if (uBristleBotService.ACTION_DEVICE_RSSI_CHANGED.equals(action)) {
                rssiView.setText(String.valueOf(intent.getIntExtra(uBristleBotService.DEVICE_RSSI, -999)) + " dBm");
            } else if (uBristleBotService.ACTION_DEVICE_BATTERY_CHANGED.equals(action)) {
                batteryView.setText(String.valueOf(intent.getIntExtra(uBristleBotService.DEVICE_BATTERY, -1)) + "%");
            } else if (uBristleBotService.ACTION_DEVICE_DISCONNECTED.equals(action)) {
                Log.e(TAG, "Connection lost");

                onBackPressed();
            }
        }
    };

    private static IntentFilter makeUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(uBristleBotService.ACTION_DEVICE_DISCONNECTED);
        intentFilter.addAction(uBristleBotService.ACTION_DEVICE_RSSI_CHANGED);
        intentFilter.addAction(uBristleBotService.ACTION_DEVICE_BATTERY_CHANGED);
        return intentFilter;
    }


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

        //
        // Setup UI actions
        //

        // Motor Control
        SeekBar leftSeekbar = (SeekBar) findViewById(R.id.motor_left);
        leftSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                Log.i(TAG, "Left Motor: " + String.valueOf(progress) + "%");
                uBristleBot.setLeftMotor(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Reset Seekbar
                seekBar.setProgress(0);
            }
        });

        SeekBar rightSeekbar = (SeekBar) findViewById(R.id.motor_right);
        rightSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                Log.i(TAG, "Right Motor: " + String.valueOf(progress) + "%");
                uBristleBot.setRightMotor(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Reset Seekbar
                seekBar.setProgress(0);
            }
        });

        // Battery Indicator
        batteryView = (TextView) findViewById(R.id.batteryTextView);

        // RSSI Indicator
        rssiView = (TextView) findViewById(R.id.rssiTextView);

        // Settings Dialog
        ImageView mSettingsIcon = (ImageView) findViewById(R.id.settingsIcon);
        mSettingsIcon.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                mDialog_Settings.show();
                return true;
            }
        });

        ViewGroup mParent = (ViewGroup) findViewById(R.id.controllerUIContainer);
        View mDialog_View = this.getLayoutInflater().inflate(R.layout.dialog_device_settings, mParent, false);
        mDialog_Settings = new AlertDialog.Builder(this)
                .setView(mDialog_View)
                .setPositiveButton(R.string.set, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        TextView deviceName = (TextView) ((AlertDialog) dialog).findViewById(R.id.dialog_text_device_name);
                        Spinner colorDropdown = (Spinner) ((AlertDialog) dialog).findViewById(R.id.dialog_spinner_color_dropdown);

                        // Set name
                        if (deviceName.getText().equals("")) {
                            return;
                        }
                        uBristleBot.setName(String.valueOf(deviceName.getText()));

                        // Set color
                        switch (colorDropdown.getSelectedItemPosition()) {
                            case 0:
                                Log.i(TAG, "Setting color to WHITE");
                                uBristleBot.setColor(255, 255, 255);
                                break;
                            case 1:
                                Log.i(TAG, "Setting color to RED");
                                uBristleBot.setColor(255, 0, 0);
                                break;
                            case 2:
                                Log.i(TAG, "Setting color to GREEN");
                                uBristleBot.setColor(0, 255, 0);
                                break;
                            case 3:
                                Log.i(TAG, "Setting color to BLUE");
                                uBristleBot.setColor(0, 0, 255);
                                break;
                            case 4:
                                Log.i(TAG, "Setting color to YELLOW");
                                uBristleBot.setColor(255, 255, 0);
                                break;
                            case 5:
                                Log.i(TAG, "Setting color to MAGENTA");
                                uBristleBot.setColor(255, 0, 255);
                                break;
                            case 6:
                                Log.i(TAG, "Setting color to CYAN");
                                uBristleBot.setColor(0, 255, 255);
                                break;
                        }

                        // Save settings and disconnect
                        uBristleBot.saveSettingsAndDisconnect();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .create();

        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(mDialog_View.getContext(),
                R.array.led_colors, android.R.layout.simple_spinner_dropdown_item);
        Spinner mDialog_ColorChooser = (Spinner) mDialog_View.findViewById(R.id.dialog_spinner_color_dropdown);
        mDialog_ColorChooser.setAdapter(adapter);

        //getActionBar().setTitle(uBristleBot.getName());
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
}
