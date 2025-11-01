package frontend.server;

import java.awt.*;

/** Centralized color and font palette for the server frontend. */
public final class ServerTheme {
    private ServerTheme() {}

    public static final Color WINDOW_BACKGROUND = new Color(16, 24, 32);
    public static final Color CANVAS_BACKGROUND = new Color(11, 18, 25);
    public static final Color CARD_BACKGROUND = new Color(31, 38, 45);
    public static final Color CARD_BORDER = new Color(41, 52, 62);
    public static final Color ACCENT = new Color(0, 168, 132);
    public static final Color ACCENT_DARK = new Color(0, 143, 112);
    public static final Color TEXT_PRIMARY = new Color(236, 244, 247);
    public static final Color TEXT_SECONDARY = new Color(173, 185, 199);
    public static final Color PLACEHOLDER = new Color(112, 125, 138);

    public static final Font FONT_HEADING = new Font("Segoe UI", Font.BOLD, 18);
    public static final Font FONT_LABEL = new Font("Segoe UI", Font.BOLD, 16);
    public static final Font FONT_BODY = new Font("Segoe UI", Font.PLAIN, 15);
    public static final Font FONT_BUTTON = new Font("Segoe UI Semibold", Font.PLAIN, 16);

    public static final int CARD_CORNER_RADIUS = 22;
    public static final int INPUT_CORNER_RADIUS = 14;

    /** Utility to apply rendering hints for smoother UI. */
    public static void enableQualityRendering(Graphics2D g2) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }
}
