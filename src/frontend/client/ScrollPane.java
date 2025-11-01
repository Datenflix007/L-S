package frontend.client;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.table.JTableHeader;

/**
 * ScrollPane mit rundem Viewport + custom ScrollBarUI
 */
public class ScrollPane extends JScrollPane {
    private final int ARCRADIUS;
    private final Border BORDER;

    private ScrollPane(Builder builder) {
        super();

        ARCRADIUS = builder.arcRadius;
        BORDER = builder.border;

        if (BORDER != null) {
            super.setBorder(BORDER);
        }

        setOpaque(false);

        // Gerundeter Viewport
        RoundedViewport roundedViewport = new RoundedViewport(ARCRADIUS);
        roundedViewport.setBackground(getBackground());
        setViewport(roundedViewport);

        // View setzen
        if (builder.view != null) {
            JComponent view = builder.view;

            if (view instanceof JTable) {
                JTable table = (JTable) view;
                table.setOpaque(false);
                table.setFillsViewportHeight(true);

                JTableHeader header = table.getTableHeader();
                if (header != null) {
                    header.setOpaque(false);
                    setColumnHeaderView(header); // Header als ColumnHeader setzen
                }
            } else {
                view.setOpaque(false);
            }

            setViewportView(view);
        }

        // ---- WICHTIG: Corner-Komponenten und ColumnHeader-Viewport transparent machen ----
        makeCornersTransparent();
        makeColumnHeaderViewportTransparent();

        // Installiere die benutzerdefinierte ScrollBarUI auf den Scrollbars
        installCustomScrollbars();

        // Forciertes Initial-Repaint (sorgt dafür, dass alles sofort korrekt gezeichnet wird)
        revalidate();
        repaint();
    }

    // Macht alle Ecke-Komponenten des JScrollPane transparent / ersetzt sie durch transparente Panels
    private void makeCornersTransparent() {
        setTransparentCorner(LOWER_LEFT_CORNER);
        setTransparentCorner(LOWER_RIGHT_CORNER);
        setTransparentCorner(UPPER_LEFT_CORNER);
        setTransparentCorner(UPPER_RIGHT_CORNER);
    }

    private void setTransparentCorner(String key) {
        Component c = getCorner(key);
        if (c == null) {
            // falls keine Ecke gesetzt ist, setze eine transparente Komponente
            setCorner(key, createTransparentPanel());
        } else if (c instanceof JComponent) {
            ((JComponent) c).setOpaque(false);
        } else {
            // sonst ersetzen durch transparente Komponente (sicherer)
            setCorner(key, createTransparentPanel());
        }
    }

    private JComponent createTransparentPanel() {
        JPanel p = new JPanel(null) {
            @Override
            protected void paintComponent(Graphics g) {
                // nichts malen -> transparent
            }
        };
        p.setOpaque(false);
        return p;
    }

    // ColumnHeader-Viewport transparent machen (so dass der Header keine eckige Fläche malt)
    private void makeColumnHeaderViewportTransparent() {
        JViewport colHeader = getColumnHeader();
        if (colHeader != null) {
            colHeader.setOpaque(false);
            Component view = colHeader.getView();
            if (view instanceof JComponent) {
                ((JComponent) view).setOpaque(false);
            }
        }
    }

    @Override
    public void paint(Graphics g) {
        if (ARCRADIUS > 0) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);

                Shape round = new RoundRectangle2D.Double(
                        0, 0, getWidth(), getHeight(), ARCRADIUS, ARCRADIUS
                );

                g2.setClip(round);
                g2.setColor(getBackground());
                g2.fill(round);

