/* Copyright 2011-2013 Google Inc.
 * Copyright 2013 mike wakerly <opensource@hoho.com>
 *
 * Project home page: https://github.com/mik3y/usb-serial-for-android
 */

package com.hoho.android.usbserial.driver;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
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

        private static final int USB_WRITE_TIMEOUT_MILLIS = 5000;

        /*
         * Configuration Request Types
         */
        private static final int REQTYPE_HOST_TO_DEVICE = 0x41;
        private static final int REQTYPE_DEVICE_TO_HOST = 0xc1;

        /*
         * Configuration Request Codes
         */
        private static final int SILABSER_IFC_ENABLE_REQUEST_CODE = 0x00;
        private static final int SILABSER_SET_LINE_CTL_REQUEST_CODE = 0x03;
        private static final int SILABSER_SET_BREAK_REQUEST_CODE = 0x05;
        private static final int SILABSER_SET_MHS_REQUEST_CODE = 0x07;
        private static final int SILABSER_SET_BAUDRATE = 0x1E;
        private static final int SILABSER_FLUSH_REQUEST_CODE = 0x12;
        private static final int SILABSER_GET_MDMSTS_REQUEST_CODE = 0x08;

        private static final int FLUSH_READ_CODE = 0x0a;
        private static final int FLUSH_WRITE_CODE = 0x05;

        /*
         * SILABSER_IFC_ENABLE_REQUEST_CODE
         */
        private static final int UART_ENABLE = 0x0001;
        private static final int UART_DISABLE = 0x0000;

        /*
         * SILABSER_SET_MHS_REQUEST_CODE
         */
        private static final int DTR_ENABLE = 0x101;
        private static final int DTR_DISABLE = 0x100;
        private static final int RTS_ENABLE = 0x202;
        private static final int RTS_DISABLE = 0x200;

        /*
        * SILABSER_GET_MDMSTS_REQUEST_CODE
         */
        private static final int STATUS_CTS = 0x10;
        private static final int STATUS_DSR = 0x20;
        private static final int STATUS_RI = 0x40;
        private static final int STATUS_CD = 0x80;


        private boolean dtr = false;
        private boolean rts = false;

        // second port of Cp2105 has limited baudRate, dataBits, stopBits, parity
        // unsupported baudrate returns error at controlTransfer(), other parameters are silently ignored
        private boolean mIsRestrictedPort;

        public Cp21xxSerialPort(UsbDevice device, int portNumber) {
            super(device, portNumber);
        }

        @Override
        public UsbSerialDriver getDriver() {
            return Cp21xxSerialDriver.this;
        }

        private void setConfigSingle(int request, int value) throws IOException {
            int result = mConnection.controlTransfer(REQTYPE_HOST_TO_DEVICE, request, value,
                    mPortNumber, null, 0, USB_WRITE_TIMEOUT_MILLIS);
            if (result != 0) {
                throw new IOException("Control transfer failed: " + request + " / " + value + " -> " + result);
            }
        }

        private byte getStatus() throws IOException {
            byte[] buffer = new byte[1];
            int result = mConnection.controlTransfer(REQTYPE_DEVICE_TO_HOST, SILABSER_GET_MDMSTS_REQUEST_CODE, 0,
                    mPortNumber, buffer, buffer.length, USB_WRITE_TIMEOUT_MILLIS);
            if (result != buffer.length) {
                throw new IOException("Control transfer failed: " + SILABSER_GET_MDMSTS_REQUEST_CODE + " / " + 0 + " -> " + result);
            }
            return buffer[0];
        }

        @Override
        protected void openInt() throws IOException {
            mIsRestrictedPort = mDevice.getInterfaceCount() == 2 && mPortNumber == 1;
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
            setConfigSingle(SILABSER_SET_MHS_REQUEST_CODE, (dtr ? DTR_ENABLE : DTR_DISABLE) | (rts ? RTS_ENABLE : RTS_DISABLE));
        }

        @Override
        protected void closeInt() {
            try {
                setConfigSingle(SILABSER_IFC_ENABLE_REQUEST_CODE, UART_DISABLE);
            } catch (Exception ignored) {}
            try {
                mConnection.releaseInterface(mDevice.getInterface(mPortNumber));
            } catch(Exception ignored) {}
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
                throw new IOException("Error setting baud rate");
            }
        }

        @Override
        public void setParameters(int baudRate, int dataBits, int stopBits, @Parity int parity) throws IOException {
            if(baudRate <= 0) {
                throw new IllegalArgumentException("Invalid baud rate: " + baudRate);
            }
            setBaudRate(baudRate);

            int configDataBits = 0;
            switch (dataBits) {
                case DATABITS_5:
                    if(mIsRestrictedPort)
                        throw new UnsupportedOperationException("Unsupported data bits: " + dataBits);
                    configDataBits |= 0x0500;
                    break;
                case DATABITS_6:
                    if(mIsRestrictedPort)
                        throw new UnsupportedOperationException("Unsupported data bits: " + dataBits);
                    configDataBits |= 0x0600;
                    break;
                case DATABITS_7:
                    if(mIsRestrictedPort)
                        throw new UnsupportedOperationException("Unsupported data bits: " + dataBits);
                    configDataBits |= 0x0700;
                    break;
                case DATABITS_8:
                    configDataBits |= 0x0800;
                    break;
                default:
                    throw new IllegalArgumentException("Invalid data bits: " + dataBits);
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
                        throw new UnsupportedOperationException("Unsupported parity: mark");
                    configDataBits |= 0x0030;
                    break;
                case PARITY_SPACE:
                    if(mIsRestrictedPort)
                        throw new UnsupportedOperationException("Unsupported parity: space");
                    configDataBits |= 0x0040;
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
                    if(mIsRestrictedPort)
                        throw new UnsupportedOperationException("Unsupported stop bits: 2");
                    configDataBits |= 2;
                    break;
                default:
                    throw new IllegalArgumentException("Invalid stop bits: " + stopBits);
            }
            setConfigSingle(SILABSER_SET_LINE_CTL_REQUEST_CODE, configDataBits);
        }

        @Override
        public boolean getCD() throws IOException {
            return (getStatus() & STATUS_CD) != 0;
        }

        @Override
        public boolean getCTS() throws IOException {
            return (getStatus() & STATUS_CTS) != 0;
        }

        @Override
        public boolean getDSR() throws IOException {
            return (getStatus() & STATUS_DSR) != 0;
        }

        @Override
        public boolean getDTR() throws IOException {
            return dtr;
        }

        @Override
        public void setDTR(boolean value) throws IOException {
            dtr = value;
            setConfigSingle(SILABSER_SET_MHS_REQUEST_CODE, dtr ? DTR_ENABLE : DTR_DISABLE);
        }

        @Override
        public boolean getRI() throws IOException {
            return (getStatus() & STATUS_RI) != 0;
        }

        @Override
        public boolean getRTS() throws IOException {
            return rts;
        }

        @Override
        public void setRTS(boolean value) throws IOException {
            rts = value;
            setConfigSingle(SILABSER_SET_MHS_REQUEST_CODE, rts ? RTS_ENABLE : RTS_DISABLE);
        }

        @Override
        public EnumSet<ControlLine> getControlLines() throws IOException {
            byte status = getStatus();
            EnumSet<ControlLine> set = EnumSet.noneOf(ControlLine.class);
            if(rts) set.add(ControlLine.RTS);
            if((status & STATUS_CTS) != 0) set.add(ControlLine.CTS);
            if(dtr) set.add(ControlLine.DTR);
            if((status & STATUS_DSR) != 0) set.add(ControlLine.DSR);
            if((status & STATUS_CD) != 0) set.add(ControlLine.CD);
            if((status & STATUS_RI) != 0) set.add(ControlLine.RI);
            return set;
        }

        @Override
        public EnumSet<ControlLine> getSupportedControlLines() throws IOException {
            return EnumSet.allOf(ControlLine.class);
        }

        @Override
        // note: only working on some devices, on other devices ignored w/o error
        public void purgeHwBuffers(boolean purgeWriteBuffers, boolean purgeReadBuffers) throws IOException {
            int value = (purgeReadBuffers ? FLUSH_READ_CODE : 0)
                    | (purgeWriteBuffers ? FLUSH_WRITE_CODE : 0);

            if (value != 0) {
                setConfigSingle(SILABSER_FLUSH_REQUEST_CODE, value);
            }
        }

        @Override
        public void setBreak(boolean value) throws IOException {
            setConfigSingle(SILABSER_SET_BREAK_REQUEST_CODE, value ? 1 : 0);
        }
    }

    @SuppressWarnings({"unused"})
    public static Map<Integer, int[]> getSupportedDevices() {
        final Map<Integer, int[]> supportedDevices = new LinkedHashMap<>();
        supportedDevices.put(UsbId.VENDOR_SILABS,
                new int[] {
            UsbId.SILABS_CP2102, // same ID for CP2101, CP2103, CP2104, CP2109
            UsbId.SILABS_CP2105,
            UsbId.SILABS_CP2108,
        });
        return supportedDevices;
    }

}
