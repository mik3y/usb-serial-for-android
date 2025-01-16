package com.hoho.android.usbserial.util;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.ByteBuffer;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbRequest;
import android.os.Process;

import com.hoho.android.usbserial.driver.CommonUsbSerialPort;

import org.junit.Test;
import org.mockito.Mock;

public class SerialInputOutputManagerTest {

    @Mock
    private UsbRequest mRequest;

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

        UsbRequest request = mock(UsbRequest.class);
        UsbEndpoint readEndpoint = mock(UsbEndpoint.class);
        UsbDeviceConnection connection = mock(UsbDeviceConnection.class);
        when(readEndpoint.getMaxPacketSize()).thenReturn(16);
        CommonUsbSerialPort port = mock(CommonUsbSerialPort.class);
        when(port.getReadEndpoint()).thenReturn(readEndpoint);
        when(port.getConnection()).thenReturn(connection);
        when(connection.requestWait()).thenReturn(request);
        when(request.getClientData()).thenReturn(ByteBuffer.wrap(new byte[16]).put((byte) 0x00));
        SerialInputOutputManager manager = new SerialInputOutputManager(port);
        manager.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
        manager.setRequestSupplier(() -> request);

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
