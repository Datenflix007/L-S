package frontend.client;
public class Tupel <A, B> 
{
    private final A EINS;
    private final B ZWEI;
    
    public Tupel(A eins, B zwei)
    {
        this.EINS = eins;
        this.ZWEI = zwei;
    }

    public A getEINS() 
    {
        return EINS;
    }

    public B getZWEI()
    {
        return ZWEI;
    }
}
