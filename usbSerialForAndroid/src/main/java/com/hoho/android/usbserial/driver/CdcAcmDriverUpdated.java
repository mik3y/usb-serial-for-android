package com.hoho.android.usbserial.driver;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbRequest;
import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import justin.RawUsbConfiguration;
import justin.RawUsbDevice;
import justin.RawUsbFunctionInterface;
import justin.RawUsbFunctionUnion;
import justin.RawUsbInterface;
import justin.RawUsbManager;

public class CdcAcmDriverUpdated implements UsbSerialDriver{

    private final String TAG = CdcAcmDriverUpdated.class.getSimpleName();

    private UsbDevice mDevice;
    private RawUsbDevice mRawDevice = null;

    private final UsbSerialPort mPort;

    public CdcAcmDriverUpdated(UsbDevice device){
        this.mDevice = device;
        mPort = new CdcAcmCompleteSerialPort(device, 0);
    }

    @Override
    public UsbDevice getDevice() {
        return mDevice;
    }

    @Override
    public List<UsbSerialPort> getPorts() {
        return Collections.singletonList(mPort);
    }

    public class CdcAcmCompleteSerialPort extends CommonUsbSerialPort {

        private final boolean mEnableAsyncReads;
        private UsbInterface mControlInterface;
        private UsbInterface mDataInterface;

        private UsbEndpoint mControlEndpoint;
        private UsbEndpoint mReadEndpoint;
        private UsbEndpoint mWriteEndpoint;

        private boolean mRts = false;
        private boolean mDtr = false;

        private ArrayList<CdcAcmVirtualSerialPort> mSerialPorts;
        private int defaultVirtualPortIndex = 0;

        private static final int USB_RECIP_INTERFACE = 0x01;
        private static final int USB_RT_ACM = UsbConstants.USB_TYPE_CLASS | USB_RECIP_INTERFACE;

        private static final int SET_LINE_CODING = 0x20;  // USB CDC 1.1 section 6.2
        private static final int GET_LINE_CODING = 0x21;
        private static final int SET_CONTROL_LINE_STATE = 0x22;
        private static final int SEND_BREAK = 0x23;

        public CdcAcmCompleteSerialPort(UsbDevice device, int portNumber) {
            super(device, portNumber);
            mEnableAsyncReads = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1);
            mSerialPorts = new ArrayList<CdcAcmVirtualSerialPort>();
        }

        @Override
        public UsbSerialDriver getDriver() {
            return CdcAcmDriverUpdated.this;
        }

        @Override
        public void open(UsbDeviceConnection connection) throws IOException{
            populateSerialPorts(connection);

            if (mSerialPorts.isEmpty()){
                throw new IOException("No CDC ACM Ports found!");
            } else {
                Log.d(TAG,"Number of serial ports found:"+mSerialPorts.size());

                //Open the first serial port found by default.
                try {
                    open(defaultVirtualPortIndex);
                } catch (IndexOutOfBoundsException e){
                    Log.d(TAG, "Virtual port was not found");
                }
            }
        }

        private void open(int mPortNumber) throws IOException{
            CdcAcmVirtualSerialPort mPort = mSerialPorts.get(mPortNumber);

            mControlInterface = mPort.getControlInterface();
            mDataInterface = mPort.getDataInterface();

            mControlEndpoint = mPort.getControlEndpoint();
            mReadEndpoint = mPort.getReadEndpoint();
            mWriteEndpoint = mPort.getWriteEndpoint();

            if (!mConnection.claimInterface(mDataInterface,true)){
                Log.d(TAG, "Could not claim interface.");
            } else {
                Log.d(TAG, "Claimed interface.");
            }
        }

        public void populateSerialPorts(UsbDeviceConnection connection) throws IOException{
            mSerialPorts.clear();
            mConnection = connection;

            //This accesses the "RawUsb" package
            mRawDevice = RawUsbManager.getDeviceFromRawString(connection.getRawDescriptors());

            if (mRawDevice == null) {
                return;
            }

            //Doing both of the following operations allows for several "virtual" serial
            //ports on a single device

            //Find any possible serial ports defined by a union functional descriptor
            ArrayList<RawUsbFunctionUnion> mUnions = getUnionDescriptors(mRawDevice);
            //Find any possible castrated serial ports that are defined with only 1 interface
            ArrayList<RawUsbInterface> mSingles = getSingleInterfaces(mRawDevice);

            setupSerialPorts(mUnions,mSingles);
        }

        public int getSerialPortCount(){
            return mSerialPorts.size();
        }

        public CdcAcmVirtualSerialPort getSerialPort(int index){
            return mSerialPorts.get(index);
        }

        public void setDefaultVirtualPort(CdcAcmVirtualSerialPort v){
            int index = 0;
            //Finds appropriate virtual port and then sets the index.
            for (CdcAcmVirtualSerialPort temp : mSerialPorts){
                if (temp == v){
                    defaultVirtualPortIndex = index;
                }
                index++;
            }
        }

