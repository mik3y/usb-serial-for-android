package com.hoho.android.usbserial.stream;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import java.io.IOException;
import java.io.InputStream;

/**
 * @author lucas@creativa77.com.ar (Lucas Chiesa)
 */
class UsbSerialInputStream extends InputStream{

    private final UsbSerialDriver device;
    private final UsbSerialStreamFactory factory;

    UsbSerialInputStream(UsbSerialDriver device, UsbSerialStreamFactory factory) {
        this.device = device;
        this.factory = factory;
    }

    @Override
    public void close() throws IOException {
        device.close();
    }

    @Override
    public int read() throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public int read(byte[] buffer) throws IOException {
        return read(buffer, 0, buffer.length);
    }

    @Override
    public int read(byte[] buffer, int offset, int count) throws IOException {
        byte[] local_buffer = new byte[count];
        if (offset < 0 || count < 0 || offset + count > buffer.length) {
            throw new IndexOutOfBoundsException();
        }
        int byteCount;
        byteCount = device.read(local_buffer, count);
        System.arraycopy(local_buffer, 0, buffer, offset, byteCount);
        return byteCount;
    }
}
