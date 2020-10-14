/* Copyright 2011-2013 Google Inc.
 * Copyright 2013 mike wakerly <opensource@hoho.com>
 *
 * Project home page: https://github.com/mik3y/usb-serial-for-android
 */

package com.hoho.android.usbserial.driver;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;

import java.io.Closeable;
import java.io.IOException;
import java.util.EnumSet;

/**
 * Interface for a single serial port.
 *
 * @author mike wakerly (opensource@hoho.com)
 */
public interface UsbSerialPort extends Closeable {

    /** 5 data bits. */
    public static final int DATABITS_5 = 5;

    /** 6 data bits. */
    public static final int DATABITS_6 = 6;

    /** 7 data bits. */
    public static final int DATABITS_7 = 7;

    /** 8 data bits. */
    public static final int DATABITS_8 = 8;

    /** No flow control. */
    public static final int FLOWCONTROL_NONE = 0;

    /** RTS/CTS input flow control. */
    public static final int FLOWCONTROL_RTSCTS_IN = 1;

    /** RTS/CTS output flow control. */
    public static final int FLOWCONTROL_RTSCTS_OUT = 2;

    /** XON/XOFF input flow control. */
    public static final int FLOWCONTROL_XONXOFF_IN = 4;

    /** XON/XOFF output flow control. */
    public static final int FLOWCONTROL_XONXOFF_OUT = 8;

    /** No parity. */
    public static final int PARITY_NONE = 0;

    /** Odd parity. */
    public static final int PARITY_ODD = 1;

    /** Even parity. */
    public static final int PARITY_EVEN = 2;

    /** Mark parity. */
    public static final int PARITY_MARK = 3;

    /** Space parity. */
    public static final int PARITY_SPACE = 4;

    /** 1 stop bit. */
    public static final int STOPBITS_1 = 1;

    /** 1.5 stop bits. */
    public static final int STOPBITS_1_5 = 3;

    /** 2 stop bits. */
    public static final int STOPBITS_2 = 2;

    /** values for get[Supported]ControlLines() */
    public enum ControlLine { RTS, CTS,  DTR, DSR,  CD, RI };

    /**
     * Returns the driver used by this port.
     */
    public UsbSerialDriver getDriver();

    /**
     * Returns the currently-bound USB device.
     */
    public UsbDevice getDevice();

    /**
     * Port number within driver.
     */
    public int getPortNumber();
    
    /**
     * The serial number of the underlying UsbDeviceConnection, or {@code null}.
     *
     * @return value from {@link UsbDeviceConnection#getSerial()}
     * @throws SecurityException starting with target SDK 29 (Android 10) if permission for USB device is not granted
     */
    public String getSerial();

    /**
     * Opens and initializes the port. Upon success, caller must ensure that
     * {@link #close()} is eventually called.
     *
     * @param connection an open device connection, acquired with
     *                   {@link UsbManager#openDevice(android.hardware.usb.UsbDevice)}
     * @throws IOException on error opening or initializing the port.
     */
    public void open(UsbDeviceConnection connection) throws IOException;

    /**
     * Closes the port and {@link UsbDeviceConnection}
     *
     * @throws IOException on error closing the port.
     */
    public void close() throws IOException;

    /**
     * Reads as many bytes as possible into the destination buffer.
     *
     * @param dest the destination byte buffer
     * @param timeout the timeout for reading in milliseconds, 0 is infinite
     * @return the actual number of bytes read
     * @throws IOException if an error occurred during reading
     */
    public int read(final byte[] dest, final int timeout) throws IOException;

    /**
     * Writes as many bytes as possible from the source buffer.
     *
     * @param src the source byte buffer
     * @param timeout the timeout for writing in milliseconds, 0 is infinite
     * @return the actual number of bytes written
     * @throws IOException if an error occurred during writing
     */
    public int write(final byte[] src, final int timeout) throws IOException;

