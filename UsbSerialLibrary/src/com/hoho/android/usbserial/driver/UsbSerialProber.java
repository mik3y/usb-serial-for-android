/* Copyright 2011 Google Inc.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301,
 * USA.
 *
 * Project home page: http://code.google.com/p/usb-serial-for-android/
 */

package com.hoho.android.usbserial.driver;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Helper class which finds compatible {@link UsbDevice}s and creates
 * {@link UsbSerialDriver} instances.
 *
 * <p/>
 * You don't need a Prober to use the rest of the library: it is perfectly
 * acceptable to instantiate driver instances manually. The Prober simply
 * provides convenience functions.
 *
 * <p/>
 * For most drivers, the corresponding {@link #probe(UsbManager, UsbDevice)}
 * method will either return an empty list (device unknown / unsupported) or a
 * singleton list. However, multi-port drivers may return multiple instances.
 *
 * @author mike wakerly (opensource@hoho.com)
 */
public enum UsbSerialProber {

    // TODO(mikey): Too much boilerplate.

    /**
     * Prober for {@link FtdiSerialDriver}.
     *
     * @see FtdiSerialDriver
     */
    FTDI_SERIAL {
        @Override
        public List<UsbSerialDriver> probe(final UsbManager manager, final UsbDevice usbDevice) {
            if (!testIfSupported(usbDevice, FtdiSerialDriver.getSupportedDevices())) {
                return Collections.emptyList();
            }
            final UsbDeviceConnection connection = manager.openDevice(usbDevice);
            if (connection == null) {
                return Collections.emptyList();
            }
            final UsbSerialDriver driver = new FtdiSerialDriver(usbDevice, connection);
            return Collections.singletonList(driver);
        }
    },

    CDC_ACM_SERIAL {
        @Override
        public List<UsbSerialDriver> probe(UsbManager manager, UsbDevice usbDevice) {
            if (!testIfSupported(usbDevice, CdcAcmSerialDriver.getSupportedDevices())) {
               return Collections.emptyList();
            }
            final UsbDeviceConnection connection = manager.openDevice(usbDevice);
            if (connection == null) {
                return Collections.emptyList();
            }
            final UsbSerialDriver driver = new CdcAcmSerialDriver(usbDevice, connection);
            return Collections.singletonList(driver);
        }
    },

    SILAB_SERIAL {
        @Override
        public List<UsbSerialDriver> probe(final UsbManager manager, final UsbDevice usbDevice) {
            if (!testIfSupported(usbDevice, Cp2102SerialDriver.getSupportedDevices())) {
                return Collections.emptyList();
            }
            final UsbDeviceConnection connection = manager.openDevice(usbDevice);
            if (connection == null) {
                return Collections.emptyList();
            }
            final UsbSerialDriver driver = new Cp2102SerialDriver(usbDevice, connection);
            return Collections.singletonList(driver);
        }
    },

    PROLIFIC_SERIAL {
        @Override
        public List<UsbSerialDriver> probe(final UsbManager manager, final UsbDevice usbDevice) {
            if (!testIfSupported(usbDevice, ProlificSerialDriver.getSupportedDevices())) {
                return Collections.emptyList();
            }
            final UsbDeviceConnection connection = manager.openDevice(usbDevice);
            if (connection == null) {
                return Collections.emptyList();
            }
            final UsbSerialDriver driver = new ProlificSerialDriver(usbDevice, connection);
            return Collections.singletonList(driver);
        }
    };

    /**
     * Tests the supplied {@link UsbDevice} for compatibility with this enum
     * member, returning one or more driver instances if compatible.
     *
     * @param manager the {@link UsbManager} to use
     * @param usbDevice the raw {@link UsbDevice} to use
     * @return zero or more {@link UsbSerialDriver}, depending on compatibility
     *         (never {@code null}).
     */
    protected abstract List<UsbSerialDriver> probe(final UsbManager manager, final UsbDevice usbDevice);

    /**
     * Creates and returns a new {@link UsbSerialDriver} instance for the first
     * compatible {@link UsbDevice} found on the bus.  If none are found,
     * returns {@code null}.
     *
     * <p/>
     * The order of devices is undefined, therefore if there are multiple
     * devices on the bus, the chosen device may not be predictable (clients
     * should use {@link #findAllDevices(UsbManager)} instead).
     *
     * @param usbManager the {@link UsbManager} to use.
     * @return the first available {@link UsbSerialDriver}, or {@code null} if
     *         none are available.
     */
    public static UsbSerialDriver findFirstDevice(final UsbManager usbManager) {
        for (final UsbDevice usbDevice : usbManager.getDeviceList().values()) {
            for (final UsbSerialProber prober : values()) {
                final List<UsbSerialDriver> probedDevices = prober.probe(usbManager, usbDevice);
                if (!probedDevices.isEmpty()) {
                    return probedDevices.get(0);
                }
            }
        }
        return null;
    }

    /**
     * Creates a new {@link UsbSerialDriver} instance for all compatible
     * {@link UsbDevice}s found on the bus. If no compatible devices are found,
     * the list will be empty.
     *
     * @param usbManager
     * @return
     */
    public static List<UsbSerialDriver> findAllDevices(final UsbManager usbManager) {
        final List<UsbSerialDriver> result = new ArrayList<UsbSerialDriver>();

        // For each UsbDevice, call probe() for each prober.
        for (final UsbDevice usbDevice : usbManager.getDeviceList().values()) {
            result.addAll(probeSingleDevice(usbManager, usbDevice));
        }
        return result;
    }

    /**
     * Special method for testing a specific device for driver support,
     * returning any compatible driver(s).
     *
     * <p/>
     * Clients should ordinarily use {@link #findAllDevices(UsbManager)}, which
     * operates against the entire bus of devices. This method is useful when
     * testing against only a single target is desired.
     *
     * @param usbManager the {@link UsbManager} to use.
     * @param usbDevice the device to test against.
     * @return a list containing zero or more {@link UsbSerialDriver} instances.
     */
    public static List<UsbSerialDriver> probeSingleDevice(final UsbManager usbManager,
            UsbDevice usbDevice) {
        final List<UsbSerialDriver> result = new ArrayList<UsbSerialDriver>();
        for (final UsbSerialProber prober : values()) {
            final List<UsbSerialDriver> probedDevices = prober.probe(usbManager, usbDevice);
            result.addAll(probedDevices);
        }
        return result;
    }

    /**
     * Deprecated; Use {@link #findFirstDevice(UsbManager)}.
     *
     * @param usbManager
     * @return
     */
    @Deprecated
    public static UsbSerialDriver acquire(final UsbManager usbManager) {
        return findFirstDevice(usbManager);
    }

    /**
     * Deprecated; use {@link #probeSingleDevice(UsbManager, UsbDevice)}.
     *
     * @param usbManager
     * @param usbDevice
     * @return
     */
    @Deprecated
    public static UsbSerialDriver acquire(final UsbManager usbManager, final UsbDevice usbDevice) {
        final List<UsbSerialDriver> probedDevices = probeSingleDevice(usbManager, usbDevice);
        if (!probedDevices.isEmpty()) {
            return probedDevices.get(0);
        }
        return null;
    }

    /**
     * Returns {@code true} if the given device is found in the driver's
     * vendor/product map.
     *
     * @param usbDevice the device to test
     * @param supportedDevices map of vendor IDs to product ID(s)
     * @return {@code true} if supported
     */
    private static boolean testIfSupported(final UsbDevice usbDevice,
            final Map<Integer, int[]> supportedDevices) {
        final int[] supportedProducts = supportedDevices.get(
                Integer.valueOf(usbDevice.getVendorId()));
        if (supportedProducts == null) {
            return false;
        }

        final int productId = usbDevice.getProductId();
        for (int supportedProductId : supportedProducts) {
            if (productId == supportedProductId) {
                return true;
            }
        }
        return false;
    }

}
