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
import android.hardware.usb.UsbRequest;
import android.util.Log;

import com.hoho.android.usbserial.util.HexDump;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
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
 * </ul>
 * </p>
 * <p>
 * Unsupported but possibly working devices (please contact the author with
 * feedback or patches):
 * <ul>
 * <li>{@value DeviceType#TYPE_2232C}</li>
 * <li>{@value DeviceType#TYPE_2232H}</li>
 * <li>{@value DeviceType#TYPE_4232H}</li>
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
    private final UsbSerialPort mPort;

    /**
     * FTDI chip types.
     */
    private static enum DeviceType {
        TYPE_BM, TYPE_AM, TYPE_2232C, TYPE_R, TYPE_2232H, TYPE_4232H;
    }

    public FtdiSerialDriver(UsbDevice device) {
        mDevice = device;
        mPort = new FtdiSerialPort(mDevice, 0);
    }
    @Override
    public UsbDevice getDevice() {
        return mDevice;
    }

    @Override
    public List<UsbSerialPort> getPorts() {
        return Collections.singletonList(mPort);
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
        private static final int SIO_RESET_PURGE_RX = 1;
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

        private int mInterface = 0; /* INTERFACE_ANY */

        private int mMaxPacketSize = 64; // TODO(mikey): detect

        /**
         * Due to http://b.android.com/28023 , we cannot use UsbRequest async reads
         * since it gives no indication of number of bytes read. Set this to
         * {@code true} on platforms where it is fixed.
         */
        private static final boolean ENABLE_ASYNC_READS = false;

        public FtdiSerialPort(UsbDevice device, int portNumber) {
            super(device, portNumber);
        }

        @Override
        public UsbSerialDriver getDriver() {
            return FtdiSerialDriver.this;
        }

        /**
         * Filter FTDI status bytes from buffer
         * @param src The source buffer (which contains status bytes)
         * @param dest The destination buffer to write the status bytes into (can be src)
         * @param totalBytesRead Number of bytes read to src
         * @param maxPacketSize The USB endpoint max packet size
         * @return The number of payload bytes
         */
        private final int filterStatusBytes(byte[] src, byte[] dest, int totalBytesRead, int maxPacketSize) {
            final int packetsCount = totalBytesRead / maxPacketSize + (totalBytesRead % maxPacketSize == 0 ? 0 : 1);
            for (int packetIdx = 0; packetIdx < packetsCount; ++packetIdx) {
                final int count = (packetIdx == (packetsCount - 1))
                        ? (totalBytesRead % maxPacketSize) - MODEM_STATUS_HEADER_LENGTH
                        : maxPacketSize - MODEM_STATUS_HEADER_LENGTH;
                if (count > 0) {
                    System.arraycopy(src,
                            packetIdx * maxPacketSize + MODEM_STATUS_HEADER_LENGTH,
                            dest,
                            packetIdx * (maxPacketSize - MODEM_STATUS_HEADER_LENGTH),
                            count);
                }
            }

          return totalBytesRead - (packetsCount * 2);
        }

        public void reset() throws IOException {
            int result = mConnection.controlTransfer(FTDI_DEVICE_OUT_REQTYPE, SIO_RESET_REQUEST,
                    SIO_RESET_SIO, 0 /* index */, null, 0, USB_WRITE_TIMEOUT_MILLIS);
            if (result != 0) {
                throw new IOException("Reset failed: result=" + result);
            }

            // TODO(mikey): autodetect.
            mType = DeviceType.TYPE_R;
        }

        @Override
        public void open(UsbDeviceConnection connection) throws IOException {
            if (mConnection != null) {
                throw new IOException("Already open");
            }
            mConnection = connection;

            boolean opened = false;
            try {
                for (int i = 0; i < mDevice.getInterfaceCount(); i++) {
                    if (connection.claimInterface(mDevice.getInterface(i), true)) {
                        Log.d(TAG, "claimInterface " + i + " SUCCESS");
                    } else {
                        throw new IOException("Error claiming interface " + i);
                    }
                }
                reset();
                opened = true;
            } finally {
                if (!opened) {
                    close();
                    mConnection = null;
                }
            }
        }

        @Override
        public void close() throws IOException {
            if (mConnection == null) {
                throw new IOException("Already closed");
            }
            try {
                mConnection.close();
            } finally {
                mConnection = null;
            }
        }

        @Override
        public int read(byte[] dest, int timeoutMillis) throws IOException {
            final UsbEndpoint endpoint = mDevice.getInterface(0).getEndpoint(0);

            if (ENABLE_ASYNC_READS) {
                final int readAmt;
                synchronized (mReadBufferLock) {
                    // mReadBuffer is only used for maximum read size.
                    readAmt = Math.min(dest.length, mReadBuffer.length);
                }

                final UsbRequest request = new UsbRequest();
                request.initialize(mConnection, endpoint);

                final ByteBuffer buf = ByteBuffer.wrap(dest);
                if (!request.queue(buf, readAmt)) {
                    throw new IOException("Error queueing request.");
                }

                final UsbRequest response = mConnection.requestWait();
                if (response == null) {
                    throw new IOException("Null response");
                }

                final int payloadBytesRead = buf.position() - MODEM_STATUS_HEADER_LENGTH;
                if (payloadBytesRead > 0) {
                    Log.d(TAG, HexDump.dumpHexString(dest, 0, Math.min(32, dest.length)));
                    return payloadBytesRead;
                } else {
                    return 0;
                }
            } else {
                final int totalBytesRead;

                synchronized (mReadBufferLock) {
                    final int readAmt = Math.min(dest.length, mReadBuffer.length);
                    totalBytesRead = mConnection.bulkTransfer(endpoint, mReadBuffer,
                            readAmt, timeoutMillis);

                    if (totalBytesRead < MODEM_STATUS_HEADER_LENGTH) {
                        throw new IOException("Expected at least " + MODEM_STATUS_HEADER_LENGTH + " bytes");
                    }

                    return filterStatusBytes(mReadBuffer, dest, totalBytesRead, endpoint.getMaxPacketSize());
                }
            }
        }

        @Override
        public int write(byte[] src, int timeoutMillis) throws IOException {
            final UsbEndpoint endpoint = mDevice.getInterface(0).getEndpoint(1);
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

                    amtWritten = mConnection.bulkTransfer(endpoint, writeBuffer, writeLength,
                            timeoutMillis);
                }

                if (amtWritten <= 0) {
                    throw new IOException("Error writing " + writeLength
                            + " bytes at offset " + offset + " length=" + src.length);
                }

                Log.d(TAG, "Wrote amtWritten=" + amtWritten + " attempted=" + writeLength);
                offset += amtWritten;
            }
            return offset;
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
        public void setParameters(int baudRate, int dataBits, int stopBits, int parity)
                throws IOException {
            setBaudRate(baudRate);

            int config = dataBits;

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
                    throw new IllegalArgumentException("Unknown parity value: " + parity);
            }

            switch (stopBits) {
                case STOPBITS_1:
                    config |= (0x00 << 11);
                    break;
                case STOPBITS_1_5:
                    config |= (0x01 << 11);
                    break;
                case STOPBITS_2:
                    config |= (0x02 << 11);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown stopBits value: " + stopBits);
            }

            int result = mConnection.controlTransfer(FTDI_DEVICE_OUT_REQTYPE,
                    SIO_SET_DATA_REQUEST, config, 0 /* index */,
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
                index = (encodedDivisor >> 8) & 0xffff;
                index &= 0xFF00;
                index |= 0 /* TODO mIndex */;
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
        public boolean purgeHwBuffers(boolean purgeReadBuffers, boolean purgeWriteBuffers) throws IOException {
            if (purgeReadBuffers) {
                int result = mConnection.controlTransfer(FTDI_DEVICE_OUT_REQTYPE, SIO_RESET_REQUEST,
                        SIO_RESET_PURGE_RX, 0 /* index */, null, 0, USB_WRITE_TIMEOUT_MILLIS);
                if (result != 0) {
                    throw new IOException("Flushing RX failed: result=" + result);
                }
            }

            if (purgeWriteBuffers) {
                int result = mConnection.controlTransfer(FTDI_DEVICE_OUT_REQTYPE, SIO_RESET_REQUEST,
                        SIO_RESET_PURGE_TX, 0 /* index */, null, 0, USB_WRITE_TIMEOUT_MILLIS);
                if (result != 0) {
                    throw new IOException("Flushing RX failed: result=" + result);
                }
            }
            return true;
        }
    }

    public static Map<Integer, int[]> getSupportedDevices() {
        final Map<Integer, int[]> supportedDevices = new LinkedHashMap<Integer, int[]>();
        supportedDevices.put(Integer.valueOf(UsbId.VENDOR_FTDI),
                new int[] {
                    UsbId.FTDI_FT232R,
                    UsbId.FTDI_FT231X,
                });
        return supportedDevices;
    }

}
