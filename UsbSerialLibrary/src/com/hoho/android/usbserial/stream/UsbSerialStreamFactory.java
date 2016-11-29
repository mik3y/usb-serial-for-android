package com.hoho.android.usbserial.stream;

import com.hoho.android.usbserial.driver.UsbSerialDriver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * This helper class encapsulates a given <code>UsbSerialDriver</code> with implementations of
 * <code>java.io.InputStream</code> and <code>java.io.OutputStream</code>.
 *
 * @author jcerruti@creativa77.com.ar (Julian Cerruti)
 */
public class UsbSerialStreamFactory {
    private final UsbSerialDriver driver;

    private final int baudRate;
    private final int dataBits;
    private final int stopBits;
    private final int parity;

    public UsbSerialStreamFactory(UsbSerialDriver driver, int baudRate, int dataBits, int stopBits, int parity) throws IOException {
        this.driver = driver;
        this.baudRate = baudRate;
        this.dataBits = dataBits;
        this.stopBits = stopBits;
        this.parity = parity;

        driver.open();
        driver.setParameters(baudRate, dataBits, stopBits, parity);
    }

    /**
     * Uses the following default values to open the underlying serial connection:
     * - baud rante: 115200
     * - data bits: 8
     * - stop bits: 1
     * - parity: none
     */
    public UsbSerialStreamFactory(UsbSerialDriver driver) throws IOException {
        this(driver, 115200, UsbSerialDriver.DATABITS_8, UsbSerialDriver.STOPBITS_1, UsbSerialDriver.PARITY_NONE);
    }

    /**
     * Closes the underlying driver's serial connection
     */
    public void close() throws IOException {
        driver.close();
    }

    public InputStream getInputStream() {
        return new UsbSerialInputStream(this.driver, this);
    }
    public OutputStream getOutputStream() {
        return new UsbSerialOutputStream(this.driver, this);
    }

    public int getStopBits() {
        return stopBits;
    }

    public int getParity() {
        return parity;
    }

    public int getDataBits() {
        return dataBits;
    }

    public int getBaudRate() {
        return baudRate;
    }
}
