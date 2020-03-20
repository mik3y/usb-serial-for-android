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

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Process;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.util.Log;

import com.hoho.android.usbserial.driver.CdcAcmSerialDriver;
import com.hoho.android.usbserial.driver.Ch34xSerialDriver;
import com.hoho.android.usbserial.driver.CommonUsbSerialPort;
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
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executors;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
public class DeviceTest implements SerialInputOutputManager.Listener {

    // testInstrumentationRunnerArguments configuration
    private static String  rfc2217_server_host;
    private static int     rfc2217_server_port = 2217;
    private static boolean rfc2217_server_nonstandard_baudrates;
    private static String  test_device_driver;
    private static int     test_device_port;

    private final static int     TELNET_READ_WAIT = 500;
    private final static int     TELNET_COMMAND_WAIT = 2000;
    private final static int     USB_READ_WAIT = 500;
    private final static int     USB_WRITE_WAIT = 500;
    private final static Integer SERIAL_INPUT_OUTPUT_MANAGER_THREAD_PRIORITY = Process.THREAD_PRIORITY_URGENT_AUDIO;

    private final static String  TAG = "DeviceTest";
    private final static byte    RFC2217_COM_PORT_OPTION = 0x2c;
    private final static byte    RFC2217_SET_BAUDRATE = 1;
    private final static byte    RFC2217_SET_DATASIZE = 2;
    private final static byte    RFC2217_SET_PARITY   = 3;
    private final static byte    RFC2217_SET_STOPSIZE = 4;
    private final static byte    RFC2217_PURGE_DATA = 12;

    private Context context;
    private UsbManager usbManager;
    private UsbSerialDriver usbSerialDriver;
    private UsbDeviceConnection usbDeviceConnection;
    private UsbSerialPort usbSerialPort;
    private SerialInputOutputManager usbIoManager;
    private final Deque<byte[]> usbReadBuffer = new LinkedList<>();
    private Exception usbReadError;
    private boolean usbReadBlock = false;
    private long usbReadTime = 0;

    private static TelnetClient telnetClient;
    private static InputStream telnetReadStream;
    private static OutputStream telnetWriteStream;
    private static Integer[] telnetComPortOptionCounter = {0};
    private int telnetWriteDelay = 0;
    private boolean isCp21xxRestrictedPort = false; // second port of Cp2105 has limited dataBits, stopBits, parity

    enum UsbOpenFlags { NO_IOMANAGER_THREAD, NO_CONTROL_LINE_INIT };

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
        telnetClient = null;
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
        telnetClient.sendCommand((byte)TelnetCommand.SB);
        telnetWriteStream.write(new byte[] {RFC2217_COM_PORT_OPTION, RFC2217_PURGE_DATA, 3});
        telnetClient.sendCommand((byte)TelnetCommand.SE);
        for(int i=0; i<TELNET_COMMAND_WAIT; i++) {
            if(telnetComPortOptionCounter[0] == 1) break;
            Thread.sleep(1);
        }
        assertEquals("telnet connection lost", 1, telnetComPortOptionCounter[0].intValue());
        telnetWriteDelay = 0;

        context = InstrumentationRegistry.getContext();
        usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        if(availableDrivers.isEmpty()) {
            ProbeTable customTable = new ProbeTable();
            customTable.addProduct(0x2342, 0x8036, CdcAcmSerialDriver.class); // arduino multiport cdc witch custom VID
            availableDrivers = new UsbSerialProber(customTable).findAllDrivers(usbManager);
        }
        assertEquals("no USB device found", 1, availableDrivers.size());
        usbSerialDriver = availableDrivers.get(0);
        if(test_device_driver != null) {
            String driverName = usbSerialDriver.getClass().getSimpleName();
            assertEquals(test_device_driver+"SerialDriver", driverName);
        }
        assertTrue( usbSerialDriver.getPorts().size() > test_device_port);
        usbSerialPort = usbSerialDriver.getPorts().get(test_device_port);
        Log.i(TAG, "Using USB device "+ usbSerialPort.toString()+" driver="+usbSerialDriver.getClass().getSimpleName());
        isCp21xxRestrictedPort = usbSerialDriver instanceof Cp21xxSerialDriver && usbSerialDriver.getPorts().size()==2 && test_device_port == 1;

