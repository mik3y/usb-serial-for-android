/*
 * restrictions
 *  - as real hardware is used, timing might need tuning. see:
 *      - Thread.sleep(...)
 *      - obj.wait(...)
 *  - missing functionality on certain devices, see:
 *      - if(rfc2217_server_nonstandard_baudrates)
 *      - if(usbSerialDriver instanceof ...)
 *
 */
package com.hoho.android.usbserial;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Process;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;
import android.util.Log;

import com.hoho.android.usbserial.driver.CdcAcmSerialDriver;
import com.hoho.android.usbserial.driver.Ch34xSerialDriver;
import com.hoho.android.usbserial.driver.CommonUsbSerialPort;
import com.hoho.android.usbserial.driver.CommonUsbSerialPortWrapper;
import com.hoho.android.usbserial.driver.Cp21xxSerialDriver;
import com.hoho.android.usbserial.driver.FtdiSerialDriver;
import com.hoho.android.usbserial.driver.ProbeTable;
import com.hoho.android.usbserial.driver.ProlificSerialDriver;
import com.hoho.android.usbserial.driver.ProlificSerialPortWrapper;
import com.hoho.android.usbserial.driver.SerialTimeoutException;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;
import com.hoho.android.usbserial.util.TelnetWrapper;
import com.hoho.android.usbserial.util.TestBuffer;
import com.hoho.android.usbserial.util.UsbWrapper;
import com.hoho.android.usbserial.driver.UsbSerialPort.ControlLine;


import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.nio.BufferOverflowException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.CoreMatchers.anyOf;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
public class DeviceTest {
    private final static String  TAG = DeviceTest.class.getSimpleName();

    // testInstrumentationRunnerArguments configuration
    private static String  rfc2217_server_host;
    private static int     rfc2217_server_port = 2217;
    private static boolean rfc2217_server_nonstandard_baudrates;
    private static String  test_device_driver;
    private static int     test_device_port;

    private Context context;
    private UsbManager usbManager;
    UsbWrapper usb;
    static TelnetWrapper telnet;

    @Rule
    public TestRule watcher = new TestWatcher() {
        protected void starting(Description description) {
            Log.i(TAG, "===== starting test: " + description.getMethodName()+ " =====");
        }
    };

    @BeforeClass
    public static void setUpFixture() throws Exception {
        rfc2217_server_host                  =                 InstrumentationRegistry.getArguments().getString("rfc2217_server_host");
        rfc2217_server_nonstandard_baudrates = Boolean.valueOf(InstrumentationRegistry.getArguments().getString("rfc2217_server_nonstandard_baudrates"));
        test_device_driver                   =                 InstrumentationRegistry.getArguments().getString("test_device_driver");
        test_device_port                     = Integer.valueOf(InstrumentationRegistry.getArguments().getString("test_device_port","0"));

        // postpone parts of fixture setup to first test, because exceptions are not reported for @BeforeClass
        // and test terminates with misleading 'Empty test suite'
        telnet = new TelnetWrapper(rfc2217_server_host, rfc2217_server_port);
    }

    @Before
    public void setUp() throws Exception {
        telnet.setUp();

        context = ApplicationProvider.getApplicationContext();
        usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        if(availableDrivers.isEmpty()) {
            ProbeTable customTable = new ProbeTable();
            customTable.addProduct(0x2342, 0x8036, CdcAcmSerialDriver.class); // arduino multiport cdc witch custom VID
            availableDrivers = new UsbSerialProber(customTable).findAllDrivers(usbManager);
        }
        assertEquals("no USB device found", 1, availableDrivers.size());
        UsbSerialDriver usbSerialDriver = availableDrivers.get(0);
        if(test_device_driver != null) {
            String driverName = usbSerialDriver.getClass().getSimpleName();
            assertEquals(test_device_driver+"SerialDriver", driverName);
        }
        assertTrue( usbSerialDriver.getPorts().size() > test_device_port);
        usb = new UsbWrapper(context, usbSerialDriver, test_device_port);
        usb.setUp();

        Log.i(TAG, "Using USB device "+ usb.serialPort.toString()+" driver="+usb.serialDriver.getClass().getSimpleName());
        telnet.read(-1); // doesn't look related here, but very often after usb permission dialog the first test failed with telnet garbage
    }

    @After
    public void tearDown() throws IOException {
        if(usb != null)
            usb.tearDown();
        telnet.tearDown();
    }

    @AfterClass
    public static void tearDownFixture() throws Exception {
        telnet.tearDownFixture();
    }

    // clone of org.apache.commons.lang3.StringUtils.indexOfDifference + optional startpos
    private static int indexOfDifference(final CharSequence cs1, final CharSequence cs2) {
        return indexOfDifference(cs1, cs2, 0, 0);
    }

    private static int indexOfDifference(final CharSequence cs1, final CharSequence cs2, int cs1startpos, int cs2startpos) {
        if (cs1 == cs2) {
            return -1;
        }
        if (cs1 == null || cs2 == null) {
            return 0;
        }
        if(cs1startpos < 0 || cs2startpos < 0)
            return -1;
        int i, j;
        for (i = cs1startpos, j = cs2startpos; i < cs1.length() && j < cs2.length(); ++i, ++j) {
            if (cs1.charAt(i) != cs2.charAt(j)) {
                break;
            }
        }
        if (j < cs2.length() || i < cs1.length()) {
            return i;
        }
        return -1;
    }

    private int findDifference(final StringBuilder data, final StringBuilder expected) {
        int length = 0;
        int datapos = indexOfDifference(data, expected);
        int expectedpos = datapos;
        while(datapos != -1) {
            int nextexpectedpos = -1;
            int nextdatapos = datapos + 2;
            int len = -1;
            if(nextdatapos + 10 < data.length()) { // try to sync data+expected, assuming that data is lost, but not corrupted
                String nextsub = data.substring(nextdatapos, nextdatapos + 10);
                nextexpectedpos = expected.indexOf(nextsub, expectedpos);
                if(nextexpectedpos >= 0) {
                    len = nextexpectedpos - expectedpos - 2;
                }
            }
            Log.i(TAG, "difference at " + datapos + " len " + len );
            Log.d(TAG, "       got " +     data.substring(Math.max(datapos - 20, 0), Math.min(datapos + 20, data.length())));
            Log.d(TAG, "  expected " + expected.substring(Math.max(expectedpos - 20, 0), Math.min(expectedpos + 20, expected.length())));
            datapos = indexOfDifference(data, expected, nextdatapos, nextexpectedpos);
            expectedpos = nextexpectedpos + (datapos  - nextdatapos);
            if(len==-1) length=-1;
            else        length+=len;
        }
        return length;
    }

    private void doReadWrite(String reason) throws Exception {
        doReadWrite(reason, -1);
    }
    private void doReadWrite(String reason, int readWait) throws Exception {
        byte[] buf1 = new byte[]{ 0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x55, 0x55};
        byte[] buf2 = new byte[]{ 0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x55, 0x55};
        byte[] data;

        telnet.write(buf1);
        data = usb.read(buf1.length, -1, readWait);
        assertThat(reason, data, equalTo(buf1)); // includes array content in output
        //assertArrayEquals("net2usb".getBytes(), data); // only includes array length in output
        usb.write(buf2);
        data = telnet.read(buf2.length, readWait);
        assertThat(reason, data, equalTo(buf2));
    }

    private void purgeWriteBuffer(int timeout) throws Exception {
        try {
            Log.d(TAG, " purge begin");
            usb.serialPort.purgeHwBuffers(true, false);
        } catch(UnsupportedOperationException ignored) {}
        byte[] data = telnet.read(-1, timeout);
        int len = 0;
        while(data.length != 0) {
            len += data.length;
            Log.d(TAG, " purge read " + data.length);
            data = telnet.read(-1, timeout);
        }
        Log.d(TAG, " purge end " + len);
    }

    @Test
    public void openClose() throws Exception {
        usb.open();
        telnet.setParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        usb.setParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        doReadWrite("");

        try {
            usb.serialPort.open(usb.deviceConnection);
            fail("already open expected");
        } catch (IOException ignored) {
        }
        doReadWrite("");

        usb.close();
        try {
            usb.serialPort.close();
            fail("already closed expected");
        } catch (IOException ignored) {
        }
        try {
            usb.write(new byte[]{0x00});
            fail("write error expected");
        } catch (IOException ignored) {
        }
        try {
            usb.read(1);
            fail("read error expected");
        } catch (IOException ignored) {
        }
        try {
            usb.setParameters(9600, 8, 1, UsbSerialPort.PARITY_NONE);
            fail("error expected");
        } catch (IOException ignored) {
        } catch (NullPointerException ignored) {
        }

        usb.open();
        telnet.setParameters(9600, 8, 1, UsbSerialPort.PARITY_NONE);
        usb.setParameters(9600, 8, 1, UsbSerialPort.PARITY_NONE);
        doReadWrite("");

        // close port before iomanager
        assertEquals(SerialInputOutputManager.State.RUNNING, usb.ioManager.getState());
        usb.serialPort.close();
        for (int i = 0; i < 1000; i++) {
            if (usb.ioManager.getState() == SerialInputOutputManager.State.STOPPED)
                break;
            Thread.sleep(1);
        }
        // assertEquals(SerialInputOutputManager.State.STOPPED, usb.usbIoManager.getState());
        // unstable. null'ify not-stopped ioManager, else usbClose would try again
        if(SerialInputOutputManager.State.STOPPED != usb.ioManager.getState())
            usb.ioManager = null;
    }

