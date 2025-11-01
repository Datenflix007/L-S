package frontend.client;
import java.awt.*;
import java.awt.geom.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.*;

public class TextField extends JTextField 
{
    private final int ARCRADIUS;
    private final Dimension MINIMUM_SIZE, PREFERRED_SIZE, MAXIMUM_SIZE;
    private final Color FOREGROUND_COLOR, BACKGROUND_COLOR;
    private final Border BORDER;
    private final Font FONT;
    private final String PLACEHOLDER;
    private final boolean FLOATING_PLACEHOLDER;
    private final Color BORDER_COLOR, PLACEHOLDER_COLOR;
    private final float ALIGNMENT_X, ALIGNMENT_Y;

    // Border/Stroke settings (kann über Builder eingestellt werden)
    private int borderInset;
    private float strokeWidth;

    // Animation
    private float animationProgress = 0f; // 0 = unten, 1 = oben
    private Timer animationTimer;

    public TextField(Builder builder) 
    {
        this.ARCRADIUS = builder.arcRadius;
        this.MINIMUM_SIZE = builder.minimumSize;
        this.PREFERRED_SIZE = builder.preferredSize;
        this.MAXIMUM_SIZE = builder.maximumSize;
        this.FOREGROUND_COLOR = builder.forgroundColor;
        this.BACKGROUND_COLOR = builder.backgroundColor;
        this.BORDER = builder.border;
        this.FONT = builder.font;
        this.PLACEHOLDER = builder.placeholder;
        this.PLACEHOLDER_COLOR = builder.placeholderColor;
        this.FLOATING_PLACEHOLDER = builder.floatingPlaceholder;
        this.BORDER_COLOR = builder.borderColor;
        this.ALIGNMENT_X = builder.alignmentX;
        this.ALIGNMENT_Y = builder.alignmentY;
        this.animationProgress = 0f;

        this.borderInset = builder.borderInset;
        this.strokeWidth = builder.strokeWidth;

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
        if (ALIGNMENT_X == 0.0f || ALIGNMENT_X == 0.5f || ALIGNMENT_X == 1.0f)
            super.setAlignmentX(ALIGNMENT_X);
        if (ALIGNMENT_Y == 0.0f || ALIGNMENT_Y == 0.5f || ALIGNMENT_Y == 1.0f)
            super.setAlignmentY(ALIGNMENT_Y);

        // Wir zeichnen eigene Border
        super.setBorder(null);
        super.setOpaque(false);

        // initial margin/insets so setzen, dass Caret/Text innerhalb der gezeichneten Border landet
        updateMarginsFromInset();

        animationProgress = (getText() != null && !getText().isEmpty()) ? 1f : 0f;

        this.addFocusListener(new FocusAdapter() 
        {
            @Override
            public void focusGained(FocusEvent e) 
            {
                startAnimation(true);
            }

            @Override
            public void focusLost(FocusEvent e) 
            {
                if (getText().isEmpty()) 
                {
                    startAnimation(false);
                }
            }
        });

        getDocument().addDocumentListener(new DocumentListener() 
        {
            public void insertUpdate(DocumentEvent e) 
            {
                startAnimation(true);
            }

            public void removeUpdate(DocumentEvent e) 
            {
                if (getText().isEmpty() && !isFocusOwner()) 
                {
                    startAnimation(false);
                }
            }

            public void changedUpdate(DocumentEvent e) {}
        });

        // Mouse handling: wenn Benutzer außerhalb der inneren Border klickt,
        // setze Caret auf die nächstgelegene Position innerhalb der inneren Fläche
        addMouseListener(new MouseAdapter() 
        {
            @Override
            public void mousePressed(MouseEvent e) 
            {
                Point p = e.getPoint();
                Shape inner = getInnerClipShape();
                if (inner == null) 
                {
                    return;
                }

                if (!inner.contains(p)) 
                {
                    Rectangle2D bounds = inner.getBounds2D();
                    double nx = Math.max(bounds.getX(), Math.min(p.getX(), bounds.getX() + bounds.getWidth() - 1));
                    double ny = Math.max(bounds.getY(), Math.min(p.getY(), bounds.getY() + bounds.getHeight() - 1));

                    if (!inner.contains(nx, ny)) 
                    {
                        double cx = bounds.getCenterX();
                        double cy = bounds.getCenterY();
                        double t = 0.0;
                        boolean found = false;
                        for (int i = 0; i < 10 && !found; i++) 
                        {
                            t += 0.1;
                            double sx = nx + (cx - nx) * t;
                            double sy = ny + (cy - ny) * t;
                            if (inner.contains(sx, sy)) {
                                nx = sx; ny = sy; found = true;
                            }
                        }
                        if (!found) 
                        {
                            nx = cx; ny = cy;
                        }
                    }

                    int pos = viewToModel(new Point((int)Math.round(nx), (int)Math.round(ny)));
                    if (pos >= 0 && pos <= getDocument().getLength()) 
                    {
                        setCaretPosition(pos);
                        requestFocusInWindow();
                        e.consume();
                    }
                } 
                else 
                {
                    int pos = viewToModel(p);
                    if (pos >= 0) 
                    {
                        setCaretPosition(pos);
                    }
                }
            }
        });
    }

