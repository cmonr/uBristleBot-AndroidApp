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

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.design.widget.Snackbar;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;


import java.util.ArrayList;

/*
 Activity Flow:
 - Enable Bluetooth
 - Connect to uBristleBot Service
   - Failed? Peace out...
 - Start BLE Scan
   - uBristleBot.scanForBots(boolean, timeout)
 - Populate list while devices are found
 - Item Clicked -> Connect
   - uBtistleBot.connectTo(device -OR- address)
   - On success, check Services for valid target
 - On success, switch to ControlUIActivity
 - On failure, rescan, without clearing list

 - On pull down gesture, Clear List and Rescan
 - On Icon press, Clear List and Rescan
 */
public class DeviceScanActivity extends AppCompatActivity {
    private static final String TAG = DeviceScanActivity.class.getSimpleName();

    private uBristleBotService uBristleBot;

    private SwipeRefreshLayout mRefreshLayout;
    private ListView mDeviceList;
    private LeDeviceListAdapter mLeDeviceListAdapter;

    private ProgressDialog mConnectionStatusDialog;

    private static final int REQUEST_ENABLE_BLUETOOTH = 1;
    private static final int PERMISSION_REQUEST_COARSE_LOCATION = 2;



    //
    // Manage uBristleBotService connection
    //
    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            uBristleBot = ((uBristleBotService.LocalBinder) service).getService();
            if (uBristleBot.initialize() != uBristleBotService.INIT_ERROR_NONE) {
                Log.e(TAG, "Unable to initialize uBristleBotService");

                finish();
                return;
            }

            // Start scan on connection
            uBristleBot.scanForBots(true);
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            uBristleBot = null;

