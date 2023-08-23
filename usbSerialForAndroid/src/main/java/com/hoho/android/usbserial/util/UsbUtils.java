package com.hoho.android.usbserial.util;

import android.hardware.usb.UsbDeviceConnection;

import java.util.ArrayList;

public class UsbUtils {

    public static  ArrayList<byte[]> getDescriptors(UsbDeviceConnection connection) {
        ArrayList<byte[]> descriptors = new ArrayList<>();
        byte[] rawDescriptors = connection.getRawDescriptors();
        if (rawDescriptors != null) {
            int pos = 0;
            while (pos < rawDescriptors.length) {
                int len = rawDescriptors[pos] & 0xFF;
                if (len == 0)
                    break;
                if (pos + len > rawDescriptors.length)
                    len = rawDescriptors.length - pos;
                byte[] descriptor = new byte[len];
                System.arraycopy(rawDescriptors, pos, descriptor, 0, len);
                descriptors.add(descriptor);
                pos += len;
            }
        }
        return descriptors;
    }


}
