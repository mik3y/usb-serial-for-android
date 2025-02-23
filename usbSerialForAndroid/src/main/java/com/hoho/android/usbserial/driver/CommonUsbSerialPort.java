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
import android.os.Build;
import android.util.Log;

import com.hoho.android.usbserial.util.MonotonicClock;
import com.hoho.android.usbserial.util.UsbUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.Objects;

/**
 * A base class shared by several driver implementations.
 *
 * @author mike wakerly (opensource@hoho.com)
 */
public abstract class CommonUsbSerialPort implements UsbSerialPort {

    public static boolean DEBUG = false;

    private static final String TAG = CommonUsbSerialPort.class.getSimpleName();
    private static final int MAX_READ_SIZE = 16 * 1024; // = old bulkTransfer limit prior to Android 9

    protected final UsbDevice mDevice;
    protected final int mPortNumber;

    // non-null when open()
    protected UsbDeviceConnection mConnection;
    protected UsbEndpoint mReadEndpoint;
    protected UsbEndpoint mWriteEndpoint;
    protected UsbRequest mReadRequest;
    protected LinkedList<UsbRequest> mReadQueueRequests;
    private int mReadQueueBufferCount;
    private int mReadQueueBufferSize;
    protected FlowControl mFlowControl = FlowControl.NONE;
    protected UsbUtils.Supplier<UsbRequest> mUsbRequestSupplier = UsbRequest::new; // override for testing

    /**
     * Internal write buffer.
     *  Guarded by {@link #mWriteBufferLock}.
     *  Default length = mReadEndpoint.getMaxPacketSize()
     **/
    protected byte[] mWriteBuffer;
    protected final Object mWriteBufferLock = new Object();


