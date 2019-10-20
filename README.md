[![Jitpack](https://jitpack.io/v/mik3y/usb-serial-for-android.svg)](https://jitpack.io/#mik3y/usb-serial-for-android)
[![Codacy](https://api.codacy.com/project/badge/Grade/4d528e82e35d42d49f659e9b93a9c77d)](https://www.codacy.com/manual/kai-morich/usb-serial-for-android-mik3y?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=mik3y/usb-serial-for-android&amp;utm_campaign=Badge_Grade)
[![codecov](https://codecov.io/gh/mik3y/usb-serial-for-android/branch/master/graph/badge.svg)](https://codecov.io/gh/mik3y/usb-serial-for-android)

# usb-serial-for-android

This is a driver library for communication with Arduinos and other USB serial hardware on
Android, using the
[Android USB Host Mode (OTG)](http://developer.android.com/guide/topics/connectivity/usb/host.html)
available since Android 3.1 and working reliably since Android 4.2.

No root access, ADK, or special kernel drivers are required; all drivers are implemented in
Java.  You get a raw serial port with `read()`, `write()`, and other basic
functions for use with your own protocols.

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
    implementation 'com.github.mik3y:usb-serial-for-android:Tag'
}
```

**2.** Copy [device_filter.xml](https://github.com/mik3y/usb-serial-for-android/blob/master/usbSerialExamples/src/main/res/xml/device_filter.xml) to your project's `res/xml/` directory.

**3.** Configure your `AndroidManifest.xml` to notify your app when a device is attached (see [Android USB Host documentation](http://developer.android.com/guide/topics/connectivity/usb/host.html#discovering-d) for help).

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

**4.** Use it! Example code snippet:

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
  // You probably need to call UsbManager.requestPermission(driver.getDevice(), ..)
  return;
}

// Read some data! Most have just one port (port 0).
UsbSerialPort port = driver.getPorts().get(0);
try {
  port.open(connection);
  port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);

  byte buffer[] = new byte[16];
  int numBytesRead = port.read(buffer, 1000);
  Log.d(TAG, "Read " + numBytesRead + " bytes.");
} catch (IOException e) {
  // Deal with error.
} finally {
  port.close();
}
```

For a simple example, see the
[UsbSerialExamples project](https://github.com/mik3y/usb-serial-for-android/blob/master/usbSerialExamples)
in git, which is a simple application for reading and showing serial data.

For a more complete example, see separate github project 
[SimpleUsbTerminal](https://github.com/kai-morich/SimpleUsbTerminal)

A [simple Arduino application](https://github.com/mik3y/usb-serial-for-android/blob/master/arduino)
is also available which can be used for testing.

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
* FTDI FT232, FT2232, ...
* Prolific PL2303
* Silabs CP2102, CP2105, ...
* Qinheng CH340

and devices implementing the CDC/ACM protocol like
* Arduino using ATmega32U4
* Digispark using V-USB software USB
* BBC micro:bit using ARM mbed DAPLink firmware
* ...

## Author, License, and Copyright

usb-serial-for-android is written and maintained by *mike wakerly* and *kai morich*

This library is licensed under *LGPL Version 2.1*.  Please see LICENSE.txt for the
complete license.

Copyright 2011-2012, Google Inc. All Rights Reserved.

Portions of this library are based on [libftdi](http://www.intra2net.com/en/developer/libftdi).
Please see FtdiSerialDriver.java for more information.

## Help & Discussion

For common problems, see the
[Troubleshooting](https://github.com/mik3y/usb-serial-for-android/wiki/Troubleshooting)
wiki page.

Are you using the library? Add your project to 
[ProjectsUsingUsbSerialForAndroid](https://github.com/mik3y/usb-serial-for-android/wiki/Projects-Using-usb-serial-for-android).
