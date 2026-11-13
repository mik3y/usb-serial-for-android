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
import android.util.Log;

import com.hoho.android.usbserial.util.HexDump;
import com.hoho.android.usbserial.util.MonotonicClock;
import com.hoho.android.usbserial.util.UsbUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * USB CDC/ACM serial driver implementation.
 *
 * @author mike wakerly (opensource@hoho.com)
 * @see <a
 *      href="http://www.usb.org/developers/devclass_docs/usbcdc11.pdf">Universal
 *      Serial Bus Class Definitions for Communication Devices, v1.1</a>
 */
public class CdcAcmSerialDriver implements UsbSerialDriver {

    public static final int USB_SUBCLASS_ACM = 2;

    private final String TAG = CdcAcmSerialDriver.class.getSimpleName();

    private final UsbDevice mDevice;
    private final List<UsbSerialPort> mPorts;

    public CdcAcmSerialDriver(UsbDevice device) {
        mDevice = device;
        mPorts = new ArrayList<>();
        int ports = countPorts(device);
        for (int port = 0; port < ports; port++) {
            mPorts.add(new CdcAcmSerialPort(mDevice, port));
        }
        if (mPorts.size() == 0) {
            mPorts.add(new CdcAcmSerialPort(mDevice, -1));
        }
    }

    @SuppressWarnings({"unused"})
    public static boolean probe(UsbDevice device) {
        return countPorts(device) > 0;
    }

