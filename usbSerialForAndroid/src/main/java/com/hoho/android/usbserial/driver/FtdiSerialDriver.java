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
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link CommonUsbSerialPort} implementation for a variety of FTDI devices
 * <p>
 * This driver is based on <a
 * href="http://www.intra2net.com/en/developer/libftdi">libftdi</a>, and is
 * copyright and subject to the following terms:
 *
 * <pre>
 *   Copyright (C) 2003 by Intra2net AG
 *
 *   This program is free software; you can redistribute it and/or modify
 *   it under the terms of the GNU Lesser General Public License
 *   version 2.1 as published by the Free Software Foundation;
 *
 *   opensource@intra2net.com
 *   http://www.intra2net.com/en/developer/libftdi
 * </pre>
 *
 * </p>
 * <p>
 * Some FTDI devices have not been tested; see later listing of supported and
 * unsupported devices. Devices listed as "supported" support the following
 * features:
 * <ul>
 * <li>Read and write of serial data (see
 * {@link CommonUsbSerialPort#read(byte[], int)} and
 * {@link CommonUsbSerialPort#write(byte[], int)}.</li>
 * <li>Setting serial line parameters (see
 * {@link CommonUsbSerialPort#setParameters(int, int, int, int)}.</li>
 * </ul>
 * </p>
 * <p>
 * Supported and tested devices:
 * <ul>
 * <li>{@value DeviceType#TYPE_R}</li>
 * <li>{@value DeviceType#TYPE_2232H}</li>
 * <li>{@value DeviceType#TYPE_4232H}</li>
 * </ul>
 * </p>
 * <p>
 * Unsupported but possibly working devices (please contact the author with
 * feedback or patches):
 * <ul>
 * <li>{@value DeviceType#TYPE_2232C}</li>
 * <li>{@value DeviceType#TYPE_AM}</li>
 * <li>{@value DeviceType#TYPE_BM}</li>
 * </ul>
 * </p>
 *
 * @author mike wakerly (opensource@hoho.com)
 * @see <a href="https://github.com/mik3y/usb-serial-for-android">USB Serial
 *      for Android project page</a>
 * @see <a href="http://www.ftdichip.com/">FTDI Homepage</a>
 * @see <a href="http://www.intra2net.com/en/developer/libftdi">libftdi</a>
 */
public class FtdiSerialDriver implements UsbSerialDriver {

    private final UsbDevice mDevice;
    private final List<UsbSerialPort> mPorts;

    /**
     * FTDI chip types.
     */
    private static enum DeviceType {
        TYPE_BM, TYPE_AM, TYPE_2232C, TYPE_R, TYPE_2232H, TYPE_4232H;
    }

    public FtdiSerialDriver(UsbDevice device) {
        mDevice = device;
        mPorts = new ArrayList<>();
        for( int port = 0; port < device.getInterfaceCount(); port++) {
            mPorts.add(new FtdiSerialPort(mDevice, port));
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

    private class FtdiSerialPort extends CommonUsbSerialPort {

        public static final int USB_TYPE_STANDARD = 0x00 << 5;
        public static final int USB_TYPE_CLASS = 0x00 << 5;
        public static final int USB_TYPE_VENDOR = 0x00 << 5;
        public static final int USB_TYPE_RESERVED = 0x00 << 5;

        public static final int USB_RECIP_DEVICE = 0x00;
        public static final int USB_RECIP_INTERFACE = 0x01;
        public static final int USB_RECIP_ENDPOINT = 0x02;
        public static final int USB_RECIP_OTHER = 0x03;

        public static final int USB_ENDPOINT_IN = 0x80;
        public static final int USB_ENDPOINT_OUT = 0x00;

        public static final int USB_WRITE_TIMEOUT_MILLIS = 5000;
        public static final int USB_READ_TIMEOUT_MILLIS = 5000;

        // From ftdi.h
        /**
         * Reset the port.
         */
        private static final int SIO_RESET_REQUEST = 0;

        /**
         * Set the modem control register.
         */
        private static final int SIO_MODEM_CTRL_REQUEST = 1;

        /**
         * Set flow control register.
         */
        private static final int SIO_SET_FLOW_CTRL_REQUEST = 2;

        /**
         * Set baud rate.
         */
        private static final int SIO_SET_BAUD_RATE_REQUEST = 3;

        /**
         * Set the data characteristics of the port.
         */
        private static final int SIO_SET_DATA_REQUEST = 4;

        private static final int SIO_RESET_SIO = 0;
        private static final int SIO_RESET_PURGE_RX = 1; // RX @ FTDI device = write @ usb-serial-for-android library
        private static final int SIO_RESET_PURGE_TX = 2;

        public static final int FTDI_DEVICE_OUT_REQTYPE =
                UsbConstants.USB_TYPE_VENDOR | USB_RECIP_DEVICE | USB_ENDPOINT_OUT;

        public static final int FTDI_DEVICE_IN_REQTYPE =
                UsbConstants.USB_TYPE_VENDOR | USB_RECIP_DEVICE | USB_ENDPOINT_IN;

        /**
         * Length of the modem status header, transmitted with every read.
         */
        private static final int MODEM_STATUS_HEADER_LENGTH = 2;

        private final String TAG = FtdiSerialDriver.class.getSimpleName();

        private DeviceType mType;

        private int mIndex = 0;

        public FtdiSerialPort(UsbDevice device, int portNumber) {
            super(device, portNumber);
        }

        @Override
        public UsbSerialDriver getDriver() {
            return FtdiSerialDriver.this;
        }

        /**
         * Filter FTDI status bytes from buffer
         * @param buffer The source buffer (which contains status bytes)
         *        buffer The destination buffer to write the status bytes into (can be src)
         * @param totalBytesRead Number of bytes read to src
         * @return The number of payload bytes
         */
        @Override
        protected int readFilter(byte[] buffer, int totalBytesRead) throws IOException {
            if (totalBytesRead < MODEM_STATUS_HEADER_LENGTH) {
                throw new IOException("Expected at least " + MODEM_STATUS_HEADER_LENGTH + " bytes");
            }
            int maxPacketSize = mReadEndpoint.getMaxPacketSize();
            final int packetsCount = (totalBytesRead + maxPacketSize -1 )/ maxPacketSize;
            for (int packetIdx = 0; packetIdx < packetsCount; ++packetIdx) {
                final int count = (packetIdx == (packetsCount - 1))
                        ? totalBytesRead - packetIdx * maxPacketSize - MODEM_STATUS_HEADER_LENGTH
                        : maxPacketSize - MODEM_STATUS_HEADER_LENGTH;
                if (count > 0) {
                    System.arraycopy(buffer, packetIdx * maxPacketSize + MODEM_STATUS_HEADER_LENGTH,
                                     buffer, packetIdx * (maxPacketSize - MODEM_STATUS_HEADER_LENGTH),
                                     count);
                }
            }
            return totalBytesRead - (packetsCount * 2);
        }

        void reset() throws IOException {
            // TODO(mikey): autodetect.
            mType = DeviceType.TYPE_R;
            if(mDevice.getInterfaceCount() > 1) {
                mIndex = mPortNumber + 1;
                if (mDevice.getInterfaceCount() == 2)
                    mType = DeviceType.TYPE_2232H;
                if (mDevice.getInterfaceCount() == 4)
                    mType = DeviceType.TYPE_4232H;
            }

            int result = mConnection.controlTransfer(FTDI_DEVICE_OUT_REQTYPE, SIO_RESET_REQUEST,
                    SIO_RESET_SIO, mIndex, null, 0, USB_WRITE_TIMEOUT_MILLIS);
            if (result != 0) {
                throw new IOException("Reset failed: result=" + result);
            }
        }

        @Override
        public void open(UsbDeviceConnection connection) throws IOException {
            if (mConnection != null) {
                throw new IOException("Already open");
            }
            mConnection = connection;

            boolean opened = false;
            try {
                if (connection.claimInterface(mDevice.getInterface(mPortNumber), true)) {
                    Log.d(TAG, "claimInterface " + mPortNumber + " SUCCESS");
                } else {
                    throw new IOException("Error claiming interface " + mPortNumber);
                }
                if (mDevice.getInterface(mPortNumber).getEndpointCount() < 2) {
                    throw new IOException("Insufficient number of endpoints (" +
                            mDevice.getInterface(mPortNumber).getEndpointCount() + ")");
                }
                mReadEndpoint = mDevice.getInterface(mPortNumber).getEndpoint(0);
                mWriteEndpoint = mDevice.getInterface(mPortNumber).getEndpoint(1);
                reset();
                opened = true;
            } finally {
                if (!opened) {
                    close();
                }
            }
        }

        @Override
        public void closeInt() {
            try {
                mConnection.releaseInterface(mDevice.getInterface(mPortNumber));
            } catch(Exception ignored) {}
        }

        private int setBaudRate(int baudRate) throws IOException {
            long[] vals = convertBaudrate(baudRate);
            long actualBaudrate = vals[0];
            long index = vals[1];
            long value = vals[2];
            int result = mConnection.controlTransfer(FTDI_DEVICE_OUT_REQTYPE,
                    SIO_SET_BAUD_RATE_REQUEST, (int) value, (int) index,
                    null, 0, USB_WRITE_TIMEOUT_MILLIS);
            if (result != 0) {
                throw new IOException("Setting baudrate failed: result=" + result);
            }
            return (int) actualBaudrate;
        }

        @Override
        public void setParameters(int baudRate, int dataBits, int stopBits, int parity) throws IOException {
            if(baudRate <= 0) {
                throw new IllegalArgumentException("Invalid baud rate: " + baudRate);
            }
            setBaudRate(baudRate);

            int config = 0;
            switch (dataBits) {
                case DATABITS_5:
                case DATABITS_6:
                    throw new UnsupportedOperationException("Unsupported data bits: " + dataBits);
                case DATABITS_7:
                case DATABITS_8:
                    config |= dataBits;
                    break;
                default:
                    throw new IllegalArgumentException("Invalid data bits: " + dataBits);
            }

            switch (parity) {
                case PARITY_NONE:
                    config |= (0x00 << 8);
                    break;
                case PARITY_ODD:
                    config |= (0x01 << 8);
                    break;
                case PARITY_EVEN:
                    config |= (0x02 << 8);
                    break;
                case PARITY_MARK:
                    config |= (0x03 << 8);
                    break;
                case PARITY_SPACE:
                    config |= (0x04 << 8);
                    break;
                default:
                    throw new IllegalArgumentException("Invalid parity: " + parity);
            }

            switch (stopBits) {
                case STOPBITS_1:
                    config |= (0x00 << 11);
                    break;
                case STOPBITS_1_5:
                    throw new UnsupportedOperationException("Unsupported stop bits: 1.5");
                case STOPBITS_2:
                    config |= (0x02 << 11);
                    break;
                default:
                    throw new IllegalArgumentException("Invalid stop bits: " + stopBits);
            }

            int result = mConnection.controlTransfer(FTDI_DEVICE_OUT_REQTYPE,
                    SIO_SET_DATA_REQUEST, config, mIndex,
                    null, 0, USB_WRITE_TIMEOUT_MILLIS);
            if (result != 0) {
                throw new IOException("Setting parameters failed: result=" + result);
            }
        }

        private long[] convertBaudrate(int baudrate) {
            // TODO(mikey): Braindead transcription of libfti method.  Clean up,
            // using more idiomatic Java where possible.
            int divisor = 24000000 / baudrate;
            int bestDivisor = 0;
            int bestBaud = 0;
            int bestBaudDiff = 0;
            int fracCode[] = {
                    0, 3, 2, 4, 1, 5, 6, 7
            };

            for (int i = 0; i < 2; i++) {
                int tryDivisor = divisor + i;
                int baudEstimate;
                int baudDiff;

                if (tryDivisor <= 8) {
                    // Round up to minimum supported divisor
                    tryDivisor = 8;
                } else if (mType != DeviceType.TYPE_AM && tryDivisor < 12) {
                    // BM doesn't support divisors 9 through 11 inclusive
                    tryDivisor = 12;
                } else if (divisor < 16) {
                    // AM doesn't support divisors 9 through 15 inclusive
                    tryDivisor = 16;
                } else {
                    if (mType == DeviceType.TYPE_AM) {
                        // TODO
                    } else {
                        if (tryDivisor > 0x1FFFF) {
                            // Round down to maximum supported divisor value (for
                            // BM)
                            tryDivisor = 0x1FFFF;
                        }
                    }
                }

                // Get estimated baud rate (to nearest integer)
                baudEstimate = (24000000 + (tryDivisor / 2)) / tryDivisor;

                // Get absolute difference from requested baud rate
                if (baudEstimate < baudrate) {
                    baudDiff = baudrate - baudEstimate;
                } else {
                    baudDiff = baudEstimate - baudrate;
                }

                if (i == 0 || baudDiff < bestBaudDiff) {
                    // Closest to requested baud rate so far
                    bestDivisor = tryDivisor;
                    bestBaud = baudEstimate;
                    bestBaudDiff = baudDiff;
                    if (baudDiff == 0) {
                        // Spot on! No point trying
                        break;
                    }
                }
            }

            // Encode the best divisor value
            long encodedDivisor = (bestDivisor >> 3) | (fracCode[bestDivisor & 7] << 14);
            // Deal with special cases for encoded value
            if (encodedDivisor == 1) {
                encodedDivisor = 0; // 3000000 baud
            } else if (encodedDivisor == 0x4001) {
                encodedDivisor = 1; // 2000000 baud (BM only)
            }

            // Split into "value" and "index" values
            long value = encodedDivisor & 0xFFFF;
            long index;
            if (mType == DeviceType.TYPE_2232C || mType == DeviceType.TYPE_2232H
                    || mType == DeviceType.TYPE_4232H) {
                index = (encodedDivisor >> 8) & 0xff00;
                index |= mIndex;
            } else {
                index = (encodedDivisor >> 16) & 0xffff;
            }

            // Return the nearest baud rate
            return new long[] {
                    bestBaud, index, value
            };
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
            return false;
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
            return false;
        }

        @Override
        public void setRTS(boolean value) throws IOException {
        }

        @Override
        public boolean purgeHwBuffers(boolean purgeWriteBuffers, boolean purgeReadBuffers) throws IOException {
            if (purgeWriteBuffers) {
                int result = mConnection.controlTransfer(FTDI_DEVICE_OUT_REQTYPE, SIO_RESET_REQUEST,
                        SIO_RESET_PURGE_RX, mIndex, null, 0, USB_WRITE_TIMEOUT_MILLIS);
                if (result != 0) {
                    throw new IOException("purge write buffer failed: result=" + result);
                }
            }

            if (purgeReadBuffers) {
                int result = mConnection.controlTransfer(FTDI_DEVICE_OUT_REQTYPE, SIO_RESET_REQUEST,
                        SIO_RESET_PURGE_TX, mIndex, null, 0, USB_WRITE_TIMEOUT_MILLIS);
                if (result != 0) {
                    throw new IOException("purge read buffer failed: result=" + result);
                }
            }
            return true;
        }
    }

    public static Map<Integer, int[]> getSupportedDevices() {
        final Map<Integer, int[]> supportedDevices = new LinkedHashMap<Integer, int[]>();
        supportedDevices.put(UsbId.VENDOR_FTDI,
                new int[] {
                    UsbId.FTDI_FT232R,
                    UsbId.FTDI_FT232H,
                    UsbId.FTDI_FT2232H,
                    UsbId.FTDI_FT4232H,
                    UsbId.FTDI_FT231X,
                });
        return supportedDevices;
    }

}
