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
        private static final int REQTYPE_DEVICE_TO_HOST_DEV = 0xc0;

        /*
         * Configuration Request Codes
         */
        private static final int SILABSER_IFC_ENABLE_REQUEST_CODE = 0x00;
        private static final int SILABSER_SET_LINE_CTL_REQUEST_CODE = 0x03;
        private static final int SILABSER_SET_BREAK_REQUEST_CODE = 0x05;
        private static final int SILABSER_SET_MHS_REQUEST_CODE = 0x07;
        private static final int SILABSER_GET_MDMSTS_REQUEST_CODE = 0x08;
        private static final int SILABSER_SET_XON_REQUEST_CODE = 0x09;
        private static final int SILABSER_SET_XOFF_REQUEST_CODE = 0x0A;
        private static final int SILABSER_GET_COMM_STATUS_REQUEST_CODE = 0x10;
        private static final int SILABSER_FLUSH_REQUEST_CODE = 0x12;
        private static final int SILABSER_SET_FLOW_REQUEST_CODE = 0x13;
        private static final int SILABSER_GET_FLOW_REQUEST_CODE = 0x14;
        private static final int SILABSER_SET_CHARS_REQUEST_CODE = 0x19;
        private static final int SILABSER_SET_BAUDRATE_REQUEST_CODE = 0x1E;

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
        private static final int STATUS_DTR = 0x01;
        private static final int STATUS_RTS = 0x02;
        private static final int STATUS_CTS = 0x10;
        private static final int STATUS_DSR = 0x20;
        private static final int STATUS_RI = 0x40;
        private static final int STATUS_CD = 0x80;

        /*
         * Vendor specific requests and part numbers (from Linux cp210x driver)
         */
        private static final int CP210X_VENDOR_SPECIFIC = 0xFF;

        private static final int CP210X_GET_PARTNUM = 0x370B;
        private static final int CP210X_GET_FW_VER = 0x000E;
        private static final int CP210X_GET_FW_VER_2N = 0x0010;

        private static final int CP210X_PARTNUM_CP2101 = 0x01;
        private static final int CP210X_PARTNUM_CP2102 = 0x02;
        private static final int CP210X_PARTNUM_CP2103 = 0x03;
        private static final int CP210X_PARTNUM_CP2104 = 0x04;
        private static final int CP210X_PARTNUM_CP2105 = 0x05;
        private static final int CP210X_PARTNUM_CP2108 = 0x08;
        private static final int CP210X_PARTNUM_CP2102N_QFN28 = 0x20;
        private static final int CP210X_PARTNUM_CP2102N_QFN24 = 0x21;
        private static final int CP210X_PARTNUM_CP2102N_QFN20 = 0x22;
        private static final int CP210X_PARTNUM_UNKNOWN = 0xFF;

        private static final int PURGE_ALL = 0x0F;


        private boolean dtr = false;
        private boolean rts = false;

        // second port of Cp2105 has limited baudRate, dataBits, stopBits, parity
        // unsupported baudrate returns error at controlTransfer(), other parameters are silently ignored
        private boolean mIsRestrictedPort;

        private int mPartNum = CP210X_PARTNUM_UNKNOWN;
        private int mFwVersion = 0;

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

        private boolean isCp2102N() {
            return mPartNum == CP210X_PARTNUM_CP2102N_QFN20 ||
                   mPartNum == CP210X_PARTNUM_CP2102N_QFN24 ||
                   mPartNum == CP210X_PARTNUM_CP2102N_QFN28;
        }

        private boolean isFlowControlSupported() {
            if (isCp2102N() && mFwVersion <= 0x10004) {
                return false;
            }
            return true;
        }

        private void queryDeviceTypeAndVersion() {
            try {
                byte[] partNumBuf = new byte[1];
                int ret = mConnection.controlTransfer(REQTYPE_DEVICE_TO_HOST_DEV, CP210X_VENDOR_SPECIFIC, CP210X_GET_PARTNUM, mPortNumber, partNumBuf, 1, USB_WRITE_TIMEOUT_MILLIS);
                if (ret == 1) {
                    mPartNum = partNumBuf[0] & 0xFF;
                } else {
                    byte[] partNumBuf2 = new byte[2];
                    ret = mConnection.controlTransfer(REQTYPE_DEVICE_TO_HOST_DEV, CP210X_VENDOR_SPECIFIC, CP210X_GET_PARTNUM, mPortNumber, partNumBuf2, 2, USB_WRITE_TIMEOUT_MILLIS);
                    if (ret >= 1) {
                        mPartNum = partNumBuf2[0] & 0xFF;
                    } else {
                        mPartNum = CP210X_PARTNUM_UNKNOWN;
                    }
                }
            } catch (Exception e) {
                mPartNum = CP210X_PARTNUM_UNKNOWN;
            }

            if (isCp2102N()) {
                try {
                    byte[] fwVerBuf = new byte[3];
                    int ret = mConnection.controlTransfer(REQTYPE_DEVICE_TO_HOST_DEV, CP210X_VENDOR_SPECIFIC, CP210X_GET_FW_VER_2N, mPortNumber, fwVerBuf, 3, USB_WRITE_TIMEOUT_MILLIS);
                    if (ret == 3) {
                        mFwVersion = ((fwVerBuf[0] & 0xFF) << 16) | ((fwVerBuf[1] & 0xFF) << 8) | (fwVerBuf[2] & 0xFF);
                    }
                } catch (Exception e) {
                    mFwVersion = 0;
                }
            } else if (mPartNum == CP210X_PARTNUM_CP2105 || mPartNum == CP210X_PARTNUM_CP2108) {
                try {
                    byte[] fwVerBuf = new byte[3];
                    int ret = mConnection.controlTransfer(REQTYPE_DEVICE_TO_HOST_DEV, CP210X_VENDOR_SPECIFIC, CP210X_GET_FW_VER, mPortNumber, fwVerBuf, 3, USB_WRITE_TIMEOUT_MILLIS);
                    if (ret == 3) {
                        mFwVersion = ((fwVerBuf[0] & 0xFF) << 16) | ((fwVerBuf[1] & 0xFF) << 8) | (fwVerBuf[2] & 0xFF);
                    }
                } catch (Exception e) {
                    mFwVersion = 0;
                }
            }
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

            queryDeviceTypeAndVersion();

            setConfigSingle(SILABSER_IFC_ENABLE_REQUEST_CODE, UART_ENABLE);
            setConfigSingle(SILABSER_SET_MHS_REQUEST_CODE, (dtr ? DTR_ENABLE : DTR_DISABLE) | (rts ? RTS_ENABLE : RTS_DISABLE));
            setFlowControl(mFlowControl);
        }

        @Override
        protected void closeInt() {
            try {
                setConfigSingle(SILABSER_FLUSH_REQUEST_CODE, PURGE_ALL);
            } catch (Exception ignored) {}
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
            int ret = mConnection.controlTransfer(REQTYPE_HOST_TO_DEVICE, SILABSER_SET_BAUDRATE_REQUEST_CODE,
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
            int maxBaud = 2000000;
            int minBaud = 300;
            if (mPartNum == CP210X_PARTNUM_CP2101) {
                maxBaud = 921600;
            } else if (mPartNum == CP210X_PARTNUM_CP2102 || mPartNum == CP210X_PARTNUM_CP2103) {
                maxBaud = 1000000;
            } else if (isCp2102N()) {
                maxBaud = 3000000;
            }
            if (mIsRestrictedPort) {
                minBaud = 2400;
                maxBaud = 921600;
            }
            if (baudRate < minBaud || baudRate > maxBaud) {
                throw new IllegalArgumentException("Baud rate " + baudRate + " out of bounds [" + minBaud + ", " + maxBaud + "] for this device");
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
            //if(rts) set.add(ControlLine.RTS);                      // configured value
            if((status & STATUS_RTS) != 0) set.add(ControlLine.RTS); // actual value
            if((status & STATUS_CTS) != 0) set.add(ControlLine.CTS);
            //if(dtr) set.add(ControlLine.DTR);                      // configured value
            if((status & STATUS_DTR) != 0) set.add(ControlLine.DTR); // actual value
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
        public boolean getXON() throws IOException {
            byte[] buffer = new byte[0x13];
            int result = mConnection.controlTransfer(REQTYPE_DEVICE_TO_HOST, SILABSER_GET_COMM_STATUS_REQUEST_CODE, 0,
                    mPortNumber, buffer, buffer.length, USB_WRITE_TIMEOUT_MILLIS);
            if (result != buffer.length) {
                throw new IOException("Control transfer failed: " + SILABSER_GET_COMM_STATUS_REQUEST_CODE + " -> " + result);
            }
            return (buffer[4] & 8) == 0;
        }

        /**
         * emulate external XON/OFF
         * @throws IOException
         */
        public void setXON(boolean value) throws IOException {
            setConfigSingle(value ? SILABSER_SET_XON_REQUEST_CODE : SILABSER_SET_XOFF_REQUEST_CODE, 0);
        }

        @Override
        public void setFlowControl(FlowControl flowControl) throws IOException {
            if (flowControl != FlowControl.NONE && !isFlowControlSupported()) {
                throw new UnsupportedOperationException("Flow control not supported on this CP2102N device due to firmware erratum CP2102N_E104");
            }
            if (flowControl == FlowControl.XON_XOFF_INLINE) {
                throw new UnsupportedOperationException();
            }

            byte[] data = new byte[16];
            int ret = mConnection.controlTransfer(REQTYPE_DEVICE_TO_HOST, SILABSER_GET_FLOW_REQUEST_CODE,
                    0, mPortNumber, data, data.length, USB_WRITE_TIMEOUT_MILLIS);
            if (ret != data.length) {
                throw new IOException("Error getting flow control: " + ret);
            }

            // RTS / CTS
            if (flowControl == FlowControl.RTS_CTS) {
                data[0] |= 0x08; // CTS handshake
                data[4] &= ~0xC0; // clear RTS mask
                data[4] |= 0x80; // RTS flow control
            } else {
                data[0] &= ~0x08; // disable CTS handshake
                data[4] &= ~0xC0; // clear RTS mask
                if (rts) {
                    data[4] |= 0x40; // RTS active
                }
            }

            // DTR / DSR
            if (flowControl == FlowControl.DTR_DSR) {
                data[0] &= ~0x03; // clear DTR mask
                data[0] |= 0x02; // DTR flow control
                data[0] |= 0x10; // DSR handshake
            } else {
                data[0] &= ~0x03; // clear DTR mask
                if (dtr) {
                    data[0] |= 0x01; // DTR active
                }
                data[0] &= ~0x10; // disable DSR handshake
            }

            // XON / XOFF
            if (flowControl == FlowControl.XON_XOFF) {
                byte[] chars = new byte[]{0, 0, 0, 0, CHAR_XON, CHAR_XOFF};
                ret = mConnection.controlTransfer(REQTYPE_HOST_TO_DEVICE, SILABSER_SET_CHARS_REQUEST_CODE,
                        0, mPortNumber, chars, chars.length, USB_WRITE_TIMEOUT_MILLIS);
                if (ret != chars.length) {
                    throw new IOException("Error setting XON/XOFF chars");
                }
                data[4] |= 0x03; // AUTO_TRANSMIT | AUTO_RECEIVE
                data[7] |= 0x80; // XOFF_CONTINUE
                data[8] = (byte) 128;
                data[9] = 0;
                data[10] = 0;
                data[11] = 0;
                data[12] = (byte) 128;
                data[13] = 0;
                data[14] = 0;
                data[15] = 0;
            } else {
                data[4] &= ~0x03; // disable AUTO_TRANSMIT | AUTO_RECEIVE
                data[7] &= ~0x80; // disable XOFF_CONTINUE
            }

            ret = mConnection.controlTransfer(REQTYPE_HOST_TO_DEVICE, SILABSER_SET_FLOW_REQUEST_CODE,
                    0, mPortNumber, data, data.length, USB_WRITE_TIMEOUT_MILLIS);
            if (ret != data.length) {
                throw new IOException("Error setting flow control");
            }
            if (flowControl == FlowControl.XON_XOFF) {
                setXON(true);
            }
            mFlowControl = flowControl;
        }

        @Override
        public EnumSet<FlowControl> getSupportedFlowControl() {
            return EnumSet.of(FlowControl.NONE, FlowControl.RTS_CTS, FlowControl.DTR_DSR, FlowControl.XON_XOFF);
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
