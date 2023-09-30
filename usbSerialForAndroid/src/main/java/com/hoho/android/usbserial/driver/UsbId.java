/* Copyright 2011-2013 Google Inc.
 * Copyright 2013 mike wakerly <opensource@hoho.com>
 *
 * Project home page: https://github.com/mik3y/usb-serial-for-android
 */

package com.hoho.android.usbserial.driver;

/**
 * Registry of USB vendor/product ID constants.
 *
 * Culled from various sources; see
 * <a href="http://www.linux-usb.org/usb.ids">usb.ids</a> for one listing.
 *
 * @author mike wakerly (opensource@hoho.com)
 */
public final class UsbId {

    public static final int VENDOR_FTDI = 0x0403;
    public static final int FTDI_FT232R = 0x6001;
    public static final int FTDI_FT2232H = 0x6010;
    public static final int FTDI_FT4232H = 0x6011;
    public static final int FTDI_FT232H = 0x6014;
    public static final int FTDI_FT231X = 0x6015; // same ID for FT230X, FT231X, FT234XD

    public static final int VENDOR_SILABS = 0x10c4;
    public static final int SILABS_CP2102 = 0xea60; // same ID for CP2101, CP2103, CP2104, CP2109
    public static final int SILABS_CP2105 = 0xea70;
    public static final int SILABS_CP2108 = 0xea71;

    public static final int VENDOR_PROLIFIC = 0x067b;
    public static final int PROLIFIC_PL2303 = 0x2303;   // device type 01, T, HX
    public static final int PROLIFIC_PL2303GC = 0x23a3; // device type HXN
    public static final int PROLIFIC_PL2303GB = 0x23b3; // "
    public static final int PROLIFIC_PL2303GT = 0x23c3; // "
    public static final int PROLIFIC_PL2303GL = 0x23d3; // "
    public static final int PROLIFIC_PL2303GE = 0x23e3; // "
    public static final int PROLIFIC_PL2303GS = 0x23f3; // "

    public static final int VENDOR_GOOGLE = 0x18d1;
    public static final int GOOGLE_CR50 = 0x5014;

    public static final int VENDOR_QINHENG = 0x1a86;
    public static final int QINHENG_CH340 = 0x7523;
    public static final int QINHENG_CH341A = 0x5523;

    public static final int VENDOR_UNISOC = 0x1782;
    public static final int FIBOCOM_L610 = 0x4D10;
    public static final int FIBOCOM_L612 = 0x4D12;


    private UsbId() {
        throw new IllegalAccessError("Non-instantiable class");
    }

}