        public void setDefaultVirtualPortIndex(int defaultVirtualPortIndex) {
            this.defaultVirtualPortIndex = defaultVirtualPortIndex;
        }

        public int getDefaultVirtualPortIndex(){
            return this.defaultVirtualPortIndex;
        }

        private void setupSerialPorts(ArrayList<RawUsbFunctionUnion> unions,
                                      ArrayList<RawUsbInterface> singles)
                                        throws IOException{
            if (unions.isEmpty() && singles.isEmpty()){
                throw new IOException("No Serial Interfaces found");
            }

            for (RawUsbFunctionUnion union : unions){
                mSerialPorts.add(getSerialFromUnion(union));
            }

            for (RawUsbInterface iface : singles){
                mSerialPorts.add(getSerialFromSingleInterface(iface));
            }
        }

        private CdcAcmVirtualSerialPort getSerialFromUnion(RawUsbFunctionUnion union) throws IOException{

            //Get appropriate interface id numbers
            int dataInterfaceNumber = union.getSlaveInterface(0);
            int controlInterfaceNumber = union.getMasterInterface();

            UsbInterface controlIface;
            UsbInterface dataIface;

            //Get actual interfaces from id numbers
            dataIface = getInterfaceFromId(dataInterfaceNumber);
            if (dataIface == null){
                Log.d(TAG,"Slave interface number is incorrect");
                throw new IOException("Slave interface number is incorrect");
            }

            controlIface = getInterfaceFromId(controlInterfaceNumber);
            if (controlIface == null){
                Log.d(TAG,"Master interface number is incorrect");
                throw new IOException("Master interface number is incorrect");
            }

            CdcAcmVirtualSerialPort mSerial = new CdcAcmVirtualSerialPort(controlIface,dataIface);
            return mSerial;
        }

        private CdcAcmVirtualSerialPort getSerialFromSingleInterface(RawUsbInterface i) throws IOException{
            return new CdcAcmVirtualSerialPort(getInterfaceFromId(i.getNumber()),
                    getInterfaceFromId(i.getNumber()));
        }

        //This ONLY checks union for CDC ACM interfaces.
        private ArrayList<RawUsbFunctionUnion> getUnionDescriptors(RawUsbDevice d){
            ArrayList<RawUsbFunctionUnion> u = new ArrayList<RawUsbFunctionUnion>();

            //Assumes only one configuration on device
            RawUsbConfiguration c = d.getConfiguration(0);
            RawUsbInterface mInterface;
            RawUsbFunctionInterface mFunc;

            //Iterate through every interface
            for (int i = 0; i < c.getInterfaceCount(); i++){
                mInterface = c.getInterface(i);

                //Only looks for interfaces of the communications class to be read as serial.
                if (mInterface.getInterfaceClass() == 2 && mInterface.getInterfaceSubclass() == 2) {
                    //Iterate through every functional descriptor
                    for (int j = 0; j < mInterface.getFunctionalDescriptorCount(); j++) {
                        mFunc = mInterface.getFunctionalDescriptor(j);
                        //Save the union descriptor to the arraylist
                        if (mFunc instanceof RawUsbFunctionUnion) {
                            u.add((RawUsbFunctionUnion) mFunc);
                        }
                    }
                }
            }

            if (u.isEmpty()){
                Log.d(TAG,"Did not find any Union Descriptors.");
            }

            return u;
        }

        private ArrayList<RawUsbInterface> getSingleInterfaces(RawUsbDevice d){
            ArrayList<RawUsbInterface> singles = new ArrayList<RawUsbInterface>();

            for (int i = 0; i < d.getInterfaceCount(); i++){
                RawUsbInterface tempInterface = d.getInterface(i);
                //Only investigates interfaces that are CDC ACM
                if (tempInterface.getInterfaceClass() == 2 &&
                        tempInterface.getInterfaceSubclass() == 2){
                    //3 endpoints could mean this is a castrated CDC ACM device
                    if (tempInterface.getEndpointCount() == 3){
                        singles.add(tempInterface);
                    }
                }
            }

            return singles;
        }

        private UsbInterface getInterfaceFromId(int id){
            for (int i = 0; i < mDevice.getInterfaceCount(); i++) {
                UsbInterface tempInt = mDevice.getInterface(i);

                if (tempInt.getId() == id){
                    return tempInt;
                }
            }

            return null;
        }

        private int sendAcmControlMessage(int request, int value, byte[] buf) {
            return mConnection.controlTransfer(
                    USB_RT_ACM, request, value, 0, buf, buf != null ? buf.length : 0, 5000);
        }

        @Override
        public void close() throws IOException {
            if (mConnection == null) {
                throw new IOException("Already closed");
            }
            mConnection.close();
            mConnection = null;
        }

