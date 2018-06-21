package justin;

import static justin.RawUsbManager.FUNCTION_INTERFACE_TYPE;

public abstract class RawUsbFunctionInterface {
    protected int bFunctionLength,bDescriptorType,bDescriptorSubtype;

    public RawUsbFunctionInterface(byte[] desc) throws DescriptorException{
            bFunctionLength = byteToInt(desc[0]);
            bDescriptorType = byteToInt(desc[1]);
            bDescriptorSubtype= byteToInt(desc[2]);

        //if the type isn't correct, this was given the wrong descriptor.
        if (bDescriptorType != FUNCTION_INTERFACE_TYPE){throw new IncorrectDescriptorException(bDescriptorType);}
        //if the length of the descriptor is less than what's expected, the descriptor is bad
        if (desc.length < bFunctionLength){throw new BadDescriptorException();}
    }

    protected int byteToInt(byte b){
        return (int) b & 0xff;
    }

    protected int bytesToInt(byte b, byte c){
        short b2 = (short)(b&0xff);
        short c2 = (short)(c&0xff);
        return (b2 << 8) | c2;
    }
}