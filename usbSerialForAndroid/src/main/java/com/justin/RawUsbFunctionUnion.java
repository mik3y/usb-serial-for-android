package justin;

import java.util.ArrayList;

import static justin.RawUsbManager.FUNCTION_UNION_SUBTYPE;

public class RawUsbFunctionUnion extends RawUsbFunctionInterface {
    private int bMasterInterface;
    private ArrayList<Integer> bSlaveInterfaces;

    public RawUsbFunctionUnion(byte[] desc) throws DescriptorException{
        super(desc);

        //If the type isn't correct, this was given the wrong descriptor.
        if (bDescriptorSubtype != FUNCTION_UNION_SUBTYPE){
            throw new IncorrectDescriptorException(bDescriptorType);
        }
        //If the length of the descriptor is less than what's expected, the descriptor is bad
        if (desc.length < bFunctionLength){throw new BadDescriptorException();}

        bMasterInterface = desc[3];
        bSlaveInterfaces = new ArrayList<Integer>();

        for (int i = 4; i < bFunctionLength; i++){
            bSlaveInterfaces.add(new Integer(desc[i]));
        }
    }

    public int getMasterInterface(){
        return bMasterInterface;
    }

    public int getSlaveInterface(int index){
        return bSlaveInterfaces.get(index);
    }

    public String toString(){
        String out = "      CDC Union:\n";
        out += String.format("        Master Interface:   %5d\n", bMasterInterface);

        for (int i = 0; i < bSlaveInterfaces.size(); i++){
            out += String.format("        Slave Interface %d:  %5d\n",i,bSlaveInterfaces.get(i));
        }

        return out;
    }
}
