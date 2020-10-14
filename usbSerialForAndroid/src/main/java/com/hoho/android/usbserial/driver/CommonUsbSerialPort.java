/* Copyright 2011-2013 Google Inc.
 * Copyright 2013 mike wakerly <opensource@hoho.com>
 *
 * Project home page: https://github.com/mik3y/usb-serial-for-android
 */

package com.hoho.android.usbserial.driver;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbRequest;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.EnumSet;

/**
 * A base class shared by several driver implementations.
 *
 * @author mike wakerly (opensource@hoho.com)
 */
public abstract class CommonUsbSerialPort implements UsbSerialPort {

    private static final String TAG = CommonUsbSerialPort.class.getSimpleName();
    private static final int DEFAULT_WRITE_BUFFER_SIZE = 16 * 1024;
    private static final int MAX_READ_SIZE = 16 * 1024; // = old bulkTransfer limit

    protected final UsbDevice mDevice;
    protected final int mPortNumber;

    // non-null when open()
    protected UsbDeviceConnection mConnection = null;
    protected UsbEndpoint mReadEndpoint;
    protected UsbEndpoint mWriteEndpoint;
    protected UsbRequest mUsbRequest;

    protected final Object mWriteBufferLock = new Object();
    /** Internal write buffer.  Guarded by {@link #mWriteBufferLock}. */
    protected byte[] mWriteBuffer;

    public CommonUsbSerialPort(UsbDevice device, int portNumber) {
        mDevice = device;
        mPortNumber = portNumber;

        mWriteBuffer = new byte[DEFAULT_WRITE_BUFFER_SIZE];
    }

    @Override
    public String toString() {
        return String.format("<%s device_name=%s device_id=%s port_number=%s>",
                getClass().getSimpleName(), mDevice.getDeviceName(),
                mDevice.getDeviceId(), mPortNumber);
    }

    @Override
    public UsbDevice getDevice() {
        return mDevice;
    }

    @Override
    public int getPortNumber() {
        return mPortNumber;
    }
    
    /**
     * Returns the device serial number
     *  @return serial number
     */
    @Override
    public String getSerial() {
        return mConnection.getSerial();
    }

    /**
     * Sets the size of the internal buffer used to exchange data with the USB
     * stack for write operations.  Most users should not need to change this.
     *
     * @param bufferSize the size in bytes
     */
    public final void setWriteBufferSize(int bufferSize) {
        synchronized (mWriteBufferLock) {
            if (bufferSize == mWriteBuffer.length) {
                return;
            }
            mWriteBuffer = new byte[bufferSize];
        }
    }

    @Override
    public void open(UsbDeviceConnection connection) throws IOException {
        if (mConnection != null) {
            throw new IOException("Already open");
        }
        mConnection = connection;
        try {
            openInt(connection);
            if (mReadEndpoint == null || mWriteEndpoint == null) {
                throw new IOException("Could not get read & write endpoints");
            }
            mUsbRequest = new UsbRequest();
            mUsbRequest.initialize(mConnection, mReadEndpoint);
        } catch(Exception e) {
            close();
            throw e;
        }
    }

    protected abstract void openInt(UsbDeviceConnection connection) throws IOException;

    @Override
    public void close() throws IOException {
        if (mConnection == null) {
            throw new IOException("Already closed");
        }
        try {
            mUsbRequest.cancel();
        } catch(Exception ignored) {}
        mUsbRequest = null;
        try {
            closeInt();
        } catch(Exception ignored) {}
        try {
            mConnection.close();
        } catch(Exception ignored) {}
        mConnection = null;
    }

    protected abstract void closeInt();

    /**
     * use simple USB request supported by all devices to test if connection is still valid
     */
    protected void testConnection() throws IOException {
        byte[] buf = new byte[2];
        int len = mConnection.controlTransfer(0x80 /*DEVICE*/, 0 /*GET_STATUS*/, 0, 0, buf, buf.length, 200);
        if(len < 0)
            throw new IOException("USB get_status request failed");
    }

    @Override
    public int read(final byte[] dest, final int timeout) throws IOException {
        return read(dest, timeout, true);
    }

