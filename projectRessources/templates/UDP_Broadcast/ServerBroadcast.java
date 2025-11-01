// ServerBroadcast.java
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class ServerBroadcast {
    public static void main(String[] args) throws Exception {
        DatagramSocket socket = new DatagramSocket();
        socket.setBroadcast(true);

        String message = "MyServer:12345"; // z.B. Servername und Port
        byte[] buffer = message.getBytes();

        while (true) {
            DatagramPacket packet = new DatagramPacket(
                buffer, buffer.length,
                InetAddress.getByName("255.255.255.255"), 8888
            );
            socket.send(packet);
            System.out.println("Broadcast sent.");
            Thread.sleep(3000); // alle 3 Sekunden senden
        }
    }
}
