/* Copyright 2011-2013 Google Inc.
 * Copyright 2013 mike wakerly <opensource@hoho.com>
 *
 * Project home page: https://github.com/mik3y/usb-serial-for-android
 */

package com.hoho.android.usbserial.driver;

import android.hardware.usb.UsbDevice;

import java.util.List;

/**
 *
 * @author mike wakerly (opensource@hoho.com)
 */
public interface UsbSerialDriver {

    /**
     * Returns the raw {@link UsbDevice} backing this port.
     *
     * @return the device
     */
    public UsbDevice getDevice();

    /**
     * Returns all available ports for this device. This list must have at least
     * one entry.
     *
     * @return the ports
     */
    public List<UsbSerialPort> getPorts();
}
