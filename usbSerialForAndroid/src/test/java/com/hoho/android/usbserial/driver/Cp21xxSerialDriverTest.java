package com.hoho.android.usbserial.driver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;

import org.junit.Before;
import org.junit.Test;
import org.mockito.stubbing.Answer;

import java.io.IOException;

public class Cp21xxSerialDriverTest {

    private UsbDevice usbDevice;
    private UsbDeviceConnection usbConnection;
    private UsbInterface usbInterface;
    private UsbEndpoint readEndpoint;
    private UsbEndpoint writeEndpoint;

    @Before
    public void setUp() {
        usbDevice = mock(UsbDevice.class);
        usbConnection = mock(UsbDeviceConnection.class);
        usbInterface = mock(UsbInterface.class);
        readEndpoint = mock(UsbEndpoint.class);
        writeEndpoint = mock(UsbEndpoint.class);

        when(usbDevice.getInterfaceCount()).thenReturn(1);
        when(usbDevice.getInterface(0)).thenReturn(usbInterface);
        when(usbInterface.getEndpointCount()).thenReturn(2);
        when(usbInterface.getEndpoint(0)).thenReturn(readEndpoint);
        when(usbInterface.getEndpoint(1)).thenReturn(writeEndpoint);

        when(readEndpoint.getType()).thenReturn(UsbConstants.USB_ENDPOINT_XFER_BULK);
        when(readEndpoint.getDirection()).thenReturn(UsbConstants.USB_DIR_IN);
        when(writeEndpoint.getType()).thenReturn(UsbConstants.USB_ENDPOINT_XFER_BULK);
        when(writeEndpoint.getDirection()).thenReturn(UsbConstants.USB_DIR_OUT);

        when(usbConnection.claimInterface(any(UsbInterface.class), eq(true))).thenReturn(true);
    }

    private void mockDeviceType(final byte partNum, final byte[] fwVerBytes) {
        // Mock part number query
        when(usbConnection.controlTransfer(
                eq(0xc0), eq(0xFF), eq(0x370B), eq(0), any(byte[].class), anyInt(), anyInt()
        )).thenAnswer((Answer<Integer>) invocation -> {
            byte[] buf = invocation.getArgument(4);
            int len = invocation.getArgument(5);
            if (len >= 1) {
                buf[0] = partNum;
                return 1;
            }
            return -1;
        });

        // Mock firmware version query (either 0x000E or 0x0010)
        if (fwVerBytes != null) {
            when(usbConnection.controlTransfer(
                    eq(0xc0), eq(0xFF), anyInt(), eq(0), any(byte[].class), anyInt(), anyInt()
            )).thenAnswer((Answer<Integer>) invocation -> {
                int reqVal = invocation.getArgument(2);
                if (reqVal == 0x000E || reqVal == 0x0010) {
                    byte[] buf = invocation.getArgument(4);
                    System.arraycopy(fwVerBytes, 0, buf, 0, Math.min(len(fwVerBytes), buf.length));
                    return 3;
                }
                return -1;
            });
        }
    }

    private int len(byte[] arr) {
        return arr == null ? 0 : arr.length;
    }

    @Test
    public void openAndClosePurge() throws Exception {
        mockDeviceType((byte) 0x02, null); // CP2102

        Cp21xxSerialDriver driver = new Cp21xxSerialDriver(usbDevice);
        Cp21xxSerialPort port = (Cp21xxSerialPort) driver.getPorts().get(0);
        port.mConnection = usbConnection;

        // Stub GET_FLOW (0x14) for initial setFlowControl call during open
        when(usbConnection.controlTransfer(
                eq(0xc1), eq(0x14), eq(0), eq(0), any(byte[].class), eq(16), anyInt()
        )).thenAnswer((Answer<Integer>) invocation -> {
            byte[] buf = invocation.getArgument(4);
            return buf.length;
        });

        port.openInt();

        // Verify claim and initial requests
        verify(usbConnection, times(1)).claimInterface(usbInterface, true);
        
        port.closeInt();

        // Verify CP210X_PURGE (0x12) with PURGE_ALL (0x0F) was called on close
        verify(usbConnection, times(1)).controlTransfer(
                eq(0x41), eq(0x12), eq(0x0F), eq(0), any(), eq(0), anyInt()
        );
    }