    private void updateMarginsFromInset() 
    {
        int strokeInset = (int) Math.ceil(strokeWidth / 2f);

        FontMetrics fm = getFontMetrics(getFont());
        int ascent = fm.getAscent();
        int descent = fm.getDescent();

        // Gesamthöhe der Schrift
        int fontHeight = ascent + descent;

        // Vertikal: wir wollen den Text mittig in der "inneren Fläche"
        // -> also obere und untere Margin so berechnen, dass Text baseline mittig sitzt
        int innerHeight = getHeight() > 0 
            ? getHeight() - 2 * (borderInset + strokeInset) 
            : fontHeight; // fallback, falls Komponente noch nicht angezeigt

        int topMargin    = Math.max(2, (innerHeight - fontHeight) / 2 + borderInset + strokeInset);
        int bottomMargin = topMargin;

        // Horizontal: Border + Stroke + kleines Padding
        int leftMargin  = borderInset + strokeInset + 6;
        int rightMargin = borderInset + strokeInset + 6;

        super.setMargin(new Insets(topMargin, leftMargin, bottomMargin, rightMargin));
        revalidate();
        repaint();
    }

    private void startAnimation(boolean toTop) 
    {
        if (!FLOATING_PLACEHOLDER) 
            return;
        if (animationTimer != null && animationTimer.isRunning()) 
        {
            animationTimer.stop();
        }
        animationTimer = new Timer(15, e -> 
        {
            if (toTop) 
            {
                animationProgress = Math.min(1f, animationProgress + 0.1f);
                if (animationProgress >= 1f) 
                    animationTimer.stop();
            } 
            else 
            {
                animationProgress = Math.max(0f, animationProgress - 0.1f);
                if (animationProgress <= 0f) animationTimer.stop();
            }
            repaint();
        });
        animationTimer.start();
    }

