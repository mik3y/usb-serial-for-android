package justin;

import static justin.RawUsbManager.FUNCTION_NCM_SUBTYPE;

public class RawUsbFunctionNCM extends RawUsbFunctionInterface {

    public RawUsbFunctionNCM(byte[] desc) throws DescriptorException{
        super(desc);

        //If the type isn't correct, this was given the wrong descriptor.
        if (bDescriptorSubtype != FUNCTION_NCM_SUBTYPE){
            throw new IncorrectDescriptorException(bDescriptorType);
        }
        //If the length of the descriptor is less than what's expected, the descriptor is bad
        if (desc.length < bFunctionLength){throw new BadDescriptorException();}
    }

    public String toString(){
        String out = "      CDC NCM:\n";
        out += "        NCM data parsing is not yet available.\n";

        return out;
    }
}