            Log.e(TAG, "Service was disconnected");
        }
    };

    // Capture events sent out by uBristleBotService
    //   BLE Not Enabled
    //   Device Found
    //   Connection Failed [General Failure, Service Match]
    //   Connecting
    //   Connected
    //   Disconnected (Just in case)
    private final BroadcastReceiver mUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            if (uBristleBotService.ACTION_BLUETOOTH_IS_DISABLED.equals(action)) {
                // Re-enable Bluetooth
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BLUETOOTH);

            } else if (uBristleBotService.ACTION_DEVICE_FOUND.equals(action)) {
                // Add to UI List
                mLeDeviceListAdapter.addDevice(
                        intent.getStringExtra(uBristleBotService.SCAN_RESULT_DEVICE_NAME),
                        intent.getStringExtra(uBristleBotService.SCAN_RESULT_DEVICE_ADDRESS),
                        intent.getIntExtra(uBristleBotService.SCAN_RESULT_DEVICE_RSSI, -999));

                // Update UI List
                mLeDeviceListAdapter.notifyDataSetChanged();
            } else if (uBristleBotService.ACTION_SCAN_COMPLETE.equals(action)) {
                // Indicate scan was completed/stopped
                mRefreshLayout.setRefreshing(false);

            }  else if (uBristleBotService.ACTION_CONNECT_FAILED.equals(action)) {
                Snackbar snackbar = Snackbar.make(
                        mRefreshLayout,
                        intent.getStringExtra(uBristleBotService.CONNECT_ERROR),
                        Snackbar.LENGTH_SHORT);
                snackbar.show();

            } else if (uBristleBotService.ACTION_CONNECTED.equals(action)) {
                mConnectionStatusDialog.dismiss();

                startDeviceScan(false);

                // Launch Control UI
                startActivity(new Intent(DeviceScanActivity.this, ControlUIActivity.class));

            }
        }
    };

    private static IntentFilter makeUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(uBristleBotService.ACTION_BLUETOOTH_IS_DISABLED);
        intentFilter.addAction(uBristleBotService.ACTION_DEVICE_FOUND);
        intentFilter.addAction(uBristleBotService.ACTION_SCAN_COMPLETE);
        intentFilter.addAction(uBristleBotService.ACTION_CONNECTING_COMPARING_SERVICES);
        intentFilter.addAction(uBristleBotService.ACTION_CONNECTING_READING_CHARACTERISTICS);
        intentFilter.addAction(uBristleBotService.ACTION_CONNECTED);
        intentFilter.addAction(uBristleBotService.ACTION_CONNECT_FAILED);
        return intentFilter;
    }


    //
    // Activity Life Cycle
    //
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_scan);


        // Android M requires additional permissions at runtime
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (this.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, PERMISSION_REQUEST_COARSE_LOCATION);
            }
        }


        // Connect to uBristleBot Service
        Intent uBristleBotServiceIntent = new Intent(this, uBristleBotService.class);
        bindService(uBristleBotServiceIntent, mServiceConnection, BIND_AUTO_CREATE);

        // Initalize UI Elements
        mRefreshLayout = (SwipeRefreshLayout) findViewById(R.id.swipeLayout);
        mRefreshLayout.setOnRefreshListener(
                new SwipeRefreshLayout.OnRefreshListener() {
                    @Override
                    public void onRefresh() {
                        Log.i(TAG, "onRefresh called from SwipeRefreshLayout");

                        // Start scanning for devices
                        startDeviceScan(true);
                    }
                }
        );
        // Workaround for Progress not being shown the first time
        mRefreshLayout.post(new Runnable() {
            @Override public void run() {
                mRefreshLayout.setRefreshing(true);
            }
        });


        mDeviceList = (ListView) findViewById(R.id.deviceList);
        mDeviceList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (!uBristleBot.isConnecting()) {
                    // Connect to device selected
                    uBristleBot.connectTo((String) mLeDeviceListAdapter.getItem(position));

                    // Show a status dialog
                    mConnectionStatusDialog.setMessage("Connecting...");
                    mConnectionStatusDialog.show();
                }
            }
        });

        // Setup Progress Dialog
        mConnectionStatusDialog = new ProgressDialog(this);
        mConnectionStatusDialog.setCancelable(true);
        mConnectionStatusDialog.setCanceledOnTouchOutside(true);
        mConnectionStatusDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialog) {
                // Stop connecting to device
                uBristleBot.disconnect();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();



        // Get updates from Service
        registerReceiver(mUpdateReceiver, makeUpdateIntentFilter());

        // Create new list
        mLeDeviceListAdapter = new LeDeviceListAdapter();
        mDeviceList.setAdapter(mLeDeviceListAdapter);

        // Start scanning for devices
        startDeviceScan(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(mUpdateReceiver);

        // Stop scanning for devices
        startDeviceScan(false);

        mConnectionStatusDialog.dismiss();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        startDeviceScan(false);

        if (uBristleBot != null) {
            uBristleBot.disconnect();
        }
        unbindService(mServiceConnection);
        uBristleBot = null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // User did not enable Bluetooth
        if (requestCode == REQUEST_ENABLE_BLUETOOTH && resultCode == Activity.RESULT_CANCELED) {
            finish();
            return;
        } else {
            // Start scanning for devices
            startDeviceScan(true);
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String permissions[],
                                           @NonNull int[] grantResults) {
        switch (requestCode) {
            case PERMISSION_REQUEST_COARSE_LOCATION: {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "coarse location permission granted");
                } else {
                    final AlertDialog.Builder builder = new AlertDialog.Builder(this);
                    builder.setTitle("Functionality limited");
                    builder.setMessage("Since location access has not been granted, this app will not be able to discover beacons when in the background.");
                    builder.setPositiveButton(android.R.string.ok, null);
                    builder.setOnDismissListener(new DialogInterface.OnDismissListener() {

                        @Override
                        public void onDismiss(DialogInterface dialog) {
                        }

                    });
                    builder.show();
                }
            }
        }
    }

    private void startDeviceScan(boolean startScan) {
        if (startScan) {
            if (uBristleBot != null) {
                // Enable refresh indicator for layout
                mRefreshLayout.setRefreshing(true);

                // Update UI List
                mLeDeviceListAdapter.clear();
                mLeDeviceListAdapter.notifyDataSetChanged();

                // Start new scan for devices
                uBristleBot.scanForBots(true);
            }
        } else {
            if (uBristleBot != null) {
                uBristleBot.scanForBots(false);
            }

            // Enable refresh indicator for layout
            mRefreshLayout.setRefreshing(false);
        }
    }



    //
    // List Adapter for holding discovered BLE Devices
    //
    private class LeDeviceListAdapter extends BaseAdapter {
        private ArrayList<String> mDeviceList_Name;
        private ArrayList<String> mDeviceList_Address;
        private ArrayList<Integer> mDeviceList_RSSI;
        private LayoutInflater mInflator;
        private ViewGroup mParent;

        public LeDeviceListAdapter() {
            super();

            mDeviceList_Name = new ArrayList<>();
            mDeviceList_Address = new ArrayList<>();
            mDeviceList_RSSI = new ArrayList<>();

            mInflator = DeviceScanActivity.this.getLayoutInflater();

            mParent = (ViewGroup) findViewById(R.id.deviceList);
        }

        // Insert new device into list, with strongest RSSI at the top
        public void addDevice(String name, String address, int rssi) {
            if (mDeviceList_Address.contains(address)) {
                // Update entry
                int index = mDeviceList_Address.indexOf(address);
                mDeviceList_Name.set(index, name);
                mDeviceList_RSSI.set(index, rssi);
            } else {
                // Add new entry
                int index = 0;
                for (; index < mDeviceList_RSSI.size(); index++) {
                    if (rssi >= mDeviceList_RSSI.get(index)) {
                        break;
                    }
                }

                mDeviceList_Name.add(index, name);
                mDeviceList_Address.add(index, address);
                mDeviceList_RSSI.add(index, rssi);
            }
        }

        public void clear() {
            mDeviceList_Name.clear();
            mDeviceList_Address.clear();
            mDeviceList_RSSI.clear();
        }

        @Override
        public Object getItem(int i) {
            return mDeviceList_Address.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public int getCount() {
            return mDeviceList_Name.size();
        }

        @Override
        public View getView(int i, View view, ViewGroup viewGroup) {
            ViewHolder viewHolder;

            if (view == null) {
                // Build new view from scratch
                view = mInflator.inflate(R.layout.list_item_ble_device, mParent, false);
                viewHolder = new ViewHolder();
                viewHolder.deviceAddress = (TextView) view.findViewById(R.id.device_address);
                viewHolder.deviceName = (TextView) view.findViewById(R.id.device_name);
                viewHolder.deviceRSSI = (TextView) view.findViewById(R.id.device_rssi);
                view.setTag(viewHolder);
            } else {
                // Retrieve existing view
                viewHolder = (ViewHolder) view.getTag();
            }

            // Populate View information
            final String deviceName= mDeviceList_Name.get(i);
            final String deviceAddr= mDeviceList_Address.get(i);
            final int deviceRSSI = mDeviceList_RSSI.get(i);
            if (deviceName != null && deviceName.length() > 0) {
                viewHolder.deviceName.setText(deviceName);
            } else {
                viewHolder.deviceName.setText(R.string.unknown_device);
            }
            viewHolder.deviceAddress.setText(deviceAddr);
            viewHolder.deviceRSSI.setText(String.valueOf(deviceRSSI) + " dBm");

            return view;
        }
    }

    static class ViewHolder {
        TextView deviceName;
        TextView deviceAddress;
        TextView deviceRSSI;
    }
}