    /**
     * Sets various serial port parameters.
     *
     * @param baudRate baud rate as an integer, for example {@code 115200}.
     * @param dataBits one of {@link #DATABITS_5}, {@link #DATABITS_6},
     *                 {@link #DATABITS_7}, or {@link #DATABITS_8}.
     * @param stopBits one of {@link #STOPBITS_1}, {@link #STOPBITS_1_5}, or {@link #STOPBITS_2}.
     * @param parity one of {@link #PARITY_NONE}, {@link #PARITY_ODD},
     *               {@link #PARITY_EVEN}, {@link #PARITY_MARK}, or {@link #PARITY_SPACE}.
     * @throws IOException on error setting the port parameters
     * @throws UnsupportedOperationException if values are not supported by a specific device
     */
    public void setParameters(int baudRate, int dataBits, int stopBits, int parity) throws IOException;

    /**
     * Gets the CD (Carrier Detect) bit from the underlying UART.
     *
     * @return the current state
     * @throws IOException if an error occurred during reading
     * @throws UnsupportedOperationException if not supported
     */
    public boolean getCD() throws IOException;

    /**
     * Gets the CTS (Clear To Send) bit from the underlying UART.
     *
     * @return the current state
     * @throws IOException if an error occurred during reading
     * @throws UnsupportedOperationException if not supported
     */
    public boolean getCTS() throws IOException;

    /**
     * Gets the DSR (Data Set Ready) bit from the underlying UART.
     *
     * @return the current state
     * @throws IOException if an error occurred during reading
     * @throws UnsupportedOperationException if not supported
     */
    public boolean getDSR() throws IOException;

    /**
     * Gets the DTR (Data Terminal Ready) bit from the underlying UART.
     *
     * @return the current state
     * @throws IOException if an error occurred during reading
     * @throws UnsupportedOperationException if not supported
     */
    public boolean getDTR() throws IOException;

    /**
     * Sets the DTR (Data Terminal Ready) bit on the underlying UART, if supported.
     *
     * @param value the value to set
     * @throws IOException if an error occurred during writing
     * @throws UnsupportedOperationException if not supported
     */
    public void setDTR(boolean value) throws IOException;

    /**
     * Gets the RI (Ring Indicator) bit from the underlying UART.
     *
     * @return the current state
     * @throws IOException if an error occurred during reading
     * @throws UnsupportedOperationException if not supported
     */
    public boolean getRI() throws IOException;

    /**
     * Gets the RTS (Request To Send) bit from the underlying UART.
     *
     * @return the current state
     * @throws IOException if an error occurred during reading
     * @throws UnsupportedOperationException if not supported
     */
    public boolean getRTS() throws IOException;

    /**
     * Sets the RTS (Request To Send) bit on the underlying UART, if supported.
     *
     * @param value the value to set
     * @throws IOException if an error occurred during writing
     * @throws UnsupportedOperationException if not supported
     */
    public void setRTS(boolean value) throws IOException;

    /**
     * Gets all control line values from the underlying UART, if supported.
     * Requires less USB calls than calling getRTS() + ... + getRI() individually.
     *
     * @return EnumSet.contains(...) is {@code true} if set, else {@code false}
     * @throws IOException if an error occurred during reading
     */
    public EnumSet<ControlLine> getControlLines() throws IOException;

    /**
     * Gets all control line supported flags.
     *
     * @return EnumSet.contains(...) is {@code true} if supported, else {@code false}
     * @throws IOException if an error occurred during reading
     */
    public EnumSet<ControlLine> getSupportedControlLines() throws IOException;

    /**
     * Purge non-transmitted output data and / or non-read input data.
     *
     * @param purgeWriteBuffers {@code true} to discard non-transmitted output data
     * @param purgeReadBuffers {@code true} to discard non-read input data
     * @throws IOException if an error occurred during flush
     * @throws UnsupportedOperationException if not supported
     */
    public void purgeHwBuffers(boolean purgeWriteBuffers, boolean purgeReadBuffers) throws IOException;

    /**
     * send BREAK condition.
     *
     * @param value set/reset
     */
    public void setBreak(boolean value) throws IOException;

    /**
     * Returns the current state of the connection.
     */
    public boolean isOpen();

}
