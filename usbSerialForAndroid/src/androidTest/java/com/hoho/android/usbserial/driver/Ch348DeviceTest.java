/*
 * CH348 specific device test
 *
 * wiring:
 *   port 0 TxD/RxD cross connected with port 4..7 (default 4)
 *   port 1 TxD connected with RxD (loopback)
 *
 * instrumentation arguments:
 *   ch348_device  optional, UsbDevice name, e.g. /dev/bus/usb/001/019, else first CH348 device
 *   ch348_serial  optional, expected USB serial number
 *   ch348_cross   optional, port cross connected with port 0, default 4
 */
package com.hoho.android.usbserial.driver;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.core.content.ContextCompat;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.hoho.android.usbserial.util.SerialInputOutputManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

@RunWith(AndroidJUnit4.class)
public class Ch348DeviceTest {

    private static final String TAG = Ch348DeviceTest.class.getSimpleName();

    private Context context;
    private UsbManager usbManager;
    private Ch348SerialDriver driver;
    private int crossPort = 4;
    private final List<UsbSerialPort> openPorts = new ArrayList<>();

    @Rule
    public TestRule watcher = new TestWatcher() {
        protected void starting(Description description) {
            Log.i(TAG, "===== starting test: " + description.getMethodName() + " =====");
        }
    };

