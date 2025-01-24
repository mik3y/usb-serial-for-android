/* Copyright 2011-2013 Google Inc.
 * Copyright 2013 mike wakerly <opensource@hoho.com>
 *
 * Project home page: https://github.com/mik3y/usb-serial-for-android
 */

package com.hoho.android.usbserial.util;

import android.os.Process;
import android.util.Log;

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

    private int mReadTimeout = 0;
    private int mWriteTimeout = 0;

    private final Object mReadBufferLock = new Object();
    private final Object mWriteBufferLock = new Object();

    private ByteBuffer mReadBuffer; // default size = getReadEndpoint().getMaxPacketSize()
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
        mReadBuffer = ByteBuffer.allocate(serialPort.getReadEndpoint().getMaxPacketSize());
    }

    public SerialInputOutputManager(UsbSerialPort serialPort, Listener listener) {
        mSerialPort = serialPort;
        mListener = listener;
        mReadBuffer = ByteBuffer.allocate(serialPort.getReadEndpoint().getMaxPacketSize());
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
     * read/write buffer size
     */
    public void setReadBufferSize(int bufferSize) {
        if (getReadBufferSize() == bufferSize)
            return;
        synchronized (mReadBufferLock) {
            mReadBuffer = ByteBuffer.allocate(bufferSize);
        }
    }

    public int getReadBufferSize() {
        return mReadBuffer.capacity();
    }

    /**
     * read/write timeout
     */
    public void setReadTimeout(int timeout) {
        // when set if already running, read already blocks and the new value will not become effective now
        if(mReadTimeout == 0 && timeout != 0 && mState.get() != State.STOPPED)
            throw new IllegalStateException("readTimeout only configurable before SerialInputOutputManager is started");
        mReadTimeout = timeout;
    }

    public int getReadTimeout() {
        return mReadTimeout;
    }

    public void setWriteTimeout(int timeout) {
        mWriteTimeout = timeout;
    }

    public int getWriteTimeout() {
        return mWriteTimeout;
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

    /**
     * Continuously services the read buffers until {@link #stop()} is called, or until a driver exception is
     * raised.
     */
    public void runRead() {
        Log.i(TAG, "runRead running ...");
        try {
            setThreadPriority();
            mStartuplatch.countDown();
            do {
                stepRead();
            } while (isStillRunning());
            Log.i(TAG, "runRead: Stopping mState=" + getState());
        } catch (Throwable e) {
            if (Thread.currentThread().isInterrupted()) {
                Log.w(TAG, "Thread interrupted, stopping runRead.");
            } else if(mSerialPort.isOpen()) {
                Log.w(TAG, "runRead ending due to exception: " + e.getMessage(), e);
                notifyErrorListener(e);
            } else {
                Log.i(TAG, "runRead: Socket closed");
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
            } else if(mSerialPort.isOpen()) {
                Log.w(TAG, "runWrite ending due to exception: " + e.getMessage(), e);
                notifyErrorListener(e);
            } else {
                Log.i(TAG, "runWrite: Socket closed");
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
        // Handle incoming data.
        byte[] buffer;
        synchronized (mReadBufferLock) {
            buffer = mReadBuffer.array();
        }
        int len = mSerialPort.read(buffer, mReadTimeout);
        if (len > 0) {
            if (DEBUG) {
                Log.d(TAG, "Read data len=" + len);
            }
            final Listener listener = getListener();
            if (listener != null) {
                final byte[] data = new byte[len];
                System.arraycopy(buffer, 0, data, 0, len);
                listener.onNewData(data);
            }
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