                super.paint(g2);
            } finally {
                g2.dispose();
            }
        } else {
            super.paint(g);
        }
    }

    @Override
    public void addNotify() {
        super.addNotify();

        // Manche LAFs setzen Corner-Komponenten beim Hinzufügen zur UI — nochmal überschreiben
        SwingUtilities.invokeLater(() -> {
            // Alle vier Ecken sicher transparent setzen / ersetzen
            setCorner(LOWER_LEFT_CORNER, ensureTransparentCorner(getCorner(LOWER_LEFT_CORNER)));
            setCorner(LOWER_RIGHT_CORNER, ensureTransparentCorner(getCorner(LOWER_RIGHT_CORNER)));
            setCorner(UPPER_LEFT_CORNER, ensureTransparentCorner(getCorner(UPPER_LEFT_CORNER)));
            setCorner(UPPER_RIGHT_CORNER, ensureTransparentCorner(getCorner(UPPER_RIGHT_CORNER)));

            // Column header viewport transparent halten
            JViewport ch = getColumnHeader();
            if (ch != null) {
                ch.setOpaque(false);
                Component hv = ch.getView();
                if (hv instanceof JComponent) ((JComponent) hv).setOpaque(false);
            }

            // Viewport ebenfalls nicht opaque (Doppelsicherung)
            if (getViewport() != null) getViewport().setOpaque(false);

            // ensure custom scrollbars are installed (some LAFs recreate scrollbars on addNotify)
            installCustomScrollbars();

            // abschließendes Repaint erzwingen
            revalidate();
            repaint();
        });
    }

    /** Hilfsmethode: vorhandene Ecke verwenden (setzt opaque=false), oder eine transparente ersetzen */
    private Component ensureTransparentCorner(Component c) {
        if (c == null) {
            JPanel p = new JPanel(null) {
                @Override
                protected void paintComponent(Graphics g) { /* transparent */ }
            };
            p.setOpaque(false);
            return p;
        }
        if (c instanceof JComponent) {
            ((JComponent) c).setOpaque(false);
            return c;
        }
        // sonst ersetzen
        JPanel p = new JPanel(null) {
            @Override
            protected void paintComponent(Graphics g) { /* transparent */ }
        };
        p.setOpaque(false);
        return p;
    }

    private static class RoundedViewport extends JViewport {
        private final int arcRadius;

        RoundedViewport(int arcRadius) {
            this.arcRadius = arcRadius;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (arcRadius > 0) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
                    Shape round = new RoundRectangle2D.Double(
                            0, 0, getWidth(), getHeight(), arcRadius, arcRadius
                    );

                    g2.setClip(round);
                    g2.setColor(getBackground());
                    g2.fill(round);

                    super.paintComponent(g2);
                } finally {
                    g2.dispose();
                }
            } else {
                super.paintComponent(g);
            }
        }
    }

    // ================= Benutzerdefinierte ScrollBarUI =================
    private static class CustomScrollBarUI extends BasicScrollBarUI {
        private final Color TRACK_DARK = new Color(27, 31, 31);
        private final Color TRACK_EDGE = new Color(40, 42, 43);
        private final Color TRACK_CENTER_TOP = new Color(255, 255, 255, 18);
        private final Color TRACK_CENTER_BOTTOM = new Color(0, 0, 0, 24);

        private final Color THUMB_TOP = new Color(220, 220, 220);
        private final Color THUMB_BOTTOM = new Color(190, 190, 190);
        private final Color THUMB_BORDER = new Color(150, 150, 150);

        private final int ARC = 8;
        private final int WIDTH = 10;
        private final int BUTTON_HEIGHT = 16;
        private final int THUMB_WIDTH = 6;

        @Override
        protected JButton createDecreaseButton(int orientation) {
            return new ArrowButton(SwingConstants.NORTH);
        }

        @Override
        protected JButton createIncreaseButton(int orientation) {
            return new ArrowButton(SwingConstants.SOUTH);
        }

        private class ArrowButton extends JButton {
            private final int direction;
            private final float cornerRadius = 2f;

            ArrowButton(int direction) {
                this.direction = direction;
                setOpaque(false);
                setFocusable(false);
                setBorder(null);
                setPreferredSize(new Dimension(WIDTH, BUTTON_HEIGHT));
                setRequestFocusEnabled(false);
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                int w = getWidth();
                int h = getHeight();

                RoundRectangle2D bg = new RoundRectangle2D.Float(0, 0, w, h, 6, 6);
                g2.setColor(TRACK_EDGE);
                g2.fill(bg);

                int size = Math.min(10, Math.max(6, h - 8));
                int cx = w / 2;
                int cy = h / 2;

                Point2D.Double p0, p1, p2;
                if (direction == SwingConstants.NORTH) {
                    p0 = new Point2D.Double(cx - size / 2.0, cy + size / 4.0);
                    p1 = new Point2D.Double(cx + size / 2.0, cy + size / 4.0);
                    p2 = new Point2D.Double(cx, cy - size / 2.0);
                } else {
                    p0 = new Point2D.Double(cx - size / 2.0, cy - size / 4.0);
                    p1 = new Point2D.Double(cx + size / 2.0, cy - size / 4.0);
                    p2 = new Point2D.Double(cx, cy + size / 2.0);
                }

                Path2D.Double path = createRoundedPolygon(
                        new Point2D.Double[]{p0, p1, p2}, cornerRadius
                );

                g2.setColor(new Color(230, 230, 230));
                g2.fill(path);

                g2.dispose();
            }
        }

        @Override
        protected void paintTrack(Graphics g, JComponent c, Rectangle trackBounds) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            g2.setColor(TRACK_DARK);
            g2.fillRect(trackBounds.x, trackBounds.y, trackBounds.width, trackBounds.height);

            g2.setColor(TRACK_EDGE);
            g2.fillRect(trackBounds.x, trackBounds.y, 1, trackBounds.height);
            g2.fillRect(trackBounds.x + trackBounds.width - 1, trackBounds.y, 1, trackBounds.height);

            int centerX = trackBounds.x + trackBounds.width / 2 - 1;
            GradientPaint centerGrad = new GradientPaint(centerX, trackBounds.y, TRACK_CENTER_TOP,
                    centerX, trackBounds.y + trackBounds.height, TRACK_CENTER_BOTTOM);
            g2.setPaint(centerGrad);
            g2.fillRect(centerX, trackBounds.y + 6, 2, trackBounds.height - 12);

            g2.dispose();
        }

        @Override
        protected void paintThumb(Graphics g, JComponent c, Rectangle thumbBounds) {
            if (thumbBounds == null || !c.isEnabled()) return;

            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int drawWidth = THUMB_WIDTH;
            int centerX = thumbBounds.x + thumbBounds.width / 2;
            int drawX = centerX - drawWidth / 2;
            int drawY = thumbBounds.y + 3;
            int drawHeight = Math.max(20, thumbBounds.height - 6);

            RoundRectangle2D shadow = new RoundRectangle2D.Float(drawX + 1, drawY + 1, drawWidth, drawHeight, ARC, ARC);
            g2.setColor(new Color(0, 0, 0, 120));
            g2.fill(shadow);

            GradientPaint gp = new GradientPaint(drawX, drawY, THUMB_TOP, drawX, drawY + drawHeight, THUMB_BOTTOM);
            RoundRectangle2D thumb = new RoundRectangle2D.Float(drawX, drawY, drawWidth, drawHeight, ARC, ARC);
            g2.setPaint(gp);
            g2.fill(thumb);

            g2.setColor(THUMB_BORDER);
            g2.setStroke(new BasicStroke(1f));
            g2.draw(thumb);

            g2.dispose();
        }

        @Override
        public Dimension getPreferredSize(JComponent c) {
            Dimension d = super.getPreferredSize(c);
            return new Dimension(WIDTH, d.height);
        }

        @Override
        protected Dimension getMinimumThumbSize() {
            return new Dimension(6, 24);
        }

        private Path2D.Double createRoundedPolygon(Point2D.Double[] pts, double cornerR) {
            int n = pts.length;
            Path2D.Double p = new Path2D.Double();
            if (n < 3) return p;

            for (int i = 0; i < n; ++i) {
                Point2D.Double prev = pts[(i - 1 + n) % n];
                Point2D.Double cur = pts[i];
                Point2D.Double next = pts[(i + 1) % n];

                double vx1 = prev.x - cur.x;
                double vy1 = prev.y - cur.y;
                double len1 = Math.hypot(vx1, vy1);
                if (len1 == 0) len1 = 1;

                double vx2 = next.x - cur.x;
                double vy2 = next.y - cur.y;
                double len2 = Math.hypot(vx2, vy2);
                if (len2 == 0) len2 = 1;

                double r1 = Math.min(cornerR, len1 * 0.5);
                double r2 = Math.min(cornerR, len2 * 0.5);

                double sx = cur.x + (vx1 / len1) * r1;
                double sy = cur.y + (vy1 / len1) * r1;
                double ex = cur.x + (vx2 / len2) * r2;
                double ey = cur.y + (vy2 / len2) * r2;

                if (i == 0) {
                    p.moveTo(sx, sy);
                } else {
                    p.lineTo(sx, sy);
                }

                p.quadTo(cur.x, cur.y, ex, ey);
            }

            p.closePath();
            return p;
        }
    }

    /**
     * Installiert die CustomScrollBarUI auf den vorhandenen Scrollbars (falls diese nicht null sind).
     * Wird sowohl im Konstruktor als auch in addNotify() aufgerufen (LAFs können Scrollbars neu erstellen).
     */
    private void installCustomScrollbars() {
        JScrollBar vsb = getVerticalScrollBar();
        if (vsb != null) {
            vsb.setUI(new CustomScrollBarUI());
            vsb.setPreferredSize(new Dimension(10, vsb.getPreferredSize().height));
            vsb.setUnitIncrement(16);
            vsb.setOpaque(false);
        }

        JScrollBar hsb = getHorizontalScrollBar();
        if (hsb != null) {
            hsb.setUI(new CustomScrollBarUI());
            hsb.setPreferredSize(new Dimension(hsb.getPreferredSize().width, 10));
            hsb.setUnitIncrement(16);
            hsb.setOpaque(false);
        }
    }

    public static class Builder {
        private int arcRadius;
        private JComponent view;
        private Border border;

        public Builder withRoundedCorners(int arcRadius) {
            this.arcRadius = arcRadius;
            return this;
        }

        public Builder withBorder(Border border) {
            this.border = border;
            return this;
        }

        public Builder addComponent(JComponent component) {
            this.view = component;
            return this;
        }

        public ScrollPane build() {
            return new ScrollPane(this);
        }
    }
}
