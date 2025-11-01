package frontend.client;

import backend.client.Message;
import frontend.client.UI.OverviewRow;

import javax.swing.SwingUtilities;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Concrete bridge that talks to the backend client/server by exchanging {@link Message}s.
 * It performs discovery via UDP, connects over TCP, and forwards all server events to the UI.
 */
public class NetworkClientBridge implements UI.ClientBridge {

    private static final int UDP_BROADCAST_PORT = 5051;
    private static final int DISCOVERY_WINDOW_MS = 2500;
    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final String INSTANCE_PRE = "NEWCLIENT";

    private volatile UiCallbacks ui;
    private volatile IClient.DiscoveredServer selectedServer;

    private final Map<String, String> localAnswers = new ConcurrentHashMap<>();
    private final Object sendLock = new Object();

    private volatile Socket socket;
    private volatile DataInputStream in;
    private volatile DataOutputStream out;
    private volatile Thread listenerThread;
    private volatile boolean running;

    private volatile String clientId = INSTANCE_PRE;
    private volatile String token = "";

    @Override
    public void setUiCallbacks(UiCallbacks ui) {
        this.ui = ui;
    }

    @Override
    public List<IClient.DiscoveredServer> fetchServers() {
        try {
            return discoverServers();
        } catch (IOException e) {
            toast("Serversuche fehlgeschlagen: " + shortMessage(e));
            return List.of();
        }
    }

    @Override
    public void selectServer(IClient.DiscoveredServer server) {
        this.selectedServer = server;
        localAnswers.clear();
    }

    @Override
    public void authenticate(String token) {
        IClient.DiscoveredServer server = this.selectedServer;
        if (server == null) {
            toast("Bitte zuerst einen Server waehlen.");
            return;
        }
        closeConnection();

        this.token = token == null ? "" : token.trim();
        this.clientId = INSTANCE_PRE;
        this.running = true;

        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(server.ip(), server.port()), CONNECT_TIMEOUT_MS);
            s.setTcpNoDelay(true);
            this.socket = s;
            this.in = new DataInputStream(s.getInputStream());
            this.out = new DataOutputStream(s.getOutputStream());

            listenerThread = new Thread(this::listenLoop, "UI-NetworkListener");
            listenerThread.setDaemon(true);
            listenerThread.start();

