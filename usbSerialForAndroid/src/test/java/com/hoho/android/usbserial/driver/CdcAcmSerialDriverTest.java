package com.hoho.android.usbserial.driver;

import static com.hoho.android.usbserial.driver.CdcAcmSerialDriver.USB_SUBCLASS_ACM;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;

import org.junit.Test;

import java.io.IOException;

public class CdcAcmSerialDriverTest {

    @Test
    public void standardDevice() throws Exception {
        UsbDeviceConnection usbDeviceConnection = mock(UsbDeviceConnection.class);
        UsbDevice usbDevice = mock(UsbDevice.class);
        UsbInterface controlInterface = mock(UsbInterface.class);
        UsbInterface dataInterface = mock(UsbInterface.class);
        UsbEndpoint controlEndpoint = mock(UsbEndpoint.class);
        UsbEndpoint readEndpoint = mock(UsbEndpoint.class);
        UsbEndpoint writeEndpoint = mock(UsbEndpoint.class);

        when(usbDeviceConnection.claimInterface(controlInterface,true)).thenReturn(true);
        when(usbDeviceConnection.claimInterface(dataInterface,true)).thenReturn(true);
        when(usbDevice.getInterfaceCount()).thenReturn(2);
        when(usbDevice.getInterface(0)).thenReturn(controlInterface);
        when(usbDevice.getInterface(1)).thenReturn(dataInterface);
        when(controlInterface.getInterfaceClass()).thenReturn(UsbConstants.USB_CLASS_COMM);
        when(controlInterface.getInterfaceSubclass()).thenReturn(USB_SUBCLASS_ACM);
        when(controlInterface.getEndpointCount()).thenReturn(1);
        when(controlInterface.getEndpoint(0)).thenReturn(controlEndpoint);
        when(dataInterface.getInterfaceClass()).thenReturn(UsbConstants.USB_CLASS_CDC_DATA);
        when(dataInterface.getEndpointCount()).thenReturn(2);
        when(dataInterface.getEndpoint(0)).thenReturn(writeEndpoint);
        when(dataInterface.getEndpoint(1)).thenReturn(readEndpoint);
        when(controlEndpoint.getDirection()).thenReturn(UsbConstants.USB_DIR_IN);
        when(controlEndpoint.getType()).thenReturn(UsbConstants.USB_ENDPOINT_XFER_INT);
        when(readEndpoint.getDirection()).thenReturn(UsbConstants.USB_DIR_IN);
        when(readEndpoint.getType()).thenReturn(UsbConstants.USB_ENDPOINT_XFER_BULK);
        when(writeEndpoint.getDirection()).thenReturn(UsbConstants.USB_DIR_OUT);
        when(writeEndpoint.getType()).thenReturn(UsbConstants.USB_ENDPOINT_XFER_BULK);

        CdcAcmSerialDriver driver = new CdcAcmSerialDriver(usbDevice);
        CdcAcmSerialDriver.CdcAcmSerialPort port = (CdcAcmSerialDriver.CdcAcmSerialPort) driver.getPorts().get(0);
        port.mConnection = usbDeviceConnection;
        port.openInt();
        assertEquals(readEndpoint, port.mReadEndpoint);
        assertEquals(writeEndpoint, port.mWriteEndpoint);

        ProbeTable probeTable = UsbSerialProber.getDefaultProbeTable();
        Class<? extends UsbSerialDriver> probeDriver = probeTable.findDriver(usbDevice);
        assertEquals(driver.getClass(), probeDriver);
    }

