package frontend.client;

import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

/**
 * Aesthetic Swing frontend that follows the flow:
 * Serverauswahl -> Tokeneingabe -> Warteraum -> Aufgabenkarte -> Abgabe
 *
 * The UI itself is framework-agnostic and communicates through the small
 * adapter interface {@link ClientBridge}. Implement this bridge on top of
 * your existing {@link IClient} and wire it in via the constructor (see
 * the static main at the bottom for a dummy client you can replace).
 *
 * Screens are swapped with a CardLayout and share a consistent dark theme.
 */
public class UI extends JFrame {

    /* ----------------------------- Theming ------------------------------ */
    public static final Color BG = new Color(0x101415);
    public static final Color PANEL = new Color(0x171C1D);
    public static final Color PANEL_SOFT = new Color(0x1E2426);
    public static final Color SERVER_LIST_BG = new Color(0x262B2D);
    public static final Color TEXT = new Color(0xEAF1F1);
    public static final Color TEXT_MUTED = new Color(0xB8C3C4);
    public static final Color ACCENT = new Color(0x0FD3D3);
    public static final Color OUTLINE = new Color(0x2B3234);
    private static final float FONT_STEP = 0.05f;
    private static final float MIN_FONT_SCALE = 0.7f;
    private static final float MAX_FONT_SCALE = 1.6f;
    private static final String HAND_ICON = "\uD83D\uDC4B";
    private static final Image APPLICATION_ICON = loadApplicationIcon();

    private static Image loadApplicationIcon() {
        URL resource = UI.class.getResource("/icon.png");
        if (resource != null) {
            return new ImageIcon(resource).getImage();
        }
        File file = new File("data/icon.png");
        if (file.isFile()) {
            return new ImageIcon(file.getAbsolutePath()).getImage();
        }
        return null;
    }

    private final CardLayout cards = new CardLayout();
    private final JPanel root = new JPanel(cards);
    private final JToggleButton handRaiseButton;
    private final JPanel handRaiseStrip;

    private final Map<Component, Font> baseFonts = new WeakHashMap<>();
    private final Map<JTable, Integer> tableBaseHeights = new WeakHashMap<>();
    private float fontScale = 1.0f;
    private final AWTEventListener zoomListener = this::handleGlobalMouseWheel;
    private boolean zoomListenerInstalled;

    // Panels
    private final ServerSelectPanel serverSelectPanel;
    private final TokenPanel tokenPanel;
    private final WaitingRoomPanel waitingPanel;
    private final TaskPanel taskPanel;
    private final SubmitPanel submitPanel;
    private final PausePanel pausePanel;

    // Bridge to your real client
    private final ClientBridge client;

    private final Map<String, TaskViewModel> taskCache = new LinkedHashMap<>();
    private final List<String> taskOrder = new ArrayList<>();
    private final Map<String, List<String>> answerCache = new LinkedHashMap<>();
    private TaskViewModel currentTask;
    private String currentTaskId;
    private int currentOrderIndex = -1;
    private int totalTasks;
    private boolean waitingForEnd;
    private boolean paused;
    private boolean handRaised;
    private String currentCard = "server";
    private String lastNonPauseCard = "server";
    private String cardBeforePause;
    private long lastWarningMillis;
    private boolean timeExpiredExitTriggered;

