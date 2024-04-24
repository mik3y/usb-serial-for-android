/* Copyright 2011-2013 Google Inc.
 * Copyright 2013 mike wakerly <opensource@hoho.com>
 * Copyright 2020 kai morich <mail@kai-morich.de>
 *
 * Project home page: https://github.com/mik3y/usb-serial-for-android
 */

package com.hoho.android.usbserial.driver;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.util.Log;

import com.hoho.android.usbserial.util.MonotonicClock;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/*
 * driver is implemented from various information scattered over FTDI documentation
 *
 * baud rate calculation https://www.ftdichip.com/Support/Documents/AppNotes/AN232B-05_BaudRates.pdf
 * control bits https://www.ftdichip.com/Firmware/Precompiled/UM_VinculumFirmware_V205.pdf
 * device type https://www.ftdichip.com/Support/Documents/AppNotes/AN_233_Java_D2XX_for_Android_API_User_Manual.pdf -> bvdDevice
 *
 */

public class FtdiSerialDriver implements UsbSerialDriver {

    private static final String TAG = FtdiSerialPort.class.getSimpleName();

    private final UsbDevice mDevice;
    private final List<UsbSerialPort> mPorts;

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

    public class FtdiSerialPort extends CommonUsbSerialPort {

        private static final int USB_WRITE_TIMEOUT_MILLIS = 5000;
        private static final int READ_HEADER_LENGTH = 2; // contains MODEM_STATUS

        private static final int REQTYPE_HOST_TO_DEVICE = UsbConstants.USB_TYPE_VENDOR | UsbConstants.USB_DIR_OUT;
        private static final int REQTYPE_DEVICE_TO_HOST = UsbConstants.USB_TYPE_VENDOR | UsbConstants.USB_DIR_IN;

        private static final int RESET_REQUEST = 0;
        private static final int MODEM_CONTROL_REQUEST = 1;
        private static final int SET_BAUD_RATE_REQUEST = 3;
        private static final int SET_DATA_REQUEST = 4;
        private static final int GET_MODEM_STATUS_REQUEST = 5;
        private static final int SET_LATENCY_TIMER_REQUEST = 9;
        private static final int GET_LATENCY_TIMER_REQUEST = 10;

        private static final int MODEM_CONTROL_DTR_ENABLE = 0x0101;
        private static final int MODEM_CONTROL_DTR_DISABLE = 0x0100;
        private static final int MODEM_CONTROL_RTS_ENABLE = 0x0202;
        private static final int MODEM_CONTROL_RTS_DISABLE = 0x0200;
        private static final int MODEM_STATUS_CTS = 0x10;
        private static final int MODEM_STATUS_DSR = 0x20;
        private static final int MODEM_STATUS_RI = 0x40;
        private static final int MODEM_STATUS_CD = 0x80;
        private static final int RESET_ALL = 0;
        private static final int RESET_PURGE_RX = 1;
        private static final int RESET_PURGE_TX = 2;

        private boolean baudRateWithPort = false;
        private boolean dtr = false;
        private boolean rts = false;
        private int breakConfig = 0;

        public FtdiSerialPort(UsbDevice device, int portNumber) {
            super(device, portNumber);
        }

        @Override
        public UsbSerialDriver getDriver() {
            return FtdiSerialDriver.this;
        }


        @Override
        protected void openInt() throws IOException {
            if (!mConnection.claimInterface(mDevice.getInterface(mPortNumber), true)) {
                throw new IOException("Could not claim interface " + mPortNumber);
            }
            if (mDevice.getInterface(mPortNumber).getEndpointCount() < 2) {
                throw new IOException("Not enough endpoints");
            }
            mReadEndpoint = mDevice.getInterface(mPortNumber).getEndpoint(0);
            mWriteEndpoint = mDevice.getInterface(mPortNumber).getEndpoint(1);

            int result = mConnection.controlTransfer(REQTYPE_HOST_TO_DEVICE, RESET_REQUEST,
                    RESET_ALL, mPortNumber+1, null, 0, USB_WRITE_TIMEOUT_MILLIS);
            if (result != 0) {
                throw new IOException("Reset failed: result=" + result);
            }
            result = mConnection.controlTransfer(REQTYPE_HOST_TO_DEVICE, MODEM_CONTROL_REQUEST,
                    (dtr ? MODEM_CONTROL_DTR_ENABLE : MODEM_CONTROL_DTR_DISABLE) |
                            (rts ? MODEM_CONTROL_RTS_ENABLE : MODEM_CONTROL_RTS_DISABLE),
                    mPortNumber+1, null, 0, USB_WRITE_TIMEOUT_MILLIS);
            if (result != 0) {
                throw new IOException("Init RTS,DTR failed: result=" + result);
            }

            // mDevice.getVersion() would require API 23
            byte[] rawDescriptors = mConnection.getRawDescriptors();
            if(rawDescriptors == null || rawDescriptors.length < 14) {
                throw new IOException("Could not get device descriptors");
            }
            int deviceType = rawDescriptors[13];
            baudRateWithPort = deviceType == 7 || deviceType == 8 || deviceType == 9 // ...H devices
                    || mDevice.getInterfaceCount() > 1; // FT2232C
        }

