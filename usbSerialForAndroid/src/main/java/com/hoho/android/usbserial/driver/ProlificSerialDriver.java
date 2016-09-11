/* This library is free software; you can redistribute it and/or
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

/*
 * Ported to usb-serial-for-android
 * by Felix Hädicke <felixhaedicke@web.de>
 *
 * Based on the pyprolific driver written
 * by Emmanuel Blot <emmanuel.blot@free.fr>
 * See https://github.com/eblot/pyftdi
 */

package com.hoho.android.usbserial.driver;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.util.Log;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ProlificSerialDriver implements UsbSerialDriver {

    private final String TAG = ProlificSerialDriver.class.getSimpleName();

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

        private static final int PROLIFIC_VENDOR_READ_REQUEST = 0x01;
        private static final int PROLIFIC_VENDOR_WRITE_REQUEST = 0x01;

        private static final int PROLIFIC_VENDOR_OUT_REQTYPE = UsbConstants.USB_DIR_OUT
                | UsbConstants.USB_TYPE_VENDOR;

        private static final int PROLIFIC_VENDOR_IN_REQTYPE = UsbConstants.USB_DIR_IN
                | UsbConstants.USB_TYPE_VENDOR;

        private static final int PROLIFIC_CTRL_OUT_REQTYPE = UsbConstants.USB_DIR_OUT
                | UsbConstants.USB_TYPE_CLASS | USB_RECIP_INTERFACE;

        private static final int WRITE_ENDPOINT = 0x02;
        private static final int READ_ENDPOINT = 0x83;
        private static final int INTERRUPT_ENDPOINT = 0x81;

        private static final int FLUSH_RX_REQUEST = 0x08;
        private static final int FLUSH_TX_REQUEST = 0x09;

        private static final int SET_LINE_REQUEST = 0x20;
        private static final int SET_CONTROL_REQUEST = 0x22;

        private static final int CONTROL_DTR = 0x01;
        private static final int CONTROL_RTS = 0x02;

        private static final int STATUS_FLAG_CD = 0x01;
        private static final int STATUS_FLAG_DSR = 0x02;
        private static final int STATUS_FLAG_RI = 0x08;
        private static final int STATUS_FLAG_CTS = 0x80;

        private static final int STATUS_BUFFER_SIZE = 10;
        private static final int STATUS_BYTE_IDX = 8;

        private static final int DEVICE_TYPE_HX = 0;
        private static final int DEVICE_TYPE_0 = 1;
        private static final int DEVICE_TYPE_1 = 2;

        private int mDeviceType = DEVICE_TYPE_HX;

        private UsbEndpoint mReadEndpoint;
        private UsbEndpoint mWriteEndpoint;
        private UsbEndpoint mInterruptEndpoint;

        private int mControlLinesValue = 0;

        private int mBaudRate = -1, mDataBits = -1, mStopBits = -1, mParity = -1;

        private int mStatus = 0;
        private volatile Thread mReadStatusThread = null;
        private final Object mReadStatusThreadLock = new Object();
        boolean mStopReadStatusThread = false;
        private IOException mReadStatusException = null;


        public ProlificSerialPort(UsbDevice device, int portNumber) {
            super(device, portNumber);
        }

        @Override
        public UsbSerialDriver getDriver() {
            return ProlificSerialDriver.this;
        }

        private final byte[] inControlTransfer(int requestType, int request,
                int value, int index, int length) throws IOException {
            byte[] buffer = new byte[length];
            int result = mConnection.controlTransfer(requestType, request, value,
                    index, buffer, length, USB_READ_TIMEOUT_MILLIS);
            if (result != length) {
                throw new IOException(
                        String.format("ControlTransfer with value 0x%x failed: %d",
                                value, result));
            }
            return buffer;
        }

        private final void outControlTransfer(int requestType, int request,
                int value, int index, byte[] data) throws IOException {
            int length = (data == null) ? 0 : data.length;
            int result = mConnection.controlTransfer(requestType, request, value,
                    index, data, length, USB_WRITE_TIMEOUT_MILLIS);
            if (result != length) {
                throw new IOException(
                        String.format("ControlTransfer with value 0x%x failed: %d",
                                value, result));
            }
        }

        private final byte[] vendorIn(int value, int index, int length)
                throws IOException {
            return inControlTransfer(PROLIFIC_VENDOR_IN_REQTYPE,
                    PROLIFIC_VENDOR_READ_REQUEST, value, index, length);
        }

        private final void vendorOut(int value, int index, byte[] data)
                throws IOException {
            outControlTransfer(PROLIFIC_VENDOR_OUT_REQTYPE,
                    PROLIFIC_VENDOR_WRITE_REQUEST, value, index, data);
        }

        private void resetDevice() throws IOException {
            purgeHwBuffers(true, true);
        }

        private final void ctrlOut(int request, int value, int index, byte[] data)
                throws IOException {
            outControlTransfer(PROLIFIC_CTRL_OUT_REQTYPE, request, value, index,
                    data);
        }

        private void doBlackMagic() throws IOException {
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
            vendorOut(2, (mDeviceType == DEVICE_TYPE_HX) ? 0x44 : 0x24, null);
        }

        private void setControlLines(int newControlLinesValue) throws IOException {
            ctrlOut(SET_CONTROL_REQUEST, newControlLinesValue, 0, null);
            mControlLinesValue = newControlLinesValue;
        }

        private final void readStatusThreadFunction() {
            try {
                while (!mStopReadStatusThread) {
                    byte[] buffer = new byte[STATUS_BUFFER_SIZE];
                    int readBytesCount = mConnection.bulkTransfer(mInterruptEndpoint,
                            buffer,
                            STATUS_BUFFER_SIZE,
                            500);
                    if (readBytesCount > 0) {
                        if (readBytesCount == STATUS_BUFFER_SIZE) {
                            mStatus = buffer[STATUS_BYTE_IDX] & 0xff;
                        } else {
                            throw new IOException(
                                    String.format("Invalid CTS / DSR / CD / RI status buffer received, expected %d bytes, but received %d",
                                            STATUS_BUFFER_SIZE,
                                            readBytesCount));
                        }
                    }
                }
            } catch (IOException e) {
                mReadStatusException = e;
            }
        }

        private final int getStatus() throws IOException {
            if ((mReadStatusThread == null) && (mReadStatusException == null)) {
                synchronized (mReadStatusThreadLock) {
                    if (mReadStatusThread == null) {
                        byte[] buffer = new byte[STATUS_BUFFER_SIZE];
                        int readBytes = mConnection.bulkTransfer(mInterruptEndpoint,
                                buffer,
                                STATUS_BUFFER_SIZE,
                                100);
                        if (readBytes != STATUS_BUFFER_SIZE) {
                            Log.w(TAG, "Could not read initial CTS / DSR / CD / RI status");
                        } else {
                            mStatus = buffer[STATUS_BYTE_IDX] & 0xff;
                        }

                        mReadStatusThread = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                readStatusThreadFunction();
                            }
                        });
                        mReadStatusThread.setDaemon(true);
                        mReadStatusThread.start();
                    }
                }
            }

            /* throw and clear an exception which occured in the status read thread */
            IOException readStatusException = mReadStatusException;
            if (mReadStatusException != null) {
                mReadStatusException = null;
                throw readStatusException;
            }

            return mStatus;
        }

        private final boolean testStatusFlag(int flag) throws IOException {
            return ((getStatus() & flag) == flag);
        }

        @Override
        public void open(UsbDeviceConnection connection) throws IOException {
            if (mConnection != null) {
                throw new IOException("Already open");
            }

            UsbInterface usbInterface = mDevice.getInterface(0);

            if (!connection.claimInterface(usbInterface, true)) {
                throw new IOException("Error claiming Prolific interface 0");
            }

            mConnection = connection;
            boolean opened = false;
            try {
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

                if (mDevice.getDeviceClass() == 0x02) {
                    mDeviceType = DEVICE_TYPE_0;
                } else {
                    try {
                        Method getRawDescriptorsMethod
                            = mConnection.getClass().getMethod("getRawDescriptors");
                        byte[] rawDescriptors
                            = (byte[]) getRawDescriptorsMethod.invoke(mConnection);
                        byte maxPacketSize0 = rawDescriptors[7];
                        if (maxPacketSize0 == 64) {
                            mDeviceType = DEVICE_TYPE_HX;
                        } else if ((mDevice.getDeviceClass() == 0x00)
                                || (mDevice.getDeviceClass() == 0xff)) {
                            mDeviceType = DEVICE_TYPE_1;
                        } else {
                          Log.w(TAG, "Could not detect PL2303 subtype, "
                              + "Assuming that it is a HX device");
                          mDeviceType = DEVICE_TYPE_HX;
                        }
                    } catch (NoSuchMethodException e) {
                        Log.w(TAG, "Method UsbDeviceConnection.getRawDescriptors, "
                                + "required for PL2303 subtype detection, not "
                                + "available! Assuming that it is a HX device");
                        mDeviceType = DEVICE_TYPE_HX;
                    } catch (Exception e) {
                        Log.e(TAG, "An unexpected exception occured while trying "
                                + "to detect PL2303 subtype", e);
                    }
                }

                setControlLines(mControlLinesValue);
                resetDevice();

                doBlackMagic();
                opened = true;
            } finally {
                if (!opened) {
                    mConnection = null;
                    connection.releaseInterface(usbInterface);
                }
            }
        }

        @Override
        public void close() throws IOException {
            if (mConnection == null) {
                throw new IOException("Already closed");
            }
            try {
                mStopReadStatusThread = true;
                synchronized (mReadStatusThreadLock) {
                    if (mReadStatusThread != null) {
                        try {
                            mReadStatusThread.join();
                        } catch (Exception e) {
                            Log.w(TAG, "An error occured while waiting for status read thread", e);
                        }
                    }
                }
                resetDevice();
            } finally {
                try {
                    mConnection.releaseInterface(mDevice.getInterface(0));
                } finally {
                    mConnection = null;
                }
            }
        }

        @Override
        public int read(byte[] dest, int timeoutMillis) throws IOException {
            synchronized (mReadBufferLock) {
                int readAmt = Math.min(dest.length, mReadBuffer.length);
                int numBytesRead = mConnection.bulkTransfer(mReadEndpoint, mReadBuffer,
                        readAmt, timeoutMillis);
                if (numBytesRead < 0) {
                    return 0;
                }
                System.arraycopy(mReadBuffer, 0, dest, 0, numBytesRead);
                return numBytesRead;
            }
        }

        @Override
        public int write(byte[] src, int timeoutMillis) throws IOException {
            int offset = 0;

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

                    amtWritten = mConnection.bulkTransfer(mWriteEndpoint,
                            writeBuffer, writeLength, timeoutMillis);
                }

                if (amtWritten <= 0) {
                    throw new IOException("Error writing " + writeLength
                            + " bytes at offset " + offset + " length="
                            + src.length);
                }

                offset += amtWritten;
            }
            return offset;
        }

        @Override
        public void setParameters(int baudRate, int dataBits, int stopBits,
                int parity) throws IOException {
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
                throw new IllegalArgumentException("Unknown stopBits value: " + stopBits);
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
                throw new IllegalArgumentException("Unknown parity value: " + parity);
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
            return ((mControlLinesValue & CONTROL_DTR) == CONTROL_DTR);
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
            return ((mControlLinesValue & CONTROL_RTS) == CONTROL_RTS);
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
        public boolean purgeHwBuffers(boolean purgeReadBuffers, boolean purgeWriteBuffers) throws IOException {
            if (purgeReadBuffers) {
                vendorOut(FLUSH_RX_REQUEST, 0, null);
            }

            if (purgeWriteBuffers) {
                vendorOut(FLUSH_TX_REQUEST, 0, null);
            }

            return purgeReadBuffers || purgeWriteBuffers;
        }
    }

    public static Map<Integer, int[]> getSupportedDevices() {
        final Map<Integer, int[]> supportedDevices = new LinkedHashMap<Integer, int[]>();
        supportedDevices.put(Integer.valueOf(UsbId.VENDOR_PROLIFIC),
                new int[] { UsbId.PROLIFIC_PL2303, });
        return supportedDevices;
    }
}
