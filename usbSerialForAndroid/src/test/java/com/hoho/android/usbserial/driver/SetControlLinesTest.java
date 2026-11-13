package com.hoho.android.usbserial.driver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;

import com.hoho.android.usbserial.driver.UsbSerialPort.ControlLine;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

public class SetControlLinesTest {

    private static final List<EnumSet<ControlLine>> STATES = Arrays.asList(
            EnumSet.of(ControlLine.DTR), EnumSet.of(ControlLine.DTR, ControlLine.RTS),
            EnumSet.of(ControlLine.RTS), EnumSet.noneOf(ControlLine.class)
    );

    private static class Case {
        final CommonUsbSerialPort port;
        final UsbDeviceConnection connection = mock(UsbDeviceConnection.class);
        final int requestType;
        final int request;
        final int index;
        final int[] values;

        Case(CommonUsbSerialPort port, int requestType, int request, int index, int... values) {
            this.port = port;
            this.requestType = requestType;
            this.request = request;
            this.index = index;
            this.values = values;
            port.mConnection = connection;
        }
    }

    private Case[] cases() {
        UsbDevice device = mock(UsbDevice.class);
        when(device.getInterfaceCount()).thenReturn(1);
        when(device.getInterface(0)).thenReturn(mock(UsbInterface.class));
        return new Case[] {
                new Case((CommonUsbSerialPort) new Cp21xxSerialDriver(device).getPorts().get(0),
                        0x41, 0x07, 0, 0x301, 0x303, 0x302, 0x300),
                new Case((CommonUsbSerialPort) new CdcAcmSerialDriver(device).getPorts().get(0),
                        0x21, 0x22, 0, 1, 3, 2, 0),
                new Case((CommonUsbSerialPort) new FtdiSerialDriver(device).getPorts().get(0),
                        0x40, 1, 1, 0x301, 0x303, 0x302, 0x300),
                new Case((CommonUsbSerialPort) new Ch34xSerialDriver(device).getPorts().get(0),
                        0x40, 0xa4, 0, ~0x20, ~0x60, ~0x40, ~0),
                new Case((CommonUsbSerialPort) new ProlificSerialDriver(device).getPorts().get(0),
                        0x21, 0x22, 0, 1, 3, 2, 0)
        };
    }

    @Test
    public void allCombinationsUseOneTransferAndUpdateCache() throws Exception {
        for (Case c : cases()) {
            for (int i = 0; i < STATES.size(); i++) {
                c.port.setControlLines(STATES.get(i));
                verify(c.connection).controlTransfer(c.requestType, c.request, c.values[i],
                        c.index, null, 0, 5000);
                verifyNoMoreInteractions(c.connection);
                assertEquals(STATES.get(i).contains(ControlLine.DTR), c.port.getDTR());
                assertEquals(STATES.get(i).contains(ControlLine.RTS), c.port.getRTS());
                clearInvocations(c.connection);
                EnumSet<ControlLine> withInputs = EnumSet.copyOf(STATES.get(i));
                withInputs.addAll(EnumSet.of(ControlLine.CTS, ControlLine.DSR,
                        ControlLine.CD, ControlLine.RI));
                c.port.setControlLines(withInputs);
                verifyNoInteractions(c.connection);
            }
            c.port.setControlLines(STATES.get(STATES.size() - 1));
            verifyNoInteractions(c.connection);
        }
    }

    @Test
    public void nullAndFailedTransferDoNotChangeCache() throws Exception {
        for (Case c : cases()) {
            assertThrows(IllegalArgumentException.class, () -> c.port.setControlLines(null));
            c.port.setControlLines(EnumSet.of(ControlLine.CTS, ControlLine.DSR,
                    ControlLine.CD, ControlLine.RI));
            verifyNoInteractions(c.connection);
            when(c.connection.controlTransfer(anyInt(), anyInt(), anyInt(), anyInt(),
                    isNull(), anyInt(), anyInt())).thenReturn(-1);
            assertThrows(IOException.class,
                    () -> c.port.setControlLines(EnumSet.of(ControlLine.DTR, ControlLine.RTS)));
            assertFalse(c.port.getDTR());
            assertFalse(c.port.getRTS());
            clearInvocations(c.connection);
            when(c.connection.controlTransfer(anyInt(), anyInt(), anyInt(), anyInt(),
                    isNull(), anyInt(), anyInt())).thenReturn(0);
            c.port.setControlLines(EnumSet.of(ControlLine.DTR, ControlLine.RTS));
            assertTrue(c.port.getDTR());
            assertTrue(c.port.getRTS());
            verify(c.connection).controlTransfer(c.requestType, c.request, c.values[1],
                    c.index, null, 0, 5000);
            verifyNoMoreInteractions(c.connection);
        }
    }

    @Test
    public void portsWithoutOutputLinesRejectCombinedUpdate() {
        UsbDevice device = mock(UsbDevice.class);
        UsbSerialPort[] ports = {
                new GsmModemSerialDriver(device).getPorts().get(0),
                new ChromeCcdSerialDriver(device).getPorts().get(0)
        };
        for (UsbSerialPort port : ports) {
            assertThrows(UnsupportedOperationException.class,
                    () -> port.setControlLines(EnumSet.noneOf(ControlLine.class)));
            assertThrows(IllegalArgumentException.class, () -> port.setControlLines(null));
            assertThrows(UnsupportedOperationException.class,
                    () -> port.setControlLines(EnumSet.of(ControlLine.CTS)));
        }
    }
}
