package frontend.server;

import javax.swing.*;
import java.awt.*;

/** Panel with customizable rounded corners and optional shadow effect. */
class RoundedPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    private static final int SHADOW_INSET_X = 3;
    private static final int SHADOW_INSET_Y = 5;
    private static final int CONTENT_INSET = 6;
    private static final Color SHADOW_COLOR = new Color(0, 0, 0, 55);


    private int cornerRadius = ServerTheme.CARD_CORNER_RADIUS;
    private Color panelBackground = ServerTheme.CARD_BACKGROUND;

    RoundedPanel() {
        setOpaque(false);
    }

    public void setCornerRadius(int radius) {
        this.cornerRadius = Math.max(0, radius);
        repaint();
    }

    public void setPanelBackground(Color background) {
        this.panelBackground = background == null ? ServerTheme.CARD_BACKGROUND : background;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        ServerTheme.enableQualityRendering(g2);

        int width = getWidth();
        int height = getHeight();
        int contentWidth = Math.max(0, width - CONTENT_INSET);
        int contentHeight = Math.max(0, height - CONTENT_INSET);

        g2.setColor(SHADOW_COLOR);
        g2.fillRoundRect(SHADOW_INSET_X, SHADOW_INSET_Y, contentWidth, contentHeight, cornerRadius + 4, cornerRadius + 4);

        g2.setColor(panelBackground);
        g2.fillRoundRect(0, 0, contentWidth, contentHeight, cornerRadius, cornerRadius);

        g2.setColor(ServerTheme.CARD_BORDER);
        g2.setStroke(new BasicStroke(1.5f));
        g2.drawRoundRect(0, 0, contentWidth, contentHeight, cornerRadius, cornerRadius);

        g2.dispose();
        super.paintComponent(g);
    }
}





