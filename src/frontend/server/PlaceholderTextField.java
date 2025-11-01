package frontend.server;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;

/** JTextField with placeholder support and accent styling. */
class PlaceholderTextField extends JTextField {
    private String placeholder;

    PlaceholderTextField(String placeholder) {
        super();
        this.placeholder = placeholder;
        setOpaque(false);
        setForeground(ServerTheme.TEXT_PRIMARY);
        setCaretColor(ServerTheme.TEXT_PRIMARY);
        setFont(ServerTheme.FONT_BODY);
        setBorder(new LineBorder(ServerTheme.ACCENT, 2, true));
        setMargin(new Insets(10, 14, 10, 14));
        setBackground(new Color(0, 0, 0, 0));
        setSelectionColor(new Color(0, 168, 132, 90));
        setSelectedTextColor(ServerTheme.TEXT_PRIMARY);
        setColumns(16);
        putClientProperty("JComponent.sizeVariant", "regular");
    }

    public void setPlaceholder(String placeholder) {
        this.placeholder = placeholder;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        ServerTheme.enableQualityRendering(g2);

        g2.setColor(new Color(16, 24, 32, 220));
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), ServerTheme.INPUT_CORNER_RADIUS, ServerTheme.INPUT_CORNER_RADIUS);

        super.paintComponent(g);

        if ((getText() == null || getText().isEmpty()) && !isFocusOwner() && placeholder != null) {
            g2.setColor(ServerTheme.PLACEHOLDER);
            g2.setFont(getFont().deriveFont(Font.ITALIC));
            Insets ins = getInsets();
            g2.drawString(placeholder, ins.left, g.getFontMetrics().getAscent() + ins.top);
        }

        g2.dispose();
    }

    @Override
    public void setBorder(Border border) {
        if (border instanceof EmptyBorder || border instanceof LineBorder) {
            super.setBorder(border);
        } else {
            setBorder(new EmptyBorder(10, 14, 10, 14));
        }
    }
}
