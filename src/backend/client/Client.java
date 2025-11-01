package backend.client;

import backend.server.LogfileHandler;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

/*
================================================================================
Frontend Integration Guide
================================================================================
Wrap the client with a GUI by forwarding user actions to the existing helpers instead of relying on System.in:
  - chooseServerByIndex(int) works on the discovery list built by discoverServers().
  - provideAuthToken(String) submits the authentication token.
  - retryConnect(), chooseAnswerIndex(int), openOverview() and overviewSelect(int) control the interactive test flow.
  - sendWarning(String) reports forbidden key combinations back to the server.
The console UI remains intact, yet a GUI can replace the input/output while reusing these methods.
================================================================================
*/

/**
 * @title Client
 * @short Konsolen-Client mit Discovery, Auswahl, Auth und Fragenlauf; ÃƒÆ’Ã†â€™Ãƒâ€¦Ã¢â‚¬Å“bersicht mit Direktnavigation per Zahl.
 * @args String[] args - optional "-history"
 */
public class Client {

    private static final int UDP_BROADCAST_PORT = 5051;
    private static final int DISCOVERY_WINDOW_MS = 2500;
    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final String INSTANCE_PRE = "NEWCLIENT";

    private static final LogfileHandler log = new LogfileHandler();

    private static volatile boolean running = true;
    private static volatile Socket socket;
    private static volatile DataInputStream in;
    private static volatile DataOutputStream out;

    private static volatile String clientId = INSTANCE_PRE;
    private static volatile String myToken = ""; // vom Benutzer eingegeben

    // Zentraler Konsolen-Reader (einziger!)
    private static final BufferedReader CONSOLE =
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

    // Lokale Merker fuer Uebersicht/Review
    private static final List<String> overviewItems = new ArrayList<>(); // "taskId::question"
    private static final Map<String,String> localAnswers = new LinkedHashMap<>(); // taskId -> menschenlesbarer Wert
    private static final Map<String, TaskView> taskCache = new LinkedHashMap<>();
    private static final List<String> taskOrder = new ArrayList<>();
    private static volatile int announcedTaskCount = 0;
    private static volatile String pendingBackRequestTaskId = null;
    private static volatile String resumeTaskAfterPreviewId = null;

    private static volatile boolean keepHistory = false;

    /** @title ServerInfo @short Container fÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¼r Discovery-Ergebnisse. @args String ip,int tcp,String name,String desc,String test */
    private static class ServerInfo {
        final String ip; final int tcp; final String name; final String desc; final String test;
        ServerInfo(String ip, int tcp, String name, String desc, String test){ this.ip=ip; this.tcp=tcp; this.name=name; this.desc=desc; this.test=test; }
    }

    private enum TaskInputMode { SINGLE_CHOICE, MULTI_CHOICE, TEXT }

    private record TaskView(
            String id,
            int number,
            String question,
            List<String> options,
            String type,
            TaskInputMode mode,
            boolean preview
    ) {}

    private record AnswerPayload(String protocolValue, String displayValue) {}

    /**
     * @title main
     * @short Startet den Client (Discovery ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ Auswahl ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ Token ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ Connect ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ Threads ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ AUTH).
     * @args String[] args
     */
    public static void main(String[] args) {
        keepHistory = Arrays.stream(args).anyMatch(a -> a.equalsIgnoreCase("-history") || a.equalsIgnoreCase("ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â€šÂ¬Ã…â€œhistory"));
        clear(); printlnHeader();

        ServerInfo chosen = chooseServerInteractively();
        if (chosen == null) return;

        clear(); printlnHeader();
        System.out.println("GewÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¤hlter Server: " + chosen.name + " @ " + chosen.ip + ":" + chosen.tcp);
        if (chosen.test != null && !chosen.test.isBlank()) System.out.println("Aktiver Test: " + chosen.test);
        System.out.print("\nToken eingeben: ");
        myToken = readConsole();
        if (myToken == null) myToken = "";

        if (!connectStrict(chosen.ip, chosen.tcp)) {
            System.out.print("Nochmal verbinden? (j/n): ");
            String r = readConsole();
            if ("j".equalsIgnoreCase(r)) main(args);
            return;
        }

        Thread serverListener = new Thread(Client::serverListenerLoop, "ServerListener");
        serverListener.start();
        //log.writeLog("Client", localIPv4(), "START|THREAD|ServerListener");

        // Threads vorhanden, aber ohne System.in ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â€šÂ¬Ã…â€œ Hooks fÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¼r spÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¤teres GUI-Frontend
        new Thread(() -> {
        }, "MaskListener").start();

        new Thread(() -> {
            // Beispiel-Aufruf:
            // sendWarning("Alt+Tab");
        }, "ForbiddenKeyListener").start();

        // AUTH senden (ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¼ber Helper; keine checked Exception hier)
        sendMsg(new Message(INSTANCE_PRE, "AUTH", myToken));
    }

