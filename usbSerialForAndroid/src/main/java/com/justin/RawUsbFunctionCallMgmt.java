package justin;

import static justin.RawUsbManager.FUNCTION_CALL_MGMT_SUBTYPE;

public class RawUsbFunctionCallMgmt extends RawUsbFunctionInterface {
    private byte bmCapabilities;
    private int bDataInterface;

    public RawUsbFunctionCallMgmt(byte[] desc) throws DescriptorException{
        super(desc);

        //if the type isn't correct, this was given the wrong descriptor.
        if (bDescriptorSubtype != FUNCTION_CALL_MGMT_SUBTYPE){throw new IncorrectDescriptorException(bDescriptorType);}
        //if the length of the descriptor is less than what's expected, the descriptor is bad
        if (desc.length < bFunctionLength){throw new BadDescriptorException();}

        bmCapabilities = desc[3];
        bDataInterface = desc[4];
    }

    public String toString(){
        String out = "      CDC Call Management:\n";
        out += String.format("        Capabilities:  %10s\n", "0x"+ Integer.toHexString(bmCapabilities));
        out += String.format("        Data iFace Number:  %5d\n", bDataInterface);

        return out;
    }
}
