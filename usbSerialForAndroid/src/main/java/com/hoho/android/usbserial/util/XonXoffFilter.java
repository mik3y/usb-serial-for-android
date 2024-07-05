package com.hoho.android.usbserial.util;

import com.hoho.android.usbserial.driver.UsbSerialPort;

import java.io.IOException;

/**
 * Some devices return XON and XOFF characters inline in read() data.
 * Other devices return XON / XOFF condition thru getXOFF() method.
 */

public class XonXoffFilter {
    private boolean xon = true;

    public XonXoffFilter() {
    }

    public boolean getXON()  {
        return xon;
    }

    /**
     * Filter XON/XOFF from read() data and remember
     *
     * @param data unfiltered data
     * @return filtered data
     */
    public byte[] filter(byte[] data) {
        int found = 0;
        for (int i=0; i<data.length; i++) {
            if (data[i] == UsbSerialPort.CHAR_XON || data[i] == UsbSerialPort.CHAR_XOFF)
                found++;
        }
        if(found == 0)
            return data;
        byte[] filtered = new byte[data.length - found];
        for (int i=0, j=0; i<data.length; i++) {
            if (data[i] == UsbSerialPort.CHAR_XON)
                xon = true;
            else if(data[i] == UsbSerialPort.CHAR_XOFF)
                xon = false;
            else
                filtered[j++] = data[i];
        }
        return filtered;
    }
}