    @Test
    public void prolificBaudRate() throws Exception {
        Assume.assumeTrue("only for Prolific", usb.serialDriver instanceof ProlificSerialDriver);

        int[] baudRates = {
                75, 150, 300, 600, 1200, 1800, 2400, 3600, 4800, 7200, 9600, 14400, 19200,
                28800, 38400, 57600, 115200, 128000, 134400, 161280, 201600, 230400, 268800,
                403200, 460800, 614400, 806400, 921600, 1228800, 2457600, 3000000, /*6000000*/
        };
        usb.open();
        Assume.assumeFalse("only for non PL2303G*", ProlificSerialPortWrapper.isDeviceTypeHxn(usb.serialPort)); // HXN does not use divisor

        int minBaudRate = ProlificSerialPortWrapper.isDeviceTypeT(usb.serialPort) ? 6 : 46;
        try {
            usb.setParameters(minBaudRate-1, 8, 1, UsbSerialPort.PARITY_NONE);
            fail("baud rate to low expected");
        } catch(UnsupportedOperationException ignored) {}
        usb.setParameters(minBaudRate, 8, 1, UsbSerialPort.PARITY_NONE);
        usb.setParameters(384_000_000, 8, 1, UsbSerialPort.PARITY_NONE);
        try {
            usb.setParameters(384_000_001, 8, 1, UsbSerialPort.PARITY_NONE);
            fail("baud rate to high expected");
        } catch(UnsupportedOperationException ignored) {}
        usb.setParameters(11_636_363, 8, 1, UsbSerialPort.PARITY_NONE);
        try {
            usb.setParameters(11_636_364, 8, 1, UsbSerialPort.PARITY_NONE);
            fail("baud rate deviation to high expected");
        } catch(UnsupportedOperationException ignored) {}

        for(int baudRate : baudRates) {
            int readWait = 500;
            if(baudRate < 300) readWait = 1000;
            if(baudRate < 150) readWait = 2000;
            telnet.setParameters(baudRate, 8, 1, UsbSerialPort.PARITY_NONE);
            usb.setParameters(baudRate, 8, 1, UsbSerialPort.PARITY_NONE);
            doReadWrite(String.valueOf(baudRate), readWait);

            usb.setParameters(baudRate + 1, 8, 1, UsbSerialPort.PARITY_NONE);
            doReadWrite(String.valueOf(baudRate + 1), readWait);

            // silent fallback to 9600 for unsupported baud rates
            telnet.setParameters(9600, 8, 1, UsbSerialPort.PARITY_NONE);
            usb.setParameters(baudRate + 1 + (1<<29), 8, 1, UsbSerialPort.PARITY_NONE);
            doReadWrite(String.valueOf(baudRate + 1) + " + 1<<29", readWait);
        }

        // some PL2303... data sheets mention additional standard baud rates, others don't
        // they do not work with my devices and linux driver also excludes them
        baudRates = new int[]{110, 56000, 256000};
        for(int baudRate : baudRates) {
            int readWait = 500;
            if(baudRate < 300) readWait = 1000;
            if(baudRate < 150) readWait = 2000;
            telnet.setParameters(baudRate, 8, 1, UsbSerialPort.PARITY_NONE);
            usb.setParameters(baudRate, 8, 1, UsbSerialPort.PARITY_NONE);
            doReadWrite(String.valueOf(baudRate), readWait);

            // silent fallback to 9600 for unsupported baud rates
            telnet.setParameters(9600, 8, 1, UsbSerialPort.PARITY_NONE);
            usb.setParameters(baudRate + (1<<29), 8, 1, UsbSerialPort.PARITY_NONE);
            doReadWrite(String.valueOf(baudRate) + " + 1<<29", readWait);
        }
    }

    @Test
    public void ftdiBaudRate() throws Exception {
        Assume.assumeTrue("only for FTDI", usb.serialDriver instanceof FtdiSerialDriver);

        usb.open();
        try {
            usb.setParameters(183, 8, 1, UsbSerialPort.PARITY_NONE);
            fail("baud rate to low expected");
        } catch (UnsupportedOperationException ignored) {
        }
        usb.setParameters(184, 8, 1, UsbSerialPort.PARITY_NONE);
        usb.setParameters( 960000, 8, 1, UsbSerialPort.PARITY_NONE);
        usb.setParameters(1000000, 8, 1, UsbSerialPort.PARITY_NONE);
        usb.setParameters(1043478, 8, 1, UsbSerialPort.PARITY_NONE);
        usb.setParameters(1090909, 8, 1, UsbSerialPort.PARITY_NONE);
        usb.setParameters(1142857, 8, 1, UsbSerialPort.PARITY_NONE);
        usb.setParameters(1200000, 8, 1, UsbSerialPort.PARITY_NONE);
        usb.setParameters(1263157, 8, 1, UsbSerialPort.PARITY_NONE);
        usb.setParameters(1333333, 8, 1, UsbSerialPort.PARITY_NONE);
        usb.setParameters(1411764, 8, 1, UsbSerialPort.PARITY_NONE);
        usb.setParameters(1500000, 8, 1, UsbSerialPort.PARITY_NONE);
        try {
            usb.setParameters((int)(2000000/1.04), 8, 1, UsbSerialPort.PARITY_NONE);
            fail("baud rate error expected");
        } catch (UnsupportedOperationException ignored) {
        }
        usb.setParameters((int)(2000000/1.03), 8, 1, UsbSerialPort.PARITY_NONE);
        usb.setParameters(2000000, 8, 1, UsbSerialPort.PARITY_NONE);
        usb.setParameters((int)(2000000*1.03), 8, 1, UsbSerialPort.PARITY_NONE);
        try {
            usb.setParameters((int)(2000000*1.04), 8, 1, UsbSerialPort.PARITY_NONE);
            fail("baud rate error expected");
        } catch (UnsupportedOperationException ignored) {
        }
        usb.setParameters(2000000, 8, 1, UsbSerialPort.PARITY_NONE);
        usb.setParameters(3000000, 8, 1, UsbSerialPort.PARITY_NONE);
        try {
            usb.setParameters(4000000, 8, 1, UsbSerialPort.PARITY_NONE);
            fail("baud rate to high expected");
        } catch (UnsupportedOperationException ignored) {
        }
    }

    @Test
    public void Ch34xBaudRate() throws Exception {
        Assume.assumeTrue("only for Ch34x", usb.serialDriver instanceof Ch34xSerialDriver);
        usb.open();

        int[] baudRates = {
                115200, 230400, 256000, 307200, 460800, 921600, 1000000, 1228800
        };
        for (int baudRate : baudRates) {
            telnet.setParameters(baudRate, 8, 1, UsbSerialPort.PARITY_NONE);
            usb.setParameters(baudRate, 8, 1, UsbSerialPort.PARITY_NONE);
            doReadWrite(baudRate + "");
            try {
                usb.setParameters(baudRate + (1 << 29), 8, 1, UsbSerialPort.PARITY_NONE);
                doReadWrite(baudRate + "+(1<<29)");

                usb.setParameters(baudRate - 1, 8, 1, UsbSerialPort.PARITY_NONE);
                doReadWrite(baudRate + "-1");

                usb.setParameters(baudRate + 1, 8, 1, UsbSerialPort.PARITY_NONE);
                doReadWrite(baudRate + "+1");
                if (baudRate == 921600)
                    fail("error expected for " + baudRate + " baud");
            } catch(AssertionError err) {
                if (baudRate != 921600)
                    throw(err);
            }
        }
    }

    @Test
    public void baudRate() throws Exception {
        usb.open();

        if (false) { // default baud rate
            // CP2102: only works if first connection after attaching device
            // PL2303, FTDI: it's not 9600
            telnet.setParameters(9600, 8, 1, UsbSerialPort.PARITY_NONE);

            doReadWrite("");
        }

        // invalid values
        try {
            usb.setParameters(-1, 8, 1, UsbSerialPort.PARITY_NONE);
            fail("invalid baud rate");
        } catch (IllegalArgumentException ignored) {
        }
        try {
            usb.setParameters(0, 8, 1, UsbSerialPort.PARITY_NONE);
            fail("invalid baud rate");
        } catch (IllegalArgumentException ignored) {
        }
        try {
            usb.setParameters(1, 8, 1, UsbSerialPort.PARITY_NONE);
            if (usb.serialDriver instanceof FtdiSerialDriver)
                ;
            else if (usb.serialDriver instanceof ProlificSerialDriver)
                ;
            else if (usb.serialDriver instanceof Cp21xxSerialDriver)
                ;
            else if (usb.serialDriver instanceof CdcAcmSerialDriver)
                ;
            else
                fail("invalid baudrate 1");
        } catch (UnsupportedOperationException ignored) { // ch340
        } catch (IOException ignored) { // cp2105 second port
        } catch (IllegalArgumentException ignored) {
        }
        try {
            usb.setParameters(1<<31, 8, 1, UsbSerialPort.PARITY_NONE);
            if (usb.serialDriver instanceof ProlificSerialDriver)
                ;
            else if (usb.serialDriver instanceof Cp21xxSerialDriver)
                ;
            else if (usb.serialDriver instanceof CdcAcmSerialDriver)
                ;
            else
                fail("invalid baudrate 2^31");
        } catch (ArithmeticException ignored) { // ch340
        } catch (IOException ignored) { // cp2105 second port
        } catch (IllegalArgumentException ignored) {
        }

        for(int baudRate : new int[] {300, 2400, 19200, 115200} ) {
            if(baudRate == 300 && usb.isCp21xxRestrictedPort) {
                try {
                    usb.setParameters(baudRate, 8, 1, UsbSerialPort.PARITY_NONE);
                    fail("baudrate 300 on cp21xx restricted port");
                } catch (IOException ignored) {
                }
                continue;
            }
            telnet.setParameters(baudRate, 8, 1, UsbSerialPort.PARITY_NONE);
            usb.setParameters(baudRate, 8, 1, UsbSerialPort.PARITY_NONE);

            doReadWrite(baudRate+"/8N1");
        }
        if(rfc2217_server_nonstandard_baudrates && !usb.isCp21xxRestrictedPort) {
            usb.setParameters(42000, 8, 1, UsbSerialPort.PARITY_NONE);
            telnet.setParameters(42000, 8, 1, UsbSerialPort.PARITY_NONE);

            byte[] buf1 = "abc".getBytes();
            byte[] buf2 = "ABC".getBytes();
            byte[] data1, data2;
            usb.write(buf1);
            data1 = telnet.read();
            telnet.write(buf2);
            data2 = usb.read();
            if (usb.serialDriver instanceof Cp21xxSerialDriver) {
                if (usb.serialDriver.getPorts().size() > 1) {
                    // supported on cp2105 first port
                    assertThat("42000/8N1", data1, equalTo(buf1));
                    assertThat("42000/8N1", data2, equalTo(buf2));
                } else {
                    // not supported on cp2102
                    assertNotEquals(data1, buf1);
                    assertNotEquals(data2, buf2);
                }
            } else {
                assertThat("42000/8N1", data1, equalTo(buf1));
                assertThat("42000/8N1", data2, equalTo(buf2));
            }
        }
        { // non matching baud rate
            telnet.setParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
            usb.setParameters(2400, 8, 1, UsbSerialPort.PARITY_NONE);

            byte[] data;
            telnet.write("net2usb".getBytes());
            data = usb.read();
            assertNotEquals(7, data.length);
            usb.write("usb2net".getBytes());
            data = telnet.read();
            assertNotEquals(7, data.length);
        }
    }