    private static int countPorts(UsbDevice device) {
        int controlInterfaceCount = 0;
        int dataInterfaceCount = 0;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            if (device.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_COMM &&
                    device.getInterface(i).getInterfaceSubclass() == USB_SUBCLASS_ACM)
                controlInterfaceCount++;
            if (device.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_CDC_DATA)
                dataInterfaceCount++;
        }
        return Math.min(controlInterfaceCount, dataInterfaceCount);
    }

    @Override
    public UsbDevice getDevice() {
        return mDevice;
    }

    @Override
    public List<UsbSerialPort> getPorts() {
        return mPorts;
    }

    public class CdcAcmSerialPort extends CommonUsbSerialPort {

        private UsbInterface mControlInterface;
        private UsbInterface mDataInterface;

        private UsbEndpoint mControlEndpoint;

        private int mControlIndex;

        private boolean mRts = false;
        private boolean mDtr = false;

        private static final int USB_RECIP_INTERFACE = 0x01;
        private static final int USB_RT_ACM = UsbConstants.USB_TYPE_CLASS | USB_RECIP_INTERFACE;

        private static final int SET_LINE_CODING = 0x20;  // USB CDC 1.1 section 6.2
        private static final int GET_LINE_CODING = 0x21;
        private static final int SET_CONTROL_LINE_STATE = 0x22;
        private static final int SEND_BREAK = 0x23;

        private static final int NOTIFICATION_REQUEST_TYPE = 0xA1;
        private static final int SERIAL_STATE = 0x20;
        private static final int SERIAL_STATE_PACKET_SIZE = 10;
        private static final int SERIAL_STATE_FLAG_CD = 0x01;
        private static final int SERIAL_STATE_FLAG_DSR = 0x02;
        private static final int SERIAL_STATE_FLAG_RI = 0x08;

        private volatile int mSerialState = 0;
        private volatile Thread mReadControlLinesThread = null;
        private final Object mReadControlLinesThreadLock = new Object();
        private boolean mStopReadControlLinesThread = false;
        private Exception mReadControlLinesException = null;

        public CdcAcmSerialPort(UsbDevice device, int portNumber) {
            super(device, portNumber);
        }

        @Override
        public UsbSerialDriver getDriver() {
            return CdcAcmSerialDriver.this;
        }

        @Override
        protected void openInt() throws IOException {
            Log.d(TAG, "interfaces:");
            for (int i = 0; i < mDevice.getInterfaceCount(); i++) {
                Log.d(TAG, mDevice.getInterface(i).toString());
            }
            if (mPortNumber == -1) {
                Log.d(TAG,"device might be castrated ACM device, trying single interface logic");
                openSingleInterface();
            } else {
                Log.d(TAG,"trying default interface logic");
                openInterface();
            }
        }

        private void openSingleInterface() throws IOException {
            // the following code is inspired by the cdc-acm driver in the linux kernel

            mControlIndex = 0;
            mControlInterface = mDevice.getInterface(0);
            mDataInterface = mDevice.getInterface(0);
            if (!mConnection.claimInterface(mControlInterface, true)) {
                throw new IOException("Could not claim shared control/data interface");
            }

            for (int i = 0; i < mControlInterface.getEndpointCount(); ++i) {
                UsbEndpoint ep = mControlInterface.getEndpoint(i);
                if ((ep.getDirection() == UsbConstants.USB_DIR_IN) && (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_INT)) {
                    mControlEndpoint = ep;
                } else if ((ep.getDirection() == UsbConstants.USB_DIR_IN) && (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK)) {
                    mReadEndpoint = ep;
                } else if ((ep.getDirection() == UsbConstants.USB_DIR_OUT) && (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK)) {
                    mWriteEndpoint = ep;
                }
            }
            if (mControlEndpoint == null) {
                throw new IOException("No control endpoint");
            }
        }

        private void openInterface() throws IOException {

            mControlInterface = null;
            mDataInterface = null;
            int j = getInterfaceIdFromDescriptors();
            Log.d(TAG, "interface count=" + mDevice.getInterfaceCount() + ", IAD=" + j);
            if (j >= 0) {
                for (int i = 0; i < mDevice.getInterfaceCount(); i++) {
                    UsbInterface usbInterface = mDevice.getInterface(i);
                    if (usbInterface.getId() == j || usbInterface.getId() == j+1) {
                        if (usbInterface.getInterfaceClass() == UsbConstants.USB_CLASS_COMM &&
                                usbInterface.getInterfaceSubclass() == USB_SUBCLASS_ACM) {
                            mControlIndex = usbInterface.getId();
                            mControlInterface = usbInterface;
                        }
                        if (usbInterface.getInterfaceClass() == UsbConstants.USB_CLASS_CDC_DATA) {
                            mDataInterface = usbInterface;
                        }
                    }
                }
            }
            if (mControlInterface == null || mDataInterface == null) {
                Log.d(TAG, "no IAD fallback");
                int controlInterfaceCount = 0;
                int dataInterfaceCount = 0;
                for (int i = 0; i < mDevice.getInterfaceCount(); i++) {
                    UsbInterface usbInterface = mDevice.getInterface(i);
                    if (usbInterface.getInterfaceClass() == UsbConstants.USB_CLASS_COMM &&
                            usbInterface.getInterfaceSubclass() == USB_SUBCLASS_ACM) {
                        if (controlInterfaceCount == mPortNumber) {
                            mControlIndex = usbInterface.getId();
                            mControlInterface = usbInterface;
                        }
                        controlInterfaceCount++;
                    }
                    if (usbInterface.getInterfaceClass() == UsbConstants.USB_CLASS_CDC_DATA) {
                        if (dataInterfaceCount == mPortNumber) {
                            mDataInterface = usbInterface;
                        }
                        dataInterfaceCount++;
                    }
                }
            }

            if(mControlInterface == null) {
                throw new IOException("No control interface");
            }
            Log.d(TAG, "Control interface id " + mControlInterface.getId());

            if (!mConnection.claimInterface(mControlInterface, true)) {
                throw new IOException("Could not claim control interface");
            }
            mControlEndpoint = mControlInterface.getEndpoint(0);
            if (mControlEndpoint.getDirection() != UsbConstants.USB_DIR_IN || mControlEndpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_INT) {
                throw new IOException("Invalid control endpoint");
            }

            if(mDataInterface == null) {
                throw new IOException("No data interface");
            }
            Log.d(TAG, "data interface id " + mDataInterface.getId());
            if (!mConnection.claimInterface(mDataInterface, true)) {
                throw new IOException("Could not claim data interface");
            }
            for (int i = 0; i < mDataInterface.getEndpointCount(); i++) {
                UsbEndpoint ep = mDataInterface.getEndpoint(i);
                if (ep.getDirection() == UsbConstants.USB_DIR_IN && ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK)
                    mReadEndpoint = ep;
                if (ep.getDirection() == UsbConstants.USB_DIR_OUT && ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK)
                    mWriteEndpoint = ep;
            }
        }

        private int getInterfaceIdFromDescriptors() {
            ArrayList<byte[]> descriptors = UsbUtils.getDescriptors(mConnection);
            Log.d(TAG, "USB descriptor:");
            for(byte[] descriptor : descriptors)
                Log.d(TAG, HexDump.toHexString(descriptor));

            if (descriptors.size() > 0 &&
                    descriptors.get(0).length == 18 &&
                    descriptors.get(0)[1] == 1 && // bDescriptorType
                    descriptors.get(0)[4] == (byte)(UsbConstants.USB_CLASS_MISC) && //bDeviceClass
                    descriptors.get(0)[5] == 2 && // bDeviceSubClass
                    descriptors.get(0)[6] == 1) { // bDeviceProtocol
                // is IAD device, see https://www.usb.org/sites/default/files/iadclasscode_r10.pdf
                int port = -1;
                for (int d = 1; d < descriptors.size(); d++) {
                    if (descriptors.get(d).length == 8 &&
                            descriptors.get(d)[1] == 0x0b && // bDescriptorType == IAD
                            descriptors.get(d)[4] == UsbConstants.USB_CLASS_COMM && // bFunctionClass == CDC
                            descriptors.get(d)[5] == USB_SUBCLASS_ACM) { // bFunctionSubClass == ACM
                        port++;
                        if (port == mPortNumber &&
                                descriptors.get(d)[3] == 2) { // bInterfaceCount
                            return descriptors.get(d)[2]; // bFirstInterface
                        }
                    }
                }
            }
            return -1;
        }

        private int sendAcmControlMessage(int request, int value, byte[] buf) throws IOException {
            int len = mConnection.controlTransfer(
                    USB_RT_ACM, request, value, mControlIndex, buf, buf != null ? buf.length : 0, 5000);
            if(len < 0) {
                throw new IOException("controlTransfer failed");
            }
            return len;
        }

        private void readControlLinesThreadFunction() {
            try {
                byte[] buffer = new byte[Math.max(SERIAL_STATE_PACKET_SIZE, mControlEndpoint.getMaxPacketSize())];
                while (!mStopReadControlLinesThread && mConnection!=null) {
                    long endTime = MonotonicClock.millis() + 500;
                    int readBytesCount = mConnection.bulkTransfer(mControlEndpoint, buffer, buffer.length, 500);
                    if (readBytesCount == -1) {
                        testConnection(MonotonicClock.millis() < endTime);
                        continue;
                    }
                    if (readBytesCount != SERIAL_STATE_PACKET_SIZE) continue;
                    if (buffer[0] != (byte)NOTIFICATION_REQUEST_TYPE) continue;
                    if (buffer[1] != SERIAL_STATE) continue;
                    int index = ((buffer[5] & 0xff) << 8) | (buffer[4] & 0xff);
                    if (index != mControlIndex) continue;
                    int payloadLength = ((buffer[7] & 0xff) << 8) | (buffer[6] & 0xff);
                    if (payloadLength < 2) continue;
                    mSerialState = ((buffer[9] & 0xff) << 8) | (buffer[8] & 0xff);
                    Log.d(TAG, "control line state " + Arrays.toString(buffer));
                }
            } catch (Exception e) {
                mReadControlLinesException = e;
            }
        }

        private int getSerialState() throws IOException {
            if(mConnection == null)
                throw new IOException("connection closed");
            if (mReadControlLinesThread == null) {
                synchronized (mReadControlLinesThreadLock) {
                    if (mReadControlLinesThread == null) {
                        mSerialState = 0;
                        mReadControlLinesThread = new Thread(this::readControlLinesThreadFunction);
                        mReadControlLinesThread.setDaemon(true);
                        mReadControlLinesThread.start();
                    }
                }
            }
            if (mReadControlLinesException != null) {
                throw new IOException(mReadControlLinesException);
            }
            return mSerialState;
        }

        @Override
        protected void closeInt() {
            try {
                synchronized (mReadControlLinesThreadLock) {
                    if (mReadControlLinesThread != null) {
                        try {
                            mStopReadControlLinesThread = true;
                            mReadControlLinesThread.join();
                        } catch (Exception e) {
                            Log.w(TAG, "An error occurred while waiting for control line read thread", e);
                        }
                        mStopReadControlLinesThread = false;
                        mReadControlLinesThread = null;
                        mReadControlLinesException = null;
                    }
                }
                mConnection.releaseInterface(mControlInterface);
                mConnection.releaseInterface(mDataInterface);
            } catch(Exception ignored) {}
        }

        @Override
        public void setParameters(int baudRate, int dataBits, int stopBits, @Parity int parity) throws IOException {
            if(baudRate <= 0) {
                throw new IllegalArgumentException("Invalid baud rate: " + baudRate);
            }
            if(dataBits < DATABITS_5 || dataBits > DATABITS_8) {
                throw new IllegalArgumentException("Invalid data bits: " + dataBits);
            }
            byte stopBitsByte;
            switch (stopBits) {
                case STOPBITS_1: stopBitsByte = 0; break;
                case STOPBITS_1_5: stopBitsByte = 1; break;
                case STOPBITS_2: stopBitsByte = 2; break;
                default: throw new IllegalArgumentException("Invalid stop bits: " + stopBits);
            }

            byte parityBitesByte;
            switch (parity) {
                case PARITY_NONE: parityBitesByte = 0; break;
                case PARITY_ODD: parityBitesByte = 1; break;
                case PARITY_EVEN: parityBitesByte = 2; break;
                case PARITY_MARK: parityBitesByte = 3; break;
                case PARITY_SPACE: parityBitesByte = 4; break;
                default: throw new IllegalArgumentException("Invalid parity: " + parity);
            }
            byte[] msg = {
                    (byte) ( baudRate & 0xff),
                    (byte) ((baudRate >> 8 ) & 0xff),
                    (byte) ((baudRate >> 16) & 0xff),
                    (byte) ((baudRate >> 24) & 0xff),
                    stopBitsByte,
                    parityBitesByte,
                    (byte) dataBits};
            sendAcmControlMessage(SET_LINE_CODING, 0, msg);
        }

        @Override
        public boolean getDTR() throws IOException {
            return mDtr;
        }

        @Override
        public void setDTR(boolean value) throws IOException {
            mDtr = value;
            setDtrRts();
        }

        @Override
        public boolean getRTS() throws IOException {
            return mRts;
        }

        @Override
        public boolean getCD() throws IOException {
            return (getSerialState() & SERIAL_STATE_FLAG_CD) != 0;
        }

        @Override
        public boolean getDSR() throws IOException {
            return (getSerialState() & SERIAL_STATE_FLAG_DSR) != 0;
        }

        @Override
        public boolean getRI() throws IOException {
            return (getSerialState() & SERIAL_STATE_FLAG_RI) != 0;
        }

        @Override
        public void setRTS(boolean value) throws IOException {
            mRts = value;
            setDtrRts();
        }

        private void setDtrRts() throws IOException {
            int value = (mRts ? 0x2 : 0) | (mDtr ? 0x1 : 0);
            sendAcmControlMessage(SET_CONTROL_LINE_STATE, value, null);
        }

        @Override
        public void setControlLines(EnumSet<ControlLine> lines) throws IOException {
            requireControlLines(lines);
            boolean newDtr = lines.contains(ControlLine.DTR);
            boolean newRts = lines.contains(ControlLine.RTS);
            if (mDtr == newDtr && mRts == newRts) return;
            sendAcmControlMessage(SET_CONTROL_LINE_STATE,
                    (newDtr ? 0x1 : 0) | (newRts ? 0x2 : 0), null);
            mDtr = newDtr;
            mRts = newRts;
        }

        @Override
        public EnumSet<ControlLine> getControlLines() throws IOException {
            int serialState = getSerialState();
            EnumSet<ControlLine> set = EnumSet.noneOf(ControlLine.class);
            if(mRts) set.add(ControlLine.RTS);
            // no CTS
            if(mDtr) set.add(ControlLine.DTR);
            if((serialState & SERIAL_STATE_FLAG_DSR) != 0) set.add(ControlLine.DSR);
            if((serialState & SERIAL_STATE_FLAG_CD) != 0) set.add(ControlLine.CD);
            if((serialState & SERIAL_STATE_FLAG_RI) != 0) set.add(ControlLine.RI);
            return set;
        }

        @Override
        public EnumSet<ControlLine> getSupportedControlLines() throws IOException {
            return EnumSet.of(ControlLine.RTS, ControlLine.DTR, ControlLine.DSR, ControlLine.CD, ControlLine.RI);
        }

        @Override
        public void setBreak(boolean value) throws IOException {
            sendAcmControlMessage(SEND_BREAK, value ? 0xffff : 0, null);
        }

    }

    @SuppressWarnings({"unused"})
    public static Map<Integer, int[]> getSupportedDevices() {
        return new LinkedHashMap<>();
    }

}