    protected int read(final byte[] dest, final int timeout, boolean testConnection) throws IOException {
        if(mConnection == null) {
            throw new IOException("Connection closed");
        }
        if(dest.length <= 0) {
            throw new IllegalArgumentException("Read buffer to small");
        }
        final int nread;
        if (timeout != 0) {
            // bulkTransfer will cause data loss with short timeout + high baud rates + continuous transfer
            //   https://stackoverflow.com/questions/9108548/android-usb-host-bulktransfer-is-losing-data
            // but mConnection.requestWait(timeout) available since Android 8.0 es even worse,
            // as it crashes with short timeout, e.g.
            //   A/libc: Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x276a in tid 29846 (pool-2-thread-1), pid 29618 (.usbserial.test)
            //     /system/lib64/libusbhost.so (usb_request_wait+192)
            //     /system/lib64/libandroid_runtime.so (android_hardware_UsbDeviceConnection_request_wait(_JNIEnv*, _jobject*, long)+84)
            // data loss / crashes were observed with timeout up to 200 msec
            long endTime = testConnection ? System.currentTimeMillis() + timeout : 0;
            int readMax = Math.min(dest.length, MAX_READ_SIZE);
            nread = mConnection.bulkTransfer(mReadEndpoint, dest, readMax, timeout);
            // Android error propagation is improvable, nread == -1 can be: timeout, connection lost, buffer undersized, ...
            if(nread == -1 && testConnection && System.currentTimeMillis() < endTime)
                testConnection();

        } else {
            final ByteBuffer buf = ByteBuffer.wrap(dest);
            if (!mUsbRequest.queue(buf, dest.length)) {
                throw new IOException("Queueing USB request failed");
            }
            final UsbRequest response = mConnection.requestWait();
            if (response == null) {
                throw new IOException("Waiting for USB request failed");
            }
            nread = buf.position();
        }
        if (nread > 0)
            return nread;
        else
            return 0;
    }

    @Override
    public int write(final byte[] src, final int timeout) throws IOException {
        int offset = 0;

        if(mConnection == null) {
            throw new IOException("Connection closed");
        }
        while (offset < src.length) {
            final int writeLength;
            final int amtWritten;

            synchronized (mWriteBufferLock) {
                final byte[] writeBuffer;

                writeLength = Math.min(src.length - offset, mWriteBuffer.length);
                if (offset == 0) {
                    writeBuffer = src;
                } else {
                    // bulkTransfer does not support offsets, make a copy.
                    System.arraycopy(src, offset, mWriteBuffer, 0, writeLength);
                    writeBuffer = mWriteBuffer;
                }

                amtWritten = mConnection.bulkTransfer(mWriteEndpoint, writeBuffer, writeLength, timeout);
            }
            if (amtWritten <= 0) {
                throw new IOException("Error writing " + writeLength
                        + " bytes at offset " + offset + " length=" + src.length);
            }

            Log.d(TAG, "Wrote amt=" + amtWritten + " attempted=" + writeLength);
            offset += amtWritten;
        }
        return offset;
    }

    @Override
    public boolean isOpen() {
        return mConnection != null;
    }

    @Override
    public abstract void setParameters(int baudRate, int dataBits, int stopBits, int parity) throws IOException;

    @Override
    public boolean getCD() throws IOException { throw new UnsupportedOperationException(); }

    @Override
    public boolean getCTS() throws IOException { throw new UnsupportedOperationException(); }

    @Override
    public boolean getDSR() throws IOException { throw new UnsupportedOperationException(); }

    @Override
    public boolean getDTR() throws IOException { throw new UnsupportedOperationException(); }

    @Override
    public void setDTR(boolean value) throws IOException { throw new UnsupportedOperationException(); }

    @Override
    public boolean getRI() throws IOException { throw new UnsupportedOperationException(); }

    @Override
    public boolean getRTS() throws IOException { throw new UnsupportedOperationException(); }

    @Override
    public void setRTS(boolean value) throws IOException { throw new UnsupportedOperationException(); }

    @Override
    public abstract EnumSet<ControlLine> getControlLines() throws IOException;

    @Override
    public abstract EnumSet<ControlLine> getSupportedControlLines() throws IOException;

    @Override
    public void purgeHwBuffers(boolean purgeWriteBuffers, boolean purgeReadBuffers) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setBreak(boolean value) throws IOException { throw new UnsupportedOperationException(); }

}
