package com.hoho.android.usbserial.driver;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static android.hardware.usb.UsbConstants.USB_DIR_OUT;

public class CdcAcmVirtualSerialPort {

    private UsbEndpoint mControlEndpoint;
    private UsbEndpoint mReadEndpoint;
    private UsbEndpoint mWriteEndpoint;

    private UsbInterface mControlInterface;
    private UsbInterface mDataInterface;

    private String mName = "";

    public CdcAcmVirtualSerialPort(UsbInterface control, UsbInterface data) throws IOException{
        mControlInterface = control;
        mDataInterface = data;

        if (mControlInterface == null || mDataInterface == null){
            throw new IOException("Control and Data interfaces are null");
        }

        if (mControlInterface == mDataInterface){
            setupSingleInterface();
        } else {
            setupUnionInterfaces();
        }

        setName();
    }

    public String getName(){
        return mName;
    }

    private void setName(){
        //Searches the string representation of the interfaces for 'mName'
        //Then it pulls whatever is after '=' as the name of the interface.
        String idata1 = mControlInterface.toString();
        String idata2 = mDataInterface.toString();
        Pattern pattern = Pattern.compile("mName=(.*?),");
        Matcher matcher1 = pattern.matcher(idata1);
        Matcher matcher2 = pattern.matcher(idata2);

        String temp1 = null, temp2 = null;
        if (matcher1.find()){
            temp1 = matcher1.group(1);
        }
        if (matcher2.find()){
            temp2 = matcher2.group(1);
        }

        //Set the name to the control interface if it's not null.
        //Then try the data interface. If both are null, create
        //a name based off of the control interface's id number.
        if (temp1 != null && !temp1.equals("null")){
            mName = temp1;
        } else if (temp2 != null && !temp2.equals("null")){
            mName = temp2;
        } else {
            mName = "mControlId: " + mControlInterface.getId();
        }
    }

    private void setupSingleInterface() throws IOException{
        mControlEndpoint = null;
        mReadEndpoint = null;
        mWriteEndpoint = null;

        int endCount = mControlInterface.getEndpointCount();

        for (int i = 0; i < endCount; ++i) {
            UsbEndpoint ep = mControlInterface.getEndpoint(i);
            if ((ep.getDirection() == UsbConstants.USB_DIR_IN) &&
                    (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_INT)) {
                //Found control endpoint
                mControlEndpoint = ep;
            } else if ((ep.getDirection() == UsbConstants.USB_DIR_IN) &&
                    (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK)) {
                //Found reading endpoint
                mReadEndpoint = ep;
            } else if ((ep.getDirection() == UsbConstants.USB_DIR_OUT) &&
                    (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK)) {
                //Found writing endpoint
                mWriteEndpoint = ep;
            }
        }

        if ((mControlEndpoint == null) ||
                (mReadEndpoint == null) ||
                (mWriteEndpoint == null)) {
            throw new IOException("Could not establish all endpoints");
        }
    }

    private void setupUnionInterfaces() throws IOException{
        mControlEndpoint = null;
        mReadEndpoint = null;
        mWriteEndpoint = null;

        //Picks first endpoint in control interface (there should only be one).
        mControlEndpoint = mControlInterface.getEndpoint(0);
        for (int i = 0; i < mDataInterface.getEndpointCount(); i++){
            UsbEndpoint tempEP = mDataInterface.getEndpoint(i);

            //USB OUT is "host to device" - so we write to it
            if (tempEP.getDirection() == USB_DIR_OUT){
                mWriteEndpoint = tempEP;
            } else {
                //USB IN is "device to host" - so we read from it
                mReadEndpoint = tempEP;
            }
        }

        if ((mControlEndpoint == null) ||
                (mReadEndpoint == null) ||
                (mWriteEndpoint == null)) {
            throw new IOException("Could not establish all endpoints");
        }
    }

    public UsbEndpoint getControlEndpoint() {
        return mControlEndpoint;
    }

    public UsbEndpoint getReadEndpoint() {
        return mReadEndpoint;
    }

    public UsbEndpoint getWriteEndpoint() {
        return mWriteEndpoint;
    }

    public UsbInterface getControlInterface() {
        return mControlInterface;
    }

    public UsbInterface getDataInterface() {
        return mDataInterface;
    }
}
