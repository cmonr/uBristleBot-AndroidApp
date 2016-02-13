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
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.support.design.widget.Snackbar;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
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

    private static final int REQUEST_ENABLE_BT = 1;


    //
    // Manage uBristleBotService connection
    //
    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            uBristleBot = ((uBristleBotService.LocalBinder) service).getService();
            if (uBristleBot.initialize() != uBristleBotService.INIT_ERROR_NONE) {
                Log.e(TAG, "Unable to initialize uBristleBotService");

                // TODO: Display useful message to user

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
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);

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
                Snackbar snackbar = Snackbar.make(
                        mRefreshLayout,
                        "WE CONNECTED!",
                        Snackbar.LENGTH_SHORT);
                snackbar.show();

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

        mDeviceList = (ListView) findViewById(R.id.deviceList);
        mDeviceList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (!uBristleBot.isConnecting()) {
                    // Connect to device selected
                    uBristleBot.connectTo((String) mLeDeviceListAdapter.getItem(position));
                }
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
        if (requestCode == REQUEST_ENABLE_BT && resultCode == Activity.RESULT_CANCELED) {
            finish();
            return;
        } else {
            // Start scanning for devices
            startDeviceScan(true);
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_device_scan_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_rescan:
                Log.i(TAG, "Rescan Menu Item selected");

                // Start scanning for devices
                startDeviceScan(true);

                return true;
        }

        return super.onOptionsItemSelected(item);
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

            mParent = (ViewGroup) findViewById(R.id.deviceScanContainer);
        }

        // Insert new device into list, with strongest RSSI at the top
        public void addDevice(String name, String address, int rssi) {
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