    @Test
    public void singleInterfaceDevice() throws Exception {
        UsbDeviceConnection usbDeviceConnection = mock(UsbDeviceConnection.class);
        UsbDevice usbDevice = mock(UsbDevice.class);
        UsbInterface usbInterface = mock(UsbInterface.class);
        UsbEndpoint controlEndpoint = mock(UsbEndpoint.class);
        UsbEndpoint readEndpoint = mock(UsbEndpoint.class);
        UsbEndpoint writeEndpoint = mock(UsbEndpoint.class);

        when(usbDeviceConnection.claimInterface(usbInterface,true)).thenReturn(true);
        when(usbDevice.getInterfaceCount()).thenReturn(1);
        when(usbDevice.getInterface(0)).thenReturn(usbInterface);
        when(usbInterface.getEndpointCount()).thenReturn(3);
        when(usbInterface.getEndpoint(0)).thenReturn(controlEndpoint);
        when(usbInterface.getEndpoint(1)).thenReturn(readEndpoint);
        when(usbInterface.getEndpoint(2)).thenReturn(writeEndpoint);
        when(controlEndpoint.getDirection()).thenReturn(UsbConstants.USB_DIR_IN);
        when(controlEndpoint.getType()).thenReturn(UsbConstants.USB_ENDPOINT_XFER_INT);
        when(readEndpoint.getDirection()).thenReturn(UsbConstants.USB_DIR_IN);
        when(readEndpoint.getType()).thenReturn(UsbConstants.USB_ENDPOINT_XFER_BULK);
        when(writeEndpoint.getDirection()).thenReturn(UsbConstants.USB_DIR_OUT);
        when(writeEndpoint.getType()).thenReturn(UsbConstants.USB_ENDPOINT_XFER_BULK);

        CdcAcmSerialDriver driver = new CdcAcmSerialDriver(usbDevice);
        CdcAcmSerialDriver.CdcAcmSerialPort port = (CdcAcmSerialDriver.CdcAcmSerialPort) driver.getPorts().get(0);
        port.mConnection = usbDeviceConnection;
        port.openInt();
        assertEquals(readEndpoint, port.mReadEndpoint);
        assertEquals(writeEndpoint, port.mWriteEndpoint);

        ProbeTable probeTable = UsbSerialProber.getDefaultProbeTable();
        Class<? extends UsbSerialDriver> probeDriver = probeTable.findDriver(usbDevice);
        assertNull(probeDriver);
    }

    @Test
    public void multiPortDevice() throws Exception {
        int n = 4;
        UsbDeviceConnection usbDeviceConnection = mock(UsbDeviceConnection.class);
        UsbDevice usbDevice = mock(UsbDevice.class);
        UsbInterface[] controlInterfaces = new UsbInterface[n];
        UsbInterface[] dataInterfaces = new UsbInterface[n];
        UsbEndpoint[] controlEndpoints = new UsbEndpoint[n];
        UsbEndpoint[] readEndpoints = new UsbEndpoint[n];
        UsbEndpoint[] writeEndpoints = new UsbEndpoint[n];

        when(usbDevice.getInterfaceCount()).thenReturn(2*n);
        for(int i=0; i<n; i++) {
            controlInterfaces[i] = mock(UsbInterface.class);
            dataInterfaces[i] = mock(UsbInterface.class);
            controlEndpoints[i] = mock(UsbEndpoint.class);
            readEndpoints[i] = mock(UsbEndpoint.class);
            writeEndpoints[i] = mock(UsbEndpoint.class);
            when(usbDeviceConnection.claimInterface(controlInterfaces[i], true)).thenReturn(true);
            when(usbDeviceConnection.claimInterface(dataInterfaces[i], true)).thenReturn(true);
            when(usbDevice.getInterface(2*i+0)).thenReturn(controlInterfaces[i]);
            when(usbDevice.getInterface(2*i+1)).thenReturn(dataInterfaces[i]);
            when(controlInterfaces[i].getInterfaceClass()).thenReturn(UsbConstants.USB_CLASS_COMM);
            when(controlInterfaces[i].getInterfaceSubclass()).thenReturn(USB_SUBCLASS_ACM);
            when(controlInterfaces[i].getEndpointCount()).thenReturn(1);
            when(controlInterfaces[i].getEndpoint(0)).thenReturn(controlEndpoints[i]);
            when(dataInterfaces[i].getInterfaceClass()).thenReturn(UsbConstants.USB_CLASS_CDC_DATA);
            when(dataInterfaces[i].getEndpointCount()).thenReturn(2);
            when(dataInterfaces[i].getEndpoint(0)).thenReturn(writeEndpoints[i]);
            when(dataInterfaces[i].getEndpoint(1)).thenReturn(readEndpoints[i]);
            when(controlEndpoints[i].getDirection()).thenReturn(UsbConstants.USB_DIR_IN);
            when(controlEndpoints[i].getType()).thenReturn(UsbConstants.USB_ENDPOINT_XFER_INT);
            when(readEndpoints[i].getDirection()).thenReturn(UsbConstants.USB_DIR_IN);
            when(readEndpoints[i].getType()).thenReturn(UsbConstants.USB_ENDPOINT_XFER_BULK);
            when(writeEndpoints[i].getDirection()).thenReturn(UsbConstants.USB_DIR_OUT);
            when(writeEndpoints[i].getType()).thenReturn(UsbConstants.USB_ENDPOINT_XFER_BULK);
        }
        int i = 2;
        CdcAcmSerialDriver driver = new CdcAcmSerialDriver(usbDevice);
        CdcAcmSerialDriver.CdcAcmSerialPort port = (CdcAcmSerialDriver.CdcAcmSerialPort) driver.getPorts().get(i);
        port.mConnection = usbDeviceConnection;
        port.openInt();
        assertEquals(readEndpoints[i], port.mReadEndpoint);
        assertEquals(writeEndpoints[i], port.mWriteEndpoint);
    }

