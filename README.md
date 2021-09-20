[![Actions Status](https://github.com/mik3y/usb-serial-for-android/workflows/build/badge.svg)](https://github.com/mik3y/usb-serial-for-android/actions)
[![Jitpack](https://jitpack.io/v/mik3y/usb-serial-for-android.svg)](https://jitpack.io/#mik3y/usb-serial-for-android)
[![Codacy](https://app.codacy.com/project/badge/Grade/ef799bba8a7343818af0a90eba3ecb46)](https://www.codacy.com/gh/kai-morich/usb-serial-for-android-mik3y/dashboard?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=kai-morich/usb-serial-for-android-mik3y&amp;utm_campaign=Badge_Grade)
[![codecov](https://codecov.io/gh/mik3y/usb-serial-for-android/branch/master/graph/badge.svg)](https://codecov.io/gh/mik3y/usb-serial-for-android)

# usb-serial-for-android

This is a driver library for communication with Arduinos and other USB serial hardware on
Android, using the
[Android USB Host Mode (OTG)](http://developer.android.com/guide/topics/connectivity/usb/host.html)
available since Android 3.1 and working reliably since Android 4.2.

No root access, ADK, or special kernel drivers are required; all drivers are implemented in
Java.  You get a raw serial port with `read()`, `write()`, and [other functions](https://github.com/mik3y/usb-serial-for-android/wiki/FAQ#Feature_Matrix) for use with your own protocols.

## Quick Start

**1.** Add library to your project:

Add jitpack.io repository to your root build.gradle:
```gradle
allprojects {
    repositories {
        ...
        maven { url 'https://jitpack.io' }
    }
}
```
Add library to dependencies
```gradle
dependencies {
    implementation 'com.github.mik3y:usb-serial-for-android:3.4.3'
}
```

**2.** If the app should be notified when a device is attached, add 
[device_filter.xml](https://github.com/mik3y/usb-serial-for-android/blob/master/usbSerialExamples/src/main/res/xml/device_filter.xml) 
to your project's `res/xml/` directory and configure in your `AndroidManifest.xml`.

```xml
<activity
    android:name="..."
    ...>
    <intent-filter>
        <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
    </intent-filter>
    <meta-data
        android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
        android:resource="@xml/device_filter" />
</activity>
```

**3.** Use it! Example code snippet:

open device:
```java
    // Find all available drivers from attached devices.
    UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
    List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);
    if (availableDrivers.isEmpty()) {
        return;
    }

    // Open a connection to the first available driver.
    UsbSerialDriver driver = availableDrivers.get(0);
    UsbDeviceConnection connection = manager.openDevice(driver.getDevice());
    if (connection == null) {
        // add UsbManager.requestPermission(driver.getDevice(), ..) handling here
        return;
    }

    UsbSerialPort port = driver.getPorts().get(0); // Most devices have just one port (port 0)
    port.open(connection);
    port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
```
then use direct read/write
```java
    port.write(request, WRITE_WAIT_MILLIS);
    len = port.read(response, READ_WAIT_MILLIS);
```
or direct write + event driven read:
```java
    usbIoManager = new SerialInputOutputManager(usbSerialPort, this);
    usbIoManager.start();
    ...
    port.write("hello".getBytes(), WRITE_WAIT_MILLIS);
    
@Override
public void onNewData(byte[] data) {
    runOnUiThread(() -> { textView.append(new String(data)); });
}
```
and finally:
```java
    port.close();
```

For a simple example, see
[UsbSerialExamples](https://github.com/mik3y/usb-serial-for-android/blob/master/usbSerialExamples)
folder in this project.

For a more complete example with background service to stay connected while
the app is not visible or rotating, see separate github project 
[SimpleUsbTerminal](https://github.com/kai-morich/SimpleUsbTerminal).

## Probing for Unrecognized Devices

Sometimes you may need to do a little extra work to support devices which
usb-serial-for-android doesn't (yet) know about -- but which you know to be
compatible with one of the built-in drivers.  This may be the case for a brand
new device or for one using a custom VID/PID pair.

UsbSerialProber is a class to help you find and instantiate compatible
UsbSerialDrivers from the tree of connected UsbDevices.  Normally, you will use
the default prober returned by ``UsbSerialProber.getDefaultProber()``, which
uses the built-in list of well-known VIDs and PIDs that are supported by our
drivers.

To use your own set of rules, create and use a custom prober:

```java
// Probe for our custom CDC devices, which use VID 0x1234
// and PIDS 0x0001 and 0x0002.
ProbeTable customTable = new ProbeTable();
customTable.addProduct(0x1234, 0x0001, CdcAcmSerialDriver.class);
customTable.addProduct(0x1234, 0x0002, CdcAcmSerialDriver.class);

UsbSerialProber prober = new UsbSerialProber(customTable);
List<UsbSerialDriver> drivers = prober.findAllDrivers(usbManager);
// ...
```

Of course, nothing requires you to use UsbSerialProber at all: you can
instantiate driver classes directly if you know what you're doing; just supply
a compatible UsbDevice.

## Compatible Devices

This library supports USB to serial converter chips:
* FTDI FT232R, FT232H, FT2232H, FT4232H, FT230X, FT231X, FT234XD
* Prolific PL2303
* Silabs CP2102 and all other CP210x
* Qinheng CH340, CH341A

and devices implementing the CDC/ACM protocol like
* Arduino using ATmega32U4
* Digispark using V-USB software USB
* BBC micro:bit using ARM mbed DAPLink firmware
* ...

## Help & Discussion

For common problems, see the [FAQ](https://github.com/mik3y/usb-serial-for-android/wiki/FAQ) wiki page.

Are you using the library? Add your project to 
[ProjectsUsingUsbSerialForAndroid](https://github.com/mik3y/usb-serial-for-android/wiki/Projects-Using-usb-serial-for-android).