    @Test
    public void dataBits() throws Exception {
        byte[] data;

        usb.open();
        for(int i: new int[] {0, 4, 9}) {
            try {
                usb.setParameters(19200, i, 1, UsbSerialPort.PARITY_NONE);
                fail("invalid databits "+i);
            } catch (IllegalArgumentException ignored) {
            }
        }

        // telnet -> usb
        usb.setParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        telnet.setParameters(19200, 7, 1, UsbSerialPort.PARITY_NONE);
        telnet.write(new byte[] {0x00});
        Thread.sleep(10); // one bit is 0.05 milliseconds long, wait >> stop bit
        telnet.write(new byte[] {(byte)0xff});
        data = usb.read(2);
        assertThat("19200/7N1", data, equalTo(new byte[] {(byte)0x80, (byte)0xff}));

        telnet.setParameters(19200, 6, 1, UsbSerialPort.PARITY_NONE);
        telnet.write(new byte[] {0x00});
        Thread.sleep(10);
        telnet.write(new byte[] {(byte)0xff});
        data = usb.read(2);
        assertThat("19000/6N1", data, equalTo(new byte[] {(byte)0xc0, (byte)0xff}));

        telnet.setParameters(19200, 5, 1, UsbSerialPort.PARITY_NONE);
        telnet.write(new byte[] {0x00});
        Thread.sleep(10);
        telnet.write(new byte[] {(byte)0xff});
        data = usb.read(2);
        assertThat("19000/5N1", data, equalTo(new byte[] {(byte)0xe0, (byte)0xff}));

        // usb -> telnet
        try {
            telnet.setParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
            usb.setParameters(19200, 7, 1, UsbSerialPort.PARITY_NONE);
            usb.write(new byte[]{0x00});
            Thread.sleep(10);
            usb.write(new byte[]{(byte) 0xff});
            data = telnet.read(2);
            assertThat("19000/7N1", data, equalTo(new byte[]{(byte) 0x80, (byte) 0xff}));
        } catch (UnsupportedOperationException e) {
                if(!usb.isCp21xxRestrictedPort)
                    throw e;
        }
        try {
            usb.setParameters(19200, 6, 1, UsbSerialPort.PARITY_NONE);
            usb.write(new byte[]{0x00});
            Thread.sleep(10);
            usb.write(new byte[]{(byte) 0xff});
            data = telnet.read(2);
            assertThat("19000/6N1", data, equalTo(new byte[]{(byte) 0xc0, (byte) 0xff}));
        } catch (UnsupportedOperationException e) {
            if (!(usb.isCp21xxRestrictedPort || usb.serialDriver instanceof FtdiSerialDriver))
                throw e;
        }
        try {
            usb.setParameters(19200, 5, 1, UsbSerialPort.PARITY_NONE);
            usb.write(new byte[] {0x00});
            Thread.sleep(5);
            usb.write(new byte[] {(byte)0xff});
            data = telnet.read(2);
            assertThat("19000/5N1", data, equalTo(new byte[] {(byte)0xe0, (byte)0xff}));
        } catch (UnsupportedOperationException e) {
            if (!(usb.isCp21xxRestrictedPort || usb.serialDriver instanceof FtdiSerialDriver))
                throw e;
        }
    }