    // Liefert die innere Shape (wo Text/Caret sichtbar sein dürfen)
    private Shape getInnerClipShape() 
    {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) 
            return null;
        float shrink = strokeWidth / 2f + 0.5f;
        double x = borderInset + shrink;
        double y = borderInset + shrink;
        double ww = Math.max(0, w - 2 * (borderInset + shrink));
        double hh = Math.max(0, h - 2 * (borderInset + shrink));
        return new RoundRectangle2D.Double(x, y, ww, hh, ARCRADIUS, ARCRADIUS);
    }

    @Override
    protected void paintComponent(Graphics graphics) 
    {
        Graphics2D g2 = (Graphics2D) graphics.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();

        // Hintergrund (volle Fläche, gerundet)
        Shape bgRect = new RoundRectangle2D.Double(0, 0, w, h, ARCRADIUS, ARCRADIUS);
        g2.setColor(BACKGROUND_COLOR);
        g2.fill(bgRect);

        // Border-Rect ist vom Rand nach innen verschoben (borderInset)
        Shape borderRect = new RoundRectangle2D.Double(
            borderInset, borderInset,
            Math.max(0, w - 2 * borderInset), Math.max(0, h - 2 * borderInset),
            ARCRADIUS, ARCRADIUS
        );
        if (BORDER_COLOR != null) // LightMode noch einfügen
            g2.setColor(BORDER_COLOR);
        else
            g2.setColor(Color.GRAY);
        g2.setStroke(new BasicStroke(strokeWidth));
        // Zeichne die komplette Border initial (durchgängig)
        g2.draw(borderRect);

        // Entscheiden, ob wir eine Lücke zeichnen – nur wenn Placeholder hochrutscht (animationProgress > 0)
        boolean shouldDrawGap = FLOATING_PLACEHOLDER && (animationProgress > 0f || (getText() != null && !getText().isEmpty()));

        if (PLACEHOLDER != null && shouldDrawGap) 
        {
            // Kleine Font für das Label (Endzustand)
            float baseFontSize = getFont().getSize2D();
            float smallFontSize = baseFontSize * 0.8f;
            Font smallFont = getFont().deriveFont(Font.ITALIC, smallFontSize);
            FontMetrics fm = g2.getFontMetrics(smallFont);

            int labelWidth = fm.stringWidth(PLACEHOLDER);
            int gapPadding = 8;
            int fullGapWidth = labelWidth + gapPadding;
            int gapHeight = fm.getAscent() + fm.getDescent();

            // Lückenbreite mit animationProgress skalieren (erscheint flüssiger beim Hochrutschen)
            int gapWidth = Math.max(1, Math.round(fullGapWidth * animationProgress));

            float borderTop = borderInset;
            float borderCenterY = borderTop + strokeWidth / 2f;
            int endBaseline = Math.round(borderCenterY + (fm.getAscent() / 2f) - (fm.getDescent() / 2f));

            int gapX = borderInset + 4;
            int gapY = endBaseline - fm.getAscent() - 2;

            // Falls die Lücke kleiner wird, stellen wir sicher, dass sie mittig über dem finalen X bleibt:
            int centeredGapX = gapX + (fullGapWidth - gapWidth) / 2;

            Shape gapRect = new RoundRectangle2D.Double(centeredGapX, gapY, gapWidth, gapHeight + 4, 6, 6);
            g2.setColor(BACKGROUND_COLOR);
            g2.fill(gapRect);
            // Optional: leicht nachzeichnen, aber das Übermalen reicht.
        }

        // Clip setzen, damit alles weitere (Text/Caret) innerhalb der inneren Shape bleibt
        Shape innerClip = getInnerClipShape();
        if (innerClip != null) 
        {
            Graphics2D gClip = (Graphics2D) graphics.create();
            gClip.setClip(innerClip);
            super.paintComponent(gClip);
            gClip.dispose();
        } 
        else 
        {
            super.paintComponent(graphics);
        }

        g2.dispose();
    }

    // === Placeholder weiterhin oberhalb zeichnen (nach Text/Border), damit er sichtbar ist ===
    @Override
    public void paint(Graphics graphics) 
    {
        // Erst Standard-Malroutine (inkl. paintComponent)
        super.paint(graphics);

        // Placeholder nur, wenn gesetzt und Floating aktiv
        if (PLACEHOLDER == null || !FLOATING_PLACEHOLDER) 
            return;

        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        Insets insets = getInsets(); // enthält die Margin, also Caret/Text-Start

        // Start-Position (im Feld, mittig vertikal)
        Font baseFont = getFont();
        FontMetrics baseFm = g.getFontMetrics(baseFont);
        int startY = getHeight() / 2 + baseFm.getAscent() / 2 - 4;

        // End-Position (oben links, zentriert auf der Border-Mitte)
        float baseFontSize = baseFont.getSize2D();
        float smallFontSize = baseFontSize * 0.8f;
        Font smallFont = baseFont.deriveFont(Font.ITALIC, smallFontSize);
        FontMetrics smallFm = g.getFontMetrics(smallFont);

        // borderTop und borderCenterY müssen identisch zu paintComponent() sein:
        float borderTop = borderInset;
        float borderCenterY = borderTop + strokeWidth / 2f;

        // Ziel-Baseline: baseline = borderCenterY + ascent/2 - descent/2
        int endBaseline = Math.round(borderCenterY + (smallFm.getAscent() / 2f) - (smallFm.getDescent() / 2f));

        int endY = endBaseline;

        // EndX so setzen, dass es mit der inneren Border und Margin übereinstimmt:
        int endX = borderInset + 8; // sitzt innerhalb der Lücke (leicht eingerückt)

        // StartX (Placeholder im Feld) leicht rechts von Margin, damit er schön hineinrutscht
        int startX = insets.left + 6;

        // Interpolierte Position
        int x = (int) (startX + (endX - startX) * animationProgress);
        int y = (int) (startY - (startY - endY) * animationProgress);

        // Schriftgröße interpolieren
        float size = baseFontSize - (baseFontSize - smallFontSize) * animationProgress;
        g.setFont(baseFont.deriveFont(Font.ITALIC, size));
        if (PLACEHOLDER_COLOR != null)
            g.setColor(PLACEHOLDER_COLOR);
        else
            g.setColor(Color.GRAY);

        // Wenn Text vorhanden, ensure animationProgress is 1 (handled by listeners). Still draw small label.
        if (getText() != null && !getText().isEmpty()) 
        {
            x = endX;
            y = endY;
            g.setFont(smallFont);
        }

        g.drawString(PLACEHOLDER, x, y);

        g.dispose();
    }

    @Override
