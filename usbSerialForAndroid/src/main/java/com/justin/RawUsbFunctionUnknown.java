package justin;

public class RawUsbFunctionUnknown extends RawUsbFunctionInterface {

    public RawUsbFunctionUnknown(byte[] desc) throws DescriptorException{
        super(desc);
    }

    public String toString(){
        String out = "      Unknown Functional Descriptor:\n";

        out += String.format("        Length:             %5d\n",bFunctionLength);
        out += String.format("        Descriptor Type:    %5d\n",bDescriptorType);
        out += String.format("        Subtype:            %5d\n",bDescriptorSubtype);

        return out;
    }
}
