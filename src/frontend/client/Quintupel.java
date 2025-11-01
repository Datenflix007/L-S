package frontend.client;
public class Quintupel<A, B, C, D, E> 
{
    private final A EINS;
    private final B ZWEI;
    private final C DREI;
    private final D VIER;
    private final E FÜNF;
    
    public Quintupel(A eins, B zwei, C drei, D vier, E fünf)
    {
        this.EINS = eins;
        this.ZWEI = zwei;
        this.DREI = drei;
        this.VIER = vier;
        this.FÜNF = fünf;
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

    public E getFÜNF() 
    {
        return FÜNF;
    }
}

