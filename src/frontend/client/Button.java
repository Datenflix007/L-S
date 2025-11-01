package frontend.client;

import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.geom.*;
import javax.swing.*;
import javax.swing.border.*;


public class Button extends JButton
{
    private final int ARCRADIUS;
    private final Dimension MINIMUM_SIZE, PREFERRED_SIZE, MAXIMUM_SIZE;
    private final Color FOREGROUND_COLOR, BACKGROUND_COLOR;
    private final Border BORDER;
    private final Font FONT;
    private final boolean ARROW_BUTTON;
    private final Color ARROW_COLOR;
    private final float ALIGNMENT_X, ALIGNMENT_Y;
    private String label;
    private final ActionListener ACTION_LISTENER;

    private Button(Builder builder)
    {
        this.ARCRADIUS = builder.arcRadius;
        this.MINIMUM_SIZE = builder.minimumSize;
        this.PREFERRED_SIZE = builder.preferredSize;
        this.MAXIMUM_SIZE = builder.maximumSize;
        this.FOREGROUND_COLOR = builder.forgroundColor;
        this.BACKGROUND_COLOR = builder.backgroundColor;
        this.BORDER = builder.border;
        this.FONT = builder.font;
        this.ALIGNMENT_X = builder.alignmentX;
        this.ALIGNMENT_Y = builder.alignmentY;
        this.ARROW_BUTTON = builder.arrowButton;
        this.ARROW_COLOR = builder.arrowColor;
        this.label = builder.label;
        this.ACTION_LISTENER = builder.actionListener;

        super.setContentAreaFilled(false); // mit KI
        setFocusPainted(false); // mit KI
        setBorderPainted(false); // mit KI
        if (MINIMUM_SIZE != null)
            super.setMinimumSize(MINIMUM_SIZE);
        if (PREFERRED_SIZE != null)
            super.setPreferredSize(PREFERRED_SIZE);
        if (MAXIMUM_SIZE != null)
            super.setMaximumSize(MAXIMUM_SIZE);
        if (FOREGROUND_COLOR != null)
            super.setForeground(FOREGROUND_COLOR);
        if (BACKGROUND_COLOR != null)
            super.setBackground(BACKGROUND_COLOR);
        if (BORDER != null)
            super.setBorder(BORDER);
        if (FONT != null)
            super.setFont(FONT);
        if (label != null)
            super.setText(this.label);
        if (ALIGNMENT_X == 0.0f || ALIGNMENT_X == 0.5f || ALIGNMENT_X == 1.0f)
            super.setAlignmentX(ALIGNMENT_X);
        if (ALIGNMENT_Y == 0.0f || ALIGNMENT_Y == 0.5f || ALIGNMENT_Y == 1.0f)
            super.setAlignmentY(ALIGNMENT_Y);
        if (ACTION_LISTENER != null)
            super.addActionListener(ACTION_LISTENER);
    }

