/*
 * Project home page: https://github.com/mik3y/usb-serial-for-android
 */

package com.hoho.android.usbserial.driver;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.util.Log;

import com.hoho.android.usbserial.util.HexDump;
import com.hoho.android.usbserial.util.MonotonicClock;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Qinheng (WCH) CH348L / CH348Q, USB to octal UART.
 *
 * All 8 ports share one interface:
 *   EP 0x01 OUT / 0x81 IN: command channel (register access, status notifications)
 *   EP 0x02 OUT / 0x82 IN: data channel, multiplexed with port headers
 *
 * TX packet: [port][len_lo][len_hi][data...], at most one USB packet. The next packet
 *   for a port must not be sent before the chip reported 'TX empty' for that port.
 * RX packet: sequence of 32 byte sub-packets [port][len][data(max 30)][padding]
 * Status notifications on EP 0x81 must be drained continuously, else EP 0x82 stalls.
 *
 * CH348Q ports 4..7 have no modem control lines.
 *
 * Protocol references: WCHSoftGroup/ch9344ser_linux (ch9344.c) and USB captures
 * of the Windows driver.
 */
public class Ch348SerialDriver implements UsbSerialDriver {

    private static final String TAG = Ch348SerialDriver.class.getSimpleName();

    static final int PORT_COUNT = 8;

    static final int RX_SUBPACKET_SIZE = 32;
    static final int RX_SUBPACKET_DATA_MAX = RX_SUBPACKET_SIZE - 2;
    static final int TX_HEADER_SIZE = 3;

    // command byte, low nibble = port number
    private static final int CMD_W_BR = 0x80;  // write register
    private static final int CMD_WB_E = 0x90;  // write extended / read register
    private static final int CMD_W_R  = 0xC0;  // write register with response

    // per port registers, see regBase()
    static final int R_C1 = 0x01; // line control enable
    static final int R_C2 = 0x02; // configuration
    static final int R_C3 = 0x03; // 0x60 = normal, 0x61 = break
    static final int R_C4 = 0x04; // modem control: 0x00/0x01 = DTR, 0x10/0x11 = RTS, 0x50/0x51 = flow control off/on
    static final int R_C5 = 0x06; // modem status, read via VEN_R

    // global registers
    static final int VEN_R   = 0x85;
    static final int R_INIT  = 0xA1;   // baud rate + line parameters, obfuscated
    static final int R_INIT2 = 0xA2;
    static final int R_MOD   = 0x97;
    static final int R_TM_O  = 0x9C;
    static final int R_UP_O  = 0x9D;
    static final int R_IO_CE = 0xA3;
    static final int R_IO_CI = 0xA7;
    static final int R_EE_CFG = 0xA9;

    // status notification register types (low nibble)
    static final int R_II_B1 = 0x06; // line status
    static final int R_II_B2 = 0x02; // TX empty
    static final int R_II_B3 = 0x00; // modem status

    // modem status bits, low nibble = changed mask, high nibble = values
    static final int CTI_C  = 0x01; // CTS
    static final int CTI_DS = 0x02; // DSR
    static final int CTI_R  = 0x04; // RI
    static final int CTI_DC = 0x08; // CD

    private static final int CMD_VER = 0x96;

    private static final int MAX_BAUD_RATE = 6_000_000;

    private static final int USB_TIMEOUT_MILLIS = 5000;
    private static final int BACKGROUND_READ_TIMEOUT = 50;
    private static final int CMD_RESPONSE_TIMEOUT = 500;
    private static final int TX_EMPTY_TIMEOUT = 5000;
    private static final int POLL_INTERVAL = 100;
    private static final int THREAD_JOIN_TIMEOUT = 1000;
    private static final int RX_QUEUE_SIZE = 1024;

    enum ChipType { CH348Q, CH348L, UNKNOWN }

    private final UsbDevice mDevice;
    private final Ch348SerialPort[] mPortArray;
    private final List<UsbSerialPort> mPorts;

    // shared resources, guarded by mOpenLock
    private final Object mOpenLock = new Object();
    private int mOpenCount = 0;
    private volatile UsbDeviceConnection mSharedConnection;
    private volatile UsbEndpoint mCmdWriteEndpoint;
    private volatile UsbEndpoint mCmdReadEndpoint;
    private volatile UsbEndpoint mDataWriteEndpoint;
    private volatile UsbEndpoint mDataReadEndpoint;
    private volatile ChipType mChipType = ChipType.UNKNOWN;
    private volatile boolean mDisconnected;

