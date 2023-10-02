/* Copyright 2014 Andreas Butti
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

import java.io.IOException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Ch34xSerialDriver implements UsbSerialDriver {

    private static final String TAG = Ch34xSerialDriver.class.getSimpleName();

    private final UsbDevice mDevice;
    private final UsbSerialPort mPort;

    private static final int LCR_ENABLE_RX   = 0x80;
    private static final int LCR_ENABLE_TX   = 0x40;
    private static final int LCR_MARK_SPACE  = 0x20;
    private static final int LCR_PAR_EVEN    = 0x10;
    private static final int LCR_ENABLE_PAR  = 0x08;
    private static final int LCR_STOP_BITS_2 = 0x04;
    private static final int LCR_CS8         = 0x03;
    private static final int LCR_CS7         = 0x02;
    private static final int LCR_CS6         = 0x01;
    private static final int LCR_CS5         = 0x00;

    private static final int GCL_CTS = 0x01;
    private static final int GCL_DSR = 0x02;
    private static final int GCL_RI  = 0x04;
    private static final int GCL_CD  = 0x08;
    private static final int SCL_DTR = 0x20;
    private static final int SCL_RTS = 0x40;

    public Ch34xSerialDriver(UsbDevice device) {
        mDevice = device;
        mPort = new Ch340SerialPort(mDevice, 0);
    }

    @Override
    public UsbDevice getDevice() {
        return mDevice;
    }

    @Override
    public List<UsbSerialPort> getPorts() {
        return Collections.singletonList(mPort);
    }

    public class Ch340SerialPort extends CommonUsbSerialPort {

        private static final int USB_TIMEOUT_MILLIS = 5000;

        private final int DEFAULT_BAUD_RATE = 9600;

        private boolean dtr = false;
        private boolean rts = false;

        public Ch340SerialPort(UsbDevice device, int portNumber) {
            super(device, portNumber);
        }

        @Override
        public UsbSerialDriver getDriver() {
            return Ch34xSerialDriver.this;
        }

        @Override
        protected void openInt() throws IOException {
            for (int i = 0; i < mDevice.getInterfaceCount(); i++) {
                UsbInterface usbIface = mDevice.getInterface(i);
                if (!mConnection.claimInterface(usbIface, true)) {
                    throw new IOException("Could not claim data interface");
                }
            }

            UsbInterface dataIface = mDevice.getInterface(mDevice.getInterfaceCount() - 1);
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

            initialize();
            setBaudRate(DEFAULT_BAUD_RATE);
        }

        @Override
        protected void closeInt() {
            try {
                for (int i = 0; i < mDevice.getInterfaceCount(); i++)
                    mConnection.releaseInterface(mDevice.getInterface(i));
            } catch(Exception ignored) {}
        }

        private int controlOut(int request, int value, int index) {
            final int REQTYPE_HOST_TO_DEVICE = UsbConstants.USB_TYPE_VENDOR | UsbConstants.USB_DIR_OUT;
            return mConnection.controlTransfer(REQTYPE_HOST_TO_DEVICE, request,
                    value, index, null, 0, USB_TIMEOUT_MILLIS);
        }


        private int controlIn(int request, int value, int index, byte[] buffer) {
            final int REQTYPE_DEVICE_TO_HOST = UsbConstants.USB_TYPE_VENDOR | UsbConstants.USB_DIR_IN;
            return mConnection.controlTransfer(REQTYPE_DEVICE_TO_HOST, request,
                    value, index, buffer, buffer.length, USB_TIMEOUT_MILLIS);
        }


        private void checkState(String msg, int request, int value, int[] expected) throws IOException {
            byte[] buffer = new byte[expected.length];
            int ret = controlIn(request, value, 0, buffer);

            if (ret < 0) {
                throw new IOException("Failed send cmd [" + msg + "]");
            }

            if (ret != expected.length) {
                throw new IOException("Expected " + expected.length + " bytes, but get " + ret + " [" + msg + "]");
            }

            for (int i = 0; i < expected.length; i++) {
                if (expected[i] == -1) {
                    continue;
                }

                int current = buffer[i] & 0xff;
                if (expected[i] != current) {
                    throw new IOException("Expected 0x" + Integer.toHexString(expected[i]) + " byte, but get 0x" + Integer.toHexString(current) + " [" + msg + "]");
                }
            }
        }

        private void setControlLines() throws IOException {
            if (controlOut(0xa4, ~((dtr ? SCL_DTR : 0) | (rts ? SCL_RTS : 0)), 0) < 0) {
                throw new IOException("Failed to set control lines");
            }
        }

        private byte getStatus() throws IOException {
            byte[] buffer = new byte[2];
            int ret = controlIn(0x95, 0x0706, 0, buffer);
            if (ret < 0)
                throw new IOException("Error getting control lines");
            return buffer[0];
        }

        private void initialize() throws IOException {
            checkState("init #1", 0x5f, 0, new int[]{-1 /* 0x27, 0x30 */, 0x00});

            if (controlOut(0xa1, 0, 0) < 0) {
                throw new IOException("Init failed: #2");
            }

            setBaudRate(DEFAULT_BAUD_RATE);

            checkState("init #4", 0x95, 0x2518, new int[]{-1 /* 0x56, c3*/, 0x00});

            if (controlOut(0x9a, 0x2518, LCR_ENABLE_RX | LCR_ENABLE_TX | LCR_CS8) < 0) {
                throw new IOException("Init failed: #5");
            }

            checkState("init #6", 0x95, 0x0706, new int[]{-1/*0xf?*/, -1/*0xec,0xee*/});

            if (controlOut(0xa1, 0x501f, 0xd90a) < 0) {
                throw new IOException("Init failed: #7");
            }

            setBaudRate(DEFAULT_BAUD_RATE);

            setControlLines();

            checkState("init #10", 0x95, 0x0706, new int[]{-1/* 0x9f, 0xff*/, -1/*0xec,0xee*/});
        }


        private void setBaudRate(int baudRate) throws IOException {
            long factor;
            long divisor;

            if (baudRate == 921600) {
                divisor = 7;
                factor = 0xf300;
            } else {
                final long BAUDBASE_FACTOR = 1532620800;
                final int BAUDBASE_DIVMAX = 3;

                if(BuildConfig.DEBUG && (baudRate & (3<<29)) == (1<<29))
                    baudRate &= ~(1<<29); // for testing purpose bypass dedicated baud rate handling
                factor = BAUDBASE_FACTOR / baudRate;
                divisor = BAUDBASE_DIVMAX;
                while ((factor > 0xfff0) && divisor > 0) {
                    factor >>= 3;
                    divisor--;
                }
                if (factor > 0xfff0) {
                    throw new UnsupportedOperationException("Unsupported baud rate: " + baudRate);
                }
                factor = 0x10000 - factor;
            }

            divisor |= 0x0080; // else ch341a waits until buffer full
            int val1 = (int) ((factor & 0xff00) | divisor);
            int val2 = (int) (factor & 0xff);
            Log.d(TAG, String.format("baud rate=%d, 0x1312=0x%04x, 0x0f2c=0x%04x", baudRate, val1, val2));
            int ret = controlOut(0x9a, 0x1312, val1);
            if (ret < 0) {
                throw new IOException("Error setting baud rate: #1)");
            }
            ret = controlOut(0x9a, 0x0f2c, val2);
            if (ret < 0) {
                throw new IOException("Error setting baud rate: #2");
            }
        }

        @Override
        public void setParameters(int baudRate, int dataBits, int stopBits, @Parity int parity) throws IOException {
            if(baudRate <= 0) {
                throw new IllegalArgumentException("Invalid baud rate: " + baudRate);
            }
            setBaudRate(baudRate);

            int lcr = LCR_ENABLE_RX | LCR_ENABLE_TX;

            switch (dataBits) {
                case DATABITS_5:
                    lcr |= LCR_CS5;
                    break;
                case DATABITS_6:
                    lcr |= LCR_CS6;
                    break;
                case DATABITS_7:
                    lcr |= LCR_CS7;
                    break;
                case DATABITS_8:
                    lcr |= LCR_CS8;
                    break;
                default:
                    throw new IllegalArgumentException("Invalid data bits: " + dataBits);
            }

            switch (parity) {
                case PARITY_NONE:
                    break;
                case PARITY_ODD:
                    lcr |= LCR_ENABLE_PAR;
                    break;
                case PARITY_EVEN:
                    lcr |= LCR_ENABLE_PAR | LCR_PAR_EVEN;
                    break;
                case PARITY_MARK:
                    lcr |= LCR_ENABLE_PAR | LCR_MARK_SPACE;
                    break;
                case PARITY_SPACE:
                    lcr |= LCR_ENABLE_PAR | LCR_MARK_SPACE | LCR_PAR_EVEN;
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
                    lcr |= LCR_STOP_BITS_2;
                    break;
                default:
                    throw new IllegalArgumentException("Invalid stop bits: " + stopBits);
            }

            int ret = controlOut(0x9a, 0x2518, lcr);
            if (ret < 0) {
                throw new IOException("Error setting control byte");
            }
        }

        @Override
        public boolean getCD() throws IOException {
            return (getStatus() & GCL_CD) == 0;
        }

        @Override
        public boolean getCTS() throws IOException {
            return (getStatus() & GCL_CTS) == 0;
        }

        @Override
        public boolean getDSR() throws IOException {
            return (getStatus() & GCL_DSR) == 0;
        }

        @Override
        public boolean getDTR() throws IOException {
            return dtr;
        }

        @Override
        public void setDTR(boolean value) throws IOException {
            dtr = value;
            setControlLines();
        }

        @Override
        public boolean getRI() throws IOException {
            return (getStatus() & GCL_RI) == 0;
        }

        @Override
        public boolean getRTS() throws IOException {
            return rts;
        }

        @Override
        public void setRTS(boolean value) throws IOException {
            rts = value;
            setControlLines();
        }

        @Override
        public EnumSet<ControlLine> getControlLines() throws IOException {
            int status = getStatus();
            EnumSet<ControlLine> set = EnumSet.noneOf(ControlLine.class);
            if(rts) set.add(ControlLine.RTS);
            if((status & GCL_CTS) == 0) set.add(ControlLine.CTS);
            if(dtr) set.add(ControlLine.DTR);
            if((status & GCL_DSR) == 0) set.add(ControlLine.DSR);
            if((status & GCL_CD) == 0) set.add(ControlLine.CD);
            if((status & GCL_RI) == 0) set.add(ControlLine.RI);
            return set;
        }

        @Override
        public EnumSet<ControlLine> getSupportedControlLines() throws IOException {
            return EnumSet.allOf(ControlLine.class);
        }

        @Override
        public void setBreak(boolean value) throws IOException {
            byte[] req = new byte[2];
            if(controlIn(0x95, 0x1805, 0, req) < 0) {
                throw new IOException("Error getting BREAK condition");
            }
            if(value) {
                req[0] &= ~1;
                req[1] &= ~0x40;
            } else {
                req[0] |= 1;
                req[1] |= 0x40;
            }
            int val = (req[1] & 0xff) << 8 | (req[0] & 0xff);
            if(controlOut(0x9a, 0x1805, val) < 0) {
                throw new IOException("Error setting BREAK condition");
            }
        }
    }

    @SuppressWarnings({"unused"})
    public static Map<Integer, int[]> getSupportedDevices() {
        final Map<Integer, int[]> supportedDevices = new LinkedHashMap<>();
        supportedDevices.put(UsbId.VENDOR_QINHENG, new int[]{
                UsbId.QINHENG_CH340,
                UsbId.QINHENG_CH341A,
        });
        return supportedDevices;
    }

}