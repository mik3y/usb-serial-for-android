/*
 * test setup
 * - android device with ADB over Wi-Fi
 *      - to set up ADB over Wi-Fi with custom roms you typically can do it from: Android settings -> Developer options
 *      - for other devices you first have to manually connect over USB and enable Wi-Fi as shown here:
 *         https://developer.android.com/studio/command-line/adb.html
 * - windows/linux machine running rfc2217_server.py
 *      python + pyserial + https://github.com/pyserial/pyserial/blob/master/examples/rfc2217_server.py
 *      for developing this test it was essential to see all data (see test/rfc2217_server.diff, run python script with '-v -v' option)
 * - all suppported usb <-> serial converter
 *      as CDC test device use an arduino leonardo / pro mini programmed with arduino_leonardo_bridge.ino
 *
 * restrictions
 *  - as real hardware is used, timing might need tuning. see:
 *      - Thread.sleep(...)
 *      - obj.wait(...)
 *  - some tests fail sporadically. typical workarounds are:
 *      - reconnect device
 *      - run test individually
 *      - increase sleep?
 *  - missing functionality on certain devices, see:
 *      - if(rfc2217_server_nonstandard_baudrates)
 *      - if(usbSerialDriver instanceof ...)
 *
 */
package com.hoho.android.usbserial;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.util.Log;
import android.os.Process;

import com.hoho.android.usbserial.driver.CdcAcmSerialDriver;
import com.hoho.android.usbserial.driver.Ch34xSerialDriver;
import com.hoho.android.usbserial.driver.Cp21xxSerialDriver;
import com.hoho.android.usbserial.driver.FtdiSerialDriver;
import com.hoho.android.usbserial.driver.ProbeTable;
import com.hoho.android.usbserial.driver.ProlificSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import org.apache.commons.net.telnet.InvalidTelnetOptionException;
import org.apache.commons.net.telnet.TelnetClient;
import org.apache.commons.net.telnet.TelnetCommand;
import org.apache.commons.net.telnet.TelnetOptionHandler;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executors;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
public class DeviceTest implements SerialInputOutputManager.Listener {

    // configuration:
    private final static String  rfc2217_server_host = "192.168.0.100";
    private final static int     rfc2217_server_port = 2217;
    private final static boolean rfc2217_server_nonstandard_baudrates = false; // false on Windows, Raspi
    private final static boolean rfc2217_server_parity_mark_space = false; // false on Raspi
    private final static int     test_device_port = 0;

    private final static int     TELNET_READ_WAIT = 500;
    private final static int     USB_READ_WAIT    = 500;
    private final static int     USB_WRITE_WAIT   = 500;
    private final static Integer SERIAL_INPUT_OUTPUT_MANAGER_THREAD_PRIORITY = Process.THREAD_PRIORITY_URGENT_AUDIO;

    private final static String  TAG = "DeviceTest";
    private final static byte    RFC2217_COM_PORT_OPTION = 0x2c;
    private final static byte    RFC2217_SET_BAUDRATE = 1;
    private final static byte    RFC2217_SET_DATASIZE = 2;
    private final static byte    RFC2217_SET_PARITY   = 3;
    private final static byte    RFC2217_SET_STOPSIZE = 4;

    private Context context;
    private UsbSerialDriver usbSerialDriver;
    private UsbDeviceConnection usbDeviceConnection;
    private UsbSerialPort usbSerialPort;
    private SerialInputOutputManager usbIoManager;
    private final Deque<byte[]> usbReadBuffer = new LinkedList<>();
    private boolean usbReadBlock = false;
    private long usbReadTime = 0;

    private static TelnetClient telnetClient;
    private static InputStream telnetReadStream;
    private static OutputStream telnetWriteStream;
    private static Integer[] telnetComPortOptionCounter = {0};
    private int telnetWriteDelay = 0;
    private boolean isCp21xxRestrictedPort = false; // second port of Cp2105 has limited dataBits, stopBits, parity

    @BeforeClass
    public static void setUpFixture() throws Exception {
        telnetClient = null;
        // postpone fixture setup to first test, because exceptions are not reported for @BeforeClass
        // and test terminates with missleading 'Empty test suite'
    }

