package com.hoho.android.usbserial.util;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.security.InvalidParameterException;

public class HexDumpText {

    @Test
    public void toByteArray() throws Exception {
        assertThat(HexDump.toByteArray((byte)0x4a), equalTo(new byte[]{ 0x4A}));
        assertThat(HexDump.toByteArray((short)0x4a5b), equalTo(new byte[]{ 0x4A, 0x5B}));
        assertThat(HexDump.toByteArray((int)0x4a5b6c7d), equalTo(new byte[]{ 0x4A, 0x5B, 0x6C, 0x7D}));
    }

    @Test
    public void toHexString() throws Exception {
        assertEquals("4A", HexDump.toHexString((byte)0x4a));
        assertEquals("4A 5B", HexDump.toHexString((short)0x4a5b));
        assertEquals("4A 5B 6C 7D", HexDump.toHexString((int)0x4a5b6c7d));
        assertEquals("4A 5B 6C 7D", HexDump.toHexString(new byte[]{ 0x4A, 0x5B, 0x6C, 0x7D}));
        assertEquals("5B 6C", HexDump.toHexString(new byte[]{ 0x4A, 0x5B, 0x6C, 0x7D}, 1, 2));
    }

    @Test
    public void dumpHexString() throws Exception {
        assertEquals("10 31 32 33 34 35 36 37 .1234567\n18 39                   .9", HexDump.dumpHexString(new byte[]{ 0x10, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x18, 0x39}));
        assertEquals("31 32                   12", HexDump.dumpHexString(new byte[]{ 0x30, 0x31, 0x32, 0x33}, 1, 2));
    }

    @Test
    public void toByte() throws Exception {
        assertThat(HexDump.hexStringToByteArray("4a 5B-6c\n7d"), equalTo(new byte[]{ 0x4A, 0x5B, 0x6C, 0x7D}));
        assertThrows(InvalidParameterException.class, () -> HexDump.hexStringToByteArray("3 "));
        assertThrows(InvalidParameterException.class, () -> HexDump.hexStringToByteArray("3z"));
        assertThrows(InvalidParameterException.class, () -> HexDump.hexStringToByteArray("3Z"));
    }

}
