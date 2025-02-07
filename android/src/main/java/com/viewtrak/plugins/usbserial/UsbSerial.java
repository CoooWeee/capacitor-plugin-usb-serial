package com.viewtrak.plugins.usbserial;

import static android.content.Context.RECEIVER_EXPORTED;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;

//import com.google.common.util.concurrent.RateLimiter;
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver;
import com.hoho.android.usbserial.driver.Ch34xSerialDriver;
import com.hoho.android.usbserial.driver.Cp21xxSerialDriver;
import com.hoho.android.usbserial.driver.FtdiSerialDriver;
import com.hoho.android.usbserial.driver.ProbeTable;
import com.hoho.android.usbserial.driver.ProlificSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;
import com.viewtrak.plugins.usbserial.Utils.*;

import org.json.JSONArray;

import java.io.IOException;
import java.lang.Error;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class UsbSerial implements SerialInputOutputManager.Listener {
    private final Context context;
    // call that will be used to send back usb device attached/detached event
    private final Callback callback;

    // activity reference from UsbSerialPlugin
//    private AppCompatActivity mActivity;
    // call that will have data to open connection
//    private PluginCall openSerialCall;

    // usb permission tag name
    public static final String USB_PERMISSION = "com.viewtrak.plugins.usbserial.USB_PERMISSION";
    private static final int WRITE_WAIT_MILLIS = 2000;
    private static final int READ_WAIT_MILLIS = 2000;

    private enum UsbPermission {Unknown, Requested, Granted, Denied}

    // logging tag
//    private final String TAG = UsbSerial.class.getSimpleName();

    //    private boolean sleepOnPause;
    // I/O manager to handle new incoming serial data
    private SerialInputOutputManager usbIoManager;
    // Default Usb permission state
    private UsbPermission usbPermission = UsbPermission.Unknown;
    // The serial port that will be used in this plugin
    private UsbSerialPort usbSerialPort;
    // Usb serial port connection status
//    private boolean connected = false;
    UsbDevice connectedDevice;
    // USB permission broadcastreceiver
    private final Handler mainLooper;
    String messageNMEA = "";


//    private RateLimiter throttle = RateLimiter.create(1.0);

    public UsbSerial(Context context, Callback callback) {
        super();
        this.context = context;
        this.callback = callback;

        mainLooper = new Handler(Looper.getMainLooper());

        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                    UsbDevice usbDevice = (UsbDevice) intent.getParcelableExtra("device");
                    callback.usbDeviceAttached(usbDevice);
                }
            }
        }, new IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED));

        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                    UsbDevice usbDevice = (UsbDevice) intent.getParcelableExtra("device");
                    callback.usbDeviceDetached(usbDevice);
                }
            }
        }, new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED));
    }

    @Override
    public void onNewData(byte[] data) {
        mainLooper.post(() -> updateReceivedData(data));
    }

    @Override
    public void onRunError(Exception e) {
        mainLooper.post(() -> {
            updateReadDataError(e);
            closeSerial();
        });
    }

    void setConnectedDevice(UsbDevice connectedDevice) {
        if (connectedDevice != this.connectedDevice) {
            this.connectedDevice = connectedDevice;
            this.callback.connected(connectedDevice);
        }
    }

    public void closeSerial() {
        setConnectedDevice(null);
        if (usbIoManager != null) {
            usbIoManager.setListener(null);
            usbIoManager.stop();
            usbIoManager = null;
        }
        usbPermission = UsbPermission.Unknown;
        try {
            if (usbSerialPort != null) {
                usbSerialPort.close();
            }
        } catch (IOException ignored) {
        }
        usbSerialPort = null;
    }

    public void openSerial(UsbSerialOptions settings) {
        try {
            closeSerial();

            // Sleep On Pause defaults to true
//            this.sleepOnPause = openSerialCall.hasOption("sleepOnPause") ? openSerialCall.getBoolean("sleepOnPause") : true;

            UsbDevice device = null;
            UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            for (UsbDevice v : usbManager.getDeviceList().values()) {
                if (v.getDeviceId() == settings.deviceId)
                    device = v;
            }
            if (device == null) {
                throw new Error("connection failed: device not found", new Throwable("connectionFailed:DeviceNotFound"));
            }
            UsbSerialDriver driver = getProper().probeDevice(device);
            if (driver == null) {
                // tyring custom
                driver = getDriverClass(device);
            }
            if (driver == null) {
                throw new Error("connection failed: no driver for device", new Throwable("connectionFailed:NoDriverForDevice"));
            }
            if (driver.getPorts().size() < settings.portNum) {
                throw new Error("connection failed: not enough ports at device", new Throwable("connectionFailed:NoAvailablePorts"));
            }
            usbSerialPort = driver.getPorts().get(settings.portNum);
            UsbDeviceConnection usbConnection = usbManager.openDevice(driver.getDevice());
            if (usbConnection == null && usbPermission == UsbPermission.Unknown && !usbManager.hasPermission(driver.getDevice())) {
                usbPermission = UsbPermission.Requested;
                PendingIntent usbPermissionIntent = PendingIntent.getBroadcast(context, 0, new Intent(USB_PERMISSION), PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                context.registerReceiver(new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        String action = intent.getAction();
                        if (USB_PERMISSION.equals(action)) {
                            usbPermission = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                                    ? UsbPermission.Granted : UsbPermission.Denied;
                            context.unregisterReceiver(this);
                            openSerial(settings);
                        }
                    }
                }, new IntentFilter(USB_PERMISSION), RECEIVER_EXPORTED);
                usbManager.requestPermission(driver.getDevice(), usbPermissionIntent);
                return;
            }
            if (usbConnection == null) {
                if (!usbManager.hasPermission(driver.getDevice())) {
                    throw new Error("connection failed: permission denied", new Throwable("connectionFailed:UsbConnectionPermissionDenied"));
                } else {
                    throw new Error("connection failed: Serial open failed", new Throwable("connectionFailed:SerialOpenFailed"));
                }
            }
            usbSerialPort.open(usbConnection);
            usbSerialPort.setParameters(settings.baudRate, settings.dataBits, settings.stopBits, settings.parity);
            if (settings.dtr) usbSerialPort.setDTR(true);
            if (settings.rts) usbSerialPort.setRTS(true);
            usbIoManager = new SerialInputOutputManager(usbSerialPort, this);
            usbIoManager.start();
//            connected = true;
            setConnectedDevice(device);
        } catch (Exception exception) {
            closeSerial();
            throw new Error(exception.getMessage(), exception.getCause());
        }
    }

    String readSerial() {
        if (connectedDevice == null) {
            throw new Error("not connected", new Throwable("NOT_CONNECTED"));
        }
        try {
            byte[] buffer = new byte[8192];
            int len = usbSerialPort.read(buffer, READ_WAIT_MILLIS);
//            str.concat("\n");
            return HexDump.toHexString(Arrays.copyOf(buffer, len));
        } catch (IOException e) {
            // when using read with timeout, USB bulkTransfer returns -1 on timeout _and_ errors
            // like connection loss, so there is typically no exception thrown here on error
            closeSerial();
            throw new Error("connection lost: " + e.getMessage(), e.getCause());
        }
    }

    void writeSerial(String str) {
        if (connectedDevice == null) {
            throw new Error("not connected", new Throwable("NOT_CONNECTED"));
        }
        if (str.length() == 0) {
            throw new Error("can't send empty string to device", new Throwable("EMPTY_STRING"));
        }
        try {
            byte[] data = (str + "\r\n").getBytes();
            usbSerialPort.write(data, WRITE_WAIT_MILLIS);
        } catch (Exception e) {
            closeSerial();
            throw new Error("connection lost: " + e.getMessage(), e.getCause());
        }
    }


