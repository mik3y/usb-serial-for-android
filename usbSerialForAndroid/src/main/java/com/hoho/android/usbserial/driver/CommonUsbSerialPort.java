/* Copyright 2011-2013 Google Inc.
 * Copyright 2013 mike wakerly <opensource@hoho.com>
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301,
 * USA.
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

    /**
     * Returns the currently-bound USB device.
     *
     * @return the device
     */
    public final UsbDevice getDevice() {
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
    public abstract void open(UsbDeviceConnection connection) throws IOException;

    @Override
    public void close() throws IOException {
        if (mConnection == null) {
            throw new IOException("Already closed");
        }
        synchronized (this) {
            if (mUsbRequest != null)
                mUsbRequest.cancel();
        }
        try {
            closeInt();
        } catch(Exception ignored) {}
        try {
            mConnection.close();
        } finally {
            mConnection = null;
        }

    }

    protected abstract void closeInt();

    @Override
    public int read(final byte[] dest, final int timeout) throws IOException {
        if(mConnection == null) {
            throw new IOException("Connection closed");
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
            int readMax = Math.min(dest.length, MAX_READ_SIZE);
            nread = mConnection.bulkTransfer(mReadEndpoint, dest, readMax, timeout);

        } else {
            final UsbRequest request = new UsbRequest();
            try {
                request.initialize(mConnection, mReadEndpoint);
                final ByteBuffer buf = ByteBuffer.wrap(dest);
                if (!request.queue(buf, dest.length)) {
                    throw new IOException("Error queueing request");
                }
                mUsbRequest = request;
                final UsbRequest response = mConnection.requestWait();
                synchronized (this) {
                    mUsbRequest = null;
                }
                if (response == null) {
                    throw new IOException("Null response");
                }
                nread = buf.position();
            } finally {
                mUsbRequest = null;
                request.close();
            }
        }
        if (nread > 0) {
            return readFilter(dest, nread);
        } else {
            return 0;
        }
    }

    protected int readFilter(final byte[] buffer, int len) throws IOException { return len; }

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
    public abstract void setParameters(int baudRate, int dataBits, int stopBits, int parity) throws IOException;

    @Override
    public abstract boolean getCD() throws IOException;

    @Override
    public abstract boolean getCTS() throws IOException;

    @Override
    public abstract boolean getDSR() throws IOException;

    @Override
    public abstract boolean getDTR() throws IOException;

    @Override
    public abstract void setDTR(boolean value) throws IOException;

    @Override
    public abstract boolean getRI() throws IOException;

    @Override
    public abstract boolean getRTS() throws IOException;

    @Override
    public abstract void setRTS(boolean value) throws IOException;

    @Override
    public boolean purgeHwBuffers(boolean purgeWriteBuffers, boolean purgeReadBuffers) throws IOException {
        return false;
    }

}
