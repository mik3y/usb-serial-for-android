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

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbRequest;
import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Cp21xxSerialDriver implements UsbSerialDriver {

    private static final String TAG = Cp21xxSerialDriver.class.getSimpleName();

    private final UsbDevice mDevice;
    private final List<UsbSerialPort> mPorts;

    public Cp21xxSerialDriver(UsbDevice device) {
        mDevice = device;
        mPorts = new ArrayList<>();
        for( int port = 0; port < device.getInterfaceCount(); port++) {
            mPorts.add(new Cp21xxSerialPort(mDevice, port));
        }
    }

    @Override
    public UsbDevice getDevice() {
        return mDevice;
    }

    @Override
    public List<UsbSerialPort> getPorts() {
        return mPorts;
    }

    public class Cp21xxSerialPort extends CommonUsbSerialPort {

        private static final int DEFAULT_BAUD_RATE = 9600;

        private static final int USB_WRITE_TIMEOUT_MILLIS = 5000;

        /*
         * Configuration Request Types
         */
        private static final int REQTYPE_HOST_TO_DEVICE = 0x41;

        /*
         * Configuration Request Codes
         */
        private static final int SILABSER_IFC_ENABLE_REQUEST_CODE = 0x00;
        private static final int SILABSER_SET_BAUDDIV_REQUEST_CODE = 0x01;
        private static final int SILABSER_SET_LINE_CTL_REQUEST_CODE = 0x03;
        private static final int SILABSER_SET_MHS_REQUEST_CODE = 0x07;
        private static final int SILABSER_SET_BAUDRATE = 0x1E;
        private static final int SILABSER_FLUSH_REQUEST_CODE = 0x12;

       private static final int FLUSH_READ_CODE = 0x0a;
       private static final int FLUSH_WRITE_CODE = 0x05;

        /*
         * SILABSER_IFC_ENABLE_REQUEST_CODE
         */
        private static final int UART_ENABLE = 0x0001;
        private static final int UART_DISABLE = 0x0000;

        /*
         * SILABSER_SET_BAUDDIV_REQUEST_CODE
         */
        private static final int BAUD_RATE_GEN_FREQ = 0x384000;

        /*
         * SILABSER_SET_MHS_REQUEST_CODE
         */
        private static final int MCR_DTR = 0x0001;
        private static final int MCR_RTS = 0x0002;
        private static final int MCR_ALL = 0x0003;

        private static final int CONTROL_WRITE_DTR = 0x0100;
        private static final int CONTROL_WRITE_RTS = 0x0200;

        private final Boolean mEnableAsyncReads;
        private UsbEndpoint mReadEndpoint;
        private UsbEndpoint mWriteEndpoint;
        private UsbRequest mUsbRequest;

        // second port of Cp2105 has limited baudRate, dataBits, stopBits, parity
        // unsupported baudrate returns error at controlTransfer(), other parameters are silently ignored
        private boolean mIsRestrictedPort;

        public Cp21xxSerialPort(UsbDevice device, int portNumber) {
            super(device, portNumber);
            mEnableAsyncReads = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1);
        }

        @Override
        public UsbSerialDriver getDriver() {
            return Cp21xxSerialDriver.this;
        }

        private int setConfigSingle(int request, int value) {
            return mConnection.controlTransfer(REQTYPE_HOST_TO_DEVICE, request, value,
                    mPortNumber, null, 0, USB_WRITE_TIMEOUT_MILLIS);
        }

        @Override
        public void open(UsbDeviceConnection connection) throws IOException {
            if (mConnection != null) {
                throw new IOException("Already opened.");
            }

            mConnection = connection;
            boolean opened = false;
            mIsRestrictedPort = mDevice.getInterfaceCount() == 2 && mPortNumber == 1;
            try {
                if(mPortNumber >= mDevice.getInterfaceCount()) {
                    throw new IOException("Unknown port number");
                }
                UsbInterface dataIface = mDevice.getInterface(mPortNumber);
                if (!mConnection.claimInterface(dataIface, true)) {
                    throw new IOException("Could not claim interface " + mPortNumber);
                }
                for (int i = 0; i < dataIface.getEndpointCount(); i++) {
                    UsbEndpoint ep = dataIface.getEndpoint(i);
                    if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.getDirection() == UsbConstants.USB_DIR_IN) {
                            mReadEndpoint = ep;
                        } else {
                            mWriteEndpoint = ep;
                        }
                    }
                }

                setConfigSingle(SILABSER_IFC_ENABLE_REQUEST_CODE, UART_ENABLE);
                setConfigSingle(SILABSER_SET_MHS_REQUEST_CODE, MCR_ALL | CONTROL_WRITE_DTR | CONTROL_WRITE_RTS);
                setConfigSingle(SILABSER_SET_BAUDDIV_REQUEST_CODE, BAUD_RATE_GEN_FREQ / DEFAULT_BAUD_RATE);
    //            setParameters(DEFAULT_BAUD_RATE, DEFAULT_DATA_BITS, DEFAULT_STOP_BITS, DEFAULT_PARITY);
                opened = true;
            } finally {
                if (!opened) {
                    try {
                        close();
                    } catch (IOException e) {
                        // Ignore IOExceptions during close()
                    }
                }
            }
        }

        @Override
        public void close() throws IOException {
            if (mConnection == null) {
                throw new IOException("Already closed");
            }
            synchronized (mEnableAsyncReads) {
                if(mUsbRequest != null) {
                    mUsbRequest.cancel();
                }
            }
            try {
                setConfigSingle(SILABSER_IFC_ENABLE_REQUEST_CODE, UART_DISABLE);
                mConnection.close();
            } finally {
                mConnection = null;
            }
        }

        @Override
        public int read(byte[] dest, int timeoutMillis) throws IOException {
            if (mEnableAsyncReads) {
                final UsbRequest request = new UsbRequest();
                try {
                    request.initialize(mConnection, mReadEndpoint);
                    final ByteBuffer buf = ByteBuffer.wrap(dest);
                    if (!request.queue(buf, dest.length)) {
                        throw new IOException("Error queueing request.");
                    }
                    mUsbRequest = request;
                    final UsbRequest response = mConnection.requestWait();
                    synchronized (mEnableAsyncReads) {
                        mUsbRequest = null;
                    }
                    if (response == null) {
                        throw new IOException("Null response");
                    }

                    final int nread = buf.position();
                    if (nread > 0) {
                        //Log.d(TAG, HexDump.dumpHexString(dest, 0, Math.min(32, dest.length)));
                        return nread;
                    } else {
                        return 0;
                    }
                } finally {
                    mUsbRequest = null;
                    request.close();
                }
            } else {
                final int numBytesRead;
                synchronized (mReadBufferLock) {
                    int readAmt = Math.min(dest.length, mReadBuffer.length);
                    numBytesRead = mConnection.bulkTransfer(mReadEndpoint, mReadBuffer, readAmt,
                            timeoutMillis);
                    if (numBytesRead < 0) {
                        // This sucks: we get -1 on timeout, not 0 as preferred.
                        // We *should* use UsbRequest, except it has a bug/api oversight
                        // where there is no way to determine the number of bytes read
                        // in response :\ -- http://b.android.com/28023
                        return 0;
                    }
                    System.arraycopy(mReadBuffer, 0, dest, 0, numBytesRead);
                }
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

                    amtWritten = mConnection.bulkTransfer(mWriteEndpoint, writeBuffer, writeLength,
                            timeoutMillis);
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

        private void setBaudRate(int baudRate) throws IOException {
            byte[] data = new byte[] {
                    (byte) ( baudRate & 0xff),
                    (byte) ((baudRate >> 8 ) & 0xff),
                    (byte) ((baudRate >> 16) & 0xff),
                    (byte) ((baudRate >> 24) & 0xff)
            };
            int ret = mConnection.controlTransfer(REQTYPE_HOST_TO_DEVICE, SILABSER_SET_BAUDRATE,
                    0, mPortNumber, data, 4, USB_WRITE_TIMEOUT_MILLIS);
            if (ret < 0) {
                throw new IOException("Error setting baud rate.");
            }
        }

        @Override
        public void setParameters(int baudRate, int dataBits, int stopBits, int parity)
                throws IOException {
            setBaudRate(baudRate);

            int configDataBits = 0;
            switch (dataBits) {
                case DATABITS_5:
                    if(mIsRestrictedPort)
                        throw new IllegalArgumentException("Unsupported dataBits value: " + dataBits);
                    configDataBits |= 0x0500;
                    break;
                case DATABITS_6:
                    if(mIsRestrictedPort)
                        throw new IllegalArgumentException("Unsupported dataBits value: " + dataBits);
                    configDataBits |= 0x0600;
                    break;
                case DATABITS_7:
                    if(mIsRestrictedPort)
                        throw new IllegalArgumentException("Unsupported dataBits value: " + dataBits);
                    configDataBits |= 0x0700;
                    break;
                case DATABITS_8:
                    configDataBits |= 0x0800;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown dataBits value: " + dataBits);
            }
            
            switch (parity) {
                case PARITY_NONE:
                    break;
                case PARITY_ODD:
                    configDataBits |= 0x0010;
                    break;
                case PARITY_EVEN:
                    configDataBits |= 0x0020;
                    break;
                case PARITY_MARK:
                    if(mIsRestrictedPort)
                        throw new IllegalArgumentException("Unsupported parity value: mark");
                    configDataBits |= 0x0030;
                    break;
                case PARITY_SPACE:
                    if(mIsRestrictedPort)
                        throw new IllegalArgumentException("Unsupported parity value: space");
                    configDataBits |= 0x0040;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown parity value: " + parity);
            }
            
            switch (stopBits) {
                case STOPBITS_1:
                    break;
                case STOPBITS_1_5:
                    throw new IllegalArgumentException("Unsupported stopBits value: 1.5");
                case STOPBITS_2:
                    if(mIsRestrictedPort)
                        throw new IllegalArgumentException("Unsupported stopBits value: 2");
                    configDataBits |= 2;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown stopBits value: " + stopBits);
            }
            setConfigSingle(SILABSER_SET_LINE_CTL_REQUEST_CODE, configDataBits);
        }

        @Override
        public boolean getCD() throws IOException {
            return false;
        }

        @Override
        public boolean getCTS() throws IOException {
            return false;
        }

        @Override
        public boolean getDSR() throws IOException {
            return false;
        }

        @Override
        public boolean getDTR() throws IOException {
            return true;
        }

        @Override
        public void setDTR(boolean value) throws IOException {
        }

        @Override
        public boolean getRI() throws IOException {
            return false;
        }

        @Override
        public boolean getRTS() throws IOException {
            return true;
        }

        @Override
        public void setRTS(boolean value) throws IOException {
        }

        @Override
        public boolean purgeHwBuffers(boolean purgeReadBuffers,
                boolean purgeWriteBuffers) throws IOException {
            int value = (purgeReadBuffers ? FLUSH_READ_CODE : 0)
                    | (purgeWriteBuffers ? FLUSH_WRITE_CODE : 0);

            if (value != 0) {
                setConfigSingle(SILABSER_FLUSH_REQUEST_CODE, value);
            }

            return true;
        }

    }

    public static Map<Integer, int[]> getSupportedDevices() {
        final Map<Integer, int[]> supportedDevices = new LinkedHashMap<Integer, int[]>();
        supportedDevices.put(Integer.valueOf(UsbId.VENDOR_SILABS),
                new int[] {
            UsbId.SILABS_CP2102,
            UsbId.SILABS_CP2105,
            UsbId.SILABS_CP2108,
            UsbId.SILABS_CP2110
        });
        return supportedDevices;
    }

}