    /**
     * @title serverListenerLoop
     * @short Liest Server-Nachrichten und reagiert (AUTH_OK, SEND_TASK, SEND_OVERVIEW, END_TEST).
     * @args keine
     */
    private static void serverListenerLoop() {
        try {
            while (running && !socket.isClosed()) {
                Message msg = Message.read(in);
                if (msg == null) break;

                switch (msg.messageType) {
                    case "AUTH_OK" -> {
                        clientId = msg.getString("clientId");
                        Integer count = msg.getInt("countOfTasks");
                        announcedTaskCount = count == null ? 0 : count;
                        String testName = msg.getString("testName");
                        clear(); printlnHeader();
                        System.out.println("Warteraum. Warte auf Server...");
                        if (testName != null) System.out.println("Aktiver Test: " + testName);
                        System.out.println("Anzahl Aufgaben: " + announcedTaskCount);
                    }
                    case "AUTH_ERR" -> {
                        System.out.println("Authentifizierung fehlgeschlagen: " + msg.getString("fehlernachricht"));
                        System.out.println("Beliebige Taste fÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¼r Neustart..."); readConsole();
                        running = false; closeSocket(); System.exit(0);
                    }
                    case "SEND_TASK" -> {
                        handleTaskMessage(msg);
                    }
                    case "SEND_OVERVIEW" -> {
                        handleOverviewMessage(msg);
                    }
                    case "END_TEST" -> {
                        clear(); printlnHeader();
                        System.out.println("Test beendet. Anwendung kann geschlossen werden.");
                        running = false; closeSocket(); return;
                    }
                    default -> {}
                }
            }
        } 
         finally { running = false; closeSocket(); }
    }

    /**
     * @title handleTaskMessage
     * @short Bereitet Aufgabenanzeige samt Navigation vor.
     * @args Message msg
     */
    private static void handleTaskMessage(Message msg) {
        TaskView view = buildTaskView(msg);
        boolean previewFromBack = view.preview
                && pendingBackRequestTaskId != null
                && view.id != null
                && view.id.equals(pendingBackRequestTaskId);
        TaskView resumeView = null;
        if (previewFromBack) {
            pendingBackRequestTaskId = null;
            if (resumeTaskAfterPreviewId != null) {
                resumeView = taskCache.get(resumeTaskAfterPreviewId);
            }
            resumeTaskAfterPreviewId = null;
        }
        registerTask(view);
        TaskView replay = presentTask(view, resumeView);
        while (replay != null) {
            replay = presentTask(replay, null);
        }
    }

    private static TaskView buildTaskView(Message msg) {
        boolean isPreview = "1".equals(msg.getString("isPreview"));
        Integer nrObj = msg.getInt("taskNr");
        int taskNr = nrObj == null ? -1 : nrObj;
        String question = msg.getString("question");
        if (question == null || question.isBlank()) {
            question = msg.getString("task");
        }
        List<String> options = parseOptions(msg.getString("answers"));
        String taskType = msg.getString("taskType");
        TaskInputMode mode = resolveInputMode(taskType, options);
        return new TaskView(
                msg.getString("taskId"),
                taskNr,
                question,
                options,
                taskType,
                mode,
                isPreview
        );
    }

