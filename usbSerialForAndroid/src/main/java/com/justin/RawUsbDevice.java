package justin;

import java.util.ArrayList;

import static justin.RawUsbManager.DEVICE_TYPE;

public class RawUsbDevice {

    private int bLength,bDescriptorType,bcdUSB,bDeviceClass,bDeviceSubClass,bDeviceProtocol,
                bMaxPacketSize0,idVendor,idProduct,bcdDevice,iManufacturer,
                iProduct,iSerialNumber,bNumConfigurations;

    private ArrayList<RawUsbConfiguration> configs;

    public RawUsbDevice(byte[] desc) throws DescriptorException{
        bLength = byteToInt(desc[0]);
        bDescriptorType = byteToInt(desc[1]);

        //if the type isn't correct, this was given the wrong descriptor.
        if (bDescriptorType != DEVICE_TYPE){throw new IncorrectDescriptorException(bDescriptorType);}
        //if the length of the descriptor is less than what's expected, the descriptor is bad
        if (desc.length < bLength){throw new BadDescriptorException();}

        bcdUSB = bytesToInt(desc[3],desc[2]);
        bDeviceClass = byteToInt(desc[4]);
        bDeviceSubClass = byteToInt(desc[5]);
        bDeviceProtocol = byteToInt(desc[6]);
        bMaxPacketSize0 = byteToInt(desc[7]);
        idVendor = bytesToInt(desc[9],desc[8]);
        idProduct = bytesToInt(desc[11],desc[10]);
        bcdDevice = bytesToInt(desc[13],desc[12]);
        iManufacturer = byteToInt(desc[14]);
        iProduct = byteToInt(desc[15]);
        iSerialNumber = byteToInt(desc[16]);
        bNumConfigurations = byteToInt(desc[17]);

        configs = new ArrayList<RawUsbConfiguration>();
    }

    public int getVendorId() {
        return idVendor;
    }

    public int getProductId(){
        return idProduct;
    }

    public int getConfigurationCount(){
        return bNumConfigurations;
    }

    public int getInterfaceCount(){
        int total = 0;
        for (RawUsbConfiguration c : configs){
            total += c.getInterfaceCount();
        }
        return total;
    }

    public RawUsbInterface getInterface(int index){
        return configs.get(0).getInterface(index);
    }

    public void addConfiguration(RawUsbConfiguration c){
        configs.add(c);
    }

    public RawUsbConfiguration getConfiguration(int index){
        return configs.get(index);
    }

    //Gets an interface based off of the interface's internal ID, not the index in the
    //devices array.
    public RawUsbInterface getInterfaceByNumber(int num){
        RawUsbConfiguration c = configs.get(0);
        for (int i = 0; i < c.getInterfaceCount(); i++){
            RawUsbInterface tempInterface = c.getInterface(i);
            if (tempInterface.getNumber() == num) return tempInterface;
        }

        return null;
    }

    public String toString(){
        String out = "Device Descriptor:\n";

        out += String.format("  Length:             %5d\n",bLength);
        out += String.format("  Descriptor Type:    %5d\n",bDescriptorType);
        out += String.format("  BcdUSB:        %10s\n", "0x"+ Integer.toHexString(bcdUSB));
        out += String.format("  Device Class:       %5d\n",bDeviceClass);
        out += String.format("  Device Subclass:    %5d\n",bDeviceSubClass);
        out += String.format("  Device Protocol:    %5d\n",bDeviceProtocol);
        out += String.format("  MaxPacketSize0:     %5d\n",bMaxPacketSize0);
        out += String.format("  Vendor ID:     %10s\n", "0x"+ Integer.toHexString(idVendor));
        out += String.format("  Product ID:    %10s\n", "0x"+ Integer.toHexString(idProduct));
        out += String.format("  BcdDevice:     %10s\n", "0x"+ Integer.toHexString(bcdDevice));
        out += String.format("  Manufacturer Index: %5d\n",iManufacturer);
        out += String.format("  Product Index:      %5d\n",iProduct);
        out += String.format("  Serial Index:       %5d\n",iSerialNumber);
        out += String.format("  NumConfigurations:  %5d\n",bNumConfigurations);

        for (RawUsbConfiguration c : configs){
            out += c.toString();
        }

        return out;
    }

    private int byteToInt(byte b){
        return (int) b & 0xff;
    }

    private int bytesToInt(byte b, byte c){
        short b2 = (short)(b&0xff);
        short c2 = (short)(c&0xff);
        return (b2 << 8) | c2;
    }
}
