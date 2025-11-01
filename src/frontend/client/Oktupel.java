package frontend.client;
public class Oktupel<A, B, C, D, E, F, G, H> 
{
    private final A EINS;
    private final B ZWEI;
    private final C DREI;
    private final D VIER;
    private final E FÜNF;
    private final F SECHS;
    private final G SIEBEN;
    private final H ACHT;
    
    public Oktupel(A eins, B zwei, C drei, D vier, E fünf, F sechs, G sieben, H acht)
    {
        this.EINS = eins;
        this.ZWEI = zwei;
        this.DREI = drei;
        this.VIER = vier;
        this.FÜNF = fünf;
        this.SECHS = sechs;
        this.SIEBEN = sieben;
        this.ACHT = acht;
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

    public F getSECHS() 
    {
        return SECHS;
    }    

    public G getSIEBEN() 
    {
        return SIEBEN;
    }    

    public H getACHT() 
    {
        return ACHT;
    }
}

