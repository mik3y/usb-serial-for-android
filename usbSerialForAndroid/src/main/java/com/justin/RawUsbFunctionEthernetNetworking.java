package justin;

import static justin.RawUsbManager.FUNCTION_ETHERNET_SUBTYPE;

public class RawUsbFunctionEthernetNetworking extends RawUsbFunctionInterface {
    private int iMACAddress,bmEthernetStatistics,wMaxSegmentSize,
                wNumberMCFilters,bNumberPowerFilters;
    public RawUsbFunctionEthernetNetworking(byte[] desc) throws DescriptorException{
        super(desc);

        //If the type isn't correct, this was given the wrong descriptor.
        if (bDescriptorSubtype != FUNCTION_ETHERNET_SUBTYPE){
            throw new IncorrectDescriptorException(bDescriptorType);
        }
        //If the length of the descriptor is less than what's expected, the descriptor is bad
        if (desc.length < bFunctionLength){throw new BadDescriptorException();}

        iMACAddress = byteToInt(desc[3]);
        bmEthernetStatistics = fourBytesToInt(desc[7],desc[6],desc[5],desc[4]);
        wMaxSegmentSize = bytesToInt(desc[9],desc[8]);
        wNumberMCFilters = bytesToInt(desc[11],desc[10]);
        bNumberPowerFilters = byteToInt(desc[12]);
    }

    public String toString(){
        String out = "      CDC Ethernet:\n";

        out += String.format("        iMACAddress:        %5d\n", iMACAddress);
        out += String.format("        bmEthStats:    %10s\n", Integer.toBinaryString(bmEthernetStatistics));
        out += String.format("        wMaxSegmentSize:    %5d\n", wMaxSegmentSize);
        out += String.format("        wNumberMCFilters:   %5d\n", wNumberMCFilters);
        out += String.format("        bNumberPowerFilters:%5d\n", bNumberPowerFilters);

        return out;
    }

    private int fourBytesToInt(byte a, byte b, byte c, byte d){
        short a2 = (short)(a&0xff);
        short b2 = (short)(b&0xff);
        short c2 = (short)(c&0xff);
        short d2 = (short)(d&0xff);
        return (a2 << 24) | (b2 << 16) | (c2 << 8) | d2;
    }
}
