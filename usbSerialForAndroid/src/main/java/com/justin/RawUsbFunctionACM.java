package justin;

import static justin.RawUsbManager.FUNCTION_ACM_SUBTYPE;

public class RawUsbFunctionACM extends RawUsbFunctionInterface {
    private int bmCapabilities;

    public RawUsbFunctionACM(byte[] desc) throws DescriptorException{
        super(desc);

        //If the type isn't correct, this was given the wrong descriptor.
        if (bDescriptorSubtype != FUNCTION_ACM_SUBTYPE){
            throw new IncorrectDescriptorException(bDescriptorType);
        }
        //If the length of the descriptor is less than what's expected, the descriptor is bad
        if (desc.length < bFunctionLength){throw new BadDescriptorException();}

        bmCapabilities = desc[3];
    }

    public String toString(){
        String out = "      CDC ACM:\n";
        out += String.format("        Capabilities:  %10s\n", "0x"+ Integer.toHexString(bmCapabilities));

        return out;
    }
}
