package frontend.client;

import java.awt.*;
import javax.swing.*;


/** 
 * @author Tobias Dietrich
*/

public class Window extends JFrame
{
    /**
     * @param relativeLocation bestimmt die relative Position des Fenster zu anderen Komponenten (wird der Wert null gesetzt, dann ist das Fenster zentriert)
     * @param menuBar bestimmt, ob die Menübar des Fensters gesetzt ist oder nicht
     */
    public Window(Component relativeLocation, boolean menuBar)
    {
        super.setLocationRelativeTo(relativeLocation);
        if (menuBar == true)
            super.setDefaultCloseOperation(EXIT_ON_CLOSE);
        else
            super.setUndecorated(true);
    }
}
