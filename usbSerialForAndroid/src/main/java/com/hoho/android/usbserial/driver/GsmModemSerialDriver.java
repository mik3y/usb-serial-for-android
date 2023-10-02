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

public class GsmModemSerialDriver implements UsbSerialDriver{

    private final String TAG = GsmModemSerialDriver.class.getSimpleName();

    private final UsbDevice mDevice;
    private final UsbSerialPort mPort;

    @Override
    public UsbDevice getDevice() {
        return mDevice;
    }

    @Override
    public List<UsbSerialPort> getPorts() {
        return Collections.singletonList(mPort);
    }

    public GsmModemSerialDriver(UsbDevice mDevice) {
        this.mDevice = mDevice;
        mPort = new GsmModemSerialPort(mDevice, 0);
    }

    public class GsmModemSerialPort extends CommonUsbSerialPort {

        private UsbInterface mDataInterface;

        public GsmModemSerialPort(UsbDevice device, int portNumber) {
            super(device, portNumber);
        }

        @Override
        protected void openInt() throws IOException {
            Log.d(TAG, "claiming interfaces, count=" + mDevice.getInterfaceCount());
            mDataInterface = mDevice.getInterface(0);
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
            initGsmModem();
        }

        @Override
        protected void closeInt() {
            try {
                mConnection.releaseInterface(mDataInterface);
            } catch(Exception ignored) {}

        }

        private int initGsmModem() throws IOException {
            int len = mConnection.controlTransfer(
                    0x21, 0x22, 0x01, 0, null, 0, 5000);
            if(len < 0) {
                throw new IOException("init failed");
            }
            return len;
        }

        @Override
        public UsbSerialDriver getDriver() {
            return GsmModemSerialDriver.this;
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
        supportedDevices.put(UsbId.VENDOR_UNISOC, new int[]{
                UsbId.FIBOCOM_L610,
                UsbId.FIBOCOM_L612,
        });
        return supportedDevices;
    }
}