    @Test
    public void compositeDevice() throws Exception {
        // mock BBC micro:bit
        UsbDeviceConnection usbDeviceConnection = mock(UsbDeviceConnection.class);
        UsbDevice usbDevice = mock(UsbDevice.class);
        UsbInterface massStorageInterface = mock(UsbInterface.class);
        UsbInterface controlInterface = mock(UsbInterface.class);
        UsbInterface dataInterface = mock(UsbInterface.class);
        UsbInterface hidInterface = mock(UsbInterface.class);
        UsbInterface vendorInterface = mock(UsbInterface.class);
        UsbEndpoint controlEndpoint = mock(UsbEndpoint.class);
        UsbEndpoint readEndpoint = mock(UsbEndpoint.class);
        UsbEndpoint writeEndpoint = mock(UsbEndpoint.class);

        when(usbDeviceConnection.claimInterface(controlInterface,true)).thenReturn(true);
        when(usbDeviceConnection.claimInterface(dataInterface,true)).thenReturn(true);
        when(usbDevice.getInterfaceCount()).thenReturn(5);
        when(usbDevice.getInterface(0)).thenReturn(massStorageInterface);
        when(usbDevice.getInterface(1)).thenReturn(controlInterface);
        when(usbDevice.getInterface(2)).thenReturn(dataInterface);
        when(usbDevice.getInterface(3)).thenReturn(hidInterface);
        when(usbDevice.getInterface(4)).thenReturn(vendorInterface);
        when(massStorageInterface.getInterfaceClass()).thenReturn(UsbConstants.USB_CLASS_MASS_STORAGE);
        when(controlInterface.getInterfaceClass()).thenReturn(UsbConstants.USB_CLASS_COMM);
        when(controlInterface.getInterfaceSubclass()).thenReturn(USB_SUBCLASS_ACM);
        when(dataInterface.getInterfaceClass()).thenReturn(UsbConstants.USB_CLASS_CDC_DATA);
        when(hidInterface.getInterfaceClass()).thenReturn(UsbConstants.USB_CLASS_HID);
        when(vendorInterface.getInterfaceClass()).thenReturn(UsbConstants.USB_CLASS_VENDOR_SPEC);

        when(controlInterface.getEndpointCount()).thenReturn(1);
        when(controlInterface.getEndpoint(0)).thenReturn(controlEndpoint);
        when(dataInterface.getEndpointCount()).thenReturn(2);
        when(dataInterface.getEndpoint(0)).thenReturn(writeEndpoint);
        when(dataInterface.getEndpoint(1)).thenReturn(readEndpoint);
        when(controlEndpoint.getDirection()).thenReturn(UsbConstants.USB_DIR_IN);
        when(controlEndpoint.getType()).thenReturn(UsbConstants.USB_ENDPOINT_XFER_INT);
        when(readEndpoint.getDirection()).thenReturn(UsbConstants.USB_DIR_IN);
        when(readEndpoint.getType()).thenReturn(UsbConstants.USB_ENDPOINT_XFER_BULK);
        when(writeEndpoint.getDirection()).thenReturn(UsbConstants.USB_DIR_OUT);
        when(writeEndpoint.getType()).thenReturn(UsbConstants.USB_ENDPOINT_XFER_BULK);

        CdcAcmSerialDriver driver = new CdcAcmSerialDriver(usbDevice);
        CdcAcmSerialDriver.CdcAcmSerialPort port = (CdcAcmSerialDriver.CdcAcmSerialPort) driver.getPorts().get(0);
        port.mConnection = usbDeviceConnection;
        port.openInt();
        assertEquals(readEndpoint, port.mReadEndpoint);
        assertEquals(writeEndpoint, port.mWriteEndpoint);
    }