    public CommonUsbSerialPort(UsbDevice device, int portNumber) {
        mDevice = device;
        mPortNumber = portNumber;
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

    @Override
    public UsbEndpoint getWriteEndpoint() { return mWriteEndpoint; }

    @Override
    public UsbEndpoint getReadEndpoint() { return mReadEndpoint; }

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
     * @param bufferSize the size in bytes, <= 0 resets original size
     */
    public final void setWriteBufferSize(int bufferSize) {
        synchronized (mWriteBufferLock) {
            if (bufferSize <= 0) {
                if (mWriteEndpoint != null) {
                    bufferSize = mWriteEndpoint.getMaxPacketSize();
                } else {
                    mWriteBuffer = null;
                    return;
                }
            }
            if (mWriteBuffer != null && bufferSize == mWriteBuffer.length) {
                return;
            }
            mWriteBuffer = new byte[bufferSize];
        }
    }

    @Override
    public void setReadQueue(int bufferCount, int bufferSize) {
        if (bufferCount < 0) {
            throw new IllegalArgumentException("Invalid bufferCount");
        }
        if (bufferSize < 0) {
            throw new IllegalArgumentException("Invalid bufferSize");
        }
        if(isOpen()) {
            if (bufferCount < mReadQueueBufferCount) {
                throw new IllegalStateException("Cannot reduce bufferCount when port is open");
            }
            if (bufferSize == 0) {
                bufferSize = mReadEndpoint.getMaxPacketSize();
            }
            if (mReadQueueBufferSize == 0) {
                mReadQueueBufferSize = mReadEndpoint.getMaxPacketSize();
            }
            if (mReadQueueBufferCount != 0 && bufferSize != mReadQueueBufferSize) {
                throw new IllegalStateException("Cannot change bufferSize when port is open");
            }
            if (bufferCount > 0) {
                if (mReadQueueRequests == null) {
                    mReadQueueRequests = new LinkedList<>();
                }
                for (int i = mReadQueueRequests.size(); i < bufferCount; i++) {
                    ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
                    UsbRequest request = mUsbRequestSupplier.get();
                    request.initialize(mConnection, mReadEndpoint);
                    request.setClientData(buffer);
                    request.queue(buffer, bufferSize);
                    mReadQueueRequests.add(request);
                }
            }
        }
        mReadQueueBufferCount = bufferCount;
        mReadQueueBufferSize = bufferSize;
    }

    @Override
    public int getReadQueueBufferCount() { return mReadQueueBufferCount; }
    @Override
    public int getReadQueueBufferSize() { return mReadQueueBufferSize; }

    private boolean useReadQueue() { return mReadQueueBufferCount != 0; }

    @Override
    public void open(UsbDeviceConnection connection) throws IOException {
        if (mConnection != null) {
            throw new IOException("Already open");
        }
        if(connection == null) {
            throw new IllegalArgumentException("Connection is null");
        }
        mConnection = connection;
        boolean ok = false;
        try {
            openInt();
            if (mReadEndpoint == null || mWriteEndpoint == null) {
                throw new IOException("Could not get read & write endpoints");
            }
            mReadRequest = mUsbRequestSupplier.get();
            mReadRequest.initialize(mConnection, mReadEndpoint);
            setReadQueue(mReadQueueBufferCount, mReadQueueBufferSize); // fill mReadQueueRequests
            ok = true;
        } finally {
            if (!ok) {
                try {
                    close();
                } catch (Exception ignored) {}
            }
        }
    }

    protected abstract void openInt() throws IOException;

    @Override
    public void close() throws IOException {
        if (mConnection == null) {
            throw new IOException("Already closed");
        }
        UsbRequest readRequest = mReadRequest;
        mReadRequest = null;
        try {
            readRequest.cancel();
        } catch(Exception ignored) {}
        if(mReadQueueRequests != null) {
            for(UsbRequest readQueueRequest : mReadQueueRequests) {
                try {
                    readQueueRequest.cancel();
                } catch(Exception ignored) {}
            }
            mReadQueueRequests = null;
        }
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
    protected void testConnection(boolean full) throws IOException {
        testConnection(full, "USB get_status request failed");
    }

    protected void testConnection(boolean full, String msg) throws IOException {
        if(mReadRequest == null) {
            throw new IOException("Connection closed");
        }
        if(!full) {
            return;
        }
        byte[] buf = new byte[2];
        int len = mConnection.controlTransfer(0x80 /*DEVICE*/, 0 /*GET_STATUS*/, 0, 0, buf, buf.length, 200);
        if(len < 0)
            throw new IOException(msg);
    }

    @Override
    public int read(final byte[] dest, final int timeout) throws IOException {
        if(dest.length == 0) {
            throw new IllegalArgumentException("Read buffer too small");
        }
        return read(dest, dest.length, timeout);
    }

    @Override
    public int read(final byte[] dest, final int length, final int timeout) throws IOException {return read(dest, length, timeout, true);}

    protected int read(final byte[] dest, int length, final int timeout, boolean testConnection) throws IOException {
        testConnection(false);
        if(length <= 0) {
            throw new IllegalArgumentException("Read length too small");
        }
        length = Math.min(length, dest.length);
        final int nread;
        if (timeout != 0) {
            if(useReadQueue()) {
                throw new IllegalStateException("Cannot use timeout!=0 if readQueue is enabled");
            }
            // bulkTransfer will cause data loss with short timeout + high baud rates + continuous transfer
            //   https://stackoverflow.com/questions/9108548/android-usb-host-bulktransfer-is-losing-data
            // but mConnection.requestWait(timeout) available since Android 8.0 es even worse,
            // as it crashes with short timeout, e.g.
            //   A/libc: Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x276a in tid 29846 (pool-2-thread-1), pid 29618 (.usbserial.test)
            //     /system/lib64/libusbhost.so (usb_request_wait+192)
            //     /system/lib64/libandroid_runtime.so (android_hardware_UsbDeviceConnection_request_wait(_JNIEnv*, _jobject*, long)+84)
            // data loss / crashes were observed with timeout up to 200 msec
            long endTime = testConnection ? MonotonicClock.millis() + timeout : 0;
            int readMax = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ? length : Math.min(length, MAX_READ_SIZE);
            nread = mConnection.bulkTransfer(mReadEndpoint, dest, readMax, timeout);
            // Android error propagation is improvable:
            //  nread == -1 can be: timeout, connection lost, buffer to small, ???
            if(nread == -1 && testConnection)
                testConnection(MonotonicClock.millis() < endTime);

        } else {
            ByteBuffer buf = null;
            if(useReadQueue()) {
                if (length != mReadQueueBufferSize) {
                    throw new IllegalStateException("Cannot use different length if readQueue is enabled");
                }
            } else {
                buf = ByteBuffer.wrap(dest, 0, length);
                if (!mReadRequest.queue(buf, length)) {
                    throw new IOException("Queueing USB request failed");
                }
            }
            final UsbRequest response = mConnection.requestWait();
            if (response == null) {
                throw new IOException("Waiting for USB request failed");
            }
            if(useReadQueue()) {
                buf = (ByteBuffer) response.getClientData();
                System.arraycopy(buf.array(), 0, dest, 0, buf.position());
                if(mReadRequest != null) { // re-queue if connection not closed
                    if (!response.queue(buf, buf.capacity())) {
                        throw new IOException("Queueing USB request failed");
                    }
                }
            }
            nread = Objects.requireNonNull(buf).position();
            // Android error propagation is improvable:
            //   response != null & nread == 0 can be: connection lost, buffer to small, ???
            if(nread == 0) {
                testConnection(true);
            }
        }
        return Math.max(nread, 0);
    }

    @Override
    public void write(byte[] src, int timeout) throws IOException {write(src, src.length, timeout);}

    @Override
    public void write(final byte[] src, int length, final int timeout) throws IOException {
        int offset = 0;
        long startTime = MonotonicClock.millis();
        length = Math.min(length, src.length);

        testConnection(false);
        while (offset < length) {
            int requestTimeout;
            final int requestLength;
            final int actualLength;

            synchronized (mWriteBufferLock) {
                final byte[] writeBuffer;

                if (mWriteBuffer == null) {
                    mWriteBuffer = new byte[mWriteEndpoint.getMaxPacketSize()];
                }
                requestLength = Math.min(length - offset, mWriteBuffer.length);
                if (offset == 0) {
                    writeBuffer = src;
                } else {
                    // bulkTransfer does not support offsets, make a copy.
                    System.arraycopy(src, offset, mWriteBuffer, 0, requestLength);
                    writeBuffer = mWriteBuffer;
                }
                if (timeout == 0 || offset == 0) {
                    requestTimeout = timeout;
                } else {
                    requestTimeout = (int)(startTime + timeout - MonotonicClock.millis());
                    if(requestTimeout == 0)
                        requestTimeout = -1;
                }
                if (requestTimeout < 0) {
                    actualLength = -2;
                } else {
                    actualLength = mConnection.bulkTransfer(mWriteEndpoint, writeBuffer, requestLength, requestTimeout);
                }
            }
            long elapsed = MonotonicClock.millis() - startTime;
            if (DEBUG) {
                Log.d(TAG, "Wrote " + actualLength + "/" + requestLength + " offset " + offset + "/" + length + " time " + elapsed + "/" + requestTimeout);
            }
            if (actualLength <= 0) {
                String msg = "Error writing " + requestLength + " bytes at offset " + offset + " of total " + src.length + " after " + elapsed + "msec, rc=" + actualLength;
                if (timeout != 0) {
                    // could be buffer full because: writing to fast, stopped by flow control
                    testConnection(elapsed < timeout, msg);
                    throw new SerialTimeoutException(msg, offset);
                } else {
                    throw new IOException(msg);

                }
            }
            offset += actualLength;
        }
    }

    @Override
    public boolean isOpen() {
        return mReadRequest != null;
    }

    @Override
    public abstract void setParameters(int baudRate, int dataBits, int stopBits, @Parity int parity) throws IOException;

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
    public EnumSet<ControlLine> getControlLines() throws IOException { throw new UnsupportedOperationException(); }

    @Override
    public EnumSet<ControlLine> getSupportedControlLines() throws IOException { return EnumSet.noneOf(ControlLine.class); }

    @Override
    public void setFlowControl(FlowControl flowcontrol) throws IOException {
        if (flowcontrol != FlowControl.NONE)
            throw new UnsupportedOperationException();
    }

    @Override
    public FlowControl getFlowControl() { return mFlowControl; }

    @Override
    public EnumSet<FlowControl> getSupportedFlowControl() { return EnumSet.of(FlowControl.NONE); }

    @Override
    public boolean getXON() throws IOException { throw new UnsupportedOperationException(); }

    @Override
    public void purgeHwBuffers(boolean purgeWriteBuffers, boolean purgeReadBuffers) throws IOException { throw new UnsupportedOperationException(); }

    @Override
    public void setBreak(boolean value) throws IOException { throw new UnsupportedOperationException(); }

}
