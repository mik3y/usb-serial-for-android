package com.hoho.android.usbserial.driver;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class FtdiSerialDriverTest {

    private final UsbDevice usbDevice = mock(UsbDevice.class);
    private final UsbEndpoint readEndpoint = mock(UsbEndpoint.class);

    private void initBuf(byte[] buf) {
        for(int i=0; i<buf.length; i++)
            buf[i] = (byte) i;
    }
    private boolean testBuf(byte[] buf, int len) {
        byte j = 2;
        for(int i=0; i<len; i++) {
            if(buf[i]!=j)
                return false;
            j++;
            if(j % 64 == 0)
                j+=2;
        }
        return true;
    }

    @Test
    public void readFilter() throws Exception {
        byte[] buf = new byte[2048];
        int len;

        when(usbDevice.getInterfaceCount()).thenReturn(1);
        when(readEndpoint.getMaxPacketSize()).thenReturn(64);
        FtdiSerialDriver driver = new FtdiSerialDriver(usbDevice);
        FtdiSerialDriver.FtdiSerialPort port = (FtdiSerialDriver.FtdiSerialPort) driver.getPorts().get(0);
        port.mReadEndpoint = readEndpoint;

        len = port.readFilter(buf, 0);
        assertEquals(len, 0);

        assertThrows(IOException.class, () -> port.readFilter(buf, 1));

        initBuf(buf);
        len = port.readFilter(buf, 2);
        assertEquals(len, 0);

        initBuf(buf);
        len = port.readFilter(buf, 3);
        assertEquals(len, 1);
        assertTrue(testBuf(buf, len));

        initBuf(buf);
        len = port.readFilter(buf, 4);
        assertEquals(len, 2);
        assertTrue(testBuf(buf, len));

        initBuf(buf);
        len = port.readFilter(buf, 64);
        assertEquals(len, 62);
        assertTrue(testBuf(buf, len));

        assertThrows(IOException.class, () -> port.readFilter(buf, 65));

        initBuf(buf);
        len = port.readFilter(buf, 66);
        assertEquals(len, 62);
        assertTrue(testBuf(buf, len));

        initBuf(buf);
        len = port.readFilter(buf, 68);
        assertEquals(len, 64);
        assertTrue(testBuf(buf, len));

        initBuf(buf);
        len = port.readFilter(buf, 16*64+11);
        assertEquals(len, 16*62+9);
        assertTrue(testBuf(buf, len));
    }
}