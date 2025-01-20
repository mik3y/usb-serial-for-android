/* Copyright 2011-2013 Google Inc.
 * Copyright 2013 mike wakerly <opensource@hoho.com>
 *
 * Project home page: https://github.com/mik3y/usb-serial-for-android
 */

package com.hoho.android.usbserial.util;

import android.annotation.SuppressLint;
import android.hardware.usb.UsbRequest;
import android.os.Process;
import android.util.Log;
import androidx.annotation.VisibleForTesting;

import com.hoho.android.usbserial.driver.UsbSerialPort;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

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
     * start SerialInputOutputManager in separate threads
     */
    public void start() {
        if(mState.compareAndSet(State.STOPPED, State.STARTING)) {
            mStartuplatch = new CountDownLatch(2);
            mShutdownlatch = new CountDownLatch(2);
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
     * stop SerialInputOutputManager threads
     *
     * when using readTimeout == 0 (default), additionally use usbSerialPort.close() to
     * interrupt blocking read
     */
    public void stop() {
        if(mState.compareAndSet(State.RUNNING, State.STOPPING)) {
            Log.i(TAG, "Stop requested");
            try {
                mShutdownlatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public State getState() {
        return mState.get();
    }

    /**
     * @return true if the thread is still running
     */
    private boolean isStillRunning() {
        State state = mState.get();
        return ((state == State.RUNNING) || (state == State.STARTING))
            && (mShutdownlatch.getCount() == 2)
            && !Thread.currentThread().isInterrupted();
    }

    /**
     * Notify listener of an error
     *
     * @param e the exception
     */
    private void notifyErrorListener(Throwable e) {
        Listener listener = getListener();
        if (listener != null) {
            try {
                listener.onRunError(e instanceof Exception ? (Exception) e : new Exception(e));
            } catch (Throwable t) {
                Log.w(TAG, "Exception in onRunError: " + t.getMessage(), t);
            }
        }
    }

    /**
     * Set the thread priority
     */
    private void setThreadPriority() {
        if (mThreadPriority != Process.THREAD_PRIORITY_DEFAULT) {
            Process.setThreadPriority(mThreadPriority);
        }
    }

    @VisibleForTesting
    protected UsbRequest getUsbRequest() {
        return new UsbRequest();
    }

    /**
     * Continuously services the read buffers until {@link #stop()} is called, or until a driver exception is
     * raised.
     */
    @SuppressLint("ObsoleteSdkInt")
    public void runRead() {
        Log.i(TAG, "runRead running ...");
        try {
            setThreadPriority();
            mStartuplatch.countDown();
            // Initialize buffers and requests
            for (int i = 0; i < mReadBufferCount; i++) {
                ByteBuffer buffer = ByteBuffer.allocate(mReadBufferSize);
                UsbRequest request = getUsbRequest();
                request.setClientData(buffer);
                request.initialize(mSerialPort.getConnection(), mSerialPort.getReadEndpoint());
                request.queue(buffer, buffer.capacity());
            }
            do {
                stepRead();
            } while (isStillRunning());
            Log.i(TAG, "runRead: Stopping mState=" + getState());
        } catch (Throwable e) {
            if (Thread.currentThread().isInterrupted()) {
                Log.w(TAG, "Thread interrupted, stopping runRead.");
            } else {
                Log.w(TAG, "runRead ending due to exception: " + e.getMessage(), e);
                notifyErrorListener(e);
            }
        } finally {
            if (!mState.compareAndSet(State.RUNNING, State.STOPPING)) {
                if (mState.compareAndSet(State.STOPPING, State.STOPPED)) {
                    Log.i(TAG, "runRead: Stopped mState=" + getState());
                }
            }
            mShutdownlatch.countDown();
        }
    }

    /**
     * Continuously services the write buffers until {@link #stop()} is called, or until a driver exception is
     * raised.
     */
    public void runWrite() {
        Log.i(TAG, "runWrite running ...");
        try {
            setThreadPriority();
            mStartuplatch.countDown();
            do {
                stepWrite();
            } while (isStillRunning());
            Log.i(TAG, "runWrite: Stopping mState=" + getState());
        } catch (Throwable e) {
            if (Thread.currentThread().isInterrupted()) {
                Log.w(TAG, "Thread interrupted, stopping runWrite.");
            } else {
                Log.w(TAG, "runWrite ending due to exception: " + e.getMessage(), e);
                notifyErrorListener(e);
            }
        } finally {
            if (!mState.compareAndSet(State.RUNNING, State.STOPPING)) {
                if (mState.compareAndSet(State.STOPPING, State.STOPPED)) {
                    Log.i(TAG, "runWrite: Stopped mState=" + getState());
                }
            }
            mShutdownlatch.countDown();
        }
    }

    private void stepRead() throws IOException {
        // Wait for the request to complete
        final UsbRequest completedRequest = mSerialPort.getConnection().requestWait();
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
                throw new IOException("Failed to requeue the buffer");
            }
        } else {
            Log.e(TAG, "Error waiting for request");
            throw new IOException("Error waiting for request");
        }
    }

    private void stepWrite() throws IOException {
        // Handle outgoing data.
        byte[] buffer = null;
        synchronized (mWriteBufferLock) {
            int len = mWriteBuffer.position();
            if (len > 0) {
                buffer = new byte[len];
                mWriteBuffer.rewind();
                mWriteBuffer.get(buffer, 0, len);
                mWriteBuffer.clear();
            }
        }
        if (buffer != null) {
            if (DEBUG) {
                Log.d(TAG, "Writing data len=" + buffer.length);
            }
            mSerialPort.write(buffer, mWriteTimeout);
        }
    }

}
