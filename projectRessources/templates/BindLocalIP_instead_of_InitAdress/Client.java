import java.io.*;
import java.net.*;

public class Client {

    private final String serverIp;
    private final int port;
    private final String name;

    public Client(String serverIp, int port, String name) {
        this.serverIp = serverIp;
        this.port = port;
        this.name = name;
    }

    public void run() throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(serverIp, port), 5000);
            System.out.println("[CLIENT] Connected from local IP: " +
                    socket.getLocalAddress().getHostAddress());

            try (DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                 DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {

                // AUTH
                Message.of(name, Message.Type.AUTH, "meinToken123").writeTo(out);
                out.flush();

                Message authReply = Message.readFrom(in);
                System.out.println("[CLIENT] AUTH Reply: " + authReply);
                if (authReply.getType() == Message.Type.AUTH_ERR) return;

                // MSG
                Message.of(name, Message.Type.MSG, "Hallo Server!").writeTo(out);
                out.flush();
                Message reply = Message.readFrom(in);
                System.out.println("[CLIENT] Reply: " + reply);

                // NEXT_TASK
                Message.of(name, Message.Type.NEXT_TASK).writeTo(out);
                out.flush();
                Message task = Message.readFrom(in);
                System.out.println("[CLIENT] Task: " + task);
            }
        }
    }

    public static void main(String[] args) throws IOException {
        new Client("192.168.137.221", 5050, "NEWCLIENT").run();
    }
}
