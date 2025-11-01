package frontend.client;

import javax.swing.SwingUtilities;

/**
 * Quick launcher used by {@code quickClient.bat}. It simply boots the Swing UI
 * with the provided demo bridge. Swap the bridge instance for the real client
 * implementation once it is available.
 */
public final class ClientMain {

    private ClientMain() {
        // utility class
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            UI ui = new UI(new NetworkClientBridge());
            ui.setVisible(true);
        });
    }
}
