package com.hoho.android.usbserial.driver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbRequest;

import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class CommonUsbSerialPortTest {

    static class DummySerialDriver implements UsbSerialDriver {
        ArrayList<UsbSerialPort> ports = new ArrayList<>();

        DummySerialDriver() { ports.add(new DummySerialPort(null, 0)); }

        @Override
        public UsbDevice getDevice() { return null; }

        @Override
        public List<UsbSerialPort> getPorts() { return ports; }
    }

    static class DummySerialPort extends CommonUsbSerialPort {
        public DummySerialPort(UsbDevice device, int portNumber) {
            super(device, portNumber);
            mUsbRequestSupplier = DummyUsbRequest::new;
        }

        @Override
        protected void openInt() throws IOException { mReadEndpoint = mWriteEndpoint = mock(UsbEndpoint.class); }

        @Override
        protected void closeInt() { }

        @Override
        public UsbSerialDriver getDriver() { return null; }

        @Override
        public void setParameters(int baudRate, int dataBits, int stopBits, int parity) throws IOException { }
    }

    static class DummyUsbRequest extends UsbRequest {
        @Override
        public boolean initialize(UsbDeviceConnection connection, UsbEndpoint endpoint) { return true; }

        @Override
        public void setClientData(Object data) { }

        @Override
        public boolean queue(ByteBuffer buffer, int length) { return true; }
    }

    @Test
    public void readQueue() throws Exception {
        UsbDeviceConnection usbDeviceConnection = mock(UsbDeviceConnection.class);
        DummySerialDriver driver = new DummySerialDriver();
        CommonUsbSerialPort port = (CommonUsbSerialPort) driver.getPorts().get(0);

        // set before open
        port.setReadQueue(0, 0);
        assertThrows(IllegalArgumentException.class, () -> port.setReadQueue(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> port.setReadQueue(1, -1));
        port.setReadQueue(2, 256);
        assertNull(port.mReadQueueRequests);

        // change after open
        port.open(usbDeviceConnection);
        assertNotNull(port.mReadQueueRequests);
        assertEquals(2, port.mReadQueueRequests.size());
        assertThrows(IllegalStateException.class, () -> port.setReadQueue(1, 256));
        port.setReadQueue(3, 256);
        assertEquals(3, port.mReadQueueRequests.size());
        assertThrows(IllegalStateException.class, () -> port.setReadQueue(3, 128));
        assertThrows(IllegalStateException.class, () -> port.setReadQueue(3, 512));

        // set after open
        port.close();
        port.setReadQueue(0, 0);
        port.open(usbDeviceConnection);
        assertNull(port.mReadQueueRequests);
        port.setReadQueue(3, 256);

        // retain over close
        port.close();
        assertNull(port.mReadQueueRequests);
        port.open(usbDeviceConnection);
        assertEquals(3, port.mReadQueueRequests.size());
    }

}