    private static List<String> parseOptions(String answersRaw) {
        if (answersRaw == null || answersRaw.isBlank()) {
            return List.of();
        }
        String[] parts = answersRaw.split("\\|\\|");
        List<String> cleaned = new ArrayList<>();
        for (String part : parts) {
            if (part == null) {
                continue;
            }
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                cleaned.add(trimmed);
            }
        }
        if (cleaned.isEmpty()) {
            return List.of();
        }
        return Collections.unmodifiableList(new ArrayList<>(cleaned));
    }

    private static TaskInputMode resolveInputMode(String taskType, List<String> options) {
        String normalized = taskType == null ? "" : taskType.trim().toLowerCase(Locale.ROOT);
        TaskInputMode mode = switch (normalized) {
            case "singlechoice", "single-choice", "single_choice", "single", "radio" -> TaskInputMode.SINGLE_CHOICE;
            case "multichoice", "multi-choice", "multiplechoice", "multiple-choice", "multiple_choice", "multi" -> TaskInputMode.MULTI_CHOICE;
            case "textinput", "text", "frei", "freitext" -> TaskInputMode.TEXT;
            default -> options.isEmpty() ? TaskInputMode.TEXT : TaskInputMode.SINGLE_CHOICE;
        };
        if ((mode == TaskInputMode.SINGLE_CHOICE || mode == TaskInputMode.MULTI_CHOICE) && options.isEmpty()) {
            return TaskInputMode.TEXT;
        }
        return mode;
    }

    private static void registerTask(TaskView view) {
        if (view == null || view.preview) {
            return;
        }
        String taskId = view.id;
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        taskCache.put(taskId, view);
        if (!taskOrder.contains(taskId)) {
            taskOrder.add(taskId);
        }
    }

    private static TaskView presentTask(TaskView view, TaskView resumeAfterPreview) {
        if (view == null) {
            return null;
        }
        clear();
        printlnHeader();
        StringBuilder title = new StringBuilder("Aufgabe");
        if (view.number > 0) {
            title.append(" ").append(view.number);
        }
        if (view.preview) {
            title.append(" (Review)");
        }
        System.out.println(title);
        if (!view.preview && announcedTaskCount > 0 && view.number > 0) {
            System.out.printf("Frage %d von %d%n", view.number, announcedTaskCount);
        } else if (view.preview) {
            System.out.println("Hinweis: Vorschau - automatische Navigation deaktiviert.");
        }
        if (view.type != null && !view.type.isBlank()) {
            System.out.println("Typ: " + view.type);
        }
        System.out.println();
        String question = view.question == null || view.question.isBlank()
                ? "(keine Fragestellung hinterlegt)"
                : view.question;
        System.out.println(question);
        if (!view.options.isEmpty()) {
            System.out.println();
            for (int i = 0; i < view.options.size(); i++) {
                System.out.printf("%2d) %s%n", i + 1, view.options.get(i));
            }
            if (view.mode == TaskInputMode.MULTI_CHOICE) {
                System.out.println("\nMehrere Antworten moeglich - Nummern mit Leerzeichen oder Komma trennen.");
            }
        }
        if (view.id != null) {
            String saved = localAnswers.get(view.id);
            if (saved != null && !saved.isBlank()) {
                System.out.println("\nGespeicherte Antwort: " + saved);
            }
        }

        List<String> commandHints = new ArrayList<>();
        commandHints.add("'o' = Uebersicht");
        boolean allowBack = !view.preview && hasPreviousTask(view.id);
        if (allowBack) {
            commandHints.add("'z' = Zurueck zur vorherigen Aufgabe");
        }
        if (!commandHints.isEmpty()) {
            System.out.println("\nBefehle: " + String.join(", ", commandHints));
        }
        if (view.mode == TaskInputMode.TEXT) {
            System.out.println("Hinweis: Leer lassen = keine Antwort.");
        }

        String prompt = switch (view.mode) {
            case SINGLE_CHOICE -> "\nAntwortnummer: ";
            case MULTI_CHOICE -> "\nAntwortnummern: ";
            case TEXT -> "\nAntwort: ";
        };

        while (running) {
            System.out.print(prompt);
            String rawLine = readConsole();
            if (rawLine == null) rawLine = "";
            String trimmed = rawLine.trim();

            if ("o".equalsIgnoreCase(trimmed)) {
                sendMsg(new Message(clientId, "ASK_OVERVIEW", myToken));
                return null;
            }
            if (allowBack && (trimmed.equalsIgnoreCase("z") || trimmed.equalsIgnoreCase("p"))) {
                if (handleBackNavigation(view)) {
                    return null;
                }
                continue;
            }

            AnswerPayload payload = collectAnswer(view, rawLine, trimmed);
            if (payload == null) {
                continue;
            }

            if (view.id != null && !view.id.isBlank()) {
                String displayValue = payload.displayValue().isBlank() ? "-" : payload.displayValue();
                localAnswers.put(view.id, displayValue);
                sendMsg(new Message(clientId, "SEND_ANSWER", myToken, List.of(
                        "taskId", view.id,
                        "antwort", payload.protocolValue()
                )));
            } else {
                System.out.println("Aufgaben-ID fehlt, Antwort kann nicht gesendet werden.");
            }

            if (view.preview) {
                if (resumeAfterPreview != null) {
                    return resumeAfterPreview;
                }
                sendMsg(new Message(clientId, "ASK_OVERVIEW", myToken));
            } else {
                sendMsg(new Message(clientId, "NEXT_TASK", myToken));
            }
            return null;
        }
        return null;
    }

    private static boolean handleBackNavigation(TaskView currentView) {
        if (pendingBackRequestTaskId != null) {
            System.out.println("Ruecksprung laeuft bereits. Bitte warten...");
            return true;
        }
        if (currentView.id == null || currentView.id.isBlank()) {
            System.out.println("Diese Aufgabe besitzt keine ID - Ruecksprung nicht moeglich.");
            return false;
        }
        String previousId = findPreviousTaskId(currentView.id);
        if (previousId == null) {
            System.out.println("Keine vorherige Aufgabe vorhanden.");
            return false;
        }
        resumeTaskAfterPreviewId = currentView.id;
        pendingBackRequestTaskId = previousId;
        TaskView prevView = taskCache.get(previousId);
        String label = prevView != null && prevView.number > 0
                ? "Aufgabe " + prevView.number
                : "Aufgabe " + previousId;
        System.out.println("Oeffne " + label + " ...");
        sendMsg(new Message(clientId, "ASK_TASK_BY_ID", myToken, List.of("taskId", previousId)));
        return true;
    }

    private static boolean hasPreviousTask(String taskId) {
        if (taskId == null) {
            return false;
        }
        int idx = taskOrder.indexOf(taskId);
        return idx > 0;
    }

    private static String findPreviousTaskId(String taskId) {
        if (taskId == null) {
            return null;
        }
        int idx = taskOrder.indexOf(taskId);
        if (idx <= 0) {
            return null;
        }
        return taskOrder.get(idx - 1);
    }

    private static AnswerPayload collectAnswer(TaskView view, String rawLine, String trimmedLine) {
        return switch (view.mode) {
            case SINGLE_CHOICE -> parseSingleChoice(view, trimmedLine);
            case MULTI_CHOICE -> parseMultiChoice(view, trimmedLine);
            case TEXT -> parseTextAnswer(rawLine);
        };
    }

    private static AnswerPayload parseSingleChoice(TaskView view, String trimmedLine) {
        if (view.options.isEmpty()) {
            System.out.println("Keine Antwortoptionen vorhanden.");
            return null;
        }
        if (trimmedLine.isEmpty()) {
            System.out.println("Bitte eine gueltige Nummer eingeben.");
            return null;
        }
        try {
            int idx = Integer.parseInt(trimmedLine);
            if (idx < 1 || idx > view.options.size()) {
                System.out.println("Nummer ausserhalb des gueltigen Bereichs.");
                return null;
            }
            String answer = view.options.get(idx - 1);
            return new AnswerPayload(answer, answer);
        } catch (NumberFormatException e) {
            System.out.println("Bitte eine Zahl eingeben.");
            return null;
        }
    }

    private static AnswerPayload parseMultiChoice(TaskView view, String trimmedLine) {
        if (view.options.isEmpty()) {
            System.out.println("Keine Antwortoptionen vorhanden.");
            return null;
        }
        if (trimmedLine.isEmpty()) {
            System.out.println("Bitte mindestens eine Nummer nennen.");
            return null;
        }
        String sanitized = trimmedLine.replace(',', ' ');
        String[] tokens = sanitized.split("\s+");
        LinkedHashSet<Integer> indices = new LinkedHashSet<>();
        for (String token : tokens) {
            if (token.isBlank()) continue;
            try {
                int idx = Integer.parseInt(token);
                if (idx < 1 || idx > view.options.size()) {
                    System.out.println("Nummer " + idx + " liegt ausserhalb des gueltigen Bereichs.");
                    return null;
                }
                indices.add(idx - 1);
            } catch (NumberFormatException e) {
                System.out.println("Ungueltige Eingabe: " + token);
                return null;
            }
        }
        if (indices.isEmpty()) {
            System.out.println("Bitte mindestens eine gueltige Nummer eingeben.");
            return null;
        }
        List<String> selected = new ArrayList<>();
        for (Integer idx : indices) {
            selected.add(view.options.get(idx));
        }
        String protocol = String.join("||", selected);
        String display = String.join(", ", selected);
        return new AnswerPayload(protocol, display);
    }

    private static AnswerPayload parseTextAnswer(String rawLine) {
        String value = rawLine == null ? "" : rawLine;
        String display = value.trim();
        return new AnswerPayload(value, display);
    }

    /**
     * @title handleOverviewMessage
     * @short Zeigt ÃƒÆ’Ã†â€™Ãƒâ€¦Ã¢â‚¬Å“bersicht, erlaubt **eine** Eingabe: Zahl ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ Aufgabe ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¶ffnen; 'j' ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ abgeben.
     * @args Message msg
     */
    private static void handleOverviewMessage(Message msg) {
        overviewItems.clear();
        String data = msg.getString("data");
        if (data != null && !data.isBlank()) {
            overviewItems.addAll(Arrays.asList(data.split("\\|\\|")));
        }
        clear(); printlnHeader();
        System.out.println("ÃƒÆ’Ã†â€™Ãƒâ€¦Ã¢â‚¬Å“bersicht ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â€šÂ¬Ã…â€œ bitte prÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¼fen:\n");
        for (int i = 0; i < overviewItems.size(); i++) {
            String[] parts = overviewItems.get(i).split("::", 2);
            String taskId = parts[0];
            String question = parts.length > 1 ? parts[1] : parts[0];
            String ans = localAnswers.getOrDefault(taskId, "-");
            System.out.printf("%2d) %s   [Antwort: %s]%n", (i+1), question, ans);
        }
        System.out.print("\nNummer ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¶ffnen oder Abgabe bestÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¤tigen? ('j' = abgeben): ");
        String input = readConsole();
        if (input == null) input = "";
        input = input.trim();

        if (input.matches("\\d+")) {
            int idx = Integer.parseInt(input);
            if (idx >= 1 && idx <= overviewItems.size()) {
                String[] parts = overviewItems.get(idx-1).split("::", 2);
                String taskId = parts[0];
                sendMsg(new Message(clientId, "ASK_TASK_BY_ID", myToken, List.of("taskId", taskId)));
            } else {
                sendMsg(new Message(clientId, "ASK_OVERVIEW", myToken)); // out of range ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ ÃƒÆ’Ã†â€™Ãƒâ€¦Ã¢â‚¬Å“bersicht neu
            }
        } else if ("j".equalsIgnoreCase(input)) {
            sendMsg(new Message(clientId, "SEND_DELIVER", myToken, List.of("testatId", UUID.randomUUID().toString())));
            clear(); printlnHeader(); System.out.println("Abgegeben. Warte auf Server...");
        } else {
            sendMsg(new Message(clientId, "ASK_OVERVIEW", myToken));
        }
    }

    // --------- Discovery & Connect ----------

    /** @title chooseServerInteractively @short Discovery + Auswahl in der Konsole. @args keine */
    private static ServerInfo chooseServerInteractively() {
        Map<String, ServerInfo> discovered = new LinkedHashMap<>();
        List<ServerInfo> listing = new ArrayList<>();
        boolean rescan = true;
        boolean lastRoundFoundNew = false;
        boolean lastRoundReceivedAny = false;

        while (true) {
            if (rescan) {
                List<ServerInfo> round = discoverServers();
                lastRoundReceivedAny = !round.isEmpty();
                lastRoundFoundNew = false;
                for (ServerInfo info : round) {
                    if (info == null || info.ip == null || info.ip.isBlank()) {
                        continue;
                    }
                    String key = info.ip + ":" + info.tcp;
                    if (!discovered.containsKey(key)) {
                        lastRoundFoundNew = true;
                    }
                    discovered.put(key, info);
                }
                if (discovered.isEmpty()) {
                    System.out.println("Kein Server gefunden.");
                    System.out.print("Erneut versuchen? (j/n): ");
                    String retry = readConsole();
                    if (!"j".equalsIgnoreCase(retry)) {
                        return null;
                    }
                    clear();
                    printlnHeader();
                    continue;
                }
                listing = new ArrayList<>(discovered.values());
                rescan = false;
            }

            clear();
            printlnHeader();
            System.out.println("Gefundene Server:");
            System.out.println("+----+---------------+------+----------------+----------------------+");
            System.out.println("| Nr | IPv4          | TCP  | Name           | Beschreibung         |");
            System.out.println("+----+---------------+------+----------------+----------------------+");
            for (int i = 0; i < listing.size(); i++) {
                ServerInfo si = listing.get(i);
                System.out.printf("| %2d | %-13s | %-4d | %-14s | %-20s |%n",
                        (i + 1),
                        safe(si.ip),
                        si.tcp,
                        safe(si.name),
                        safe(si.desc));
                if (si.test != null && !si.test.isBlank()) {
                    System.out.printf("      -> Test: %s%n", safe(si.test));
                }
            }
            System.out.println("+----+---------------+------+----------------+----------------------+");
            if (lastRoundFoundNew) {
                System.out.println("(Neue Server wurden in der letzten Suche gefunden.)");
            } else if (lastRoundReceivedAny) {
                System.out.println("(Keine neuen Server in der letzten Suche gefunden.)");
            } else {
                System.out.println("(Keine Antwort auf die letzte Suche erhalten. Liste unveraendert.)");
            }

            System.out.print("\nServer-Nummer waehlen (0 = erneut suchen): ");
            String selection = readConsole();
            if (selection == null) {
                System.out.println("Ungueltige Auswahl.\n");
                rescan = false;
                continue;
            }
            selection = selection.trim();
            if ("0".equals(selection)) {
                rescan = true;
                continue;
            }
            try {
                int idx = Integer.parseInt(selection) - 1;
                if (idx >= 0 && idx < listing.size()) {
                    return listing.get(idx);
                }
            } catch (NumberFormatException ignored) {
            }
            System.out.println("Ungueltige Auswahl.\n");
            rescan = false;
        }
    }
    /** @title discoverServers 
      * @short HÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¶rt kurz auf UDP-Broadcasts und sammelt Kandidaten. 
      * @args keine */
    private static List<ServerInfo> discoverServers() {
        long end = System.currentTimeMillis() + DISCOVERY_WINDOW_MS;
        Map<String, ServerInfo> byKey = new LinkedHashMap<>();
        try (DatagramSocket ds = new DatagramSocket(UDP_BROADCAST_PORT)) {
            ds.setSoTimeout(Math.max(800, DISCOVERY_WINDOW_MS/2));
            while (System.currentTimeMillis() < end) {
                byte[] buf = new byte[1024];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    ds.receive(p);
                    String payload = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                    Map<String,String> map = parseKv(payload);
                    String ip = map.get("ipv4");
                    int tcp = Integer.parseInt(map.getOrDefault("tcp","5050"));
                    String name = map.getOrDefault("name","Lernstands-Server");
                    String desc = map.getOrDefault("desc","");
                    String test = map.getOrDefault("test","");
                    byKey.putIfAbsent(ip+":"+tcp, new ServerInfo(ip,tcp,name,desc,test));
                } catch (SocketTimeoutException ignored) {}
                catch (IOException | RuntimeException e) { log.writeLog("Client", localIPv4(), "ERROR|DISCOVERY_ITEM|" + e.getMessage()); }
            }
        } catch (IOException e) {
            log.writeLog("Client", localIPv4(), "ERROR|DISCOVERY|" + e.getMessage());
        }
        return new ArrayList<>(byKey.values());
    }

    /**
     * @title connectStrict
     * @short Baut TCP-Verbindung nur zur angegebenen IP auf (kein 127.0.0.1-Fallback).
     * @args String ip, int port
     */
    private static boolean connectStrict(String ip, int port) {
        try {
            socket = new Socket(); socket.setReuseAddress(true);
            socket.connect(new InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            in = new DataInputStream(socket.getInputStream());
            out = new DataOutputStream(socket.getOutputStream());
            log.writeLog("Client", socket.getLocalAddress().getHostAddress(), "START|NETWORK|TCP "+ip+":"+port);
            return true;
        } catch (IOException e) {
            System.out.println("Verbindung fehlgeschlagen: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    // -------- Utilities --------
    /** @title sendMsg 
     *  @short Hilfsfunktion zum Senden. 
     *  @args Message m */
    private static void sendMsg(Message m) {

        try {

            Message.write(out, m);

        } catch (IOException e) {

            log.writeLog("Client", localIPv4(), "ERROR|SEND|" + e.getMessage());

        }

    }

    /** @title parseKv 
     *  @short Parsen "key=value;..." aus Broadcast. 
     *  @args String s */
    private static Map<String,String> parseKv(String s){ Map<String,String> m=new HashMap<>(); for(String p:s.split(";")){int i=p.indexOf('='); if(i>0)m.put(p.substring(0,i),p.substring(i+1));} return m; }

    /** @title localIPv4 
     *  @short Liefert IPv4 des Hosts. 
     *  @args keine */
    private static String localIPv4() {

        try {

            return InetAddress.getLocalHost().getHostAddress();

        } catch (UnknownHostException e) {

            return "127.0.0.1";

        }

    }

    /** @title readConsole 
     *  @short Liest eine Zeile aus dem EINZIGEN Konsolen-Reader. 
     *  @args keine */
    private static String readConsole(){ try { return CONSOLE.readLine(); } catch(IOException e){ return ""; } }

    /** @title clear 
     *  @short RÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¤umt die Konsole auf (auÃƒÆ’Ã†â€™Ãƒâ€¦Ã‚Â¸er -history). 
     *  @args keine */
    private static void clear() {

        if (keepHistory) {

            return;

        }

        try {

            String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);

            if (os.contains("win")) {

                new ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor();

            } else {

                System.out.print("\033[H\033[2J");

                System.out.flush();

            }

        } catch (IOException e) {

            log.writeLog("Client", localIPv4(), "ERROR|CLEAR|" + e.getMessage());

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

        }

    }

    /** @title printlnHeader 
     *  @short Druckt die Kopfzeile. 
     *  @args keine */
    private static void printlnHeader(){ System.out.println("Lernstands-ÃƒÆ’Ã†â€™Ãƒâ€¦Ã¢â‚¬Å“berprÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¼fungs-Software ? Client\n"); }

    /** @title closeSocket 
     *  @short SchlieÃƒÆ’Ã†â€™Ãƒâ€¦Ã‚Â¸t die TCP-Verbindung.
     *  @args keine */
    private static void closeSocket(){ try { if (socket!=null) socket.close(); } catch(IOException ignored){} }

    /** @title safe @short Null-sichere String-Darstellung. @args Object o */
    private static String safe(Object o){ return o==null?"":String.valueOf(o); }

    /** @title sendWarning 
     *  @short Schickt WARNING an den Server (Hook fÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¼r KeyListener im GUI). 
     *  @args String grund */
    @SuppressWarnings("unused")
    private static void sendWarning(String grund){
        sendMsg(new Message(clientId, "WARNING", myToken, List.of(
                "grund", grund,
                "zeitstempel", LocalDateTime.now().toString()
        )));
    }
}



