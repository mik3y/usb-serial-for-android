package com.hoho.android.usbserial.stream;

import com.hoho.android.usbserial.driver.UsbSerialDriver;

import java.io.IOException;
import java.io.OutputStream;

/**
 * @author lucas@creativa77.com.ar (Lucas Chiesa)
 */
class UsbSerialOutputStream extends OutputStream {

    private final UsbSerialDriver driver;
    private final UsbSerialStreamFactory factory;

    UsbSerialOutputStream(UsbSerialDriver driver, UsbSerialStreamFactory factory) {
        this.driver = driver;
        this.factory = factory;
    }

    @Override
    public void close() throws IOException {
        driver.close();
    }

    @Override
    public void flush() throws IOException {
    }

    @Override
    public void write(byte[] buffer, int offset, int count) throws java.io.IOException  {
        if (offset < 0 || count < 0 || offset + count > buffer.length) {
            throw new IndexOutOfBoundsException();
        }

        byte[] local = new byte[count];
        System.arraycopy(buffer, offset, local, 0, count);
        this.driver.write(local, 1000);
     }

    @Override
    public void write(int oneByte) throws IOException {
        write(new byte[] { (byte) oneByte }, 0, 1);
    }
}