    @Test
    public void parity() throws Exception {
        byte[] _8n1 = {(byte)0x00, (byte)0x01, (byte)0xfe, (byte)0xff};
        byte[] _7n1 = {(byte)0x00, (byte)0x01, (byte)0x7e, (byte)0x7f};
        byte[] _7o1 = {(byte)0x80, (byte)0x01, (byte)0xfe, (byte)0x7f};
        byte[] _7e1 = {(byte)0x00, (byte)0x81, (byte)0x7e, (byte)0xff};
        byte[] _7m1 = {(byte)0x80, (byte)0x81, (byte)0xfe, (byte)0xff};
        byte[] _7s1 = {(byte)0x00, (byte)0x01, (byte)0x7e, (byte)0x7f};
        byte[] data;

        usb.open();
        for(int i: new int[] {-1, 5}) {
            try {
                usb.setParameters(19200, 8, 1, i);
                fail("invalid parity "+i);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if(usb.isCp21xxRestrictedPort) {
            usb.setParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
            usb.setParameters(19200, 8, 1, UsbSerialPort.PARITY_EVEN);
            usb.setParameters(19200, 8, 1, UsbSerialPort.PARITY_ODD);
            try {
                usb.setParameters(19200, 8, 1, UsbSerialPort.PARITY_MARK);
                fail("parity mark");
            } catch (UnsupportedOperationException ignored) {}
            try {
                usb.setParameters(19200, 8, 1, UsbSerialPort.PARITY_SPACE);
                fail("parity space");
            } catch (UnsupportedOperationException ignored) {}
            return;
            // test below not possible as it requires unsupported 7 dataBits
        }

        // usb -> telnet
        telnet.setParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        usb.setParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        usb.write(_8n1);
        data = telnet.read(4);
        assertThat("19200/8N1", data, equalTo(_8n1));

        usb.setParameters(19200, 7, 1, UsbSerialPort.PARITY_ODD);
        usb.write(_8n1);
        data = telnet.read(4);
        assertThat("19200/7O1", data, equalTo(_7o1));

        usb.setParameters(19200, 7, 1, UsbSerialPort.PARITY_EVEN);
        usb.write(_8n1);
        data = telnet.read(4);
        assertThat("19200/7E1", data, equalTo(_7e1));

        if (usb.serialDriver instanceof CdcAcmSerialDriver) {
            // not supported by arduino_leonardo_bridge.ino, other devices might support it
            usb.setParameters(19200, 7, 1, UsbSerialPort.PARITY_MARK);
            usb.setParameters(19200, 7, 1, UsbSerialPort.PARITY_SPACE);
        } else {
            usb.setParameters(19200, 7, 1, UsbSerialPort.PARITY_MARK);
            usb.write(_8n1);
            data = telnet.read(4);
            assertThat("19200/7M1", data, equalTo(_7m1));

            usb.setParameters(19200, 7, 1, UsbSerialPort.PARITY_SPACE);
            usb.write(_8n1);
            data = telnet.read(4);
            assertThat("19200/7S1", data, equalTo(_7s1));
        }

        // telnet -> usb
        usb.setParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        telnet.setParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        telnet.write(_8n1);
        data = usb.read(4);
        assertThat("19200/8N1", data, equalTo(_8n1));

        telnet.setParameters(19200, 7, 1, UsbSerialPort.PARITY_ODD);
        telnet.write(_8n1);
        data = usb.read(4);
        assertThat("19200/7O1", data, equalTo(_7o1));

        telnet.setParameters(19200, 7, 1, UsbSerialPort.PARITY_EVEN);
        telnet.write(_8n1);
        data = usb.read(4);
        assertThat("19200/7E1", data, equalTo(_7e1));

        if (usb.serialDriver instanceof CdcAcmSerialDriver) {
            // not supported by arduino_leonardo_bridge.ino, other devices might support it
        } else {
            telnet.setParameters(19200, 7, 1, UsbSerialPort.PARITY_MARK);
            telnet.write(_8n1);
            data = usb.read(4);
            assertThat("19200/7M1", data, equalTo(_7m1));

            telnet.setParameters(19200, 7, 1, UsbSerialPort.PARITY_SPACE);
            telnet.write(_8n1);
            data = usb.read(4);
            assertThat("19200/7S1", data, equalTo(_7s1));

            usb.setParameters(19200, 7, 1, UsbSerialPort.PARITY_ODD);
            telnet.setParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
            telnet.write(_8n1);
            data = usb.read(4);
            assertThat("19200/8N1", data, equalTo(_7n1)); // read is resilient against errors
        }
    }

    @Test
    public void stopBits() throws Exception {
        byte[] data;

        usb.open();
        for (int i : new int[]{0, 4}) {
            try {
                usb.setParameters(19200, 8, i, UsbSerialPort.PARITY_NONE);
                fail("invalid stopbits " + i);
            } catch (IllegalArgumentException ignored) {
            }
        }

        if (usb.serialDriver instanceof CdcAcmSerialDriver) {
            usb.setParameters(19200, 8, UsbSerialPort.STOPBITS_1_5, UsbSerialPort.PARITY_NONE);
            // software based bridge in arduino_leonardo_bridge.ino is to slow for real test, other devices might support it
        } else {
            // shift stopbits into next byte, by using different databits
            // a - start bit (0)
            // o - stop bit  (1)
            // d - data bit

            // out 8N2:   addddddd doaddddddddo
            //             1000001 0  10001111
            // in 6N1:    addddddo addddddo
            //             100000   101000
            usb.setParameters(19200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            telnet.setParameters(19200, 6, 1, UsbSerialPort.PARITY_NONE);
            usb.write(new byte[]{(byte)0x41, (byte)0xf1});
            data = telnet.read(2);
            assertThat("19200/8N1", data, equalTo(new byte[]{1, 5}));

            // out 8N2:   addddddd dooaddddddddoo
            //             1000001 0   10011111
            // in 6N1:    addddddo addddddo
            //             100000   110100
            try {
                usb.setParameters(19200, 8, UsbSerialPort.STOPBITS_2, UsbSerialPort.PARITY_NONE);
                telnet.setParameters(19200, 6, 1, UsbSerialPort.PARITY_NONE);
                usb.write(new byte[]{(byte) 0x41, (byte) 0xf9});
                data = telnet.read(2);
                assertThat("19200/8N1", data, equalTo(new byte[]{1, 11}));
            } catch(UnsupportedOperationException e) {
                if(!usb.isCp21xxRestrictedPort)
                    throw e;
            }
            try {
                usb.setParameters(19200, 8, UsbSerialPort.STOPBITS_1_5, UsbSerialPort.PARITY_NONE);
                // todo: could create similar test for 1.5 stopbits, by reading at double speed
                //       but only some devices support 1.5 stopbits and it is basically not used any more
            } catch(UnsupportedOperationException ignored) {
            }
        }
    }


    @Test
    public void probeTable() throws Exception {
        class DummyDriver implements UsbSerialDriver {
            @Override
            public UsbDevice getDevice() { return null; }
            @Override
            public List<UsbSerialPort> getPorts() { return null; }
        }
        List<UsbSerialDriver> availableDrivers;
        ProbeTable probeTable = new ProbeTable();
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        availableDrivers = new UsbSerialProber(probeTable).findAllDrivers(usbManager);
        assertEquals(0, availableDrivers.size());

        probeTable.addProduct(0, 0, DummyDriver.class);
        availableDrivers = new UsbSerialProber(probeTable).findAllDrivers(usbManager);
        assertEquals(0, availableDrivers.size());

        probeTable.addProduct(usb.serialDriver.getDevice().getVendorId(), usb.serialDriver.getDevice().getProductId(), usb.serialDriver.getClass());
        availableDrivers = new UsbSerialProber(probeTable).findAllDrivers(usbManager);
        assertEquals(1, availableDrivers.size());
        assertEquals(availableDrivers.get(0).getClass(), usb.serialDriver.getClass());
    }

    @Test
    public void writeSizes() throws Exception {
        assertNull(CommonUsbSerialPortWrapper.getWriteBuffer(usb.serialPort));
        ((CommonUsbSerialPort)usb.serialPort).setWriteBufferSize(12);
        assertEquals(12, CommonUsbSerialPortWrapper.getWriteBuffer(usb.serialPort).length);
        ((CommonUsbSerialPort)usb.serialPort).setWriteBufferSize(-1);
        ((CommonUsbSerialPort)usb.serialPort).setWriteBufferSize(-1);
        assertNull(CommonUsbSerialPortWrapper.getWriteBuffer(usb.serialPort));
        usb.open();
        ((CommonUsbSerialPort)usb.serialPort).setWriteBufferSize(12);
        assertEquals(12, CommonUsbSerialPortWrapper.getWriteBuffer(usb.serialPort).length);
        ((CommonUsbSerialPort)usb.serialPort).setWriteBufferSize(-1);
        ((CommonUsbSerialPort)usb.serialPort).setWriteBufferSize(-1);
        assertEquals(usb.serialPort.getWriteEndpoint().getMaxPacketSize(),
                     CommonUsbSerialPortWrapper.getWriteBuffer(usb.serialPort).length);
        assertEquals(usb.serialPort.getWriteEndpoint().getMaxPacketSize(),
                     usb.serialPort.getReadEndpoint().getMaxPacketSize());

        int baudRate = 300;
        if(usb.serialDriver instanceof Cp21xxSerialDriver && usb.serialDriver.getPorts().size() > 1)
            baudRate = 2400;
        usb.setParameters(baudRate, 8, 1, UsbSerialPort.PARITY_NONE);
        telnet.setParameters(baudRate, 8, 1, UsbSerialPort.PARITY_NONE);
        int purgeTimeout = 250;
        if(usb.serialDriver instanceof CdcAcmSerialDriver)
            purgeTimeout = 500;
        purgeWriteBuffer(purgeTimeout);

        // determine write buffer size
        int writePacketSize = usb.serialPort.getWriteEndpoint().getMaxPacketSize();
        byte[] pbuf = new byte[writePacketSize];
        int writePackets = 0;
        try {
            for (writePackets = 0; writePackets < 64; writePackets++)
                usb.serialPort.write(pbuf, 1);
            fail("write error expected");
        } catch(IOException ignored) {}
        purgeWriteBuffer(purgeTimeout);

        int writeBufferSize = writePacketSize * writePackets;
        Log.d(TAG, "write packet size = " + writePacketSize + ", write buffer size = " + writeBufferSize);
        assertEquals("write packet size", usb.writePacketSize, writePacketSize);
        if (usb.serialDriver instanceof Cp21xxSerialDriver && usb.serialDriver.getPorts().size() == 1)  // write buffer size detection is unreliable
            assertTrue("write buffer size " + writeBufferSize, writeBufferSize == usb.writeBufferSize || writeBufferSize == usb.writeBufferSize + 64);
        else
            assertEquals("write buffer size", usb.writeBufferSize, writeBufferSize);
    }

    @Test
    public void writeTimeout() throws Exception {
        // serial processing to slow for tests below, but they anyway only check shared code in CommonUsbSerialPort
        Assume.assumeFalse(usb.serialDriver instanceof CdcAcmSerialDriver);
        // write buffer size detection unreliable as baud rate to high
        Assume.assumeFalse(usb.serialDriver instanceof Cp21xxSerialDriver && usb.serialDriver.getPorts().size() > 1);

        usb.open();
        usb.setParameters(9600, 8, 1, UsbSerialPort.PARITY_NONE);
        telnet.setParameters(9600, 8, 1, UsbSerialPort.PARITY_NONE);
        TestBuffer tbuf;
        int purgeTimeout = 250;

        // total write timeout
        tbuf = new TestBuffer(usb.writeBufferSize + usb.writePacketSize);
        int timeout = usb.writePacketSize / 32 * 50; // time for 1.5 packets. write 48 byte in 50 msec at 9600 baud
        usb.serialPort.write(tbuf.buf, timeout);
        purgeWriteBuffer(purgeTimeout);
        tbuf = new TestBuffer(usb.writeBufferSize + 2*usb.writePacketSize);
        try {
            usb.serialPort.write(tbuf.buf, timeout); // would not fail if each block has own timeout
            fail("write error expected");
        } catch(SerialTimeoutException ignored) {}
        purgeWriteBuffer(purgeTimeout);

        // infinite wait
        usb.serialPort.write(tbuf.buf, 0);
        purgeWriteBuffer(purgeTimeout);

        // timeout in bulkTransfer + SerialTimeoutException.bytesTransferred
        int readWait = usb.writePacketSize > 64 ? 250 : 50;
        ((CommonUsbSerialPort)usb.serialPort).setWriteBufferSize(tbuf.buf.length);
        try {
            usb.serialPort.write(tbuf.buf, timeout);
            fail("write error expected");
        } catch(SerialTimeoutException ex) {
            assertTrue(ex.getMessage(), ex.getMessage().endsWith("rc=-1")); // timeout in bulkTransfer
            for(byte[] data = telnet.read(-1, readWait); data.length != 0;
                       data = telnet.read(-1, readWait)) {
                tbuf.testRead(data);
            }
            assertEquals(0, ex.bytesTransferred);
            assertEquals(usb.writeBufferSize + usb.writePacketSize, tbuf.len);
        }
        purgeWriteBuffer(purgeTimeout);
        ((CommonUsbSerialPort)usb.serialPort).setWriteBufferSize(-1);
        tbuf.len = 0;
        try {
            usb.serialPort.write(tbuf.buf, timeout);
            fail("write error expected");
        } catch(SerialTimeoutException ex) {
            assertTrue(ex.getMessage(), ex.getMessage().endsWith("rc=-1")); // timeout in bulkTransfer
            for(byte[] data = telnet.read(-1, readWait); data.length != 0;
                       data = telnet.read(-1, readWait)) {
                tbuf.testRead(data);
            }
            assertEquals(usb.writeBufferSize + usb.writePacketSize, ex.bytesTransferred);
            assertEquals(usb.writeBufferSize + usb.writePacketSize, tbuf.len);
        }
        purgeWriteBuffer(purgeTimeout);

        // timeout in library
        timeout = 1;
        try {
            usb.serialPort.write(tbuf.buf, timeout);
            fail("write error expected");
        } catch (SerialTimeoutException ex) {
            assertTrue(ex.getMessage(), ex.getMessage().endsWith("rc=-2")); // timeout in library
        }
        purgeWriteBuffer(purgeTimeout);
    }

    @Test
    // compare write duration.
    //
    // multiple packet sized writes typically take 2-3X time of single full buffer write.
    // here some typical durations:
    //          full    packet [msec]
    // Prolific 4       8
    // Cp2102   3       10
    // CP2105   1.x     2-3
    // FT232    1.5-2   2-3
    // Ch34x    1.x     2-3
    // CDC      1.x     2-3
    public void writeDuration() throws Exception {
        usb.open();
        usb.setParameters(9600, 8, 1, UsbSerialPort.PARITY_NONE);
        telnet.setParameters(9600, 8, 1, UsbSerialPort.PARITY_NONE);

        boolean purge = true;
        try {
            usb.serialPort.purgeHwBuffers(true, false);
        } catch(Exception ignored) {
            purge = false;
        }
        if(usb.serialDriver instanceof Cp21xxSerialDriver && usb.serialDriver.getPorts().size() == 1)
            purge = false; // purge is blocking

        int purgeTimeout = 250;
        TestBuffer tbuf;
        long begin;
        int duration1, duration2, retries, i;
        retries = purge ? 10 : 1;
        tbuf = new TestBuffer(usb.writeBufferSize);

        ((CommonUsbSerialPort) usb.serialPort).setWriteBufferSize(tbuf.buf.length);
        Log.d(TAG, "writeDuration: full write begin");
        begin = System.currentTimeMillis();
        for(i=0; i<retries; i++) {
            usb.serialPort.write(tbuf.buf, 0);
            if(purge)
                usb.serialPort.purgeHwBuffers(true, false);
        }
        duration1 = (int)(System.currentTimeMillis() - begin);
        if(!purge)
            purgeWriteBuffer(purgeTimeout);
        Log.d(TAG, "writeDuration: full write end, duration " + duration1/(float)(retries) + " msec");
        ((CommonUsbSerialPort) usb.serialPort).setWriteBufferSize(-1);
        Log.d(TAG, "writeDuration: packet write begin");
        begin = System.currentTimeMillis();
        for(i=0; i<retries; i++) {
            usb.serialPort.write(tbuf.buf, 0);
            if(purge)
                usb.serialPort.purgeHwBuffers(true, false);
        }
        duration2 = (int)(System.currentTimeMillis() - begin);
        purgeWriteBuffer(purgeTimeout);
        Log.d(TAG, "writeDuration: packet write end, duration " + duration2/(float)(retries) + " msec");
        assertTrue("full duration " + duration1 + ", packet duration " + duration2, duration1 < duration2);
        assertTrue("full duration " + duration1 + ", packet duration " + duration2, duration2 < 5*duration1);
    }

    @Test
    public void writeFragments() throws Exception {
        usb.open();
        usb.setParameters(115200, 8, 1, UsbSerialPort.PARITY_NONE);
        telnet.setParameters(115200, 8, 1, UsbSerialPort.PARITY_NONE);

        ((CommonUsbSerialPort) usb.serialPort).setWriteBufferSize(12); // init buffer
        ((CommonUsbSerialPort) usb.serialPort).setWriteBufferSize(12); // keeps last buffer
        TestBuffer buf = new TestBuffer(256);
        usb.serialPort.write(buf.buf, 5000);
        while (!buf.testRead(telnet.read(-1)))
            ;
    }

    @Test
    public void readBufferSize() throws Exception {
        // looks like devices perform USB read with full mReadEndpoint.getMaxPacketSize() size (32, 64, 512)
        // if the buffer is smaller than the received result, it is silently lost
        //
        // for buffer > packet size, but not multiple of packet size, the same issue happens, but typically
        // only the last (partly filled) packet is lost.
        if(usb.serialDriver instanceof CdcAcmSerialDriver)
            return; // arduino sends each byte individually, so not testable here
        byte[] data;
        boolean purge = true;

        usb.open(EnumSet.of(UsbWrapper.OpenCloseFlags.NO_IOMANAGER_START));
        usb.ioManager.setReadBufferSize(8);
        usb.ioManager.start();
        usb.setParameters(115200, 8, 1, UsbSerialPort.PARITY_NONE);
        telnet.setParameters(115200, 8, 1, UsbSerialPort.PARITY_NONE);
        try { usb.serialPort.purgeHwBuffers(true, true); } catch(Exception ignored) { purge = false; }

        telnet.write("1aaa".getBytes());
        data = usb.read(4);
        Assert.assertThat(data, equalTo("1aaa".getBytes()));
        telnet.write(new byte[16]);
        try {
            data = usb.read(16);
            if (usb.serialDriver instanceof Cp21xxSerialDriver && usb.serialDriver.getPorts().size() == 1)
                Assert.assertNotEquals(0, data.length); // can be shorter or full length
            else if (usb.serialDriver instanceof ProlificSerialDriver)
                Assert.assertTrue("expected > 0 and < 16 byte, got " + data.length, data.length > 0 && data.length < 16);
            else // ftdi, ch340, cp2105
                Assert.assertEquals(0, data.length);
        } catch (IOException ignored) {
        }
        if (purge) {
            usb.serialPort.purgeHwBuffers(true, true);
        } else {
            usb.close();
            usb.open();
            Thread.sleep(100); // try to read remaining data by iomanager to avoid garbage in next test
        }

        usb.close();
        usb.open(EnumSet.of(UsbWrapper.OpenCloseFlags.NO_IOMANAGER_THREAD));
        usb.setParameters(115200, 8, 1, UsbSerialPort.PARITY_NONE);
        telnet.setParameters(115200, 8, 1, UsbSerialPort.PARITY_NONE);

        try {
            usb.serialPort.read(new byte[0], 0);
            fail("IllegalArgumentException expected");
        } catch (IllegalArgumentException ignored) {}
        try {
            usb.serialPort.read(new byte[0], 100);
            fail("IllegalArgumentException expected");
        } catch (IllegalArgumentException ignored) {}
        if (usb.serialDriver instanceof FtdiSerialDriver) {
            try {
                usb.serialPort.read(new byte[2], 0);
                fail("IllegalArgumentException expected");
            } catch (IllegalArgumentException ignored) {}
            try {
                usb.serialPort.read(new byte[2], 100);
                fail("IllegalArgumentException expected");
            } catch (IllegalArgumentException ignored) {}
        }
        
        telnet.write("2aaa".getBytes());
        data = usb.read(4, 8);
        Assert.assertThat(data, equalTo("2aaa".getBytes()));
        telnet.write(new byte[16]);
        data = usb.read(16, 8);
        if (usb.serialDriver instanceof Cp21xxSerialDriver && usb.serialDriver.getPorts().size() == 1)
            Assert.assertNotEquals(0, data.length); // can be shorter or full length
        else if (usb.serialDriver instanceof ProlificSerialDriver)
            Assert.assertTrue("sporadic issue! expected > 0 and < 16 byte, got " + data.length, data.length > 0 && data.length < 16);
        else // ftdi, ch340, cp2105
            Assert.assertEquals(0, data.length);
        telnet.write("2ccc".getBytes());
        data = usb.read(4);
        // Assert.assertThat(data, equalTo("1ccc".getBytes())); // unpredictable here. typically '2ccc' but sometimes '' or byte[16]
        if(data.length != 4) {
            if (purge) {
                usb.serialPort.purgeHwBuffers(true, true);
            } else {
                usb.close();
                usb.open();
                Thread.sleep(100); // try to read remaining data by iomanager to avoid garbage in next test
            }
        }
    }

    @Test
    // provoke data loss, when data is not read fast enough
    public void readBufferOverflow() throws Exception {
        if(usb.serialDriver instanceof CdcAcmSerialDriver)
            telnet.writeDelay = 10; // arduino_leonardo_bridge.ino sends each byte in own USB packet, which is horribly slow
        usb.open();
        usb.setParameters(115200, 8, 1, UsbSerialPort.PARITY_NONE);
        telnet.setParameters(115200, 8, 1, UsbSerialPort.PARITY_NONE);

        StringBuilder expected = new StringBuilder();
        StringBuilder data = new StringBuilder();
        final int maxWait = 2000;
        int bufferSize;
        for(bufferSize = 8; bufferSize < (2<<15); bufferSize *= 2) {
            int linenr;
            String line="-";
            expected.setLength(0);
            data.setLength(0);

            Log.i(TAG, "bufferSize " + bufferSize);
            usb.readBlock = true;
            for (linenr = 0; linenr < bufferSize/8; linenr++) {
                line = String.format("%07d,", linenr);
                telnet.write(line.getBytes());
                expected.append(line);
            }
            usb.readBlock = false;

            // slowly write new data, until old data is completely read from buffer and new data is received
            boolean found = false;
            for (; linenr < bufferSize/8 + maxWait/10 && !found; linenr++) {
                line = String.format("%07d,", linenr);
                telnet.write(line.getBytes());
                Thread.sleep(10);
                expected.append(line);
                data.append(new String(usb.read(0)));
                found = data.toString().endsWith(line);
            }
            while(!found) {
                // use waiting read to clear input queue, else next test would see unexpected data
                byte[] rest = usb.read(-1);
                if(rest.length == 0)
                    fail("last line "+line+" not found");
                data.append(new String(rest));
                found = data.toString().endsWith(line);
            }
            if (data.length() != expected.length())
                break;
        }

        findDifference(data, expected);
        assertTrue(bufferSize > 16);
        assertTrue(data.length() != expected.length());
    }

    @Test
    public void readSpeed() throws Exception {
        // see logcat for performance results
        //
        // CDC arduino_leonardo_bridge.ino has transfer speed ~ 100 byte/sec
        // all other devices are near physical limit with ~ 10-12k/sec
        //
        // readBufferOverflow provokes read errors, but they can also happen here where the data is actually read fast enough.
        // Android is not a real time OS, so there is no guarantee that the USB thread is scheduled, or it might be blocked by Java garbage collection.
        // Using SERIAL_INPUT_OUTPUT_MANAGER_THREAD_PRIORITY=THREAD_PRIORITY_URGENT_AUDIO sometimes reduced errors by factor 10, sometimes not at all!
        //
        int diffLen = readSpeedInt(5, -1, 0);
        if(usb.serialDriver instanceof Ch34xSerialDriver && diffLen == -1)
             diffLen = 0; // todo: investigate last packet loss
        assertEquals(0, diffLen);
    }

    private int readSpeedInt(int writeSeconds, int readBufferSize, int readTimeout) throws Exception {
        int baudrate = 115200;
        if(usb.serialDriver instanceof Ch34xSerialDriver)
            baudrate = 38400;
        int writeAhead = 5*baudrate/10; // write ahead for another 5 second read
        if(usb.serialDriver instanceof CdcAcmSerialDriver)
            writeAhead = 50;

        usb.open(EnumSet.of(UsbWrapper.OpenCloseFlags.NO_IOMANAGER_START));
        usb.ioManager.setReadTimeout(readTimeout);
        if(readBufferSize > 0)
            usb.ioManager.setReadBufferSize(readBufferSize);
        usb.ioManager.start();
        usb.setParameters(baudrate, 8, 1, UsbSerialPort.PARITY_NONE);
        telnet.setParameters(baudrate, 8, 1, UsbSerialPort.PARITY_NONE);

        int linenr = 0;
        String line="";
        StringBuilder data = new StringBuilder();
        StringBuilder expected = new StringBuilder();
        int dlen = 0, elen = 0;
        Log.i(TAG, "readSpeed: 'read' should be near "+baudrate/10);
        long begin = System.currentTimeMillis();
        long next = System.currentTimeMillis();
        for(int seconds=1; seconds <= writeSeconds; seconds++) {
            next += 1000;
            while (System.currentTimeMillis() < next) {
                if((writeAhead < 0) || (expected.length() < data.length() + writeAhead)) {
                    line = String.format("%07d,", linenr++);
                    telnet.write(line.getBytes());
                    expected.append(line);
                } else {
                    Thread.sleep(0, 100000);
                }
                data.append(new String(usb.read(0)));
            }
            Log.i(TAG, "readSpeed: t="+(next-begin)+", read="+(data.length()-dlen)+", write="+(expected.length()-elen));
            dlen = data.length();
            elen = expected.length();
        }

        boolean found = false;
        while(!found) {
            // use waiting read to clear input queue, else next test would see unexpected data
            byte[] rest = usb.read(-1);
            if(rest.length == 0)
                break;
            data.append(new String(rest));
            found = data.toString().endsWith(line);
        }
        return findDifference(data, expected);
    }

    @Test
    public void writeSpeed() throws Exception {
        // see logcat for performance results
        //
        // CDC arduino_leonardo_bridge.ino has transfer speed ~ 100 byte/sec
        // all other devices can get near physical limit:
        // longlines=true:, speed is near physical limit at 11.5k
        // longlines=false: speed is 3-4k for all devices, as more USB packets are required
        usb.open();
        usb.setParameters(115200, 8, 1, UsbSerialPort.PARITY_NONE);
        telnet.setParameters(115200, 8, 1, UsbSerialPort.PARITY_NONE);
        boolean longlines = !(usb.serialDriver instanceof CdcAcmSerialDriver);

        int linenr = 0;
        String line="";
        StringBuilder data = new StringBuilder();
        StringBuilder expected = new StringBuilder();
        int dlen = 0, elen = 0;
        Log.i(TAG, "writeSpeed: 'write' should be near "+115200/10);
        long begin = System.currentTimeMillis();
        long next = System.currentTimeMillis();
        for(int seconds=1; seconds<=5; seconds++) {
            next += 1000;
            while (System.currentTimeMillis() < next) {
                if(longlines)
                    line = String.format("%060d,", linenr++);
                else
                    line = String.format("%07d,", linenr++);
                usb.write(line.getBytes());
                expected.append(line);
                data.append(new String(telnet.read(0)));
            }
            Log.i(TAG, "writeSpeed: t="+(next-begin)+", write="+(expected.length()-elen)+", read="+(data.length()-dlen));
            dlen = data.length();
            elen = expected.length();
        }
        boolean found = false;
        for (linenr=0; linenr < 2000 && !found; linenr++) {
            data.append(new String(telnet.read(0)));
            Thread.sleep(1);
            found = data.toString().endsWith(line);
        }
        next = System.currentTimeMillis();
        Log.i(TAG, "writeSpeed: t="+(next-begin)+", read="+(data.length()-dlen));
        assertTrue(found);
        int pos = indexOfDifference(data, expected);
        if(pos!=-1) {

            Log.i(TAG, "writeSpeed: first difference at " + pos);
            String datasub     =     data.substring(Math.max(pos - 20, 0), Math.min(pos + 20, data.length()));
            String expectedsub = expected.substring(Math.max(pos - 20, 0), Math.min(pos + 20, expected.length()));
            assertThat(datasub, equalTo(expectedsub));
        }
    }

    @Test
    public void purgeHwBuffers() throws Exception {
        // purge write buffer
        // 2400 is slowest baud rate for usb.isCp21xxRestrictedPort
        usb.open();
        usb.setParameters(2400, 8, 1, UsbSerialPort.PARITY_NONE);
        telnet.setParameters(2400, 8, 1, UsbSerialPort.PARITY_NONE);
        byte[] buf = new byte[64];
        Arrays.fill(buf, (byte) 'a');
        StringBuilder data = new StringBuilder();

        usb.write(buf);
        Thread.sleep(50); // ~ 12 bytes
        boolean purged;
        try {
            usb.serialPort.purgeHwBuffers(true, false);
            purged = true;
        } catch (UnsupportedOperationException ex) {
            purged = false;
        }
        usb.write("bcd".getBytes());
        Thread.sleep(50);
        while(data.length()==0 || data.charAt(data.length()-1)!='d')
            data.append(new String(telnet.read()));
        Log.i(TAG, "purgeHwBuffers " + purged + ": " + (buf.length+3) + " -> " + data.length());

        assertTrue(data.length() > 5);
        if(purged) {
            if(usb.serialDriver instanceof Cp21xxSerialDriver && usb.serialDriver.getPorts().size() == 1) // only working on some devices/ports
                assertTrue(data.length() < buf.length + 1 || data.length() == buf.length + 3);
            else
                assertTrue(data.length() < buf.length + 1);
        } else {
            assertEquals(data.length(), buf.length + 3);
        }

        // purge read buffer
        usb.close();
        usb.open(EnumSet.of(UsbWrapper.OpenCloseFlags.NO_IOMANAGER_THREAD));
        usb.setParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        telnet.setParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        telnet.write("x".getBytes());
        Thread.sleep(10); // ~ 20 bytes
        if(purged)
            usb.serialPort.purgeHwBuffers(false, true);
        Log.d(TAG, "purged = " + purged);
        telnet.write("y".getBytes());
        Thread.sleep(10); // ~ 20 bytes
        if(purged) {
            if(usb.serialDriver instanceof Cp21xxSerialDriver) { // only working on some devices/ports
                if(usb.isCp21xxRestrictedPort) {
                    assertThat(usb.read(2), equalTo("xy".getBytes())); // cp2105/1
                } else if(usb.serialDriver.getPorts().size() > 1) {
                    assertThat(usb.read(1), equalTo("y".getBytes()));  // cp2105/0
                } else {
                    assertThat(usb.read(2), anyOf(equalTo("xy".getBytes()), // cp2102
                                                                equalTo("y".getBytes()))); // cp2102
                }
            } else {
                assertThat(usb.read(1), equalTo("y".getBytes()));
            }
        } else {
            assertThat(usb.read(2), equalTo("xy".getBytes()));
        }
    }

    @Test
    public void IoManager() throws Exception {
        SerialInputOutputManager.DEBUG = true;
        usb.open(EnumSet.of(UsbWrapper.OpenCloseFlags.NO_IOMANAGER_THREAD));
        assertNull(usb.ioManager);
        usb.ioManager = new SerialInputOutputManager(usb.serialPort);
        assertNull(usb.ioManager.getListener());
        usb.ioManager.setListener(usb);
        assertEquals(usb, usb.ioManager.getListener());
        usb.ioManager = new SerialInputOutputManager(usb.serialPort, usb);
        assertEquals(usb, usb.ioManager.getListener());

        assertEquals(0, usb.ioManager.getReadTimeout());
        usb.ioManager.setReadTimeout(10);
        assertEquals(10, usb.ioManager.getReadTimeout());
        assertEquals(0, usb.ioManager.getWriteTimeout());
        usb.ioManager.setWriteTimeout(11);
        assertEquals(11, usb.ioManager.getWriteTimeout());

        assertEquals(usb.serialPort.getReadEndpoint().getMaxPacketSize(), usb.ioManager.getReadBufferSize());
        usb.ioManager.setReadBufferSize(12);
        assertEquals(12, usb.ioManager.getReadBufferSize());
        assertEquals(4096, usb.ioManager.getWriteBufferSize());
        usb.ioManager.setWriteBufferSize(13);
        assertEquals(13, usb.ioManager.getWriteBufferSize());

        usb.ioManager.setReadBufferSize(usb.ioManager.getReadBufferSize());
        usb.ioManager.setWriteBufferSize(usb.ioManager.getWriteBufferSize());
        usb.ioManager.setReadTimeout(usb.ioManager.getReadTimeout());
        usb.ioManager.setWriteTimeout(usb.ioManager.getWriteTimeout());
        usb.close();

        usb.open(EnumSet.of(UsbWrapper.OpenCloseFlags.NO_IOMANAGER_START)); // creates new IoManager
        usb.setParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        telnet.setParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        usb.ioManager.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
        usb.ioManager.start();
        usb.waitForIoManagerStarted();
        assertTrue("iomanager thread", usb.hasIoManagerThread());
        try {
            usb.ioManager.start();
            fail("already running error expected");
        } catch (IllegalStateException ignored) {
        }
        try {
            usb.ioManager.run();
            fail("already running error expected");
        } catch (IllegalStateException ignored) {
        }
        try {
            usb.ioManager.setThreadPriority(Process.THREAD_PRIORITY_LOWEST);
            fail("setThreadPriority IllegalStateException expected");
        } catch (IllegalStateException ignored) {}
        try {
            usb.ioManager.setReadTimeout(20);
            fail("setReadTimeout IllegalStateException expected");
        } catch (IllegalStateException ignored) {}
        assertEquals(0, usb.ioManager.getReadTimeout());
        usb.ioManager.setWriteTimeout(21);
        assertEquals(21, usb.ioManager.getWriteTimeout());
        usb.ioManager.setReadBufferSize(22);
        assertEquals(22, usb.ioManager.getReadBufferSize());
        usb.ioManager.setWriteBufferSize(23);
        assertEquals(23, usb.ioManager.getWriteBufferSize());

        // readbuffer resize
        telnet.write(new byte[1]);
        usb.ioManager.setReadBufferSize(64);
        Log.d(TAG, "setReadBufferSize(64)");
        telnet.write(new byte[1]); // still uses old buffer as infinite waiting step() holds reference to buffer
        telnet.write(new byte[1]); // now uses 8 byte buffer
        usb.read(3);

        // writebuffer resize
        try {
            usb.ioManager.writeAsync(new byte[8192]);
            fail("expected BufferOverflowException");
        } catch (BufferOverflowException ignored) {}

        usb.ioManager.setWriteBufferSize(16);
        usb.ioManager.writeAsync("1234567890AB".getBytes());
        try {
            usb.ioManager.setWriteBufferSize(8);
            fail("expected BufferOverflowException");
        } catch (BufferOverflowException ignored) {}
        usb.ioManager.setWriteBufferSize(24); // pending date copied to new buffer
        telnet.write("a".getBytes());
        assertThat(usb.read(1), equalTo("a".getBytes()));
        assertThat(telnet.read(12), equalTo("1234567890AB".getBytes()));

        // small readbuffer
        usb.ioManager.setReadBufferSize(8);
        Log.d(TAG, "setReadBufferSize(8)");
        telnet.write("b".getBytes());
        assertThat(usb.read(1), equalTo("b".getBytes()));
        // now new buffer is used
        telnet.write("c".getBytes());
        assertThat(usb.read(1), equalTo("c".getBytes()));
        telnet.write("d".getBytes());
        assertThat(usb.read(1), equalTo("d".getBytes()));

        usb.close();
        for (int i = 0; i < 100 && usb.hasIoManagerThread(); i++) {
            Thread.sleep(1);
        }
        assertFalse("iomanager thread", usb.hasIoManagerThread());
        SerialInputOutputManager.DEBUG = false;

        // legacy start
        usb.open(EnumSet.of(UsbWrapper.OpenCloseFlags.NO_IOMANAGER_START)); // creates new IoManager
        usb.setParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        telnet.setParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        usb.ioManager.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
        Executors.newSingleThreadExecutor().submit(usb.ioManager);
        usb.waitForIoManagerStarted();
        try {
            usb.ioManager.start();
            fail("already running error expected");
        } catch (IllegalStateException ignored) {
        }
    }

    @Test
    public void writeAsync() throws Exception {
        byte[] data, buf = new byte[]{1};

        // w/o timeout: write delayed until something is read
        usb.open();
        usb.setParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        telnet.setParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        usb.ioManager.writeAsync(buf);
        usb.ioManager.writeAsync(buf);
        data = telnet.read(1);
        assertEquals(0, data.length);
        telnet.write(buf);
        data = usb.read(1);
        assertEquals(1, data.length);
        data = telnet.read(2);
        assertEquals(2, data.length);
        usb.close();

        // with timeout: write after timeout
        usb.open(EnumSet.of(UsbWrapper.OpenCloseFlags.NO_IOMANAGER_START));
        usb.ioManager.setReadTimeout(100);
        usb.ioManager.start();
        usb.setParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        telnet.setParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        usb.ioManager.writeAsync(buf);
        usb.ioManager.writeAsync(buf);
        data = telnet.read(2);
        assertEquals(2, data.length);
        usb.ioManager.setReadTimeout(200);
    }

    @Test
    public void readTimeout() throws Exception {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        ScheduledFuture<?> future;
        byte[] writeBuf = new byte[]{1};
        byte[] readBuf = new byte[1];
        if (usb.serialDriver instanceof FtdiSerialDriver)
            readBuf = new byte[3]; // include space for 2 header bytes
        int len,i,j;
        long time;

        usb.open(EnumSet.of(UsbWrapper.OpenCloseFlags.NO_IOMANAGER_THREAD));
        usb.setParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        telnet.setParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);

        // w/o timeout
        telnet.write(writeBuf);
        len = usb.serialPort.read(readBuf, 0); // not blocking because data is available
        assertEquals(1, len);

        time = System.currentTimeMillis();
        future = scheduler.schedule(() -> usb.close(), 100, TimeUnit.MILLISECONDS);
        try {
            len = usb.serialPort.read(readBuf, 0); // blocking until close()
            assertEquals(0, len);
        } catch (IOException ignored) {
            // typically no exception as read request canceled at the beginning of close()
            // and most cases the connection is still valid in testConnection()
        } catch (Exception ignored) {
            // can fail with NPE if connection is closed between closed check and queueing/waiting for request
        }
        assertTrue(System.currentTimeMillis()-time >= 100);
        future.get(); // wait until close finished
        scheduler.shutdown();

        // with timeout
        usb.open(EnumSet.of(UsbWrapper.OpenCloseFlags.NO_IOMANAGER_THREAD));
        usb.setParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        telnet.setParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);

        int longTimeout = 1000;
        int shortTimeout = 10;
        time = System.currentTimeMillis();
        len = usb.serialPort.read(readBuf, shortTimeout);
        assertEquals(0, len);
        assertTrue(System.currentTimeMillis()-time < 100);

        // no issue with slow transfer rate and short read timeout
        time = System.currentTimeMillis();
        for(i=0; i<50; i++) {
            Thread.sleep(10);
            telnet.write(writeBuf);
            Log.d(TAG,"telnet write 1");
            for(j=0; j<20; j++) {
                len = usb.serialPort.read(readBuf, shortTimeout);
                if (len > 0)
                    break;
            }
            assertEquals("failed after " + i, 1, len);
        }
        Log.i(TAG, "average time per read " + (System.currentTimeMillis()-time)/i + " msec");

        if(!(usb.serialDriver instanceof CdcAcmSerialDriver)) {
            int diffLen;
            usb.close();
            // no issue with high transfer rate and long read timeout
            diffLen = readSpeedInt(5, -1, longTimeout);
            if(usb.serialDriver instanceof Ch34xSerialDriver && diffLen == -1)
                diffLen = 0; // todo: investigate last packet loss
            assertEquals(0, diffLen);
            usb.close();
            // date loss with high transfer rate and short read timeout !!!
            diffLen = readSpeedInt(5, -1, shortTimeout);

            assertNotEquals("sporadic issue!", 0, diffLen);

            // data loss observed with read timeout up to 200 msec, e.g.
            //  difference at 181 len 64
            //        got 000020,0000021,0000030,0000031,0000032,0
            //   expected 000020,0000021,0000022,0000023,0000024,0
            // difference at 341 len 128
            //        got 000048,0000049,0000066,0000067,0000068,0
            //   expected 000048,0000049,0000050,0000051,0000052,0
            // difference at 724 len 704
            //        got 0000112,0000113,0000202,0000203,0000204,
            //   expected 0000112,0000113,0000114,0000115,0000116,
            // difference at 974 len 8
            //        got 00231,0000232,0000234,0000235,0000236,00
            //   expected 00231,0000232,0000233,0000234,0000235,00
        }
    }

    @Test
    public void wrongDriver() throws Exception {

        UsbDeviceConnection wrongDeviceConnection;
        UsbSerialDriver wrongSerialDriver;
        UsbSerialPort wrongSerialPort;

        if(!(usb.serialDriver instanceof CdcAcmSerialDriver)) {
            wrongDeviceConnection = usbManager.openDevice(usb.serialDriver.getDevice());
            wrongSerialDriver = new CdcAcmSerialDriver(usb.serialDriver.getDevice());
            wrongSerialPort = wrongSerialDriver.getPorts().get(0);
            try {
                wrongSerialPort.open(wrongDeviceConnection);
                wrongSerialPort.setParameters(115200, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE); // ch340 fails here
                wrongSerialPort.write(new byte[]{1}, 1000); // pl2302 does not fail, but sends with wrong baud rate
                if(!(usb.serialDriver instanceof ProlificSerialDriver))
                    fail("error expected");
            } catch (IOException ignored) {
            }
            try {
                if(usb.serialDriver instanceof ProlificSerialDriver) {
                    assertNotEquals(new byte[]{1}, telnet.read());
                }
                wrongSerialPort.close();
                if(!(usb.serialDriver instanceof Ch34xSerialDriver |
                     usb.serialDriver instanceof ProlificSerialDriver))
                    fail("error expected");
            } catch (IOException ignored) {
            }
        }
        if(!(usb.serialDriver instanceof Ch34xSerialDriver)) {
            wrongDeviceConnection = usbManager.openDevice(usb.serialDriver.getDevice());
            wrongSerialDriver = new Ch34xSerialDriver(usb.serialDriver.getDevice());
            wrongSerialPort = wrongSerialDriver.getPorts().get(0);
            try {
                wrongSerialPort.open(wrongDeviceConnection);
                fail("error expected");
            } catch (IOException ignored) {
            }
            try {
                wrongSerialPort.close();
                fail("error expected");
            } catch (IOException ignored) {
            }
        }
        // FTDI only recovers from Cp21xx control commands with power toggle, so skip this combination!
        if(!(usb.serialDriver instanceof Cp21xxSerialDriver | usb.serialDriver instanceof FtdiSerialDriver)) {
            wrongDeviceConnection = usbManager.openDevice(usb.serialDriver.getDevice());
            wrongSerialDriver = new Cp21xxSerialDriver(usb.serialDriver.getDevice());
            wrongSerialPort = wrongSerialDriver.getPorts().get(0);
            try {
                wrongSerialPort.open(wrongDeviceConnection);
                //if(usb.usbSerialDriver instanceof FtdiSerialDriver)
                //    wrongSerialPort.setParameters(115200, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE); // ch340 fails here
                fail("error expected");
            } catch (IOException ignored) {
            }
            try {
                wrongSerialPort.close();
                //if(!(usb.usbSerialDriver instanceof FtdiSerialDriver))
                //    fail("error expected");
            } catch (IOException ignored) {
            }
        }
        if(!(usb.serialDriver instanceof FtdiSerialDriver)) {
            wrongDeviceConnection = usbManager.openDevice(usb.serialDriver.getDevice());
            wrongSerialDriver = new FtdiSerialDriver(usb.serialDriver.getDevice());
            wrongSerialPort = wrongSerialDriver.getPorts().get(0);
            try {
                wrongSerialPort.open(wrongDeviceConnection);
                if(usb.serialDriver instanceof Cp21xxSerialDriver)
                    wrongSerialPort.setParameters(115200, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE); // ch340 fails here
                //fail("error expected"); // only fails on some devices
            } catch (IOException ignored) {
            }
            try {
                wrongSerialPort.close();
                if(!(usb.serialDriver instanceof Cp21xxSerialDriver))
                    fail("error expected");
            } catch (IOException ignored) {
            }
        }
        if(!(usb.serialDriver instanceof ProlificSerialDriver)) {
            wrongDeviceConnection = usbManager.openDevice(usb.serialDriver.getDevice());
            wrongSerialDriver = new ProlificSerialDriver(usb.serialDriver.getDevice());
            wrongSerialPort = wrongSerialDriver.getPorts().get(0);
            try {
                wrongSerialPort.open(wrongDeviceConnection);
                fail("error expected");
            } catch (IOException ignored) {
            }
            try {
                wrongSerialPort.close();
                fail("error expected");
            } catch (IOException ignored) {
            }
        }
        // test that device recovers from wrong commands
        usb.open();
        telnet.setParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        usb.setParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        doReadWrite("");
    }

    @Test
    /* test not done by RFC2217 server. Instead output control lines are connected to
         input control lines with a binary decoder 74LS42, 74LS138, 74LS139, 74HC... or ...
        in
            A0 = RTS
            A1 = DTR
        out
            Y0 = CD
            Y1 = DTS/DSR
            Y2 = CTS
            Y3 = RI
        expected result:
            none -> RI
            RTS  -> CTS
            DTR  -> DTS/DSR
            both -> CD
       for onlyRtsCts devices these two lines are connected directly
     */
    public void controlLines() throws Exception {
        byte[] data;
        int sleep = 10;

        Boolean inputLineFalse = usb.inputLinesSupported ? Boolean.FALSE : null;
        Boolean inputLineTrue = usb.inputLinesConnected ? Boolean.TRUE : inputLineFalse;

        EnumSet<ControlLine> supportedControlLines = EnumSet.of(ControlLine.RTS, ControlLine.DTR);
        if(usb.inputLinesSupported) {
            supportedControlLines.add(ControlLine.CTS);
            supportedControlLines.add(ControlLine.DSR);
            supportedControlLines.add(ControlLine.CD);
            supportedControlLines.add(ControlLine.RI);
        }

        // UsbSerialProber creates new UsbSerialPort objects which resets control lines,
        // so the initial open has the output control lines unset.
        // On additional close+open the output control lines can be retained.
        usb.open(EnumSet.of(UsbWrapper.OpenCloseFlags.NO_CONTROL_LINE_INIT));
        usb.setParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        telnet.setParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        Thread.sleep(sleep);

        assertEquals(supportedControlLines, usb.serialPort.getSupportedControlLines());

        // control lines reset on initial open
        data = "none".getBytes();
        assertEquals(usb.inputLinesConnected && !usb.inputLinesOnlyRtsCts
                        ? EnumSet.of(ControlLine.RI)
                        : EnumSet.noneOf(ControlLine.class),
                usb.serialPort.getControlLines());
        assertThat(usb.getControlLine(usb.serialPort::getRTS), equalTo(Boolean.FALSE));
        assertThat(usb.getControlLine(usb.serialPort::getCTS), equalTo(inputLineFalse));
        assertThat(usb.getControlLine(usb.serialPort::getDTR), equalTo(Boolean.FALSE));
        assertThat(usb.getControlLine(usb.serialPort::getDSR), equalTo(inputLineFalse));
        assertThat(usb.getControlLine(usb.serialPort::getCD), equalTo(inputLineFalse));
        assertThat(usb.getControlLine(usb.serialPort::getRI), equalTo(usb.inputLinesOnlyRtsCts ? Boolean.FALSE : inputLineTrue));
        telnet.write(data);
        if(usb.serialDriver instanceof CdcAcmSerialDriver)
            // arduino: control line feedback as serial_state notification is not implemented.
            // It does not send w/o RTS or DTR, so these control lines can be partly checked here.
            assertEquals(0, usb.read().length);
        else
            assertThat(Arrays.toString(data), usb.read(4), equalTo(data));
        usb.write(data);
        assertThat(Arrays.toString(data), telnet.read(4), equalTo(data));

        data = "rts ".getBytes();
        usb.serialPort.setRTS(true);
        Thread.sleep(sleep);
        assertEquals(usb.inputLinesConnected
                        ? EnumSet.of(ControlLine.RTS, ControlLine.CTS)
                        : EnumSet.of(ControlLine.RTS),
                usb.serialPort.getControlLines());
        assertThat(usb.getControlLine(usb.serialPort::getRTS), equalTo(Boolean.TRUE));
        assertThat(usb.getControlLine(usb.serialPort::getCTS), equalTo(inputLineTrue));
        assertThat(usb.getControlLine(usb.serialPort::getDTR), equalTo(Boolean.FALSE));
        assertThat(usb.getControlLine(usb.serialPort::getDSR), equalTo(inputLineFalse));
        assertThat(usb.getControlLine(usb.serialPort::getCD), equalTo(inputLineFalse));
        assertThat(usb.getControlLine(usb.serialPort::getRI), equalTo(inputLineFalse));
        telnet.write(data);
        assertThat(Arrays.toString(data), usb.read(4), equalTo(data));
        usb.write(data);
        assertThat(Arrays.toString(data), telnet.read(4), equalTo(data));

        data = "both".getBytes();
        usb.serialPort.setDTR(true);
        Thread.sleep(sleep);
        assertEquals(usb.inputLinesOnlyRtsCts
                ? EnumSet.of(ControlLine.RTS, ControlLine.DTR, ControlLine.CTS)
                : usb.inputLinesConnected
                ? EnumSet.of(ControlLine.RTS, ControlLine.DTR, ControlLine.CD)
                : EnumSet.of(ControlLine.RTS, ControlLine.DTR),
                usb.serialPort.getControlLines());
        assertThat(usb.getControlLine(usb.serialPort::getRTS), equalTo(Boolean.TRUE));
        assertThat(usb.getControlLine(usb.serialPort::getCTS), equalTo(usb.inputLinesOnlyRtsCts ? Boolean.TRUE : inputLineFalse));
        assertThat(usb.getControlLine(usb.serialPort::getDTR), equalTo(Boolean.TRUE));
        assertThat(usb.getControlLine(usb.serialPort::getDSR), equalTo(inputLineFalse));
        assertThat(usb.getControlLine(usb.serialPort::getCD), equalTo(usb.inputLinesOnlyRtsCts ? Boolean.FALSE : inputLineTrue));
        assertThat(usb.getControlLine(usb.serialPort::getRI), equalTo(inputLineFalse));
        telnet.write(data);
        assertThat(Arrays.toString(data), usb.read(4), equalTo(data));
        usb.write(data);
        assertThat(Arrays.toString(data), telnet.read(4), equalTo(data));

        data = "dtr ".getBytes();
        usb.serialPort.setRTS(false);
        Thread.sleep(sleep);
        assertEquals(usb.inputLinesConnected && !usb.inputLinesOnlyRtsCts
                        ? EnumSet.of(ControlLine.DTR, ControlLine.DSR)
                        : EnumSet.of(ControlLine.DTR),
                usb.serialPort.getControlLines());
        assertThat(usb.getControlLine(usb.serialPort::getRTS), equalTo(Boolean.FALSE));
        assertThat(usb.getControlLine(usb.serialPort::getCTS), equalTo(inputLineFalse));
        assertThat(usb.getControlLine(usb.serialPort::getDTR), equalTo(Boolean.TRUE));
        assertThat(usb.getControlLine(usb.serialPort::getDSR), equalTo(usb.inputLinesOnlyRtsCts ? Boolean.FALSE : inputLineTrue));
        assertThat(usb.getControlLine(usb.serialPort::getCD), equalTo(inputLineFalse));
        assertThat(usb.getControlLine(usb.serialPort::getRI), equalTo(inputLineFalse));
        telnet.write(data);
        assertThat(Arrays.toString(data), usb.read(4), equalTo(data));
        usb.write(data);
        assertThat(Arrays.toString(data), telnet.read(4), equalTo(data));

        // control lines retained over close+open
        boolean inputRetained = usb.inputLinesConnected;
        boolean outputRetained = true;
        usb.serialPort.setRTS(true);
        usb.serialPort.setDTR(false);
        usb.close(EnumSet.of(UsbWrapper.OpenCloseFlags.NO_CONTROL_LINE_INIT));
        usb.open(EnumSet.of(UsbWrapper.OpenCloseFlags.NO_CONTROL_LINE_INIT, UsbWrapper.OpenCloseFlags.NO_IOMANAGER_THREAD));
        usb.setParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);

        EnumSet<ControlLine> retainedControlLines = EnumSet.noneOf(ControlLine.class);
        if(outputRetained) retainedControlLines.add(ControlLine.RTS);
        if(inputRetained)  retainedControlLines.add(ControlLine.CTS);
        assertEquals(retainedControlLines, usb.serialPort.getControlLines());
        assertThat(usb.getControlLine(usb.serialPort::getRTS), equalTo(outputRetained));
        assertThat(usb.getControlLine(usb.serialPort::getCTS), equalTo(inputRetained ? inputLineTrue : inputLineFalse));
        assertThat(usb.getControlLine(usb.serialPort::getDTR), equalTo(Boolean.FALSE));
        assertThat(usb.getControlLine(usb.serialPort::getDSR), equalTo(inputLineFalse));
        assertThat(usb.getControlLine(usb.serialPort::getCD), equalTo(inputLineFalse));
        assertThat(usb.getControlLine(usb.serialPort::getRI), equalTo(inputLineFalse));

        if (usb.serialDriver instanceof ProlificSerialDriver) { // check different control line mapping in GET_CONTROL_REQUEST
            usb.serialPort.setRTS(false);
            usb.serialPort.setDTR(false);
            usb.close(EnumSet.of(UsbWrapper.OpenCloseFlags.NO_CONTROL_LINE_INIT));
            usb.open(EnumSet.of(UsbWrapper.OpenCloseFlags.NO_CONTROL_LINE_INIT, UsbWrapper.OpenCloseFlags.NO_IOMANAGER_THREAD));
            assertEquals(EnumSet.of(ControlLine.RI), usb.serialPort.getControlLines());

            usb.serialPort.setRTS(true);
            usb.serialPort.setDTR(false);
            usb.close(EnumSet.of(UsbWrapper.OpenCloseFlags.NO_CONTROL_LINE_INIT));
            usb.open(EnumSet.of(UsbWrapper.OpenCloseFlags.NO_CONTROL_LINE_INIT, UsbWrapper.OpenCloseFlags.NO_IOMANAGER_THREAD));
            assertEquals(EnumSet.of(ControlLine.RTS, ControlLine.CTS), usb.serialPort.getControlLines());

            usb.serialPort.setRTS(false);
            usb.serialPort.setDTR(true);
            usb.close(EnumSet.of(UsbWrapper.OpenCloseFlags.NO_CONTROL_LINE_INIT));
            usb.open(EnumSet.of(UsbWrapper.OpenCloseFlags.NO_CONTROL_LINE_INIT, UsbWrapper.OpenCloseFlags.NO_IOMANAGER_THREAD));
            assertEquals(EnumSet.of(ControlLine.DTR, ControlLine.DSR), usb.serialPort.getControlLines());

            usb.serialPort.setRTS(true);
            usb.serialPort.setDTR(true);
            usb.close(EnumSet.of(UsbWrapper.OpenCloseFlags.NO_CONTROL_LINE_INIT));
            usb.open(EnumSet.of(UsbWrapper.OpenCloseFlags.NO_CONTROL_LINE_INIT, UsbWrapper.OpenCloseFlags.NO_IOMANAGER_THREAD));
            assertEquals(EnumSet.of(ControlLine.RTS, ControlLine.DTR, ControlLine.CD), usb.serialPort.getControlLines());
        }

        // force error
        usb.close(EnumSet.of(UsbWrapper.OpenCloseFlags.NO_CONTROL_LINE_INIT));
        usb.open(EnumSet.of(UsbWrapper.OpenCloseFlags.NO_CONTROL_LINE_INIT, UsbWrapper.OpenCloseFlags.NO_IOMANAGER_THREAD));
        if (usb.serialDriver instanceof ProlificSerialDriver) {
            usb.serialPort.getRI(); // start background thread
        }
        usb.setParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        for (int i = 0; i < usb.serialDriver.getDevice().getInterfaceCount(); i++)
            usb.deviceConnection.releaseInterface(usb.serialDriver.getDevice().getInterface(i));
        usb.deviceConnection.close();

        try {
            usb.serialPort.setRTS(true);
            fail("error expected");
        } catch (IOException ignored) {
        }

        try {
            if (usb.serialDriver instanceof ProlificSerialDriver) {
                for(int i = 0; i < 10; i++) { // can take some time until background thread fails
                    usb.serialPort.getRI();
                    Thread.sleep(100);
                }
            } else {
                usb.serialPort.getRI();
            }
            fail("error expected");
        } catch (IOException ignored) {
        } catch (UnsupportedOperationException ignored) {
        }
    }

