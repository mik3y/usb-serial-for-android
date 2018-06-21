package justin;

import static justin.RawUsbManager.ENDPOINT_TYPE;

public class RawUsbEndpoint {
    private int bLength,bDescriptorType,bEndpointAddress,
                wMaxPacketSize,bInterval;
    private byte bmAttributes;

    public RawUsbEndpoint(byte[] desc) throws DescriptorException{
        bLength = byteToInt(desc[0]);
        bDescriptorType = byteToInt(desc[1]);

        //If the type isn't correct, this was given the wrong descriptor.
        if (bDescriptorType != ENDPOINT_TYPE){
            throw new IncorrectDescriptorException(bDescriptorType);
        }
        //If the length of the descriptor is less than what's expected, the descriptor is bad
        if (desc.length < bLength){throw new BadDescriptorException();}

        bEndpointAddress = desc[2];
        bmAttributes = desc[3];
        wMaxPacketSize = bytesToInt(desc[5],desc[4]);
        bInterval = byteToInt(desc[6]);
    }

    public int getEndpointDirection(){
        //0 is OUT, 1 (or non-zero) is IN.
        return bEndpointAddress & 0b10000000;
    }

    public boolean isIn(){
        if (getEndpointDirection() != 0) return true;
        return false;
    }

    public String toString(){
        String out = "      Endpoint Descriptor:\n";

        out += String.format("        Length:             %5d\n", bLength);
        out += String.format("        Descriptor Type:    %5d\n",bDescriptorType);
        out += String.format("        Address:       %10s\n",
                "0x"+ Integer.toHexString(bEndpointAddress & 0xff)+
                        (isIn() ? "(IN)" : "(OUT)"));
        out += String.format("        Attributes:    %10s\n", "0x"+ Integer.toHexString(bmAttributes));
        out += String.format("        Max Packet Size:%9s\n", "0x"+ Integer.toHexString(wMaxPacketSize));
        out += String.format("        bInterval:          %5d\n",bInterval);

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