    public UI(ClientBridge client) {
        super("Klausur-Client");
        this.client = Objects.requireNonNull(client);

        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        if (APPLICATION_ICON != null) {
            setIconImage(APPLICATION_ICON);
        }
        setMinimumSize(new Dimension(960, 620));
        setLocationByPlatform(true);
        getContentPane().setBackground(BG);

        // Root
        root.setBackground(BG);
        handRaiseButton = createHandRaiseButton();
        handRaiseStrip = new JPanel(new FlowLayout(FlowLayout.RIGHT, 16, 16));
        handRaiseStrip.setOpaque(false);
        handRaiseStrip.add(handRaiseButton);
        handRaiseStrip.setVisible(false);

        JPanel container = new JPanel(new BorderLayout());
        container.setBackground(BG);
        container.add(handRaiseStrip, BorderLayout.NORTH);
        container.add(root, BorderLayout.CENTER);
        setContentPane(container);
        updateHandRaiseButtonVisuals();
        updateHandRaiseVisibility();
        enterFullscreen();
        installKeyGuards();

        // Instantiate screens
        serverSelectPanel = new ServerSelectPanel();
        tokenPanel = new TokenPanel();
        waitingPanel = new WaitingRoomPanel();
        taskPanel = new TaskPanel();
        submitPanel = new SubmitPanel();
        pausePanel = new PausePanel();

        // Add to CardLayout
        root.add(serverSelectPanel, "server");
        root.add(tokenPanel, "token");
        root.add(waitingPanel, "waiting");
        root.add(taskPanel, "task");
        root.add(submitPanel, "submit");
        root.add(pausePanel, "pause");

        // Wire actions
        serverSelectPanel.onRefresh = () -> serverSelectPanel.setServers(client.fetchServers());
        serverSelectPanel.onNext = () -> {
            IClient.DiscoveredServer s = serverSelectPanel.getSelected();
            if (s != null) {
                client.selectServer(s);
                showCard("token");
            }
        };

        tokenPanel.onBack = () -> showCard("server");
        tokenPanel.onLogin = () -> {
            String token = tokenPanel.getToken();
            if (token == null || token.isBlank()) {
                toast("Bitte Token eingeben.");
                return;
            }
            waitingPanel.showWaitingForStart("Verbindung wird aufgebaut", 0);
            client.authenticate(token);
            showCard("waiting");
        };

        waitingPanel.onCancel = () -> {
            client.cancelWaiting();
            resetTaskState();
            showCard("server");
        };

        taskPanel.onBack = this::navigateToPreviousTask;
        taskPanel.onSubmit = (taskId, answers) -> {
            cacheAnswers(taskId, answers);
            client.submitAnswer(taskId, answers);
        };
        taskPanel.onNext = () -> {
            if (currentOrderIndex >= 0 && currentOrderIndex < taskOrder.size() - 1) {
                showTaskAt(currentOrderIndex + 1);
            } else {
                client.requestNextTask();
            }
        };
        taskPanel.onEnd = () -> {
            client.requestOverview();
            showCard("submit");
        };

        submitPanel.onSubmitAll = () -> {
            client.submitAll();
            waitingForEnd = true;
            waitingPanel.showWaitingForFinish();
            showCard("waiting");
        };
        submitPanel.onBack = () -> showCard("task");
        submitPanel.onOpenTask = this::openTaskFromOverview;

        // Bridge -> UI callbacks
        client.setUiCallbacks(new ClientBridge.UiCallbacks() {
            @Override public void showWaitingRoom(String exerciseName, int taskCount) {
                SwingUtilities.invokeLater(() -> {
                    totalTasks = Math.max(0, taskCount);
                    waitingForEnd = false;
                    resetTaskState();
                    waitingPanel.showWaitingForStart(exerciseName, totalTasks);
                    showCard("waiting");
                });
            }
            @Override public void showTask(TaskViewModel task) {
                SwingUtilities.invokeLater(() -> displayTask(task));
            }
            @Override public void showOverview(List<OverviewRow> rows) {
                SwingUtilities.invokeLater(() -> {
                    submitPanel.setOverview(rows);
                    if (waitingForEnd) {
                        waitingPanel.showWaitingForFinish();
                        showCard("waiting");
                    } else {
                        showCard("submit");
                    }
                });
            }
            @Override public void showEnd() {
                SwingUtilities.invokeLater(() -> {
                    setHandRaised(false, true);
                    waitingForEnd = false;
                    deactivatePauseOverlay();
                    if (isDisplayable()) {
                        JOptionPane.showMessageDialog(
                                UI.this,
                                "Der Test wurde beendet. Die Anwendung wird geschlossen.",
                                "Test beendet",
                                JOptionPane.INFORMATION_MESSAGE
                        );
                    }
                    dispose();
                    System.exit(0);
                });
            }
            @Override public void showTimeExpired(String message, boolean forceExit) {
                SwingUtilities.invokeLater(() -> handleTimeExpired(message, forceExit));
            }
            @Override public void showPauseOverlay() {
                SwingUtilities.invokeLater(UI.this::activatePauseOverlay);
            }
            @Override public void hidePauseOverlay() {
                SwingUtilities.invokeLater(UI.this::deactivatePauseOverlay);
            }
            @Override public void toast(String message) { UI.this.toast(message); }
            @Override public void backToToken() {
                resetTaskState();
                showCard("token");
            }
        });

        // Initial data
        serverSelectPanel.setServers(client.fetchServers());
        showCard("server");

        registerFontTree(getContentPane());
        applyFontScale(getContentPane());
        Toolkit.getDefaultToolkit().addAWTEventListener(zoomListener, AWTEvent.MOUSE_WHEEL_EVENT_MASK);
        zoomListenerInstalled = true;
    }

    private void showCard(String name) {
        if (paused && !"pause".equals(name)) {
            lastNonPauseCard = name;
            return;
        }
        cards.show(root, name);
        currentCard = name;
        if (!"pause".equals(name)) {
            lastNonPauseCard = name;
            if (!paused) {
                cardBeforePause = null;
            }
        }
        updateHandRaiseVisibility();
    }

    private boolean shouldShowHandRaise(String cardName) {
        if (cardName == null) {
            return false;
        }
        return switch (cardName) {
            case "waiting", "task", "submit", "pause" -> true;
            default -> false;
        };
    }

    private void updateHandRaiseVisibility() {
        if (handRaiseStrip == null) {
            return;
        }
        boolean visible = shouldShowHandRaise(currentCard);
        handRaiseStrip.setVisible(visible);
        handRaiseButton.setEnabled(visible);
    }

    private void toggleHandRaise() {
        if (!shouldShowHandRaise(currentCard)) {
            return;
        }
        setHandRaised(!handRaised, true);
    }

    private void setHandRaised(boolean raised, boolean notifyBridge) {
        boolean changed = this.handRaised != raised;
        this.handRaised = raised;
        updateHandRaiseButtonVisuals();
        if (notifyBridge && changed) {
            client.setHandRaise(raised);
        }
    }

