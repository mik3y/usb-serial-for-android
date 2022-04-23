package com.hoho.android.usbserial.driver;

public class ProlificSerialPortWrapper {
    public static boolean isDeviceTypeT(UsbSerialPort serialPort) {
        ProlificSerialDriver.ProlificSerialPort prolificSerialPort = (ProlificSerialDriver.ProlificSerialPort) serialPort;
        return prolificSerialPort.mDeviceType == ProlificSerialDriver.DeviceType.DEVICE_TYPE_T;
    }

    public static boolean isDeviceTypeHxn(UsbSerialPort serialPort) {
        ProlificSerialDriver.ProlificSerialPort prolificSerialPort = (ProlificSerialDriver.ProlificSerialPort) serialPort;
        return prolificSerialPort.mDeviceType == ProlificSerialDriver.DeviceType.DEVICE_TYPE_HXN;
    }
}