    private final Object mCmdLock = new Object();
    private final BlockingQueue<byte[]> mCmdResponseQueue = new ArrayBlockingQueue<>(64);

    private Thread mDataReaderThread;
    private Thread mCmdReaderThread;
    private volatile boolean mThreadsRunning;

    public Ch348SerialDriver(UsbDevice device) {
        mDevice = device;
        mPortArray = new Ch348SerialPort[PORT_COUNT];
        List<UsbSerialPort> ports = new ArrayList<>(PORT_COUNT);
        for (int i = 0; i < PORT_COUNT; i++) {
            mPortArray[i] = new Ch348SerialPort(device, i);
            ports.add(mPortArray[i]);
        }
        mPorts = Collections.unmodifiableList(ports);
    }

    @Override
    public UsbDevice getDevice() {
        return mDevice;
    }

    @Override
    public List<UsbSerialPort> getPorts() {
        return mPorts;
    }

    @SuppressWarnings({"unused"})
    public static Map<Integer, int[]> getSupportedDevices() {
        final Map<Integer, int[]> supportedDevices = new LinkedHashMap<>();
        supportedDevices.put(UsbId.VENDOR_QINHENG, new int[]{
                UsbId.QINHENG_CH348,
        });
        return supportedDevices;
    }

    /** register base address of a port, identical for CH348L and CH348Q */
    static int regBase(int port) {
        return port < 4 ? 0x10 * port : 0x10 * (port - 4) + 0x08;
    }

    // ---------------------------------------------------------------------------------------
    // shared resources
    // ---------------------------------------------------------------------------------------

    private void acquireSharedResources(UsbDeviceConnection connection) throws IOException {
        synchronized (mOpenLock) {
            if (mOpenCount == 0) {
                UsbInterface usbIface = mDevice.getInterface(0);
                if (!connection.claimInterface(usbIface, true))
                    throw new IOException("Could not claim interface 0");
                try {
                    findEndpoints(usbIface);
                } catch (IOException e) {
                    connection.releaseInterface(usbIface);
                    throw e;
                }
                mSharedConnection = connection;
                mDisconnected = false;
                mChipType = detectChipType(connection);
                startThreads();
            }
            mOpenCount++;
        }
    }

    /**
     * @param connection connection given to the port's open(). It is closed here, unless it is
     *                   the shared connection still in use by other ports.
     */
    private void releaseSharedResources(UsbDeviceConnection connection) {
        synchronized (mOpenLock) {
            mOpenCount--;
            if (connection != null && connection != mSharedConnection) {
                connection.close();
            }
            if (mOpenCount == 0) {
                stopThreads();
                UsbDeviceConnection sharedConnection = mSharedConnection;
                mSharedConnection = null;
                if (sharedConnection != null) {
                    try {
                        sharedConnection.releaseInterface(mDevice.getInterface(0));
                    } catch (Exception ignored) {}
                    sharedConnection.close();
                }
                mCmdWriteEndpoint = null;
                mCmdReadEndpoint = null;
                mDataWriteEndpoint = null;
                mDataReadEndpoint = null;
            }
        }
    }

