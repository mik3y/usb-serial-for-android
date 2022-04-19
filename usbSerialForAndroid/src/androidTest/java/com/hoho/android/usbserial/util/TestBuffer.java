package com.hoho.android.usbserial.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class TestBuffer {
    public final byte[] buf;
    public int len;

    public TestBuffer(int length) {
        len = 0;
        buf = new byte[length];
        int i = 0;
        int j = 0;
        for (j = 0; j < length / 16; j++)
            for (int k = 0; k < 16; k++)
                buf[i++] = (byte) j;
        while (i < length)
            buf[i++] = (byte) j;
    }

    public boolean testRead(byte[] data) {
        assertNotEquals(0, data.length);
        assertTrue("got " + (len + data.length) + " bytes", (len + data.length) <= buf.length);
        for (int j = 0; j < data.length; j++)
            assertEquals("at pos " + (len + j), (byte) ((len + j) / 16), data[j]);
        len += data.length;
        //Log.d(TAG, "read " + len);
        return len == buf.length;
    }
}
