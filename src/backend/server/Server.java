package backend.server;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/*
================================================================================
Frontend Integration Guide
================================================================================
Use this backend class from a GUI by steering the existing navigation helpers instead of supplying console input.
Key touch points for a UI controller:
  - handleRootSelection(String) processes the root dashboard options (1 = Test laden, 2 = Import, 3 = Korrektur, 'e' = Beenden).
  - handleTestSelected(String) reacts to the test selection menu (1 = Server hosten, 2 = Abbrechen).
  - hostingView(...) and runningView(...) illustrate the waiting room and active run; a GUI should mirror their behaviour while driving the workflow via startTestRun(), abortHosting() and endRunningTest().
Read-only status data for rendering is available through getDashboardState(), getSelectedTestName(), getSelectedClassName() and getClientSummaries().
In GUI projects instantiate the server via frontend.server.BackendServerAdapter to avoid console I/O and reuse the logic.
================================================================================
*/

/**
 * @title Server
 * @short Konsolen-Server mit Dashboard, Discovery, Authentifizierung und Testlauf + Review-Navigation.
 * @args String[] args - optional "-history" (Konsole nicht clearen)
 */
public class Server {

    // -------- Konfiguration --------
    private static final int TCP_PORT = 5050;
    private static final int UDP_BROADCAST_PORT = 5051;
    private static final String BROADCAST_ADDR = "255.255.255.255";
    private static final String DEFAULT_TOKEN = "1234";
    private static final String TIME_EXPIRED_MESSAGE = "Die Zeit ist abgelaufen. Die Anwendung wird geschlossen.";
    // --------------------------------

    private final LogfileHandler log = new LogfileHandler();
    private final boolean keepHistory;
    private final boolean consoleMode;
    private volatile boolean running = true;

    public enum DashboardState { ROOT, TEST_SELECTED, HOSTING, RUNNING }
    private volatile DashboardState state = DashboardState.ROOT;

    private volatile Test selectedTest = null;
    private volatile String selectedTestName = null;
    private volatile int selectedTestId = -1;

    private ServerSocket serverSocket;
    private Thread dashboardThread;
    private Thread broadcastThread;