    @Test
    public void compositeRndisDevice() throws Exception {
        UsbDeviceConnection usbDeviceConnection = mock(UsbDeviceConnection.class);
        UsbDevice usbDevice = mock(UsbDevice.class);
        UsbInterface rndisControlInterface = mock(UsbInterface.class);
        UsbInterface rndisDataInterface = mock(UsbInterface.class);
        UsbInterface controlInterface = mock(UsbInterface.class);
        UsbInterface dataInterface = mock(UsbInterface.class);
        UsbEndpoint controlEndpoint = mock(UsbEndpoint.class);
        UsbEndpoint readEndpoint = mock(UsbEndpoint.class);
        UsbEndpoint writeEndpoint = mock(UsbEndpoint.class);

        when(usbDeviceConnection.claimInterface(controlInterface,true)).thenReturn(true);
        when(usbDeviceConnection.claimInterface(dataInterface,true)).thenReturn(true);
        when(usbDevice.getInterfaceCount()).thenReturn(4);
        when(usbDevice.getInterface(0)).thenReturn(rndisControlInterface);
        when(usbDevice.getInterface(1)).thenReturn(rndisDataInterface);
        when(usbDevice.getInterface(2)).thenReturn(controlInterface);
        when(usbDevice.getInterface(3)).thenReturn(dataInterface);
        when(rndisControlInterface.getInterfaceClass()).thenReturn(UsbConstants.USB_CLASS_WIRELESS_CONTROLLER);
        when(rndisControlInterface.getInterfaceSubclass()).thenReturn(1);
        when(rndisControlInterface.getInterfaceProtocol()).thenReturn(3);
        when(rndisDataInterface.getInterfaceClass()).thenReturn(UsbConstants.USB_CLASS_CDC_DATA);
        when(controlInterface.getInterfaceClass()).thenReturn(UsbConstants.USB_CLASS_COMM);
        when(controlInterface.getInterfaceSubclass()).thenReturn(USB_SUBCLASS_ACM);
        when(dataInterface.getInterfaceClass()).thenReturn(UsbConstants.USB_CLASS_CDC_DATA);

        when(controlInterface.getEndpointCount()).thenReturn(1);
        when(controlInterface.getEndpoint(0)).thenReturn(controlEndpoint);
        when(dataInterface.getEndpointCount()).thenReturn(2);
        when(dataInterface.getEndpoint(0)).thenReturn(writeEndpoint);
        when(dataInterface.getEndpoint(1)).thenReturn(readEndpoint);
        when(controlEndpoint.getDirection()).thenReturn(UsbConstants.USB_DIR_IN);
        when(controlEndpoint.getType()).thenReturn(UsbConstants.USB_ENDPOINT_XFER_INT);
        when(readEndpoint.getDirection()).thenReturn(UsbConstants.USB_DIR_IN);
        when(readEndpoint.getType()).thenReturn(UsbConstants.USB_ENDPOINT_XFER_BULK);
        when(writeEndpoint.getDirection()).thenReturn(UsbConstants.USB_DIR_OUT);
        when(writeEndpoint.getType()).thenReturn(UsbConstants.USB_ENDPOINT_XFER_BULK);

        CdcAcmSerialDriver driver = new CdcAcmSerialDriver(usbDevice);
        CdcAcmSerialDriver.CdcAcmSerialPort port = (CdcAcmSerialDriver.CdcAcmSerialPort) driver.getPorts().get(0);
        port.mConnection = usbDeviceConnection;
        port.openInt();
        assertEquals(readEndpoint, port.mReadEndpoint);
        assertEquals(writeEndpoint, port.mWriteEndpoint);
    }

