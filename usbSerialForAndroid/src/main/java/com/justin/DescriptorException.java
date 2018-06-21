package justin;

public class DescriptorException extends Exception {
}

class IncorrectDescriptorException extends DescriptorException {
    private int type;
    public IncorrectDescriptorException(int type){
        this.type = type;
    }
    public int getType(){
        return this.type;
    }
}

class BadDescriptorException extends DescriptorException {
}