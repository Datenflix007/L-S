// ClientDiscovery.java
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.HashSet;
import javax.swing.*;

public class ClientDiscovery {
    public static void main(String[] args) throws Exception {
        DatagramSocket socket = new DatagramSocket(8888);
        byte[] buffer = new byte[1024];
        HashSet<String> discoveredServers = new HashSet<>();

        JFrame frame = new JFrame("Serverauswahl");
        JPanel panel = new JPanel();

        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(400, 200);
        frame.add(panel);
        frame.setVisible(true);

        while (true) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);

            String msg = new String(packet.getData(), 0, packet.getLength());
            if (!discoveredServers.contains(msg)) {
                discoveredServers.add(msg);

                JButton button = new JButton(msg);
                button.addActionListener(e -> {
                    // Hier z. B. Verbindung zum Server starten
                    System.out.println("Verbinde mit: " + msg);
                });

                panel.add(button);
                panel.revalidate();
                panel.repaint();
            }
        }
    }
}
