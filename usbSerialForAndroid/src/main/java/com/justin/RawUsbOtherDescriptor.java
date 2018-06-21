package justin;

public class RawUsbOtherDescriptor {
    private int length,type;
    private byte[] descriptor;

    public RawUsbOtherDescriptor(byte[] desc){
        length = desc[0];
        type = desc[1];
        descriptor = desc;
    }

    public byte[] getRawDescriptor(){
        return descriptor;
    }

    public String toString(){
        String out = "Unknown Descriptor:\n";

        out += String.format("Length:             %5d\n",length);
        out += String.format("Descriptor Type:    %5d\n",type);

        return out;
    }
}