            sendMessage(new Message(clientId, "AUTH", this.token));
        } catch (IOException e) {
            handleConnectionLoss("Verbindung fehlgeschlagen: " + shortMessage(e));
        }
    }

    @Override
    public void cancelWaiting() {
        closeConnection();
        SwingUtilities.invokeLater(() -> {
            UiCallbacks callbacks = ui;
            if (callbacks != null) {
                callbacks.backToToken();
            }
        });
    }

    @Override
    public void requestNextTask() {
        sendMessage(new Message(clientId, "NEXT_TASK", token));
    }

    @Override
    public void submitAnswer(String taskId, List<String> answers) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        String protocolValue = joinAnswersProtocol(answers);
        String displayValue = joinAnswersDisplay(answers);
        localAnswers.put(taskId, displayValue.isBlank() ? "-" : displayValue);
        Message msg = new Message(clientId, "SEND_ANSWER", token)
                .put("taskId", taskId)
                .put("antwort", protocolValue);
        sendMessage(msg);
    }

    @Override
    public void submitAll() {
        Message deliver = new Message(clientId, "SEND_DELIVER", token)
                .put("testatId", UUID.randomUUID().toString());
        sendMessage(deliver);
    }

    @Override
    public void requestOverview() {
        sendMessage(new Message(clientId, "ASK_OVERVIEW", token));
    }

    @Override
    public void requestTaskById(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        Message msg = new Message(clientId, "ASK_TASK_BY_ID", token)
                .put("taskId", taskId);
        sendMessage(msg);
    }

    @Override
    public void sendWarning(String reason) {
        if (reason == null || reason.isBlank()) {
            return;
        }
        Message msg = new Message(clientId, "WARNING", token)
                .put("grund", reason)
                .put("zeitstempel", Instant.now().toString());
        sendMessage(msg);
    }

    @Override
    public void setHandRaise(boolean raised) {
        Message msg = new Message(clientId, raised ? "RAISE_HAND" : "LOWER_HAND", token)
                .put("zeitstempel", Instant.now().toString());
        sendMessage(msg);
    }

    /* ------------------------- Listening & dispatch ------------------------- */

    private void listenLoop() {
        try {
            while (running && socket != null && !socket.isClosed()) {
                Message msg = Message.read(in);
                if (msg == null) {
                    break;
                }
                handleMessage(msg);
            }
        } catch (Exception e) {
            handleConnectionLoss("Verbindung getrennt: " + shortMessage(e));
        } finally {
            running = false;
            closeSilently(in);
            closeSilently(out);
            closeSilently(socket);
            in = null;
            out = null;
            socket = null;
        }
    }

    private void handleMessage(Message msg) {
        switch (msg.messageType) {
            case "AUTH_OK" -> handleAuthOk(msg);
            case "AUTH_ERR" -> handleAuthError(msg);
            case "SEND_TASK" -> handleTask(msg);
            case "SEND_OVERVIEW" -> handleOverview(msg);
            case "END_TEST" -> handleEnd();
            case "TEST_PAUSED" -> handlePaused();
            case "TEST_RESUMED" -> handleResumed();
            case "TIME_EXPIRED" -> handleTimeExpired(msg);
            case "ERROR" -> toast(nonBlank(msg.getString("fehler"), "Unbekannter Fehler vom Server."));
            default -> {
                // ignore other message types for now
            }
        }
    }

    private void handleAuthOk(Message msg) {
        clientId = Objects.requireNonNullElse(msg.getString("clientId"), INSTANCE_PRE);
        String testName = msg.getString("testName");
        Integer total = msg.getInt("countOfTasks");
        int totalTasks = total == null ? 0 : total;
        UiCallbacks callbacks = ui;
        if (callbacks != null) {
            SwingUtilities.invokeLater(() -> callbacks.showWaitingRoom(nonBlank(testName, "Warteraum"), totalTasks));
        }
    }

    private void handleAuthError(Message msg) {
        String reason = msg.getString("fehlernachricht");
        handleConnectionLoss(nonBlank(reason, "Authentifizierung fehlgeschlagen."));
        SwingUtilities.invokeLater(() -> {
            UiCallbacks callbacks = ui;
            if (callbacks != null) {
                callbacks.backToToken();
            }
        });
    }

    private void handleTask(Message msg) {
        Integer nr = msg.getInt("taskNr");
        String question = nonBlank(msg.getString("question"), msg.getString("task"));
        String answers = msg.getString("answers");
        List<String> opts = answers == null || answers.isBlank()
                ? List.of()
                : List.of(answers.split("\\|\\|"));
        String taskId = msg.getString("taskId");
        boolean preview = "1".equals(msg.getString("isPreview"));
        String taskType = msg.getString("taskType");

        UI.TaskViewModel view = new UI.TaskViewModel(
                nonBlank(taskId, UUID.randomUUID().toString()),
                nonBlank(question, "(Keine Fragestellung hinterlegt)"),
                opts,
                nr == null ? -1 : nr,
                preview,
                nonBlank(taskType, "Unbekannt")
        );

        UiCallbacks callbacks = ui;
        if (callbacks != null) {
            SwingUtilities.invokeLater(() -> callbacks.showTask(view));
        }
    }

    private void handleOverview(Message msg) {
        String data = msg.getString("data");
        List<OverviewRow> rows = new ArrayList<>();
        if (data != null && !data.isBlank()) {
            String[] parts = data.split("\\|\\|");
            for (String entry : parts) {
                if (entry == null || entry.isBlank()) {
                    continue;
                }
                String[] pieces = entry.split("::", 3);
                String taskId = pieces.length > 0 ? pieces[0] : UUID.randomUUID().toString();
                String question = pieces.length > 1 ? pieces[1] : taskId;
                String provided = pieces.length > 2 ? pieces[2] : null;
                String answer = localAnswers.getOrDefault(taskId, nonBlank(provided, "-"));
                rows.add(new OverviewRow(taskId, question, answer));
            }
        }

        UiCallbacks callbacks = ui;
        if (callbacks != null) {
            SwingUtilities.invokeLater(() -> callbacks.showOverview(rows));
        }
    }

    private void handleEnd() {
        closeConnection();
        UiCallbacks callbacks = ui;
        if (callbacks != null) {
            SwingUtilities.invokeLater(callbacks::showEnd);
        }
    }

    private void handlePaused() {
        UiCallbacks callbacks = ui;
        if (callbacks != null) {
            SwingUtilities.invokeLater(callbacks::showPauseOverlay);
        }
    }

    private void handleResumed() {
        UiCallbacks callbacks = ui;
        if (callbacks != null) {
            SwingUtilities.invokeLater(callbacks::hidePauseOverlay);
        }
    }

    private void handleTimeExpired(Message msg) {
        UiCallbacks callbacks = ui;
        if (callbacks != null) {
            String message = msg == null ? null : msg.getString("message");
            boolean forceExit = msg != null && "true".equalsIgnoreCase(msg.getString("forceExit"));
            SwingUtilities.invokeLater(() -> callbacks.showTimeExpired(message, forceExit));
        }
    }

    /* ---------------------------- Helper methods ---------------------------- */

    private List<IClient.DiscoveredServer> discoverServers() throws IOException {
        long deadline = System.currentTimeMillis() + DISCOVERY_WINDOW_MS;
        Map<String, IClient.DiscoveredServer> byKey = new LinkedHashMap<>();
        try (DatagramSocket ds = new DatagramSocket(UDP_BROADCAST_PORT)) {
            ds.setSoTimeout(Math.max(800, DISCOVERY_WINDOW_MS / 2));
            while (System.currentTimeMillis() < deadline) {
                byte[] buf = new byte[1024];
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                try {
                    ds.receive(packet);
                    String payload = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                    Map<String, String> kv = parseKv(payload);
                    String ip = nonBlank(kv.get("ipv4"), packet.getAddress().getHostAddress());
                    int port = parseInt(kv.get("tcp"), 5050);
                    String name = nonBlank(kv.get("name"), "Lernstands-Server");
                    String desc = nonBlank(kv.get("desc"), "");
                    String test = nonBlank(kv.get("test"), "");
                    IClient.DiscoveredServer server = new IClient.DiscoveredServer(ip, port, name, desc, test);
                    byKey.putIfAbsent(ip + ":" + port, server);
                } catch (SocketTimeoutException ignored) {
                    // loop again until deadline
                } catch (IOException | RuntimeException e) {
                    // ignore individual packet issues but continue listening
                }
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private Map<String, String> parseKv(String payload) {
        Map<String, String> map = new LinkedHashMap<>();
        if (payload == null || payload.isBlank()) {
            return map;
        }
        String[] pairs = payload.split(";");
        for (String pair : pairs) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                String key = pair.substring(0, idx).trim().toLowerCase(Locale.ROOT);
                String value = pair.substring(idx + 1).trim();
                map.put(key, value);
            }
        }
        return map;
    }

    private void sendMessage(Message msg) {
        if (msg == null) {
            return;
        }
        DataOutputStream outStream = this.out;
        if (outStream == null) {
            return;
        }
        synchronized (sendLock) {
            try {
                Message.write(outStream, msg);
            } catch (IOException e) {
                handleConnectionLoss("Senden fehlgeschlagen: " + shortMessage(e));
            }
        }
    }

    private void closeConnection() {
        running = false;
        closeSilently(socket);
        closeSilently(in);
        closeSilently(out);
        socket = null;
        in = null;
        out = null;
        Thread listener = listenerThread;
        if (listener != null && listener != Thread.currentThread()) {
            listener.interrupt();
        }
        listenerThread = null;
    }

    private void handleConnectionLoss(String message) {
        closeConnection();
        toast(message);
    }

    private void toast(String message) {
        UiCallbacks callbacks = ui;
        if (callbacks != null && message != null && !message.isBlank()) {
            SwingUtilities.invokeLater(() -> callbacks.toast(message));
        }
    }

    private String joinAnswersProtocol(List<String> answers) {
        if (answers == null || answers.isEmpty()) {
            return "";
        }
        return String.join("||", answers);
    }

    private String joinAnswersDisplay(List<String> answers) {
        if (answers == null || answers.isEmpty()) {
            return "";
        }
        return String.join(", ", answers);
    }

    private int parseInt(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String shortMessage(Exception e) {
        if (e == null) {
            return "";
        }
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return msg;
    }

    private String nonBlank(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private void closeSilently(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void closeSilently(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }
}
