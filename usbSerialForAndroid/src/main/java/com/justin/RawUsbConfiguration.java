package justin;

import java.util.ArrayList;

import static justin.RawUsbManager.CONFIGURATION_TYPE;

public class RawUsbConfiguration {
    private int bLength,bDescriptorType,wTotalLength,bNumInterfaces,bConfigurationValue,
                iConfiguration,bMaxPower;
    private byte bmAttributes;
    private ArrayList<RawUsbInterface> interfaces;
    private ArrayList<RawUsbOtherDescriptor> others;

    public RawUsbConfiguration(byte[] desc) throws DescriptorException{
        bLength = byteToInt(desc[0]);
        bDescriptorType = byteToInt(desc[1]);

        //if the type isn't correct, this was given the wrong descriptor.
        if (bDescriptorType != CONFIGURATION_TYPE){throw new IncorrectDescriptorException(bDescriptorType);}
        //if the length of the descriptor is less than what's expected, the descriptor is bad
        if (desc.length < bLength){throw new BadDescriptorException();}

        wTotalLength = bytesToInt(desc[3],desc[2]);
        bNumInterfaces = byteToInt(desc[4]);
        bConfigurationValue = byteToInt(desc[5]);
        iConfiguration = byteToInt(desc[6]);
        bmAttributes = desc[7];
        bMaxPower = byteToInt(desc[8]);

        interfaces = new ArrayList<RawUsbInterface>();
        others = new ArrayList<RawUsbOtherDescriptor>();
    }

    public RawUsbInterface getInterface(int index){
        return interfaces.get(index);
    }

    public int getInterfaceCount(){
        return interfaces.size();
    }

    public void addInterface(RawUsbInterface i){
        interfaces.add(i);
    }

    public void addOther(RawUsbOtherDescriptor o){
        others.add(o);
    }

    public int getTotalLength(){
        return wTotalLength;
    }

    public int getLength(){
        return bLength;
    }

    public String toString(){
        String out = "  Configuration descriptor:\n";

        out += String.format("    Length:             %5d\n",bLength);
        out += String.format("    Descriptor Type:    %5d\n",bDescriptorType);
        out += String.format("    Total Length:       %5d\n",wTotalLength);
        out += String.format("    NumInterfaces:      %5d\n",bNumInterfaces);
        out += String.format("    Configuration Value:%5d\n", bConfigurationValue);
        out += String.format("    ConfigIndex:        %5d\n", iConfiguration);
        out += String.format("    Attributes:         %5s\n", "0x"+ Integer.toHexString(bmAttributes & 0xFF));
        out += String.format("    Max Power:          %5d\n", bMaxPower*2);

        for (RawUsbInterface i : interfaces){
            out += i.toString();
        }

        for (RawUsbOtherDescriptor o : others){
            out += o.toString();
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
