package com.hoho.android.usbserial.driver;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class Ch348SerialDriverTest {

    private static byte[] bytes(int... values) {
        byte[] b = new byte[values.length];
        for (int i = 0; i < values.length; i++)
            b[i] = (byte) values[i];
        return b;
    }

    @Test
    public void regBase() {
        int[] expected = {0x00, 0x10, 0x20, 0x30, 0x08, 0x18, 0x28, 0x38};
        for (int port = 0; port < expected.length; port++)
            assertEquals(expected[port], Ch348SerialDriver.regBase(port));
    }

    @Test
    public void buildTxPacket() {
        byte[] src = new byte[400];
        for (int i = 0; i < src.length; i++)
            src[i] = (byte) i;
        byte[] packet = Ch348SerialDriver.buildTxPacket(5, src, 10, 300);
        assertEquals(303, packet.length);
        assertArrayEquals(bytes(5, 0x2c, 0x01), Arrays.copyOf(packet, 3));
        assertArrayEquals(Arrays.copyOfRange(src, 10, 310), Arrays.copyOfRange(packet, 3, 303));
    }

    @Test
    public void parseRxPacket() {
        List<String> result = new ArrayList<>();
        Ch348SerialDriver.RxListener listener = (port, data, offset, length) ->
                result.add(port + ":" + Arrays.toString(Arrays.copyOfRange(data, offset, offset + length)));

        byte[] buf = new byte[96];
        Arrays.fill(buf, (byte) 0x55); // padding garbage must not be interpreted
        buf[0] = 1; buf[1] = 2; buf[2] = 10; buf[3] = 11;
        buf[32] = 7; buf[33] = 0;
        buf[64] = 3; buf[65] = 30;
        Ch348SerialDriver.parseRxPacket(buf, buf.length, listener);
        assertEquals(2, result.size());
        assertEquals("1:[10, 11]", result.get(0));
        assertEquals(3 + ":" + Arrays.toString(Arrays.copyOfRange(buf, 66, 96)), result.get(1));

        result.clear(); // invalid port
        Ch348SerialDriver.parseRxPacket(bytes(8, 1, 0), 3, listener);
        assertEquals(0, result.size());

        result.clear(); // invalid length
        Ch348SerialDriver.parseRxPacket(bytes(0, 31, 0), 3, listener);
        assertEquals(0, result.size());

        result.clear(); // truncated
        Ch348SerialDriver.parseRxPacket(bytes(2, 5, 1, 2), 4, listener);
        assertEquals("2:[1, 2]", result.get(0));
    }

    @Test
    public void parseStatus() {
        List<String> result = new ArrayList<>();
        Ch348SerialDriver.StatusListener listener = new Ch348SerialDriver.StatusListener() {
            @Override
            public void onTxEmpty(int port) { result.add("tx" + port); }
            @Override
            public void onModemStatus(int port, int changedMask, int values) {
                result.add("ms" + port + "/" + Integer.toHexString(changedMask) + "/" + Integer.toHexString(values));
            }
        };

        // modem status notification, as seen after 'c0 01 0f'
        Ch348SerialDriver.parseStatus(bytes(0x80, 0x00, 0x0e), 3, listener);
        assertEquals(Arrays.asList("ms0/e/0"), result);

        // VEN_R response for port 5, R_INIT echo skipped, tx empty for port 3
        result.clear();
        byte[] buf = bytes(
                0x95, 0x85, 0x18 | 0x06, 0x30,
                0x92, 0xa1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0x03, 0x02, 0x00);
        Ch348SerialDriver.parseStatus(buf, buf.length, listener);
        assertEquals(Arrays.asList("ms5/f/3", "tx3"), result);

        // unknown register stops parsing
        result.clear();
        Ch348SerialDriver.parseStatus(bytes(0x00, 0x0f, 0x00, 0x03, 0x02, 0x00), 6, listener);
        assertEquals(0, result.size());
    }

    @Test
    public void calOutData() {
        byte[] plain = bytes(0x00, 0x00, 0x25, 0x80, 0x00, 0x00, 0x08, 0x10);
        assertArrayEquals(plain, Ch348SerialDriver.calOutData(plain, 0, 0));
        assertArrayEquals(bytes(0x00, 0x25, 0x80, 0x00, 0x00, 0x08, 0x10, 0x00),
                Ch348SerialDriver.calOutData(plain, 8, 0));
        assertArrayEquals(bytes(0x00, 0x00, 0x4b, 0x00, 0x00, 0x00, 0x10, 0x20),
                Ch348SerialDriver.calOutData(plain, 1, 0));
        assertArrayEquals(bytes(0xff, 0xda, 0x7f, 0xff, 0xff, 0xf7, 0xef, 0xff),
                Ch348SerialDriver.calOutData(plain, 8, 0xff));
        byte[] msb = bytes(0x80, 0, 0, 0, 0, 0, 0, 0);
        assertArrayEquals(bytes(0, 0, 0, 0, 0, 0, 0, 0x01), Ch348SerialDriver.calOutData(msb, 1, 0));
    }

    @Test
    public void calRecvTmt() {
        assertEquals(16, Ch348SerialDriver.calRecvTmt(9600));
        assertEquals(2, Ch348SerialDriver.calRecvTmt(115200));
        assertEquals(5, Ch348SerialDriver.calRecvTmt(921600));
        assertEquals(5, Ch348SerialDriver.calRecvTmt(6000000));
    }
}