        @Override
        protected void closeInt() {
            try {
                mConnection.releaseInterface(mDevice.getInterface(mPortNumber));
            } catch(Exception ignored) {}
        }

        @Override
        public int read(final byte[] dest, final int timeout) throws IOException
        {
            if(dest.length <= READ_HEADER_LENGTH) {
                throw new IllegalArgumentException("Read buffer too small");
                // could allocate larger buffer, including space for 2 header bytes, but this would
                // result in buffers not being 64 byte aligned any more, causing data loss at continuous
                // data transfer at high baud rates when buffers are fully filled.
            }
            return read(dest, dest.length, timeout);
        }

        @Override
        public int read(final byte[] dest, int length, final int timeout) throws IOException {
            if(length <= READ_HEADER_LENGTH) {
                throw new IllegalArgumentException("Read length too small");
                // could allocate larger buffer, including space for 2 header bytes, but this would
                // result in buffers not being 64 byte aligned any more, causing data loss at continuous
                // data transfer at high baud rates when buffers are fully filled.
            }
            length = Math.min(length, dest.length);
            int nread;
            if (timeout != 0) {
                long endTime = MonotonicClock.millis() + timeout;
                do {
                    nread = super.read(dest, length, Math.max(1, (int)(endTime - MonotonicClock.millis())), false);
                } while (nread == READ_HEADER_LENGTH && MonotonicClock.millis() < endTime);
                if(nread <= 0)
                    testConnection(MonotonicClock.millis() < endTime);
            } else {
                do {
                    nread = super.read(dest, length, timeout);
                } while (nread == READ_HEADER_LENGTH);
            }
            return readFilter(dest, nread);
        }

        protected int readFilter(byte[] buffer, int totalBytesRead) throws IOException {
            final int maxPacketSize = mReadEndpoint.getMaxPacketSize();
            int destPos = 0;
            for(int srcPos = 0; srcPos < totalBytesRead; srcPos += maxPacketSize) {
                int length = Math.min(srcPos + maxPacketSize, totalBytesRead) - (srcPos + READ_HEADER_LENGTH);
                if (length < 0)
                    throw new IOException("Expected at least " + READ_HEADER_LENGTH + " bytes");
                System.arraycopy(buffer, srcPos + READ_HEADER_LENGTH, buffer, destPos, length);
                destPos += length;
            }
            //Log.d(TAG, "read filter " + totalBytesRead + " -> " + destPos);
            return destPos;
        }

