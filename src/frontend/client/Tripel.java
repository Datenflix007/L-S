package frontend.client;
public class Tripel <A, B, C> 
{
    private final A EINS;
    private final B ZWEI;
    private final C DREI;
    
    public Tripel(A eins, B zwei, C drei)
    {
        this.EINS = eins;
        this.ZWEI = zwei;
        this.DREI = drei;
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
}

