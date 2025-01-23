package com.hoho.android.usbserial.util;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.hardware.usb.UsbEndpoint;
import android.os.Process;

import com.hoho.android.usbserial.driver.CommonUsbSerialPort;

import org.junit.Test;

public class SerialInputOutputManagerTest {


    // catch all Throwables in onNewData() and onRunError()
    @Test
    public void throwable() throws Exception {

        class ExceptionListener implements SerialInputOutputManager.Listener {
            public Exception e;
            @Override public void onNewData(byte[] data) { throw new RuntimeException("exception1"); }
            @Override public void onRunError(Exception e) { this.e = e; throw new RuntimeException("exception2"); }
        }
        class ErrorListener implements SerialInputOutputManager.Listener {
            public Exception e;
            @Override public void onNewData(byte[] data) { throw new UnknownError("error1"); }
            @Override public void onRunError(Exception e) { this.e = e; throw new UnknownError("error2");}
        }

        UsbEndpoint readEndpoint = mock(UsbEndpoint.class);
        when(readEndpoint.getMaxPacketSize()).thenReturn(16);
        CommonUsbSerialPort port = mock(CommonUsbSerialPort.class);
        when(port.getReadEndpoint()).thenReturn(readEndpoint);
        when(port.read(new byte[16], 0)).thenReturn(1);
        SerialInputOutputManager manager = new SerialInputOutputManager(port);
        manager.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);

        ExceptionListener exceptionListener = new ExceptionListener();
        manager.setListener(exceptionListener);
        manager.runRead();
        assertEquals(RuntimeException.class, exceptionListener.e.getClass());
        assertEquals("exception1", exceptionListener.e.getMessage());

        ErrorListener errorListener = new ErrorListener();
        manager.setListener(errorListener);
        manager.runRead();
        assertEquals(Exception.class, errorListener.e.getClass());
        assertEquals("java.lang.UnknownError: error1", errorListener.e.getMessage());
        assertEquals(UnknownError.class, errorListener.e.getCause().getClass());
        assertEquals("error1", errorListener.e.getCause().getMessage());
    }
}
