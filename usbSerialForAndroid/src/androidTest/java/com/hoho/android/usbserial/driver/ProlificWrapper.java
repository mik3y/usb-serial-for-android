package com.hoho.android.usbserial.driver;

public class ProlificWrapper {
    public static boolean isDeviceTypeT(UsbSerialPort serialPort) {
        ProlificSerialDriver.ProlificSerialPort prolificSerialPort = (ProlificSerialDriver.ProlificSerialPort) serialPort;
        return prolificSerialPort.mDeviceType == ProlificSerialDriver.DeviceType.DEVICE_TYPE_T;
    }
}