    @Test
    public void setBreak() throws Exception {
        usb.open();
        telnet.setParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        usb.setParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        doReadWrite("");
        usb.serialPort.setBreak(true);
        Thread.sleep(100);
        usb.serialPort.setBreak(false);
        // RFC2217 has SET_CONTROL + REQ_BREAK_STATE request, but this is not supported by pyserial
        // as there is no easy notification on <break> condition. By default break is returned as
        // 0 byte on Linux, see https://man7.org/linux/man-pages/man3/termios.3.html -> BRKINT
        byte[] data = telnet.read(1);
        if (usb.serialDriver instanceof CdcAcmSerialDriver) {
            // BREAK forwarding not implemented by arduino_leonardo_bridge.ino
            assertThat("<break>", data, equalTo(new byte[]{}));
        } else if(usb.isCp21xxRestrictedPort) {
            assertThat("<break>", data, equalTo(new byte[]{0x26})); // send the last byte again?
        } else {
            assertThat("<break>", data, equalTo(new byte[]{0}));
        }
        doReadWrite("");
    }

    @Test
    public void deviceConnection() throws Exception {
        byte[] buf = new byte[256];
        usb.open(EnumSet.of(UsbWrapper.OpenCloseFlags.NO_IOMANAGER_THREAD));
        usb.setParameters(115200, 8, 1, UsbSerialPort.PARITY_NONE);

        usb.write("x".getBytes());
        usb.serialPort.read(buf, 1000);
        usb.serialPort.setRTS(true);
        try {
            usb.serialPort.getRI();
        } catch (UnsupportedOperationException ignored) {
        }
        boolean purged;
        try {
            usb.serialPort.purgeHwBuffers(true, true);
            purged = true;
        } catch (UnsupportedOperationException ex) {
            purged = false;
        }
        usb.deviceConnection.close();
        try { // only Prolific driver has early exit if nothing changed
            usb.setParameters(115200, 8, 1, UsbSerialPort.PARITY_NONE);
            if(!(usb.serialDriver instanceof ProlificSerialDriver))
                fail("setParameters error expected");
        } catch (IOException ignored) {
        }
        try {
            usb.setParameters(57600, 8, 1, UsbSerialPort.PARITY_NONE);
            fail("setParameters error expected");
        } catch (IOException ignored) {
        }
        try {
            usb.write("x".getBytes());
            fail("write error expected");
        } catch (IOException ignored) {
        }
        try {
            usb.serialPort.read(buf, 1000);
            fail("read error expected");
        } catch (IOException ignored) {
        }
        try {
            usb.serialPort.read(buf, 0);
            fail("read error expected");
        } catch (IOException ignored) {
        }
        try {
            usb.serialPort.setRTS(true);
            fail("setRts error expected");
        } catch (IOException ignored) {
        }
        try {
            if(usb.serialDriver instanceof ProlificSerialDriver)
                Thread.sleep(600); // wait for background thread
            usb.serialPort.getRI();
            fail("getRI error expected");
        } catch (IOException ignored) {
        } catch (UnsupportedOperationException ignored) {
        }
        if(purged) {
            usb.serialPort.purgeHwBuffers(false, false);
            try {
                usb.serialPort.purgeHwBuffers(true, false);
                fail("purgeHwBuffers(write) error expected");
            } catch (IOException ignored) {
            }
            try {
                usb.serialPort.purgeHwBuffers(false, true);
                fail("purgeHwBuffers(read) error expected");
            } catch (IOException ignored) {
            }
        }
        try {
            usb.serialPort.setBreak(true);
            fail("setBreak error expected");
        } catch (IOException ignored) {
        }
        usb.close(EnumSet.of(UsbWrapper.OpenCloseFlags.NO_DEVICE_CONNECTION));
        try {
            usb.open(EnumSet.of(UsbWrapper.OpenCloseFlags.NO_IOMANAGER_THREAD, UsbWrapper.OpenCloseFlags.NO_DEVICE_CONNECTION));
            fail("open error expected");
        } catch (Exception ignored) {
        }

        usb.open(EnumSet.of(UsbWrapper.OpenCloseFlags.NO_IOMANAGER_THREAD));
        usb.write("x".getBytes());
        UsbDeviceConnection otherDeviceConnection = usbManager.openDevice(usb.serialDriver.getDevice());
        usb.write("x".getBytes());
        otherDeviceConnection.close();
        usb.write("x".getBytes());

        // already queued read request is not interrupted by closing deviceConnection and test would hang
    }

