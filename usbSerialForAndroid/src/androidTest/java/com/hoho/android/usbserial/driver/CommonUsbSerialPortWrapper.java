package com.hoho.android.usbserial.driver;

import android.hardware.usb.UsbRequest;

import com.hoho.android.usbserial.util.UsbUtils;

import java.util.LinkedList;

public class CommonUsbSerialPortWrapper {
    public static byte[] getWriteBuffer(UsbSerialPort serialPort) {
        CommonUsbSerialPort commonSerialPort = (CommonUsbSerialPort) serialPort;
        return commonSerialPort.mWriteBuffer;
    }

    public static LinkedList<UsbRequest> getReadQueueRequests(UsbSerialPort serialPort) {
        CommonUsbSerialPort commonSerialPort = (CommonUsbSerialPort) serialPort;
        return commonSerialPort.mReadQueueRequests;
    }

    public static void setReadQueueRequestSupplier(UsbSerialPort serialPort, UsbUtils.Supplier<UsbRequest> supplier) {
        CommonUsbSerialPort commonSerialPort = (CommonUsbSerialPort) serialPort;
        commonSerialPort.mUsbRequestSupplier = supplier;
    }
}
