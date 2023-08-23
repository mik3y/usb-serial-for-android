package com.hoho.android.usbserial.driver;

import static com.hoho.android.usbserial.driver.CdcAcmSerialDriver.USB_SUBCLASS_ACM;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;

import com.hoho.android.usbserial.util.HexDump;

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

        /*
         * digispark - no IAD
         *   UsbInterface[mId=0,mAlternateSetting=0,mName=null,mClass=2,mSubclass=2,mProtocol=1,mEndpoints=[
         *     UsbEndpoint[mAddress=131,mAttributes=3,mMaxPacketSize=8,mInterval=255]]
         *   UsbInterface[mId=1,mAlternateSetting=0,mName=null,mClass=10,mSubclass=0,mProtocol=0,mEndpoints=[
         *     UsbEndpoint[mAddress=1,mAttributes=2,mMaxPacketSize=8,mInterval=0]
         *     UsbEndpoint[mAddress=129,mAttributes=2,mMaxPacketSize=8,mInterval=0]]
         */
        when(usbDeviceConnection.getRawDescriptors()).thenReturn(HexDump.hexStringToByteArray(
                "12 01 10 01 02 00 00 08 D0 16 7E 08 00 01 01 02 00 01\n" +
                "09 02 43 00 02 01 00 80 32\n" +
                "09 04 00 00 01 02 02 01 00\n" +
                "05 24 00 10 01\n" +
                "04 24 02 02\n" +
                "05 24 06 00 01\n" +
                "05 24 01 03 01\n" +
                "07 05 83 03 08 00 FF\n" +
                "09 04 01 00 02 0A 00 00 00\n" +
                "07 05 01 02 08 00 00\n" +
                "07 05 81 02 08 00 00"));
        when(usbDeviceConnection.claimInterface(controlInterface,true)).thenReturn(true);
        when(usbDeviceConnection.claimInterface(dataInterface,true)).thenReturn(true);
        when(usbDevice.getInterfaceCount()).thenReturn(2);
        when(usbDevice.getInterface(0)).thenReturn(controlInterface);
        when(usbDevice.getInterface(1)).thenReturn(dataInterface);
        when(controlInterface.getId()).thenReturn(0);
        when(controlInterface.getInterfaceClass()).thenReturn(UsbConstants.USB_CLASS_COMM);
        when(controlInterface.getInterfaceSubclass()).thenReturn(USB_SUBCLASS_ACM);
        when(controlInterface.getEndpointCount()).thenReturn(1);
        when(controlInterface.getEndpoint(0)).thenReturn(controlEndpoint);
        when(dataInterface.getId()).thenReturn(1);
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
        int n = 2;
        UsbDeviceConnection usbDeviceConnection = mock(UsbDeviceConnection.class);
        UsbDevice usbDevice = mock(UsbDevice.class);
        UsbInterface[] controlInterfaces = new UsbInterface[n];
        UsbInterface[] dataInterfaces = new UsbInterface[n];
        UsbEndpoint[] controlEndpoints = new UsbEndpoint[n];
        UsbEndpoint[] readEndpoints = new UsbEndpoint[n];
        UsbEndpoint[] writeEndpoints = new UsbEndpoint[n];

        /*
         * pi zero - dual port
         *   UsbInterface[mId=0,mAlternateSetting=0,mName=TinyUSB CDC,mClass=2,mSubclass=2,mProtocol=0,mEndpoints=[
         *     UsbEndpoint[mAddress=129,mAttributes=3,mMaxPacketSize=8,mInterval=16]]
         *   UsbInterface[mId=1,mAlternateSetting=0,mName=null,mClass=10,mSubclass=0,mProtocol=0,mEndpoints=[
         *     UsbEndpoint[mAddress=2,mAttributes=2,mMaxPacketSize=64,mInterval=0]
         *     UsbEndpoint[mAddress=130,mAttributes=2,mMaxPacketSize=64,mInterval=0]]
         *   UsbInterface[mId=2,mAlternateSetting=0,mName=TinyUSB CDC,mClass=2,mSubclass=2,mProtocol=0,mEndpoints=[
         *     UsbEndpoint[mAddress=131,mAttributes=3,mMaxPacketSize=8,mInterval=16]]
         *   UsbInterface[mId=3,mAlternateSetting=0,mName=null,mClass=10,mSubclass=0,mProtocol=0,mEndpoints=[
         *     UsbEndpoint[mAddress=4,mAttributes=2,mMaxPacketSize=64,mInterval=0]
         *      UsbEndpoint[mAddress=132,mAttributes=2,mMaxPacketSize=64,mInterval=0]]
         */
        when(usbDeviceConnection.getRawDescriptors()).thenReturn(HexDump.hexStringToByteArray(
                "12 01 00 02 EF 02 01 40 FE CA 02 40 00 01 01 02 03 01\n" +
                "09 02 8D 00 04 01 00 80 32\n" +
                "08 0B 00 02 02 02 00 00\n" +
                "09 04 00 00 01 02 02 00 04\n" +
                "05 24 00 20 01\n" +
                "05 24 01 00 01\n" +
                "04 24 02 02\n" +
                "05 24 06 00 01\n" +
                "07 05 81 03 08 00 10\n" +
                "09 04 01 00 02 0A 00 00 00\n" +
                "07 05 02 02 40 00 00\n" +
                "07 05 82 02 40 00 00\n" +
                "08 0B 02 02 02 02 00 00\n" +
                "09 04 02 00 01 02 02 00 04\n" +
                "05 24 00 20 01\n" +
                "05 24 01 00 03\n" +
                "04 24 02 02\n" +
                "05 24 06 02 03\n" +
                "07 05 83 03 08 00 10\n" +
                "09 04 03 00 02 0A 00 00 00\n" +
                "07 05 04 02 40 00 00\n" +
                "07 05 84 02 40 00 00\n"));
        when(usbDevice.getInterfaceCount()).thenReturn(2*n);
        for(int i=0; i<n; i++) {
            controlInterfaces[i] = mock(UsbInterface.class);
            dataInterfaces[i] = mock(UsbInterface.class);
            controlEndpoints[i] = mock(UsbEndpoint.class);
            readEndpoints[i] = mock(UsbEndpoint.class);
            writeEndpoints[i] = mock(UsbEndpoint.class);
            when(usbDeviceConnection.claimInterface(controlInterfaces[i], true)).thenReturn(true);
            when(usbDeviceConnection.claimInterface(dataInterfaces[i], true)).thenReturn(true);
            when(usbDevice.getInterface(2*i  )).thenReturn(controlInterfaces[i]);
            when(usbDevice.getInterface(2*i+1)).thenReturn(dataInterfaces[i]);
            when(controlInterfaces[i].getId()).thenReturn(2*i);
            when(controlInterfaces[i].getInterfaceClass()).thenReturn(UsbConstants.USB_CLASS_COMM);
            when(controlInterfaces[i].getInterfaceSubclass()).thenReturn(USB_SUBCLASS_ACM);
            when(controlInterfaces[i].getEndpointCount()).thenReturn(1);
            when(controlInterfaces[i].getEndpoint(0)).thenReturn(controlEndpoints[i]);
            when(dataInterfaces[i].getId()).thenReturn(2*i+1);
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
        int i = 1;
        CdcAcmSerialDriver driver = new CdcAcmSerialDriver(usbDevice);
        CdcAcmSerialDriver.CdcAcmSerialPort port = (CdcAcmSerialDriver.CdcAcmSerialPort) driver.getPorts().get(i);
        port.mConnection = usbDeviceConnection;
        // reset invocations from countPorts()
        clearInvocations(controlInterfaces[0]);
        clearInvocations(controlInterfaces[1]);

        port.openInt();
        assertEquals(readEndpoints[i], port.mReadEndpoint);
        assertEquals(writeEndpoints[i], port.mWriteEndpoint);
        verify(controlInterfaces[0], times(0)).getInterfaceClass(); // not openInterface with 'no IAD fallback'
        verify(controlInterfaces[1], times(2)).getInterfaceClass(); // openInterface with IAD
        port.closeInt();
        clearInvocations(controlInterfaces[0]);
        clearInvocations(controlInterfaces[1]);

        when(usbDeviceConnection.getRawDescriptors()).thenReturn(null);
        port.openInt();
        verify(controlInterfaces[0], times(2)).getInterfaceClass(); // openInterface with 'no IAD fallback'
        verify(controlInterfaces[1], times(2)).getInterfaceClass(); // openInterface with 'no IAD fallback'
        port.closeInt();
        clearInvocations(controlInterfaces[0]);
        clearInvocations(controlInterfaces[1]);

        when(usbDeviceConnection.getRawDescriptors()).thenReturn(HexDump.hexStringToByteArray("01 02 02 82 02")); // truncated descriptor
        port.openInt();
        verify(controlInterfaces[0], times(2)).getInterfaceClass(); // openInterface with 'no IAD fallback'
        verify(controlInterfaces[1], times(2)).getInterfaceClass(); // openInterface with 'no IAD fallback'
    }

    @Test
    public void compositeDevice() throws Exception {
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

        /*
         * BBC micro:bit
         *   UsbInterface[mId=0,mAlternateSetting=0,mName=USB_MSC,mClass=8,mSubclass=6,mProtocol=80,mEndpoints=[
         *     UsbEndpoint[mAddress=130,mAttributes=2,mMaxPacketSize=64,mInterval=0]
         *     UsbEndpoint[mAddress=2,mAttributes=2,mMaxPacketSize=64,mInterval=0]]
         *   UsbInterface[mId=1,mAlternateSetting=0,mName=mbed Serial Port,mClass=2,mSubclass=2,mProtocol=1,mEndpoints=[
         *     UsbEndpoint[mAddress=131,mAttributes=3,mMaxPacketSize=16,mInterval=32]]
         *   UsbInterface[mId=2,mAlternateSetting=0,mName=mbed Serial Port,mClass=10,mSubclass=0,mProtocol=0,mEndpoints=[
         *     UsbEndpoint[mAddress=4,mAttributes=2,mMaxPacketSize=64,mInterval=0]
         *     UsbEndpoint[mAddress=132,mAttributes=2,mMaxPacketSize=64,mInterval=0]]
         *   UsbInterface[mId=3,mAlternateSetting=0,mName=CMSIS-DAP,mClass=3,mSubclass=0,mProtocol=0,mEndpoints=[
         *     UsbEndpoint[mAddress=129,mAttributes=3,mMaxPacketSize=64,mInterval=1]
         *     UsbEndpoint[mAddress=1,mAttributes=3,mMaxPacketSize=64,mInterval=1]]
         *   UsbInterface[mId=4,mAlternateSetting=0,mName=WebUSB: CMSIS-DAP,mClass=255,mSubclass=3,mProtocol=0,mEndpoints=[]
         */
        when(usbDeviceConnection.getRawDescriptors()).thenReturn(HexDump.hexStringToByteArray(
                "12 01 10 02 EF 02 01 40 28 0D 04 02 00 10 01 02 03 01\n" +
                "09 02 8B 00 05 01 00 80 FA\n" +
                "09 04 00 00 02 08 06 50 08\n" +
                "07 05 82 02 40 00 00\n" +
                "07 05 02 02 40 00 00\n" +
                "08 0B 01 02 02 02 01 04\n" +
                "09 04 01 00 01 02 02 01 04\n" +
                "05 24 00 10 01\n" +
                "05 24 01 03 02\n" +
                "04 24 02 06\n" +
                "05 24 06 01 02\n" +
                "07 05 83 03 10 00 20\n" +
                "09 04 02 00 02 0A 00 00 05\n" +
                "07 05 04 02 40 00 00\n" +
                "07 05 84 02 40 00 00\n" +
                "09 04 03 00 02 03 00 00 06\n" +
                "09 21 00 01 00 01 22 21 00\n" +
                "07 05 81 03 40 00 01\n" +
                "07 05 01 03 40 00 01\n" +
                "09 04 04 00 00 FF 03 00 07"));
        when(usbDeviceConnection.claimInterface(controlInterface,true)).thenReturn(true);
        when(usbDeviceConnection.claimInterface(dataInterface,true)).thenReturn(true);
        when(usbDevice.getInterfaceCount()).thenReturn(5);
        when(usbDevice.getInterface(0)).thenReturn(massStorageInterface);
        when(usbDevice.getInterface(1)).thenReturn(controlInterface);
        when(usbDevice.getInterface(2)).thenReturn(dataInterface);
        when(usbDevice.getInterface(3)).thenReturn(hidInterface);
        when(usbDevice.getInterface(4)).thenReturn(vendorInterface);
        when(massStorageInterface.getId()).thenReturn(0);
        when(massStorageInterface.getInterfaceClass()).thenReturn(UsbConstants.USB_CLASS_MASS_STORAGE);
        when(controlInterface.getId()).thenReturn(1);
        when(controlInterface.getInterfaceClass()).thenReturn(UsbConstants.USB_CLASS_COMM);
        when(controlInterface.getInterfaceSubclass()).thenReturn(USB_SUBCLASS_ACM);
        when(dataInterface.getId()).thenReturn(2);
        when(dataInterface.getInterfaceClass()).thenReturn(UsbConstants.USB_CLASS_CDC_DATA);
        when(hidInterface.getId()).thenReturn(3);
        when(hidInterface.getInterfaceClass()).thenReturn(UsbConstants.USB_CLASS_HID);
        when(vendorInterface.getId()).thenReturn(4);
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

        ProbeTable probeTable = UsbSerialProber.getDefaultProbeTable();
        Class<? extends UsbSerialDriver> probeDriver = probeTable.findDriver(usbDevice);
        assertEquals(driver.getClass(), probeDriver);
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

        // has multiple USB_CLASS_CDC_DATA interfaces => get correct with IAD
        when(usbDeviceConnection.getRawDescriptors()).thenReturn(HexDump.hexStringToByteArray(
                "12 01 00 02 EF 02 01 40 FE CA 02 40 00 01 01 02 03 01\n" +
                "09 02 8D 00 04 01 00 80 32\n" +
                "08 0B 00 02 E0 01 03 00\n" +
                "09 04 00 00 01 E0 01 03 04\n" +
                "05 24 00 10 01\n" +
                "05 24 01 00 01\n" +
                "04 24 02 00\n" +
                "05 24 06 00 01\n" +
                "07 05 81 03 08 00 01\n" +
                "09 04 01 00 02 0A 00 00 00\n" +
                "07 05 82 02 40 00 00\n" +
                "07 05 02 02 40 00 00\n" +
                "08 0B 02 02 02 02 00 00\n" +
                "09 04 02 00 01 02 02 00 04\n" +
                "05 24 00 20 01\n" +
                "05 24 01 00 03\n" +
                "04 24 02 02\n" +
                "05 24 06 02 03\n" +
                "07 05 83 03 08 00 10\n" +
                "09 04 03 00 02 0A 00 00 00\n" +
                "07 05 04 02 40 00 00\n" +
                "07 05 84 02 40 00 00"));
        when(usbDeviceConnection.claimInterface(controlInterface,true)).thenReturn(true);
        when(usbDeviceConnection.claimInterface(dataInterface,true)).thenReturn(true);
        when(usbDevice.getInterfaceCount()).thenReturn(4);
        when(usbDevice.getInterface(0)).thenReturn(rndisControlInterface);
        when(usbDevice.getInterface(1)).thenReturn(rndisDataInterface);
        when(usbDevice.getInterface(2)).thenReturn(controlInterface);
        when(usbDevice.getInterface(3)).thenReturn(dataInterface);
        when(rndisControlInterface.getId()).thenReturn(0);
        when(rndisControlInterface.getInterfaceClass()).thenReturn(UsbConstants.USB_CLASS_WIRELESS_CONTROLLER);
        when(rndisControlInterface.getInterfaceSubclass()).thenReturn(1);
        when(rndisControlInterface.getInterfaceProtocol()).thenReturn(3);
        when(rndisDataInterface.getId()).thenReturn(1);
        when(rndisDataInterface.getInterfaceClass()).thenReturn(UsbConstants.USB_CLASS_CDC_DATA);
        when(controlInterface.getId()).thenReturn(2);
        when(controlInterface.getInterfaceClass()).thenReturn(UsbConstants.USB_CLASS_COMM);
        when(controlInterface.getInterfaceSubclass()).thenReturn(USB_SUBCLASS_ACM);
        when(dataInterface.getId()).thenReturn(3);
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
    public void compositeAlternateSettingDevice() throws Exception {
        UsbDeviceConnection usbDeviceConnection = mock(UsbDeviceConnection.class);
        UsbDevice usbDevice = mock(UsbDevice.class);
        UsbInterface ethernetControlInterface = mock(UsbInterface.class);
        UsbInterface ethernetDummyInterface = mock(UsbInterface.class);
        UsbInterface ethernetDataInterface = mock(UsbInterface.class);
        UsbInterface controlInterface = mock(UsbInterface.class);
        UsbInterface dataInterface = mock(UsbInterface.class);
        UsbEndpoint controlEndpoint = mock(UsbEndpoint.class);
        UsbEndpoint readEndpoint = mock(UsbEndpoint.class);
        UsbEndpoint writeEndpoint = mock(UsbEndpoint.class);

        // has multiple USB_CLASS_CDC_DATA interfaces => get correct with IAD
        when(usbDeviceConnection.getRawDescriptors()).thenReturn(HexDump.hexStringToByteArray(
                "12 01 00 02 EF 02 01 40 FE CA 02 40 00 01 01 02 03 01\n" +
                "09 02 9A 00 04 01 00 80 32\n" +
                "08 0B 00 02 02 06 00 00\n" +
                "09 04 00 00 01 02 06 00 04\n" +
                "05 24 00 20 01\n" +
                "05 24 06 00 01\n" +
                "0D 24 0F 04 00 00 00 00 DC 05 00 00 00\n" +
                "07 05 81 03 08 00 01\n" +
                "09 04 01 00 00 0A 00 00 00\n" +
                "09 04 01 01 02 0A 00 00 00\n" +
                "07 05 82 02 40 00 00\n" +
                "07 05 02 02 40 00 00\n" +
                "08 0B 02 02 02 02 00 00\n" +
                "09 04 02 00 01 02 02 00 04\n" +
                "05 24 00 20 01\n" +
                "05 24 01 00 03\n" +
                "04 24 02 02\n" +
                "05 24 06 02 03\n" +
                "07 05 83 03 08 00 10\n" +
                "09 04 03 00 02 0A 00 00 00\n" +
                "07 05 04 02 40 00 00\n" +
                "07 05 84 02 40 00 00"));
        when(usbDeviceConnection.claimInterface(controlInterface,true)).thenReturn(true);
        when(usbDeviceConnection.claimInterface(dataInterface,true)).thenReturn(true);
        when(usbDevice.getInterfaceCount()).thenReturn(5);
        when(usbDevice.getInterface(0)).thenReturn(ethernetControlInterface);
        when(usbDevice.getInterface(1)).thenReturn(ethernetDummyInterface);
        when(usbDevice.getInterface(2)).thenReturn(ethernetDataInterface);
        when(usbDevice.getInterface(3)).thenReturn(controlInterface);
        when(usbDevice.getInterface(4)).thenReturn(dataInterface);
        when(ethernetControlInterface.getId()).thenReturn(0);
        when(ethernetControlInterface.getInterfaceClass()).thenReturn(UsbConstants.USB_CLASS_COMM);
        when(ethernetControlInterface.getInterfaceSubclass()).thenReturn(6);
        when(ethernetDummyInterface.getId()).thenReturn(1);
        when(ethernetDummyInterface.getAlternateSetting()).thenReturn(0);
        when(ethernetDummyInterface.getInterfaceClass()).thenReturn(UsbConstants.USB_CLASS_CDC_DATA);
        when(ethernetDataInterface.getId()).thenReturn(1);
        when(ethernetDataInterface.getAlternateSetting()).thenReturn(1);
        when(ethernetDataInterface.getInterfaceClass()).thenReturn(UsbConstants.USB_CLASS_CDC_DATA);
        when(controlInterface.getId()).thenReturn(2);
        when(controlInterface.getInterfaceClass()).thenReturn(UsbConstants.USB_CLASS_COMM);
        when(controlInterface.getInterfaceSubclass()).thenReturn(USB_SUBCLASS_ACM);
        when(dataInterface.getId()).thenReturn(3);
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
