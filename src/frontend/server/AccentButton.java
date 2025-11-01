package frontend.server;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/** Flat rounded button with WhatsApp-inspired accent color. */
class AccentButton extends JButton {
    private Color background = ServerTheme.ACCENT;
    private Color hoverBackground = ServerTheme.ACCENT_DARK;
    private boolean hover;

    AccentButton(String text) {
        super(text);
        setFocusPainted(false);
        setBorderPainted(false);
        setContentAreaFilled(false);
        setOpaque(false);
        setForeground(ServerTheme.TEXT_PRIMARY);
        setFont(ServerTheme.FONT_BUTTON);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setMargin(new Insets(10, 20, 10, 20));

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                hover = true;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hover = false;
                repaint();
            }
        });
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        ServerTheme.enableQualityRendering(g2);
        g2.setColor(hover ? hoverBackground : background);
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), ServerTheme.INPUT_CORNER_RADIUS, ServerTheme.INPUT_CORNER_RADIUS);
        super.paintComponent(g);
        g2.dispose();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        setForeground(ServerTheme.TEXT_PRIMARY);
    }
}