    @Override // mit KI
    protected void paintComponent(Graphics graphics)
    {
        Graphics2D newGraphics2d = (Graphics2D) graphics.create();
        newGraphics2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
         // Linienstärke proportional zur Buttonhöhe (10%)
        float strokeWidth = Math.max(2f, getHeight() * 0.1f); // mind. 2px
        newGraphics2d.setStroke(new BasicStroke(strokeWidth));


        Shape rundesRechteck = new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), ARCRADIUS, ARCRADIUS);
        newGraphics2d.setColor(super.getBackground());
        newGraphics2d.fill(rundesRechteck);

        if (ARROW_BUTTON == true) 
        {
            if (ARROW_COLOR != null)
                newGraphics2d.setColor(ARROW_COLOR);
            else
                newGraphics2d.setColor(getForeground());

            int centerY = getHeight() / 2;

            // Pfeilgröße relativ zur Buttonhöhe
            int arrowHeight = (int) (getHeight() * 0.4);   // Höhe der Spitze
            int arrowLength = (int) (getHeight() * 0.5);    // Länge des Schafts
            int arrowPadding = 10;                           // fester Abstand

            int xStart = arrowPadding;
            int xEnd   = arrowPadding + arrowLength;

            // Horizontale Linie (Schaft)
            newGraphics2d.drawLine(xStart, centerY, xEnd, centerY);

            // Spitze spitzer gestalten
            int yTop    = centerY - arrowHeight / 3;   // Spitze weniger hoch
            int yBottom = centerY + arrowHeight / 3;
            int tipX    = xStart + arrowHeight / 3;    // Spitze näher zur linken Kante

            newGraphics2d.drawLine(xStart, centerY, tipX, yTop);
            newGraphics2d.drawLine(xStart, centerY, tipX, yBottom);

            // Text-Abstand nach rechts verschieben
            int leftInset = xEnd + arrowPadding; 
            setMargin(new Insets(0, leftInset, 0, 0));
        }



        newGraphics2d.dispose();
        super.paintComponent(graphics);
    }

    public void setButtonLabel(String label)
    {
        this.label = label;
    }
    public String getButtonLabel()
    {
        return this.label;
    }

    @Override
    public String toString()
    {
        return "Button{" + System.lineSeparator() +
            "  arcRadius       = " + ARCRADIUS + System.lineSeparator() +
            ", minimumSize     = " + (MINIMUM_SIZE != null ? MINIMUM_SIZE.toString() : "null") + System.lineSeparator() +
            ", preferredSize   = " + (PREFERRED_SIZE != null ? PREFERRED_SIZE.toString() : "null") + System.lineSeparator() +
            ", maximumSize     = " + (MAXIMUM_SIZE != null ? MAXIMUM_SIZE.toString() : "null") + System.lineSeparator() +
            ", foregroundColor = " + (FOREGROUND_COLOR != null ? FOREGROUND_COLOR.toString() : "null") + System.lineSeparator() +
            ", backgroundColor = " + (BACKGROUND_COLOR != null ? BACKGROUND_COLOR.toString() : "null") + System.lineSeparator() +
            ", border          = " + (BORDER != null ? BORDER.getClass().getSimpleName() : "null") + System.lineSeparator() +
            ", font            = " + (FONT != null ? FONT.getClass().getSimpleName() : "null") + System.lineSeparator() +
            ", ButtonLabel     = " + (label != null ? label : "null") + System.lineSeparator() +
            '}';
    }

    public static class Builder
    {
        private int arcRadius;
        private Dimension minimumSize, preferredSize, maximumSize;
        private Color forgroundColor, backgroundColor, arrowColor;
        private Border border;
        private Font font;
        private boolean arrowButton;
        private float alignmentX, alignmentY;
        private String label;
        private ActionListener actionListener;

        public Builder withRoundedCorners(int arc)  // mit KI
        {
            this.arcRadius = arc;
            return this;
        }

        public Builder withRoundedBackground(Color color, int arc) // mit KI
        {
            this.backgroundColor = color;
            this.arcRadius = arc;
            return this;
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

        public Builder maximumSize(Dimension maxiumSize)
        {
            this.maximumSize = maxiumSize;
            return this;
        }

        public Builder foregroundColor(Color foregroundColor)
        {
            this.forgroundColor = foregroundColor;
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

        public Builder font(Font font)
        {
            this.font = font;
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

        public Builder arrowButton(boolean arrowButton)
        {
            this.arrowButton = arrowButton;
            return this;
        }

        public Builder arrowButton(boolean arrowButton, Color arrowColor)
        {
            this.arrowButton = arrowButton;
            this.arrowColor = arrowColor;
            return this;
        }

        public Builder label(String label)
        {
            this.label = label;
            return this;
        }

        public Builder addActionListener(ActionListener actionListener)
        {
            this.actionListener = actionListener;
            return this;
        }

        public Button build()
        {
            return new Button(this);
        }
    }
}
