package justin;

import java.util.ArrayList;

import static justin.RawUsbManager.INTERFACE_TYPE;

public class RawUsbInterface {
    private int bLength, bDescriptorType, bInterfaceNumber, bAlternateSetting,
                bNumEndpoints, bInterfaceClass, bInterfaceSubClass,
                bInterfaceProtocol, iInterface;

    private ArrayList<RawUsbFunctionInterface> functionalDescriptors;
    private ArrayList<RawUsbEndpoint> endpoints;

    //This is only here so RawUsbInterfaceAssociation can be a subclass of this
    protected RawUsbInterface(){}

    public RawUsbInterface(byte[] desc) throws DescriptorException {
        //read the length of this descriptor as well as the descriptor type
        bLength = byteToInt(desc[0]);
        bDescriptorType = byteToInt(desc[1]);

        //if the type isn't correct, this was given the wrong descriptor.
        if (bDescriptorType != INTERFACE_TYPE){throw new IncorrectDescriptorException(bDescriptorType);}
        //if the length of the descriptor is less than what's expected, the descriptor is bad
        if (desc.length < bLength){throw new BadDescriptorException();}

        //read the rest of the descriptor information
        bInterfaceNumber = byteToInt(desc[2]);
        bAlternateSetting = byteToInt(desc[3]);
        bNumEndpoints = byteToInt(desc[4]);
        bInterfaceClass = byteToInt(desc[5]);
        bInterfaceSubClass = byteToInt(desc[6]);
        bInterfaceProtocol = byteToInt(desc[7]);
        iInterface = byteToInt(desc[8]);

        functionalDescriptors = new ArrayList<RawUsbFunctionInterface>();
        endpoints = new ArrayList<RawUsbEndpoint>();
    }

    public RawUsbEndpoint getEndpoint(int index){
        return endpoints.get(index);
    }

    public int getEndpointCount(){
        return endpoints.size();
    }

    public int getInterfaceClass(){
        return bInterfaceClass;
    }

    public int getInterfaceSubclass(){
        return bInterfaceSubClass;
    }

    public void addFunctionalDescriptor(RawUsbFunctionInterface f){
        functionalDescriptors.add(f);
    }

    public int getFunctionalDescriptorCount(){
        return functionalDescriptors.size();
    }

    public RawUsbFunctionInterface getFunctionalDescriptor(int index){
        return functionalDescriptors.get(index);
    }

    public void addEndpoint(RawUsbEndpoint e){
        endpoints.add(e);
    }

    public int getNumber(){
        return bInterfaceNumber;
    }

    public String toString(){
        String out = "    Interface Descriptor:\n";
        String extra1 = "";
        String extra2 = "";

        switch (bInterfaceClass){
            case 2:
                extra1 = "  (Communications)";
                break;
            case 10:
                extra1 = "  (CDC Data)";
                break;
        }
        if (bInterfaceClass == 2){
            switch (bInterfaceSubClass){
                case 2:
                    extra2 = "  (Abstract Control Model)";
                    break;
                case 13:
                    extra2 = "  (Network Control Model)";
                    break;
            }
        }

        out += String.format("      Length:             %5d\n",bLength);
        out += String.format("      Descriptor Type:    %5d\n",bDescriptorType);
        out += String.format("      Interface Number:   %5d\n",bInterfaceNumber);
        out += String.format("      Alternate Setting:  %5d\n",bAlternateSetting);
        out += String.format("      Number of Endpoints:%5d\n",bNumEndpoints);
        out += String.format("      Interface Class:    %5d\n",bInterfaceClass,extra1);
        out += String.format("      Interface Subclass: %5d\n",bInterfaceSubClass,extra2);
        out += String.format("      Protocol:           %5d\n",bInterfaceProtocol);
        out += String.format("      String Index:       %5d\n",iInterface);

        for (RawUsbFunctionInterface f : functionalDescriptors){
            out += f.toString();
        }

        for (RawUsbEndpoint e : endpoints){
            out += e.toString();
        }

        return out;
    }

    private int byteToInt(byte b) {
        return (int) b & 0xff;
    }
}