    @Test
    public void commonMethods() throws Exception {
        String s;
        assertNotNull(usb.serialPort.getDriver());
        assertNotNull(usb.serialPort.getDevice());
        assertEquals(test_device_port, usb.serialPort.getPortNumber());
        s = usb.serialDriver.toString();
        assertNotEquals(0, s.length());

        assertFalse(usb.serialPort.isOpen());
        usb.open();
        assertTrue(usb.serialPort.isOpen());

        s = usb.serialPort.getSerial();
        // with target sdk 29 can throw SecurityException before USB permission dialog is confirmed
        // not all devices implement serial numbers. some observed values are:
        // FT232         00000000, FTGH4NTX, ...
        // FT2232        <null>
        // CP2102        0001
        // CP2105        0035E46E
        // CH340         <null>
        // PL2303        <null>
        // CDC:Microbit  9900000037024e450034200b0000004a0000000097969901
        // CDC:Digispark <null>

        try {
            usb.open();
            fail("already open error expected");
        } catch (IOException ignored) {
        }
        try {
            byte[] buffer = new byte[0];
            usb.serialPort.read(buffer, UsbWrapper.USB_READ_WAIT);
            fail("read buffer to small expected");
        } catch(IllegalArgumentException ignored) {}
    }

    @Test
    public void ftdiMethods() throws Exception {
        Assume.assumeTrue("only for FTDI", usb.serialDriver instanceof FtdiSerialDriver);

        byte[] b;
        usb.open();
        usb.setParameters(115200, 8, 1, UsbSerialPort.PARITY_NONE);
        telnet.setParameters(115200, 8, 1, UsbSerialPort.PARITY_NONE);

        FtdiSerialDriver.FtdiSerialPort ftdiSerialPort = (FtdiSerialDriver.FtdiSerialPort) usb.serialPort;
        int lt = ftdiSerialPort.getLatencyTimer();
        ftdiSerialPort.setLatencyTimer(1);
        telnet.write("x".getBytes());
        b = usb.read(1);
        long t1 = System.currentTimeMillis();
        telnet.write("x".getBytes());
        b = usb.read(1);
        ftdiSerialPort.setLatencyTimer(100);
        long t2 = System.currentTimeMillis();
        telnet.write("x".getBytes());
        b = usb.read(1);
        long t3 = System.currentTimeMillis();
        ftdiSerialPort.setLatencyTimer(lt);
        assertTrue("latency 1: expected < 100, got "+ (t2-t1), (t2-t1) < 100);
        assertTrue("latency 100: expected >= 100, got " + (t3-t2), (t3-t2) >= 100);

        usb.deviceConnection.close();
        try {
            ftdiSerialPort.getLatencyTimer();
            fail("getLatencyTimer error expected");
        } catch (IOException ignored) {}
        usb.deviceConnection.close();
        try {
            ftdiSerialPort.setLatencyTimer(1);
            fail("setLatencyTimer error expected");
        } catch (IOException ignored) {}
    }
}