    public static void setUpFixtureInt() throws Exception {
        if(telnetClient != null)
            return;
        telnetClient = new TelnetClient();
        telnetClient.addOptionHandler(new TelnetOptionHandler(RFC2217_COM_PORT_OPTION, false, false, false, false) {
            @Override
            public int[] answerSubnegotiation(int[] suboptionData, int suboptionLength) {
                telnetComPortOptionCounter[0] += 1;
                return super.answerSubnegotiation(suboptionData, suboptionLength);
            }
        });

        telnetClient.setConnectTimeout(2000);
        telnetClient.connect(rfc2217_server_host, rfc2217_server_port);
        telnetClient.setTcpNoDelay(true);
        telnetWriteStream = telnetClient.getOutputStream();
        telnetReadStream = telnetClient.getInputStream();
    }

    @Before
    public void setUp() throws Exception {
        setUpFixtureInt();
        telnetClient.sendAYT(1000); // not correctly handled by rfc2217_server.py, but WARNING output "ignoring Telnet command: '\xf6'" is a nice separator between tests
        telnetComPortOptionCounter[0] = 0;
        telnetWriteDelay = 0;

        context = InstrumentationRegistry.getContext();
        final UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        assertEquals("no usb device found", 1, availableDrivers.size());
        usbSerialDriver = availableDrivers.get(0);
        assertTrue( usbSerialDriver.getPorts().size() > test_device_port);
        usbSerialPort = usbSerialDriver.getPorts().get(test_device_port);
        Log.i(TAG, "Using USB device "+ usbSerialDriver.getClass().getSimpleName());
        isCp21xxRestrictedPort = usbSerialDriver instanceof Cp21xxSerialDriver && usbSerialDriver.getPorts().size()==2 && test_device_port == 1;

        if (!usbManager.hasPermission(usbSerialPort.getDriver().getDevice())) {
            final Boolean[] granted = {Boolean.FALSE};
            BroadcastReceiver usbReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    granted[0] = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    synchronized (granted) {
                        granted.notify();
                    }
                }
            };
            PendingIntent permissionIntent = PendingIntent.getBroadcast(context, 0, new Intent("com.android.example.USB_PERMISSION"), 0);
            IntentFilter filter = new IntentFilter("com.android.example.USB_PERMISSION");
            context.registerReceiver(usbReceiver, filter);
            usbManager.requestPermission(usbSerialDriver.getDevice(), permissionIntent);
            synchronized (granted) {
                granted.wait(5000);
            }
            assertTrue("USB permission dialog not confirmed", granted[0]);
        }
        usbDeviceConnection = usbManager.openDevice(usbSerialDriver.getDevice());
        usbSerialPort.open(usbDeviceConnection);
        usbSerialPort.setDTR(true);
        usbSerialPort.setRTS(true);
        usbIoManager = new SerialInputOutputManager(usbSerialPort, this) {
            @Override
            public void run() {
                if(SERIAL_INPUT_OUTPUT_MANAGER_THREAD_PRIORITY != null)
                    Process.setThreadPriority(SERIAL_INPUT_OUTPUT_MANAGER_THREAD_PRIORITY);
                super.run();
            }
        };

        Executors.newSingleThreadExecutor().submit(usbIoManager);

        synchronized (usbReadBuffer) {
            usbReadBuffer.clear();
        }
    }

    @After
    public void tearDown() throws IOException {
        try {
            usbRead(0);
        } catch (Exception ignored) {}
        try {
            telnetRead(0);
        } catch (Exception ignored) {}

        try {
            usbIoManager.setListener(null);
            usbIoManager.stop();
        } catch (Exception ignored) {}
        try {
            usbSerialPort.setDTR(false);
            usbSerialPort.setRTS(false);
            usbSerialPort.close();
        } catch (Exception ignored) {}
        try {
            usbDeviceConnection.close();
        } catch (Exception ignored) {}
        usbIoManager = null;
        usbSerialPort = null;
        usbDeviceConnection = null;
        usbSerialDriver = null;
    }

    @AfterClass
    public static void tearDownFixture() throws Exception {
        try {
            telnetClient.disconnect();
        } catch (Exception ignored) {}
        telnetReadStream = null;
        telnetWriteStream = null;
        telnetClient = null;
    }

    // wait full time
    private byte[] telnetRead() throws Exception {
        return telnetRead(-1);
    }

    private byte[] telnetRead(int expectedLength) throws Exception {
        long end = System.currentTimeMillis() + TELNET_READ_WAIT;
        ByteBuffer buf = ByteBuffer.allocate(4096);
        while(System.currentTimeMillis() < end) {
            if(telnetReadStream.available() > 0) {
                buf.put((byte) telnetReadStream.read());
            } else {
                if (expectedLength >= 0 && buf.position() >= expectedLength)
                    break;
                Thread.sleep(1);
            }
        }
        byte[] data = new byte[buf.position()];
        buf.flip();
        buf.get(data);
        return data;
    }

    private void telnetWrite(byte[] data) throws Exception{
        if(telnetWriteDelay != 0) {
            for(byte b : data) {
                telnetWriteStream.write(b);
                telnetWriteStream.flush();
                Thread.sleep(telnetWriteDelay);
            }
        } else {
            telnetWriteStream.write(data);
            telnetWriteStream.flush();
        }
    }

    // wait full time
    private byte[] usbRead() throws Exception {
        return usbRead(-1);
    }

    private byte[] usbRead(int expectedLength) throws Exception {
        long end = System.currentTimeMillis() + USB_READ_WAIT;
        ByteBuffer buf = ByteBuffer.allocate(8192);
        if(usbIoManager != null) {
            while (System.currentTimeMillis() < end) {
                synchronized (usbReadBuffer) {
                    while(usbReadBuffer.peek() != null)
                        buf.put(usbReadBuffer.remove());
                }
                if (expectedLength >= 0 && buf.position() >= expectedLength)
                    break;
                Thread.sleep(1);
            }

        } else {
            byte[] b1 = new byte[256];
            while (System.currentTimeMillis() < end) {
                int len = usbSerialPort.read(b1, USB_READ_WAIT / 10);
                if (len > 0) {
                    buf.put(b1, 0, len);
                } else {
                    if (expectedLength >= 0 && buf.position() >= expectedLength)
                        break;
                    Thread.sleep(1);
                }
            }
        }
        byte[] data = new byte[buf.position()];
        buf.flip();
        buf.get(data);
        return data;
    }

    private void usbWrite(byte[] data) throws IOException {
        usbSerialPort.write(data, USB_WRITE_WAIT);
    }

    private void usbParameters(int baudRate, int dataBits, int stopBits, int parity) throws IOException, InterruptedException {
        usbSerialPort.setParameters(baudRate, dataBits, stopBits, parity);
        if(usbSerialDriver instanceof CdcAcmSerialDriver)
            Thread.sleep(10); // arduino_leonardeo_bridge.ini needs some time
        else
            Thread.sleep(1);
    }

    private void telnetParameters(int baudRate, int dataBits, int stopBits, int parity) throws IOException, InterruptedException, InvalidTelnetOptionException {
        telnetComPortOptionCounter[0] = 0;

        telnetClient.sendCommand((byte)TelnetCommand.SB);
        telnetWriteStream.write(new byte[] {RFC2217_COM_PORT_OPTION, RFC2217_SET_BAUDRATE, (byte)(baudRate>>24), (byte)(baudRate>>16), (byte)(baudRate>>8), (byte)baudRate});
        telnetClient.sendCommand((byte)TelnetCommand.SE);

        telnetClient.sendCommand((byte)TelnetCommand.SB);
        telnetWriteStream.write(new byte[] {RFC2217_COM_PORT_OPTION, RFC2217_SET_DATASIZE, (byte)dataBits});
        telnetClient.sendCommand((byte)TelnetCommand.SE);

        telnetClient.sendCommand((byte)TelnetCommand.SB);
        telnetWriteStream.write(new byte[] {RFC2217_COM_PORT_OPTION, RFC2217_SET_STOPSIZE, (byte)stopBits});
        telnetClient.sendCommand((byte)TelnetCommand.SE);

        telnetClient.sendCommand((byte)TelnetCommand.SB);
        telnetWriteStream.write(new byte[] {RFC2217_COM_PORT_OPTION, RFC2217_SET_PARITY, (byte)(parity+1)});
        telnetClient.sendCommand((byte)TelnetCommand.SE);

        // windows does not like nonstandard baudrates. rfc2217_server.py terminates w/o response
        for(int i=0; i<2000; i++) {
            if(telnetComPortOptionCounter[0] == 4) break;
            Thread.sleep(1);
        }
        assertEquals("telnet connection lost", 4, telnetComPortOptionCounter[0].intValue());
    }

    @Override
    public void onNewData(byte[] data) {
        long now = System.currentTimeMillis();
        if(usbReadTime == 0)
            usbReadTime = now;
        if(data.length > 64) {
            Log.d(TAG, "usb read: time+=" + String.format("%-3d",now-usbReadTime) + " len=" + String.format("%-4d",data.length) + " data=" + new String(data, 0, 32) + "..." + new String(data, data.length-32, 32));
        } else {
            Log.d(TAG, "usb read: time+=" + String.format("%-3d",now-usbReadTime) + " len=" + String.format("%-4d",data.length) + " data=" + new String(data));
        }
        usbReadTime = now;

        while(usbReadBlock)
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        synchronized (usbReadBuffer) {
            usbReadBuffer.add(data);
        }
    }

    @Override
    public void onRunError(Exception e) {
        assertTrue("usb connection lost", false);
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


    @Test
    public void baudRate() throws Exception {
        byte[] data;

        if (false) { // default baud rate
            // CP2102: only works if first connection after attaching device
            // PL2303, FTDI: it's not 9600
            telnetParameters(9600, 8, 1, UsbSerialPort.PARITY_NONE);

            telnetWrite("net2usb".getBytes());
            data = usbRead(7);
            assertThat(data, equalTo("net2usb".getBytes())); // includes array content in output
            //assertArrayEquals("net2usb".getBytes(), data); // only includes array length in output
            usbWrite("usb2net".getBytes());
            data = telnetRead(7);
            assertThat(data, equalTo("usb2net".getBytes()));
        }

        // invalid values
        try {
            usbParameters(-1, 8, 1, UsbSerialPort.PARITY_NONE);
            if (usbSerialDriver instanceof Ch34xSerialDriver)
                ; // todo: add range check in driver
            else if (usbSerialDriver instanceof FtdiSerialDriver)
                ; // todo: add range check in driver
            else if (usbSerialDriver instanceof ProlificSerialDriver)
                ; // todo: add range check in driver
            else if (usbSerialDriver instanceof Cp21xxSerialDriver)
                ; // todo: add range check in driver
            else if (usbSerialDriver instanceof CdcAcmSerialDriver)
                ; // todo: add range check in driver
            else
                fail("invalid baudrate 0");
        } catch (java.io.IOException e) { // cp2105 second port
        } catch (java.lang.IllegalArgumentException e) {
        }
        try {
            usbParameters(0, 8, 1, UsbSerialPort.PARITY_NONE);
            if (usbSerialDriver instanceof ProlificSerialDriver)
                ; // todo: add range check in driver
            else if (usbSerialDriver instanceof Cp21xxSerialDriver)
                ; // todo: add range check in driver
            else if (usbSerialDriver instanceof CdcAcmSerialDriver)
                ; // todo: add range check in driver
            else
                fail("invalid baudrate 0");
        } catch (java.lang.ArithmeticException e) { // ch340
        } catch (java.io.IOException e) { // cp2105 second port
        } catch (java.lang.IllegalArgumentException e) {
        }
        try {
            usbParameters(1, 8, 1, UsbSerialPort.PARITY_NONE);
            if (usbSerialDriver instanceof FtdiSerialDriver)
                ;
            else if (usbSerialDriver instanceof ProlificSerialDriver)
                ;
            else if (usbSerialDriver instanceof Cp21xxSerialDriver)
                ;
            else if (usbSerialDriver instanceof CdcAcmSerialDriver)
                ;
            else
                fail("invalid baudrate 0");
        } catch (java.io.IOException e) { // ch340
        } catch (java.lang.IllegalArgumentException e) {
        }
        try {
            usbParameters(2<<31, 8, 1, UsbSerialPort.PARITY_NONE);
            if (usbSerialDriver instanceof ProlificSerialDriver)
                ;
            else if (usbSerialDriver instanceof Cp21xxSerialDriver)
                ;
            else if (usbSerialDriver instanceof CdcAcmSerialDriver)
                ;
            else
                fail("invalid baudrate 2^31");
        } catch (java.lang.ArithmeticException e) { // ch340
        } catch (java.io.IOException e) { // cp2105 second port
        } catch (java.lang.IllegalArgumentException e) {
        }

        for(int baudRate : new int[] {300, 2400, 19200, 42000, 115200} ) {
            if(baudRate == 42000 && !rfc2217_server_nonstandard_baudrates)
                continue; // rfc2217_server.py would terminate
            if(baudRate == 300 && isCp21xxRestrictedPort) {
                try {
                    usbParameters(baudRate, 8, 1, UsbSerialPort.PARITY_NONE);
                    assertTrue(false);
                } catch (java.io.IOException e) {
                }
                continue;
            }
            telnetParameters(baudRate, 8, 1, UsbSerialPort.PARITY_NONE);
            usbParameters(baudRate, 8, 1, UsbSerialPort.PARITY_NONE);

            telnetWrite("net2usb".getBytes());
            data = usbRead(7);
            assertThat(String.valueOf(baudRate)+"/8N1", data, equalTo("net2usb".getBytes()));
            usbWrite("usb2net".getBytes());
            data = telnetRead(7);
            assertThat(String.valueOf(baudRate)+"/8N1", data, equalTo("usb2net".getBytes()));
        }
        { // non matching baud rate
            telnetParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
            usbParameters(2400, 8, 1, UsbSerialPort.PARITY_NONE);

            telnetWrite("net2usb".getBytes());
            data = usbRead();
            assertNotEquals(7, data.length);
            usbWrite("usb2net".getBytes());
            data = telnetRead();
            assertNotEquals(7, data.length);
        }
    }

    @Test
    public void dataBits() throws Exception {
        byte[] data;

        for(int i: new int[] {0, 4, 9}) {
            try {
                usbParameters(19200, i, 1, UsbSerialPort.PARITY_NONE);
                if (usbSerialDriver instanceof ProlificSerialDriver)
                    ; // todo: add range check in driver
                else if (usbSerialDriver instanceof CdcAcmSerialDriver)
                    ; // todo: add range check in driver
                else
                    fail("invalid databits "+i);
            } catch (java.lang.IllegalArgumentException e) {
            }
        }

        // telnet -> usb
        usbParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        telnetParameters(19200, 7, 1, UsbSerialPort.PARITY_NONE);
        telnetWrite(new byte[] {0x00});
        Thread.sleep(1); // one bit is 0.05 milliseconds long, wait >> stop bit
        telnetWrite(new byte[] {(byte)0xff});
        data = usbRead(2);
        assertThat("19200/7N1", data, equalTo(new byte[] {(byte)0x80, (byte)0xff}));

        telnetParameters(19200, 6, 1, UsbSerialPort.PARITY_NONE);
        telnetWrite(new byte[] {0x00});
        Thread.sleep(1);
        telnetWrite(new byte[] {(byte)0xff});
        data = usbRead(2);
        assertThat("19000/6N1", data, equalTo(new byte[] {(byte)0xc0, (byte)0xff}));

        telnetParameters(19200, 5, 1, UsbSerialPort.PARITY_NONE);
        telnetWrite(new byte[] {0x00});
        Thread.sleep(1);
        telnetWrite(new byte[] {(byte)0xff});
        data = usbRead(2);
        assertThat("19000/5N1", data, equalTo(new byte[] {(byte)0xe0, (byte)0xff}));

        // usb -> telnet
        try {
            telnetParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
            usbParameters(19200, 7, 1, UsbSerialPort.PARITY_NONE);
            usbWrite(new byte[]{0x00});
            Thread.sleep(1);
            usbWrite(new byte[]{(byte) 0xff});
            data = telnetRead(2);
            assertThat("19000/7N1", data, equalTo(new byte[]{(byte) 0x80, (byte) 0xff}));
        } catch (java.lang.IllegalArgumentException e) {
                if(!isCp21xxRestrictedPort)
                    throw e;
        }
        try {
            usbParameters(19200, 6, 1, UsbSerialPort.PARITY_NONE);
            usbWrite(new byte[]{0x00});
            Thread.sleep(1);
            usbWrite(new byte[]{(byte) 0xff});
            data = telnetRead(2);
            assertThat("19000/6N1", data, equalTo(new byte[]{(byte) 0xc0, (byte) 0xff}));
        } catch (java.lang.IllegalArgumentException e) {
            if (!(isCp21xxRestrictedPort || usbSerialDriver instanceof FtdiSerialDriver))
                throw e;
        }
        try {
            usbParameters(19200, 5, 1, UsbSerialPort.PARITY_NONE);
            usbWrite(new byte[] {0x00});
            Thread.sleep(1);
            usbWrite(new byte[] {(byte)0xff});
            data = telnetRead(2);
            assertThat("19000/5N1", data, equalTo(new byte[] {(byte)0xe0, (byte)0xff}));
        } catch (java.lang.IllegalArgumentException e) {
            if (!(isCp21xxRestrictedPort || usbSerialDriver instanceof FtdiSerialDriver))
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

        for(int i: new int[] {-1, 5}) {
            try {
                usbParameters(19200, 8, 1, i);
                fail("invalid parity "+i);
            } catch (java.lang.IllegalArgumentException e) {
            }
        }
        if(isCp21xxRestrictedPort) {
            usbParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
            usbParameters(19200, 8, 1, UsbSerialPort.PARITY_EVEN);
            usbParameters(19200, 8, 1, UsbSerialPort.PARITY_ODD);
            try {
                usbParameters(19200, 8, 1, UsbSerialPort.PARITY_MARK);
                usbParameters(19200, 8, 1, UsbSerialPort.PARITY_SPACE);
            } catch (java.lang.IllegalArgumentException e) {
            }
            return;
            // test below not possible as it requires unsupported 7 dataBits
        }

        // usb -> telnet
        telnetParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        usbParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        usbWrite(_8n1);
        data = telnetRead(4);
        assertThat("19200/8N1", data, equalTo(_8n1));

        usbParameters(19200, 7, 1, UsbSerialPort.PARITY_ODD);
        usbWrite(_8n1);
        data = telnetRead(4);
        assertThat("19200/7O1", data, equalTo(_7o1));

        usbParameters(19200, 7, 1, UsbSerialPort.PARITY_EVEN);
        usbWrite(_8n1);
        data = telnetRead(4);
        assertThat("19200/7E1", data, equalTo(_7e1));

        if (usbSerialDriver instanceof CdcAcmSerialDriver) {
            // not supported by arduino_leonardo_bridge.ino, other devices might support it
        } else if (rfc2217_server_parity_mark_space) {
            usbParameters(19200, 7, 1, UsbSerialPort.PARITY_MARK);
            usbWrite(_8n1);
            data = telnetRead(4);
            assertThat("19200/7M1", data, equalTo(_7m1));

            usbParameters(19200, 7, 1, UsbSerialPort.PARITY_SPACE);
            usbWrite(_8n1);
            data = telnetRead(4);
            assertThat("19200/7S1", data, equalTo(_7s1));
        }

        // telnet -> usb
        usbParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        telnetParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        telnetWrite(_8n1);
        data = usbRead(4);
        assertThat("19200/8N1", data, equalTo(_8n1));

        telnetParameters(19200, 7, 1, UsbSerialPort.PARITY_ODD);
        telnetWrite(_8n1);
        data = usbRead(4);
        assertThat("19200/7O1", data, equalTo(_7o1));

        telnetParameters(19200, 7, 1, UsbSerialPort.PARITY_EVEN);
        telnetWrite(_8n1);
        data = usbRead(4);
        assertThat("19200/7E1", data, equalTo(_7e1));

        if (usbSerialDriver instanceof CdcAcmSerialDriver) {
            // not supported by arduino_leonardo_bridge.ino, other devices might support it
        } else {
            if (rfc2217_server_parity_mark_space) {
                telnetParameters(19200, 7, 1, UsbSerialPort.PARITY_MARK);
                telnetWrite(_8n1);
                data = usbRead(4);
                assertThat("19200/7M1", data, equalTo(_7m1));

                telnetParameters(19200, 7, 1, UsbSerialPort.PARITY_SPACE);
                telnetWrite(_8n1);
                data = usbRead(4);
                assertThat("19200/7S1", data, equalTo(_7s1));
            }
            usbParameters(19200, 7, 1, UsbSerialPort.PARITY_ODD);
            telnetParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
            telnetWrite(_8n1);
            data = usbRead(4);
            assertThat("19200/8N1", data, equalTo(_7n1)); // read is resilient against errors
        }
    }

    @Test
    public void stopBits() throws Exception {
        byte[] data;

        for (int i : new int[]{0, 4}) {
            try {
                usbParameters(19200, 8, i, UsbSerialPort.PARITY_NONE);
                fail("invalid stopbits " + i);
            } catch (java.lang.IllegalArgumentException e) {
            }
        }

        if (usbSerialDriver instanceof CdcAcmSerialDriver) {
            // software based bridge in arduino_leonardo_bridge.ino is to slow, other devices might support it
        } else {
            // shift stopbits into next byte, by using different databits
            // a - start bit (0)
            // o - stop bit  (1)
            // d - data bit

            // out 8N2:   addddddd doaddddddddo
            //             1000001 0  10001111
            // in 6N1:    addddddo addddddo
            //             100000   101000
            usbParameters(19200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            telnetParameters(19200, 6, 1, UsbSerialPort.PARITY_NONE);
            usbWrite(new byte[]{(byte)0x41, (byte)0xf1});
            data = telnetRead(2);
            assertThat("19200/8N1", data, equalTo(new byte[]{1, 5}));

            // out 8N2:   addddddd dooaddddddddoo
            //             1000001 0   10011111
            // in 6N1:    addddddo addddddo
            //             100000   110100
            try {
                usbParameters(19200, 8, UsbSerialPort.STOPBITS_2, UsbSerialPort.PARITY_NONE);
                telnetParameters(19200, 6, 1, UsbSerialPort.PARITY_NONE);
                usbWrite(new byte[]{(byte) 0x41, (byte) 0xf9});
                data = telnetRead(2);
                assertThat("19200/8N1", data, equalTo(new byte[]{1, 11}));
            } catch(java.lang.IllegalArgumentException e) {
                if(!isCp21xxRestrictedPort)
                    throw e;
            }
            // todo: could create similar test for 1.5 stopbits, by reading at double speed
            //       but only some devices support 1.5 stopbits and it is basically not used any more
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

        probeTable.addProduct(usbSerialDriver.getDevice().getVendorId(), usbSerialDriver.getDevice().getProductId(), usbSerialDriver.getClass());
        availableDrivers = new UsbSerialProber(probeTable).findAllDrivers(usbManager);
        assertEquals(1, availableDrivers.size());
        assertEquals(true, availableDrivers.get(0).getClass() == usbSerialDriver.getClass());
    }

    @Test
    // data loss es expected, if data is not consumed fast enough
    public void readBuffer() throws Exception {
        if(usbSerialDriver instanceof CdcAcmSerialDriver)
            telnetWriteDelay = 10; // arduino_leonardo_bridge.ino sends each byte in own USB packet, which is horribly slow
        usbParameters(115200, 8, 1, UsbSerialPort.PARITY_NONE);
        telnetParameters(115200, 8, 1, UsbSerialPort.PARITY_NONE);

        StringBuilder expected = new StringBuilder();
        StringBuilder data = new StringBuilder();
        final int maxWait = 2000;
        int bufferSize = 0;
        for(bufferSize = 8; bufferSize < (2<<15); bufferSize *= 2) {
            int linenr = 0;
            String line;
            expected.setLength(0);
            data.setLength(0);

            Log.i(TAG, "bufferSize " + bufferSize);
            usbReadBlock = true;
            for (linenr = 0; linenr < bufferSize/8; linenr++) {
                line = String.format("%07d,", linenr);
                telnetWrite(line.getBytes());
                expected.append(line);
            }
            usbReadBlock = false;

            // slowly write new data, until old data is comletely read from buffer and new data is received again
            boolean found = false;
            for (; linenr < bufferSize/8 + maxWait/10 && !found; linenr++) {
                line = String.format("%07d,", linenr);
                telnetWrite(line.getBytes());
                Thread.sleep(10);
                expected.append(line);
                data.append(new String(usbRead(0)));
                found = data.toString().endsWith(line);
            }
            if(!found) {
                // use waiting read to clear input queue, else next test would see unexpected data
                byte[] rest = null;
                while(rest==null || rest.length>0)
                    rest = usbRead(-1);
                fail("end not found");
            }
            if (data.length() != expected.length())
                break;
        }
        int pos = indexOfDifference(data, expected);
        Log.i(TAG, "bufferSize " + bufferSize + ", first difference at " + pos);
        // actual values have large variance for same device, e.g.
        //  bufferSize 4096, first difference at 164
        //  bufferSize 64, first difference at 57
        assertTrue(bufferSize > 16);
        assertTrue(data.length() != expected.length());
    }


    //
    // this test can fail sporadically!
    //
    // Android is not a real time OS, so there is no guarantee that the usb thread is scheduled, or it might be blocked by Java garbage collection.
    // The SerialInputOutputManager uses a buffer size of 4Kb. Reading of these blocks happen behind UsbRequest.queue / UsbDeviceConnection.requestWait
    // The dump of data and error positions in logcat show, that data is lost somewhere in the UsbRequest handling,
    // very likely when the individual 64 byte USB packets are not read fast enough, and the serial converter chip has to discard bytes.
    //
    // On some days SERIAL_INPUT_OUTPUT_MANAGER_THREAD_PRIORITY=THREAD_PRIORITY_URGENT_AUDIO reduced errors by factor 10, on other days it had no effect at all!
    //
    @Test
    public void readSpeed() throws Exception {
        // see logcat for performance results
        //
        // CDC arduino_leonardo_bridge.ini has transfer speed ~ 100 byte/sec
        // all other devices are near physical limit with ~ 10-12k/sec
        int baudrate = 115200;
        usbParameters(baudrate, 8, 1, UsbSerialPort.PARITY_NONE);
        telnetParameters(baudrate, 8, 1, UsbSerialPort.PARITY_NONE);

        // fails more likely with larger or unlimited (-1) write ahead
        int writeSeconds = 5;
        int writeAhead = 5*baudrate/10; // write ahead for another 5 second read
        if(usbSerialDriver instanceof CdcAcmSerialDriver)
            writeAhead = 50;

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
                    telnetWrite(line.getBytes());
                    expected.append(line);
                } else {
                    Thread.sleep(0, 100000);
                }
                data.append(new String(usbRead(0)));
            }
            Log.i(TAG, "readSpeed: t="+(next-begin)+", read="+(data.length()-dlen)+", write="+(expected.length()-elen));
            dlen = data.length();
            elen = expected.length();
        }
        boolean found = false;
        long maxwait = Math.max(1000, (expected.length() - data.length()) * 20000L / baudrate );
        next = System.currentTimeMillis() + maxwait;
        Log.d(TAG, "readSpeed: rest wait time " + maxwait + " for " + (expected.length() - data.length()) + " byte");
        while(!found && System.currentTimeMillis() < next) {
            data.append(new String(usbRead(0)));
            found = data.toString().endsWith(line);
            Thread.sleep(1);
        }
        //next = System.currentTimeMillis();
        //Log.i(TAG, "readSpeed: t="+(next-begin)+", read="+(data.length()-dlen));

        int errcnt = 0;
        int errlen = 0;
        int datapos = indexOfDifference(data, expected);
        int expectedpos = datapos;
        while(datapos != -1) {
            errcnt += 1;
            int nextexpectedpos = -1;
            int nextdatapos = datapos + 2;
            int len = -1;
            if(nextdatapos + 10 < data.length()) { // try to sync data+expected, assuming that data is lost, but not corrupted
                String nextsub = data.substring(nextdatapos, nextdatapos + 10);
                nextexpectedpos = expected.indexOf(nextsub, expectedpos);
                if(nextexpectedpos >= 0) {
                    len = nextexpectedpos - expectedpos - 2;
                    errlen += len;
                }
            }
            Log.i(TAG, "readSpeed: difference at " + datapos + " len " + len );
            Log.d(TAG, "readSpeed:        got " +     data.substring(Math.max(datapos - 20, 0), Math.min(datapos + 20, data.length())));
            Log.d(TAG, "readSpeed:   expected " + expected.substring(Math.max(expectedpos - 20, 0), Math.min(expectedpos + 20, expected.length())));
            datapos = indexOfDifference(data, expected, nextdatapos, nextexpectedpos);
            expectedpos = nextexpectedpos + (datapos  - nextdatapos);
        }
        if(errcnt != 0)
            Log.i(TAG, "readSpeed: got " + errcnt + " errors, total len " + errlen+ ", avg. len " + errlen/errcnt);
        assertTrue("end not found", found);
        assertEquals("no errors", 0, errcnt);
    }

    @Test
    public void writeSpeed() throws Exception {
        // see logcat for performance results
        //
        // CDC arduino_leonardo_bridge.ini has transfer speed ~ 100 byte/sec
        // all other devices can get near physical limit:
        // longlines=true:, speed is near physical limit at 11.5k
        // longlines=false: speed is 3-4k for all devices, as more USB packets are required
        usbParameters(115200, 8, 1, UsbSerialPort.PARITY_NONE);
        telnetParameters(115200, 8, 1, UsbSerialPort.PARITY_NONE);
        boolean longlines = !(usbSerialDriver instanceof CdcAcmSerialDriver);

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
                usbWrite(line.getBytes());
                expected.append(line);
                data.append(new String(telnetRead(0)));
            }
            Log.i(TAG, "writeSpeed: t="+(next-begin)+", write="+(expected.length()-elen)+", read="+(data.length()-dlen));
            dlen = data.length();
            elen = expected.length();
        }
        boolean found = false;
        for (linenr=0; linenr < 2000 && !found; linenr++) {
            data.append(new String(telnetRead(0)));
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

}