        private void setBaudrate(int baudRate) throws IOException {
            int divisor, subdivisor, effectiveBaudRate;
            if (baudRate > 3500000) {
                throw new UnsupportedOperationException("Baud rate to high");
            } else if(baudRate >= 2500000) {
                divisor = 0;
                subdivisor = 0;
                effectiveBaudRate = 3000000;
            } else if(baudRate >= 1750000) {
                divisor = 1;
                subdivisor = 0;
                effectiveBaudRate = 2000000;
            } else {
                divisor = (24000000 << 1) / baudRate;
                divisor = (divisor + 1) >> 1; // round
                subdivisor = divisor & 0x07;
                divisor >>= 3;
                if (divisor > 0x3fff) // exceeds bit 13 at 183 baud
                    throw new UnsupportedOperationException("Baud rate to low");
                effectiveBaudRate = (24000000 << 1) / ((divisor << 3) + subdivisor);
                effectiveBaudRate = (effectiveBaudRate +1) >> 1;
            }
            double baudRateError = Math.abs(1.0 - (effectiveBaudRate / (double)baudRate));
            if(baudRateError >= 0.031) // can happen only > 1.5Mbaud
                throw new UnsupportedOperationException(String.format("Baud rate deviation %.1f%% is higher than allowed 3%%", baudRateError*100));
            int value = divisor;
            int index = 0;
            switch(subdivisor) {
                case 0:                              break; // 16,15,14 = 000 - sub-integer divisor = 0
                case 4: value |= 0x4000;             break; // 16,15,14 = 001 - sub-integer divisor = 0.5
                case 2: value |= 0x8000;             break; // 16,15,14 = 010 - sub-integer divisor = 0.25
                case 1: value |= 0xc000;             break; // 16,15,14 = 011 - sub-integer divisor = 0.125
                case 3: value |= 0x0000; index |= 1; break; // 16,15,14 = 100 - sub-integer divisor = 0.375
                case 5: value |= 0x4000; index |= 1; break; // 16,15,14 = 101 - sub-integer divisor = 0.625
                case 6: value |= 0x8000; index |= 1; break; // 16,15,14 = 110 - sub-integer divisor = 0.75
                case 7: value |= 0xc000; index |= 1; break; // 16,15,14 = 111 - sub-integer divisor = 0.875
            }
            if(baudRateWithPort) {
                index <<= 8;
                index |= mPortNumber+1;
            }
            Log.d(TAG, String.format("baud rate=%d, effective=%d, error=%.1f%%, value=0x%04x, index=0x%04x, divisor=%d, subdivisor=%d",
                    baudRate, effectiveBaudRate, baudRateError*100, value, index, divisor, subdivisor));

            int result = mConnection.controlTransfer(REQTYPE_HOST_TO_DEVICE, SET_BAUD_RATE_REQUEST,
                    value, index, null, 0, USB_WRITE_TIMEOUT_MILLIS);
            if (result != 0) {
                throw new IOException("Setting baudrate failed: result=" + result);
            }
        }

        @Override
        public void setParameters(int baudRate, int dataBits, int stopBits, @Parity int parity) throws IOException {
            if(baudRate <= 0) {
                throw new IllegalArgumentException("Invalid baud rate: " + baudRate);
            }
            setBaudrate(baudRate);

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
                    break;
                case PARITY_ODD:
                    config |= 0x100;
                    break;
                case PARITY_EVEN:
                    config |= 0x200;
                    break;
                case PARITY_MARK:
                    config |= 0x300;
                    break;
                case PARITY_SPACE:
                    config |= 0x400;
                    break;
                default:
                    throw new IllegalArgumentException("Invalid parity: " + parity);
            }

            switch (stopBits) {
                case STOPBITS_1:
                    break;
                case STOPBITS_1_5:
                    throw new UnsupportedOperationException("Unsupported stop bits: 1.5");
                case STOPBITS_2:
                    config |= 0x1000;
                    break;
                default:
                    throw new IllegalArgumentException("Invalid stop bits: " + stopBits);
            }

            int result = mConnection.controlTransfer(REQTYPE_HOST_TO_DEVICE, SET_DATA_REQUEST,
                    config, mPortNumber+1,null, 0, USB_WRITE_TIMEOUT_MILLIS);
            if (result != 0) {
                throw new IOException("Setting parameters failed: result=" + result);
            }
            breakConfig = config;
        }

        private int getStatus() throws IOException {
            byte[] data = new byte[2];
            int result = mConnection.controlTransfer(REQTYPE_DEVICE_TO_HOST, GET_MODEM_STATUS_REQUEST,
                    0, mPortNumber+1, data, data.length, USB_WRITE_TIMEOUT_MILLIS);
            if (result != data.length) {
                throw new IOException("Get modem status failed: result=" + result);
            }
            return data[0];
        }

        @Override
        public boolean getCD() throws IOException {
            return (getStatus() & MODEM_STATUS_CD) != 0;
        }

        @Override
        public boolean getCTS() throws IOException {
            return (getStatus() & MODEM_STATUS_CTS) != 0;
        }

        @Override
        public boolean getDSR() throws IOException {
            return (getStatus() & MODEM_STATUS_DSR) != 0;
        }

        @Override
        public boolean getDTR() throws IOException {
            return dtr;
        }

        @Override
        public void setDTR(boolean value) throws IOException {
            int result = mConnection.controlTransfer(REQTYPE_HOST_TO_DEVICE, MODEM_CONTROL_REQUEST,
                    value ? MODEM_CONTROL_DTR_ENABLE : MODEM_CONTROL_DTR_DISABLE, mPortNumber+1, null, 0, USB_WRITE_TIMEOUT_MILLIS);
            if (result != 0) {
                throw new IOException("Set DTR failed: result=" + result);
            }
            dtr = value;
        }

