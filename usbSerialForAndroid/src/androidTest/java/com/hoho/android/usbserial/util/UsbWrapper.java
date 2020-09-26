package com.hoho.android.usbserial.util;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Process;
import android.util.Log;

import com.hoho.android.usbserial.driver.CdcAcmSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;



public class UsbWrapper implements SerialInputOutputManager.Listener {

    private final static int     USB_READ_WAIT = 500;
    private final static int     USB_WRITE_WAIT = 500;
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


    public UsbWrapper(Context context, UsbSerialDriver serialDriver, int devicePort) {
        this.context = context;
        this.serialDriver = serialDriver;
        this.devicePort = devicePort;
        serialPort = serialDriver.getPorts().get(devicePort);
    }

    public void setUp() throws Exception {
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (!usbManager.hasPermission(serialDriver.getDevice())) {
            Log.d(TAG,"USB permission ...");
            final Boolean[] granted = {null};
            BroadcastReceiver usbReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    granted[0] = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                }
            };
            PendingIntent permissionIntent = PendingIntent.getBroadcast(context, 0, new Intent("com.android.example.USB_PERMISSION"), 0);
            IntentFilter filter = new IntentFilter("com.android.example.USB_PERMISSION");
            context.registerReceiver(usbReceiver, filter);
            usbManager.requestPermission(serialDriver.getDevice(), permissionIntent);
            for(int i=0; i<5000; i++) {
                if(granted[0] != null) break;
                Thread.sleep(1);
            }
            Log.d(TAG,"USB permission "+granted[0]);
            assertTrue("USB permission dialog not confirmed", granted[0]==null?false:granted[0]);
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
                Executors.newSingleThreadExecutor().submit(ioManager);
        }
        synchronized (readBuffer) {
            readBuffer.clear();
        }
        readError = null;
    }

    public void startIoManager() {
        Executors.newSingleThreadExecutor().submit(ioManager);
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
                    throw readError;
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

    public void setParameters(int baudRate, int dataBits, int stopBits, int parity) throws IOException, InterruptedException {
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

    @Override
    public void onNewData(byte[] data) {
        long now = System.currentTimeMillis();
        if(readTime == 0)
            readTime = now;
        if(data.length > 64) {
            Log.d(TAG, "usb read: time+=" + String.format("%-3d",now- readTime) + " len=" + String.format("%-4d",data.length) + " data=" + new String(data, 0, 32) + "..." + new String(data, data.length-32, 32));
        } else {
            Log.d(TAG, "usb read: time+=" + String.format("%-3d",now- readTime) + " len=" + String.format("%-4d",data.length) + " data=" + new String(data));
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
