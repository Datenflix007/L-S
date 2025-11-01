import java.io.*;
import java.net.*;
import java.util.concurrent.Executors;

public class Server {

    private final String bindIp;
    private final int port;

    public Server(String bindIp, int port) {
        this.bindIp = bindIp;
        this.port = port;
    }

    public void start() throws IOException {
        var pool = Executors.newCachedThreadPool();
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.bind(new InetSocketAddress(bindIp, port));
            System.out.println("[SERVER] Listening on " + bindIp + ":" + port);

            while (true) {
                Socket socket = serverSocket.accept();
                pool.submit(() -> handleClient(socket));
            }
        }
    }

    private void handleClient(Socket socket) {
        String clientIp = ((InetSocketAddress) socket.getRemoteSocketAddress())
                .getAddress().getHostAddress();
        System.out.println("[SERVER] Connected: " + clientIp);

        try (socket;
             DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {

            // 1) AUTH
            Message first = Message.readFrom(in);
            if (first.getType() == Message.Type.AUTH && !first.getArgs().isEmpty()) {
                String token = first.getArgs().get(0);
                if (isValidToken(token)) {
                    Message.of("SERVER", Message.Type.AUTH_OK, "Client1", "5", "Testname")
                            .writeTo(out);
                } else {
                    Message.of("SERVER", Message.Type.AUTH_ERR, "Ungültiger Token").writeTo(out);
                    return;
                }
            } else {
                Message.of("SERVER", Message.Type.ERROR, "Erwarte AUTH").writeTo(out);
                return;
            }
            out.flush();

            // 2) Nachrichtenloop
            while (true) {
                Message msg = Message.readFrom(in);
                System.out.println("[SERVER] " + msg);

                switch (msg.getType()) {
                    case MSG -> {
                        String payload = msg.getArgs().isEmpty() ? "" : msg.getArgs().get(0);
                        Message.of("SERVER", Message.Type.MSG, "Echo: " + payload).writeTo(out);
                        out.flush();
                    }
                    case NEXT_TASK -> {
                        Message.of("SERVER", Message.Type.MSG, "Neue Aufgabe: Beschreibe ...").writeTo(out);
                        out.flush();
                    }
                    default -> {}
                }
            }
        } catch (EOFException e) {
            System.out.println("[SERVER] Client disconnected: " + clientIp);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private boolean isValidToken(String token) {
        return token != null && !token.isBlank();
    }

    public static void main(String[] args) throws IOException {
        // Beispiel: Bind auf fester LAN-IP
        new Server("192.168.137.221", 5050).start();
    }
}
