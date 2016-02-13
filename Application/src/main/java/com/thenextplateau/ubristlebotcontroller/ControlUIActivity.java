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
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

/**
 * TODO: Fill this out...
 */
public class ControlUIActivity extends Activity {
    private static final String TAG = ControlUIActivity.class.getSimpleName();

    private uBristleBotService uBristleBot;

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
            if (uBristleBot.initialize() != uBristleBotService.INIT_ERROR_NONE) {
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


            if (uBristleBotService.ACTION_DEVICE_RSSI_CHANGED.equals(action)) {

            } else if (uBristleBotService.ACTION_DEVICE_BATTERY_CHANGED.equals(action)) {
                Log.i(TAG, "Battery: " + String.valueOf(intent.getIntExtra(uBristleBotService.DEVICE_BATTERY, -1)) + "%");
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

        // Setup UI actions
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

        View ledIcon = findViewById(R.id.led_icon);
        ledIcon.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                mDialog_Settings.show();
                return true;
            }
        });


        // Setup Settings Dialog
        ViewGroup mParent = (ViewGroup) findViewById(R.id.controllerUIContainer);
        mDialog_View = this.getLayoutInflater().inflate(R.layout.dialog_device_settings, mParent, false);
        mDialog_Settings = new AlertDialog.Builder(this)
                .setView(mDialog_View)
                .setPositiveButton(R.string.set, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        // TODO
                        //uBristleBot.setName();
                        //uBristleBot.setColor();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .create();

        mDialog_Text_DeviceName = (TextView) mDialog_View.findViewById(R.id.text_device_name);

        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(mDialog_View.getContext(),
                R.array.led_colors, android.R.layout.simple_spinner_dropdown_item);
        mDialog_ColorChooser = (Spinner) mDialog_View.findViewById(R.id.color_dropdown);
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