public Insets getInsets() 
{
    int strokeInset = (int) Math.ceil(strokeWidth / 2f);
    int left = borderInset + strokeInset + 6;
    int right = borderInset + strokeInset + 6;

    FontMetrics fm = getFontMetrics(getFont());
    int ascent = fm.getAscent();
    int descent = fm.getDescent();
    int fontHeight = ascent + descent;

    int innerHeight = getHeight() > 0 
        ? getHeight() - 2 * (borderInset + strokeInset) 
        : fontHeight;

    int top = Math.max(2, (innerHeight - fontHeight) / 2 + borderInset + strokeInset);
    int bottom = top;

    return new Insets(top, left, bottom, right);
}

    // Setter, damit man borderInset / strokeWidth später ändern kann und Caret-Position automatisch angepasst wird
    public void setBorderInset(int inset) 
    {
        this.borderInset = Math.max(0, inset);
        updateMarginsFromInset();
        repaint();
    }

    public int getBorderInset() 
    {
        return this.borderInset;
    }

    public void setStrokeWidth(float width) 
    {
        this.strokeWidth = Math.max(0.5f, width);
        repaint();
    }

    public float getStrokeWidth() 
    {
        return this.strokeWidth;
    }

    // toString bleibt hilfreich zur Debug-Ausgabe
    @Override
    public String toString() 
    {
        return "TextField{" + System.lineSeparator() +
            "  arcRadius       = " + ARCRADIUS + System.lineSeparator() +
            ", minimumSize     = " + (MINIMUM_SIZE != null ? MINIMUM_SIZE.toString() : "null") + System.lineSeparator() +
            ", preferredSize   = " + (PREFERRED_SIZE != null ? PREFERRED_SIZE.toString() : "null") + System.lineSeparator() +
            ", maximumSize     = " + (MAXIMUM_SIZE != null ? MAXIMUM_SIZE.toString() : "null") + System.lineSeparator() +
            ", foregroundColor = " + (FOREGROUND_COLOR != null ? FOREGROUND_COLOR.toString() : "null") + System.lineSeparator() +
            ", backgroundColor = " + (BACKGROUND_COLOR != null ? BACKGROUND_COLOR.toString() : "null") + System.lineSeparator() +
            ", borderInset     = " + borderInset + System.lineSeparator() +
            ", strokeWidth     = " + strokeWidth + System.lineSeparator() +
            ", Placeholder     = " + (PLACEHOLDER != null ? PLACEHOLDER : "null") + System.lineSeparator() +
            '}';
    }

    // Builder (vereinfachte Defaults)
    public static class Builder 
    {
        private int arcRadius = 12;
        private Dimension minimumSize, preferredSize, maximumSize;
        private Color forgroundColor = Color.BLACK;
        private Color backgroundColor = Color.WHITE;
        private Border border;
        private Font font = new Font("Arial", Font.PLAIN, 14);
        private String placeholder;
        private boolean floatingPlaceholder = true;
        private Color borderColor, placeholderColor;
        private float alignmentX, alignmentY;

        private int borderInset = 6;
        private float strokeWidth = 1.2f;

        public Builder withRoundedCorners(int arc) 
        { 
            this.arcRadius = arc; return this; 
        }

        public Builder withRoundedBackground(Color color, int arc) 
        { 
            this.backgroundColor = color; this.arcRadius = arc; return this; 
        }

        public Builder withPlaceholder(String placeholder) 
        { 
            this.placeholder = placeholder; return this; 
        }
        public Builder floatingPlaceholder(boolean floating) 
        { 
            this.floatingPlaceholder = floating; return this; 
        }

        public Builder minimumSize(Dimension minimumSize) 
        { 
            this.minimumSize = minimumSize; return this; 
        }

        public Builder preferredSize(Dimension preferredSize) 
        { 
            this.preferredSize = preferredSize; return this; 
        }
        public Builder maximumSize(Dimension maxiumSize) 
        { 
            this.maximumSize = maxiumSize; return this; 
        }

        public Builder foregroundColor(Color foregroundColor) 
        { 
            this.forgroundColor = foregroundColor; return this; 
        }

        public Builder backgroundColor(Color backgroundColor) 
        { 
            this.backgroundColor = backgroundColor; return this; 
        }

        public Builder border(Border border) 
        { 
            this.border = border; return this; 
        }

        public Builder font(Font font) 
        { 
            this.font = font; return this; 
        }

        public Builder borderColor(Color borderColor)
        {
            this.borderColor = borderColor;
            return this;
        }

        public Builder placeholderColor(Color placeholderColor)
        {
            this.placeholderColor = placeholderColor;
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

        public Builder borderInset(int inset) 
        { 
            this.borderInset = inset; return this; 
        }

        public Builder strokeWidth(float width) 
        { 
            this.strokeWidth = width; return this; 
        }

        public TextField build() 
        {
            return new TextField(this);
        }
    }
}