        @Override
        public boolean getRI() throws IOException {
            return (getStatus() & MODEM_STATUS_RI) != 0;
        }

        @Override
        public boolean getRTS() throws IOException {
            return rts;
        }

        @Override
        public void setRTS(boolean value) throws IOException {
            int result = mConnection.controlTransfer(REQTYPE_HOST_TO_DEVICE, MODEM_CONTROL_REQUEST,
                    value ? MODEM_CONTROL_RTS_ENABLE : MODEM_CONTROL_RTS_DISABLE, mPortNumber+1, null, 0, USB_WRITE_TIMEOUT_MILLIS);
            if (result != 0) {
                throw new IOException("Set DTR failed: result=" + result);
            }
            rts = value;
        }

        @Override
        public EnumSet<ControlLine> getControlLines() throws IOException {
            int status = getStatus();
            EnumSet<ControlLine> set = EnumSet.noneOf(ControlLine.class);
            if(rts) set.add(ControlLine.RTS);
            if((status & MODEM_STATUS_CTS) != 0) set.add(ControlLine.CTS);
            if(dtr) set.add(ControlLine.DTR);
            if((status & MODEM_STATUS_DSR) != 0) set.add(ControlLine.DSR);
            if((status & MODEM_STATUS_CD) != 0) set.add(ControlLine.CD);
            if((status & MODEM_STATUS_RI) != 0) set.add(ControlLine.RI);
            return set;
        }

        @Override
        public EnumSet<ControlLine> getSupportedControlLines() throws IOException {
            return EnumSet.allOf(ControlLine.class);
        }

        @Override
        public void purgeHwBuffers(boolean purgeWriteBuffers, boolean purgeReadBuffers) throws IOException {
            if (purgeWriteBuffers) {
                int result = mConnection.controlTransfer(REQTYPE_HOST_TO_DEVICE, RESET_REQUEST,
                        RESET_PURGE_RX, mPortNumber+1, null, 0, USB_WRITE_TIMEOUT_MILLIS);
                if (result != 0) {
                    throw new IOException("Purge write buffer failed: result=" + result);
                }
            }

            if (purgeReadBuffers) {
                int result = mConnection.controlTransfer(REQTYPE_HOST_TO_DEVICE, RESET_REQUEST,
                        RESET_PURGE_TX, mPortNumber+1, null, 0, USB_WRITE_TIMEOUT_MILLIS);
                if (result != 0) {
                    throw new IOException("Purge read buffer failed: result=" + result);
                }
            }
        }

        @Override
        public void setBreak(boolean value) throws IOException {
            int config = breakConfig;
            if(value) config |= 0x4000;
            int result = mConnection.controlTransfer(REQTYPE_HOST_TO_DEVICE, SET_DATA_REQUEST,
                    config, mPortNumber+1,null, 0, USB_WRITE_TIMEOUT_MILLIS);
            if (result != 0) {
                throw new IOException("Setting BREAK failed: result=" + result);
            }
        }

        public void setLatencyTimer(int latencyTime) throws IOException {
            int result = mConnection.controlTransfer(REQTYPE_HOST_TO_DEVICE, SET_LATENCY_TIMER_REQUEST,
                    latencyTime, mPortNumber+1, null, 0, USB_WRITE_TIMEOUT_MILLIS);
            if (result != 0) {
                throw new IOException("Set latency timer failed: result=" + result);
            }
        }

        public int getLatencyTimer() throws IOException {
            byte[] data = new byte[1];
            int result = mConnection.controlTransfer(REQTYPE_DEVICE_TO_HOST, GET_LATENCY_TIMER_REQUEST,
                    0, mPortNumber+1, data, data.length, USB_WRITE_TIMEOUT_MILLIS);
            if (result != data.length) {
                throw new IOException("Get latency timer failed: result=" + result);
            }
            return data[0];
        }

    }

    @SuppressWarnings({"unused"})
    public static Map<Integer, int[]> getSupportedDevices() {
        final Map<Integer, int[]> supportedDevices = new LinkedHashMap<>();
        supportedDevices.put(UsbId.VENDOR_FTDI,
                new int[] {
                    UsbId.FTDI_FT232R,
                    UsbId.FTDI_FT232H,
                    UsbId.FTDI_FT2232H,
                    UsbId.FTDI_FT4232H,
                    UsbId.FTDI_FT231X,  // same ID for FT230X, FT231X, FT234XD
                });
        return supportedDevices;
    }

}
