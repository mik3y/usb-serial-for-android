/* Copyright 2011-2013 Google Inc.
 * Copyright 2013 mike wakerly <opensource@hoho.com>
 *
 * Project home page: https://github.com/mik3y/usb-serial-for-android
 */

package com.hoho.android.usbserial.util;

import android.annotation.SuppressLint;
import android.hardware.usb.UsbRequest;
import android.os.Build.VERSION;
import android.os.Process;
import android.util.Log;
import androidx.annotation.VisibleForTesting;

import com.hoho.android.usbserial.driver.UsbSerialPort;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Utility class which services a {@link UsbSerialPort} in its {@link #runWrite()} ()} and {@link #runRead()} ()} ()} methods.
 *
 * @author mike wakerly (opensource@hoho.com)
 */
public class SerialInputOutputManager {

    public enum State {
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING
    }

    public static boolean DEBUG = false;

    private static final String TAG = SerialInputOutputManager.class.getSimpleName();
    private static final int BUFSIZ = 4096;

    private int mWriteTimeout = 0;

    private final Object mWriteBufferLock = new Object();

    private int mReadBufferSize; // default size = getReadEndpoint().getMaxPacketSize()
    private int mReadBufferCount = 4;
    private ByteBuffer mWriteBuffer = ByteBuffer.allocate(BUFSIZ);

    private int mThreadPriority = Process.THREAD_PRIORITY_URGENT_AUDIO;
    private final AtomicReference<State> mState = new AtomicReference<>(State.STOPPED);
    private CountDownLatch mStartuplatch = new CountDownLatch(2);
    private CountDownLatch mShutdownlatch = new CountDownLatch(2);
    private Listener mListener; // Synchronized by 'this'
    private final UsbSerialPort mSerialPort;
    private Supplier<UsbRequest> mRequestSupplier = UsbRequest::new;

    public interface Listener {
        /**
         * Called when new incoming data is available.
         */
        void onNewData(byte[] data);

        /**
         * Called when {@link SerialInputOutputManager#runRead()} ()} or {@link SerialInputOutputManager#runWrite()} ()} ()} aborts due to an error.
         */
        void onRunError(Exception e);
    }

    public SerialInputOutputManager(UsbSerialPort serialPort) {
        mSerialPort = serialPort;
        mReadBufferSize = serialPort.getReadEndpoint().getMaxPacketSize();
    }

    public SerialInputOutputManager(UsbSerialPort serialPort, Listener listener) {
        mSerialPort = serialPort;
        mListener = listener;
        mReadBufferSize = serialPort.getReadEndpoint().getMaxPacketSize();
    }

    public synchronized void setListener(Listener listener) {
        mListener = listener;
    }

    public synchronized Listener getListener() {
        return mListener;
    }

    /**
     * setThreadPriority. By default a higher priority than UI thread is used to prevent data loss
     *
     * @param threadPriority  see {@link Process#setThreadPriority(int)}
     * */
    public void setThreadPriority(int threadPriority) {
        if (!mState.compareAndSet(State.STOPPED, State.STOPPED)) {
            throw new IllegalStateException("threadPriority only configurable before SerialInputOutputManager is started");
        }
        mThreadPriority = threadPriority;
    }

    /**
     * read buffer count
     */
    public int getReadBufferCount() {
        return mReadBufferCount;
    }

    /**
     * read buffer count
     */
    public void setReadBufferCount(int mReadBuffeCount) {
        if (!mState.compareAndSet(State.STOPPED, State.STOPPED)) {
            throw new IllegalStateException("ReadBufferCount only configurable before SerialInputOutputManager is started");
        }
        this.mReadBufferCount = mReadBuffeCount;
    }

    public void setWriteTimeout(int timeout) {
        mWriteTimeout = timeout;
    }

    public int getWriteTimeout() {
        return mWriteTimeout;
    }

    /**
     * read/write buffer size
     */
    public void setReadBufferSize(int bufferSize) {
        if (getReadBufferSize() != bufferSize) {
            if (!mState.compareAndSet(State.STOPPED, State.STOPPED)) {
                throw new IllegalStateException("ReadBuffeCount only configurable before SerialInputOutputManager is started");
            }
            mReadBufferSize = bufferSize;
        }
    }

    public int getReadBufferSize() {
        return mReadBufferSize;
    }

    @VisibleForTesting
    void setRequestSupplier(Supplier<UsbRequest> mRequestSupplier) {
        this.mRequestSupplier = mRequestSupplier;
    }

    public void setWriteBufferSize(int bufferSize) {
        if(getWriteBufferSize() == bufferSize)
            return;
        synchronized (mWriteBufferLock) {
            ByteBuffer newWriteBuffer = ByteBuffer.allocate(bufferSize);
            if(mWriteBuffer.position() > 0)
                newWriteBuffer.put(mWriteBuffer.array(), 0, mWriteBuffer.position());
            mWriteBuffer = newWriteBuffer;
        }
    }

    public int getWriteBufferSize() {
        return mWriteBuffer.capacity();
    }

    /**
     * when using writeAsync, it is recommended to use readTimeout != 0,
     * else the write will be delayed until read data is available
     */
    public void writeAsync(byte[] data) {
        synchronized (mWriteBufferLock) {
            mWriteBuffer.put(data);
        }
    }

    /**
     * start SerialInputOutputManager in separate thread
     */
    public void start() {
        if(mState.compareAndSet(State.STOPPED, State.STARTING)) {
            new Thread(this::runRead, this.getClass().getSimpleName() + "_read").start();
            new Thread(this::runWrite, this.getClass().getSimpleName() + "_write").start();
            try {
                mStartuplatch.await();
                mState.set(State.RUNNING);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else {
            throw new IllegalStateException("already started");
        }
    }

    /**
     * stop SerialInputOutputManager thread
     *
     * when using readTimeout == 0 (default), additionally use usbSerialPort.close() to
     * interrupt blocking read
     */
    public void stop() {
        if(mState.compareAndSet(State.RUNNING, State.STOPPING)) {
            Log.i(TAG, "Stop requested");
            try {
                mStartuplatch.await();
                mState.set(State.STOPPED);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            mStartuplatch = new CountDownLatch(2);
            mShutdownlatch = new CountDownLatch(2);
        }
    }

    public State getState() {
        return mState.get();
    }

    /**
     * Continuously services the read buffers until {@link #stop()} is called, or until a driver exception is
     * raised.
     */
    @SuppressLint("ObsoleteSdkInt")
    public void runRead() {
        mStartuplatch.countDown();
        Log.i(TAG, "Running ...");
        try {
            if (mThreadPriority != Process.THREAD_PRIORITY_DEFAULT) {
                Process.setThreadPriority(mThreadPriority);
            }
            // Initialize buffers and requests
            for (int i = 0; i < mReadBufferCount; i++) {
                final ByteBuffer buffer = ByteBuffer.allocate(mReadBufferSize);
                final UsbRequest request;
                if (VERSION.SDK_INT == 0) {
                    request = mRequestSupplier.get();
                } else {
                    request = new UsbRequest();
                }
                request.setClientData(buffer);
                request.initialize(mSerialPort.getConnection(), mSerialPort.getReadEndpoint());
                request.queue(buffer, buffer.capacity());
            }
            while (true) {
                stepRead();
                if (getState() != State.RUNNING) {
                    Log.i(TAG, "Stopping mState=" + getState());
                    break;
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "Run ending due to exception: " + e.getMessage(), e);
            final Listener listener = getListener();
            if (listener != null) {
                try {
                    if (e instanceof Exception) {
                        listener.onRunError((Exception) e);
                    } else {
                        listener.onRunError(new Exception(e));
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Exception in onRunError: " + t.getMessage(), t);
                }
            }
        } finally {
            mShutdownlatch.countDown();
        }
    }

    /**
     * Continuously services the write buffers until {@link #stop()} is called, or until a driver exception is
     * raised.
     */
    public void runWrite() {
        mStartuplatch.countDown();
        Log.i(TAG, "Running ...");
        try {
            if (mThreadPriority != Process.THREAD_PRIORITY_DEFAULT) {
                Process.setThreadPriority(mThreadPriority);
            }
            while (true) {
                stepWrite();
                if (getState() != State.RUNNING) {
                    Log.i(TAG, "Stopping mState=" + getState());
                    break;
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "Run ending due to exception: " + e.getMessage(), e);
            final Listener listener = getListener();
            if (listener != null) {
                try {
                    if (e instanceof Exception) {
                        listener.onRunError((Exception) e);
                    } else {
                        listener.onRunError(new Exception(e));
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Exception in onRunError: " + t.getMessage(), t);
                }
            }
        } finally {
            mShutdownlatch.countDown();
        }
    }

    private void stepRead() throws IOException {
        // Wait for the request to complete
        UsbRequest completedRequest = mSerialPort.getConnection().requestWait();
        if (completedRequest != null) {
            final ByteBuffer completedBuffer = (ByteBuffer) completedRequest.getClientData();
            completedBuffer.flip(); // Prepare for reading
            final byte[] data = new byte[completedBuffer.remaining()];
            completedBuffer.get(data);
            final Listener listener = getListener();
            if ((listener != null) && (data.length > 0)) {
                listener.onNewData(data); // Handle data
            }
            completedBuffer.clear(); // Prepare for reuse
            // Requeue the buffer and handle potential failures
            if (!completedRequest.queue(completedBuffer, completedBuffer.capacity())) {
                Log.e(TAG, "Failed to requeue the buffer");
            }
        } else {
            Log.e(TAG, "Error waiting for request");
        }
    }

    private void stepWrite() throws IOException {
        // Handle outgoing data.
        byte[] buffer = null;
        int len;
        synchronized (mWriteBufferLock) {
            len = mWriteBuffer.position();
            if (len > 0) {
                buffer = new byte[len];
                mWriteBuffer.rewind();
                mWriteBuffer.get(buffer, 0, len);
                mWriteBuffer.clear();
            }
        }
        if (buffer != null) {
            if (DEBUG) {
                Log.d(TAG, "Writing data len=" + len);
            }
            mSerialPort.write(buffer, mWriteTimeout);
        }
    }

}