    @Test
    public void baudRateValidation() throws Exception {
        // Test CP2101 Max 921,600
        mockDeviceType((byte) 0x01, null);
        Cp21xxSerialDriver driver1 = new Cp21xxSerialDriver(usbDevice);
        Cp21xxSerialPort port1 = (Cp21xxSerialPort) driver1.getPorts().get(0);
        port1.mConnection = usbConnection;
        port1.openInt();
        port1.setParameters(921600, 8, 1, UsbSerialPort.PARITY_NONE); // should pass
        assertThrows(IllegalArgumentException.class, () -> port1.setParameters(1000000, 8, 1, UsbSerialPort.PARITY_NONE));

        // Test CP2102 Max 1,000,000
        mockDeviceType((byte) 0x02, null);
        Cp21xxSerialDriver driver2 = new Cp21xxSerialDriver(usbDevice);
        Cp21xxSerialPort port2 = (Cp21xxSerialPort) driver2.getPorts().get(0);
        port2.mConnection = usbConnection;
        port2.openInt();
        port2.setParameters(1000000, 8, 1, UsbSerialPort.PARITY_NONE); // should pass
        assertThrows(IllegalArgumentException.class, () -> port2.setParameters(1152000, 8, 1, UsbSerialPort.PARITY_NONE));

        // Test CP2102N Max 3,000,000
        mockDeviceType((byte) 0x20, new byte[]{1, 0, 5}); // CP2102N, v1.0.5
        Cp21xxSerialDriver driver3 = new Cp21xxSerialDriver(usbDevice);
        Cp21xxSerialPort port3 = (Cp21xxSerialPort) driver3.getPorts().get(0);
        port3.mConnection = usbConnection;
        port3.openInt();
        port3.setParameters(3000000, 8, 1, UsbSerialPort.PARITY_NONE); // should pass
        assertThrows(IllegalArgumentException.class, () -> port3.setParameters(4000000, 8, 1, UsbSerialPort.PARITY_NONE));

        // Test CP2105 restricted port (port number 1, 2 interfaces)
        UsbDevice usbDeviceRestricted = mock(UsbDevice.class);
        when(usbDeviceRestricted.getInterfaceCount()).thenReturn(2);
        when(usbDeviceRestricted.getInterface(0)).thenReturn(usbInterface);
        when(usbDeviceRestricted.getInterface(1)).thenReturn(usbInterface);

        mockDeviceType((byte) 0x05, new byte[]{1, 0, 0});
        Cp21xxSerialDriver driver4 = new Cp21xxSerialDriver(usbDeviceRestricted);
        Cp21xxSerialPort port4 = (Cp21xxSerialPort) driver4.getPorts().get(1); // restricted port (SCI)
        port4.mConnection = usbConnection;
        port4.openInt();

        port4.setParameters(2400, 8, 1, UsbSerialPort.PARITY_NONE); // should pass
        assertThrows(IllegalArgumentException.class, () -> port4.setParameters(300, 8, 1, UsbSerialPort.PARITY_NONE)); // min 2400
        assertThrows(IllegalArgumentException.class, () -> port4.setParameters(1000000, 8, 1, UsbSerialPort.PARITY_NONE)); // max 921600
    }

    @Test
    public void erratumE104Enforcement() throws Exception {
        // CP2102N with firmware version 1.0.4 (<= 1.0.4)
        mockDeviceType((byte) 0x20, new byte[]{1, 0, 4});

        Cp21xxSerialDriver driver = new Cp21xxSerialDriver(usbDevice);
        Cp21xxSerialPort port = (Cp21xxSerialPort) driver.getPorts().get(0);
        port.mConnection = usbConnection;
        port.openInt();

        // Flow control should fail
        assertThrows(UnsupportedOperationException.class, () -> port.setFlowControl(UsbSerialPort.FlowControl.RTS_CTS));
        assertThrows(UnsupportedOperationException.class, () -> port.setFlowControl(UsbSerialPort.FlowControl.DTR_DSR));
        assertThrows(UnsupportedOperationException.class, () -> port.setFlowControl(UsbSerialPort.FlowControl.XON_XOFF));

        // FlowControl.NONE should pass
        port.setFlowControl(UsbSerialPort.FlowControl.NONE);
    }

    @Test
    public void flowControlReadModifyWrite() throws Exception {
        mockDeviceType((byte) 0x02, null); // CP2102

        Cp21xxSerialDriver driver = new Cp21xxSerialDriver(usbDevice);
        Cp21xxSerialPort port = (Cp21xxSerialPort) driver.getPorts().get(0);
        port.mConnection = usbConnection;

        // Stub GET_FLOW (0x14) to return a specific configuration pattern
        final byte[] mockFlowConfig = new byte[16];
        mockFlowConfig[0] = 0x00; // no CTS/DTR handshake
        mockFlowConfig[4] = 0x04; // error char replacement enabled
        mockFlowConfig[5] = 0x11; // custom flags

        when(usbConnection.controlTransfer(
                eq(0xc1), eq(0x14), eq(0), eq(0), any(byte[].class), eq(16), anyInt()
        )).thenAnswer((Answer<Integer>) invocation -> {
            byte[] buf = invocation.getArgument(4);
            System.arraycopy(mockFlowConfig, 0, buf, 0, 16);
            return 16;
        });

        // Capture what is written back on SET_FLOW (0x13)
        final byte[][] writtenData = new byte[1][];
        when(usbConnection.controlTransfer(
                eq(0x41), eq(0x13), eq(0), eq(0), any(byte[].class), eq(16), anyInt()
        )).thenAnswer((Answer<Integer>) invocation -> {
            byte[] buf = invocation.getArgument(4);
            writtenData[0] = buf.clone();
            return 16;
        });

        port.openInt();
        port.setFlowControl(UsbSerialPort.FlowControl.RTS_CTS);

        // Verify we read and modified rather than zero-wrote:
        // Byte 4 should have 0x84 (0x80 RTS_FLOW_CTL | 0x04 preserved error char flag)
        // Byte 5 should still have 0x11 preserved
        // Byte 0 should have 0x08 (CTS handshake)
        assertEquals((byte) 0x08, writtenData[0][0]);
        assertEquals((byte) 0x84, writtenData[0][4]);
        assertEquals((byte) 0x11, writtenData[0][5]);
    }
}
