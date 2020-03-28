package com.hongyi.arboard.sample;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Iterator;

public class MainActivity extends AppCompatActivity {
    private static final String ACTION_DEVICE_PERMISSION = "com.linc.USB_PERMISSION";
    private String TAG = "ARBoard_Sample";

    private UsbManager mUsbManager = null;
    private PendingIntent mPermissionIntent;
    private UsbEndpoint mUsbEndpointIn;
    private UsbEndpoint mUsbEndpointOut;
    private UsbInterface mUsbInterface;
    private UsbDeviceConnection mUsbDeviceConnection;
    private boolean isReading = false;


    private class MyHandler extends Handler {
        private final WeakReference<MainActivity> mActivity;
        public MyHandler(MainActivity activity) {
            mActivity = new WeakReference<>(activity);
        }

        @Override
        public void handleMessage(Message msg) {
            MainActivity activity = mActivity.get();
            if (activity != null) {
                String[] data = (String[])(msg.obj);
                if (msg.what == 0) {
                    ((TextView) (findViewById(R.id.acc_data))).setText(data[0]);
                    ((TextView) (findViewById(R.id.gyro_data))).setText(data[1]);
                    ((TextView) (findViewById(R.id.mag_data))).setText(data[2]);
                }
            }
        }
    }
    private final MyHandler mHandler = new MyHandler(this);