        if (!usbManager.hasPermission(usbSerialPort.getDriver().getDevice())) {
            Log.d(TAG,"USB permission ...");
            final Boolean[] granted = {null};
            BroadcastReceiver usbReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                granted[0] = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                }
            };
            PendingIntent permissionIntent = PendingIntent.getBroadcast(context, 0, new Intent("com.android.example.USB_PERMISSION"), 0);
            IntentFilter filter = new IntentFilter("com.android.example.USB_PERMISSION");
            context.registerReceiver(usbReceiver, filter);
            usbManager.requestPermission(usbSerialDriver.getDevice(), permissionIntent);
            for(int i=0; i<5000; i++) {
                if(granted[0] != null) break;
                Thread.sleep(1);
            }
            Log.d(TAG,"USB permission "+granted[0]);
            assertTrue("USB permission dialog not confirmed", granted[0]==null?false:granted[0]);
            telnetRead(-1); // doesn't look related here, but very often after usb permission dialog the first test failed with telnet garbage
        }
    }

    @After
    public void tearDown() throws IOException {
        try {
            if(usbIoManager != null)
                usbRead(0);
            else
                usbSerialPort.purgeHwBuffers(true, true);
        } catch (Exception ignored) {}
        try {
            telnetRead(0);
        } catch (Exception ignored) {}
        usbClose();
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

    private class TestBuffer {
        private byte[] buf;
        private int len;

        private TestBuffer(int length) {
            len = 0;
            buf = new byte[length];
            int i=0;
            int j=0;
            for(j=0; j<length/16; j++)
                for(int k=0; k<16; k++)
                    buf[i++]=(byte)j;
            while(i<length)
                buf[i++]=(byte)j;
        }

        private boolean testRead(byte[] data) {
            assertNotEquals(0, data.length);
            assertTrue("got " + (len+data.length) +" bytes", (len+data.length) <= buf.length);
            for(int j=0; j<data.length; j++)
                assertEquals("at pos "+(len+j), (byte)((len+j)/16), data[j]);
            len += data.length;
            //Log.d(TAG, "read " + len);
            return len == buf.length;
        }
    }

    // wait full time
    private byte[] telnetRead() throws Exception {
        return telnetRead(-1);
    }

    private byte[] telnetRead(int expectedLength) throws Exception {
        long end = System.currentTimeMillis() + TELNET_READ_WAIT;
        ByteBuffer buf = ByteBuffer.allocate(65536);
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

    private void usbClose() {
        usbClose(EnumSet.noneOf(UsbOpenFlags.class));
    }

    private void usbClose(EnumSet<UsbOpenFlags> flags) {
        if (usbIoManager != null) {
            usbIoManager.setListener(null);
            usbIoManager.stop();
        }
        if (usbSerialPort != null) {
            try {
                if(!flags.contains(UsbOpenFlags.NO_CONTROL_LINE_INIT)) {
                    usbSerialPort.setDTR(false);
                    usbSerialPort.setRTS(false);
                }
            } catch (Exception ignored) {
            }
            try {
                usbSerialPort.close();
            } catch (Exception ignored) {
            }
            usbSerialPort = null;
        }
        usbDeviceConnection = null; // closed in usbSerialPort.close()
        if(usbIoManager != null) {
            for (int i = 0; i < 2000; i++) {
                if (SerialInputOutputManager.State.STOPPED == usbIoManager.getState()) break;
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            assertEquals(SerialInputOutputManager.State.STOPPED, usbIoManager.getState());
            usbIoManager = null;
        }
    }

    private void usbOpen() throws Exception {
        usbOpen(EnumSet.noneOf(UsbOpenFlags.class), 0);
    }

    private void usbOpen(EnumSet<UsbOpenFlags> flags) throws Exception {
        usbOpen(flags, 0);
    }

    private void usbOpen(EnumSet<UsbOpenFlags> flags, int ioManagerTimeout) throws Exception {
        usbDeviceConnection = usbManager.openDevice(usbSerialDriver.getDevice());
        usbSerialPort = usbSerialDriver.getPorts().get(test_device_port);
        usbSerialPort.open(usbDeviceConnection);
        if(!flags.contains(UsbOpenFlags.NO_CONTROL_LINE_INIT)) {
            usbSerialPort.setDTR(true);
            usbSerialPort.setRTS(true);
        }
        if(!flags.contains(UsbOpenFlags.NO_IOMANAGER_THREAD)) {
            usbIoManager = new SerialInputOutputManager(usbSerialPort, this) {
                @Override
                public void run() {
                    if (SERIAL_INPUT_OUTPUT_MANAGER_THREAD_PRIORITY != null)
                        Process.setThreadPriority(SERIAL_INPUT_OUTPUT_MANAGER_THREAD_PRIORITY);
                    super.run();
                }
            };
            usbIoManager.setReadTimeout(ioManagerTimeout);
            usbIoManager.setWriteTimeout(ioManagerTimeout);
            Executors.newSingleThreadExecutor().submit(usbIoManager);
        }
        synchronized (usbReadBuffer) {
            usbReadBuffer.clear();
        }
        usbReadError = null;
    }

    // wait full time
    private byte[] usbRead() throws Exception {
        return usbRead(-1);
    }

    private byte[] usbRead(int expectedLength) throws Exception {
        long end = System.currentTimeMillis() + USB_READ_WAIT;
        ByteBuffer buf = ByteBuffer.allocate(16*1024);
        if(usbIoManager != null) {
            while (System.currentTimeMillis() < end) {
                if(usbReadError != null)
                    throw usbReadError;
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
                int len = usbSerialPort.read(b1, USB_READ_WAIT);
                if (len > 0)
                    buf.put(b1, 0, len);
                if (expectedLength >= 0 && buf.position() >= expectedLength)
                    break;
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
        for(int i=0; i<TELNET_COMMAND_WAIT; i++) {
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
        usbReadError = e;
        //fail("usb connection lost");
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
        byte[] buf1 = new byte[]{ 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16};
        byte[] buf2 = new byte[]{ 0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26};
        byte[] data;

        telnetWrite(buf1);
        data = usbRead(buf1.length);
        assertThat(reason, data, equalTo(buf1)); // includes array content in output
        //assertArrayEquals("net2usb".getBytes(), data); // only includes array length in output
        usbWrite(buf2);
        data = telnetRead(buf2.length);
        assertThat(reason, data, equalTo(buf2));
    }

    @Test
    public void openClose() throws Exception {
        usbOpen();
        telnetParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        usbParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        doReadWrite("");

        try {
            usbSerialPort.open(usbDeviceConnection);
            fail("already open expected");
        } catch (IOException ignored) {
        }
        doReadWrite("");

        usbClose();
        usbSerialPort = usbSerialDriver.getPorts().get(test_device_port);
        try {
            usbSerialPort.close();
            fail("already closed expected");
        } catch (IOException ignored) {
        }
        try {
            usbWrite(new byte[]{0x00});
            fail("write error expected");
        } catch (IOException ignored) {
        }
        try {
            usbRead(1);
            fail("read error expected");
        } catch (IOException ignored) {
        }
        try {
            usbParameters(9600, 8, 1, UsbSerialPort.PARITY_NONE);
            fail("error expected");
        } catch (IOException ignored) {
        } catch (NullPointerException ignored) {
        }
        usbSerialPort = null;

        usbOpen();
        telnetParameters(9600, 8, 1, UsbSerialPort.PARITY_NONE);
        usbParameters(9600, 8, 1, UsbSerialPort.PARITY_NONE);
        doReadWrite("");

        // close port before iomanager
        assertEquals(SerialInputOutputManager.State.RUNNING, usbIoManager.getState());
        usbSerialPort.close();
        for (int i = 0; i < 1000; i++) {
            if (usbIoManager.getState() == SerialInputOutputManager.State.STOPPED)
                break;
            Thread.sleep(1);
        }
        // assertEquals(SerialInputOutputManager.State.STOPPED, usbIoManager.getState());
        // unstable. null'ify not-stopped ioManager, else usbClose would try again
        if(SerialInputOutputManager.State.STOPPED != usbIoManager.getState())
            usbIoManager = null;
    }

    @Test
    public void baudRate() throws Exception {
        usbOpen();

        if (false) { // default baud rate
            // CP2102: only works if first connection after attaching device
            // PL2303, FTDI: it's not 9600
            telnetParameters(9600, 8, 1, UsbSerialPort.PARITY_NONE);

            doReadWrite("");
        }

        // invalid values
        try {
            usbParameters(-1, 8, 1, UsbSerialPort.PARITY_NONE);
            fail("invalid baud rate");
        } catch (IllegalArgumentException ignored) {
        }
        try {
            usbParameters(0, 8, 1, UsbSerialPort.PARITY_NONE);
            fail("invalid baud rate");
        } catch (IllegalArgumentException ignored) {
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
                fail("invalid baudrate 1");
        } catch (UnsupportedOperationException ignored) { // ch340
        } catch (IOException ignored) { // cp2105 second port
        } catch (IllegalArgumentException ignored) {
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
        } catch (ArithmeticException ignored) { // ch340
        } catch (IOException ignored) { // cp2105 second port
        } catch (IllegalArgumentException ignored) {
        }

        for(int baudRate : new int[] {300, 2400, 19200, 115200} ) {
            if(baudRate == 300 && isCp21xxRestrictedPort) {
                try {
                    usbParameters(baudRate, 8, 1, UsbSerialPort.PARITY_NONE);
                    fail("baudrate 300 on cp21xx restricted port");
                } catch (IOException ignored) {
                }
                continue;
            }
            telnetParameters(baudRate, 8, 1, UsbSerialPort.PARITY_NONE);
            usbParameters(baudRate, 8, 1, UsbSerialPort.PARITY_NONE);

            doReadWrite(baudRate+"/8N1");
        }
        if(rfc2217_server_nonstandard_baudrates && !isCp21xxRestrictedPort) {
            // usbParameters does not fail on devices that do not support nonstandard baud rates
            usbParameters(42000, 8, 1, UsbSerialPort.PARITY_NONE);
            telnetParameters(42000, 8, 1, UsbSerialPort.PARITY_NONE);

            byte[] buf1 = "abc".getBytes();
            byte[] buf2 = "ABC".getBytes();
            byte[] data1, data2;
            usbWrite(buf1);
            data1 = telnetRead();
            telnetWrite(buf2);
            data2 = usbRead();
            if (usbSerialDriver instanceof ProlificSerialDriver) {
                // not supported
                assertNotEquals(data1, buf2);
                assertNotEquals(data2, buf2);
            } else if (usbSerialDriver instanceof Cp21xxSerialDriver) {
                if (usbSerialDriver.getPorts().size() > 1) {
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
            telnetParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
            usbParameters(2400, 8, 1, UsbSerialPort.PARITY_NONE);

            byte[] data;
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

        usbOpen();
        for(int i: new int[] {0, 4, 9}) {
            try {
                usbParameters(19200, i, 1, UsbSerialPort.PARITY_NONE);
                fail("invalid databits "+i);
            } catch (IllegalArgumentException ignored) {
            }
        }

        // telnet -> usb
        usbParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        telnetParameters(19200, 7, 1, UsbSerialPort.PARITY_NONE);
        telnetWrite(new byte[] {0x00});
        Thread.sleep(10); // one bit is 0.05 milliseconds long, wait >> stop bit
        telnetWrite(new byte[] {(byte)0xff});
        data = usbRead(2);
        assertThat("19200/7N1", data, equalTo(new byte[] {(byte)0x80, (byte)0xff}));

        telnetParameters(19200, 6, 1, UsbSerialPort.PARITY_NONE);
        telnetWrite(new byte[] {0x00});
        Thread.sleep(10);
        telnetWrite(new byte[] {(byte)0xff});
        data = usbRead(2);
        assertThat("19000/6N1", data, equalTo(new byte[] {(byte)0xc0, (byte)0xff}));

        telnetParameters(19200, 5, 1, UsbSerialPort.PARITY_NONE);
        telnetWrite(new byte[] {0x00});
        Thread.sleep(10);
        telnetWrite(new byte[] {(byte)0xff});
        data = usbRead(2);
        assertThat("19000/5N1", data, equalTo(new byte[] {(byte)0xe0, (byte)0xff}));

        // usb -> telnet
        try {
            telnetParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
            usbParameters(19200, 7, 1, UsbSerialPort.PARITY_NONE);
            usbWrite(new byte[]{0x00});
            Thread.sleep(10);
            usbWrite(new byte[]{(byte) 0xff});
            data = telnetRead(2);
            assertThat("19000/7N1", data, equalTo(new byte[]{(byte) 0x80, (byte) 0xff}));
        } catch (UnsupportedOperationException e) {
                if(!isCp21xxRestrictedPort)
                    throw e;
        }
        try {
            usbParameters(19200, 6, 1, UsbSerialPort.PARITY_NONE);
            usbWrite(new byte[]{0x00});
            Thread.sleep(10);
            usbWrite(new byte[]{(byte) 0xff});
            data = telnetRead(2);
            assertThat("19000/6N1", data, equalTo(new byte[]{(byte) 0xc0, (byte) 0xff}));
        } catch (UnsupportedOperationException e) {
            if (!(isCp21xxRestrictedPort || usbSerialDriver instanceof FtdiSerialDriver))
                throw e;
        }
        try {
            usbParameters(19200, 5, 1, UsbSerialPort.PARITY_NONE);
            usbWrite(new byte[] {0x00});
            Thread.sleep(5);
            usbWrite(new byte[] {(byte)0xff});
            data = telnetRead(2);
            assertThat("19000/5N1", data, equalTo(new byte[] {(byte)0xe0, (byte)0xff}));
        } catch (UnsupportedOperationException e) {
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

        usbOpen();
        for(int i: new int[] {-1, 5}) {
            try {
                usbParameters(19200, 8, 1, i);
                fail("invalid parity "+i);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if(isCp21xxRestrictedPort) {
            usbParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
            usbParameters(19200, 8, 1, UsbSerialPort.PARITY_EVEN);
            usbParameters(19200, 8, 1, UsbSerialPort.PARITY_ODD);
            try {
                usbParameters(19200, 8, 1, UsbSerialPort.PARITY_MARK);
                fail("parity mark");
            } catch (UnsupportedOperationException ignored) {}
            try {
                usbParameters(19200, 8, 1, UsbSerialPort.PARITY_SPACE);
                fail("parity space");
            } catch (UnsupportedOperationException ignored) {}
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
        } else {
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
            telnetParameters(19200, 7, 1, UsbSerialPort.PARITY_MARK);
            telnetWrite(_8n1);
            data = usbRead(4);
            assertThat("19200/7M1", data, equalTo(_7m1));

            telnetParameters(19200, 7, 1, UsbSerialPort.PARITY_SPACE);
            telnetWrite(_8n1);
            data = usbRead(4);
            assertThat("19200/7S1", data, equalTo(_7s1));

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

        usbOpen();
        for (int i : new int[]{0, 4}) {
            try {
                usbParameters(19200, 8, i, UsbSerialPort.PARITY_NONE);
                fail("invalid stopbits " + i);
            } catch (IllegalArgumentException ignored) {
            }
        }

        if (usbSerialDriver instanceof CdcAcmSerialDriver) {
            usbParameters(19200, 8, UsbSerialPort.STOPBITS_2, UsbSerialPort.PARITY_NONE);
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
            } catch(UnsupportedOperationException e) {
                if(!isCp21xxRestrictedPort)
                    throw e;
            }
            try {
                usbParameters(19200, 8, UsbSerialPort.STOPBITS_1_5, UsbSerialPort.PARITY_NONE);
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

        probeTable.addProduct(usbSerialDriver.getDevice().getVendorId(), usbSerialDriver.getDevice().getProductId(), usbSerialDriver.getClass());
        availableDrivers = new UsbSerialProber(probeTable).findAllDrivers(usbManager);
        assertEquals(1, availableDrivers.size());
        assertEquals(availableDrivers.get(0).getClass(), usbSerialDriver.getClass());
    }

    @Test
    public void writeTimeout() throws Exception {
        usbOpen();
        usbParameters(115200, 8, 1, UsbSerialPort.PARITY_NONE);
        telnetParameters(115200, 8, 1, UsbSerialPort.PARITY_NONE);

        // Basically all devices have a UsbEndpoint.getMaxPacketSize() 64. When the timeout
        // in usbSerialPort.write() is reached, some packets have been written and the rest
        // is discarded. bulkTransfer() does not return the number written so far, but -1.
        // With 115200 baud and 1/2 second timeout, typical values are:
        //   ch340    6080 of 6144
        //   pl2302   5952 of 6144
        //   cp2102   6400 of 7168
        //   cp2105   6272 of 7168
        //   ft232    5952 of 6144
        //   ft2232   9728 of 10240
        //   arduino   128 of 144
        int timeout = 500;
        int len = 0;
        int startLen = 1024;
        int step = 1024;
        int minLen = 4069;
        int maxLen = 12288;
        int bufferSize = 511;
        TestBuffer buf = new TestBuffer(len);
        if(usbSerialDriver instanceof CdcAcmSerialDriver) {
            startLen = 16;
            step = 16;
            minLen = 128;
            maxLen = 256;
            bufferSize = 31;
        }

        try {
            for (len = startLen; len < maxLen; len += step) {
                buf = new TestBuffer(len);
                Log.d(TAG, "write buffer size " + len);
                usbSerialPort.write(buf.buf, timeout);
                while (!buf.testRead(telnetRead(-1)))
                    ;
            }
            fail("write timeout expected between " + minLen + " and " + maxLen + ", is " + len);
        } catch (IOException e) {
            Log.d(TAG, "usbWrite failed", e);
            while (true) {
                byte[] data = telnetRead(-1);
                if (data.length == 0) break;
                if (buf.testRead(data)) break;
            }
            Log.d(TAG, "received " + buf.len + " of " + len + " bytes of failing usbWrite");
            assertTrue("write timeout expected between " + minLen + " and " + maxLen + ", is " + len, len > minLen);
        }

        // With smaller writebuffer, the timeout is used per bulkTransfer and each call 'fits'
        // into this timout, but shouldn't further calls only use the remaining timeout?
        ((CommonUsbSerialPort) usbSerialPort).setWriteBufferSize(bufferSize);
        len = maxLen;
        buf = new TestBuffer(len);
        Log.d(TAG, "write buffer size " + len);
        usbSerialPort.write(buf.buf, timeout);
        while (!buf.testRead(telnetRead(-1)))
            ;
    }

    @Test
    public void writeFragments() throws Exception {
        usbOpen();
        usbParameters(115200, 8, 1, UsbSerialPort.PARITY_NONE);
        telnetParameters(115200, 8, 1, UsbSerialPort.PARITY_NONE);

        ((CommonUsbSerialPort) usbSerialPort).setWriteBufferSize(12);
        ((CommonUsbSerialPort) usbSerialPort).setWriteBufferSize(12); // keeps last buffer
        TestBuffer buf = new TestBuffer(256);
        usbSerialPort.write(buf.buf, 5000);
        while (!buf.testRead(telnetRead(-1)))
            ;
    }

    @Test
    // provoke data loss, when data is not read fast enough
    public void readBufferOverflow() throws Exception {
        if(usbSerialDriver instanceof CdcAcmSerialDriver)
            telnetWriteDelay = 10; // arduino_leonardo_bridge.ino sends each byte in own USB packet, which is horribly slow
        usbOpen();
        usbParameters(115200, 8, 1, UsbSerialPort.PARITY_NONE);
        telnetParameters(115200, 8, 1, UsbSerialPort.PARITY_NONE);

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
            usbReadBlock = true;
            for (linenr = 0; linenr < bufferSize/8; linenr++) {
                line = String.format("%07d,", linenr);
                telnetWrite(line.getBytes());
                expected.append(line);
            }
            usbReadBlock = false;

            // slowly write new data, until old data is completely read from buffer and new data is received
            boolean found = false;
            for (; linenr < bufferSize/8 + maxWait/10 && !found; linenr++) {
                line = String.format("%07d,", linenr);
                telnetWrite(line.getBytes());
                Thread.sleep(10);
                expected.append(line);
                data.append(new String(usbRead(0)));
                found = data.toString().endsWith(line);
            }
            while(!found) {
                // use waiting read to clear input queue, else next test would see unexpected data
                byte[] rest = usbRead(-1);
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
        // CDC arduino_leonardo_bridge.ini has transfer speed ~ 100 byte/sec
        // all other devices are near physical limit with ~ 10-12k/sec
        //
        // readBufferOverflow provokes read errors, but they can also happen here where the data is actually read fast enough.
        // Android is not a real time OS, so there is no guarantee that the USB thread is scheduled, or it might be blocked by Java garbage collection.
        // Using SERIAL_INPUT_OUTPUT_MANAGER_THREAD_PRIORITY=THREAD_PRIORITY_URGENT_AUDIO sometimes reduced errors by factor 10, sometimes not at all!
        //
        int diffLen = readSpeedInt(5, 0);
        if(usbSerialDriver instanceof Ch34xSerialDriver && diffLen == -1)
             diffLen = 0; // todo: investigate last packet loss
        assertEquals(0, diffLen);
    }

    private int readSpeedInt(int writeSeconds, int readTimeout) throws Exception {
        int baudrate = 115200;
        if(usbSerialDriver instanceof Ch34xSerialDriver)
            baudrate = 38400;
        int writeAhead = 5*baudrate/10; // write ahead for another 5 second read
        if(usbSerialDriver instanceof CdcAcmSerialDriver)
            writeAhead = 50;

        usbOpen(EnumSet.noneOf(UsbOpenFlags.class), readTimeout);
        usbParameters(baudrate, 8, 1, UsbSerialPort.PARITY_NONE);
        telnetParameters(baudrate, 8, 1, UsbSerialPort.PARITY_NONE);

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
        while(!found) {
            // use waiting read to clear input queue, else next test would see unexpected data
            byte[] rest = usbRead(-1);
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
        // CDC arduino_leonardo_bridge.ini has transfer speed ~ 100 byte/sec
        // all other devices can get near physical limit:
        // longlines=true:, speed is near physical limit at 11.5k
        // longlines=false: speed is 3-4k for all devices, as more USB packets are required
        usbOpen();
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

    @Test
    public void purgeHwBuffers() throws Exception {
        // purge write buffer
        // 2400 is slowest baud rate for isCp21xxRestrictedPort
        usbOpen();
        usbParameters(2400, 8, 1, UsbSerialPort.PARITY_NONE);
        telnetParameters(2400, 8, 1, UsbSerialPort.PARITY_NONE);
        byte[] buf = new byte[64];
        for(int i=0; i<buf.length; i++) buf[i]='a';
        StringBuilder data = new StringBuilder();

        usbWrite(buf);
        Thread.sleep(50); // ~ 12 bytes
        boolean purged = usbSerialPort.purgeHwBuffers(true, false);
        usbWrite("bcd".getBytes());
        Thread.sleep(50);
        while(data.length()==0 || data.charAt(data.length()-1)!='d')
            data.append(new String(telnetRead()));
        Log.i(TAG, "purgeHwBuffers " + purged + ": " + buf.length+1 + " -> " + data.length());

        assertTrue(data.length() > 5);
        if(purged)
            assertTrue(data.length() < buf.length+1);
        else
            assertEquals(data.length(), buf.length + 3);

        // purge read buffer
        usbClose();
        usbOpen(EnumSet.of(UsbOpenFlags.NO_IOMANAGER_THREAD));
        usbParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        telnetParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        telnetWrite("x".getBytes());
        Thread.sleep(10); // ~ 20 bytes
        purged = usbSerialPort.purgeHwBuffers(false, true);
        Log.d(TAG, "purged = " + purged);
        telnetWrite("y".getBytes());
        Thread.sleep(10); // ~ 20 bytes
        if(purged) {
            if(usbSerialDriver instanceof Cp21xxSerialDriver) { // only working on some devices/ports
                if(isCp21xxRestrictedPort) {
                    assertThat(usbRead(2), equalTo("xy".getBytes())); // cp2105/1
                } else if(usbSerialDriver.getPorts().size() > 1) {
                    assertThat(usbRead(1), equalTo("y".getBytes()));  // cp2105/0
                } else {
                    assertThat(usbRead(2), equalTo("xy".getBytes())); // cp2102
                }
            } else {
                assertThat(usbRead(1), equalTo("y".getBytes()));
            }
        } else {
            assertThat(usbRead(2), equalTo("xy".getBytes()));
        }
    }

    @Test
    public void writeAsync() throws Exception {
        if (usbSerialDriver instanceof FtdiSerialDriver)
            return; // periodically sends status messages, so does not block here

        byte[] data, buf = new byte[]{1};

        usbIoManager = new SerialInputOutputManager(null);
        assertEquals(null, usbIoManager.getListener());
        usbIoManager.setListener(this);
        assertEquals(this, usbIoManager.getListener());
        usbIoManager = new SerialInputOutputManager(usbSerialPort, this);
        assertEquals(this, usbIoManager.getListener());
        assertEquals(0, usbIoManager.getReadTimeout());
        usbIoManager.setReadTimeout(100);
        assertEquals(100, usbIoManager.getReadTimeout());
        assertEquals(0, usbIoManager.getWriteTimeout());
        usbIoManager.setWriteTimeout(200);
        assertEquals(200, usbIoManager.getWriteTimeout());

        // w/o timeout: write delayed until something is read
        usbOpen();
        usbParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        telnetParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        usbIoManager.writeAsync(buf);
        usbIoManager.writeAsync(buf);
        data = telnetRead(1);
        assertEquals(0, data.length);
        telnetWrite(buf);
        data = usbRead(1);
        assertEquals(1, data.length);
        data = telnetRead(2);
        assertEquals(2, data.length);
        try {
            usbIoManager.setReadTimeout(100);
            fail("IllegalStateException expected");
        } catch (IllegalStateException ignored) {}
        usbClose();

        // with timeout: write after timeout
        usbOpen(EnumSet.noneOf(UsbOpenFlags.class), 100);
        usbParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        telnetParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        usbIoManager.writeAsync(buf);
        usbIoManager.writeAsync(buf);
        data = telnetRead(2);
        assertEquals(2, data.length);
        usbIoManager.setReadTimeout(200);
    }

    @Test
    public void readTimeout() throws Exception {
        if (usbSerialDriver instanceof FtdiSerialDriver)
            return; // periodically sends status messages, so does not block here
        final Boolean[] closed = {Boolean.FALSE};

        Runnable closeThread = new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                usbClose();
                closed[0] = true;
            }
        };

        usbOpen(EnumSet.of(UsbOpenFlags.NO_IOMANAGER_THREAD));
        usbParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        telnetParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);

        byte[] buf = new byte[]{1};
        int len,i,j;
        long time;

        // w/o timeout
        telnetWrite(buf);
        len = usbSerialPort.read(buf, 0); // not blocking because data is available
        assertEquals(1, len);

        time = System.currentTimeMillis();
        closed[0] = false;
        Executors.newSingleThreadExecutor().submit(closeThread);
        len = usbSerialPort.read(buf, 0); // blocking until close()
        assertEquals(0, len);
        assertTrue(System.currentTimeMillis()-time >= 100);
        // wait for usbClose
        for(i=0; i<100; i++) {
            if(closed[0]) break;
            Thread.sleep(1);
        }
        assertTrue("not closed in time", closed[0]);

        // with timeout
        usbOpen(EnumSet.of(UsbOpenFlags.NO_IOMANAGER_THREAD));
        usbParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        telnetParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);

        int longTimeout = 1000;
        int shortTimeout = 10;
        time = System.currentTimeMillis();
        len = usbSerialPort.read(buf, shortTimeout);
        assertEquals(0, len);
        assertTrue(System.currentTimeMillis()-time < 100);

        // no issue with slow transfer rate and short read timeout
        time = System.currentTimeMillis();
        for(i=0; i<50; i++) {
            Thread.sleep(10);
            telnetWrite(buf);
            for(j=0; j<20; j++) {
                len = usbSerialPort.read(buf, shortTimeout);
                if (len > 0)
                    break;
            }
            assertEquals("failed after " + i, 1, len);
        }
        Log.i(TAG, "average time per read " + (System.currentTimeMillis()-time)/i + " msec");

        if(!(usbSerialDriver instanceof CdcAcmSerialDriver)) {
            int diffLen;
            usbClose();
            // no issue with high transfer rate and long read timeout
            diffLen = readSpeedInt(5, longTimeout);
            if(usbSerialDriver instanceof Ch34xSerialDriver && diffLen == -1)
                diffLen = 0; // todo: investigate last packet loss
            assertEquals(0, diffLen);
            usbClose();
            // date loss with high transfer rate and short read timeout !!!
            diffLen = readSpeedInt(5, shortTimeout);
            assertNotEquals(0, diffLen);

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

        if(!(usbSerialDriver instanceof CdcAcmSerialDriver)) {
            wrongDeviceConnection = usbManager.openDevice(usbSerialDriver.getDevice());
            wrongSerialDriver = new CdcAcmSerialDriver(usbSerialDriver.getDevice());
            wrongSerialPort = wrongSerialDriver.getPorts().get(0);
            try {
                wrongSerialPort.open(wrongDeviceConnection);
                wrongSerialPort.setParameters(115200, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE); // ch340 fails here
                wrongSerialPort.write(new byte[]{1}, 1000); // pl2302 does not fail, but sends with wrong baud rate
                if(!(usbSerialDriver instanceof ProlificSerialDriver))
                    fail("error expected");
            } catch (IOException ignored) {
            }
            try {
                if(usbSerialDriver instanceof ProlificSerialDriver) {
                    assertNotEquals(new byte[]{1}, telnetRead());
                }
                wrongSerialPort.close();
                if(!(usbSerialDriver instanceof Ch34xSerialDriver |
                     usbSerialDriver instanceof ProlificSerialDriver))
                    fail("error expected");
            } catch (IOException ignored) {
            }
        }
        if(!(usbSerialDriver instanceof Ch34xSerialDriver)) {
            wrongDeviceConnection = usbManager.openDevice(usbSerialDriver.getDevice());
            wrongSerialDriver = new Ch34xSerialDriver(usbSerialDriver.getDevice());
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
        if(!(usbSerialDriver instanceof Cp21xxSerialDriver | usbSerialDriver instanceof FtdiSerialDriver)) {
            wrongDeviceConnection = usbManager.openDevice(usbSerialDriver.getDevice());
            wrongSerialDriver = new Cp21xxSerialDriver(usbSerialDriver.getDevice());
            wrongSerialPort = wrongSerialDriver.getPorts().get(0);
            try {
                wrongSerialPort.open(wrongDeviceConnection);
                //if(usbSerialDriver instanceof FtdiSerialDriver)
                //    wrongSerialPort.setParameters(115200, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE); // ch340 fails here
                fail("error expected");
            } catch (IOException ignored) {
            }
            try {
                wrongSerialPort.close();
                //if(!(usbSerialDriver instanceof FtdiSerialDriver))
                //    fail("error expected");
            } catch (IOException ignored) {
            }
        }
        if(!(usbSerialDriver instanceof FtdiSerialDriver)) {
            wrongDeviceConnection = usbManager.openDevice(usbSerialDriver.getDevice());
            wrongSerialDriver = new FtdiSerialDriver(usbSerialDriver.getDevice());
            wrongSerialPort = wrongSerialDriver.getPorts().get(0);
            try {
                wrongSerialPort.open(wrongDeviceConnection);
                if(usbSerialDriver instanceof Cp21xxSerialDriver)
                    wrongSerialPort.setParameters(115200, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE); // ch340 fails here
                fail("error expected");
            } catch (IOException ignored) {
            }
            try {
                wrongSerialPort.close();
                if(!(usbSerialDriver instanceof Cp21xxSerialDriver))
                    fail("error expected");
            } catch (IOException ignored) {
            }
        }
        if(!(usbSerialDriver instanceof ProlificSerialDriver)) {
            wrongDeviceConnection = usbManager.openDevice(usbSerialDriver.getDevice());
            wrongSerialDriver = new ProlificSerialDriver(usbSerialDriver.getDevice());
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
        usbOpen();
        telnetParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        usbParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        doReadWrite("");
    }

    @Test
    /* test not done by RFC2217 server. Instead output control lines are connected to
         input control lines with a binary decoder 74LS138, 74LS139, 74HC... or ...
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
     */
    public void controlLines() throws Exception {
        byte[] data;
        int sleep = 10;

        // output lines are supported by all drivers
        boolean inputLinesSupported = false;
        boolean inputLinesConnected = false;
        if (usbSerialDriver instanceof FtdiSerialDriver) {
            inputLinesSupported = true;
            inputLinesConnected = usbSerialDriver.getPorts().size()==2; // I only have 74LS138 connected at FT2232
        } else if (usbSerialDriver instanceof ProlificSerialDriver) {
            inputLinesSupported = true;
            inputLinesConnected = true;
        }

        usbOpen(EnumSet.of(UsbOpenFlags.NO_CONTROL_LINE_INIT));
        usbParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        telnetParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        Thread.sleep(sleep);

        if(usbSerialDriver instanceof ProlificSerialDriver) {
            // the initial status is sometimes not available or wrong.
            // this is more likely if other tests have been executed before.
            // start thread and wait until status hopefully updated.
            usbSerialPort.getRI(); // todo
            Thread.sleep(sleep);
            assertTrue(usbSerialPort.getRI());
        }
        data = "none".getBytes();
        assertFalse(usbSerialPort.getRTS());
        assertFalse(usbSerialPort.getCTS());
        assertFalse(usbSerialPort.getDTR());
        assertFalse(usbSerialPort.getDSR());
        assertFalse(usbSerialPort.getCD());
        assertEquals(usbSerialPort.getRI(), inputLinesConnected);
        telnetWrite(data);
        if(usbSerialDriver instanceof CdcAcmSerialDriver)
            // arduino: control line feedback as serial_state notification is not implemented.
            // It does not send w/o RTS or DTR, so these control lines can be partly checked here.
            assertEquals(0, usbRead().length);
        else
            assertThat(Arrays.toString(data), usbRead(4), equalTo(data));
        usbWrite(data);
        assertThat(Arrays.toString(data), telnetRead(4), equalTo(data));

        data = "rts ".getBytes();
        usbSerialPort.setRTS(true);
        Thread.sleep(sleep);
        assertTrue(usbSerialPort.getRTS());
        assertEquals(usbSerialPort.getCTS(), inputLinesConnected);
        assertFalse(usbSerialPort.getDTR());
        assertFalse(usbSerialPort.getDSR());
        assertFalse(usbSerialPort.getCD());
        assertFalse(usbSerialPort.getRI());
        telnetWrite(data);
        assertThat(Arrays.toString(data), usbRead(4), equalTo(data));
        usbWrite(data);
        assertThat(Arrays.toString(data), telnetRead(4), equalTo(data));

        data = "both".getBytes();
        usbSerialPort.setDTR(true);
        Thread.sleep(sleep);
        assertTrue(usbSerialPort.getRTS());
        assertFalse(usbSerialPort.getCTS());
        assertTrue(usbSerialPort.getDTR());
        assertFalse(usbSerialPort.getDSR());
        assertEquals(usbSerialPort.getCD(), inputLinesConnected);
        assertFalse(usbSerialPort.getRI());
        telnetWrite(data);
        assertThat(Arrays.toString(data), usbRead(4), equalTo(data));
        usbWrite(data);
        assertThat(Arrays.toString(data), telnetRead(4), equalTo(data));

        data = "dtr ".getBytes();
        usbSerialPort.setRTS(false);
        Thread.sleep(sleep);
        assertFalse(usbSerialPort.getRTS());
        assertFalse(usbSerialPort.getCTS());
        assertTrue(usbSerialPort.getDTR());
        assertEquals(usbSerialPort.getDSR(), inputLinesConnected);
        assertFalse(usbSerialPort.getCD());
        assertFalse(usbSerialPort.getRI());
        telnetWrite(data);
        assertThat(Arrays.toString(data), usbRead(4), equalTo(data));
        usbWrite(data);
        assertThat(Arrays.toString(data), telnetRead(4), equalTo(data));

        // state retained over close/open
        boolean inputRetained = inputLinesConnected;
        boolean outputRetained = true;
        if(usbSerialDriver instanceof FtdiSerialDriver)
            outputRetained = false; // todo
        usbClose(EnumSet.of(UsbOpenFlags.NO_CONTROL_LINE_INIT));
        usbOpen(EnumSet.of(UsbOpenFlags.NO_CONTROL_LINE_INIT, UsbOpenFlags.NO_IOMANAGER_THREAD));
        usbParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);

        assertFalse(usbSerialPort.getRTS());
        assertFalse(usbSerialPort.getCTS());
        assertEquals(usbSerialPort.getDTR(), outputRetained);
        assertEquals(usbSerialPort.getDSR(), inputRetained);
        assertFalse(usbSerialPort.getCD());
        assertFalse(usbSerialPort.getRI());

        usbClose(EnumSet.of(UsbOpenFlags.NO_CONTROL_LINE_INIT));
        usbOpen(EnumSet.of(UsbOpenFlags.NO_CONTROL_LINE_INIT, UsbOpenFlags.NO_IOMANAGER_THREAD));
        usbParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE);
        for (int i = 0; i < usbSerialDriver.getDevice().getInterfaceCount(); i++)
            usbDeviceConnection.releaseInterface(usbSerialDriver.getDevice().getInterface(i));
        usbDeviceConnection.close();

        // set... error
        try {
            usbSerialPort.setRTS(true);
            fail("error expected");
        } catch (IOException ignored) {
        }

        // get... error
        try {
            usbSerialPort.getRI();
            if (!inputLinesSupported)
                ;
            else if (usbSerialDriver instanceof ProlificSerialDriver)
                ; // todo: currently not possible to detect, as bulkTransfer in background thread does not distinguish timeout and error
            else
                fail("error expected");
        } catch (IOException ignored) {
        }
    }
}
