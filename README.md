# usb-serial-for-android

Library for talking to Arduinos and other USB serial devices on Android, using
USB Host mode and Android 3.1+

Homepage: http://code.google.com/p/usb-serial-for-android

## About

This project provides an Android userspace driver for USB serial devices.  You
can use it to talk to your Arduino, or any other supported serial devices.

## Usage

Download the sources.  Inside you will find two Eclipse projects:

* UsbSerialLibrary - the main library code, an "Android Library" project.
* UsbSerialExamples - a demo Android application

In Eclipse, open "File", "Import", and then select "General, "Existing Projects
into Workspace".

Navigate to the directory you just checked out and import both projects.  Then
run the demo application.


## Compatible Serial Devices

Supported and tested:

*   FT232R

Possibly supported (untested):

*   FT232H
*   FT2232D
*   FT2432H

Unsupported (send patches!):

*   Arduino Uno (CDC)


## Compatible Android Devices

Supported and tested:

*   Motorola Xoom, Android 3.1/3.2

Possibly supported (untested):

*   Samsung Galaxy Tab 10.1


## License and Copyright

This library is licensed under LGPL Version 2.1.  Please see LICENSE.txt for the
complete license.

Copyright 2011, Google Inc. All Rights Reserved.

Portions of this library are based on libftdi
(http://www.intra2net.com/en/developer/libftdi).  Please see
FtdiSerialDriver.java for more information.


## Contributing

Patches are welcome.  We're especially interested in supporting more devices.
Please open a bug report.


## Credits

Author/maintainer: mike wakerly <opensource@hoho.com>

Contributors:

*   Robert Tsai <rob@tsaiberspace.com> (code review)


