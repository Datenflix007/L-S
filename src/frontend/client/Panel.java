package frontend.client;
import java.awt.*;
import java.awt.geom.*;
import java.util.*;
import java.util.function.*;
import javax.swing.*;
import javax.swing.border.*;

/**
 * @author Tobias Dietrich
 * 
 * Die Klasse Panel ermöglicht das erstellen eines selbstkonfigurierbaren Panels. Sie besteht aus zwei Teilen. Der erste ist die Panelklasse an sich und der zweite
 * ist eine in der Klasse Panel liegende innere Klasse Builder. Über die Methoden der Builder-Klasse wird das Panel erstellt.
 */

public class Panel extends JPanel
{
    /**
     * ARCRADIUS        ist eine Konstante, die enthält den Wert der bestimmt, wie stark die Ecken des Panels abgerundet werden
     * LAYOUT           ist eine Konstante, die das Layout speichert, das dem Panel zugewiesen wird
     * MINIMUM_SIZE     ist eine Konstante, die die Mindestgröße des Panels speichert
     * PREFERRED_SIZE   ist eine Konstante, die die bevorzugte Größe des Panels speichert (wird genommen wenn genung Platz auf dem Panel vorhanden ist)
     * MAXIMUM_SIZE     ist eine Konstante, die die maximale Größe des Panels speichert
     * BACKGROUND_COLOR ist eine Konstante, die die Hintergrundfarbe des Panels enthält
     * BORDER           ist eine Konstante, die die Border des Panels enthält
     */
    private final int ARCRADIUS;
    private final LayoutManager LAYOUT;
    private final Dimension MINIMUM_SIZE, PREFERRED_SIZE, MAXIMUM_SIZE;
    private final Color BACKGROUND_COLOR;
    private final Border BORDER;
    private final float ALIGNMENT_X, ALIGNMENT_Y;

    /**
     * @param builder es wird ein Builder-Objekt übergeben, das die Panelkonfiguration enthält. Die durch den Programmierer festgelegte Konfiguration wird dann im
     *                Konstruktor auf das zu erstellende Panel-Objekt übertragen.
     */
    private Panel(Builder builder)
    {
        this.ARCRADIUS = builder.arcRadius;
        if (builder.layoutFactory != null)
            this.LAYOUT = builder.layoutFactory.apply(this);
        else
            this.LAYOUT = null;
        this.MINIMUM_SIZE = builder.minimumSize;
        this.PREFERRED_SIZE = builder.preferredSize;
        this.MAXIMUM_SIZE = builder.maximumSize;
        this.BACKGROUND_COLOR = builder.backgroundColor;
        this.BORDER = builder.border;
        this.ALIGNMENT_X = builder.alignmentX;
        this.ALIGNMENT_Y = builder.alignmentY;

        /**
         * super.setLayout(LAYOUT)                 ruft die Methode setLayout() der Elternklasse, in unserem Fall JPanel, mit dem Parameter LAYOUT auf
         * super.setMinimumSize(MINIMUM_SIZE)      ruft die Methode setMinimumSize() der Elternklasse, in unserem Fall JPanel, mit dem Parameter MINIMUM_SIZE auf
         * super.setPreferredSize(PREFERRED_SIZE); ruft die Methode setPreferredSize() der Elternklasse, in unserem Fall JPanel, mit dem Parameter PREFERRED_SIZE auf
         * super.setMaximumSize(MAXIMUM_SIZE);     ruft die Methode setMaximumSize() der Elternklasse, in unserem Fall JPanel, mit dem Parameter MAXIMUM_SIZE auf
         * super.setBackground(BACKGROUND_COLOR);  ruft die Methode setBackground() der Elternklasse, in unserem Fall JPanel, mit dem Parameter BACKGROUND_COLOR auf
         * super.setBorder(BORDER);                ruft die Methode setBorder() der Elternklasse, in unserem Fall JPanel, mit dem Parameter BORDER auf
         */
        if (LAYOUT != null)
            super.setLayout(LAYOUT);
        if (MINIMUM_SIZE != null)
            super.setMinimumSize(MINIMUM_SIZE);
        if (PREFERRED_SIZE != null)
            super.setPreferredSize(PREFERRED_SIZE);
        if (MAXIMUM_SIZE != null)
            super.setMaximumSize(MAXIMUM_SIZE);
        if (BACKGROUND_COLOR != null)
            super.setBackground(BACKGROUND_COLOR);
        if (BORDER != null)
            super.setBorder(BORDER);
        if (ALIGNMENT_X == 0.0f || ALIGNMENT_X == 0.5f || ALIGNMENT_X == 1.0f)
            super.setAlignmentX(ALIGNMENT_X);
        if (ALIGNMENT_Y == 0.0f || ALIGNMENT_Y == 0.5f || ALIGNMENT_Y == 1.0f)
            super.setAlignmentY(ALIGNMENT_Y);

        /**
         * fügt jede Komponente, die dem Panel im Bulder zugewiesen wurde dem Panel hinzu
         */
        for (Component c : builder.components) 
        {
            if (LAYOUT instanceof BorderLayout && builder.constraintsMap.containsKey(c)) 
            {
                super.add(c, builder.constraintsMap.get(c));
            }
            else if (LAYOUT instanceof GridBagLayout && builder.constraintsMap.get(c) instanceof GridbagConstraints)
            {
                super.add(c, ((GridbagConstraints) builder.constraintsMap.get(c)).toAWT());
            }
            else 
            {
                super.add(c);
            }
        }

        // Im Konstruktor NACH dem Setzen von Farben/Größen:
        if (ARCRADIUS > 0) {
            setOpaque(false); // wir malen den Hintergrund selbst (rund)
        }
    }

