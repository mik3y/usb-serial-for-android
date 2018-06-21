package justin;

import static justin.RawUsbManager.FUNCTION_HEADER_SUBTYPE;

public class RawUsbFunctionHeader extends RawUsbFunctionInterface {

    private int bcdCDC;

    public RawUsbFunctionHeader(byte[] desc) throws DescriptorException{
        super(desc);

        //If the type isn't correct, this was given the wrong descriptor.
        if (bDescriptorSubtype != FUNCTION_HEADER_SUBTYPE){throw new IncorrectDescriptorException(bDescriptorType);}
        //If the length of the descriptor is less than what's expected, the descriptor is bad
        if (desc.length < bFunctionLength){throw new BadDescriptorException();}

        bcdCDC = bytesToInt(desc[4],desc[3]);
    }

    public String toString(){
        String out = "      CDC Header:\n";
        out += String.format("        Release Number:%10s\n", "0x"+ Integer.toHexString(bcdCDC));

        return out;
    }
}
