package frontend.client;
import java.awt.Dimension;

public class Quadrupel<A, B, C, D> 
{
    private final A EINS;
    private final B ZWEI;
    private final C DREI;
    private final D VIER;
    
    public Quadrupel(A eins, B zwei, C drei, D vier)
    {
        this.EINS = eins;
        this.ZWEI = zwei;
        this.DREI = drei;
        this.VIER = vier;
    }

    public A getEINS() 
    {
        return EINS;
    }

    public B getZWEI() 
    {
        return ZWEI;
    }

    public C getDREI() 
    {
        return DREI;
    }  
    
    public D getVIER() 
    {
        return VIER;
    }
}

