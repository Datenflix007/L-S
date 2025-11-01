package frontend.client;
import java.awt.Dimension;
import java.awt.Toolkit;

public class ScreenSize // mit KI
{
    private Dimension screenSize;
    private int screenWidth;
    private int screenHeight;

    public ScreenSize()
    {
        screenSize = Toolkit.getDefaultToolkit().getScreenSize();
    }

    public int berechneScreenWidth()
    {
        screenWidth = (int) screenSize.getWidth();
        return screenWidth;
    }

    public int berechneScreenHeight()
    {
        screenHeight = (int) screenSize.getHeight();
        return screenHeight;
    }
}

