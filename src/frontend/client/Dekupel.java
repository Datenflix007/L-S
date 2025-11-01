package frontend.client;
public class Dekupel <A, B, C, D, E, F, G, H, I, J> 
{
    private final A EINS;
    private final B ZWEI;
    private final C DREI;
    private final D VIER;
    private final E FÜNF;
    private final F SECHS;
    private final G SIEBEN;
    private final H ACHT;
    private final I NEUN;
    private final J ZEHN;
    
    public Dekupel(A eins, B zwei, C drei, D vier, E fünf, F sechs, G sieben, H acht, I neun, J zehn)
    {
        this.EINS = eins;
        this.ZWEI = zwei;
        this.DREI = drei;
        this.VIER = vier;
        this.FÜNF = fünf;
        this.SECHS = sechs;
        this.SIEBEN = sieben;
        this.ACHT = acht;
        this.NEUN = neun;
        this.ZEHN = zehn;
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

    public I getNEUN() 
    {
        return NEUN;
    }

    public J getZEHN() 
    {
        return ZEHN;
    }
}