    @Test
    public void invalidStandardDevice() throws Exception {
        UsbDeviceConnection usbDeviceConnection = mock(UsbDeviceConnection.class);
        UsbDevice usbDevice = mock(UsbDevice.class);
        UsbInterface controlInterface = mock(UsbInterface.class);
        UsbInterface dataInterface = mock(UsbInterface.class);
        UsbEndpoint controlEndpoint = mock(UsbEndpoint.class);
        UsbEndpoint readEndpoint = mock(UsbEndpoint.class);
        UsbEndpoint writeEndpoint = mock(UsbEndpoint.class);

        when(usbDeviceConnection.claimInterface(controlInterface,true)).thenReturn(true);
        when(usbDeviceConnection.claimInterface(dataInterface,true)).thenReturn(true);
        when(usbDevice.getInterfaceCount()).thenReturn(2);
        when(usbDevice.getInterface(0)).thenReturn(controlInterface);
        when(usbDevice.getInterface(1)).thenReturn(dataInterface);
        when(controlInterface.getInterfaceClass()).thenReturn(UsbConstants.USB_CLASS_COMM);
        when(controlInterface.getInterfaceSubclass()).thenReturn(USB_SUBCLASS_ACM);
        when(controlInterface.getEndpointCount()).thenReturn(1);
        when(controlInterface.getEndpoint(0)).thenReturn(controlEndpoint);
        when(dataInterface.getInterfaceClass()).thenReturn(UsbConstants.USB_CLASS_CDC_DATA);
        when(dataInterface.getEndpointCount()).thenReturn(2);
        when(dataInterface.getEndpoint(0)).thenReturn(writeEndpoint);
        when(dataInterface.getEndpoint(1)).thenReturn(readEndpoint);
        //when(controlEndpoint.getDirection()).thenReturn(UsbConstants.USB_DIR_IN);
        //when(controlEndpoint.getType()).thenReturn(UsbConstants.USB_ENDPOINT_XFER_INT);
        when(readEndpoint.getDirection()).thenReturn(UsbConstants.USB_DIR_IN);
        when(readEndpoint.getType()).thenReturn(UsbConstants.USB_ENDPOINT_XFER_BULK);
        when(writeEndpoint.getDirection()).thenReturn(UsbConstants.USB_DIR_OUT);
        when(writeEndpoint.getType()).thenReturn(UsbConstants.USB_ENDPOINT_XFER_BULK);

        CdcAcmSerialDriver driver = new CdcAcmSerialDriver(usbDevice);
        CdcAcmSerialDriver.CdcAcmSerialPort port = (CdcAcmSerialDriver.CdcAcmSerialPort) driver.getPorts().get(0);
        port.mConnection = usbDeviceConnection;
        assertThrows(IOException.class, port::openInt);
    }

    @Test
    public void invalidSingleInterfaceDevice() throws Exception {
        UsbDeviceConnection usbDeviceConnection = mock(UsbDeviceConnection.class);
        UsbDevice usbDevice = mock(UsbDevice.class);
        UsbInterface usbInterface = mock(UsbInterface.class);
        UsbEndpoint controlEndpoint = mock(UsbEndpoint.class);
        UsbEndpoint readEndpoint = mock(UsbEndpoint.class);
        //UsbEndpoint writeEndpoint = mock(UsbEndpoint.class);

        when(usbDeviceConnection.claimInterface(usbInterface,true)).thenReturn(true);
        when(usbDevice.getInterfaceCount()).thenReturn(1);
        when(usbDevice.getInterface(0)).thenReturn(usbInterface);
        when(usbInterface.getEndpointCount()).thenReturn(2);
        when(usbInterface.getEndpoint(0)).thenReturn(controlEndpoint);
        when(usbInterface.getEndpoint(1)).thenReturn(readEndpoint);
        //when(usbInterface.getEndpoint(2)).thenReturn(writeEndpoint);
        when(controlEndpoint.getDirection()).thenReturn(UsbConstants.USB_DIR_IN);
        when(controlEndpoint.getType()).thenReturn(UsbConstants.USB_ENDPOINT_XFER_INT);
        when(readEndpoint.getDirection()).thenReturn(UsbConstants.USB_DIR_IN);
        when(readEndpoint.getType()).thenReturn(UsbConstants.USB_ENDPOINT_XFER_BULK);
        //when(writeEndpoint.getDirection()).thenReturn(UsbConstants.USB_DIR_OUT);
        //when(writeEndpoint.getType()).thenReturn(UsbConstants.USB_ENDPOINT_XFER_BULK);

        CdcAcmSerialDriver driver = new CdcAcmSerialDriver(usbDevice);
        CdcAcmSerialDriver.CdcAcmSerialPort port = (CdcAcmSerialDriver.CdcAcmSerialPort) driver.getPorts().get(0);
        port.mConnection = usbDeviceConnection;
        port.openInt();
        assertNull(port.mWriteEndpoint);
    }

}
