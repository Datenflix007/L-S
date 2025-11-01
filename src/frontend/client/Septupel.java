package frontend.client;
public class Septupel<A, B, C, D, E, F, G> 
{
    private final A EINS;
    private final B ZWEI;
    private final C DREI;
    private final D VIER;
    private final E FÜNF;
    private final F SECHS;
    private final G SIEBEN;
    
    public Septupel(A eins, B zwei, C drei, D vier, E fünf, F sechs, G sieben)
    {
        this.EINS = eins;
        this.ZWEI = zwei;
        this.DREI = drei;
        this.VIER = vier;
        this.FÜNF = fünf;
        this.SECHS = sechs;
        this.SIEBEN = sieben;
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
}