    private void updateHandRaiseButtonVisuals() {
        if (handRaiseButton == null) {
            return;
        }
        handRaiseButton.setSelected(handRaised);
        Color background = handRaised ? ACCENT : PANEL_SOFT;
        Color foreground = handRaised ? BG : TEXT;
        handRaiseButton.setBackground(background);
        handRaiseButton.setForeground(foreground);
        handRaiseButton.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(handRaised ? ACCENT.darker() : OUTLINE),
                new EmptyBorder(6, 14, 6, 14)
        ));
        handRaiseButton.setToolTipText(handRaised ? "Meldung zuruecknehmen" : "Bei der Lehrkraft melden");
    }

    private JToggleButton createHandRaiseButton() {
        JToggleButton button = new JToggleButton(HAND_ICON);
        button.setOpaque(true);
        button.setBackground(PANEL_SOFT);
        button.setForeground(TEXT);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(OUTLINE),
                new EmptyBorder(6, 14, 6, 14)
        ));
        button.setFont(button.getFont().deriveFont(Font.BOLD, 20f));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setToolTipText("Bei der Lehrkraft melden");
        button.addActionListener(e -> toggleHandRaise());
        return button;
    }

    private void activatePauseOverlay() {
        if (paused) {
            return;
        }
        paused = true;
        cardBeforePause = currentCard;
        pausePanel.showPaused();
        showCard("pause");
    }

    private void deactivatePauseOverlay() {
        if (!paused) {
            return;
        }
        paused = false;
        String target = cardBeforePause;
        cardBeforePause = null;
        if (target == null || "pause".equals(target)) {
            if (waitingForEnd) {
                target = "waiting";
            } else if (currentTask != null) {
                target = "task";
            } else {
                target = lastNonPauseCard != null ? lastNonPauseCard : "waiting";
            }
        }
        pausePanel.clear();
        showCard(target);
    }

    private void handleTimeExpired(String message, boolean forceExit) {
        setHandRaised(false, true);
        String displayMessage = (message == null || message.isBlank())
                ? "Die Zeit ist abgelaufen. Die Anwendung wird geschlossen."
                : message;
        waitingForEnd = true;
        deactivatePauseOverlay();
        waitingPanel.showTimeExpired(displayMessage);
        showCard("waiting");
        if (forceExit && !timeExpiredExitTriggered) {
            timeExpiredExitTriggered = true;
            if (isDisplayable()) {
                JOptionPane.showMessageDialog(
                        this,
                        displayMessage,
                        "Zeit abgelaufen",
                        JOptionPane.INFORMATION_MESSAGE
                );
            }
            dispose();
            System.exit(0);
        }
    }

    private void clearPauseState() {
        paused = false;
        cardBeforePause = null;
        pausePanel.clear();
        if ("pause".equals(currentCard)) {
            String target = lastNonPauseCard != null ? lastNonPauseCard : "waiting";
            showCard(target);
        }
    }

    private void resetTaskState() {
        taskCache.clear();
        taskOrder.clear();
        answerCache.clear();
        currentTask = null;
        currentTaskId = null;
        currentOrderIndex = -1;
        waitingForEnd = false;
        setHandRaised(false, true);
        clearPauseState();
    }

    private void displayTask(TaskViewModel task) {
        if (task == null) {
            return;
        }
        currentTask = task;
        if (task.taskId != null) {
            taskCache.put(task.taskId, task);
            if (!taskOrder.contains(task.taskId)) {
                taskOrder.add(task.taskId);
            }
            currentTaskId = task.taskId;
            currentOrderIndex = taskOrder.indexOf(task.taskId);
        } else {
            currentTaskId = null;
            currentOrderIndex = -1;
        }
        boolean hasPrevious = currentOrderIndex > 0;
        boolean hasCachedNext = currentOrderIndex >= 0 && currentOrderIndex < taskOrder.size() - 1;
        boolean hasRemaining = totalTasks <= 0 ? hasCachedNext : currentOrderIndex < totalTasks - 1;
        boolean hasNext = hasCachedNext || hasRemaining;
        List<String> saved = task.taskId == null ? List.of() : answerCache.getOrDefault(task.taskId, List.of());
        taskPanel.setTask(task, totalTasks, hasPrevious, hasNext, task.preview, saved);
        showCard("task");
    }

    private void navigateToPreviousTask() {
        if (currentOrderIndex <= 0) {
            return;
        }
        showTaskAt(currentOrderIndex - 1);
    }

    private void openTaskFromOverview(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        int orderIndex = taskOrder.indexOf(taskId);
        if (orderIndex >= 0) {
            showTaskAt(orderIndex);
        } else {
            client.requestTaskById(taskId);
        }
    }

    private void showTaskAt(int index) {
        if (index < 0 || index >= taskOrder.size()) {
            return;
        }
        String taskId = taskOrder.get(index);
        TaskViewModel task = taskCache.get(taskId);
        if (task == null) {
            client.requestTaskById(taskId);
            return;
        }
        currentTask = task;
        currentTaskId = taskId;
        currentOrderIndex = index;

        boolean hasPrevious = currentOrderIndex > 0;
        boolean hasCachedNext = currentOrderIndex < taskOrder.size() - 1;
        boolean hasRemaining = totalTasks <= 0 ? hasCachedNext : currentOrderIndex < totalTasks - 1;
        boolean hasNext = hasCachedNext || hasRemaining;
        List<String> saved = answerCache.getOrDefault(taskId, List.of());
        taskPanel.setTask(task, totalTasks, hasPrevious, hasNext, false, saved);
        showCard("task");
    }

    private void cacheAnswers(String taskId, List<String> answers) {
        if (taskId == null) {
            return;
        }
        List<String> copy = (answers == null) ? List.of() : new ArrayList<>(answers);
        answerCache.put(taskId, copy);
    }

    /* ------------------------------ Screens ----------------------------- */

    private abstract static class Surface extends JPanel {
        protected final JPanel inner = new JPanel();
        Surface() {
            setBackground(BG);
            setLayout(new GridBagLayout());

            inner.setBackground(PANEL);
            inner.setBorder(new EmptyBorder(24, 24, 24, 24));
            inner.setLayout(new BorderLayout(16, 16));

            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0; c.gridy = 0; c.weightx = 1; c.weighty = 1; c.fill = GridBagConstraints.BOTH;
            add(inner, c);
        }
        protected JLabel h1(String text){ return label(text, 20, TEXT, Font.BOLD); }
        protected JLabel h2(String text){ return label(text, 16, TEXT, Font.BOLD); }
        protected JLabel muted(String text){ return label(text, 13, TEXT_MUTED, Font.PLAIN); }
        protected JLabel label(String text, int size, Color color, int style){
            JLabel l = new JLabel(text);
            l.setFont(l.getFont().deriveFont(style, size));
            l.setForeground(color);
            return l;
        }
        protected JButton pill(String text){
            JButton b = new JButton(text);
            b.setBackground(PANEL_SOFT);
            b.setForeground(TEXT);
            b.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(OUTLINE),
                    new EmptyBorder(10,18,10,18)));
            b.setFocusPainted(false);
            return b;
        }
        protected JComponent spacer(int h){ JPanel p = new JPanel(); p.setOpaque(false); p.setPreferredSize(new Dimension(1,h)); return p; }
    }

    /** Serverauswahl */
    private class ServerSelectPanel extends Surface {
        private final DefaultListModel<IClient.DiscoveredServer> model = new DefaultListModel<>();
        private final JList<IClient.DiscoveredServer> list = new JList<>(model);
        Runnable onRefresh, onNext;
        

        ServerSelectPanel(){
            inner.add(h1("Serverauswahl"), BorderLayout.NORTH);

            list.setBackground(SERVER_LIST_BG);
            list.setForeground(TEXT);
            list.setSelectionBackground(SERVER_LIST_BG.brighter());
            list.setSelectionForeground(TEXT);
            list.setOpaque(false);
            list.setCellRenderer(new DefaultListCellRenderer(){
                @Override public Component getListCellRendererComponent(JList<?> l, Object val, int i, boolean s, boolean f){
                    super.getListCellRendererComponent(l, val, i, s, f);
                    setOpaque(true);
                    setBackground(s ? SERVER_LIST_BG.brighter() : SERVER_LIST_BG);
                    setForeground(TEXT);
                    if(val instanceof IClient.DiscoveredServer ds){
                        String right = ds.activeTest()==null?"":"  -  " + ds.activeTest();
                        setText(" *  " + ds.name() + right + "\n  " + ds.description() + "\n  " + ds.ip()+":"+ds.port());
                        setBorder(new EmptyBorder(12,12,12,12));
                    }
                    return this;
                }
            });
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            JScrollPane sp = new JScrollPane(list); sp.getViewport().setBackground(SERVER_LIST_BG);
            sp.setBorder(BorderFactory.createLineBorder(OUTLINE));
            inner.add(sp, BorderLayout.CENTER);

            JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
            south.setOpaque(false);
            JButton refresh = pill("Aktualisieren");
            JButton next = pill("Weiter"); next.setEnabled(true);
            refresh.addActionListener(e -> { if(onRefresh!=null) onRefresh.run(); });
            next.addActionListener(e -> { if(onNext!=null) onNext.run(); });
            south.add(refresh); south.add(next);
            inner.add(south, BorderLayout.SOUTH);
        }
        void setServers(List<IClient.DiscoveredServer> servers){
            model.clear();
            if(servers!=null) servers.forEach(model::addElement);
            if(!model.isEmpty()) list.setSelectedIndex(0);
        }
        IClient.DiscoveredServer getSelected(){ return list.getSelectedValue(); }
    }

    /** Tokeneingabe */
    private class TokenPanel extends Surface {
        Runnable onBack, onLogin; private final JTextField tf = new JTextField();
        TokenPanel(){
            JPanel north = new JPanel(new BorderLayout()); north.setOpaque(false);
            JButton back = pill("<< Zurueck zur Serverauswahl");
            back.addActionListener(e -> { if(onBack!=null) onBack.run(); });
            north.add(back, BorderLayout.WEST);
            north.add(h1("Anmeldung"), BorderLayout.SOUTH);
            inner.add(north, BorderLayout.NORTH);

            JPanel form = new JPanel(); form.setOpaque(false); form.setLayout(new GridBagLayout());
            GridBagConstraints c = new GridBagConstraints(); c.insets = new Insets(8,8,8,8);
            c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1; c.gridx=0; c.gridy=0;
            //form.add(label(, 13, TEXT, Font.PLAIN), c);
            c.gridy++; //form.add(label("Label", 13, TEXT, Font.PLAIN), c);
            c.gridx=1; c.gridy=1; tf.setOpaque(true); tf.setBackground(PANEL_SOFT); tf.setForeground(TEXT);
            tf.setCaretColor(TEXT); tf.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(ACCENT), new EmptyBorder(10,12,10,12)));
            form.add(tf, c);
            inner.add(form, BorderLayout.CENTER);

            JButton login = pill("Anmelden"); login.addActionListener(e -> { if(onLogin!=null) onLogin.run(); });
            JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT)); south.setOpaque(false); south.add(login);
            inner.add(south, BorderLayout.SOUTH);
        }
        String getToken(){ return tf.getText().trim(); }
    }

    /** Warteraum */
    private class WaitingRoomPanel extends Surface {
        Runnable onCancel;
        private final JLabel title = h1("Warteraum");
        //private final JLabel exerciseLabel = h2("Name der Uebung");
        private final JLabel statusLabel = muted("Warte bis der Lehrer den Test startet.");
        private final JProgressBar spinner = new JProgressBar();
        private final JButton cancelButton = pill("Abbrechen");

        WaitingRoomPanel(){
            inner.add(title, BorderLayout.NORTH);
            JPanel center = new JPanel();
            center.setOpaque(false);
            center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
            //exerciseLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            center.add(Box.createVerticalStrut(8));
            //center.add(exerciseLabel);
            center.add(Box.createVerticalStrut(40));
            spinner.setIndeterminate(true);
            spinner.setBorderPainted(false);
            spinner.setOpaque(false);
            center.add(spinner);
            inner.add(center, BorderLayout.CENTER);
            JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            south.setOpaque(false);
            cancelButton.addActionListener(e -> { if(onCancel!=null) onCancel.run(); });
            south.add(statusLabel);
            south.add(cancelButton);
            inner.add(south, BorderLayout.SOUTH);
        }

        void showWaitingForStart(String exerciseName, int taskCount){
            title.setText("Warteraum - " + exerciseName);
            /*if (taskCount > 0) {
                exerciseLabel.setText(exerciseName + " (" + taskCount + " Aufgaben)");
            } else {
                exerciseLabel.setText(exerciseName);
            }*/
            statusLabel.setText("Warte bis der Lehrer den Test startet.");
            spinner.setVisible(true);
            spinner.setIndeterminate(true);
            cancelButton.setVisible(true);
        }

        void showWaitingForFinish(){
            statusLabel.setText("Test abgegeben - bitte warten, bis die Zeit beendet ist oder der Lehrer den Test beendet.");
            spinner.setVisible(true);
            spinner.setIndeterminate(true);
            cancelButton.setVisible(false);
        }

        void showTimeExpired(String message){
            if (message == null || message.isBlank()) {
                message = "Die Zeit ist abgelaufen. Die Anwendung wird geschlossen.";
            }
            statusLabel.setText(message);
            spinner.setVisible(true);
            spinner.setIndeterminate(true);
            cancelButton.setVisible(false);
        }

        void showFinished(){
            statusLabel.setText("Der Test wurde beendet. Dieses Fenster kann geschlossen werden.");
            spinner.setIndeterminate(false);
            spinner.setVisible(false);
            cancelButton.setVisible(false);
        }
    }

    /** Aufgabenkarte */
    private class TaskPanel extends Surface {
        interface SubmitListener { void onSubmit(String taskId, List<String> answers); }
        SubmitListener onSubmit; Runnable onNext; Runnable onEnd; Runnable onBack;
        private final JLabel heading = h1("Aufgabe 1");
        private final JTextArea question = new JTextArea();
        private final JPanel answerHolder = new JPanel();
        private final JButton backButton = pill("Zurueck");
        //private final JButton submitButton = pill("Antwort speichern");
        private final JButton nextButton = pill("Weiter");
        private final JButton endButton = pill("Zur Abgabe");
        private final JLabel progressLabel = muted(" ");
        private TaskViewModel current;

        TaskPanel(){
            inner.add(heading, BorderLayout.NORTH);
            JPanel center = new JPanel(); center.setOpaque(false); center.setLayout(new BorderLayout(8,8));
            question.setLineWrap(true); question.setWrapStyleWord(true); question.setEditable(false);
            question.setBackground(PANEL); question.setForeground(TEXT); question.setFont(question.getFont().deriveFont(15f));
            center.add(question, BorderLayout.NORTH);

            answerHolder.setOpaque(false); answerHolder.setLayout(new BoxLayout(answerHolder, BoxLayout.Y_AXIS));
            center.add(answerHolder, BorderLayout.CENTER);
            inner.add(center, BorderLayout.CENTER);

            JPanel nav = new JPanel(new BorderLayout());
            nav.setOpaque(false);

            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0)); left.setOpaque(false);
            backButton.addActionListener(e -> { if(onBack!=null) onBack.run(); });
            left.add(backButton);

            JPanel centerInfo = new JPanel(); centerInfo.setOpaque(false);
            progressLabel.setHorizontalAlignment(SwingConstants.CENTER);
            centerInfo.add(progressLabel);

            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0)); right.setOpaque(false);
            //submitButton.addActionListener(e -> { if(current!=null && onSubmit!=null) onSubmit.onSubmit(current.taskId, collectAnswers()); });
            nextButton.addActionListener(e -> 
            { 
                if(current!=null && onSubmit!=null && onNext!=null)
                {
                    onSubmit.onSubmit(current.taskId, collectAnswers());
                    onNext.run();
                }
            });
            endButton.addActionListener(e -> 
            { 
                if(current!=null && onSubmit!=null && onEnd!=null)
                {
                    onSubmit.onSubmit(current.taskId, collectAnswers());
                    onEnd.run();
                }
            });
            //right.add(submitButton); 
            right.add(nextButton); right.add(endButton);

            nav.add(left, BorderLayout.WEST);
            nav.add(centerInfo, BorderLayout.CENTER);
            nav.add(right, BorderLayout.EAST);
            inner.add(nav, BorderLayout.SOUTH);
        }

        void setTask(TaskViewModel t, int totalTasks, boolean canGoBack, boolean canGoForward, boolean previewMode, List<String> savedAnswers){
            this.current = t;
            if (previewMode) {
                heading.setText("Aufgabe (Review)");
            } else if (t.taskNumber > 0) {
                heading.setText("Aufgabe " + t.taskNumber);
            } else {
                heading.setText("Aufgabe");
            }
            question.setText(t.question);
            answerHolder.removeAll();
            if("SingleChoice".equalsIgnoreCase(t.taskType)){
                ButtonGroup g = new ButtonGroup();
                for(String a : t.answers){
                    JRadioButton rb = new JRadioButton(a); style(rb); g.add(rb); answerHolder.add(rb);
                }
            } else if ("MultiChoice".equalsIgnoreCase(t.taskType)){
                for(String a : t.answers){
                    JCheckBox cb = new JCheckBox(a); style(cb); answerHolder.add(cb);
                }
            } else { // TextInput
                JTextArea ta = new JTextArea(6, 40); ta.setLineWrap(true); ta.setWrapStyleWord(true); style(ta);
                JScrollPane sp = new JScrollPane(ta); sp.getViewport().setBackground(PANEL);
                sp.setBorder(BorderFactory.createLineBorder(OUTLINE)); sp.putClientProperty("textArea", ta);
                answerHolder.add(sp);
            }
            applySavedAnswers(savedAnswers);
            if (previewMode) {
                nextButton.setText("Zur aktuellen Ansicht");
                nextButton.setEnabled(true);
                nextButton.setVisible(true);
                backButton.setVisible(false);
                endButton.setVisible(false);
                progressLabel.setText("Review-Modus");
            } else {
                nextButton.setText("Weiter");
                backButton.setVisible(canGoBack);
                backButton.setEnabled(canGoBack);
                if (totalTasks > 0 && t.taskNumber > 0) {
                    progressLabel.setText("Frage " + t.taskNumber + " / " + totalTasks);
                } else {
                    progressLabel.setText(" ");
                }
                nextButton.setVisible(canGoForward);
                nextButton.setEnabled(canGoForward);
                endButton.setVisible(!canGoForward);
                endButton.setEnabled(!canGoForward);
            }
            UI.this.registerFontTree(answerHolder);
            UI.this.applyFontScale(answerHolder);
            answerHolder.revalidate(); answerHolder.repaint();
        }

        private void applySavedAnswers(List<String> savedAnswers) {
            if (savedAnswers == null || savedAnswers.isEmpty()) {
                return;
            }
            if (answerHolder.getComponentCount() == 0) {
                return;
            }
            Component first = answerHolder.getComponent(0);
            if (first instanceof JScrollPane sp) {
                JTextArea ta = (JTextArea) sp.getClientProperty("textArea");
                if (ta != null) {
                    ta.setText(savedAnswers.get(0));
                }
                return;
            }
            java.util.Set<String> normalized = new LinkedHashSet<>();
            for (String ans : savedAnswers) {
                if (ans != null) {
                    normalized.add(ans.trim());
                }
            }
            for (Component c : answerHolder.getComponents()) {
                if (c instanceof JRadioButton rb) {
                    rb.setSelected(normalized.contains(rb.getText().trim()));
                } else if (c instanceof JCheckBox cb) {
                    cb.setSelected(normalized.contains(cb.getText().trim()));
                }
            }
        }

        private void style(AbstractButton b){ b.setOpaque(false); b.setForeground(TEXT); b.setFocusPainted(false); b.setFont(b.getFont().deriveFont(14f)); }
        private void style(JTextArea ta){ ta.setBackground(PANEL_SOFT); ta.setForeground(TEXT); ta.setCaretColor(TEXT); ta.setBorder(new EmptyBorder(10,10,10,10)); }

        private List<String> collectAnswers(){
            List<String> out = new ArrayList<>();
            if(answerHolder.getComponentCount()==0) return out;
            Component c0 = answerHolder.getComponent(0);
            if(c0 instanceof JScrollPane sp){
                JTextArea ta = (JTextArea) sp.getClientProperty("textArea");
                out.add(ta.getText());
            } else {
                for(Component c : answerHolder.getComponents()){
                    if(c instanceof JRadioButton rb && rb.isSelected()) out.add(rb.getText());
                    if(c instanceof JCheckBox cb && cb.isSelected()) out.add(cb.getText());
                }
            }
            return out;
        }
    }

    /** Abgabe / Uebersicht */
    private class SubmitPanel extends Surface {
        Runnable onSubmitAll, onBack;
        Consumer<String> onOpenTask;
        private final JTable table;
        private final OverviewTableModel model = new OverviewTableModel();
        SubmitPanel(){
            inner.add(h1("Uebersicht & Abgabe"), BorderLayout.NORTH);
            table = new JTable(model);
            table.setFillsViewportHeight(true); table.setRowHeight(28);
            table.setGridColor(OUTLINE); table.setBackground(PANEL); table.setForeground(TEXT);
            table.getTableHeader().setReorderingAllowed(false);
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            table.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        openSelectedRow();
                    }
                }
            });
            JScrollPane sp = new JScrollPane(table); sp.getViewport().setBackground(PANEL);
            sp.setBorder(BorderFactory.createLineBorder(OUTLINE));
            inner.add(sp, BorderLayout.CENTER);

            JPanel south = new JPanel(new BorderLayout()); south.setOpaque(false);
            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0)); left.setOpaque(false);
            JButton open = pill("Auswahl bestätigen");
            open.addActionListener(e -> openSelectedRow());
            open.setEnabled(false);
            table.getSelectionModel().addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting()) {
                    open.setEnabled(table.getSelectedRow() >= 0);
                }
            });
            left.add(open);

            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0)); right.setOpaque(false);
            JButton back = pill("Zurueck zu Aufgaben"); back.addActionListener(e -> { if(onBack!=null) onBack.run(); });
            JButton submit = pill("Test abgeben"); submit.addActionListener(e -> { if(onSubmitAll!=null) onSubmitAll.run(); });
            right.add(back); right.add(submit);

            south.add(left, BorderLayout.WEST);
            south.add(right, BorderLayout.EAST);
            inner.add(south, BorderLayout.SOUTH);
        }
        void setOverview(List<OverviewRow> rows){ model.setRows(rows); }

        private void openSelectedRow() {
            int selected = table.getSelectedRow();
            if (selected < 0) {
                return;
            }
            int modelIndex = table.convertRowIndexToModel(selected);
            OverviewRow row = model.get(modelIndex);
            if (row != null && onOpenTask != null) {
                onOpenTask.accept(row.taskId());
            }
        }
    }

    /** Pause Overlay */
    private class PausePanel extends JPanel {
        private final JLabel icon = new JLabel("||", SwingConstants.CENTER);
        private final JLabel message = new JLabel("Test pausiert", SwingConstants.CENTER);
        private final JLabel sub = new JLabel("Bitte warten, bis der Lehrer den Test fortsetzt.", SwingConstants.CENTER);

        PausePanel() {
            setBackground(BG);
            setLayout(new GridBagLayout());
            icon.setForeground(ACCENT);
            icon.setFont(icon.getFont().deriveFont(Font.BOLD, 120f));
            message.setForeground(TEXT);
            message.setFont(message.getFont().deriveFont(Font.BOLD, 32f));
            sub.setForeground(TEXT_MUTED);
            sub.setFont(sub.getFont().deriveFont(Font.PLAIN, 20f));

            GridBagConstraints gbc = new GridBagConstraints();
            gbc.gridx = 0;
            gbc.gridy = 0;
            gbc.insets = new Insets(8, 8, 8, 8);
            gbc.anchor = GridBagConstraints.CENTER;
            add(icon, gbc);
            gbc.gridy++;
            add(message, gbc);
            gbc.gridy++;
            add(sub, gbc);
        }

        void showPaused() {
            icon.setForeground(ACCENT);
        }

        void clear() {
            // nothing to clear yet, keep method for symmetry
        }
    }

    /* ------------------------------ Models ------------------------------ */

    public static class TaskViewModel {
        public final String taskId, question, taskType; public final int taskNumber; public final List<String> answers; public final boolean preview;
        public TaskViewModel(String taskId, String question, List<String> answers, int taskNumber, boolean preview, String taskType){
            this.taskId = taskId; this.question = question; this.answers = answers; this.taskNumber = taskNumber; this.preview = preview; this.taskType = taskType;
        }
    }
    public record OverviewRow(String taskId, String question, String selectedAnswer){}

    private class OverviewTableModel extends javax.swing.table.AbstractTableModel {
        private final String[] cols = {"#", "Frage", "Auswahl / Antwort"};
        private final List<OverviewRow> rows = new ArrayList<>();
        
        public void setRows(List<OverviewRow> r) { 
            rows.clear(); 
            if(r != null) {
                // Sort rows by task number to ensure they are in correct order
                r.stream()
                    .sorted((a, b) -> {
                        TaskViewModel taskA = taskCache.get(a.taskId());
                        TaskViewModel taskB = taskCache.get(b.taskId());
                        if (taskA != null && taskB != null) {
                            return Integer.compare(taskA.taskNumber, taskB.taskNumber);
                        }
                        return 0;
                    })
                    .forEach(rows::add);
            }
            fireTableDataChanged(); 
        }
        
        public OverviewRow get(int index){
            if(index < 0 || index >= rows.size()) return null;
            return rows.get(index);
        }
        
        @Override public int getRowCount(){ return rows.size(); }
        @Override public int getColumnCount(){ return cols.length; }
        @Override public String getColumnName(int i){ return cols[i]; }
        @Override public Object getValueAt(int r, int c){ 
            OverviewRow row = rows.get(r);
            TaskViewModel task = taskCache.get(row.taskId());
            return switch(c) {
                case 0 -> task != null ? task.taskNumber : (r + 1);
                case 1 -> row.question();
                default -> formatAnswer(row.selectedAnswer(), task);
            };
        }
        
        private String formatAnswer(String answer, TaskViewModel task) {
            if (task == null || answer == null) {
                return answer;
            }
            // Format MultiChoice answers more clearly
            if ("MultiChoice".equalsIgnoreCase(task.taskType)) {
                String[] answers = answer.split(",");
                return String.join(", ", answers);
            }
            return answer;
        }
    }

    /* ----------------------------- Utilities ---------------------------- */

    private void enterFullscreen() {
        try {
            GraphicsDevice device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            setUndecorated(true);
            if (device.isFullScreenSupported()) {
                device.setFullScreenWindow(this);
            } else {
                setExtendedState(getExtendedState() | JFrame.MAXIMIZED_BOTH);
            }
        } catch (Exception ignored) {
            setExtendedState(getExtendedState() | JFrame.MAXIMIZED_BOTH);
        }
    }

    private void installKeyGuards() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(this::handleForbiddenKey);
    }

    private boolean handleForbiddenKey(KeyEvent event) {
        if (event.getID() != KeyEvent.KEY_PRESSED) {
            return false;
        }
        boolean forbidden = (event.getKeyCode() == KeyEvent.VK_TAB && event.isAltDown())
                || (event.getKeyCode() == KeyEvent.VK_WINDOWS)
                || (event.getKeyCode() == KeyEvent.VK_META)
                || (event.getKeyCode() == KeyEvent.VK_F4 && event.isAltDown());
        if (!forbidden) {
            return false;
        }
        event.consume();
        requestFocusInWindow();
        long now = System.currentTimeMillis();
        if (now - lastWarningMillis > 1200) {
            lastWarningMillis = now;
            String reason = switch (event.getKeyCode()) {
                case KeyEvent.VK_TAB -> "Alt+Tab";
                case KeyEvent.VK_F4 -> "Alt+F4";
                default -> "Windows-Taste";
            };
            SwingUtilities.invokeLater(() ->
                    toast("Diese Tastenkombination ist waehrend des Tests nicht erlaubt."));
            client.sendWarning(reason);
        }
        return true;
    }

    private void toast(String msg){
        JOptionPane.showMessageDialog(this, msg, "Hinweis", JOptionPane.INFORMATION_MESSAGE);
    }

    private void handleGlobalMouseWheel(AWTEvent event) {
        if (!(event instanceof MouseWheelEvent wheelEvent) || !wheelEvent.isControlDown()) {
            return;
        }
        if (!isDisplayable()) {
            return;
        }
        java.awt.Window target = SwingUtilities.getWindowAncestor(wheelEvent.getComponent());
        if (target != this) {
            return;
        }
        int rotation = wheelEvent.getWheelRotation();
        if (rotation < 0) {
            adjustFontScale(FONT_STEP);
        } else if (rotation > 0) {
            adjustFontScale(-FONT_STEP);
        }
        wheelEvent.consume();
    }

    private void adjustFontScale(float delta) {
        float newScale = Math.max(MIN_FONT_SCALE, Math.min(MAX_FONT_SCALE, fontScale + delta));
        if (Math.abs(newScale - fontScale) < 0.001f) {
            return;
        }
        fontScale = newScale;
        SwingUtilities.invokeLater(() -> {
            registerFontTree(getContentPane());
            applyFontScale(getContentPane());
            revalidate();
            repaint();
        });
    }

    private void registerFontTree(Component comp) {
        if (comp == null || baseFonts.containsKey(comp)) {
            if (comp instanceof Container container) {
                for (Component child : container.getComponents()) {
                    registerFontTree(child);
                }
            }
            return;
        }
        Font font = comp.getFont();
        if (font != null) {
            baseFonts.put(comp, font);
        }
        if (comp instanceof JTable table && !tableBaseHeights.containsKey(table)) {
            tableBaseHeights.put(table, table.getRowHeight());
        }
        if (comp instanceof Container container) {
            for (Component child : container.getComponents()) {
                registerFontTree(child);
            }
        }
    }

    private void applyFontScale(Component comp) {
        if (comp == null) {
            return;
        }
        Font base = baseFonts.get(comp);
        if (base != null) {
            comp.setFont(base.deriveFont(base.getSize2D() * fontScale));
        }
        if (comp instanceof JTable table) {
            Integer baseHeight = tableBaseHeights.get(table);
            if (baseHeight != null) {
                table.setRowHeight(Math.max(18, Math.round(baseHeight * fontScale)));
            }
        }
        if (comp instanceof Container container) {
            for (Component child : container.getComponents()) {
                applyFontScale(child);
            }
        }
    }

    @Override
    public void dispose() {
        if (zoomListenerInstalled) {
            Toolkit.getDefaultToolkit().removeAWTEventListener(zoomListener);
            zoomListenerInstalled = false;
        }
        super.dispose();
    }

    /* ------------------------------ Bridge ------------------------------ */

    /**
     * Thin adapter between this UI and your real client (IClient).
     * Implement this on top of your networking client and supply it to the
     * {@link UI} constructor. A ready-to-run dummy is provided below.
     */
    public interface ClientBridge {
        interface UiCallbacks {
            void showWaitingRoom(String exerciseName, int totalTasks);
            void showTask(TaskViewModel task);
            void showOverview(List<OverviewRow> rows);
            void showEnd();
            void showTimeExpired(String message, boolean forceExit);
            void showPauseOverlay();
            void hidePauseOverlay();
            void toast(String message);
            void backToToken();
        }
        void setUiCallbacks(UiCallbacks ui);
        List<IClient.DiscoveredServer> fetchServers();
        void selectServer(IClient.DiscoveredServer server);
        void authenticate(String token);
        void cancelWaiting();
        void requestNextTask();
        void submitAnswer(String taskId, List<String> answers);
        void submitAll();
        void requestOverview();
        void requestTaskById(String taskId);
        void sendWarning(String reason);
        void setHandRaise(boolean raised);
    }

    /* ------------------------------ Demo main --------------------------- */

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            // Replace DummyBridge with your adapter around the real IClient
            UI ui = new UI(new DummyBridge());
            ui.setVisible(true);
        });
    }

    /**
     * Minimal in-memory "client" that simulates the flow for quick UI testing.
     * Replace with an implementation that delegates to your real IClient.
     */
    public static class DummyBridge implements ClientBridge {
        private UiCallbacks ui;
        private final List<IClient.DiscoveredServer> servers = List.of(
                new IClient.DiscoveredServer("192.168.0.10", 7777, "Apfel", "Eine leckere Frucht", "/images/apple.png"),
                new IClient.DiscoveredServer("192.168.0.11", 7777, "Banane", "Gelb und gesund", "/images/banana.png"),
                new IClient.DiscoveredServer("192.168.0.12", 7777, "Kirsche", "Suess und rot", "/images/cherry.png")
        );
        private int idx = 0;

        @Override public void setUiCallbacks(UiCallbacks ui){ this.ui = ui; }
        @Override public List<IClient.DiscoveredServer> fetchServers(){ return servers; }
        @Override public void selectServer(IClient.DiscoveredServer server){ /* no-op for demo */ }
        @Override public void authenticate(String token){ if(ui!=null) ui.showWaitingRoom("Probeklausur", 3); SwingUtilities.invokeLater(this::pushTask); }
        @Override public void cancelWaiting(){ /* demo */ }
        @Override public void requestNextTask(){ idx++; pushTask(); }
        @Override public void submitAnswer(String taskId, List<String> answers){ /* store if needed */ }
        @Override public void submitAll(){
            if(ui!=null){
                ui.toast("Test abgegeben. Danke!");
                ui.showEnd();
            }
        }
        @Override public void requestOverview(){
            if(ui!=null){
                ui.showOverview(List.of(
                    new OverviewRow("t1","Beispielaufgabe 1","Antwortmoeglichkeit 2"),
                    new OverviewRow("t2","Beispielaufgabe 2","A, C"),
                    new OverviewRow("t3","Beispielaufgabe 3","Lorem ipsum...")
                ));
            }
        }

        @Override public void requestTaskById(String taskId){
            if(ui==null) return;
            ui.showTask(new TaskViewModel(taskId == null ? "preview" : taskId,
                    "Review fuer " + (taskId == null ? "Aufgabe" : taskId),
                    List.of("Beobachtung 1", "Beobachtung 2"),
                    0,
                    true,
                    "SingleChoice"));
        }

        @Override public void sendWarning(String reason){
            System.out.println("Demo Warning: " + reason);
        }

        @Override public void setHandRaise(boolean raised){
            System.out.println("Demo HandRaise: " + (raised ? "RAISE" : "LOWER"));
        }

        private void pushTask(){
            if(ui==null) return;
            if(idx==0){
                ui.showTask(new TaskViewModel("t1","Waehle die richtige Antwort.", List.of("Antwortmoeglichkeit 1","Antwortmoeglichkeit 2","Antwortmoeglichkeit 3"), 1,false,"SingleChoice"));
            } else if(idx==1){
                ui.showTask(new TaskViewModel("t2","Mehrere Antworten sind moeglich.", List.of("A","B","C","D"), 2,false,"MultiChoice"));
            } else if(idx==2){
                ui.showTask(new TaskViewModel("t3","Bitte gib hier eine kurze Begruendung ein:", List.of(), 3,false,"TextInput"));
            } else {
                ui.showOverview(List.of(
                    new OverviewRow("t1","Waehle die richtige Antwort.", "Antwortmoeglichkeit 2"),
                    new OverviewRow("t2","Mehrere Antworten sind moeglich.", "A, C"),
                    new OverviewRow("t3","Begruendung", "Lorem ipsum...")
                ));
                ui.showEnd();
            }
        }
    }
}

