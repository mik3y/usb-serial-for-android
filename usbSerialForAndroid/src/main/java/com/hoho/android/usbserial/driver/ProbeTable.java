/* Copyright 2011-2013 Google Inc.
 * Copyright 2013 mike wakerly <opensource@hoho.com>
 *
 * Project home page: https://github.com/mik3y/usb-serial-for-android
 */

package com.hoho.android.usbserial.driver;

import android.hardware.usb.UsbDevice;
import android.util.Pair;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps (vendor id, product id) pairs to the corresponding serial driver,
 * or invoke 'probe' method to check actual USB devices for matching interfaces.
 */
public class ProbeTable {

    private final Map<Pair<Integer, Integer>, Class<? extends UsbSerialDriver>> mVidPidProbeTable =
            new LinkedHashMap<>();
    private final Map<Method, Class<? extends UsbSerialDriver>> mMethodProbeTable = new LinkedHashMap<>();

    /**
     * Adds or updates a (vendor, product) pair in the table.
     *
     * @param vendorId the USB vendor id
     * @param productId the USB product id
     * @param driverClass the driver class responsible for this pair
     * @return {@code this}, for chaining
     */
    public ProbeTable addProduct(int vendorId, int productId,
            Class<? extends UsbSerialDriver> driverClass) {
        mVidPidProbeTable.put(Pair.create(vendorId, productId), driverClass);
        return this;
    }

    /**
     * Internal method to add all supported products from
     * {@code getSupportedProducts} static method.
     *
     * @param driverClass to be added
     */
    @SuppressWarnings("unchecked")
    void addDriver(Class<? extends UsbSerialDriver> driverClass) {
        Method method;

        try {
            method = driverClass.getMethod("getSupportedDevices");
        } catch (SecurityException | NoSuchMethodException e) {
            throw new RuntimeException(e);
        }

        final Map<Integer, int[]> devices;
        try {
            devices = (Map<Integer, int[]>) method.invoke(null);
        } catch (IllegalArgumentException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }

        for (Map.Entry<Integer, int[]> entry : devices.entrySet()) {
            final int vendorId = entry.getKey();
            for (int productId : entry.getValue()) {
                addProduct(vendorId, productId, driverClass);
            }
        }

        try {
            method = driverClass.getMethod("probe", UsbDevice.class);
            mMethodProbeTable.put(method, driverClass);
        } catch (SecurityException | NoSuchMethodException ignored) {
        }
    }

    /**
     * Returns the driver for the given USB device, or {@code null} if no match.
     *
     * @param usbDevice the USB device to be probed
     * @return the driver class matching this pair, or {@code null}
     */
    public Class<? extends UsbSerialDriver> findDriver(final UsbDevice usbDevice) {
        final Pair<Integer, Integer> pair = Pair.create(usbDevice.getVendorId(), usbDevice.getProductId());
        Class<? extends UsbSerialDriver> driverClass = mVidPidProbeTable.get(pair);
        if (driverClass != null)
            return driverClass;
        for (Map.Entry<Method, Class<? extends UsbSerialDriver>> entry : mMethodProbeTable.entrySet()) {
            try {
                Method method = entry.getKey();
                Object o = method.invoke(null, usbDevice);
                if((boolean)o)
                    return entry.getValue();
            } catch (IllegalArgumentException | IllegalAccessException | InvocationTargetException e) {
                throw new RuntimeException(e);
            }
        }
        return null;
    }

}