    /**
     * überschreibt die paint Mehtode der Panel-Klasse, um runde Ecken zu zeichnen
     */
    @Override
public void paint(Graphics g) {
    if (ARCRADIUS > 0) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        Shape round = new java.awt.geom.RoundRectangle2D.Double(
                0, 0, getWidth(), getHeight(), ARCRADIUS, ARCRADIUS
        );
        g2.setClip(round);

        // eigenen Hintergrund malen (statt des opaken Standardfills)
        g2.setColor(BACKGROUND_COLOR != null ? BACKGROUND_COLOR : getBackground());
        g2.fill(round);

        // alles Weitere (Komponente, Border, Kinder) im Clip malen
        super.paint(g2);
        g2.dispose();
    } else {
        super.paint(g);
    }
}

@Override
protected void paintComponent(Graphics g) {
    // Bei runden Ecken NICHT noch einmal opak füllen – verhindert rechteckiges Übermalen
    if (ARCRADIUS <= 0) {
        super.paintComponent(g);
    }
}

    /**
     * gibt einen String zurück, der die Werte aller Klassenvariablen sowie die Anzahl der auf dem Panel befindlichen Komponenten enthält
     */
    @Override
    public String toString() 
    {
    return "Panel{" + System.lineSeparator() +
            "  arcRadius       = " + ARCRADIUS + System.lineSeparator() +
            ", layout          = " + (LAYOUT != null ? LAYOUT.getClass().getSimpleName() : "null") + System.lineSeparator() +
            ", minimumSize     = " + (MINIMUM_SIZE != null ? MINIMUM_SIZE.toString() : "null") + System.lineSeparator() +
            ", preferredSize   = " + (PREFERRED_SIZE != null ? PREFERRED_SIZE.toString() : "null") + System.lineSeparator() +
            ", maximumSize     = " + (MAXIMUM_SIZE != null ? MAXIMUM_SIZE.toString() : "null") + System.lineSeparator() +
            ", backgroundColor = " + (BACKGROUND_COLOR != null ? BACKGROUND_COLOR.toString() : "null") + System.lineSeparator() +
            ", border          = " + (BORDER != null ? BORDER.getClass().getSimpleName() : "null") + System.lineSeparator() +
            ", componentCount  = " + getComponentCount() + System.lineSeparator() +
            '}';
    }

    /**
     * inner Klasse von Panel, mit der eine Konfiguration für das, zu diesem Zeitpunkt noch nicht erstellte, Panel erfolgt
     */
    public static class Builder
    {
        /**
         * arcRadius        speichert während der Konfiguration des Panels den Wert der bestimmt, wie stark die Ecken des Panels abgerundet werden
         * layoutFactory    speichert während der Konfiguration des Panels eine Funktion, die bestimmt welches Layout dem Panel nach dessen Erstellung zugewiesen wird
         * minimumSize      speichert während der Konfiguration die Mindestgröße des Panels 
         * preferredSize    speichert während der Konfiguration die bevorzugte Größe des Panels
         * maximumSize      speichert während der Konfiguration die maximale Größe des Panels speichert
         * BACKGROUND_COLOR speichert während der Konfiguration die Hintergrundfarbe des Panels
         * BORDER           speichert während der Konfiguration die Border des Panels
         */
        private int arcRadius;
        private Function<Container, LayoutManager> layoutFactory;
        private Dimension minimumSize, preferredSize, maximumSize;
        private Color backgroundColor;
        private Border border;
        private float alignmentX, alignmentY;
        private ArrayList<Component> components = new ArrayList<>();
        private Map<Component, Object> constraintsMap = new HashMap<>();

        /**
         * @param arc Wert der angibt wie stark die Ecken des Panels gerundet werden
         * @return es wird das Builder-Objekt zurückgegeben
         */
        public Builder withRoundedCorners(int arc)  // mit KI
        {
            this.arcRadius = arc;
            return this;
        }

        /**
         * @param color Farbe, die als Hintergrundfarbe des Panels dienen soll
         * @param arc Wert der angibt wie stark die Ecken des Panels gerundet werden
         * @return es wird das Builder-Objekt zurückgegeben
         */
        public Builder withRoundedBackground(Color color, int arc) // mit KI
        {
            this.backgroundColor = color;
            this.arcRadius = arc;
            return this;
        }

        // ====================================================================================================================================
        // Layout-Factory mit KI generiert
        // ====================================================================================================================================
        public Builder withLayout(Function<Container, LayoutManager> layoutFactory) // Generische Factory-Methode und KI generiert
        {
            this.layoutFactory = layoutFactory;
            return this;
        }

        // FlowLayout
        public Builder withFlowLayout() 
        { 
            return withLayout(p -> new FlowLayout()); 
        }
        public Builder withFlowLayout(int align) 
        { 
            return withLayout(p -> new FlowLayout(align)); 
        }
        public Builder withFlowLayout(int align, int hgap, int vgap) 
        {
            return withLayout(p -> new FlowLayout(align, hgap, vgap)); 
        }

        // BorderLayout
        public Builder withBorderLayout() 
        { 
            return withLayout(p -> new BorderLayout()); 
        }
        public Builder withBorderLayout(int hgap, int vgap) 
        { 
            return withLayout(p -> new BorderLayout(hgap, vgap)); 
        }

        // GrindLayout
        public Builder withGridLayout(int rows, int cols) 
        {
            return withLayout(p -> new GridLayout(rows, cols)); 
        }
        public Builder withGridLayout(int rows, int cols, int hgap, int vgap) 
        {
            return withLayout(p -> new GridLayout(rows, cols, hgap, vgap)); 
        }

        // BoxLayout
        public Builder withBoxLayoutX() 
        {
            return withLayout(p -> new BoxLayout(p, BoxLayout.X_AXIS)); 
        }
        public Builder withBoxLayoutY() 
        {
            return withLayout(p -> new BoxLayout(p, BoxLayout.Y_AXIS)); 
        }

        // GridBagLayout
        public Builder withGridBagLayout() 
        { 
            return withLayout(p -> new GridBagLayout()); 
        }

        public Builder minimumSize(Dimension minimumSize)
        {
            this.minimumSize = minimumSize;
            return this;
        }

        public Builder preferredSize(Dimension preferredSize)
        {
            this.preferredSize = preferredSize;
            return this;
        }

        public Builder maximumSize(Dimension maximumSize)
        {
            this.maximumSize = maximumSize;
            return this;
        }

        public Builder backgroundColor(Color backgroundColor)
        {
            this.backgroundColor = backgroundColor;
            return this;
        }

        public Builder border(Border border)
        {
            this.border = border;
            return this;
        }

        public Builder alignmentX(float alignment)
        {
            this.alignmentX = alignment;
            return this;
        }

        public Builder alignmentY(float alignment)
        {
            this.alignmentY = alignment;
            return this;
        }

        // Box Komponenten hinzufügen
        public Builder addRigidArea(Dimension dimension)
        {
            components.add(Box.createRigidArea(dimension));
            return this;
        }

        public Builder addVerticalGlue()
        {
            components.add(Box.createVerticalGlue());
            return this;
        }

        public Builder addHorizontalGlue()
        {
            this.components.add(Box.createHorizontalGlue());
            return this;
        }

        public Builder addComponent(Component component)
        {
            this.components.add(component);
            return this;
        }

        public Builder addComponent(Component component, Object constraints)
        {
            components.add(component);
            constraintsMap.put(component, constraints);
            return this;
        }

        public Panel build()
        {
            return new Panel(this);
        }
    }
}

