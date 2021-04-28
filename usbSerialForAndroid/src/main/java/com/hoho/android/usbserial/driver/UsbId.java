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

    public static final int VENDOR_ATMEL = 0x03EB;
    public static final int ATMEL_LUFA_CDC_DEMO_APP = 0x2044;

    public static final int VENDOR_ARDUINO = 0x2341;
    public static final int ARDUINO_UNO = 0x0001;
    public static final int ARDUINO_MEGA_2560 = 0x0010;
    public static final int ARDUINO_SERIAL_ADAPTER = 0x003b;
    public static final int ARDUINO_MEGA_ADK = 0x003f;
    public static final int ARDUINO_MEGA_2560_R3 = 0x0042;
    public static final int ARDUINO_UNO_R3 = 0x0043;
    public static final int ARDUINO_MEGA_ADK_R3 = 0x0044;
    public static final int ARDUINO_SERIAL_ADAPTER_R3 = 0x0044;
    public static final int ARDUINO_LEONARDO = 0x8036;
    public static final int ARDUINO_MICRO = 0x8037;
    public static final int ARDUINO_ZERO = 0x804d;
    public static final int ARDUINO_MKR_1000 = 0x804e;
    public static final int ARDUINO_MKR_ZERO = 0x804f;
    public static final int ARDUINO_MKR_FOX_1200 = 0x8050;
    public static final int ARDUINO_MKR_GSM_1400 = 0x8052;
    public static final int ARDUINO_MKR_WAN_1300 = 0x8053;
    public static final int ARDUINO_MKR_NB_1500 = 0x8055;
    public static final int ARDUINO_MKR_VIDOR_4000 = 0x8056;
    public static final int ARDUINO_MKR_WAN_1310 = 0x8059;
    public static final int ARDUINO_MKR_WIFI_1010 = 0x8054;
    public static final int ARDUINO_NANO_33_IOT = 0x8057;


    public static final int VENDOR_VAN_OOIJEN_TECH = 0x16c0;
    public static final int VAN_OOIJEN_TECH_TEENSYDUINO_SERIAL = 0x0483;

    public static final int VENDOR_LEAFLABS = 0x1eaf;
    public static final int LEAFLABS_MAPLE = 0x0004;

    public static final int VENDOR_SILABS = 0x10c4;
    public static final int SILABS_CP2102 = 0xea60; // same ID for CP2101, CP2103, CP2104, CP2109
    public static final int SILABS_CP2105 = 0xea70;
    public static final int SILABS_CP2108 = 0xea71;

    public static final int VENDOR_PROLIFIC = 0x067b;
    public static final int PROLIFIC_PL2303 = 0x2303;

    public static final int VENDOR_QINHENG = 0x1a86;
    public static final int QINHENG_CH340 = 0x7523;
    public static final int QINHENG_CH341A = 0x5523;

    // at www.linux-usb.org/usb.ids listed for NXP/LPC1768, but all processors supported by ARM mbed DAPLink firmware report these ids
    public static final int VENDOR_ARM = 0x0d28;
    public static final int ARM_MBED = 0x0204;

    private UsbId() {
        throw new IllegalAccessError("Non-instantiable class");
    }

}
