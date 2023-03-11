/* Copyright 2011-2013 Google Inc.
 * Copyright 2013 mike wakerly <opensource@hoho.com>
 *
 * Project home page: https://github.com/mik3y/usb-serial-for-android
 */

package com.hoho.android.usbserial.driver;

import android.hardware.usb.UsbDevice;

import java.util.List;

public interface UsbSerialDriver {

    /*
     * Additional interface properties. Invoked thru reflection.
     *
        UsbSerialDriver(UsbDevice device);                  // constructor with device
        static Map<Integer, int[]> getSupportedDevices();
        static boolean probe(UsbDevice device);             // optional
     */


    /**
     * Returns the raw {@link UsbDevice} backing this port.
     *
     * @return the device
     */
    UsbDevice getDevice();

    /**
     * Returns all available ports for this device. This list must have at least
     * one entry.
     *
     * @return the ports
     */
    List<UsbSerialPort> getPorts();
}
