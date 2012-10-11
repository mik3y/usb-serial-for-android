/* Copyright 2011 Google Inc.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301,
 * USA.
 *
 * Project home page: http://code.google.com/p/usb-serial-for-android/
 */

package com.hoho.android.usbserial.driver;

import java.io.IOException;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;

/**
 * Driver interface for a supported USB serial device.
 *
 * @author mike wakerly (opensource@hoho.com)
 */
public abstract class UsbSerialDriver {

    protected final UsbDevice mDevice;
    protected final UsbDeviceConnection mConnection;

    public UsbSerialDriver(UsbDevice device, UsbDeviceConnection connection) {
        mDevice = device;
        mConnection = connection;
    }

    /**
     * Opens and initializes the device as a USB serial device. Upon success,
     * caller must ensure that {@link #close()} is eventually called.
     *
     * @throws IOException on error opening or initializing the device.
     */
    public abstract void open() throws IOException;

    /**
     * Closes the serial device.
     *
     * @throws IOException on error closing the device.
     */
    public abstract void close() throws IOException;

    /**
     * Reads as many bytes as possible into the destination buffer.
     *
     * @param dest the destination byte buffer
     * @param timeoutMillis the timeout for reading
     * @return the actual number of bytes read
     * @throws IOException if an error occurred during reading
     */
    public abstract int read(final byte[] dest, final int timeoutMillis) throws IOException;

    /**
     * Writes as many bytes as possible from the source buffer.
     *
     * @param src the source byte buffer
     * @param timeoutMillis the timeout for writing
     * @return the actual number of bytes written
     * @throws IOException if an error occurred during writing
     */
    public abstract int write(final byte[] src, final int timeoutMillis) throws IOException;

    /**
     * Sets the baud rate of the serial device.
     *
     * @param baudRate the desired baud rate, in bits per second
     * @return the actual rate set
     * @throws IOException on error setting the baud rate
     */
    public abstract int setBaudRate(final int baudRate) throws IOException;

    /**
     * Returns the currently-bound USB device.
     *
     * @return the device
     */
    public final UsbDevice getDevice() {
        return mDevice;
    }

}