//    void onResume() {
//        if (sleepOnPause) {
//            if (usbPermission == UsbPermission.Unknown || usbPermission == UsbPermission.Granted)
//                mainLooper.post(() -> {
//                    openSerial(this.openSerialCall);
//                });
//        }
//    }
//
//    void onPause() {
//        if (connected && sleepOnPause) {
//            disconnect();
//        }
//    }


    private void updateReceivedData(byte[] data) {
        try {
            messageNMEA += new String(data);

            int eol = messageNMEA.indexOf(0x0a);
            if (-1 != eol) {
                String sentence = messageNMEA.substring(0, eol + 1);
                messageNMEA = messageNMEA.substring(eol + 1);

//                    Boolean allowed = throttle.tryAcquire();
//                    if (!allowed) {
//                        return;
//                    }

                callback.receivedData(sentence);
            } else if (messageNMEA.length() > 128) {
                throw new Exception("invalid NMEA string");
            }
        } catch (Exception exception) {
            updateReadDataError(exception);
        }
    }

    private void updateReadDataError(Exception exception) {
        callback.error(new Error(exception.getMessage(), exception.getCause()));
    }

    UsbSerialDriver getDriverClass(final UsbDevice usbDevice) {
        Class<? extends UsbSerialDriver> driverClass = null;

        final int vid = usbDevice.getVendorId();
        final int pid = usbDevice.getProductId();

        if (vid == 1027) {
            switch (pid) {
                case 24577:
                case 24592:
                case 24593:
                case 24596:
                case 24597:
                    driverClass = FtdiSerialDriver.class;
            }
        } else if (vid == 4292) {
            switch (pid) {
                case 60000:
                case 60016:
                case 60017:
                    driverClass = Cp21xxSerialDriver.class;
            }
        } else if (vid == 1659) {
            switch (pid) {
                case 8963:
                case 9123:
                case 9139:
                case 9155:
                case 9171:
                case 9187:
                case 9203:
                    driverClass = ProlificSerialDriver.class;
            }
        } else if (vid == 6790) {
            switch (pid) {
                case 21795:
                case 29987:
                    driverClass = Ch34xSerialDriver.class;
            }
        } else {
            if (vid == 9025 || vid == 5446 || vid == 3725
                    || (vid == 5824 && pid == 1155)
                    || (vid == 1003 && pid == 8260)
                    || (vid == 7855 && pid == 4)
                    || (vid == 3368 && pid == 516)
                    || (vid == 1155 && pid == 22336)
            )
                driverClass = CdcAcmSerialDriver.class;
        }

        if (driverClass != null) {
            final UsbSerialDriver driver;
            try {
                final Constructor<? extends UsbSerialDriver> ctor =
                        driverClass.getConstructor(UsbDevice.class);
                driver = ctor.newInstance(usbDevice);
            } catch (NoSuchMethodException | IllegalArgumentException | InstantiationException |
                    IllegalAccessException | InvocationTargetException e) {
                throw new RuntimeException(e);
            }
            return driver;
        }

        return null;
    }

    private UsbSerialProber getProper() {
        ProbeTable customTable = UsbSerialProber.getDefaultProbeTable();

        // 0x0403 / 0x60??: FTDI
        customTable.addProduct(1027, 24577, FtdiSerialDriver.class); // 0x6001: FT232R
        customTable.addProduct(1027, 24592, FtdiSerialDriver.class); // 0x6010: FT2232H
        customTable.addProduct(1027, 24593, FtdiSerialDriver.class); // 0x6011: FT4232H
        customTable.addProduct(1027, 24596, FtdiSerialDriver.class); // 0x6014: FT232H
        customTable.addProduct(1027, 24597, FtdiSerialDriver.class); // 0x6015: FT230X, FT231X, FT234XD

        // 0x10C4 / 0xEA??: Silabs CP210x
        customTable.addProduct(4292, 60000, Cp21xxSerialDriver.class); // 0xea60: CP2102 and other CP210x single port devices
        customTable.addProduct(4292, 60016, Cp21xxSerialDriver.class); // 0xea70: CP2105
        customTable.addProduct(4292, 60017, Cp21xxSerialDriver.class); // 0xea71: CP2108

        // 0x067B / 0x23?3: Prolific PL2303x
        customTable.addProduct(1659, 8963, ProlificSerialDriver.class); // 0x2303: PL2303HX, HXD, TA, ...
        customTable.addProduct(1659, 9123, ProlificSerialDriver.class); // 0x23a3: PL2303GC
        customTable.addProduct(1659, 9139, ProlificSerialDriver.class); // 0x23b3: PL2303GB
        customTable.addProduct(1659, 9155, ProlificSerialDriver.class); // 0x23c3: PL2303GT
        customTable.addProduct(1659, 9171, ProlificSerialDriver.class); // 0x23d3: PL2303GL
        customTable.addProduct(1659, 9187, ProlificSerialDriver.class); // 0x23e3: PL2303GE
        customTable.addProduct(1659, 9203, ProlificSerialDriver.class); // 0x23f3: PL2303GS

        // 0x1a86 / 0x?523: Qinheng CH34x
        customTable.addProduct(6790, 21795, Ch34xSerialDriver.class); // 0x5523: CH341A
        customTable.addProduct(6790, 29987, Ch34xSerialDriver.class); // 0x7523: CH340

        // CDC driver
        // customTable.addProduct(9025,      , driver)  // 0x2341 / ......: Arduino
        customTable.addProduct(5824, 1155, CdcAcmSerialDriver.class); // 0x16C0 / 0x0483: Teensyduino
        customTable.addProduct(1003, 8260, CdcAcmSerialDriver.class); // 0x03EB / 0x2044: Atmel Lufa
        customTable.addProduct(7855, 4, CdcAcmSerialDriver.class); // 0x1eaf / 0x0004: Leaflabs Maple
        customTable.addProduct(3368, 516, CdcAcmSerialDriver.class); // 0x0d28 / 0x0204: ARM mbed
        customTable.addProduct(1155, 22336, CdcAcmSerialDriver.class); // 0x0483 / 0x5740: ST CDC

        return new UsbSerialProber(customTable);
    }

    public JSONArray devices() {
        List<DeviceItem> listItems = new ArrayList<>();
        UsbSerialProber usbProper = getProper();
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            UsbSerialDriver driver = usbProper.probeDevice(device);
            if (driver != null) {
                for (int port = 0; port < driver.getPorts().size(); port++)
                    listItems.add(new DeviceItem(device, port, driver));
            } else {
                listItems.add(new DeviceItem(device, 0, getDriverClass(device)));
            }
        }
        return  Utils.deviceListToJsonConvert(listItems);
    }
}
