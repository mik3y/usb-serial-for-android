## multiple CDC interface test

As mentioned [here](https://arduino.stackexchange.com/a/31695/62145),  Arduino functions can be _overwritten_ by copying complete files into the own project. 

This is used to create a device with 2 CDC interfaces. For simplicity only one of both is functional (configurable in USBDesc.h)

The modifications have been done against Arduino 1.8.10, for changes see comments containing `kai`.

