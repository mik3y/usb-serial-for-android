package com.hoho.android.usbserial.util;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import com.hoho.android.usbserial.driver.CdcAcmSerialDriver;
import com.hoho.android.usbserial.driver.Ch34xSerialDriver;
import com.hoho.android.usbserial.driver.CommonUsbSerialPort;
import com.hoho.android.usbserial.driver.Cp21xxSerialDriver;
import com.hoho.android.usbserial.driver.FtdiSerialDriver;
import com.hoho.android.usbserial.driver.ProlificSerialDriver;
import com.hoho.android.usbserial.driver.UsbId;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.concurrent.Callable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class UsbWrapper implements SerialInputOutputManager.Listener {

    public final static int     USB_READ_WAIT = 500;
    public final static int     USB_WRITE_WAIT = 500;
    private static final String TAG = UsbWrapper.class.getSimpleName();

    public enum OpenCloseFlags { NO_IOMANAGER_THREAD, NO_IOMANAGER_START, NO_CONTROL_LINE_INIT, NO_DEVICE_CONNECTION };

    // constructor
    final Context context;
    public final UsbSerialDriver serialDriver;
    public final int devicePort;
    public final UsbSerialPort serialPort;
    // open
    public UsbDeviceConnection deviceConnection;
    public SerialInputOutputManager ioManager;
    // read
    final Deque<byte[]> readBuffer = new LinkedList<>();
    Exception readError;
    public boolean readBlock = false;
    long readTime = 0;

    // device properties
    public boolean isCp21xxRestrictedPort; // second port of Cp2105 has limited dataBits, stopBits, parity
    public boolean outputLinesSupported;
    public boolean inputLinesSupported;
    public boolean inputLinesConnected;
    public boolean inputLinesOnlyRtsCts;
    public int writePacketSize = -1;
    public int writeBufferSize = -1;

    public UsbWrapper(Context context, UsbSerialDriver serialDriver, int devicePort) {
        this.context = context;
        this.serialDriver = serialDriver;
        this.devicePort = devicePort;
        serialPort = serialDriver.getPorts().get(devicePort);
        CommonUsbSerialPort.DEBUG = true;
    }

    public void setUp() throws Exception {
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (!usbManager.hasPermission(serialDriver.getDevice())) {
            Uri notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            RingtoneManager.getRingtone(context, notification).play();

            Log.d(TAG,"USB permission ...");
            final Boolean[] granted = {null};
            BroadcastReceiver usbReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    granted[0] = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                }
            };
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_MUTABLE : 0;
            Intent intent = new Intent("com.android.example.USB_PERMISSION");
            intent.setPackage(context.getPackageName());
            PendingIntent permissionIntent = PendingIntent.getBroadcast(context, 0, intent, flags);
            IntentFilter filter = new IntentFilter("com.android.example.USB_PERMISSION");
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            usbManager.requestPermission(serialDriver.getDevice(), permissionIntent);
            for(int i=0; i<5000; i++) {
                if(granted[0] != null) break;
                Thread.sleep(1);
            }
            Log.d(TAG,"USB permission "+granted[0]);
            assertTrue("USB permission dialog not confirmed", granted[0] != null && granted[0]);
        }

        // extract some device properties:
        isCp21xxRestrictedPort = serialDriver instanceof Cp21xxSerialDriver && serialDriver.getPorts().size()==2 && serialPort.getPortNumber() == 1;
        // output lines are supported by all common drivers
        // input lines are supported by all common drivers except CDC
        if (serialDriver instanceof FtdiSerialDriver) {
            outputLinesSupported = true;
            inputLinesSupported = true;
            if(serialDriver.getDevice().getProductId() == UsbId.FTDI_FT2232H)
                inputLinesConnected = true; // I only have 74LS138 connected at FT2232, not at FT232
            if(serialDriver.getDevice().getProductId() == UsbId.FTDI_FT231X) {
                inputLinesConnected = true;
                inputLinesOnlyRtsCts = true; // I only test with FT230X that has only these 2 control lines. DTR is silently ignored
            }
        } else if (serialDriver instanceof Cp21xxSerialDriver) {
            outputLinesSupported = true;
            inputLinesSupported = true;
            if(serialDriver.getPorts().size() == 1)
                inputLinesConnected = true; // I only have 74LS138 connected at CP2102, not at CP2105
        } else if (serialDriver instanceof ProlificSerialDriver) {
            outputLinesSupported = true;
            inputLinesSupported = true;
            inputLinesConnected = true;
        } else if (serialDriver instanceof Ch34xSerialDriver) {
            outputLinesSupported = true;
            inputLinesSupported = true;
            if(serialDriver.getDevice().getProductId() == UsbId.QINHENG_CH340)
                inputLinesConnected = true;  // I only have 74LS138 connected at CH340, not connected at CH341A
        } else if (serialDriver instanceof CdcAcmSerialDriver) {
            outputLinesSupported = true;
        }

        if (serialDriver instanceof Cp21xxSerialDriver) {
            if (serialDriver.getPorts().size() == 1) { writePacketSize = 64; writeBufferSize = 576; }
            else if (serialPort.getPortNumber() == 0) { writePacketSize = 64; writeBufferSize = 320; }
            else { writePacketSize = 32; writeBufferSize = 128; }; //, 160}; // write buffer size detection is unreliable
        } else if (serialDriver instanceof Ch34xSerialDriver) {
            writePacketSize = 32; writeBufferSize = 64;
        } else if (serialDriver instanceof ProlificSerialDriver) {
            writePacketSize = 64; writeBufferSize = 256;
        } else if (serialDriver instanceof FtdiSerialDriver) {
            switch (serialDriver.getPorts().size()) {
                case 1: writePacketSize = 64; writeBufferSize = 128; break;
                case 2: writePacketSize = 512; writeBufferSize = 4096; break;
                case 4: writePacketSize = 512; writeBufferSize = 2048; break;
            }
        } else if (serialDriver instanceof CdcAcmSerialDriver) {
            writePacketSize = 64; writeBufferSize = 128;
        }

    }

    public void tearDown() {
        try {
            if (ioManager != null)
                read(0);
            else
                serialPort.purgeHwBuffers(true, true);
        } catch (Exception ignored) {
        }
        close();
        //usb.serialDriver = null;
    }

    public void close() {
        close(EnumSet.noneOf(OpenCloseFlags.class));
    }

    public void close(EnumSet<OpenCloseFlags> flags) {
        if (ioManager != null) {
            ioManager.setListener(null);
            ioManager.stop();
        }
        if (serialPort != null) {
            try {
                if(!flags.contains(OpenCloseFlags.NO_CONTROL_LINE_INIT)) {
                    serialPort.setDTR(false);
                    serialPort.setRTS(false);
                }
            } catch (Exception ignored) {
            }
            try {
                serialPort.close();
            } catch (Exception ignored) {
            }
            //usbSerialPort = null;
        }
        if(!flags.contains(OpenCloseFlags.NO_DEVICE_CONNECTION)) {
            deviceConnection = null; // closed in usbSerialPort.close()
        }
        if(ioManager != null) {
            for (int i = 0; i < 2000; i++) {
                if (SerialInputOutputManager.State.STOPPED == ioManager.getState()) break;
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            assertEquals(SerialInputOutputManager.State.STOPPED, ioManager.getState());
            ioManager = null;
        }
    }

    public void open() throws Exception {
        open(EnumSet.noneOf(OpenCloseFlags.class));
    }

    public void open(EnumSet<OpenCloseFlags> flags) throws Exception {
        if(!flags.contains(OpenCloseFlags.NO_DEVICE_CONNECTION)) {
            UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            deviceConnection = usbManager.openDevice(serialDriver.getDevice());
        }
        serialPort.open(deviceConnection);
        if(!flags.contains(OpenCloseFlags.NO_CONTROL_LINE_INIT)) {
            serialPort.setDTR(true);
            serialPort.setRTS(true);
        }
        if(!flags.contains(OpenCloseFlags.NO_IOMANAGER_THREAD)) {
            ioManager = new SerialInputOutputManager(serialPort, this);
            if(!flags.contains(OpenCloseFlags.NO_IOMANAGER_START))
                ioManager.start();
        }
        synchronized (readBuffer) {
            readBuffer.clear();
        }
        readError = null;
    }

    public void waitForIoManagerStarted() throws IOException {
        for (int i = 0; i < 100; i++) {
            if (SerialInputOutputManager.State.STOPPED != ioManager.getState()) return;
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        throw new IOException("IoManager not started");
    }

    public boolean hasIoManagerThread() {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().equals(SerialInputOutputManager.class.getSimpleName()))
                return true;
        }
        return false;
    }

    // wait full time
    public byte[] read() throws Exception {
        return read(-1, -1, -1);
    }
    public byte[] read(int expectedLength) throws Exception {
        return read(expectedLength, -1, -1);
    }
    public byte[] read(int expectedLength, int readBufferSize) throws Exception {
        return read(expectedLength, readBufferSize, -1);
    }
    public byte[] read(int expectedLength, int readBufferSize, int readWait) throws Exception {
        if(readWait == -1)
            readWait = USB_READ_WAIT;
        long end = System.currentTimeMillis() + readWait;
        ByteBuffer buf = ByteBuffer.allocate(16*1024);
        if(ioManager != null) {
            while (System.currentTimeMillis() < end) {
                if(readError != null)
                    throw new IOException(readError);
                synchronized (readBuffer) {
                    while(readBuffer.peek() != null)
                        buf.put(readBuffer.remove());
                }
                if (expectedLength >= 0 && buf.position() >= expectedLength)
                    break;
                Thread.sleep(1);
            }

        } else {
            byte[] b1 = new byte[readBufferSize > 0 ? readBufferSize : 256];
            while (System.currentTimeMillis() < end) {
                int len = serialPort.read(b1, USB_READ_WAIT);
                if (len > 0)
                    buf.put(b1, 0, len);
                if (expectedLength >= 0 && buf.position() >= expectedLength)
                    break;
            }
        }
        byte[] data = new byte[buf.position()];
        buf.flip();
        buf.get(data);
        return data;
    }

    public void write(byte[] data) throws IOException {
        serialPort.write(data, USB_WRITE_WAIT);
    }

    public void setParameters(int baudRate, int dataBits, int stopBits, @UsbSerialPort.Parity int parity) throws IOException, InterruptedException {
        serialPort.setParameters(baudRate, dataBits, stopBits, parity);
        if(serialDriver instanceof CdcAcmSerialDriver)
            Thread.sleep(10); // arduino_leonardeo_bridge.ini needs some time
        else
            Thread.sleep(1);
    }

    /* return TRUE/FALSE/null instead of true/false/<throw UnsupportedOperationException> */
    public Boolean getControlLine(Callable<?> callable) throws Exception {
        try {
            return (Boolean)callable.call();
        } catch (UnsupportedOperationException t) {
            return null;
        }
    }

    // return [write packet size, write buffer size(s)]
    public int[] getWriteSizes() {
        if (serialDriver instanceof Cp21xxSerialDriver) {
            if (serialDriver.getPorts().size() == 1) return new int[]{64, 576};
            else if (serialPort.getPortNumber() == 0) return new int[]{64, 320};
            else return new int[]{32, 128, 160}; // write buffer size detection is unreliable
        } else if (serialDriver instanceof Ch34xSerialDriver) {
            return new int[]{32, 64};
        } else if (serialDriver instanceof ProlificSerialDriver) {
            return new int[]{64, 256};
        } else if (serialDriver instanceof FtdiSerialDriver) {
            switch (serialDriver.getPorts().size()) {
                case 1: return new int[]{64, 128};
                case 2: return new int[]{512, 4096};
                case 4: return new int[]{512, 2048};
                default: return null;
            }
        } else if (serialDriver instanceof CdcAcmSerialDriver) {
            return new int[]{64, 128};
        } else {
            return null;
        }
    }


    @Override
    public void onNewData(byte[] data) {
        long now = System.currentTimeMillis();
        if(readTime == 0)
            readTime = now;
        if(data.length > 64) {
            Log.d(TAG, "usb " + devicePort + " read: time+=" + String.format("%-3d",now- readTime) + " len=" + String.format("%-4d",data.length) + " data=" + new String(data, 0, 32) + "..." + new String(data, data.length-32, 32));
        } else {
            Log.d(TAG, "usb " + devicePort + " read: time+=" + String.format("%-3d",now- readTime) + " len=" + String.format("%-4d",data.length) + " data=" + new String(data));
        }
        readTime = now;

        while(readBlock)
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        synchronized (readBuffer) {
            readBuffer.add(data);
        }
    }

    @Override
    public void onRunError(Exception e) {
        readError = e;
    }

}