    private final Map<String, ClientHandler> clients = new ConcurrentHashMap<>();
    private final List<ClientWarning> warnings = new ArrayList<>();
    private volatile int clientCounter = 0;
    private final ScheduledExecutorService timerScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ServerTimer");
        t.setDaemon(true);
        return t;
    });
    private volatile ScheduledFuture<?> timerFuture;
    private volatile long configuredDurationMillis = 0L;
    private volatile long remainingDurationMillis = 0L;
    private volatile boolean timerRunning = false;
    private volatile boolean timerPaused = false;
    private volatile boolean testPaused = false;
    private volatile long lastTimerTickAt = 0L;

    private volatile String boundIp = resolveLocalIPv4();
    private volatile boolean clientsTableDirty = false; // nur bei erfolgreicher AUTH ÃƒÂ¢Ã¢â‚¬Â Ã¢â‚¬â„¢ UI-Refresh

    // FÃƒÆ’Ã‚Â¼ge ein Feld fÃƒÆ’Ã‚Â¼r den DatabaseHandler hinzu
    private DatabaseHandler db;

    // Mapping: Token ÃƒÂ¢Ã¢â‚¬Â Ã¢â‚¬â„¢ SchÃƒÆ’Ã‚Â¼ler-ID fÃƒÆ’Ã‚Â¼r die aktuelle Sitzung
    private volatile Map<String, Integer> tokenToSchuelerId = new ConcurrentHashMap<>();
    private volatile String selectedClassName = null;
    private volatile int selectedClassId = -1;

    /**
     * @title main
     * @short Einstiegspunkt.
     * @args String[] args
     */
    public static void main(String[] args) {
        boolean keepHistory = Arrays.stream(args).anyMatch(a -> a.equalsIgnoreCase("-history") || a.equalsIgnoreCase("ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Å“history"));
        new Server(keepHistory, true).start();
    }

    /**
     * @title Konstruktor
     * @short Init mit Clear-Flag.
     * @args boolean keepHistory
     */
    public Server(boolean keepHistory) {
        this(keepHistory, true);
    }

    public Server(boolean keepHistory, boolean consoleMode) {
        this.keepHistory = keepHistory;
        this.consoleMode = consoleMode;
        try {
            this.db = new DatabaseHandler("data/lues.db");
        } catch (SQLException e) {
            System.err.println("Fehler beim Oeffnen der Datenbank: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * @title start
     * @short Startet Dashboard und TCP-Acceptor (bindet auf lokale IPv4).
     * @args keine
     */
    public void start() {
        if (consoleMode) {
            dashboardThread = new Thread(this::dashboardLoop, "DashboardListener");
            dashboardThread.start();
            log.writeLog("Server", boundIp, "START|THREAD|DashboardListener");
        }

        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(boundIp, TCP_PORT), 100);
            log.writeLog("Server", boundIp, "START|NETWORK|Listen " + boundIp + ":" + TCP_PORT);
            Thread acceptor = new Thread(this::acceptLoop, "Acceptor");
            acceptor.start();
            log.writeLog("Server", boundIp, "START|THREAD|Acceptor");
        } catch (IOException e) {
            System.err.println("TCP-Serverstart fehlgeschlagen: " + e.getMessage());
            log.writeLog("Server", boundIp, "ERROR|NETWORK|" + e.getMessage());
        }
    }

    /**
     * @title acceptLoop
     * @short Nimmt Verbindungen an und startet je Client einen CommunicationListener.
     * @args keine
     */
    private void acceptLoop() {
        while (running) {
            try {
                Socket s = serverSocket.accept();
                s.setTcpNoDelay(true);
                new ClientHandler("NEWCLIENT", s).start();
            } catch (IOException e) {
                if (running) log.writeLog("Server", boundIp, "ERROR|ACCEPT|" + e.getMessage());
            }
        }
    }

    /**
     * @title dashboardLoop
     * @short Konsolen-UI; HOSTING-Ansicht rendert nur bei Eingabe ODER wenn sich Clients ÃƒÆ’Ã‚Â¤ndern.
     * @args keine
     */
    private void dashboardLoop() {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            while (running) {
                clear(); printHeader();
                switch (state) {
                    case ROOT -> {
                        System.out.println("1) Test laden");
                        System.out.println("2) Test importieren");
                        System.out.println("3) Korrekturmodus starten");
                        System.out.println("e) Beenden");
                        System.out.print("\nAuswahl (Zahl): ");
                        handleRootSelection(readLineAndClear(br));
                    }
                    case TEST_SELECTED -> {
                        System.out.println("AusgewÃƒÆ’Ã‚Â¤hlter Test: " + selectedTestName);
                        System.out.println("1) Server hosten");
                        System.out.println("2) Testat abbrechen");
                        System.out.print("\nAuswahl (Zahl): ");
                        handleTestSelected(readLineAndClear(br));
                    }
                    case HOSTING -> hostingView(br);
                    case RUNNING -> runningView(br);
                }
            }
        } catch (IOException e) {
            log.writeLog("Server", boundIp, "ERROR|DASHBOARD|" + e.getMessage());
        } finally {
            running = false;
            stopBroadcast();
            for (ClientHandler ch : clients.values()) ch.close();
            try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * @title hostingView
     * @short HOSTING-MenÃƒÆ’Ã‚Â¼ mit ereignisgetriebenem Refresh (nur bei neuer AUTH oder Tastatureingabe).
     * @args BufferedReader br
     */
    private void hostingView(BufferedReader br) throws IOException {
        while (state == DashboardState.HOSTING && running) {
            clear(); printHeader();
            System.out.println("Warteraum aktiv ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Å“ Clients kÃƒÆ’Ã‚Â¶nnen sich verbinden.");
            renderClientsTable();
            System.out.println("\n1) Testat beginnen");
            System.out.println("2) Testat abbrechen");
            System.out.print("\nAuswahl (Zahl): ");

            while (state == DashboardState.HOSTING && running) {
                if (br.ready()) {
                    String line = readLineAndClear(br);
                    handleHostingSelection(line);
                    break;
                }
                if (clientsTableDirty) { // ÃƒÂ¢Ã¢â‚¬Â Ã¢â‚¬â„¢ UI aktualisieren
                    clientsTableDirty = false;
                    break;
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(50);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * @title runningView
     * @short RUNNING-MenÃƒÆ’Ã‚Â¼; einfacher Refresh nur bei Eingabe.
     * @args BufferedReader br
     */
    private void runningView(BufferedReader br) throws IOException {
        while (state == DashboardState.RUNNING && running) {
            clear(); printHeader();
            System.out.println("Test lÃƒÆ’Ã‚Â¤uft.");
            renderClientsTable();
            System.out.println("\n1) Test beenden");
            System.out.print("\nAuswahl (Zahl): ");
            String line = readLineAndClear(br);
            if ("1".equals(line != null ? line.trim() : "")) {
                broadcastToAll(new Message("SERVER", "END_TEST", "")); // Token leer ok
                state = DashboardState.HOSTING;
            }
        }
    }

    /**
     * @title handleRootSelection
     * @short MenÃƒÆ’Ã‚Â¼: Test laden / Import / Korrektur.
     * @args String sel
     */
    private void handleRootSelection(String sel) throws IOException {
        String s = sel == null ? "" : sel.trim();
        switch (s) {
            case "1" -> {
                try {
                    List<String> names = scanDbForTestNames();
                    if (names.isEmpty()) { System.out.println("\nKeine Tests gefunden. Enter..."); waitForEnterAndClear(); return; }
                    clear(); printHeader();
                    System.out.println("VerfÃƒÆ’Ã‚Â¼gbare Tests:");
                    for (int i=0;i<names.size();i++) System.out.printf("%d) %s%n", i+1, names.get(i));
                    System.out.print("\nNummer eingeben: ");
                    BufferedReader r = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                    String selection = readLineAndClear(r);
                    if (selection == null) throw new NumberFormatException("Abbruch");
                    int idx = Integer.parseInt(selection.trim()) - 1;
                    selectedTestName = names.get(idx);
                    selectedTest = loadTestFromDb(selectedTestName);
                    selectedTestId = getTestIdByName(selectedTestName);
                    state = DashboardState.TEST_SELECTED;
                    log.writeLog("Server", boundIp, "INFO|TEST|Selected="+selectedTestName);
                } catch (IOException | SQLException | NumberFormatException | IndexOutOfBoundsException ex) {
                    System.out.println("Ungueltige Auswahl oder Datenbankfehler. Enter..."); waitForEnterAndClear();
                }
            }
            case "2" -> { System.out.println("\nNoch nicht implementiert. Enter..."); waitForEnterAndClear(); }
            case "3" -> { enterCorrectionMode(); }
            case "e" -> { System.out.println("\nServer wird beendet."); System.exit(0); }
            default -> { if (!s.isEmpty()) { System.out.println("\nUngueltig. Enter..."); waitForEnterAndClear(); } }
        }
    }

    /**
     * @title handleTestSelected
     * @short MenÃƒÆ’Ã‚Â¼ nach Testauswahl. Jetzt mit Klassenwahl und Token-Generierung.
     * @args String sel
     */
    private void handleTestSelected(String sel) {
        String s = sel == null ? "" : sel.trim();
        switch (s) {
            case "1" -> {
                // Schritt 1: Klasse auswÃƒÆ’Ã‚Â¤hlen
                try {
                    List<Map<String, Object>> klassen = db.getAllEntries("Klasse");
                    if (klassen.isEmpty()) {
                        System.out.println("Keine Klassen gefunden. Enter..."); waitForEnterAndClear();
                        return;
                    }
                    clear(); printHeader();
                    System.out.println("VerfÃƒÆ’Ã‚Â¼gbare Klassen:");
                    for (int i = 0; i < klassen.size(); i++) {
                        System.out.printf("%d) %s%n", i + 1, klassen.get(i).get("klassenname"));
                    }
                    System.out.print("\nNummer der Klasse eingeben: ");
                    BufferedReader r = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                    String selection = readLineAndClear(r);
                    if (selection == null) throw new NumberFormatException("Abbruch");
                    int idx = Integer.parseInt(selection.trim()) - 1;
                    selectedClassName = klassen.get(idx).get("klassenname").toString();
                    selectedClassId = (int) klassen.get(idx).get("idKlasse");

                    // Schritt 2: Token generieren und speichern
                    generateAndSaveTokensForClass(selectedClassId, selectedClassName, true);

                    startBroadcast();
                    state = DashboardState.HOSTING;
                } catch (IOException | SQLException | NumberFormatException | IndexOutOfBoundsException ex) {
                    System.out.println("Ungueltige Auswahl oder Datenbankfehler. Enter..."); waitForEnterAndClear();
                }
            }
            case "2" -> { selectedTest = null; selectedTestName = null; state = DashboardState.ROOT; }
            default -> {}
        }
    }



    private void waitForEnterAndClear() {
        try {
            int ch;
            while ((ch = System.in.read()) != -1) {
                if (ch == '\n') {
                    break;
                }
            }
        } catch (IOException ignored) {
        }
        clear();
    }

    private String readLineAndClear(BufferedReader reader) throws IOException {
        String line = reader.readLine();
        clear();
        return line;
    }

    private void enterCorrectionMode() {
        try {
            List<Map<String, Object>> tests = db.getAllEntries("Test");
            List<Map<String, Object>> answers = db.getAllEntries("Antwort");
            if (answers.isEmpty()) {
                System.out.println("\nKeine abgegebenen Antworten gefunden. Enter...");
                waitForEnterAndClear();
                return;
            }

            Map<Integer, String> testNamesById = new LinkedHashMap<>();
            for (Map<String, Object> test : tests) {
                Object idObj = test.get("idTest");
                Object typObj = test.get("typ");
                if (idObj instanceof Number id && typObj != null) {
                    testNamesById.put(id.intValue(), typObj.toString());
                }
            }

            Map<Integer, List<Map<String, Object>>> answersByTest = new HashMap<>();
            for (Map<String, Object> answer : answers) {
                Object testObj = answer.get("idTest");
                if (!(testObj instanceof Number testNumber)) {
                    continue;
                }
                int testId = testNumber.intValue();
                answersByTest.computeIfAbsent(testId, ArrayList::new).add(answer);
            }

            List<Integer> selectableTestIds = answersByTest.keySet().stream()
                    .filter(testNamesById::containsKey)
                    .sorted(Comparator.comparing(testNamesById::get, String.CASE_INSENSITIVE_ORDER))
                    .toList();

            if (selectableTestIds.isEmpty()) {
                System.out.println("\nFuer keinen Test liegen Antworten vor. Enter...");
                waitForEnterAndClear();
                return;
            }

            Map<Integer, Map<String, Object>> bewertungCache = new HashMap<>();
            for (Map<String, Object> row : db.getAllEntries("Bewertung")) {
                Object idAntwortObj = row.get("idAntwort");
                if (idAntwortObj instanceof Number idAntwort) {
                    bewertungCache.put(idAntwort.intValue(), row);
                }
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            while (true) {
                clear();
                printHeader();
                System.out.println("Korrekturmodus - Test auswaehlen (0 = zurueck, h = Hauptmenue)\\n");
                for (int i = 0; i < selectableTestIds.size(); i++) {
                    int testId = selectableTestIds.get(i);
                    String testName = testNamesById.getOrDefault(testId, "Unbenannter Test");
                    List<Map<String, Object>> answersForTest = answersByTest.getOrDefault(testId, List.of());
                    long evaluated = answersForTest.stream()
                            .map(a -> ((Number) a.get("idAntwort")).intValue())
                            .filter(bewertungCache::containsKey)
                            .count();
                    System.out.printf("%d) %s (%d Antworten, %d bewertet)%n", i + 1, testName, answersForTest.size(), evaluated);
                }
                System.out.print("\nAuswahl: ");
                String input = readLineAndClear(reader);
                if (input == null) return;
                input = input.trim();
                if (input.equalsIgnoreCase("h")) throw new CorrectionAbortException();
                if (input.equals("0")) return;
                int idx;
                try {
                    idx = Integer.parseInt(input) - 1;
                } catch (NumberFormatException e) {
                    continue;
                }
                if (idx < 0 || idx >= selectableTestIds.size()) continue;
                int chosenTestId = selectableTestIds.get(idx);
                String chosenTestName = testNamesById.getOrDefault(chosenTestId, "Unbenannter Test");
                List<Map<String, Object>> answersForTest = new ArrayList<>(answersByTest.getOrDefault(chosenTestId, List.of()));
                runCorrectionForTest(chosenTestName, answersForTest, bewertungCache, reader);
            }
        } catch (CorrectionAbortException abort) {
            System.out.println("\nZurueck zum Hauptmenue.");
        } catch (IOException | SQLException e) {
            System.out.println("\nFehler im Korrekturmodus: " + e.getMessage() + " Enter...");
            waitForEnterAndClear();
            log.writeLog("Server", boundIp, "ERROR|CORRECTION|" + e.getMessage());
        }
    }


    private void runCorrectionForTest(String testName, List<Map<String, Object>> answersForTest,
                                      Map<Integer, Map<String, Object>> bewertungCache, BufferedReader reader) throws IOException, SQLException {
        if (answersForTest.isEmpty()) {
            System.out.println();
            System.out.println("Fuer diesen Test liegen keine Antworten vor. Enter...");
            String wait = readLineAndClear(reader);
            if (wait == null) {
                return;
            }
            return;
        }

        Map<Integer, Map<String, Object>> studentCache = new HashMap<>();
        Map<Integer, Map<String, Object>> classCache = new HashMap<>();
        Map<Integer, Map<String, Object>> taskCache = new HashMap<>();
        Map<Integer, List<Map<String, Object>>> answersByStudent = new HashMap<>();
        Map<Integer, List<Map<String, Object>>> answersByTask = new HashMap<>();

        for (Map<String, Object> answer : answersForTest) {
            Object schuelerObj = answer.get("idSchueler");
            if (!(schuelerObj instanceof Number studentNumber)) {
                continue;
            }
            int studentId = studentNumber.intValue();
            answersByStudent.computeIfAbsent(studentId, ArrayList::new).add(answer);
            studentCache.computeIfAbsent(studentId, id -> safeDbGet("Schueler", id));
            Object taskObj = answer.get("idAufgabe");
            if (taskObj instanceof Number taskNumber) {
                int taskId = taskNumber.intValue();
                answersByTask.computeIfAbsent(taskId, ArrayList::new).add(answer);
                taskCache.computeIfAbsent(taskId, id -> safeDbGet("Aufgabe", id));
            }
        }
        for (List<Map<String, Object>> list : answersByStudent.values()) {
            list.sort(Comparator.comparingInt(a -> ((Number) a.get("idAufgabe")).intValue()));
        }

        Map<Integer, LinkedHashSet<Integer>> classToStudents = new LinkedHashMap<>();
        for (Map.Entry<Integer, Map<String, Object>> entry : studentCache.entrySet()) {
            Map<String, Object> student = entry.getValue();
            if (student == null || student.isEmpty()) {
                continue;
            }
            Object classObj = student.get("idKlasse");
            if (!(classObj instanceof Number classNumber)) {
                continue;
            }
            int classId = classNumber.intValue();
            classCache.computeIfAbsent(classId, id -> safeDbGet("Klasse", id));
            classToStudents.computeIfAbsent(classId, LinkedHashSet::new).add(entry.getKey());
        }

        if (classToStudents.isEmpty()) {
            System.out.println();
            System.out.println("Es konnten keine Klassen zu den Antworten ermittelt werden. Enter...");
            String wait = readLineAndClear(reader);
            if (wait == null) {
                return;
            }
            return;
        }

        GradeSetup gradeSetup = promptGradeSetup(reader, testName);
        GradeScale gradeScale = gradeSetup.scale();
        Double maxPoints = gradeSetup.maxPoints();
        Map<Integer, GradeReport> gradeReports = new HashMap<>();

        List<Integer> classIds = new ArrayList<>(classToStudents.keySet());
        classIds.sort(Comparator.comparing(id -> getClassDisplayName(classCache.get(id)), String.CASE_INSENSITIVE_ORDER));

        while (true) {
            clear();
            printHeader();
            System.out.println("Korrekturmodus - Test: " + testName);
            System.out.println();
            System.out.println("Klasse auswaehlen (0 = zurueck, h = Hauptmenue):");
            for (int i = 0; i < classIds.size(); i++) {
                int classId = classIds.get(i);
                String className = getClassDisplayName(classCache.get(classId));
                LinkedHashSet<Integer> studentIdsSet = classToStudents.get(classId);
                int totalStudents = studentIdsSet == null ? 0 : studentIdsSet.size();
                long fullyEvaluated = studentIdsSet == null ? 0 : studentIdsSet.stream()
                        .filter(id -> isStudentFullyEvaluated(answersByStudent.getOrDefault(id, List.of()), bewertungCache))
                        .count();
                System.out.printf("%d) %s (%d Schueler, %d komplett bewertet)%n", i + 1, className, totalStudents, fullyEvaluated);
            }
            System.out.println();
            System.out.print("Auswahl: ");
            String input = readLineAndClear(reader);
            if (input == null) {
                return;
            }
            input = input.trim();
            if (input.equalsIgnoreCase("h")) {
                throw new CorrectionAbortException();
            }
            if (input.equals("0")) {
                return;
            }
            int idx;
            try {
                idx = Integer.parseInt(input) - 1;
            } catch (NumberFormatException e) {
                continue;
            }
            if (idx < 0 || idx >= classIds.size()) {
                continue;
            }

            int classId = classIds.get(idx);
            String className = getClassDisplayName(classCache.get(classId));
            LinkedHashSet<Integer> studentSet = classToStudents.getOrDefault(classId, new LinkedHashSet<>());
            List<Integer> studentIds = new ArrayList<>(studentSet);
            studentIds.sort(Comparator.comparing(id -> getStudentDisplayName(studentCache.get(id)), String.CASE_INSENSITIVE_ORDER));

            while (true) {
                CorrectionTraversalMode mode = promptCorrectionMode(reader, testName, className);
                if (mode == null) {
                    break;
                }
                switch (mode) {
                    case STUDENT -> runStudentTraversal(testName, className, studentIds, studentCache, answersByStudent,
                            bewertungCache, reader, taskCache, gradeScale, maxPoints, gradeReports);
                    case TASK -> runTaskTraversal(testName, className, studentIds, studentCache, answersByStudent,
                            answersByTask, bewertungCache, reader, taskCache, gradeScale, maxPoints, gradeReports);
                }
            }
        }
    }


    private void reviewStudentAnswers(String testName, String className, Map<String, Object> student,
                                      List<Map<String, Object>> answers, Map<Integer, Map<String, Object>> bewertungCache,
                                      BufferedReader reader, Map<Integer, Map<String, Object>> taskCache) throws IOException, SQLException {
        String studentName = getStudentDisplayName(student);
        if (answers.isEmpty()) {
            System.out.println();
            System.out.println("Fuer " + studentName + " liegen keine Antworten vor. Enter...");
            String wait = readLineAndClear(reader);
            if (wait == null) {
                return;
            }
            return;
        }

        answers.sort(Comparator.comparingInt(a -> ((Number) a.get("idAufgabe")).intValue()));

        for (Map<String, Object> answer : answers) {
            EvaluationNavigation navigation = editAnswer(
                    testName,
                    "Klasse: " + className + " | Schueler: " + studentName,
                    answer,
                    bewertungCache,
                    reader,
                    "s",
                    "Aufgabe ueberspringen",
                    "z",
                    "zur Schuelerliste",
                    taskCache
            );
            if (navigation == EvaluationNavigation.BACK) {
                return;
            }
        }
    }

    private void runStudentTraversal(String testName, String className, List<Integer> studentIds,
                                     Map<Integer, Map<String, Object>> studentCache,
                                     Map<Integer, List<Map<String, Object>>> answersByStudent,
                                     Map<Integer, Map<String, Object>> bewertungCache,
                                     BufferedReader reader,
                                     Map<Integer, Map<String, Object>> taskCache,
                                     GradeScale gradeScale, Double maxPoints,
                                     Map<Integer, GradeReport> gradeReports) throws IOException, SQLException {
        while (true) {
            clear();
            printHeader();
            System.out.println("Korrekturmodus - Test: " + testName);
            System.out.println("Klasse: " + className + " | Modus: Schuelerweise");
            System.out.println();
            System.out.println("Schueler auswaehlen (0 = zurueck, h = Hauptmenue):");
            for (int i = 0; i < studentIds.size(); i++) {
                int studentId = studentIds.get(i);
                String studentName = getStudentDisplayName(studentCache.get(studentId));
                List<Map<String, Object>> answers = answersByStudent.getOrDefault(studentId, List.of());
                long evaluated = answers.stream()
                        .map(ans -> ((Number) ans.get("idAntwort")).intValue())
                        .filter(bewertungCache::containsKey)
                        .count();
                System.out.printf("%d) %s (%d/%d Antworten bewertet)%n", i + 1, studentName, evaluated, answers.size());
            }
            System.out.println();
            System.out.print("Auswahl: ");
            String input = readLineAndClear(reader);
            if (input == null) {
                return;
            }
            input = input.trim();
            if (input.equalsIgnoreCase("h")) {
                throw new CorrectionAbortException();
            }
            if (input.equals("0")) {
                return;
            }
            int idx;
            try {
                idx = Integer.parseInt(input) - 1;
            } catch (NumberFormatException e) {
                continue;
            }
            if (idx < 0 || idx >= studentIds.size()) {
                continue;
            }
            int studentId = studentIds.get(idx);
            Map<String, Object> student = studentCache.get(studentId);
            String studentName = getStudentDisplayName(student);
            List<Map<String, Object>> studentAnswers = new ArrayList<>(answersByStudent.getOrDefault(studentId, List.of()));
            reviewStudentAnswers(testName, className, student, studentAnswers, bewertungCache, reader, taskCache);
            maybeDisplayGrade(studentId, studentName, answersByStudent, bewertungCache, gradeScale, maxPoints, gradeReports, reader);
        }
    }
    private void runTaskTraversal(String testName, String className, List<Integer> studentIds,
                                  Map<Integer, Map<String, Object>> studentCache,
                                  Map<Integer, List<Map<String, Object>>> answersByStudent,
                                  Map<Integer, List<Map<String, Object>>> answersByTask,
                                  Map<Integer, Map<String, Object>> bewertungCache,
                                  BufferedReader reader,
                                  Map<Integer, Map<String, Object>> taskCache,
                                  GradeScale gradeScale, Double maxPoints,
                                  Map<Integer, GradeReport> gradeReports) throws IOException, SQLException {
        Set<Integer> taskIdSet = new LinkedHashSet<>();
        for (int studentId : studentIds) {
            List<Map<String, Object>> answers = answersByStudent.getOrDefault(studentId, List.of());
            for (Map<String, Object> answer : answers) {
                Object taskObj = answer.get("idAufgabe");
                if (taskObj instanceof Number number) {
                    taskIdSet.add(number.intValue());
                }
            }
        }
        List<Integer> taskIds = new ArrayList<>(taskIdSet);
        taskIds.sort(Integer::compareTo);
        if (taskIds.isEmpty()) {
            System.out.println();
            System.out.println("Keine Aufgaben mit Antworten fuer diese Klasse gefunden. Enter...");
            String wait = readLineAndClear(reader);
            if (wait == null) {
                return;
            }
            return;
        }
        Set<Integer> studentIdSet = new LinkedHashSet<>(studentIds);

        while (true) {
            clear();
            printHeader();
            System.out.println("Korrekturmodus - Test: " + testName);
            System.out.println("Klasse: " + className + " | Modus: Aufgabenweise");
            System.out.println();
            System.out.println("Aufgabe auswaehlen (0 = zurueck, h = Hauptmenue):");
            for (int i = 0; i < taskIds.size(); i++) {
                int taskId = taskIds.get(i);
                Map<String, Object> task = taskCache.getOrDefault(taskId, Collections.emptyMap());
                String taskTitle = getTaskTitle(task);
                List<Map<String, Object>> answersForTask = answersByTask.getOrDefault(taskId, List.of());
                long evaluated = answersForTask.stream()
                        .filter(ans -> studentIdSet.contains(((Number) ans.get("idSchueler")).intValue()))
                        .map(ans -> ((Number) ans.get("idAntwort")).intValue())
                        .filter(bewertungCache::containsKey)
                        .count();
                System.out.printf("%d) Aufgabe #%d - %s (%d/%d Antworten bewertet)%n",
                        i + 1, taskId, taskTitle, evaluated, studentIds.size());
            }
            System.out.println();
            System.out.print("Auswahl: ");
            String input = readLineAndClear(reader);
            if (input == null) {
                return;
            }
            input = input.trim();
            if (input.equalsIgnoreCase("h")) {
                throw new CorrectionAbortException();
            }
            if (input.equals("0")) {
                return;
            }
            int idx;
            try {
                idx = Integer.parseInt(input) - 1;
            } catch (NumberFormatException e) {
                continue;
            }
            if (idx < 0 || idx >= taskIds.size()) {
                continue;
            }
            int taskId = taskIds.get(idx);
            String taskHeader = getTaskTitle(taskCache.getOrDefault(taskId, Collections.emptyMap()));

            while (true) {
                clear();
                printHeader();
                System.out.println("Korrekturmodus - Test: " + testName);
                System.out.println("Klasse: " + className + " | Aufgabe #" + taskId + " - " + taskHeader);
                System.out.println();
                System.out.println("Schueler auswaehlen (0 = zurueck zur Aufgabenliste, h = Hauptmenue):");
                for (int i = 0; i < studentIds.size(); i++) {
                    int studentId = studentIds.get(i);
                    String studentName = getStudentDisplayName(studentCache.get(studentId));
                    Map<String, Object> answer = findAnswerForStudentTask(studentId, taskId, answersByStudent);
                    String status;
                    if (answer == null) {
                        status = "(keine Antwort)";
                    } else {
                        Object idAntwortObj = answer.get("idAntwort");
                        boolean evaluated = idAntwortObj instanceof Number idAntwortNumber
                                && bewertungCache.containsKey(idAntwortNumber.intValue());
                        status = evaluated ? "(bewertet)" : "(offen)";
                    }
                    System.out.printf("%d) %s %s%n", i + 1, studentName, status);
                }
                System.out.println();
                System.out.print("Auswahl: ");
                String studentInput = readLineAndClear(reader);
                if (studentInput == null) {
                    return;
                }
                studentInput = studentInput.trim();
                if (studentInput.equalsIgnoreCase("h")) {
                    throw new CorrectionAbortException();
                }
                if (studentInput.equals("0")) {
                    break;
                }
                int studentIdx;
                try {
                    studentIdx = Integer.parseInt(studentInput) - 1;
                } catch (NumberFormatException e) {
                    continue;
                }
                if (studentIdx < 0 || studentIdx >= studentIds.size()) {
                    continue;
                }
                int studentId = studentIds.get(studentIdx);
                String studentName = getStudentDisplayName(studentCache.get(studentId));
                Map<String, Object> answer = findAnswerForStudentTask(studentId, taskId, answersByStudent);
                if (answer == null) {
                    System.out.println();
                    System.out.println("Fuer diesen Schueler liegt keine Antwort vor. Enter...");
                    String wait = readLineAndClear(reader);
                    if (wait == null) {
                        return;
                    }
                    continue;
                }
                EvaluationNavigation navigation = editAnswer(
                        testName,
                        "Klasse: " + className + " | Aufgabe #" + taskId + " | Schueler: " + studentName,
                        answer,
                        bewertungCache,
                        reader,
                        "s",
                        "naechsten Schueler ueberspringen",
                        "b",
                        "zur Schuelerliste",
                        taskCache
                );
                if (navigation == EvaluationNavigation.BACK) {
                    continue;
                }
                maybeDisplayGrade(studentId, studentName, answersByStudent, bewertungCache, gradeScale, maxPoints, gradeReports, reader);
            }
        }
    }

    private EvaluationNavigation editAnswer(String testName, String headerSubtitle, Map<String, Object> answer,
                                            Map<Integer, Map<String, Object>> bewertungCache, BufferedReader reader,
                                            String skipToken, String skipLabel, String backToken, String backLabel,
                                            Map<Integer, Map<String, Object>> taskCache) throws IOException, SQLException {
        clear();
        printHeader();
        System.out.println("Korrekturmodus - Test: " + testName);
        System.out.println(headerSubtitle);
        System.out.println();

        int aufgabeId = ((Number) answer.get("idAufgabe")).intValue();
        Map<String, Object> aufgabe = taskCache.computeIfAbsent(aufgabeId, id -> safeDbGet("Aufgabe", id));
        String aufgabeText = String.valueOf(aufgabe.getOrDefault("aufgabeMarkdown", "")).trim();
        if (aufgabeText.isEmpty()) {
            aufgabeText = String.valueOf(aufgabe.getOrDefault("aufgabeText", "")).trim();
        }
        String loesung = String.valueOf(aufgabe.getOrDefault("loesung", "")).trim();
        String antwortText = String.valueOf(answer.getOrDefault("antwort", "")).trim();

        System.out.println("Aufgabe #" + aufgabeId + ":");
        System.out.println(aufgabeText.isEmpty() ? "(kein Aufgabentext hinterlegt)" : aufgabeText);
        if (!loesung.isEmpty()) {
            System.out.println();
            System.out.println("Erwartete Loesung: " + loesung);
        }
        System.out.println();
        System.out.println("Abgegebene Antwort: " + (antwortText.isEmpty() ? "(keine Antwort abgegeben)" : antwortText));

        int antwortId = ((Number) answer.get("idAntwort")).intValue();
        Map<String, Object> existing = bewertungCache.get(antwortId);
        if (existing == null) {
            existing = db.getEntryWhere("Bewertung", "idAntwort", antwortId);
            if (existing != null) {
                bewertungCache.put(antwortId, existing);
            }
        }

        String currentBewertung = existing != null && existing.get("bewertung") != null
                ? existing.get("bewertung").toString()
                : "";
        String currentKommentar = existing != null && existing.get("kommentar") != null
                ? existing.get("kommentar").toString()
                : "";
        Double currentPunkte = null;
        if (existing != null) {
            Object punkteObj = existing.get("punkte");
            if (punkteObj instanceof Number punkteNumber) {
                currentPunkte = punkteNumber.doubleValue();
            } else if (punkteObj instanceof CharSequence sequence) {
                try {
                    currentPunkte = Double.parseDouble(sequence.toString());
                } catch (NumberFormatException ignored) {
                }
            }
        }

        System.out.println();
        System.out.println("Aktuelle Bewertung: " + (currentBewertung.isBlank() ? "(keine)" : currentBewertung));
        if (!currentKommentar.isBlank()) {
            System.out.println("Kommentar: " + currentKommentar);
        }
        if (currentPunkte != null) {
            System.out.println("Punkte: " + currentPunkte);
        }

        System.out.println();
        System.out.println("Optionen: '" + skipToken + "' = " + skipLabel + ", '" + backToken + "' = " + backLabel + ", 'h' = Hauptmenue.");
        System.out.print("Neue Bewertung (Enter = unveraendert, '" + backToken + "' = " + backLabel + ", 'h' = Hauptmenue): ");
        String bewertungInput = readLineAndClear(reader);
        if (bewertungInput == null) {
            return EvaluationNavigation.CONTINUE;
        }
        bewertungInput = bewertungInput.trim();
        if (bewertungInput.equalsIgnoreCase("h")) {
            throw new CorrectionAbortException();
        }
        if (bewertungInput.equalsIgnoreCase(backToken)) {
            return EvaluationNavigation.BACK;
        }
        if (bewertungInput.equalsIgnoreCase(skipToken)) {
            return EvaluationNavigation.CONTINUE;
        }
        String newBewertung = bewertungInput.isEmpty() ? currentBewertung : bewertungInput;

        System.out.print("Kommentar (Enter = unveraendert, '-' = loeschen): ");
        String kommentarInput = readLineAndClear(reader);
        if (kommentarInput == null) {
            kommentarInput = "";
        }
        kommentarInput = kommentarInput.trim();
        String newKommentar;
        if (kommentarInput.isEmpty()) {
            newKommentar = currentKommentar;
        } else if (kommentarInput.equals("-")) {
            newKommentar = "";
        } else {
            newKommentar = kommentarInput;
        }

        System.out.print("Punkte (Enter = unveraendert, '-' = loeschen): ");
        String punkteInput = readLineAndClear(reader);
        Double newPunkte = currentPunkte;
        if (punkteInput != null) {
            punkteInput = punkteInput.trim();
            if (!punkteInput.isEmpty()) {
                if (punkteInput.equals("-")) {
                    newPunkte = null;
                } else {
                    String normalizedInput = punkteInput.replace(',', '.');
                    try {
                        newPunkte = Double.parseDouble(normalizedInput);
                    } catch (NumberFormatException nf) {
                        System.out.println("Ungueltige Punkteingabe. Wert bleibt unveraendert.");
                        String wait = readLineAndClear(reader);
                        if (wait == null) {
                            return EvaluationNavigation.CONTINUE;
                        }
                        newPunkte = currentPunkte;
                    }
                }
            }
        }

        boolean changed = !Objects.equals(newBewertung, currentBewertung)
                || !Objects.equals(newKommentar, currentKommentar)
                || !Objects.equals(newPunkte, currentPunkte);

        if (!changed) {
            System.out.println();
            System.out.println("Keine Aenderungen vorgenommen. Enter fuer naechste Bewertung...");
            String wait = readLineAndClear(reader);
            if (wait == null) {
                return EvaluationNavigation.CONTINUE;
            }
            return EvaluationNavigation.CONTINUE;
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        db.upsertBewertung(
                antwortId,
                newBewertung.isBlank() ? null : newBewertung,
                newKommentar.isBlank() ? null : newKommentar,
                newPunkte,
                timestamp
        );
        Map<String, Object> refreshed = db.getEntryWhere("Bewertung", "idAntwort", antwortId);
        if (refreshed != null) {
            bewertungCache.put(antwortId, refreshed);
        } else {
            bewertungCache.remove(antwortId);
        }

        System.out.println();
        System.out.println("Bewertung gespeichert. Enter fuer naechste Bewertung...");
        String wait = readLineAndClear(reader);
        if (wait == null) {
            return EvaluationNavigation.CONTINUE;
        }
        return EvaluationNavigation.CONTINUE;
    }

    private Map<String, Object> findAnswerForStudentTask(int studentId, int taskId,
                                                         Map<Integer, List<Map<String, Object>>> answersByStudent) {
        List<Map<String, Object>> answers = answersByStudent.get(studentId);
        if (answers == null) {
            return null;
        }
        for (Map<String, Object> answer : answers) {
            Object taskObj = answer.get("idAufgabe");
            if (taskObj instanceof Number number && number.intValue() == taskId) {
                return answer;
            }
        }
        return null;
    }

    private String getTaskTitle(Map<String, Object> task) {
        if (task == null || task.isEmpty()) {
            return "(ohne Titel)";
        }
        String text = String.valueOf(task.getOrDefault("aufgabeMarkdown", "")).trim();
        if (text.isEmpty()) {
            text = String.valueOf(task.getOrDefault("aufgabeText", "")).trim();
        }
        if (text.isEmpty()) {
            text = String.valueOf(task.getOrDefault("frage", "")).trim();
        }
        if (text.isEmpty()) {
            return "(ohne Titel)";
        }
        text = text.replace('\r', ' ').replace('\n', ' ').replaceAll("\s+", " ").trim();
        return text.length() > 60 ? text.substring(0, 57) + "..." : text;
    }

    private CorrectionTraversalMode promptCorrectionMode(BufferedReader reader, String testName, String className) throws IOException {
        while (true) {
            clear();
            printHeader();
            System.out.println("Korrekturmodus - Test: " + testName);
            System.out.println("Klasse: " + className);
            System.out.println();
            System.out.println("1) Schuelerweise bewerten");
            System.out.println("2) Aufgabenweise bewerten");
            System.out.println("0) Zurueck zur Klassenliste");
            System.out.println("h) Hauptmenue");
            System.out.println();
            System.out.print("Auswahl: ");
            String input = readLineAndClear(reader);
            if (input == null) {
                return null;
            }
            input = input.trim();
            if (input.equalsIgnoreCase("h")) {
                throw new CorrectionAbortException();
            }
            if (input.equals("0") || input.isEmpty()) {
                return null;
            }
            if (input.equals("1")) {
                return CorrectionTraversalMode.STUDENT;
            }
            if (input.equals("2")) {
                return CorrectionTraversalMode.TASK;
            }
        }
    }

    private GradeSetup promptGradeSetup(BufferedReader reader, String testName) throws IOException {
        List<GradeScale> scales = loadGradeScales();
        if (scales.isEmpty()) {
            return new GradeSetup(null, null);
        }
        while (true) {
            clear();
            printHeader();
            System.out.println("Optional: Bewertungsmassstab fuer den Test \"" + testName + "\" waehlen.");
            System.out.println();
            System.out.println("0) Keine Note berechnen");
            for (int i = 0; i < scales.size(); i++) {
                GradeScale scale = scales.get(i);
                System.out.printf("%d) %s%n", i + 1, scale.displayName());
            }
            System.out.println();
            System.out.print("Auswahl: ");
            String input = readLineAndClear(reader);
            if (input == null) {
                return new GradeSetup(null, null);
            }
            input = input.trim();
            if (input.equalsIgnoreCase("h")) {
                throw new CorrectionAbortException();
            }
            if (input.equals("0") || input.isEmpty()) {
                return new GradeSetup(null, null);
            }
            int idx;
            try {
                idx = Integer.parseInt(input) - 1;
            } catch (NumberFormatException e) {
                continue;
            }
            if (idx < 0 || idx >= scales.size()) {
                continue;
            }
            GradeScale selected = scales.get(idx);
            Double maxPoints = promptMaxPoints(reader, selected, testName);
            if (maxPoints == null) {
                return new GradeSetup(null, null);
            }
            return new GradeSetup(selected, maxPoints);
        }
    }

    private Double promptMaxPoints(BufferedReader reader, GradeScale scale, String testName) throws IOException {
        while (true) {
            clear();
            printHeader();
            System.out.println("Bewertungsmassstab: " + scale.displayName());
            System.out.println();
            System.out.print("Maximal erreichbare Punkte fuer \"" + testName + "\" (z.B. 60, '0' = abbrechen): ");
            String input = readLineAndClear(reader);
            if (input == null) {
                return null;
            }
            input = input.trim();
            if (input.equalsIgnoreCase("h")) {
                throw new CorrectionAbortException();
            }
            if (input.equals("0")) {
                return null;
            }
            if (input.isEmpty()) {
                continue;
            }
            String normalized = input.replace(',', '.');
            try {
                double value = Double.parseDouble(normalized);
                if (value > 0) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
            }
            System.out.println();
            System.out.println("Bitte eine positive Zahl eingeben. Enter...");
            String wait = readLineAndClear(reader);
            if (wait == null) {
                return null;
            }
        }
    }

    private void maybeDisplayGrade(int studentId, String studentName,
                                   Map<Integer, List<Map<String, Object>>> answersByStudent,
                                   Map<Integer, Map<String, Object>> bewertungCache,
                                   GradeScale gradeScale, Double maxPoints,
                                   Map<Integer, GradeReport> gradeReports,
                                   BufferedReader reader) throws IOException {
        if (gradeScale == null || maxPoints == null || maxPoints <= 0) {
            return;
        }
        List<Map<String, Object>> answers = answersByStudent.get(studentId);
        if (answers == null || answers.isEmpty()) {
            return;
        }
        double totalPoints = 0.0;
        for (Map<String, Object> answer : answers) {
            Object idAntwortObj = answer.get("idAntwort");
            if (!(idAntwortObj instanceof Number idAntwortNumber)) {
                return;
            }
            int idAntwort = idAntwortNumber.intValue();
            Map<String, Object> evaluation = bewertungCache.get(idAntwort);
            if (evaluation == null) {
                return;
            }
            Object punkteObj = evaluation.get("punkte");
            if (punkteObj instanceof Number punkteNumber) {
                totalPoints += punkteNumber.doubleValue();
            } else if (punkteObj instanceof CharSequence sequence && !sequence.toString().isBlank()) {
                try {
                    totalPoints += Double.parseDouble(sequence.toString());
                } catch (NumberFormatException ignored) {
                }
            } else {
                return;
            }
        }
        double percentage = maxPoints <= 0 ? 0.0 : (totalPoints / maxPoints) * 100.0;
        percentage = Math.max(0.0, Math.min(percentage, 100.0));
        String grade = gradeScale.gradeFor(percentage);
        GradeReport current = new GradeReport(totalPoints, percentage, grade);
        GradeReport previous = gradeReports.get(studentId);
        if (previous != null && previous.isEquivalent(current)) {
            return;
        }
        gradeReports.put(studentId, current);
        System.out.println();
        System.out.printf("Alle Antworten von %s sind bewertet. Ergebnis: %.2f / %.2f Punkte (%.1f%%) -> Note: %s%n",
                studentName, totalPoints, maxPoints, percentage, grade);
        System.out.println("Enter...");
        String wait = readLineAndClear(reader);
        if (wait == null) {
            return;
        }
    }

    private List<GradeScale> loadGradeScales() {
        Path directory = Paths.get("data", "bewertungsmassstaebe");
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<GradeScale> scales = new ArrayList<>();
        try (Stream<Path> stream = Files.list(directory)) {
            stream.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .forEach(path -> {
                        try {
                            GradeScale scale = parseGradeScale(path);
                            if (scale != null) {
                                scales.add(scale);
                            }
                        } catch (IOException e) {
                            log.writeLog("Server", boundIp, "ERROR|GRADE_SCALE|" + path.getFileName() + "|" + e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.writeLog("Server", boundIp, "ERROR|GRADE_SCALE_DIR|" + e.getMessage());
        }
        return scales;
    }

    private GradeScale parseGradeScale(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        NavigableMap<Double, String> thresholds = new TreeMap<>();
        String displayName = null;
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#")) {
                if (displayName == null && line.length() > 1) {
                    displayName = line.substring(1).trim();
                }
                continue;
            }
            String[] parts = line.split(";", 2);
            if (parts.length < 2) {
                continue;
            }
            String thresholdPart = parts[0].trim().replace(',', '.');
            String gradePart = parts[1].trim();
            if (gradePart.isEmpty()) {
                continue;
            }
            try {
                double threshold = Double.parseDouble(thresholdPart);
                thresholds.put(threshold, gradePart);
            } catch (NumberFormatException ignored) {
            }
        }
        if (thresholds.isEmpty()) {
            return null;
        }
        String filename = path.getFileName().toString();
        int dotIndex = filename.lastIndexOf('.');
        String id = dotIndex > 0 ? filename.substring(0, dotIndex) : filename;
        if (displayName == null || displayName.isBlank()) {
            displayName = id;
        }
        return new GradeScale(id, displayName, thresholds);
    }

    private enum EvaluationNavigation {
        CONTINUE,
        BACK
    }

    private enum CorrectionTraversalMode {
        STUDENT,
        TASK
    }

    private record GradeSetup(GradeScale scale, Double maxPoints) {
    }

    private static final class GradeReport {
        private final double points;
        private final double percentage;
        private final String grade;

        GradeReport(double points, double percentage, String grade) {
            this.points = points;
            this.percentage = percentage;
            this.grade = grade;
        }

        boolean isEquivalent(GradeReport other) {
            if (other == null) {
                return false;
            }
            return Math.abs(points - other.points) < 0.01
                    && Math.abs(percentage - other.percentage) < 0.01
                    && Objects.equals(grade, other.grade);
        }
    }

    private static final class GradeScale {
        private final String id;
        private final String displayName;
        private final NavigableMap<Double, String> thresholds;

        GradeScale(String id, String displayName, NavigableMap<Double, String> thresholds) {
            this.id = id;
            this.displayName = displayName;
            this.thresholds = Collections.unmodifiableNavigableMap(new TreeMap<>(thresholds));
        }

        String displayName() {
            return displayName;
        }

        String gradeFor(double percentage) {
            double normalized = Math.max(0.0, percentage);
            Map.Entry<Double, String> entry = thresholds.floorEntry(normalized);
            if (entry != null) {
                return entry.getValue();
            }
            return thresholds.firstEntry().getValue();
        }
    }


    private Map<String, Object> safeDbGet(String table, int id) {
        try {
            return db.getEntry(table, id);
        } catch (SQLException e) {
            log.writeLog("Server", boundIp, "ERROR|DB_FETCH|" + table + "#" + id + "|" + e.getMessage());
            return Collections.emptyMap();
        }
    }

    private String getClassDisplayName(Map<String, Object> klasse) {
        if (klasse == null || klasse.isEmpty()) return "(unbekannte Klasse)";
        Object name = klasse.get("klassenname");
        if (name == null) return "(unbekannte Klasse)";
        String value = name.toString().trim();
        return value.isEmpty() ? "(unbenannte Klasse)" : value;
    }

    private String getStudentDisplayName(Map<String, Object> student) {
        if (student == null || student.isEmpty()) return "(unbekannter Schueler)";
        String vorname = String.valueOf(student.getOrDefault("vorname", "")).trim();
        String nachname = String.valueOf(student.getOrDefault("nachname", "")).trim();
        String combined = (vorname + " " + nachname).trim();
        if (!combined.isEmpty()) return combined;
        Object idObj = student.get("idSchueler");
        return idObj == null ? "(unbekannter Schueler)" : "Schueler " + idObj;
    }

    private boolean isStudentFullyEvaluated(List<Map<String, Object>> answers, Map<Integer, Map<String, Object>> bewertungCache) {
        if (answers == null || answers.isEmpty()) {
            return false;
        }
        for (Map<String, Object> answer : answers) {
            Object idObj = answer.get("idAntwort");
            if (!(idObj instanceof Number idAntwortValue)) {
                return false;
            }
            int idAntwort = idAntwortValue.intValue();
            if (!bewertungCache.containsKey(idAntwort)) {
                return false;
            }
        }
        return true;
    }

    private static final class CorrectionAbortException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    /**
     * @title generateAndSaveTokensForClass
     * @short Generiert Tokens fÃƒÆ’Ã‚Â¼r alle SchÃƒÆ’Ã‚Â¼ler der Klasse und speichert sie als Datei.
     * @args int classId, String className
     */
    private String generateAndSaveTokensForClass(int classId, String className, boolean interactive) throws IOException, SQLException {
        List<Map<String, Object>> schueler = db.getAllEntries("Schueler");
        List<Map<String, Object>> schuelerInKlasse = schueler.stream()
                .filter(s -> ((int) s.get("idKlasse")) == classId)
                .toList();

        tokenToSchuelerId.clear();
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> s : schuelerInKlasse) {
            int schuelerId = (int) s.get("idSchueler");
            String vorname = String.valueOf(s.get("vorname"));
            String nachname = String.valueOf(s.get("nachname"));
            String token = generateRandomToken();
            tokenToSchuelerId.put(token, schuelerId);
            lines.add(String.format("%s %s;%d;%s", vorname, nachname, schuelerId, token));
        }

        Files.createDirectories(Paths.get("data/tokenlists"));
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String filename = String.format("data/tokenlists/%s_%s.csv", timestamp, className.replaceAll("\\s+", "_"));
        Files.write(Paths.get(filename), lines);

        if (interactive) {
            System.out.println("Tokenliste fuer die Klasse wurde erstellt: " + filename);
            System.out.println("Bitte drucken und an die Schueler verteilen. Enter...");
            waitForEnterAndClear();
        }
        return filename;
    }

    /**
     * @title generateRandomToken
     * @short Erzeugt einen zufÃƒÆ’Ã‚Â¤lligen 6-stelligen Token.
     * @args keine
     */
    private String generateRandomToken() {
        Random rnd = new Random();
        int num = 100000 + rnd.nextInt(900000);
        return String.valueOf(num);
    }

    /**
     * @title startBroadcast
     * @short Startet Broadcast-Thread.
     * @args keine
     */
    private void startBroadcast() {
        stopBroadcast();
        broadcastThread = new Thread(this::broadcastLoop, "BroadcastListener");
        broadcastThread.start();
        log.writeLog("Server", boundIp, "START|THREAD|BroadcastListener");
    }

    /**
     * @title stopBroadcast
     * @short Stoppt Broadcast.
     * @args keine
     */
    private void stopBroadcast() {
        if (broadcastThread != null && broadcastThread.isAlive()) {
            broadcastThread.interrupt();
            try {
                broadcastThread.join(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            log.writeLog("Server", boundIp, "STOP|THREAD|BroadcastListener");
        }
    }

    /**
     * @title resolveLocalIPv4
     * @short Sucht brauchbare lokale IPv4 (nicht Loopback/virtuell).
     * @args keine
     */
    private static String resolveLocalIPv4() {
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics.hasMoreElements()) {
                NetworkInterface ni = nics.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address) return a.getHostAddress();
                }
            }
        } catch (SocketException | SecurityException ignored) {
            // ignored, fallback below
        }
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            return "127.0.0.1";
        }
    }

    /**
     * @title clear
     * @short Konsole leeren (auÃƒÆ’Ã…Â¸er -history).
     * @args keine
     */
    private void clear() {
        if (!consoleMode || keepHistory) {
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
            log.writeLog("Server", boundIp, "WARN|CLEAR|" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * @title printHeader
     * @short Headline inkl. IP.
     * @args keine
     */
    private void printHeader() {
        System.out.println("Lernstands-ÃƒÆ’Ã…â€œberprÃƒÆ’Ã‚Â¼fungs-Software");
        System.out.println("Server IPV4: " + boundIp);
        System.out.println();
    }

    /**
     * @title scanDbForTestNames
     * @short Liefert alle TestName-EintrÃƒÆ’Ã‚Â¤ge.
     * @args keine
     */
    private List<String> scanDbForTestNames() throws SQLException {
        List<Map<String, Object>> tests = db.getAllEntries("Test");
        List<String> names = new ArrayList<>();
        for (Map<String, Object> test : tests) {
            Object typ = test.get("typ");
            if (typ != null) names.add(typ.toString());
        }
        return names;
    }

    /**
     * @title loadTestFromDb
     * @short LÃƒÆ’Ã‚Â¤dt einen Test aus der Datenbank.
     * @args String testTyp
     */
    private Test loadTestFromDb(String testTyp) throws SQLException {
        // Hole den Test mit passendem Typ
        List<Map<String, Object>> tests = db.getAllEntries("Test");
        Map<String, Object> testEntry = null;
        for (Map<String, Object> t : tests) {
            if (testTyp.equals(t.get("typ"))) {
                testEntry = t;
                break;
            }
        }
        if (testEntry == null) throw new IllegalStateException("Test nicht gefunden: " + testTyp);

        int idTest = (int) testEntry.get("idTest");

        // Hole alle Aufgaben, die zu diesem Test gehÃƒÆ’Ã‚Â¶ren
        List<Map<String, Object>> ctTestAufgabe = db.getAllEntries("CT_TestAufgabeLoesung");
        List<Task> tasks = new ArrayList<>();
        for (Map<String, Object> ct : ctTestAufgabe) {
            if (idTest == ((int) ct.get("idTest"))) {
                int idAufgabe = (int) ct.get("idAufgabe");
                Map<String, Object> aufgabe = db.getEntry("Aufgabe", idAufgabe);
                String aufgabeText = aufgabe == null ? "" : String.valueOf(aufgabe.getOrDefault("aufgabeMarkdown", ""));
                String loesung = aufgabe == null ? "" : String.valueOf(aufgabe.getOrDefault("loesung", ""));
                String aufgabenTyp = aufgabe == null ? "" : String.valueOf(aufgabe.getOrDefault("typ", ""));
                List<String> answers = new ArrayList<>();
                if (!loesung.isEmpty()) answers.add(loesung);
                tasks.add(new Task("task" + idAufgabe, aufgabeText, answers, aufgabenTyp));
            }
        }
        return new Test(testTyp, tasks);
    }

    /**
     * @title ClientHandler
     * @short TCP-CommunicationListener je Client.
     * @args String clientId, Socket socket
     */
    private class ClientHandler {
        volatile String clientId;
        final Socket socket;
        final String remoteIp;
        volatile String status = "Warteraum";
        volatile Test test;
        volatile int currentTaskIndex = 0;
        volatile String token = ""; // personalisierter Token des Clients
        volatile boolean handRaised = false;
        volatile long handRaisedAt = 0L;
        DataOutputStream output;
        final Thread t;

        ClientHandler(String id, Socket s) { this.clientId = id; this.socket = s; this.remoteIp = s.getInetAddress().getHostAddress(); this.t = new Thread(this::run, "CommunicationListener-"+remoteIp); }

        /** @title start @short Startet Thread. @args keine */
        void start(){ t.start(); log.writeLog("Server", remoteIp, "START|THREAD|CommunicationListener"); }

        /**
         * @title run
         * @short Liest Nachrichten und reagiert (AUTH/NEXT_TASK/SEND_ANSWER/... + ASK_TASK_BY_ID/ASK_OVERVIEW).
         * @args keine
         */
        void run() {
            try (DataInputStream in = new DataInputStream(socket.getInputStream());
                 DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {
                this.output = out;
                while (running && !socket.isClosed()) {
                    Message msg = Message.read(in);
                    if (msg == null) break;

                    switch (msg.messageType) {
                        case "AUTH" -> {
                            String incomingToken = msg.token;
                            // Token-Validierung
                            if (!tokenToSchuelerId.containsKey(incomingToken)) {
                                send(new Message("SERVER", "AUTH_ERR", incomingToken, List.of("fehlernachricht", "Ungueltiger Token. Bitte erneut eingeben.")));
                                continue;
                            }
                            this.token = incomingToken;
                            this.clientId = "Client" + (++clientCounter);
                            this.status = "Verbunden";
                            this.handRaised = false;
                            this.handRaisedAt = 0L;
                            this.test = selectedTest;
                            this.currentTaskIndex = 0;
                            clients.put(this.clientId, this);
                            clientsTableDirty = true;

                            int taskCount = this.test != null && this.test.tasks != null ? this.test.tasks.size() : 0;
                            String announcedTestName = this.test != null && this.test.name != null ? this.test.name : selectedTestName;
                            // AUTH_OK mit Testname und Aufgabenanzahl
                            send(new Message("SERVER", "AUTH_OK", incomingToken, List.of(
                                    "clientId", this.clientId,
                                    "countOfTasks", String.valueOf(taskCount),
                                    "testName", announcedTestName == null ? "" : announcedTestName
                            )));
                        }
                        case "NEXT_TASK" -> { sendNextTask(this); }
                        case "SEND_ANSWER" -> {
                            String taskId = msg.getString("taskId");
                            String antwort = msg.getString("antwort");
                            int schuelerId = tokenToSchuelerId.getOrDefault(this.token, -1);
                            int testId = getTestIdByName(selectedTestName);
                            int aufgabeId = -1;
                            // Finde Aufgabe-ID anhand taskId
                            for (Task task : test.tasks) {
                                if (task.id.equals(taskId)) {
                                    try {
                                        aufgabeId = Integer.parseInt(task.id.replace("task", ""));
                                    } catch (NumberFormatException ignored) {}
                                    break;
                                }
                            }
                            if (schuelerId != -1 && testId != -1 && aufgabeId != -1) {
                                // Antwort speichern
                                try {
                                    db.setEntry("Antwort", Map.of(
                                            "idSchueler", schuelerId,
                                            "idTest", testId,
                                            "idAufgabe", aufgabeId,
                                            "antwort", antwort
                                    ));
                                } catch (SQLException e) {
                                    log.writeLog("Server", remoteIp, "ERROR|DB_ANSWER|" + e.getMessage());
                                }
                            }
                        }
                        case "ASK_OVERVIEW" -> { sendOverview(this); }
                        case "ASK_TASK_BY_ID" -> {
                            String taskId = msg.getString("taskId");
                            for (Task task : test.tasks) {
                                if (task.id.equals(taskId)) {
                                    sendTask(this, task, true);
                                    break;
                                }
                            }
                        }
                        case "SEND_DELIVER" -> {
                            this.status = "Abgegeben";
                            sendOverview(this); // ÃƒÆ’Ã…â€œbersicht nochmal anzeigen
                        }
                        case "WARNING" -> {
                            String reason = msg.getString("grund");
                            String timestamp = msg.getString("zeitstempel");
                            registerWarning(this, reason, timestamp);
                        }
                        case "RAISE_HAND" -> markHandRaised();
                        case "LOWER_HAND" -> clearHandRaised();
                        default -> {}
                    }
                }
            } catch (IOException e) {
                log.writeLog("Server", remoteIp, "ERROR|CLIENT|" + e.getMessage());
            } finally {
                this.output = null;
                close();
            }
        }

        /** @title resetForNewRun @short Setzt Status & Testzeiger. @args Test t */
        void resetForNewRun(Test t){
            this.test = t;
            this.currentTaskIndex = 0;
            this.status = "Test laeuft";
            clearHandRaised();
        }

        /** @title buildOverviewPayload @short Erzeugt ÃƒÆ’Ã…â€œbersicht (Fragenliste). @args keine */
        String buildOverviewPayload(){
            return test.tasks.stream()
                    .map(x -> x.id+"::"+x.question)
                    .collect(Collectors.joining("||"));
        }

        /** @title send @short Sendet Message (optional Preview). @args Message msg, boolean isPreview */
        void send(Message msg, boolean isPreview){
            try {
                DataOutputStream channel = this.output;
                if (channel == null) {
                    channel = new DataOutputStream(socket.getOutputStream());
                    this.output = channel;
                }
                Message.write(channel, msg);
                // Bei Preview KEIN Fortschritt
            } catch (IOException e){
                log.writeLog("Network", remoteIp, "ERROR|SEND|"+e.getMessage());
            }
        }

        /** @title send (ohne Preview) @short Convenience. @args Message msg */
        void send(Message msg){ send(msg, false); }

        /** @title close @short Schliesst Socket & entfernt aus Map. @args keine */
        void close(){
            try {
                DataOutputStream channel = this.output;
                if (channel != null) {
                    channel.close();
                }
            } catch (IOException ignored) {
                // ignore during shutdown
            } finally {
                this.output = null;
            }
            try {
                socket.close();
            } catch (IOException ignored) {
                // ignore during shutdown
            }
            clearHandRaised();
            clients.values().removeIf(v -> v == this);
            clientsTableDirty = true;
        }

        private void markHandRaised() {
            this.handRaised = true;
            this.handRaisedAt = System.currentTimeMillis();
            clientsTableDirty = true;
        }

        private void clearHandRaised() {
            if (!this.handRaised) {
                return;
            }
            this.handRaised = false;
            this.handRaisedAt = 0L;
            clientsTableDirty = true;
        }
    }

    /**
     * @title broadcastLoop
     * @short Sendet UDP-Announce mit Testname & Port.
     * @args keine
     */
    private void broadcastLoop() {
        try (DatagramSocket ds = new DatagramSocket()) {
            ds.setBroadcast(true);
            InetAddress target = InetAddress.getByName(BROADCAST_ADDR);
            while (state == DashboardState.HOSTING && running) {
                String payload = "ipv4=" + boundIp + ";name=Lernstands-Server;desc=Testserver;tcp=" + TCP_PORT + ";token=" + DEFAULT_TOKEN + ";test=" + selectedTestName;
                byte[] data = payload.getBytes(StandardCharsets.UTF_8);
                DatagramPacket packet = new DatagramPacket(data, data.length, target, UDP_BROADCAST_PORT);
                ds.send(packet);
                try {
                    TimeUnit.SECONDS.sleep(1);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (IOException e) {
            if (running) {
                log.writeLog("Server", boundIp, "ERROR|BROADCAST|" + e.getMessage());
            }
        }
    }

    /**
     * @title broadcastToAll
     * @short Sendet Nachricht an alle Clients (mit deren Token).
     * @args Message template
     */
    private void broadcastToAll(Message template) {
        for (ClientHandler ch : clients.values()) {
            Message m = new Message(template.sender, template.messageType, ch.token, template.args.keySet().stream().flatMap(k -> Arrays.stream(new String[]{k, template.args.get(k)})).toList());
            ch.send(m);
        }
    }

    /**
     * @title renderClientsTable
     * @short Zeigt die verbundenen Clients im Warteraum/Testlauf.
     * @args keine
     */
    private void renderClientsTable() {
        System.out.println("+----------------+-----------------+-------------+");
        System.out.println("| SchÃƒÆ’Ã‚Â¼ler        | IP-Adresse      | Status      |");
        System.out.println("+----------------+-----------------+-------------+");
        for (ClientHandler ch : clients.values()) {
            String schuelerName = getSchuelerNameByToken(ch.token);
            System.out.printf("| %-14s | %-15s | %-11s |\n", schuelerName, ch.remoteIp, ch.status);
        }
        System.out.println("+----------------+-----------------+-------------+");
    }

    // Hilfsmethode fÃƒÆ’Ã‚Â¼r SchÃƒÆ’Ã‚Â¼lername anhand Token
    private String getSchuelerNameByToken(String token) {
        Integer schuelerId = tokenToSchuelerId.get(token);
        if (schuelerId == null) {
            return "(unbekannt)";
        }
        try {
            Map<String, Object> schueler = db.getEntry("Schueler", schuelerId);
            if (schueler == null || schueler.isEmpty()) {
                return "(unbekannt)";
            }
            String vorname = String.valueOf(schueler.getOrDefault("vorname", "")).trim();
            String nachname = String.valueOf(schueler.getOrDefault("nachname", "")).trim();
            String name = (vorname + " " + nachname).trim();
            return name.isEmpty() ? "(unbekannt)" : name;
        } catch (SQLException e) {
            log.writeLog("Server", boundIp, "ERROR|DB_FETCH|Schueler#" + schuelerId + "|" + e.getMessage());
            return "(unbekannt)";
        }
    }

    /**
     * @title handleHostingSelection
     * @short MenÃƒÆ’Ã‚Â¼auswahl im Hosting-Modus.
     * @args String sel
     */
    private void handleHostingSelection(String sel) {
        String s = sel == null ? "" : sel.trim();
        switch (s) {
            case "1" -> {
                // Test starten
                state = DashboardState.RUNNING;
                stopBroadcast();
                // Aufgaben an alle verbundenen SchÃƒÆ’Ã‚Â¼ler schicken
                for (ClientHandler ch : clients.values()) {
                    ch.resetForNewRun(selectedTest);
                    sendNextTask(ch);
                    ch.status = "Testlauf";
                }
                clientsTableDirty = true;
            }
            case "2" -> {
                // Testat abbrechen
                state = DashboardState.ROOT;
                stopBroadcast();
                for (ClientHandler ch : clients.values()) {
                    ch.close();
                }
                clients.clear();
                clientsTableDirty = true;
            }
            default -> {}
        }
    }

    /**
     * @title sendNextTask
     * @short Sendet die nÃƒÆ’Ã‚Â¤chste Aufgabe an den Client.
     * @args ClientHandler ch
     */
    private void sendNextTask(ClientHandler ch) {
        if (ch.test == null || ch.currentTaskIndex >= ch.test.tasks.size()) {
            // Nach letzter Aufgabe: ÃƒÆ’Ã…â€œbersicht schicken
            sendOverview(ch);
            return;
        }
        Task t = ch.test.tasks.get(ch.currentTaskIndex);
        sendTask(ch, t, false, ch.currentTaskIndex + 1); // taskNr ist 1-basiert
        ch.currentTaskIndex++;
    }

    /**
     * @title sendTask
     * @short Sendet eine Aufgabe an den Client.
     * @args ClientHandler ch, Task t, boolean isPreview, int taskNr
     */
    private void sendTask(ClientHandler ch, Task t, boolean isPreview, int taskNr) {
        Message msg = new Message("SERVER", "SEND_TASK", ch.token, List.of(
                "taskId", t.id,
                "taskNr", String.valueOf(taskNr),
                "question", t.question,
                "task", t.question,
                "taskType", t.type == null ? "" : t.type,
                "answers", String.join("||", t.answers),
                "isPreview", isPreview ? "1" : "0"
        ));
        ch.send(msg, isPreview);
    }

    // Convenience-Overload fÃƒÆ’Ã‚Â¼r bisherigen Code:
    private void sendTask(ClientHandler ch, Task t, boolean isPreview) {
        sendTask(ch, t, isPreview, ch.currentTaskIndex + 1);
    }

    /**
     * @title sendOverview
     * @short Sendet die AufgabenÃƒÆ’Ã‚Â¼bersicht an den Client.
     * @args ClientHandler ch
     */
    private synchronized void registerWarning(ClientHandler ch, String reason, String timestamp) {
        if (ch == null) {
            return;
        }
        String studentName = getSchuelerNameByToken(ch.token);
        String message = (reason == null || reason.isBlank()) ? "Unbekannte Eingabe" : reason.trim();
        String recorded = (timestamp == null || timestamp.isBlank()) ? LocalDateTime.now().toString() : timestamp;
        ClientWarning warning = new ClientWarning(UUID.randomUUID().toString(), ch.clientId, studentName, message, recorded);
        warnings.add(warning);
        log.writeLog("Server", ch.remoteIp, "WARN|CLIENT|" + message);
    }
    private void sendOverview(ClientHandler ch) {
        Message msg = new Message("SERVER", "SEND_OVERVIEW", ch.token, List.of(
                "data", ch.buildOverviewPayload()
        ));
        ch.send(msg);
    }

    // Hilfsmethode, um die Test-ID anhand des Namens zu bekommen
    private int getTestIdByName(String testName) {
        try {
            List<Map<String, Object>> tests = db.getAllEntries("Test");
            for (Map<String, Object> t : tests) {
                if (testName.equals(t.get("typ"))) {
                    return (int) t.get("idTest");
                }
            }
        } catch (SQLException e) {
            System.err.println("Fehler beim Lesen der Test-IDs: " + e.getMessage());
        }
        return -1;
    }

        public DashboardState getDashboardState() {
        return state;
    }

    public boolean isConsoleMode() {
        return consoleMode;
    }

    public synchronized String getBoundIp() {
        return boundIp;
    }

    public synchronized int getSelectedTestId() {
        return selectedTestId;
    }

    public synchronized String getSelectedTestName() {
        return selectedTestName;
    }

    public synchronized int getSelectedTaskCount() {
        return selectedTest == null ? 0 : selectedTest.tasks.size();
    }

    public synchronized int getSelectedClassId() {
        return selectedClassId;
    }

    public synchronized String getSelectedClassName() {
        return selectedClassName;
    }

    public synchronized List<TestSummary> listTests() {
        try {
            List<Map<String, Object>> tests = db.getAllEntries("Test");
            List<TestSummary> result = new ArrayList<>();
            for (Map<String, Object> t : tests) {
                int id = ((Number) t.get("idTest")).intValue();
                String typ = String.valueOf(t.get("typ"));
                result.add(new TestSummary(id, typ));
            }
            result.sort(Comparator.comparing(TestSummary::getName, String.CASE_INSENSITIVE_ORDER));
            return result;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized int getTaskCountForTest(int testId) {
        try {
            List<Map<String, Object>> relations = db.getAllEntries("CT_TestAufgabeLoesung");
            int count = 0;
            for (Map<String, Object> rel : relations) {
                if (testId == ((Number) rel.get("idTest")).intValue()) {
                    count++;
                }
            }
            return count;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized void selectTestById(int testId) {
        try {
            Map<String, Object> test = db.getEntry("Test", testId);
            if (test == null || test.isEmpty()) throw new IllegalArgumentException("Test nicht gefunden");
            String typ = String.valueOf(test.get("typ"));
            selectedTest = loadTestFromDb(typ);
            selectedTestName = typ;
            selectedTestId = testId;
            state = DashboardState.TEST_SELECTED;
            log.writeLog("Server", boundIp, "INFO|TEST|Selected=" + typ);
        } catch (RuntimeException re) {
            throw re;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized void resetToRoot() {
        stopBroadcast();
        selectedTest = null;
        selectedTestName = null;
        selectedTestId = -1;
        selectedClassId = -1;
        selectedClassName = null;
        timerRunning = false;
        timerPaused = false;
        testPaused = false;
        lastTimerTickAt = 0L;
        remainingDurationMillis = configuredDurationMillis;
        cancelTimerFutureInternal();
        state = DashboardState.ROOT;
    }

    public synchronized List<ClassSummary> listClasses() {
        try {
            List<Map<String, Object>> classes = db.getAllEntries("Klasse");
            List<ClassSummary> result = new ArrayList<>();
            for (Map<String, Object> c : classes) {
                int id = ((Number) c.get("idKlasse")).intValue();
                String name = String.valueOf(c.get("klassenname"));
                result.add(new ClassSummary(id, name));
            }
            result.sort(Comparator.comparing(ClassSummary::getName, String.CASE_INSENSITIVE_ORDER));
            return result;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized ImportResult importTestFromFile(File file) {
        return TestImportService.importFromJson(db, file == null ? null : file.toPath(), log, boundIp);
    }

    public synchronized String prepareHostingForClass(int classId, int durationMinutes) {
        try {
            Map<String, Object> klasse = db.getEntry("Klasse", classId);
            if (klasse == null || klasse.isEmpty()) throw new IllegalArgumentException("Klasse nicht gefunden");
            selectedClassId = classId;
            selectedClassName = String.valueOf(klasse.get("klassenname"));
            configuredDurationMillis = Math.max(0, durationMinutes) * 60_000L;
            remainingDurationMillis = configuredDurationMillis;
            timerRunning = false;
            timerPaused = false;
            lastTimerTickAt = 0L;
            cancelTimerFutureInternal();
            String filename = generateAndSaveTokensForClass(classId, selectedClassName, false);
            startBroadcast();
            state = DashboardState.HOSTING;
            return filename;
        } catch (RuntimeException re) {
            throw re;
        } catch (IOException | SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized void startTestRun() {
        if (state != DashboardState.HOSTING || selectedTest == null) {
            return;
        }
        state = DashboardState.RUNNING;
        stopBroadcast();
        for (ClientHandler ch : clients.values()) {
            ch.resetForNewRun(selectedTest);
            sendNextTask(ch);
            ch.status = "Testlauf";
        }
        clientsTableDirty = true;
        startTimerIfConfigured();
    }

    public synchronized void pauseTestRun() {
        if (state != DashboardState.RUNNING || testPaused) {
            return;
        }
        testPaused = true;
        if (timerRunning && lastTimerTickAt > 0L) {
            long now = System.currentTimeMillis();
            long delta = Math.max(0L, now - lastTimerTickAt);
            if (delta > 0L && remainingDurationMillis > 0L) {
                remainingDurationMillis = Math.max(0L, remainingDurationMillis - delta);
            }
        }
        timerRunning = false;
        timerPaused = configuredDurationMillis > 0L && remainingDurationMillis > 0L;
        lastTimerTickAt = 0L;
        cancelTimerFutureInternal();
        for (ClientHandler ch : clients.values()) {
            if (!isDeliveredStatus(ch.status)) {
                ch.status = "Pausiert";
            }
        }
        clientsTableDirty = true;
        broadcastToAll(new Message("SERVER", "TEST_PAUSED", ""));
        log.writeLog("Server", boundIp, "INFO|RUN|Paused");
    }

    public synchronized void resumeTestRun() {
        if (state != DashboardState.RUNNING || !testPaused) {
            return;
        }
        testPaused = false;
        if (configuredDurationMillis > 0L && remainingDurationMillis > 0L) {
            timerPaused = false;
            timerRunning = true;
            lastTimerTickAt = System.currentTimeMillis();
            cancelTimerFutureInternal();
            scheduleTimerLocked();
        } else {
            timerRunning = false;
            timerPaused = false;
            lastTimerTickAt = 0L;
        }
        for (ClientHandler ch : clients.values()) {
            if (!isDeliveredStatus(ch.status)) {
                ch.status = "Testlauf";
            }
        }
        clientsTableDirty = true;
        broadcastToAll(new Message("SERVER", "TEST_RESUMED", ""));
        log.writeLog("Server", boundIp, "INFO|RUN|Resumed");
    }

    public synchronized void addExtraTimeMinutes(int minutes) {
        if (minutes <= 0) {
            return;
        }
        long extraMillis;
        try {
            extraMillis = Math.multiplyExact(minutes, 60_000L);
        } catch (ArithmeticException ex) {
            extraMillis = Long.MAX_VALUE;
        }
        applyAdditionalTime(extraMillis);
    }

    private void applyAdditionalTime(long extraMillis) {
        if (extraMillis <= 0L) {
            return;
        }
        configuredDurationMillis = safeAdd(configuredDurationMillis, extraMillis);
        remainingDurationMillis = safeAdd(remainingDurationMillis, extraMillis);
        if (state == DashboardState.RUNNING) {
            if (testPaused) {
                timerPaused = true;
                timerRunning = false;
            } else if (remainingDurationMillis > 0L) {
                timerRunning = true;
                timerPaused = false;
                lastTimerTickAt = System.currentTimeMillis();
                if (timerFuture == null || timerFuture.isCancelled()) {
                    scheduleTimerLocked();
                }
            }
        }
        long addedSeconds = extraMillis <= 0L ? 0L : extraMillis / 1000L;
        log.writeLog("Server", boundIp, "INFO|TIMER|Extend+" + addedSeconds + "s");
    }

    private void startTimerIfConfigured() {
        cancelTimerFutureInternal();
        if (configuredDurationMillis <= 0L) {
            remainingDurationMillis = 0L;
            timerRunning = false;
            timerPaused = false;
            testPaused = false;
            lastTimerTickAt = 0L;
            return;
        }
        remainingDurationMillis = configuredDurationMillis;
        timerRunning = true;
        timerPaused = false;
        testPaused = false;
        lastTimerTickAt = System.currentTimeMillis();
        scheduleTimerLocked();
    }

    private void cancelTimerFutureInternal() {
        ScheduledFuture<?> future = timerFuture;
        if (future != null) {
            future.cancel(true);
            timerFuture = null;
        }
    }

    private void scheduleTimerLocked() {
        try {
            timerFuture = timerScheduler.scheduleAtFixedRate(this::tickTimer, 1, 1, TimeUnit.SECONDS);
        } catch (RuntimeException ex) {
            timerFuture = null;
            timerRunning = false;
            timerPaused = false;
            log.writeLog("Server", boundIp, "ERROR|TIMER|Schedule=" + ex.getMessage());
        }
    }

    private void tickTimer() {
        ScheduledFuture<?> futureToCancel = null;
        boolean elapsed = false;
        synchronized (this) {
            if (!running || state != DashboardState.RUNNING || !timerRunning || testPaused) {
                return;
            }
            long now = System.currentTimeMillis();
            long delta = lastTimerTickAt <= 0L ? 1000L : Math.max(0L, now - lastTimerTickAt);
            lastTimerTickAt = now;
            if (delta >= remainingDurationMillis) {
                remainingDurationMillis = 0L;
                timerRunning = false;
                timerPaused = false;
                futureToCancel = timerFuture;
                timerFuture = null;
                elapsed = true;
            } else {
                remainingDurationMillis -= delta;
            }
        }
        if (futureToCancel != null) {
            futureToCancel.cancel(false);
        }
        if (elapsed) {
            handleTimerElapsed();
        }
    }

    private void handleTimerElapsed() {
        List<ClientHandler> toNotify = new ArrayList<>();
        synchronized (this) {
            if (state != DashboardState.RUNNING) {
                return;
            }
            for (ClientHandler ch : clients.values()) {
                if (!isDeliveredStatus(ch.status)) {
                    ch.status = "Abgegeben (Zeit abgelaufen)";
                    if (ch.test != null && ch.test.tasks != null) {
                        ch.currentTaskIndex = ch.test.tasks.size();
                    }
                    toNotify.add(ch);
                }
            }
            remainingDurationMillis = 0L;
            timerRunning = false;
            timerPaused = false;
            testPaused = false;
            lastTimerTickAt = 0L;
            clientsTableDirty = true;
            log.writeLog("Server", boundIp, "INFO|TIMER|Expired");
        }
        for (ClientHandler ch : toNotify) {
            ch.send(new Message("SERVER", "TIME_EXPIRED", ch.token, List.of(
                    "message", TIME_EXPIRED_MESSAGE,
                    "forceExit", "true"
            )));
            ch.close();
        }
    }

    private boolean isDeliveredStatus(String status) {
        if (status == null) {
            return false;
        }
        String normalized = status.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("abgegeben");
    }

    public synchronized void abortHosting() {
        stopBroadcast();
        for (ClientHandler ch : clients.values()) {
            ch.close();
        }
        clients.clear();
        clientsTableDirty = true;
        selectedClassId = -1;
        selectedClassName = null;
        timerRunning = false;
        timerPaused = false;
        testPaused = false;
        lastTimerTickAt = 0L;
        remainingDurationMillis = configuredDurationMillis;
        cancelTimerFutureInternal();
        state = DashboardState.ROOT;
    }

    public synchronized void endRunningTest() {
        if (state != DashboardState.RUNNING) {
            return;
        }
        broadcastToAll(new Message("SERVER", "END_TEST", ""));
        for (ClientHandler ch : new ArrayList<>(clients.values())) {
            ch.close();
        }
        clients.clear();
        clientsTableDirty = true;
        selectedClassId = -1;
        selectedClassName = null;
        timerRunning = false;
        timerPaused = false;
        testPaused = false;
        remainingDurationMillis = configuredDurationMillis;
        lastTimerTickAt = 0L;
        cancelTimerFutureInternal();
        state = DashboardState.ROOT;
    }

    public synchronized TimerStatus getTimerStatus() {
        long totalSeconds = configuredDurationMillis > 0L ? configuredDurationMillis / 1000L : 0L;
        long remainingSeconds = 0L;
        if (configuredDurationMillis > 0L) {
            long snapshot = remainingDurationMillis;
            if (timerRunning && lastTimerTickAt > 0L) {
                long delta = Math.max(0L, System.currentTimeMillis() - lastTimerTickAt);
                snapshot = Math.max(0L, remainingDurationMillis - delta);
            }
            remainingSeconds = Math.max(0L, (snapshot + 999L) / 1000L);
        }
        boolean runningNow = state == DashboardState.RUNNING && timerRunning;
        boolean pausedNow = testPaused;
        return new TimerStatus(totalSeconds, remainingSeconds, runningNow, pausedNow);
    }

    public synchronized List<ClientSummary> getClientSummaries() {
        List<ClientSummary> list = new ArrayList<>();
        for (ClientHandler ch : clients.values()) {
            String name = getSchuelerNameByToken(ch.token);
            int totalTasks = ch.test == null || ch.test.tasks == null ? 0 : ch.test.tasks.size();
            int currentTask = ch.currentTaskIndex;
            if (totalTasks > 0) {
                currentTask = Math.max(0, Math.min(currentTask, totalTasks));
            } else {
                currentTask = Math.max(0, currentTask);
            }
            list.add(new ClientSummary(
                    ch.clientId,
                    name,
                    ch.remoteIp,
                    ch.status,
                    ch.token,
                    currentTask,
                    totalTasks,
                    ch.handRaised,
                    ch.handRaisedAt
            ));
        }
        list.sort((a, b) -> {
            if (a.handRaised() && !b.handRaised()) {
                return -1;
            }
            if (!a.handRaised() && b.handRaised()) {
                return 1;
            }
            if (a.handRaised() && b.handRaised()) {
                int cmp = Long.compare(b.handRaisedAt(), a.handRaisedAt());
                if (cmp != 0) {
                    return cmp;
                }
            }
            return String.CASE_INSENSITIVE_ORDER.compare(a.clientId(), b.clientId());
        });
        return list;
    }
    public synchronized List<ClientWarning> getActiveWarnings() {
        return new ArrayList<>(warnings);
    }

    public synchronized void dismissWarning(String warningId) {
        if (warningId == null || warningId.isBlank()) {
            return;
        }
        boolean removed = warnings.removeIf(w -> w.id.equals(warningId));
        if (removed) {
            log.writeLog("Server", boundIp, "WARN|DISMISS|" + warningId);
        }
    }

    public synchronized void shutdown() {
        running = false;
        stopBroadcast();
        if (dashboardThread != null) {
            dashboardThread.interrupt();
        }
        cancelTimerFutureInternal();
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}
        for (ClientHandler ch : new ArrayList<>(clients.values())) {
            ch.close();
        }
        clients.clear();
        timerScheduler.shutdownNow();
    }

    public static final class TimerStatus {
        private final long totalSeconds;
        private final long remainingSeconds;
        private final boolean running;
        private final boolean paused;

        public TimerStatus(long totalSeconds, long remainingSeconds, boolean running, boolean paused) {
            this.totalSeconds = totalSeconds;
            this.remainingSeconds = remainingSeconds;
            this.running = running;
            this.paused = paused;
        }

        public long getTotalSeconds() {
            return totalSeconds;
        }

        public long getRemainingSeconds() {
            return remainingSeconds;
        }

        public boolean isRunning() {
            return running;
        }

        public boolean isPaused() {
            return paused;
        }
    }

    public static final class TestSummary {
        private final int id;
        private final String name;

        public TestSummary(int id, String name) {
            this.id = id;
            this.name = name;
        }

        public int getId() {
            return id;
        }

        public String getName() {
            return name;
        }
    }

    public static final class ClassSummary {
        private final int id;
        private final String name;

        public ClassSummary(int id, String name) {
            this.id = id;
            this.name = name;
        }

        public int getId() {
            return id;
        }

        public String getName() {
            return name;
        }
    }

    public static final class ClientSummary {
        private final String clientId;
        private final String studentName;
        private final String remoteIp;
        private final String status;
        private final String token;
        private final int currentTaskNumber;
        private final int totalTasks;
        private final boolean handRaised;
        private final long handRaisedAt;

        public ClientSummary(String clientId, String studentName, String remoteIp, String status, String token,
                             int currentTaskNumber, int totalTasks,
                             boolean handRaised, long handRaisedAt) {
            this.clientId = clientId;
            this.studentName = studentName;
            this.remoteIp = remoteIp;
            this.status = status;
            this.token = token;
            this.currentTaskNumber = currentTaskNumber;
            this.totalTasks = totalTasks;
            this.handRaised = handRaised;
            this.handRaisedAt = handRaisedAt;
        }

        public String clientId() {
            return clientId;
        }

        public String studentName() {
            return studentName;
        }

        public String remoteIp() {
            return remoteIp;
        }

        public String status() {
            return status;
        }

        public String token() {
            return token;
        }

        public int currentTaskNumber() {
            return currentTaskNumber;
        }

        public int totalTasks() {
            return totalTasks;
        }

        public boolean handRaised() {
            return handRaised;
        }

        public long handRaisedAt() {
            return handRaisedAt;
        }
    }

    public static final class ImportResult {
        private final boolean success;
        private final String message;

        public ImportResult(boolean success, String message) {
            this.success = success;
            this.message = message == null ? "" : message;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }
    }

    public static final class ClientWarning {
        private final String id;
        private final String clientId;
        private final String studentName;
        private final String message;
        private final String timestamp;

        public ClientWarning(String id, String clientId, String studentName, String message, String timestamp) {
            this.id = id;
            this.clientId = clientId;
            this.studentName = studentName;
            this.message = message;
            this.timestamp = timestamp;
        }

        public String getId() {
            return id;
        }

        public String getClientId() {
            return clientId;
        }

        public String getStudentName() {
            return studentName;
        }

        public String getMessage() {
            return message;
        }

        public String getTimestamp() {
            return timestamp;
        }
    }

    private long safeAdd(long base, long delta) {
        long result;
        try {
            result = Math.addExact(base, delta);
        } catch (ArithmeticException ex) {
            result = delta >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        return result;
    }

// -------- Hilfstypen --------
    /** @title Test @short Testdaten. @args String name, List<Task> tasks */
    private static class Test { final String name; final List<Task> tasks; Test(String n, List<Task> t){name=n;tasks=t;} }
    /** @title Task @short Aufgabe + Antworten. @args String id, String question, List<String> answers, String type */
    private static class Task { final String id; final String question; final List<String> answers; final String type; Task(String i,String q,List<String>a,String type){id=i;question=q;answers=a;this.type=type;} }
}