    private void findEndpoints(UsbInterface usbIface) throws IOException {
        mCmdWriteEndpoint = mCmdReadEndpoint = mDataWriteEndpoint = mDataReadEndpoint = null;
        for (int i = 0; i < usbIface.getEndpointCount(); i++) {
            UsbEndpoint ep = usbIface.getEndpoint(i);
            if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK)
                continue;
            boolean in = ep.getDirection() == UsbConstants.USB_DIR_IN;
            switch (ep.getEndpointNumber()) {
                case 1: if (in) mCmdReadEndpoint = ep; else mCmdWriteEndpoint = ep; break;
                case 2: if (in) mDataReadEndpoint = ep; else mDataWriteEndpoint = ep; break;
            }
        }
        if (mCmdWriteEndpoint == null || mCmdReadEndpoint == null || mDataWriteEndpoint == null || mDataReadEndpoint == null)
            throw new IOException("Could not get command & data endpoints");
    }

    private ChipType detectChipType(UsbDeviceConnection connection) {
        byte[] buffer = new byte[4];
        int ret = connection.controlTransfer(UsbConstants.USB_TYPE_VENDOR | UsbConstants.USB_DIR_IN,
                CMD_VER, 0, 0, buffer, buffer.length, USB_TIMEOUT_MILLIS);
        ChipType chipType;
        if (ret == buffer.length)
            chipType = (buffer[1] & 0x80) != 0 ? ChipType.CH348Q : ChipType.CH348L;
        else
            chipType = ChipType.UNKNOWN;
        Log.d(TAG, String.format("chip version=0x%02x type=%s", buffer[0], chipType));
        return chipType;
    }

    private boolean testConnection(UsbDeviceConnection connection) {
        byte[] buf = new byte[2];
        return connection.controlTransfer(0x80 /*DEVICE*/, 0 /*GET_STATUS*/, 0, 0, buf, buf.length, 200) >= 0;
    }

    // ---------------------------------------------------------------------------------------
    // command channel
    // ---------------------------------------------------------------------------------------

    private void cmdOut(byte[] data) throws IOException {
        UsbDeviceConnection connection = mSharedConnection;
        UsbEndpoint ep = mCmdWriteEndpoint;
        if (connection == null || ep == null)
            throw new IOException("Connection closed");
        int ret = connection.bulkTransfer(ep, data, data.length, USB_TIMEOUT_MILLIS);
        if (CommonUsbSerialPort.DEBUG)
            Log.d(TAG, "cmd out " + HexDump.toHexString(data) + " rc=" + ret);
        if (ret != data.length)
            throw new IOException(String.format("Command 0x%02x 0x%02x failed, rc=%d", data[0], data[1], ret));
    }

    private void regWrite(int port, int reg, int value) throws IOException {
        cmdOut(new byte[]{(byte) (CMD_W_BR | port), (byte) (regBase(port) + reg), (byte) value});
    }

    private void regWriteWithResponse(int port, int reg, int value) throws IOException {
        cmdOut(new byte[]{(byte) (CMD_W_R | port), (byte) (regBase(port) + reg), (byte) value});
    }

    /**
     * send command and wait for next message on command channel. Response content is
     * not evaluated, but waiting keeps the command sequence in sync with the device.
     */
    private void cmdOutWaitResponse(byte[] data) throws IOException {
        synchronized (mCmdLock) {
            mCmdResponseQueue.clear();
            cmdOut(data);
            try {
                if (mCmdResponseQueue.poll(CMD_RESPONSE_TIMEOUT, TimeUnit.MILLISECONDS) == null)
                    Log.d(TAG, String.format("No response for command 0x%02x 0x%02x", data[0], data[1]));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // background threads
    // ---------------------------------------------------------------------------------------

    private void startThreads() {
        mThreadsRunning = true;
        mCmdResponseQueue.clear();
        mDataReaderThread = new Thread(this::dataReaderLoop, TAG + "_data");
        mDataReaderThread.setDaemon(true);
        mDataReaderThread.start();
        mCmdReaderThread = new Thread(this::cmdReaderLoop, TAG + "_cmd");
        mCmdReaderThread.setDaemon(true);
        mCmdReaderThread.start();
    }

    private void stopThreads() {
        mThreadsRunning = false;
        for (Thread thread : new Thread[]{mDataReaderThread, mCmdReaderThread}) {
            if (thread == null || thread == Thread.currentThread())
                continue;
            thread.interrupt();
            try {
                thread.join(THREAD_JOIN_TIMEOUT); // connection must not be closed while thread is in bulkTransfer
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        mDataReaderThread = null;
        mCmdReaderThread = null;
        mCmdResponseQueue.clear();
    }

    private synchronized void onDisconnected() {
        if (mDisconnected)
            return;
        Log.w(TAG, "USB connection lost");
        mDisconnected = true;
        mThreadsRunning = false;
        for (Ch348SerialPort port : mPortArray)
            port.wakeUp();
    }

    private void dataReaderLoop() {
        byte[] buf = new byte[Math.max(mDataReadEndpoint.getMaxPacketSize(), 512)];
        RxListener listener = (port, data, offset, length) -> mPortArray[port].onRxData(data, offset, length);
        while (mThreadsRunning) {
            UsbDeviceConnection connection = mSharedConnection;
            UsbEndpoint ep = mDataReadEndpoint;
            if (connection == null || ep == null)
                break;
            long startTime = MonotonicClock.millis();
            int len = connection.bulkTransfer(ep, buf, buf.length, BACKGROUND_READ_TIMEOUT);
            if (len > 0) {
                if (CommonUsbSerialPort.DEBUG)
                    Log.d(TAG, "data in " + HexDump.toHexString(buf, 0, len));
                parseRxPacket(buf, len, listener);
            } else if (len < 0 && mThreadsRunning && MonotonicClock.millis() - startTime < BACKGROUND_READ_TIMEOUT / 2) {
                // early error return instead of timeout
                if (!testConnection(connection)) {
                    onDisconnected();
                    break;
                }
            }
        }
    }

    private void cmdReaderLoop() {
        byte[] buf = new byte[Math.max(mCmdReadEndpoint.getMaxPacketSize(), 512)];
        StatusListener listener = new StatusListener() {
            @Override
            public void onTxEmpty(int port) { mPortArray[port].onTxEmpty(); }
            @Override
            public void onModemStatus(int port, int changedMask, int values) { mPortArray[port].onModemStatus(changedMask, values); }
        };
        while (mThreadsRunning) {
            UsbDeviceConnection connection = mSharedConnection;
            UsbEndpoint ep = mCmdReadEndpoint;
            if (connection == null || ep == null)
                break;
            int len = connection.bulkTransfer(ep, buf, buf.length, BACKGROUND_READ_TIMEOUT);
            if (len <= 0)
                continue;
            byte[] msg = new byte[len];
            System.arraycopy(buf, 0, msg, 0, len);
            if (CommonUsbSerialPort.DEBUG)
                Log.d(TAG, "cmd in " + HexDump.toHexString(msg));
            if (!mCmdResponseQueue.offer(msg)) {
                mCmdResponseQueue.poll();
                mCmdResponseQueue.offer(msg);
            }
            parseStatus(msg, len, listener);
        }
    }

    // ---------------------------------------------------------------------------------------
    // protocol helpers, static for unit tests
    // ---------------------------------------------------------------------------------------

    interface RxListener {
        void onData(int port, byte[] data, int offset, int length);
    }

    interface StatusListener {
        void onTxEmpty(int port);
        void onModemStatus(int port, int changedMask, int values);
    }

    static void parseRxPacket(byte[] buf, int len, RxListener listener) {
        for (int i = 0; i + 2 <= len; i += RX_SUBPACKET_SIZE) {
            int port = buf[i] & 0xff;
            int dataLen = buf[i + 1] & 0xff;
            if (port >= PORT_COUNT || dataLen > RX_SUBPACKET_DATA_MAX)
                break;
            dataLen = Math.min(dataLen, len - i - 2);
            if (dataLen > 0)
                listener.onData(port, buf, i + 2, dataLen);
        }
    }

    /** parse notifications from command channel, see ch9344_cmd_irq() */
    static void parseStatus(byte[] buf, int len, StatusListener listener) {
        int i = 0;
        while (i + 2 <= len) {
            int port = buf[i] & 0x0f;
            int reg = buf[i + 1] & 0xff;
            if (reg == R_INIT) {
                i += 12;
            } else if (reg >= R_MOD && reg <= R_TM_O) {
                i += 4;
            } else if (reg >= R_IO_CE && reg <= R_IO_CI) {
                i += reg == R_IO_CI ? 10 : 3;
            } else if ((reg & 0x0f) == R_II_B2) {
                if (port >= PORT_COUNT) break;
                listener.onTxEmpty(port);
                i += 3;
            } else if ((reg & 0x0f) == R_II_B3 ||
                    (reg == VEN_R && i + 3 < len && (buf[i + 2] & 0xff) == (regBase(port) | R_C5))) {
                if (port >= PORT_COUNT || i + 2 >= len) break;
                int status = reg == VEN_R ? (buf[i + 3] & 0xff) | 0x0f : buf[i + 2] & 0xff;
                listener.onModemStatus(port, status & 0x0f, status >> 4);
                i += reg == VEN_R ? 4 : 3;
            } else if ((reg & 0x0f) == R_II_B1) {
                i += 3;
            } else if (reg == R_EE_CFG || reg == R_UP_O) {
                i += 4;
            } else {
                break;
            }
        }
    }

    static byte[] buildTxPacket(int port, byte[] src, int offset, int length) {
        byte[] packet = new byte[TX_HEADER_SIZE + length];
        packet[0] = (byte) port;
        packet[1] = (byte) length;
        packet[2] = (byte) (length >> 8);
        System.arraycopy(src, offset, packet, TX_HEADER_SIZE, length);
        return packet;
    }

    /** obfuscate line parameters, see cal_outdata() */
    static byte[] calOutData(byte[] plain, int rol, int xor) {
        byte[] buf = plain.clone();
        for (int r = 0; r < rol; r++) {
            int msb = (buf[0] & 0x80) != 0 ? 1 : 0;
            for (int i = 0; i < 7; i++)
                buf[i] = (byte) ((buf[i] << 1) | ((buf[i + 1] & 0x80) != 0 ? 1 : 0));
            buf[7] = (byte) ((buf[7] << 1) | msb);
        }
        for (int i = 0; i < 8; i++)
            buf[i] ^= xor;
        return buf;
    }

    /** receive timeout, see cal_recv_tmt() */
    static int calRecvTmt(int baudRate) {
        if (baudRate >= 921600)
            return 5;
        return 1000000 * 15 / baudRate / 100 + 1;
    }

    // ---------------------------------------------------------------------------------------
    // port
    // ---------------------------------------------------------------------------------------

    public class Ch348SerialPort extends CommonUsbSerialPort {

        private volatile boolean mIsOpen;

        private final BlockingQueue<byte[]> mRxQueue = new ArrayBlockingQueue<>(RX_QUEUE_SIZE);
        private final Object mRxLock = new Object();
        private byte[] mRxPending;       // guarded by mRxLock
        private int mRxPendingPos;

        private final Object mTxLock = new Object();
        private final Object mTxEmptyLock = new Object();
        private boolean mTxEmpty = true; // guarded by mTxEmptyLock
        private int mTxMaxData;

        private boolean mDtr;
        private boolean mRts;
        private volatile int mModemStatus;
        private int mBaudRate = 9600;

        public Ch348SerialPort(UsbDevice device, int portNumber) {
            super(device, portNumber);
        }

        @Override
        public UsbSerialDriver getDriver() {
            return Ch348SerialDriver.this;
        }

        private boolean hasControlLines() {
            return !(mChipType == ChipType.CH348Q && mPortNumber >= 4);
        }

        private void checkOpen() throws IOException {
            if (!mIsOpen)
                throw new IOException("Connection closed");
            if (mDisconnected)
                throw new IOException("USB get_status request failed");
        }

        // called from background threads

        void onRxData(byte[] data, int offset, int length) {
            if (!mIsOpen)
                return;
            byte[] chunk = new byte[length];
            System.arraycopy(data, offset, chunk, 0, length);
            if (!mRxQueue.offer(chunk))
                Log.w(TAG, "Port " + mPortNumber + " receive queue full, " + length + " bytes lost");
        }

        void onTxEmpty() {
            synchronized (mTxEmptyLock) {
                mTxEmpty = true;
                mTxEmptyLock.notifyAll();
            }
        }

        void onModemStatus(int changedMask, int values) {
            mModemStatus = (mModemStatus & ~changedMask) | (values & changedMask);
        }

        void wakeUp() {
            synchronized (mTxEmptyLock) {
                mTxEmptyLock.notifyAll();
            }
        }

        // open / close

        @Override
        public void open(UsbDeviceConnection connection) throws IOException {
            if (connection == null)
                throw new IllegalArgumentException("Connection is null");
            if (mIsOpen)
                throw new IOException("Already open");
            acquireSharedResources(connection);
            mConnection = connection;
            mReadEndpoint = mDataReadEndpoint;
            mWriteEndpoint = mDataWriteEndpoint;
            mTxMaxData = mWriteEndpoint.getMaxPacketSize() - TX_HEADER_SIZE;
            mRxQueue.clear();
            synchronized (mRxLock) {
                mRxPending = null;
            }
            synchronized (mTxEmptyLock) {
                mTxEmpty = true;
            }
            mDtr = mRts = false;
            mModemStatus = 0;
            mFlowControl = FlowControl.NONE;
            mIsOpen = true;
            try {
                initialize();
            } catch (Exception e) {
                mIsOpen = false;
                cleanup();
                throw e;
            }
        }

        @Override
        public void close() throws IOException {
            if (!mIsOpen)
                throw new IOException("Already closed");
            if (!mDisconnected) {
                try {
                    regWrite(mPortNumber, R_C3, 0x60);
                    regWrite(mPortNumber, R_C4, 0x00); // DTR off
                    regWrite(mPortNumber, R_C4, 0x10); // RTS off
                } catch (Exception ignored) {}
            }
            mIsOpen = false;
            wakeUp();
            cleanup();
        }

        private void cleanup() {
            UsbDeviceConnection connection = mConnection;
            mConnection = null;
            mReadEndpoint = null;
            mWriteEndpoint = null;
            mRxQueue.clear();
            releaseSharedResources(connection);
        }

        @Override
        public boolean isOpen() {
            return mIsOpen;
        }

        @Override
        protected void openInt() {
            throw new UnsupportedOperationException(); // not used, open() is overridden
        }

        @Override
        protected void closeInt() {
            throw new UnsupportedOperationException(); // not used, close() is overridden
        }

        /**
         * open sequence, taken from USB capture of the Windows driver
         */
        private void initialize() throws IOException {
            int base = regBase(mPortNumber);
            cmdOutWaitResponse(new byte[]{(byte) (CMD_WB_E | mPortNumber), (byte) R_INIT2, (byte) (0xF0 | mPortNumber), 0});
            regWriteWithResponse(mPortNumber, R_C2, 0x87);
            regWriteWithResponse(mPortNumber, R_C4, 0x08);
            if (hasControlLines()) {
                regWrite(mPortNumber, R_C4, 0x50);
                cmdOutWaitResponse(new byte[]{(byte) (CMD_WB_E | mPortNumber), (byte) VEN_R, (byte) (base | R_C5)});
                setLineParameters(mBaudRate, 8, STOPBITS_1, PARITY_NONE);
                regWrite(mPortNumber, R_C4, 0x10);
                regWrite(mPortNumber, R_C4, 0x01);
                regWrite(mPortNumber, R_C4, 0x10);
                cmdOutWaitResponse(new byte[]{(byte) (CMD_WB_E | mPortNumber), (byte) VEN_R, (byte) (base | R_C5)});
                regWrite(mPortNumber, R_C4, 0x50);
                regWrite(mPortNumber, R_C4, 0x11);
                regWrite(mPortNumber, R_C4, 0x01);
                regWrite(mPortNumber, R_C3, 0x60);
                mDtr = mRts = true;
            } else {
                setLineParameters(mBaudRate, 8, STOPBITS_1, PARITY_NONE);
                regWrite(mPortNumber, R_C4, 0x01);
            }
        }

        // read / write

        @Override
        public void setReadQueue(int bufferCount, int bufferSize) {
            if (bufferCount != 0)
                throw new UnsupportedOperationException("Read queue not supported");
            super.setReadQueue(bufferCount, bufferSize);
        }

        @Override
        public int read(byte[] dest, int length, int timeout) throws IOException {
            checkOpen();
            if (length <= 0)
                throw new IllegalArgumentException("Read length too small");
            length = Math.min(length, dest.length);
            synchronized (mRxLock) {
                if (mRxPending == null) {
                    mRxPending = waitRxChunk(timeout);
                    mRxPendingPos = 0;
                }
                int nread = 0;
                while (nread < length && mRxPending != null) {
                    int len = Math.min(length - nread, mRxPending.length - mRxPendingPos);
                    System.arraycopy(mRxPending, mRxPendingPos, dest, nread, len);
                    nread += len;
                    mRxPendingPos += len;
                    if (mRxPendingPos == mRxPending.length) {
                        mRxPending = mRxQueue.poll();
                        mRxPendingPos = 0;
                    }
                }
                return nread;
            }
        }

        /** @return null on timeout */
        private byte[] waitRxChunk(int timeout) throws IOException {
            long endTime = MonotonicClock.millis() + timeout;
            while (true) {
                long wait = POLL_INTERVAL; // poll to detect close() or disconnect
                if (timeout != 0)
                    wait = Math.min(wait, endTime - MonotonicClock.millis());
                byte[] chunk;
                try {
                    chunk = wait > 0 ? mRxQueue.poll(wait, TimeUnit.MILLISECONDS) : mRxQueue.poll();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                if (chunk != null)
                    return chunk;
                checkOpen();
                if (timeout != 0 && MonotonicClock.millis() >= endTime)
                    return null;
            }
        }

        @Override
        public void write(byte[] src, int length, int timeout) throws IOException {
            checkOpen();
            length = Math.min(length, src.length);
            long startTime = MonotonicClock.millis();
            int offset = 0;
            synchronized (mTxLock) {
                while (offset < length) {
                    int requestLength = Math.min(length - offset, mTxMaxData);
                    int requestTimeout;
                    if (timeout == 0) {
                        // bytes of previous packet might still be in device buffer
                        long txTime = (long) mTxMaxData * 10 * 1000 / mBaudRate;
                        requestTimeout = (int) Math.max(TX_EMPTY_TIMEOUT, 2 * txTime);
                    } else {
                        requestTimeout = (int) (startTime + timeout - MonotonicClock.millis());
                    }
                    String error = null;
                    if (requestTimeout <= 0 || !waitTxEmpty(requestTimeout)) {
                        error = "TX empty notification timeout";
                    } else {
                        byte[] packet = buildTxPacket(mPortNumber, src, offset, requestLength);
                        UsbDeviceConnection connection = mSharedConnection;
                        UsbEndpoint ep = mWriteEndpoint;
                        int actualLength = connection == null || ep == null ? -1 :
                                connection.bulkTransfer(ep, packet, packet.length, requestTimeout);
                        if (actualLength != packet.length) {
                            onTxEmpty();
                            error = "rc=" + actualLength;
                            if (connection != null && !mDisconnected && !Ch348SerialDriver.this.testConnection(connection))
                                onDisconnected();
                        }
                    }
                    if (error != null) {
                        checkOpen();
                        String msg = "Error writing " + requestLength + " bytes at offset " + offset + " of total " + length
                                + " after " + (MonotonicClock.millis() - startTime) + "msec, " + error;
                        throw new SerialTimeoutException(msg, offset);
                    }
                    offset += requestLength;
                }
            }
        }

        /** wait until previous packet is sent, then claim TX for next packet */
        private boolean waitTxEmpty(int timeout) throws IOException {
            long endTime = MonotonicClock.millis() + timeout;
            synchronized (mTxEmptyLock) {
                while (!mTxEmpty) {
                    checkOpen();
                    long wait = endTime - MonotonicClock.millis();
                    if (wait <= 0) {
                        mTxEmpty = true; // don't block subsequent writes forever, if notification got lost
                        return false;
                    }
                    try {
                        mTxEmptyLock.wait(Math.min(wait, POLL_INTERVAL));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                mTxEmpty = false;
                return true;
            }
        }

        // parameters

        @Override
        public void setParameters(int baudRate, int dataBits, int stopBits, @Parity int parity) throws IOException {
            if (baudRate <= 0)
                throw new IllegalArgumentException("Invalid baud rate: " + baudRate);
            if (baudRate > MAX_BAUD_RATE)
                throw new UnsupportedOperationException("Unsupported baud rate: " + baudRate);
            switch (dataBits) {
                case DATABITS_5: case DATABITS_6: case DATABITS_7: case DATABITS_8:
                    break;
                default:
                    throw new IllegalArgumentException("Invalid data bits: " + dataBits);
            }
            switch (stopBits) {
                case STOPBITS_1: case STOPBITS_2:
                    break;
                case STOPBITS_1_5:
                    throw new UnsupportedOperationException("Unsupported stop bits: 1.5");
                default:
                    throw new IllegalArgumentException("Invalid stop bits: " + stopBits);
            }
            switch (parity) {
                case PARITY_NONE: case PARITY_ODD: case PARITY_EVEN: case PARITY_MARK: case PARITY_SPACE:
                    break;
                default:
                    throw new IllegalArgumentException("Invalid parity: " + parity);
            }
            checkOpen();
            setLineParameters(baudRate, dataBits, stopBits, parity);
        }

        /**
         * baud rate and line parameters are sent in one obfuscated command,
         * see ch9344_tty_set_termios()
         */
        private void setLineParameters(int baudRate, int dataBits, int stopBits, int parity) throws IOException {
            Random random = new Random();
            int rol = random.nextInt(16);
            int xor = random.nextInt(256);

            byte[] plain = new byte[8];
            plain[0] = (byte) (baudRate >> 24);
            plain[1] = (byte) (baudRate >> 16);
            plain[2] = (byte) (baudRate >> 8);
            plain[3] = (byte) baudRate;
            plain[4] = (byte) (stopBits == STOPBITS_2 ? 2 : 0);
            plain[5] = (byte) parity; // PARITY_* values match device encoding
            plain[6] = (byte) dataBits;
            plain[7] = (byte) calRecvTmt(baudRate);

            byte[] cmd = new byte[12];
            cmd[0] = (byte) (CMD_WB_E | mPortNumber);
            cmd[1] = (byte) R_INIT;
            cmd[2] = (byte) (mPortNumber | (rol << 4));
            System.arraycopy(calOutData(plain, rol, xor), 0, cmd, 3, 8);
            cmd[11] = (byte) xor;
            cmdOutWaitResponse(cmd);
            regWriteWithResponse(mPortNumber, R_C1, 0x0f);
            mBaudRate = baudRate;
        }

        // control lines

        @Override
        public boolean getCD() throws IOException { return getControlLines().contains(ControlLine.CD); }

        @Override
        public boolean getCTS() throws IOException { return getControlLines().contains(ControlLine.CTS); }

        @Override
        public boolean getDSR() throws IOException { return getControlLines().contains(ControlLine.DSR); }

        @Override
        public boolean getDTR() throws IOException { return getControlLines().contains(ControlLine.DTR); }

        @Override
        public boolean getRI() throws IOException { return getControlLines().contains(ControlLine.RI); }

        @Override
        public boolean getRTS() throws IOException { return getControlLines().contains(ControlLine.RTS); }

        @Override
        public void setDTR(boolean value) throws IOException {
            if (!hasControlLines())
                throw new UnsupportedOperationException();
            checkOpen();
            regWrite(mPortNumber, R_C4, value ? 0x01 : 0x00);
            mDtr = value;
        }

        @Override
        public void setRTS(boolean value) throws IOException {
            if (!hasControlLines())
                throw new UnsupportedOperationException();
            checkOpen();
            regWrite(mPortNumber, R_C4, value ? 0x11 : 0x10);
            mRts = value;
        }

        @Override
        public EnumSet<ControlLine> getControlLines() throws IOException {
            if (!hasControlLines())
                throw new UnsupportedOperationException();
            checkOpen();
            int status = mModemStatus;
            EnumSet<ControlLine> set = EnumSet.noneOf(ControlLine.class);
            if (mRts) set.add(ControlLine.RTS);
            if ((status & CTI_C) != 0) set.add(ControlLine.CTS);
            if (mDtr) set.add(ControlLine.DTR);
            if ((status & CTI_DS) != 0) set.add(ControlLine.DSR);
            if ((status & CTI_DC) != 0) set.add(ControlLine.CD);
            if ((status & CTI_R) != 0) set.add(ControlLine.RI);
            return set;
        }

        @Override
        public EnumSet<ControlLine> getSupportedControlLines() throws IOException {
            return hasControlLines() ? EnumSet.allOf(ControlLine.class) : EnumSet.noneOf(ControlLine.class);
        }

        @Override
        public void setFlowControl(FlowControl flowControl) throws IOException {
            if (!getSupportedFlowControl().contains(flowControl))
                throw new UnsupportedOperationException();
            checkOpen();
            regWrite(mPortNumber, R_C4, flowControl == FlowControl.RTS_CTS ? 0x51 : 0x50);
            mFlowControl = flowControl;
        }

        @Override
        public EnumSet<FlowControl> getSupportedFlowControl() {
            return hasControlLines() ? EnumSet.of(FlowControl.NONE, FlowControl.RTS_CTS) : EnumSet.of(FlowControl.NONE);
        }

        @Override
        public void setBreak(boolean value) throws IOException {
            checkOpen();
            regWrite(mPortNumber, R_C3, value ? 0x61 : 0x60);
        }
    }

}