    @Before
    public void setUp() throws Exception {
        CommonUsbSerialPort.DEBUG = true;
        context = ApplicationProvider.getApplicationContext();
        usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        Bundle args = InstrumentationRegistry.getArguments();
        String deviceName = args.getString("ch348_device");
        if (args.getString("ch348_cross") != null)
            crossPort = Integer.parseInt(args.getString("ch348_cross"));
        for (UsbSerialDriver d : UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)) {
            if (d instanceof Ch348SerialDriver && (deviceName == null || deviceName.equals(d.getDevice().getDeviceName()))) {
                driver = (Ch348SerialDriver) d;
                break;
            }
        }
        assertNotNull("CH348 device not found", driver);
        requestPermission();
        String serial = args.getString("ch348_serial");
        if (serial != null) {
            UsbDeviceConnection connection = usbManager.openDevice(driver.getDevice());
            try {
                assertEquals("wrong device", serial, connection.getSerial());
            } finally {
                connection.close();
            }
        }
    }

    @After
    public void tearDown() {
        for (UsbSerialPort port : openPorts) {
            try {
                if (port.isOpen())
                    port.close();
            } catch (Exception ignored) {}
        }
        openPorts.clear();
    }

    private void requestPermission() throws Exception {
        if (usbManager.hasPermission(driver.getDevice()))
            return;
        final Boolean[] granted = {null};
        BroadcastReceiver usbReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                granted[0] = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            }
        };
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_MUTABLE : 0;
        Intent intent = new Intent("com.android.example.USB_PERMISSION");
        intent.setPackage(context.getPackageName());
        PendingIntent permissionIntent = PendingIntent.getBroadcast(context, 0, intent, flags);
        IntentFilter filter = new IntentFilter("com.android.example.USB_PERMISSION");
        ContextCompat.registerReceiver(context, usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        usbManager.requestPermission(driver.getDevice(), permissionIntent);
        for (int i = 0; i < 600 && granted[0] == null; i++)
            Thread.sleep(100);
        context.unregisterReceiver(usbReceiver);
        assertTrue("USB permission dialog not confirmed", granted[0] != null && granted[0]);
    }

    // ---------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------

    private UsbSerialPort open(int portNumber, int baudRate) throws Exception {
        return open(portNumber, baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
    }

    private UsbSerialPort open(int portNumber, int baudRate, int dataBits, int stopBits, int parity) throws Exception {
        UsbSerialPort port = driver.getPorts().get(portNumber);
        port.open(usbManager.openDevice(driver.getDevice()));
        openPorts.add(port);
        port.setParameters(baudRate, dataBits, stopBits, parity);
        purge(port);
        return port;
    }

    /** discard stale data, e.g. glitches from opening the other side */
    private static void purge(UsbSerialPort port) throws IOException {
        byte[] buf = new byte[512];
        //noinspection StatementWithEmptyBody
        while (port.read(buf, 50) > 0);
    }

    private static byte[] readFully(UsbSerialPort port, int length, int timeout, int chunkSize) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buf = new byte[chunkSize];
        long endTime = System.currentTimeMillis() + timeout;
        while (result.size() < length && System.currentTimeMillis() < endTime) {
            int len = port.read(buf, 100);
            result.write(buf, 0, len);
        }
        return result.toByteArray();
    }

    private static byte[] randomData(int length, long seed) {
        byte[] data = new byte[length];
        new Random(seed).nextBytes(data);
        return data;
    }

    private static int transferTimeout(int length, int baudRate) {
        return (int) (2000 + (long) length * 11 * 1000 * 2 / baudRate);
    }

    private static void assertTransfer(UsbSerialPort tx, UsbSerialPort rx, byte[] data, int baudRate, int chunkSize) throws Exception {
        AtomicReference<Exception> writeError = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                tx.write(data, 0);
            } catch (Exception e) {
                writeError.set(e);
            }
        });
        long t0 = System.currentTimeMillis();
        writer.start();
        byte[] received = readFully(rx, data.length, transferTimeout(data.length, baudRate), chunkSize);
        writer.join();
        long dt = System.currentTimeMillis() - t0;
        Log.i(TAG, String.format("port %d -> %d: %d/%d bytes @ %d baud in %d msec",
                tx.getPortNumber(), rx.getPortNumber(), received.length, data.length, baudRate, dt));
        if (writeError.get() != null)
            throw writeError.get();
        assertEquals("received length", data.length, received.length);
        assertArrayEquals(data, received);
    }

    // ---------------------------------------------------------------------------------------
    // tests
    // ---------------------------------------------------------------------------------------

    @Test
    public void chipType() throws Exception {
        UsbSerialPort port0 = open(0, 115200);
        UsbSerialPort portX = open(crossPort, 115200);
        Log.i(TAG, "port0 lines=" + port0.getSupportedControlLines() + " portX lines=" + portX.getSupportedControlLines());
        // CH348Q: port 4..7 without control lines
        assertEquals(EnumSet.allOf(UsbSerialPort.ControlLine.class), port0.getSupportedControlLines());
        assertEquals(EnumSet.noneOf(UsbSerialPort.ControlLine.class), portX.getSupportedControlLines());
        assertThrows(UnsupportedOperationException.class, () -> portX.setDTR(true));
        assertThrows(UnsupportedOperationException.class, () -> portX.setFlowControl(UsbSerialPort.FlowControl.RTS_CTS));
        port0.setDTR(false);
        port0.setRTS(true);
        assertTrue(port0.getRTS());
        assertTrue(!port0.getDTR());
        Log.i(TAG, "port0 control lines=" + port0.getControlLines());
    }

    /**
     * manual test, run with argument ch348_disconnect=true and unplug the device while running
     */
    @Test
    public void disconnect() throws Exception {
        assumeTrue("manual test", "true".equals(InstrumentationRegistry.getArguments().getString("ch348_disconnect")));
        UsbSerialPort port0 = open(0, 115200);
        UsbSerialPort portX = open(crossPort, 115200);
        UsbSerialPort port1 = open(1, 115200);

        AtomicReference<Exception> ioManagerError = new AtomicReference<>();
        long[] ioManagerErrorTime = {0};
        SerialInputOutputManager ioManager = new SerialInputOutputManager(port1, new SerialInputOutputManager.Listener() {
            @Override
            public void onNewData(byte[] data) {}
            @Override
            public void onRunError(Exception e) {
                ioManagerErrorTime[0] = System.currentTimeMillis();
                ioManagerError.set(e);
            }
        });
        ioManager.start();

        String[] names = {"port 0 write (timeout 0)", "port " + crossPort + " read (timeout 0)",
                "port " + crossPort + " write (timeout 1000)", "port 0 read (timeout 200)"};
        Exception[] errors = new Exception[names.length];
        long[] errorTimes = new long[names.length];
        long[] bytes = new long[names.length];
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            final int n = i;
            Thread thread = new Thread(() -> {
                byte[] buf = new byte[1000];
                try {
                    //noinspection InfiniteLoopStatement
                    while (true) {
                        switch (n) {
                            case 0: port0.write(buf, 0); bytes[n] += buf.length; break;
                            case 1: bytes[n] += portX.read(buf, 0); break;
                            case 2: portX.write(buf, 100, 1000); bytes[n] += 100; Thread.sleep(10); break;
                            case 3: bytes[n] += port0.read(buf, 200); break;
                        }
                    }
                } catch (Exception e) {
                    errorTimes[n] = System.currentTimeMillis();
                    errors[n] = e;
                }
            }, names[i]);
            threads.add(thread);
            thread.start();
        }

        Thread.sleep(1000);
        for (int i = 0; i < names.length; i++)
            assertTrue(names[i] + " failed before unplug: " + errors[i], errors[i] == null);
        Log.i(TAG, "===== READY: UNPLUG DEVICE NOW =====");
        long waitEnd = System.currentTimeMillis() + 180_000;
        for (Thread thread : threads) {
            while (thread.isAlive() && System.currentTimeMillis() < waitEnd) {
                ioManager.writeAsync("x".getBytes());
                thread.join(100);
            }
        }
        long firstError = Long.MAX_VALUE, lastError = 0;
        for (int i = 0; i < names.length; i++) {
            Log.i(TAG, names[i] + ": " + bytes[i] + " bytes, error=" + errors[i]);
            if (errors[i] != null) {
                firstError = Math.min(firstError, errorTimes[i]);
                lastError = Math.max(lastError, errorTimes[i]);
            }
        }
        for (int i = 0; i < 20 && ioManagerError.get() == null; i++)
            Thread.sleep(100);
        Log.i(TAG, "ioManager error=" + ioManagerError.get());
        Log.i(TAG, "errors detected within " + (lastError - firstError) + " msec");
        for (int i = 0; i < names.length; i++) {
            assertTrue(names[i] + " not terminated", errors[i] instanceof IOException);
        }
        assertTrue("ioManager not terminated", ioManagerError.get() instanceof IOException);
        assertTrue("error detection too slow", Math.max(lastError, ioManagerErrorTime[0]) - firstError < 2000);

        long t0 = System.currentTimeMillis();
        ioManager.stop();
        for (UsbSerialPort port : openPorts)
            port.close();
        openPorts.clear();
        long dt = System.currentTimeMillis() - t0;
        Log.i(TAG, "close after disconnect took " + dt + " msec");
        assertTrue("close too slow", dt < 3000);
        Log.i(TAG, "===== DONE =====");
    }

    @Test
    public void loopback() throws Exception {
        UsbSerialPort port1 = open(1, 115200);
        assertTransfer(port1, port1, "hello CH348".getBytes(), 115200, 512);
    }

    @Test
    public void cross() throws Exception {
        UsbSerialPort port0 = open(0, 115200);
        UsbSerialPort portX = open(crossPort, 115200);
        assertTransfer(port0, portX, "port0 -> cross".getBytes(), 115200, 512);
        assertTransfer(portX, port0, "cross -> port0".getBytes(), 115200, 512);
    }

    @Test
    public void longWrite() throws Exception {
        // > 255 bytes per packet and multiple packets per write
        UsbSerialPort port0 = open(0, 921600);
        UsbSerialPort portX = open(crossPort, 921600);
        assertTransfer(port0, portX, randomData(300, 1), 921600, 512);
        assertTransfer(portX, port0, randomData(16 * 1024, 2), 921600, 512);
        UsbSerialPort port1 = open(1, 921600);
        assertTransfer(port1, port1, randomData(16 * 1024, 3), 921600, 512);
    }

    @Test
    public void smallReadBuffer() throws Exception {
        // read buffer smaller than RX sub-packets must not lose data
        UsbSerialPort port1 = open(1, 115200);
        assertTransfer(port1, port1, randomData(2000, 4), 115200, 7);
        assertTransfer(port1, port1, randomData(2000, 5), 115200, 1);
    }

    @Test
    public void baudRates() throws Exception {
        UsbSerialPort port1 = open(1, 9600);
        for (int baudRate : new int[]{1200, 9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600, 1000000, 1500000, 2000000, 3000000, 6000000}) {
            port1.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            purge(port1);
            int length = baudRate < 9600 ? 64 : 1024;
            assertTransfer(port1, port1, randomData(length, baudRate), baudRate, 512);
        }
    }

    @Test
    public void lineParameters() throws Exception {
        UsbSerialPort port0 = open(0, 115200);
        UsbSerialPort portX = open(crossPort, 115200);
        byte[] data = randomData(256, 6);
        int[][] params = {
                {8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE},
                {8, UsbSerialPort.STOPBITS_2, UsbSerialPort.PARITY_NONE},
                {8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_EVEN},
                {8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_ODD},
                {8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_MARK},
                {8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_SPACE},
                {7, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_EVEN},
                {6, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE},
                {5, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE},
        };
        for (int[] p : params) {
            Log.i(TAG, "data bits " + p[0] + ", stop bits " + p[1] + ", parity " + p[2]);
            port0.setParameters(115200, p[0], p[1], p[2]);
            portX.setParameters(115200, p[0], p[1], p[2]);
            purge(port0);
            purge(portX);
            byte[] masked = data.clone();
            for (int i = 0; i < masked.length; i++)
                masked[i] &= (1 << p[0]) - 1;
            assertTransfer(port0, portX, masked, 115200, 512);
        }

        // data bits are really applied: 8 bit sent, 7 bit received
        port0.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
        portX.setParameters(115200, 7, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
        purge(portX);
        port0.write(new byte[]{(byte) 0xc1}, 0);
        byte[] received = readFully(portX, 1, 1000, 512);
        assertArrayEquals(new byte[]{0x41}, received);

        assertThrows(UnsupportedOperationException.class, () -> port0.setParameters(115200, 8, UsbSerialPort.STOPBITS_1_5, UsbSerialPort.PARITY_NONE));
        assertThrows(IllegalArgumentException.class, () -> port0.setParameters(115200, 9, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE));
        assertThrows(IllegalArgumentException.class, () -> port0.setParameters(0, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE));
    }

    @Test
    public void concurrentPorts() throws Exception {
        UsbSerialPort port0 = open(0, 460800);
        UsbSerialPort port1 = open(1, 460800);
        UsbSerialPort portX = open(crossPort, 460800);
        List<Thread> threads = new ArrayList<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        UsbSerialPort[][] pairs = {{port0, portX}, {portX, port0}, {port1, port1}};
        for (int i = 0; i < pairs.length; i++) {
            UsbSerialPort tx = pairs[i][0], rx = pairs[i][1];
            byte[] data = randomData(32 * 1024, 100 + i);
            Thread thread = new Thread(() -> {
                try {
                    assertTransfer(tx, rx, data, 460800, 512);
                } catch (Throwable e) {
                    error.compareAndSet(null, e);
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads)
            thread.join();
        if (error.get() != null)
            throw new AssertionError(error.get());
    }

    @Test
    public void ioManager() throws Exception {
        UsbSerialPort port0 = open(0, 115200);
        UsbSerialPort portX = open(crossPort, 115200);
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        AtomicReference<Exception> runError = new AtomicReference<>();
        SerialInputOutputManager ioManager = new SerialInputOutputManager(portX, new SerialInputOutputManager.Listener() {
            @Override
            public void onNewData(byte[] data) {
                synchronized (received) {
                    received.write(data, 0, data.length);
                }
            }
            @Override
            public void onRunError(Exception e) {
                runError.set(e);
            }
        });
        ioManager.start();
        byte[] data = randomData(4000, 7);
        port0.write(data, 0);
        ioManager.writeAsync("from ioManager".getBytes());
        byte[] echo = readFully(port0, 14, 2000, 512);
        assertArrayEquals("from ioManager".getBytes(), echo);
        for (int i = 0; i < 50; i++) {
            synchronized (received) {
                if (received.size() >= data.length) break;
            }
            Thread.sleep(100);
        }
        synchronized (received) {
            assertArrayEquals(data, received.toByteArray());
        }
        // close() terminates the blocking read (read timeout 0) without error
        ioManager.stop();
        portX.close();
        for (int i = 0; i < 20 && ioManager.getState() != SerialInputOutputManager.State.STOPPED; i++)
            Thread.sleep(100);
        assertEquals(SerialInputOutputManager.State.STOPPED, ioManager.getState());
    }

    @Test
    public void readTimeout() throws Exception {
        UsbSerialPort port1 = open(1, 115200);
        byte[] buf = new byte[64];
        long t0 = System.currentTimeMillis();
        assertEquals(0, port1.read(buf, 200));
        long dt = System.currentTimeMillis() - t0;
        assertTrue("read timeout " + dt, dt >= 180 && dt < 500);

        // blocking read is terminated by close()
        AtomicReference<Exception> readError = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            try {
                port1.read(buf, 0);
            } catch (Exception e) {
                readError.set(e);
            }
        });
        reader.start();
        Thread.sleep(300);
        assertTrue(reader.isAlive());
        port1.close();
        reader.join(1000);
        assertTrue(!reader.isAlive());
        assertTrue(readError.get() instanceof IOException);
    }

    @Test
    public void writeTimeout() throws Exception {
        UsbSerialPort port0 = open(0, 9600);
        UsbSerialPort portX = open(crossPort, 9600);
        byte[] data = randomData(8000, 8);  // ~8 sec @ 9600 baud
        try {
            port0.write(data, 500);
            fail("write timeout expected");
        } catch (SerialTimeoutException e) {
            Log.i(TAG, "expected timeout: " + e.getMessage() + " transferred=" + e.bytesTransferred);
            assertTrue(e.bytesTransferred > 0 && e.bytesTransferred < data.length);
            byte[] received = readFully(portX, e.bytesTransferred, transferTimeout(e.bytesTransferred, 9600), 512);
            assertArrayEquals(Arrays.copyOf(data, e.bytesTransferred), received);
        }
        // port still usable after timeout
        port0.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
        portX.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
        purge(portX);
        assertTransfer(port0, portX, "after timeout".getBytes(), 115200, 512);
    }

    @Test
    public void openClose() throws Exception {
        UsbSerialPort port0 = open(0, 115200);
        UsbSerialPort port1 = open(1, 115200);
        assertThrows(IOException.class, () -> port0.open(usbManager.openDevice(driver.getDevice())));

        // port0 owns the shared connection, port1 still works after port0 is closed
        port0.close();
        assertThrows(IOException.class, port0::close);
        assertThrows(IOException.class, () -> port0.read(new byte[1], 100));
        assertTransfer(port1, port1, "port0 closed".getBytes(), 115200, 512);
        port1.close();

        for (int i = 0; i < 3; i++) {
            UsbSerialPort portX = open(crossPort, 115200);
            UsbSerialPort port0b = open(0, 115200);
            assertTransfer(portX, port0b, ("reopen " + i).getBytes(), 115200, 512);
            port0b.close();
            portX.close();
        }
    }

    @Test
    public void setBreak() throws Exception {
        UsbSerialPort port0 = open(0, 115200);
        UsbSerialPort portX = open(crossPort, 115200);
        port0.setBreak(true);
        Thread.sleep(100);
        port0.setBreak(false);
        byte[] received = readFully(portX, 1, 500, 512);
        Log.i(TAG, "received during break: " + Arrays.toString(received));
        assertTransfer(port0, portX, "after break".getBytes(), 115200, 512);
    }

    @Test
    public void flowControl() throws Exception {
        // CTS not connected, only check that the command is accepted on a port with control lines
        UsbSerialPort port1 = open(1, 115200);
        port1.setFlowControl(UsbSerialPort.FlowControl.RTS_CTS);
        assertEquals(UsbSerialPort.FlowControl.RTS_CTS, port1.getFlowControl());
        port1.setFlowControl(UsbSerialPort.FlowControl.NONE);
        assertTransfer(port1, port1, "flow control off".getBytes(), 115200, 512);
    }
}
