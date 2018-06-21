package justin;

import android.util.Log;

import java.util.Arrays;

public class RawUsbManager {
    public static final int DEVICE_TYPE = 1;
    public static final int CONFIGURATION_TYPE = 2;
    public static final int INTERFACE_TYPE = 4;
    public static final int ENDPOINT_TYPE = 5;
    public static final int INTERFACE_ASSOCIATION_TYPE = 11;

    public static final int FUNCTION_INTERFACE_TYPE = 36;

    public static final int FUNCTION_HEADER_SUBTYPE = 0;
    public static final int FUNCTION_CALL_MGMT_SUBTYPE = 1;
    public static final int FUNCTION_ACM_SUBTYPE = 2;
    public static final int FUNCTION_UNION_SUBTYPE = 6;
    public static final int FUNCTION_ETHERNET_SUBTYPE = 15;
    public static final int FUNCTION_NCM_SUBTYPE = 26;

    public static String TAG = "com.justin.RawUsbManager";

    public static RawUsbDevice getDeviceFromRawString(byte[] desc){
        int dLength, dType, absOffset;
        absOffset = 0;

        RawUsbDevice tempDevice = null;

        //Outside loop reads entire device descriptor
        while (absOffset < desc.length){
            dLength = byteToInt(desc[0]);
            dType = byteToInt(desc[1]);

            //We only want to build the device if this is a device descriptor.
            if (dType == 1){
                try{
                    //Build the device from the descriptor. If this throws an exception then
                    //we will just return null since we can't read in the device.
                    tempDevice = new RawUsbDevice(
                            Arrays.copyOfRange(desc,absOffset,absOffset+dLength));
                } catch (DescriptorException e){
                    return null;
                }

                absOffset += dLength;

                //This line parses all of the device's configurations
                absOffset = buildConfigs(tempDevice,desc,absOffset);
            }
        }

        return tempDevice;
    }

    private static int buildConfigs(RawUsbDevice tempDevice, byte[] desc, int absOffset){
        RawUsbConfiguration tempConfig;

        int cLength = desc[absOffset];

        int numConfigs = tempDevice.getConfigurationCount();

        for (int i = 0; i < numConfigs; i++){
            try{
                //Creates configuration object
                tempConfig = new RawUsbConfiguration(
                        Arrays.copyOfRange(desc,absOffset,absOffset+cLength));
                //Parses full configuration and builds interfaces/endpoints/etc.
                buildConfig(tempConfig,
                        Arrays.copyOfRange(desc,absOffset,
                                absOffset+tempConfig.getTotalLength()));
                tempDevice.addConfiguration(tempConfig);

                absOffset += tempConfig.getTotalLength();
            } catch (DescriptorException e){
                //If we get a descriptor exception, adding cLength allows us to
                //keep reading until we find another configuration descriptor.
                absOffset += cLength;
            }

        }

        return absOffset;
    }

    private static void buildConfig(RawUsbConfiguration tempConfig, byte[] desc){
        RawUsbInterface tempInterface = null;
        RawUsbFunctionInterface tempFD;
        RawUsbEndpoint tempEndpoint;

        int intLength, intType;
        int total = tempConfig.getTotalLength();
        int offset = tempConfig.getLength();

        while (offset < total){
            intLength = desc[offset];
            intType = desc[offset+1];

            try {
                if (intType == INTERFACE_TYPE){ //Construct Interface
                    Log.d(TAG,"Found an interface");
                    tempInterface = new RawUsbInterface(
                            Arrays.copyOfRange(desc,offset,offset+intLength));
                    tempConfig.addInterface(tempInterface);

                } else if (intType == FUNCTION_INTERFACE_TYPE){
                    //Construct functional interface
                    tempFD = parseFunctionalDescriptor(desc,offset,intLength);
                    //doesn't check if tempInterface is null, fix later
                    tempInterface.addFunctionalDescriptor(tempFD);

                } else if (intType == ENDPOINT_TYPE){
                    tempEndpoint = new RawUsbEndpoint(
                            Arrays.copyOfRange(desc,offset,offset+intLength));
                    tempInterface.addEndpoint(tempEndpoint);

                } else if (intType == INTERFACE_ASSOCIATION_TYPE){
                    tempInterface = new RawUsbInterfaceAssociation(
                            Arrays.copyOfRange(desc,offset,offset+intLength));
                    tempConfig.addInterface(tempInterface);

                } else {
                    tempConfig.addOther(new RawUsbOtherDescriptor(
                            Arrays.copyOfRange(desc,offset,offset+intLength)));
                }
            } catch (DescriptorException e){
                //TODO add exception handling. Maybe return null?
                Log.d(TAG,"Uh oh, descriptor exception");
            }

            offset += intLength;
        }
    }

    private static RawUsbFunctionInterface parseFunctionalDescriptor(byte[] desc, int offset, int intLength)
            throws DescriptorException{

        RawUsbFunctionInterface tempFD;

        int functionSubtype = desc[offset+2];

        if (functionSubtype == FUNCTION_HEADER_SUBTYPE){
            tempFD = new RawUsbFunctionHeader(
                    Arrays.copyOfRange(desc,offset,offset+intLength));
        } else if (functionSubtype == FUNCTION_CALL_MGMT_SUBTYPE){
            tempFD = new RawUsbFunctionCallMgmt(
                    Arrays.copyOfRange(desc,offset,offset+intLength));
        } else if (functionSubtype == FUNCTION_ACM_SUBTYPE){
            tempFD = new RawUsbFunctionACM(
                    Arrays.copyOfRange(desc,offset,offset+intLength));
        } else if (functionSubtype == FUNCTION_UNION_SUBTYPE){
            tempFD = new RawUsbFunctionUnion(
                    Arrays.copyOfRange(desc,offset,offset+intLength));
        } else if (functionSubtype == FUNCTION_ETHERNET_SUBTYPE){
            tempFD = new RawUsbFunctionEthernetNetworking(
                    Arrays.copyOfRange(desc,offset,offset+intLength));
        } else if (functionSubtype == FUNCTION_NCM_SUBTYPE){
            tempFD = new RawUsbFunctionNCM(
                    Arrays.copyOfRange(desc,offset,offset+intLength));
        } else {
            tempFD = new RawUsbFunctionUnknown(
                    Arrays.copyOfRange(desc,offset,offset+intLength));
        }

        return tempFD;
    }

    private static int byteToInt(byte b){
        return (int) b & 0xff;
    }

    private static int bytesToInt(byte b, byte c){
        short b2 = (short)(b&0xff);
        short c2 = (short)(c&0xff);
        return (b2 << 8) | c2;
    }
}
