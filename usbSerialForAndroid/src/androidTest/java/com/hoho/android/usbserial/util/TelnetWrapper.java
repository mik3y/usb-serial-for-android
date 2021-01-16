package com.hoho.android.usbserial.util;

import com.hoho.android.usbserial.driver.UsbSerialPort;

import org.apache.commons.net.telnet.InvalidTelnetOptionException;
import org.apache.commons.net.telnet.TelnetClient;
import org.apache.commons.net.telnet.TelnetCommand;
import org.apache.commons.net.telnet.TelnetOptionHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;

import static org.junit.Assert.assertEquals;

public class TelnetWrapper {
    private final static String  TAG = TelnetWrapper.class.getSimpleName();

    private final static int     TELNET_READ_WAIT = 500;
    private final static int     TELNET_COMMAND_WAIT = 2000;

    private final static byte    RFC2217_COM_PORT_OPTION = 0x2c;
    private final static byte    RFC2217_SET_BAUDRATE = 1;
    private final static byte    RFC2217_SET_DATASIZE = 2;
    private final static byte    RFC2217_SET_PARITY   = 3;
    private final static byte    RFC2217_SET_STOPSIZE = 4;
    private final static byte    RFC2217_PURGE_DATA = 12;

    private final String host;
    private final int port;

    private TelnetClient telnetClient;
    private InputStream readStream;
    private OutputStream writeStream;
    private ArrayList<int[]> commandResponse = new ArrayList<>();
    public int writeDelay = 0;

    public TelnetWrapper(String host, int port) {
        this.host = host;
        this.port = port;
        telnetClient = null;
    }

    private void setUpFixtureInt() throws Exception {
        if(telnetClient != null)
            return;
        telnetClient = new TelnetClient();
        telnetClient.addOptionHandler(new TelnetOptionHandler(RFC2217_COM_PORT_OPTION, false, false, false, false) {
            @Override
            public int[] answerSubnegotiation(int[] suboptionData, int suboptionLength) {
                int[] data = new int[suboptionLength];
                System.arraycopy(suboptionData, 0, data, 0, suboptionLength);
                commandResponse.add(data);
                return super.answerSubnegotiation(suboptionData, suboptionLength);
            }
        });

        telnetClient.setConnectTimeout(2000);
        telnetClient.connect(host, port);
        telnetClient.setTcpNoDelay(true);
        writeStream = telnetClient.getOutputStream();
        readStream = telnetClient.getInputStream();
    }

    private int[] doCommand(String name, byte[] command) throws IOException, InterruptedException {
        commandResponse.clear();
        telnetClient.sendCommand((byte) TelnetCommand.SB);
        writeStream.write(command);
        telnetClient.sendCommand((byte)TelnetCommand.SE);

        for(int i=0; i<TELNET_COMMAND_WAIT; i++) {
            if(commandResponse.size() > 0) break;
            Thread.sleep(1);
        }
        assertEquals("RFC2217 " + name+ " w/o response.", 1, commandResponse.size());
        //Log.d(TAG, name + " -> " + Arrays.toString(commandResponse.get(0)));
        return commandResponse.get(0);
    }

    public void setUp() throws Exception {
        setUpFixtureInt();
        telnetClient.sendAYT(1000); // not correctly handled by rfc2217_server.py, but WARNING output "ignoring Telnet command: '\xf6'" is a nice separator between tests
        doCommand("purge-data", new byte[] {RFC2217_COM_PORT_OPTION, RFC2217_PURGE_DATA, 3});
        writeDelay = 0;
    }

    public void tearDown() {
        try {
            read(0);
        } catch (Exception ignored) {
        }
    }

    public void tearDownFixture() throws Exception {
        try {
            telnetClient.disconnect();
        } catch (Exception ignored) {}
        readStream = null;
        writeStream = null;
        telnetClient = null;
    }

    // wait full time
    public byte[] read() throws Exception {
        return read(-1, -1);
    }
    public byte[] read(int expectedLength) throws Exception {
        return read(expectedLength, -1);
    }
    public byte[] read(int expectedLength, int readWait) throws Exception {
        if(readWait == -1)
            readWait = TELNET_READ_WAIT;
        long end = System.currentTimeMillis() + readWait;
        ByteBuffer buf = ByteBuffer.allocate(65536);
        while(System.currentTimeMillis() < end) {
            if(readStream.available() > 0) {
                buf.put((byte) readStream.read());
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

    public void write(byte[] data) throws Exception{
        if(writeDelay != 0) {
            for(byte b : data) {
                writeStream.write(b);
                writeStream.flush();
                Thread.sleep(writeDelay);
            }
        } else {
            writeStream.write(data);
            writeStream.flush();
        }
    }

    public void setParameters(int baudRate, int dataBits, int stopBits, @UsbSerialPort.Parity int parity) throws IOException, InterruptedException, InvalidTelnetOptionException {
        doCommand("set-baudrate", new byte[] {RFC2217_COM_PORT_OPTION, RFC2217_SET_BAUDRATE, (byte)(baudRate>>24), (byte)(baudRate>>16), (byte)(baudRate>>8), (byte)baudRate});
        doCommand("set-datasize", new byte[] {RFC2217_COM_PORT_OPTION, RFC2217_SET_DATASIZE, (byte)dataBits});
        doCommand("set-stopsize", new byte[] {RFC2217_COM_PORT_OPTION, RFC2217_SET_STOPSIZE, (byte)stopBits});
        doCommand("set-parity", new byte[] {RFC2217_COM_PORT_OPTION, RFC2217_SET_PARITY, (byte)(parity+1)});
    }

}