    // USB device permission receiver
    private final BroadcastReceiver mUsbPermissionReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "onReceive: " + intent);
        String action = intent.getAction();
        if (ACTION_DEVICE_PERMISSION.equals(action)) {
            synchronized (this) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    if (device != null) {
                        initDevice(device);
                    }
                }
            }
        }
        }
    };

    // USB "Insert & remove" event receiver
    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        Log.d(TAG, "onReceive: " + action);

        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {   // Insert
            searchUsb();
        } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {  // Remove
            closeUsbService();
        }
        }
    };

    // Search USB device
    private void searchUsb() {
        mUsbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        assert mUsbManager != null;

        HashMap<String, UsbDevice> devices = mUsbManager.getDeviceList();

        Iterator<UsbDevice> deviceIterator = devices.values().iterator();
        while(deviceIterator.hasNext()){
            UsbDevice device = deviceIterator.next();
            int vid = device.getVendorId();
            int pid = device.getProductId();

            if (pid == 0x5750 && vid == 0x483) {
                Toast.makeText(this, "AR device detected", Toast.LENGTH_LONG).show();
                if (mUsbManager.hasPermission(device)) {
                    initDevice(device);
                    return;
                } else {
                    mUsbManager.requestPermission(device, mPermissionIntent);
                }
            }
        }
    }

    // Init device
    private void initDevice(UsbDevice device) {
        UsbInterface usbInterface = device.getInterface(0);

        for(int i = 0; i < usbInterface.getEndpointCount(); i++) {
            UsbEndpoint ep = usbInterface.getEndpoint(i);
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_INT) {
                if (ep.getDirection() == UsbConstants.USB_DIR_IN && ep.getAddress() == 0x81) {
                    mUsbEndpointIn = ep;
                } else if (ep.getDirection() == UsbConstants.USB_DIR_OUT && ep.getAddress() == 0x01){
                    mUsbEndpointOut = ep;
                }
            }
        }

        // Start reading thread
        if ((null == mUsbEndpointIn)) {
            mUsbInterface = null;
        } else {
            mUsbInterface = usbInterface;
            mUsbDeviceConnection = mUsbManager.openDevice(device);
            mUsbDeviceConnection.claimInterface(mUsbInterface, true);
            startReading();
        }
    }

    // Send command to mcu via usb bulkTransfer
    private boolean sendUsbCommand(int command, int value) {
        if (null == mUsbDeviceConnection || null == mUsbEndpointOut) {
            return false;
        }

        byte[] bytes = new byte[64];
        bytes[0] = 0x66;
        bytes[1] = (byte) command;
        bytes[2] = (byte) value;

        Log.d(TAG, "Try to sendUsbCommand: " + command + ", value: " + value);
        return mUsbDeviceConnection.bulkTransfer(mUsbEndpointOut, bytes, 64, 1000) > 0;
    }

    static int byte2int(byte[] b, int index) {
        int l;
        l = b[index];
        l &= 0xff;
        l |= ((long) b[index + 1] << 8);
        l &= 0xffff;
        l |= ((long) b[index + 2] << 16);
        l &= 0xffffff;
        l |= ((long) b[index + 3] << 24);
        return l;
    }

    float byte2float(byte[] b, int index) {
        return Float.intBitsToFloat(byte2int(b, index));
    }

    // Start the reading thread to retrieve sensors(acc, gyro, mag)
    private void startReading() {

        isReading = true;
        Thread readingThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (isReading) {
                        synchronized (this) {
                            int ret;

                            // Fetch the data here
                            if(mUsbDeviceConnection != null && mUsbEndpointIn != null){
                                byte[] bytes = new byte[mUsbEndpointIn.getMaxPacketSize()];
                                ret = mUsbDeviceConnection.bulkTransfer(mUsbEndpointIn, bytes, bytes.length, 1000);

                                if (ret >= 64) {
                                    if(bytes[1] == (byte)0xc8){
                                        // USB_COMMAND_ACK
                                    }
                                    else{
                                        // DATA Frame, bytes[1] should be 0x65
                                        float[] accSensorData = new float[3];
                                        float[] gyroSensorData = new float[3];
                                        float[] magSensorData = new float[3];

                                        // ACC
                                        accSensorData[0] = byte2float(bytes, 4);
                                        accSensorData[1] = byte2float(bytes, 8);
                                        accSensorData[2] = byte2float(bytes, 12);

                                        // Gyro
                                        gyroSensorData[0] = byte2float(bytes, 16) * (float)Math.PI / 180.0f;
                                        gyroSensorData[1] = byte2float(bytes, 20) * (float)Math.PI / 180.0f;
                                        gyroSensorData[2] = byte2float(bytes, 24) * (float)Math.PI / 180.0f;

                                        // Mag, not used so far
                                        magSensorData[0] = (float) (byte2float(bytes, 28) * Math.PI / 180.0f);
                                        magSensorData[1] = (float) (byte2float(bytes, 32) * Math.PI / 180.0f);
                                        magSensorData[2] = (float) (byte2float(bytes, 36) * Math.PI / 180.0f);

                                        String[] msgData = new String[3];
                                        msgData[0] = String.format("%.2f", accSensorData[0]) + ", " + String.format("%.2f", accSensorData[1]) + ", " + String.format("%.2f", accSensorData[2]);
                                        msgData[1] = String.format("%.2f", gyroSensorData[0]) + ", " + String.format("%.2f", gyroSensorData[1]) + ", " + String.format("%.2f", gyroSensorData[2]);
                                        msgData[2] = String.format("%.2f", magSensorData[0]) + ", " + String.format("%.2f", magSensorData[1]) + ", " + String.format("%.2f", magSensorData[2]);

                                        Message msg = new Message();
                                        msg.obj = msgData;
                                        msg.what = 0;
                                        mHandler.sendMessage(msg);
                                    }
                                }
                                else{
                                    Log.d(TAG, "No data from USB device.");
                                }
                            }
                            else{
                                isReading = false;
                                Log.d(TAG, "USB connection is not available.");
                            }
                        }
                    }
                }
            });

        Log.d(TAG, "Start the reading thread here to retrieve sensors(acc, gyro, mag)");
        readingThread.start();
    }

    // Close USB device
    private void closeUsbService() {
        if (isReading) {
            isReading = false;
        }

        mUsbEndpointIn = null;
        mUsbEndpointOut = null;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Register "insert & remove" receiver
        IntentFilter usbFilter = new IntentFilter();
        usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(mUsbReceiver, usbFilter);


        // Register permission receiver
        mPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_DEVICE_PERMISSION), 0);
        IntentFilter permissionFilter = new IntentFilter(ACTION_DEVICE_PERMISSION);
        registerReceiver(mUsbPermissionReceiver, permissionFilter);

        searchUsb();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mUsbPermissionReceiver);
        unregisterReceiver(mUsbReceiver);
    }

    public void onEnableUSBSensorsClick(View view) {
        if(!sendUsbCommand(0x01, 0)){
            searchUsb();
        }
    }

    public void onDisableUSBSensorsClick(View view) {
        if(!sendUsbCommand(0x02, 0)){
            searchUsb();
        }
    }

}
