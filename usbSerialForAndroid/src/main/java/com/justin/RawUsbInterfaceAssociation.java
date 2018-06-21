package justin;

import static justin.RawUsbManager.INTERFACE_ASSOCIATION_TYPE;

public class RawUsbInterfaceAssociation extends RawUsbInterface {
    private int bLength,bDescriptorType,bFirstInterface,bInterfaceCount,
                bFunctionClass,bFunctionSubClass,bFunctionProtocol,iFunction;

    public RawUsbInterfaceAssociation(byte[] desc) throws DescriptorException{
        bLength = byteToInt(desc[0]);
        bDescriptorType = byteToInt(desc[1]);

        //If the type isn't correct, this was given the wrong descriptor.
        if (bDescriptorType != INTERFACE_ASSOCIATION_TYPE){
            throw new IncorrectDescriptorException(bDescriptorType);
        }
        //If the length of the descriptor is less than what's expected, the descriptor is bad
        if (desc.length < bLength){throw new BadDescriptorException();}
        bFirstInterface = byteToInt(desc[2]);
        bInterfaceCount = byteToInt(desc[3]);
        bFunctionClass = byteToInt(desc[4]);
        bFunctionSubClass = byteToInt(desc[5]);
        bFunctionProtocol = byteToInt(desc[6]);
        iFunction = byteToInt(desc[7]);
    }

    public String toString(){
        String out = "    Interface Association:\n";

        out += String.format("      Length:             %5d\n",bLength);
        out += String.format("      Descriptor Type:    %5d\n",bDescriptorType);
        out += String.format("      First Interface:    %5d\n",bFirstInterface);
        out += String.format("      Interface Count:    %5d\n",bInterfaceCount);
        out += String.format("      Function Class:     %5d\n",bFunctionClass);
        out += String.format("      Function Subclass:  %5d\n",bFunctionSubClass);
        out += String.format("      Protocol:           %5d\n",bFunctionProtocol);
        out += String.format("      Function Index:     %5d\n",iFunction);

        return out;
    }

    private int byteToInt(byte b){
        return (int) b & 0xff;
    }
}
