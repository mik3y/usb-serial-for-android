/*
 * test multiple devices or multiple ports on same device
 *
 * TxD and RxD have to be cross connected
 */
package com.hoho.android.usbserial;

import android.content.Context;
import android.hardware.usb.UsbManager;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.util.Log;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.UsbWrapper;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runner.RunWith;

import java.util.EnumSet;
import java.util.List;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

@RunWith(AndroidJUnit4.class)
public class CrossoverTest {

    private final static String  TAG = CrossoverTest.class.getSimpleName();

    private Context context;
    private UsbManager usbManager;
    private UsbWrapper usb1, usb2;

    @Rule
    public TestRule watcher = new TestWatcher() {
        protected void starting(Description description) {
            Log.i(TAG, "===== starting test: " + description.getMethodName()+ " =====");
        }
    };

    @Before
    public void setUp() throws Exception {
        assumeTrue("ignore test for device specific coverage report",
                InstrumentationRegistry.getArguments().getString("test_device_driver") == null);

        context = InstrumentationRegistry.getContext();
        usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        assertNotEquals("no USB device found", 0, availableDrivers.size());
        if (availableDrivers.size() == 0) {
            fail("no USB device found");
        } else if (availableDrivers.size() == 1) {
            assertEquals(2, availableDrivers.get(0).getPorts().size());
            usb1 = new UsbWrapper(context, availableDrivers.get(0), 0);
            usb2 = new UsbWrapper(context, availableDrivers.get(0), 1);
        } else {
            assertEquals(1, availableDrivers.get(0).getPorts().size());
            assertEquals(1, availableDrivers.get(1).getPorts().size());
            usb1 = new UsbWrapper(context, availableDrivers.get(0), 0);
            usb2 = new UsbWrapper(context, availableDrivers.get(1), 0);
        }
        usb1.setUp();
        usb2.setUp();
    }

    @Test
    public void reopen() throws Exception {
        byte[] buf;

        usb1.open(EnumSet.of(UsbWrapper.OpenCloseFlags.NO_IOMANAGER_THREAD));
        usb2.open(EnumSet.of(UsbWrapper.OpenCloseFlags.NO_IOMANAGER_THREAD));
        usb1.setParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        usb2.setParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);

        usb1.write("x".getBytes());
        buf = usb2.read(1);
        assertThat(buf, equalTo("x".getBytes()));

        usb2.write("y".getBytes());
        buf = usb1.read(1);
        assertThat(buf, equalTo("y".getBytes()));

        usb2.close(); // does not affect usb1 with individual UsbDeviceConnection on same device

        usb2.open(EnumSet.of(UsbWrapper.OpenCloseFlags.NO_IOMANAGER_THREAD));
        usb2.setParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);

        usb1.write("x".getBytes());
        buf = usb2.read(1);
        assertThat(buf, equalTo("x".getBytes()));

        usb2.write("y".getBytes());
        buf = usb1.read(1);
        assertThat(buf, equalTo("y".getBytes()));

        usb1.close();
        usb2.close();
    }

    @Test
    public void ioManager() throws Exception {
        byte[] buf;

        // each SerialInputOutputManager thread runs in it's own SingleThreadExecutor
        usb1.open();
        usb2.open();
        usb1.setParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        usb2.setParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);

        usb1.write("x".getBytes());
        buf = usb2.read(1);
        assertThat(buf, equalTo("x".getBytes()));

        usb2.write("y".getBytes());
        buf = usb1.read(1);
        assertThat(buf, equalTo("y".getBytes()));

        usb1.close();
        usb2.close();
    }

    @Test
    public void baudRate() throws Exception {
        byte[] buf;

        usb1.open(EnumSet.of(UsbWrapper.OpenCloseFlags.NO_IOMANAGER_THREAD));
        usb2.open(EnumSet.of(UsbWrapper.OpenCloseFlags.NO_IOMANAGER_THREAD));
        usb1.setParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        usb2.setParameters(9600, 8, 1, UsbSerialPort.PARITY_NONE);

        // a - start bit (0)
        // o - stop bit  (1)
        // 0/1 - data bit

        // out 19k2: a00011001o
        // in 9k6:   a 0 1 0 1 1 1 1 1 o
        usb1.write(new byte[]{(byte)0x98});
        buf = usb2.read(1);
        assertThat(buf, equalTo(new byte[]{(byte)0xfa}));

        // out 9k6: a 1 0 1 1 1 1 1 1 o
        // in 19k2: a01100111o
        usb2.write(new byte[]{(byte)0xfd});
        buf = usb1.read(1);
        assertThat(buf, equalTo(new byte[]{(byte)0xe6}));

        usb1.close();
        usb2.close();
    }

}
