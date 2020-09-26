package com.hoho.android.usbserial.util;

import org.apache.commons.net.telnet.InvalidTelnetOptionException;
import org.apache.commons.net.telnet.TelnetClient;
import org.apache.commons.net.telnet.TelnetCommand;
import org.apache.commons.net.telnet.TelnetOptionHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

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
    private Integer[] comPortOptionCounter = {0};
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
                comPortOptionCounter[0] += 1;
                return super.answerSubnegotiation(suboptionData, suboptionLength);
            }
        });

        telnetClient.setConnectTimeout(2000);
        telnetClient.connect(host, port);
        telnetClient.setTcpNoDelay(true);
        writeStream = telnetClient.getOutputStream();
        readStream = telnetClient.getInputStream();
    }

    public void setUp() throws Exception {
        setUpFixtureInt();
        telnetClient.sendAYT(1000); // not correctly handled by rfc2217_server.py, but WARNING output "ignoring Telnet command: '\xf6'" is a nice separator between tests
        comPortOptionCounter[0] = 0;
        telnetClient.sendCommand((byte)TelnetCommand.SB);
        writeStream.write(new byte[] {RFC2217_COM_PORT_OPTION, RFC2217_PURGE_DATA, 3});
        telnetClient.sendCommand((byte)TelnetCommand.SE);
        for(int i=0; i<TELNET_COMMAND_WAIT; i++) {
            if(comPortOptionCounter[0] == 1) break;
            Thread.sleep(1);
        }
        assertEquals("telnet connection lost", 1, comPortOptionCounter[0].intValue());
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

    public void setParameters(int baudRate, int dataBits, int stopBits, int parity) throws IOException, InterruptedException, InvalidTelnetOptionException {
        comPortOptionCounter[0] = 0;

        telnetClient.sendCommand((byte) TelnetCommand.SB);
        writeStream.write(new byte[] {RFC2217_COM_PORT_OPTION, RFC2217_SET_BAUDRATE, (byte)(baudRate>>24), (byte)(baudRate>>16), (byte)(baudRate>>8), (byte)baudRate});
        telnetClient.sendCommand((byte)TelnetCommand.SE);

        telnetClient.sendCommand((byte)TelnetCommand.SB);
        writeStream.write(new byte[] {RFC2217_COM_PORT_OPTION, RFC2217_SET_DATASIZE, (byte)dataBits});
        telnetClient.sendCommand((byte)TelnetCommand.SE);

        telnetClient.sendCommand((byte)TelnetCommand.SB);
        writeStream.write(new byte[] {RFC2217_COM_PORT_OPTION, RFC2217_SET_STOPSIZE, (byte)stopBits});
        telnetClient.sendCommand((byte)TelnetCommand.SE);

        telnetClient.sendCommand((byte)TelnetCommand.SB);
        writeStream.write(new byte[] {RFC2217_COM_PORT_OPTION, RFC2217_SET_PARITY, (byte)(parity+1)});
        telnetClient.sendCommand((byte)TelnetCommand.SE);

        // windows does not like nonstandard baudrates. rfc2217_server.py terminates w/o response
        for(int i=0; i<TELNET_COMMAND_WAIT; i++) {
            if(comPortOptionCounter[0] == 4) break;
            Thread.sleep(1);
        }
        assertEquals("telnet connection lost", 4, comPortOptionCounter[0].intValue());
    }
}
