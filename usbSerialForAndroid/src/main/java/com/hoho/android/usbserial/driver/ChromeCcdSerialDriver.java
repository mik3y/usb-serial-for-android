package com.hoho.android.usbserial.driver;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.util.Log;

import java.io.IOException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

public class ChromeCcdSerialDriver implements UsbSerialDriver{

    private final String TAG = ChromeCcdSerialDriver.class.getSimpleName();

    private final UsbDevice mDevice;
    private final List<UsbSerialPort> mPorts;

    @Override
    public UsbDevice getDevice() {
        return mDevice;
    }

    @Override
    public List<UsbSerialPort> getPorts() {
        return mPorts;
    }

    public ChromeCcdSerialDriver(UsbDevice mDevice) {
        this.mDevice = mDevice;
        mPorts = new ArrayList<UsbSerialPort>();
        for (int i = 0; i < 3; i++)
            mPorts.add(new ChromeCcdSerialPort(mDevice, i));
    }

    public class ChromeCcdSerialPort extends CommonUsbSerialPort {
        private UsbInterface mDataInterface;

        public ChromeCcdSerialPort(UsbDevice device, int portNumber) {
            super(device, portNumber);
        }

        @Override
        protected void openInt() throws IOException {
            Log.d(TAG, "claiming interfaces, count=" + mDevice.getInterfaceCount());
            mDataInterface = mDevice.getInterface(mPortNumber);
            if (!mConnection.claimInterface(mDataInterface, true)) {
                throw new IOException("Could not claim shared control/data interface");
            }
            Log.d(TAG, "endpoint count=" + mDataInterface.getEndpointCount());
            for (int i = 0; i < mDataInterface.getEndpointCount(); ++i) {
                UsbEndpoint ep = mDataInterface.getEndpoint(i);
                if ((ep.getDirection() == UsbConstants.USB_DIR_IN) && (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK)) {
                    mReadEndpoint = ep;
                } else if ((ep.getDirection() == UsbConstants.USB_DIR_OUT) && (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK)) {
                    mWriteEndpoint = ep;
                }
            }
        }

        @Override
        protected void closeInt() {
            try {
                mConnection.releaseInterface(mDataInterface);
            } catch(Exception ignored) {}
        }

        @Override
        public UsbSerialDriver getDriver() {
            return ChromeCcdSerialDriver.this;
        }

        @Override
        public void setParameters(int baudRate, int dataBits, int stopBits, int parity) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public EnumSet<ControlLine> getSupportedControlLines() throws IOException {
            return EnumSet.noneOf(ControlLine.class);
        }
    }

    public static Map<Integer, int[]> getSupportedDevices() {
        final Map<Integer, int[]> supportedDevices = new LinkedHashMap<>();
        supportedDevices.put(UsbId.VENDOR_GOOGLE, new int[]{
                UsbId.GOOGLE_CR50,
        });
        return supportedDevices;
    }
}
