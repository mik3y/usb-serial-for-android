/*
 * Ported to usb-serial-for-android by Felix Hädicke <felixhaedicke@web.de>
 *
 * Based on the pyprolific driver written by Emmanuel Blot <emmanuel.blot@free.fr>
 * See https://github.com/eblot/pyftdi
 *
 * Project home page: https://github.com/mik3y/usb-serial-for-android
 */

package com.hoho.android.usbserial.driver;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.util.Log;

import com.hoho.android.usbserial.BuildConfig;
import com.hoho.android.usbserial.util.MonotonicClock;

import java.io.IOException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ProlificSerialDriver implements UsbSerialDriver {

    private final String TAG = ProlificSerialDriver.class.getSimpleName();

    private final static int[] standardBaudRates = {
            75, 150, 300, 600, 1200, 1800, 2400, 3600, 4800, 7200, 9600, 14400, 19200,
            28800, 38400, 57600, 115200, 128000, 134400, 161280, 201600, 230400, 268800,
            403200, 460800, 614400, 806400, 921600, 1228800, 2457600, 3000000, 6000000
    };
    protected enum DeviceType { DEVICE_TYPE_01, DEVICE_TYPE_T, DEVICE_TYPE_HX, DEVICE_TYPE_HXN }

    private final UsbDevice mDevice;
    private final UsbSerialPort mPort;

    public ProlificSerialDriver(UsbDevice device) {
        mDevice = device;
        mPort = new ProlificSerialPort(mDevice, 0);
    }

    @Override
    public List<UsbSerialPort> getPorts() {
        return Collections.singletonList(mPort);
    }

    @Override
    public UsbDevice getDevice() {
        return mDevice;
    }

    class ProlificSerialPort extends CommonUsbSerialPort {

        private static final int USB_READ_TIMEOUT_MILLIS = 1000;
        private static final int USB_WRITE_TIMEOUT_MILLIS = 5000;

        private static final int USB_RECIP_INTERFACE = 0x01;

        private static final int VENDOR_READ_REQUEST = 0x01;
        private static final int VENDOR_WRITE_REQUEST = 0x01;
        private static final int VENDOR_READ_HXN_REQUEST = 0x81;
        private static final int VENDOR_WRITE_HXN_REQUEST = 0x80;

        private static final int VENDOR_OUT_REQTYPE = UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_VENDOR;
        private static final int VENDOR_IN_REQTYPE = UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_VENDOR;
        private static final int CTRL_OUT_REQTYPE = UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_CLASS | USB_RECIP_INTERFACE;

        private static final int WRITE_ENDPOINT = 0x02;
        private static final int READ_ENDPOINT = 0x83;
        private static final int INTERRUPT_ENDPOINT = 0x81;

        private static final int RESET_HXN_REQUEST = 0x07;
        private static final int FLUSH_RX_REQUEST = 0x08;
        private static final int FLUSH_TX_REQUEST = 0x09;
        private static final int SET_LINE_REQUEST = 0x20; // same as CDC SET_LINE_CODING
        private static final int SET_CONTROL_REQUEST = 0x22; // same as CDC SET_CONTROL_LINE_STATE
        private static final int SEND_BREAK_REQUEST = 0x23; // same as CDC SEND_BREAK
        private static final int GET_CONTROL_HXN_REQUEST = 0x80;
        private static final int GET_CONTROL_REQUEST = 0x87;
        private static final int STATUS_NOTIFICATION = 0xa1; // similar to CDC SERIAL_STATE but different length

        /* RESET_HXN_REQUEST */
        private static final int RESET_HXN_RX_PIPE = 1;
        private static final int RESET_HXN_TX_PIPE = 2;

        /* SET_CONTROL_REQUEST */
        private static final int CONTROL_DTR = 0x01;
        private static final int CONTROL_RTS = 0x02;

        /* GET_CONTROL_REQUEST */
        private static final int GET_CONTROL_FLAG_CD = 0x02;
        private static final int GET_CONTROL_FLAG_DSR = 0x04;
        private static final int GET_CONTROL_FLAG_RI = 0x01;
        private static final int GET_CONTROL_FLAG_CTS = 0x08;

        /* GET_CONTROL_HXN_REQUEST */
        private static final int GET_CONTROL_HXN_FLAG_CD = 0x40;
        private static final int GET_CONTROL_HXN_FLAG_DSR = 0x20;
        private static final int GET_CONTROL_HXN_FLAG_RI = 0x80;
        private static final int GET_CONTROL_HXN_FLAG_CTS = 0x08;

        /* interrupt endpoint read */
        private static final int STATUS_FLAG_CD = 0x01;
        private static final int STATUS_FLAG_DSR = 0x02;
        private static final int STATUS_FLAG_RI = 0x08;
        private static final int STATUS_FLAG_CTS = 0x80;

        private static final int STATUS_BUFFER_SIZE = 10;
        private static final int STATUS_BYTE_IDX = 8;

        protected DeviceType mDeviceType = DeviceType.DEVICE_TYPE_HX;
        private UsbEndpoint mInterruptEndpoint;
        private int mControlLinesValue = 0;
        private int mBaudRate = -1, mDataBits = -1, mStopBits = -1, mParity = -1;

        private int mStatus = 0;
        private volatile Thread mReadStatusThread = null;
        private final Object mReadStatusThreadLock = new Object();
        private boolean mStopReadStatusThread = false;
        private IOException mReadStatusException = null;


        public ProlificSerialPort(UsbDevice device, int portNumber) {
            super(device, portNumber);
        }

        @Override
        public UsbSerialDriver getDriver() {
            return ProlificSerialDriver.this;
        }

        private byte[] inControlTransfer(int requestType, int request, int value, int index, int length) throws IOException {
            byte[] buffer = new byte[length];
            int result = mConnection.controlTransfer(requestType, request, value, index, buffer, length, USB_READ_TIMEOUT_MILLIS);
            if (result != length) {
                throw new IOException(String.format("ControlTransfer %s 0x%x failed: %d",mDeviceType.name(), value, result));
            }
            return buffer;
        }

        private void outControlTransfer(int requestType, int request, int value, int index, byte[] data) throws IOException {
            int length = (data == null) ? 0 : data.length;
            int result = mConnection.controlTransfer(requestType, request, value, index, data, length, USB_WRITE_TIMEOUT_MILLIS);
            if (result != length) {
                throw new IOException( String.format("ControlTransfer %s 0x%x failed: %d", mDeviceType.name(), value, result));
            }
        }

        private byte[] vendorIn(int value, int index, int length) throws IOException {
            int request = (mDeviceType == DeviceType.DEVICE_TYPE_HXN) ? VENDOR_READ_HXN_REQUEST : VENDOR_READ_REQUEST;
            return inControlTransfer(VENDOR_IN_REQTYPE, request, value, index, length);
        }

        private void vendorOut(int value, int index, byte[] data) throws IOException {
            int request = (mDeviceType == DeviceType.DEVICE_TYPE_HXN) ? VENDOR_WRITE_HXN_REQUEST : VENDOR_WRITE_REQUEST;
            outControlTransfer(VENDOR_OUT_REQTYPE, request, value, index, data);
        }

        private void resetDevice() throws IOException {
            purgeHwBuffers(true, true);
        }

        private void ctrlOut(int request, int value, int index, byte[] data) throws IOException {
            outControlTransfer(CTRL_OUT_REQTYPE, request, value, index, data);
        }

        private boolean testHxStatus() {
            try {
                inControlTransfer(VENDOR_IN_REQTYPE, VENDOR_READ_REQUEST, 0x8080, 0, 1);
                return true;
            } catch(IOException ignored) {
                return false;
            }
        }

        private void doBlackMagic() throws IOException {
            if (mDeviceType == DeviceType.DEVICE_TYPE_HXN)
                return;
            vendorIn(0x8484, 0, 1);
            vendorOut(0x0404, 0, null);
            vendorIn(0x8484, 0, 1);
            vendorIn(0x8383, 0, 1);
            vendorIn(0x8484, 0, 1);
            vendorOut(0x0404, 1, null);
            vendorIn(0x8484, 0, 1);
            vendorIn(0x8383, 0, 1);
            vendorOut(0, 1, null);
            vendorOut(1, 0, null);
            vendorOut(2, (mDeviceType == DeviceType.DEVICE_TYPE_01) ? 0x24 : 0x44, null);
        }

        private void setControlLines(int newControlLinesValue) throws IOException {
            ctrlOut(SET_CONTROL_REQUEST, newControlLinesValue, 0, null);
            mControlLinesValue = newControlLinesValue;
        }

        private void readStatusThreadFunction() {
            try {
                while (!mStopReadStatusThread) {
                    byte[] buffer = new byte[STATUS_BUFFER_SIZE];
                    long endTime = MonotonicClock.millis() + 500;
                    int readBytesCount = mConnection.bulkTransfer(mInterruptEndpoint, buffer, STATUS_BUFFER_SIZE, 500);
                    if(readBytesCount == -1)
                        testConnection(MonotonicClock.millis() < endTime);
                    if (readBytesCount > 0) {
                        if (readBytesCount != STATUS_BUFFER_SIZE) {
                            throw new IOException("Invalid status notification, expected " + STATUS_BUFFER_SIZE + " bytes, got " + readBytesCount);
                        } else if(buffer[0] != (byte)STATUS_NOTIFICATION ) {
                            throw new IOException("Invalid status notification, expected " + STATUS_NOTIFICATION + " request, got " + buffer[0]);
                        } else {
                            mStatus = buffer[STATUS_BYTE_IDX] & 0xff;
                        }
                    }
                }
            } catch (IOException e) {
                mReadStatusException = e;
            }
            //Log.d(TAG, "end control line status thread " + mStopReadStatusThread + " " + (mReadStatusException == null ? "-" : mReadStatusException.getMessage()));
        }

        private int getStatus() throws IOException {
            if ((mReadStatusThread == null) && (mReadStatusException == null)) {
                synchronized (mReadStatusThreadLock) {
                    if (mReadStatusThread == null) {
                        mStatus = 0;
                        if(mDeviceType == DeviceType.DEVICE_TYPE_HXN) {
                            byte[] data = vendorIn(GET_CONTROL_HXN_REQUEST, 0, 1);
                            if ((data[0] & GET_CONTROL_HXN_FLAG_CTS) == 0) mStatus |= STATUS_FLAG_CTS;
                            if ((data[0] & GET_CONTROL_HXN_FLAG_DSR) == 0) mStatus |= STATUS_FLAG_DSR;
                            if ((data[0] & GET_CONTROL_HXN_FLAG_CD) == 0) mStatus |= STATUS_FLAG_CD;
                            if ((data[0] & GET_CONTROL_HXN_FLAG_RI) == 0) mStatus |= STATUS_FLAG_RI;
                        } else {
                            byte[] data = vendorIn(GET_CONTROL_REQUEST, 0, 1);
                            if ((data[0] & GET_CONTROL_FLAG_CTS) == 0) mStatus |= STATUS_FLAG_CTS;
                            if ((data[0] & GET_CONTROL_FLAG_DSR) == 0) mStatus |= STATUS_FLAG_DSR;
                            if ((data[0] & GET_CONTROL_FLAG_CD) == 0) mStatus |= STATUS_FLAG_CD;
                            if ((data[0] & GET_CONTROL_FLAG_RI) == 0) mStatus |= STATUS_FLAG_RI;
                        }
                        //Log.d(TAG, "start control line status thread " + mStatus);
                        mReadStatusThread = new Thread(this::readStatusThreadFunction);
                        mReadStatusThread.setDaemon(true);
                        mReadStatusThread.start();
                    }
                }
            }

            /* throw and clear an exception which occured in the status read thread */
            IOException readStatusException = mReadStatusException;
            if (mReadStatusException != null) {
                mReadStatusException = null;
                throw new IOException(readStatusException);
            }

            return mStatus;
        }

        private boolean testStatusFlag(int flag) throws IOException {
            return ((getStatus() & flag) == flag);
        }

        @Override
        public void openInt() throws IOException {
            UsbInterface usbInterface = mDevice.getInterface(0);

            if (!mConnection.claimInterface(usbInterface, true)) {
                throw new IOException("Error claiming Prolific interface 0");
            }

            for (int i = 0; i < usbInterface.getEndpointCount(); ++i) {
                UsbEndpoint currentEndpoint = usbInterface.getEndpoint(i);

                switch (currentEndpoint.getAddress()) {
                case READ_ENDPOINT:
                    mReadEndpoint = currentEndpoint;
                    break;

                case WRITE_ENDPOINT:
                    mWriteEndpoint = currentEndpoint;
                    break;

                case INTERRUPT_ENDPOINT:
                    mInterruptEndpoint = currentEndpoint;
                    break;
                }
            }

            byte[] rawDescriptors = mConnection.getRawDescriptors();
            if(rawDescriptors == null || rawDescriptors.length < 14) {
                throw new IOException("Could not get device descriptors");
            }
            int usbVersion = (rawDescriptors[3] << 8) + rawDescriptors[2];
            int deviceVersion = (rawDescriptors[13] << 8) + rawDescriptors[12];
            byte maxPacketSize0 = rawDescriptors[7];
            if (mDevice.getDeviceClass() == 0x02 || maxPacketSize0 != 64) {
                mDeviceType = DeviceType.DEVICE_TYPE_01;
            } else if(usbVersion == 0x200) {
                if(deviceVersion == 0x300 && testHxStatus()) {
                    mDeviceType = DeviceType.DEVICE_TYPE_T; // TA
                } else if(deviceVersion == 0x500 && testHxStatus()) {
                    mDeviceType = DeviceType.DEVICE_TYPE_T; // TB
                } else {
                    mDeviceType = DeviceType.DEVICE_TYPE_HXN;
                }
            } else {
                mDeviceType = DeviceType.DEVICE_TYPE_HX;
            }
            Log.d(TAG, String.format("usbVersion=%x, deviceVersion=%x, deviceClass=%d, packetSize=%d => deviceType=%s",
                    usbVersion, deviceVersion, mDevice.getDeviceClass(), maxPacketSize0, mDeviceType.name()));
            resetDevice();
            doBlackMagic();
            setControlLines(mControlLinesValue);
        }

        @Override
        public void closeInt() {
            try {
                synchronized (mReadStatusThreadLock) {
                    if (mReadStatusThread != null) {
                        try {
                            mStopReadStatusThread = true;
                            mReadStatusThread.join();
                        } catch (Exception e) {
                            Log.w(TAG, "An error occured while waiting for status read thread", e);
                        }
                        mStopReadStatusThread = false;
                        mReadStatusThread = null;
                        mReadStatusException = null;
                    }
                }
                resetDevice();
            } catch(Exception ignored) {}
            try {
                mConnection.releaseInterface(mDevice.getInterface(0));
            } catch(Exception ignored) {}
        }

        private int filterBaudRate(int baudRate) {
            if(BuildConfig.DEBUG && (baudRate & (3<<29)) == (1<<29)) {
                return baudRate & ~(1<<29); // for testing purposes accept without further checks
            }
            if (baudRate <= 0) {
                throw new IllegalArgumentException("Invalid baud rate: " + baudRate);
            }
            if (mDeviceType == DeviceType.DEVICE_TYPE_HXN) {
                return baudRate;
            }
            for(int br : standardBaudRates) {
                if (br == baudRate) {
                    return baudRate;
                }
            }
            /*
             * Formula taken from Linux + FreeBSD.
             *
             * For TA+TB devices
             *   baudrate = baseline / (mantissa * 2^exponent)
             * where
             *   mantissa = buf[10:0]
             *   exponent = buf[15:13 16]
             *
             * For other devices
             *   baudrate = baseline / (mantissa * 4^exponent)
             * where
             *   mantissa = buf[8:0]
             *   exponent = buf[11:9]
             *
             */
            int baseline, mantissa, exponent, buf, effectiveBaudRate;
            baseline = 12000000 * 32;
            mantissa = baseline / baudRate;
            if (mantissa == 0) { // > unrealistic 384 MBaud
                throw new UnsupportedOperationException("Baud rate to high");
            }
            exponent = 0;
            if (mDeviceType == DeviceType.DEVICE_TYPE_T) {
                while (mantissa >= 2048) {
                    if (exponent < 15) {
                        mantissa >>= 1;    /* divide by 2 */
                        exponent++;
                    } else { // < 7 baud
                        throw new UnsupportedOperationException("Baud rate to low");
                    }
                }
                buf = mantissa + ((exponent & ~1) << 12) + ((exponent & 1) << 16) + (1 << 31);
                effectiveBaudRate = (baseline / mantissa) >> exponent;
            } else {
                while (mantissa >= 512) {
                    if (exponent < 7) {
                        mantissa >>= 2;    /* divide by 4 */
                        exponent++;
                    } else { // < 45.8 baud
                        throw new UnsupportedOperationException("Baud rate to low");
                    }
                }
                buf = mantissa + (exponent << 9) + (1 << 31);
                effectiveBaudRate = (baseline / mantissa) >> (exponent << 1);
            }
            double baudRateError = Math.abs(1.0 - (effectiveBaudRate / (double)baudRate));
            if(baudRateError >= 0.031) // > unrealistic 11.6 Mbaud
                throw new UnsupportedOperationException(String.format("Baud rate deviation %.1f%% is higher than allowed 3%%", baudRateError*100));

            Log.d(TAG, String.format("baud rate=%d, effective=%d, error=%.1f%%, value=0x%08x, mantissa=%d, exponent=%d",
                    baudRate, effectiveBaudRate, baudRateError*100, buf, mantissa, exponent));
            return buf;
        }

        @Override
        public void setParameters(int baudRate, int dataBits, int stopBits, @Parity int parity) throws IOException {
            baudRate = filterBaudRate(baudRate);
            if ((mBaudRate == baudRate) && (mDataBits == dataBits)
                    && (mStopBits == stopBits) && (mParity == parity)) {
                // Make sure no action is performed if there is nothing to change
                return;
            }

            byte[] lineRequestData = new byte[7];
            lineRequestData[0] = (byte) (baudRate & 0xff);
            lineRequestData[1] = (byte) ((baudRate >> 8) & 0xff);
            lineRequestData[2] = (byte) ((baudRate >> 16) & 0xff);
            lineRequestData[3] = (byte) ((baudRate >> 24) & 0xff);

            switch (stopBits) {
            case STOPBITS_1:
                lineRequestData[4] = 0;
                break;
            case STOPBITS_1_5:
                lineRequestData[4] = 1;
                break;
            case STOPBITS_2:
                lineRequestData[4] = 2;
                break;
            default:
                throw new IllegalArgumentException("Invalid stop bits: " + stopBits);
            }

            switch (parity) {
            case PARITY_NONE:
                lineRequestData[5] = 0;
                break;
            case PARITY_ODD:
                lineRequestData[5] = 1;
                break;
            case PARITY_EVEN:
                lineRequestData[5] = 2;
                break;
            case PARITY_MARK:
                lineRequestData[5] = 3;
                break;
            case PARITY_SPACE:
                lineRequestData[5] = 4;
                break;
            default:
                throw new IllegalArgumentException("Invalid parity: " + parity);
            }

            if(dataBits < DATABITS_5 || dataBits > DATABITS_8) {
                throw new IllegalArgumentException("Invalid data bits: " + dataBits);
            }
            lineRequestData[6] = (byte) dataBits;

            ctrlOut(SET_LINE_REQUEST, 0, 0, lineRequestData);

            resetDevice();

            mBaudRate = baudRate;
            mDataBits = dataBits;
            mStopBits = stopBits;
            mParity = parity;
        }

        @Override
        public boolean getCD() throws IOException {
            return testStatusFlag(STATUS_FLAG_CD);
        }

        @Override
        public boolean getCTS() throws IOException {
            return testStatusFlag(STATUS_FLAG_CTS);
        }

        @Override
        public boolean getDSR() throws IOException {
            return testStatusFlag(STATUS_FLAG_DSR);
        }

        @Override
        public boolean getDTR() throws IOException {
            return (mControlLinesValue & CONTROL_DTR) != 0;
        }

        @Override
        public void setDTR(boolean value) throws IOException {
            int newControlLinesValue;
            if (value) {
                newControlLinesValue = mControlLinesValue | CONTROL_DTR;
            } else {
                newControlLinesValue = mControlLinesValue & ~CONTROL_DTR;
            }
            setControlLines(newControlLinesValue);
        }

        @Override
        public boolean getRI() throws IOException {
            return testStatusFlag(STATUS_FLAG_RI);
        }

        @Override
        public boolean getRTS() throws IOException {
            return (mControlLinesValue & CONTROL_RTS) != 0;
        }

        @Override
        public void setRTS(boolean value) throws IOException {
            int newControlLinesValue;
            if (value) {
                newControlLinesValue = mControlLinesValue | CONTROL_RTS;
            } else {
                newControlLinesValue = mControlLinesValue & ~CONTROL_RTS;
            }
            setControlLines(newControlLinesValue);
        }


        @Override
        public EnumSet<ControlLine> getControlLines() throws IOException {
            int status = getStatus();
            EnumSet<ControlLine> set = EnumSet.noneOf(ControlLine.class);
            if((mControlLinesValue & CONTROL_RTS) != 0) set.add(ControlLine.RTS);
            if((status & STATUS_FLAG_CTS) != 0) set.add(ControlLine.CTS);
            if((mControlLinesValue & CONTROL_DTR) != 0) set.add(ControlLine.DTR);
            if((status & STATUS_FLAG_DSR) != 0) set.add(ControlLine.DSR);
            if((status & STATUS_FLAG_CD) != 0) set.add(ControlLine.CD);
            if((status & STATUS_FLAG_RI) != 0) set.add(ControlLine.RI);
            return set;
        }

        @Override
        public EnumSet<ControlLine> getSupportedControlLines() throws IOException {
            return EnumSet.allOf(ControlLine.class);
        }

        @Override
        public void purgeHwBuffers(boolean purgeWriteBuffers, boolean purgeReadBuffers) throws IOException {
            if (mDeviceType == DeviceType.DEVICE_TYPE_HXN) {
                int index = 0;
                if(purgeWriteBuffers) index |= RESET_HXN_RX_PIPE;
                if(purgeReadBuffers) index |= RESET_HXN_TX_PIPE;
                if(index != 0)
                    vendorOut(RESET_HXN_REQUEST, index, null);
            } else {
                if (purgeWriteBuffers)
                    vendorOut(FLUSH_RX_REQUEST, 0, null);
                if (purgeReadBuffers)
                    vendorOut(FLUSH_TX_REQUEST, 0, null);
            }
        }

        @Override
        public void setBreak(boolean value) throws IOException {
            ctrlOut(SEND_BREAK_REQUEST, value ? 0xffff : 0, 0, null);
        }
    }

    @SuppressWarnings({"unused"})
    public static Map<Integer, int[]> getSupportedDevices() {
        final Map<Integer, int[]> supportedDevices = new LinkedHashMap<>();
        supportedDevices.put(UsbId.VENDOR_PROLIFIC,
                new int[] {
                        UsbId.PROLIFIC_PL2303,
                        UsbId.PROLIFIC_PL2303GC,
                        UsbId.PROLIFIC_PL2303GB,
                        UsbId.PROLIFIC_PL2303GT,
                        UsbId.PROLIFIC_PL2303GL,
                        UsbId.PROLIFIC_PL2303GE,
                        UsbId.PROLIFIC_PL2303GS,
                });
        return supportedDevices;
    }
}