        @Override
        public int read(byte[] dest, int timeoutMillis) throws IOException {
            if (mEnableAsyncReads) {
                final UsbRequest request = new UsbRequest();
                try {
                    request.initialize(mConnection, mReadEndpoint);
                    final ByteBuffer buf = ByteBuffer.wrap(dest);
                    if (!request.queue(buf, dest.length)) {
                        throw new IOException("Error queueing request.");
                    }

                    final UsbRequest response = mConnection.requestWait();
                    if (response == null) {
                        throw new IOException("Null response");
                    }

                    final int nread = buf.position();
                    if (nread > 0) {
                        //Log.d(TAG, HexDump.dumpHexString(dest, 0, Math.min(32, dest.length)));
                        return nread;
                    } else {
                        return 0;
                    }
                } finally {
                    request.close();
                }
            }

            final int numBytesRead;
            synchronized (mReadBufferLock) {
                int readAmt = Math.min(dest.length, mReadBuffer.length);
                numBytesRead = mConnection.bulkTransfer(mReadEndpoint, mReadBuffer, readAmt,
                        timeoutMillis);
                if (numBytesRead < 0) {
                    // This sucks: we get -1 on timeout, not 0 as preferred.
                    // We *should* use UsbRequest, except it has a bug/api oversight
                    // where there is no way to determine the number of bytes read
                    // in response :\ -- http://b.android.com/28023
                    if (timeoutMillis == Integer.MAX_VALUE) {
                        // Hack: Special case "~infinite timeout" as an error.
                        return -1;
                    }
                    return 0;
                }
                System.arraycopy(mReadBuffer, 0, dest, 0, numBytesRead);
            }
            return numBytesRead;
        }

        @Override
        public int write(byte[] src, int timeoutMillis) throws IOException {
            // TODO(mikey): Nearly identical to FtdiSerial write. Refactor.
            int offset = 0;

            while (offset < src.length) {
                final int writeLength;
                final int amtWritten;

                synchronized (mWriteBufferLock) {
                    final byte[] writeBuffer;

                    writeLength = Math.min(src.length - offset, mWriteBuffer.length);
                    if (offset == 0) {
                        writeBuffer = src;
                    } else {
                        // bulkTransfer does not support offsets, make a copy.
                        System.arraycopy(src, offset, mWriteBuffer, 0, writeLength);
                        writeBuffer = mWriteBuffer;
                    }

                    amtWritten = mConnection.bulkTransfer(mWriteEndpoint, writeBuffer, writeLength,
                            timeoutMillis);
                }
                if (amtWritten <= 0) {
                    throw new IOException("Error writing " + writeLength
                            + " bytes at offset " + offset + " length=" + src.length);
                }

                Log.d(TAG, "Wrote amt=" + amtWritten + " attempted=" + writeLength);
                offset += amtWritten;
            }
            return offset;
        }

        @Override
        public void setParameters(int baudRate, int dataBits, int stopBits, int parity) {
            byte stopBitsByte;
            switch (stopBits) {
                case STOPBITS_1: stopBitsByte = 0; break;
                case STOPBITS_1_5: stopBitsByte = 1; break;
                case STOPBITS_2: stopBitsByte = 2; break;
                default: throw new IllegalArgumentException("Bad value for stopBits: " + stopBits);
            }

            byte parityBitesByte;
            switch (parity) {
                case PARITY_NONE: parityBitesByte = 0; break;
                case PARITY_ODD: parityBitesByte = 1; break;
                case PARITY_EVEN: parityBitesByte = 2; break;
                case PARITY_MARK: parityBitesByte = 3; break;
                case PARITY_SPACE: parityBitesByte = 4; break;
                default: throw new IllegalArgumentException("Bad value for parity: " + parity);
            }

            byte[] msg = {
                    (byte) ( baudRate & 0xff),
                    (byte) ((baudRate >> 8 ) & 0xff),
                    (byte) ((baudRate >> 16) & 0xff),
                    (byte) ((baudRate >> 24) & 0xff),
                    stopBitsByte,
                    parityBitesByte,
                    (byte) dataBits};
            sendAcmControlMessage(SET_LINE_CODING, 0, msg);
        }

        @Override
        public boolean getCD() throws IOException {
            return false;  // TODO
        }

        @Override
        public boolean getCTS() throws IOException {
            return false;  // TODO
        }

        @Override
        public boolean getDSR() throws IOException {
            return false;  // TODO
        }

        @Override
        public boolean getDTR() throws IOException {
            return mDtr;
        }

        @Override
        public void setDTR(boolean value) throws IOException {
            mDtr = value;
            setDtrRts();
        }

        @Override
        public boolean getRI() throws IOException {
            return false;  // TODO
        }

        @Override
        public boolean getRTS() throws IOException {
            return mRts;
        }

        @Override
        public void setRTS(boolean value) throws IOException {
            mRts = value;
            setDtrRts();
        }

        private void setDtrRts() {
            int value = (mRts ? 0x2 : 0) | (mDtr ? 0x1 : 0);
            sendAcmControlMessage(SET_CONTROL_LINE_STATE, value, null);
        }
    }
}
