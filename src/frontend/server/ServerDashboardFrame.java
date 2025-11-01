package frontend.server;

import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseWheelEvent;
import java.io.File;
import java.net.URL;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.stream.Collectors;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;

/** Modern dashboard for controlling the backend server without console interaction. */
public class ServerDashboardFrame extends JFrame {

    private static final Image APPLICATION_ICON = loadApplicationIcon();

    private final IServer server;
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cardPanel = new JPanel(cardLayout);
    private final RootPanel rootPanel = new RootPanel();
    private final SelectedTestPanel selectedTestPanel = new SelectedTestPanel();
    private final HostingPanel hostingPanel = new HostingPanel();
    private final RunningPanel runningPanel = new RunningPanel();
    private static final float FONT_STEP = 0.05f;
    private static final float MIN_FONT_SCALE = 0.7f;
    private static final float MAX_FONT_SCALE = 1.6f;
    private static final float COUNTDOWN_FONT_STEP = 0.1f;
    private static final float MIN_COUNTDOWN_FONT_SCALE = 0.6f;
    private static final float MAX_COUNTDOWN_FONT_SCALE = 2.4f;
    private final Map<Component, Font> baseFonts = new WeakHashMap<>();
    private final Map<JTable, Integer> tableBaseHeights = new WeakHashMap<>();
    private float fontScale = 1.0f;
    private final AWTEventListener zoomListener = this::handleZoomEvent;
    private boolean zoomListenerInstalled;
    private final Set<String> knownWarningIds = new LinkedHashSet<>();
    private boolean warningNotificationsPrimed;

    private final JLabel ipValueLabel = createInfoValue();
    private final JLabel stateValueLabel = createInfoValue();
    private final JLabel testValueLabel = createInfoValue();
    private final JLabel classValueLabel = createInfoValue();

    private javax.swing.Timer refreshTimer;
    private String lastTokenFile;
    private CountdownFrame countdownFrame;
    private boolean countdownPinned = false;
    private boolean countdownMoveProgrammatic;
    private float countdownFontScale = 1.0f;

    public ServerDashboardFrame() {
        this(new BackendServerAdapter());
    }

    ServerDashboardFrame(IServer server) {
        super("Lernstands-Ueberpruefungssoftware - Server Dashboard");
        this.server = Objects.requireNonNull(server, "server");
        this.server.start();
        configureWindow();
        setContentPane(buildContent());
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (refreshTimer != null) {
                    refreshTimer.stop();
                }
                disposeCountdownFrame();
                ServerDashboardFrame.this.server.shutdown();
            }
        });
        refreshTimer = new javax.swing.Timer(1000, ignored -> refreshState());
        refreshTimer.start();
        refreshState();
        refreshFonts(getContentPane());
        Toolkit.getDefaultToolkit().addAWTEventListener(zoomListener, AWTEvent.MOUSE_WHEEL_EVENT_MASK);
        zoomListenerInstalled = true;
    }

    private void configureWindow() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        if (APPLICATION_ICON != null) {
            setIconImage(APPLICATION_ICON);
        }
        setMinimumSize(new Dimension(1024, 640));
        setLocationRelativeTo(null);
    }

    private static Image loadApplicationIcon() {
        URL resource = ServerDashboardFrame.class.getResource("/icon.png");
        if (resource != null) {
            return new ImageIcon(resource).getImage();
        }
        File file = new File("data/icon.png");
        if (file.isFile()) {
            return new ImageIcon(file.getAbsolutePath()).getImage();
        }
        return null;
    }

    private JComponent buildContent() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(ServerTheme.WINDOW_BACKGROUND);

        root.add(buildHeader(), BorderLayout.NORTH);

        cardPanel.setOpaque(false);
        cardPanel.add(rootPanel, "ROOT");
        cardPanel.add(selectedTestPanel, "TEST");
        cardPanel.add(hostingPanel, "HOSTING");
        cardPanel.add(runningPanel, "RUNNING");

        JPanel centerWrapper = new JPanel(new BorderLayout());
        centerWrapper.setOpaque(false);
        centerWrapper.setBorder(new EmptyBorder(30, 30, 30, 30));
        centerWrapper.add(cardPanel, BorderLayout.CENTER);
        root.add(centerWrapper, BorderLayout.CENTER);

        return root;
    }

    private JComponent buildHeader() {
        JPanel header = new JPanel(new GridLayout(1, 4, 12, 0));
        header.setBackground(ServerTheme.CANVAS_BACKGROUND);
        header.setBorder(new EmptyBorder(18, 24, 12, 24));

        header.add(buildInfoBlock("Server IP", ipValueLabel));
        header.add(buildInfoBlock("Status", stateValueLabel));
        header.add(buildInfoBlock("Test", testValueLabel));
        header.add(buildInfoBlock("Klasse", classValueLabel));
        return header;
    }

    private JPanel buildInfoBlock(String label, JLabel value) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        JLabel title = new JLabel(label.toUpperCase(Locale.ROOT));
        title.setFont(ServerTheme.FONT_LABEL.deriveFont(12f));
        title.setForeground(ServerTheme.TEXT_SECONDARY);
        panel.add(title, BorderLayout.NORTH);
        panel.add(value, BorderLayout.CENTER);
        return panel;
    }

    private JLabel createInfoValue() {
        JLabel label = new JLabel("-");
        label.setForeground(ServerTheme.TEXT_PRIMARY);
        label.setFont(ServerTheme.FONT_HEADING);
        return label;
    }

    private void refreshState() {
        ipValueLabel.setText(server.getBoundIp());
        testValueLabel.setText(Optional.ofNullable(server.getSelectedTestName()).orElse("-").trim());
        classValueLabel.setText(Optional.ofNullable(server.getSelectedClassName()).orElse("-").trim());

        IServer.DashboardState state = server.getDashboardState();
        IServer.TimerStatus timerStatus = server.getTimerStatus();
        List<IServer.Warning> warnings = server.getActiveWarnings();
        handleWarningNotifications(warnings);

        switch (state) {
            case ROOT -> {
                stateValueLabel.setText("Root");
                cardLayout.show(cardPanel, "ROOT");
            }
            case TEST_SELECTED -> {
                stateValueLabel.setText("Test gewaehlt");
                selectedTestPanel.refresh();
                cardLayout.show(cardPanel, "TEST");
            }
            case HOSTING -> {
                stateValueLabel.setText("Hosting");
                hostingPanel.refresh(timerStatus);
                cardLayout.show(cardPanel, "HOSTING");
            }
            case RUNNING -> {
                stateValueLabel.setText("Testlauf");
                runningPanel.refresh(timerStatus, warnings);
                cardLayout.show(cardPanel, "RUNNING");
            }
        }
        updateCountdownFrame(state, timerStatus);
    }

    private void handleWarningNotifications(List<IServer.Warning> warnings) {
        List<IServer.Warning> safe = warnings == null ? List.of() : warnings;
        if (!warningNotificationsPrimed) {
            knownWarningIds.clear();
            for (IServer.Warning warning : safe) {
                if (warning != null && warning.id() != null) {
                    knownWarningIds.add(warning.id());
                }
            }
            warningNotificationsPrimed = true;
            return;
        }
        Set<String> currentIds = safe.stream()
                .filter(Objects::nonNull)
                .map(IServer.Warning::id)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (IServer.Warning warning : safe) {
            if (warning == null || warning.id() == null) {
                continue;
            }
            if (knownWarningIds.add(warning.id())) {
                Toolkit.getDefaultToolkit().beep();
                runningPanel.showWarningBanner(warning);
            }
        }
        knownWarningIds.retainAll(currentIds);
    }

    private void showTestSelection() {
        TestSelectionDialog dialog = new TestSelectionDialog(this, server);
        IServer.TestSummary selected = dialog.open();
        if (selected != null) {
            try {
                server.selectTestById(selected.id());
                refreshState();
            } catch (RuntimeException ex) {
                showError("Fehler beim Laden des Tests: " + ex.getMessage());
            }
        }
    }

    private void showClassSelection() {
        ClassSelectionDialog dialog = new ClassSelectionDialog(this, server);
        IServer.ClassSummary chosen = dialog.open();
        if (chosen != null) {
            try {
                int durationMinutes = selectedTestPanel.getDurationMinutes();
                lastTokenFile = server.prepareHostingForClass(chosen.id(), durationMinutes);
                JOptionPane.showMessageDialog(this,
                        "Tokenliste erstellt: " + lastTokenFile,
                        "Tokenliste",
                        JOptionPane.INFORMATION_MESSAGE);
                refreshState();
            } catch (RuntimeException ex) {
                showError("Fehler beim Starten des Hostings: " + ex.getMessage());
            }
        }
    }

    private void showTestImportDialog() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Testdatei importieren");
        chooser.setFileFilter(new FileNameExtensionFilter("JSON-Dateien (*.json)", "json"));
        int choice = chooser.showOpenDialog(this);
        if (choice != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File selectedFile = chooser.getSelectedFile();
        if (selectedFile == null) {
            showError("Keine Datei ausgewählt.");
            return;
        }
        try {
            IServer.ImportResult result = server.importTestFromFile(selectedFile);
            if (result == null) {
                showError("Import fehlgeschlagen: Kein Ergebnis vom Server.");
                return;
            }
            if (result.success()) {
                String message = result.message();
                if (message == null || message.isBlank()) {
                    message = "Test erfolgreich importiert.";
                }
                JOptionPane.showMessageDialog(this, message, "Import erfolgreich", JOptionPane.INFORMATION_MESSAGE);
                refreshState();
            } else {
                String message = result.message();
                if (message == null || message.isBlank()) {
                    message = "Import fehlgeschlagen.";
                }
                showError(message);
            }
        } catch (RuntimeException ex) {
            showError("Import fehlgeschlagen: " + ex.getMessage());
        }
    }

    private void showCorrectionDialog() {
        try (CorrectionDialog dialog = new CorrectionDialog(this)) {
            dialog.setVisible(true);
        } catch (Exception ex) {
            showError("Fehler im Korrekturmodus: " + ex.getMessage());
        }
    }

    private void showGradeOverviewDialog() {
        try (GradeOverviewDialog dialog = new GradeOverviewDialog(this)) {
            dialog.setVisible(true);
        } catch (Exception ex) {
            showError("Fehler in der Notenübersicht: " + ex.getMessage());
        }
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Fehler", JOptionPane.ERROR_MESSAGE);
    }

    private String formatDurationMinutes(IServer.TimerStatus status) {
        if (status == null) {
            return "keine Begrenzung";
        }
        long minutes = (status.totalSeconds() + 59) / 60;
        if (minutes <= 0) {
            return "keine Begrenzung";
        }
        return minutes == 1 ? "1 Minute" : minutes + " Minuten";
    }

    private static String formatTime(long seconds) {
        if (seconds < 0) {
            seconds = 0;
        }
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, secs);
    }

    private void updateCountdownFrame(IServer.DashboardState state, IServer.TimerStatus status) {
        if (status == null) {
            status = new IServer.TimerStatus(0, 0, false, false);
        }
        boolean shouldShow = state == IServer.DashboardState.RUNNING && status.totalSeconds() > 0;
        if (shouldShow) {
            boolean created = false;
            if (countdownFrame == null) {
                countdownFrame = new CountdownFrame();
                created = true;
                runningPanel.updateCountdownPinButton();
            }
            if (created && !countdownPinned) {
                countdownMoveProgrammatic = true;
                try {
                    countdownFrame.setLocationRelativeTo(this);
                } finally {
                    SwingUtilities.invokeLater(() -> countdownMoveProgrammatic = false);
                }
            }
            countdownFrame.applyFontScale(countdownFontScale);
            countdownFrame.update(status);
            if (countdownPinned) {
                positionCountdownFrame();
            }
        } else {
            disposeCountdownFrame();
        }
    }

    private void setCountdownPinned(boolean pinned) {
        if (countdownPinned == pinned) {
            return;
        }
        countdownPinned = pinned;
        if (countdownPinned && countdownFrame != null) {
            positionCountdownFrame();
        }
        runningPanel.updateCountdownPinButton();
    }

    private void positionCountdownFrame() {
        if (countdownFrame == null || !countdownPinned) {
            return;
        }
        if (!isShowing()) {
            countdownMoveProgrammatic = true;
            countdownFrame.setLocationRelativeTo(this);
            SwingUtilities.invokeLater(() -> countdownMoveProgrammatic = false);
            return;
        }
        try {
            Point base = getLocationOnScreen();
            Dimension parentSize = getSize();
            Dimension timerSize = countdownFrame.getSize();
            int x = base.x + parentSize.width - timerSize.width - 24;
            int y = base.y + 60;
            countdownMoveProgrammatic = true;
            countdownFrame.setLocation(x, y);
            SwingUtilities.invokeLater(() -> countdownMoveProgrammatic = false);
        } catch (IllegalComponentStateException ignored) {
            countdownMoveProgrammatic = true;
            countdownFrame.setLocationRelativeTo(this);
            SwingUtilities.invokeLater(() -> countdownMoveProgrammatic = false);
        }
    }

    private void disposeCountdownFrame() {
        if (countdownFrame != null) {
            countdownFrame.dispose();
            countdownFrame = null;
            countdownMoveProgrammatic = false;
            runningPanel.updateCountdownPinButton();
        }
    }

    @Override
    public void dispose() {
        disposeCountdownFrame();
        if (zoomListenerInstalled) {
            Toolkit.getDefaultToolkit().removeAWTEventListener(zoomListener);
            zoomListenerInstalled = false;
        }
        super.dispose();
    }

    private void handleZoomEvent(AWTEvent event) {
        if (!(event instanceof MouseWheelEvent wheelEvent) || !wheelEvent.isControlDown()) {
            return;
        }
        Window window = SwingUtilities.getWindowAncestor(wheelEvent.getComponent());
        if (window != this && !(window instanceof Dialog dialog && dialog.getOwner() == this)) {
            return;
        }
        Component targetRoot = null;
        if (window instanceof RootPaneContainer rpc) {
            targetRoot = rpc.getContentPane();
        }
        int rotation = wheelEvent.getWheelRotation();
        if (rotation < 0) {
            adjustFontScale(FONT_STEP, targetRoot);
        } else if (rotation > 0) {
            adjustFontScale(-FONT_STEP, targetRoot);
        }
        wheelEvent.consume();
    }

    private void adjustFontScale(float delta, Component additionalRoot) {
        float newScale = Math.max(MIN_FONT_SCALE, Math.min(MAX_FONT_SCALE, fontScale + delta));
        if (Math.abs(newScale - fontScale) < 0.001f) {
            return;
        }
        fontScale = newScale;
        SwingUtilities.invokeLater(() -> {
            refreshFonts(getContentPane());
            if (additionalRoot != null && additionalRoot != getContentPane()) {
                refreshFonts(additionalRoot);
            }
            revalidate();
            repaint();
        });
    }

    private void adjustCountdownFontScale(float delta) {
        float newScale = Math.max(MIN_COUNTDOWN_FONT_SCALE, Math.min(MAX_COUNTDOWN_FONT_SCALE, countdownFontScale + delta));
        if (Math.abs(newScale - countdownFontScale) < 0.001f) {
            return;
        }
        countdownFontScale = newScale;
        if (countdownFrame != null) {
            countdownFrame.applyFontScale(newScale);
        }
    }

    private void registerFontTree(Component component) {
        if (component == null) {
            return;
        }
        if (!baseFonts.containsKey(component)) {
            Font font = component.getFont();
            if (font != null) {
                baseFonts.put(component, font);
            }
        }
        if (component instanceof JTable table && !tableBaseHeights.containsKey(table)) {
            tableBaseHeights.put(table, table.getRowHeight());
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                registerFontTree(child);
            }
        }
    }

    private void applyFontScale(Component component) {
        if (component == null) {
            return;
        }
        Font base = baseFonts.get(component);
        if (base != null) {
            component.setFont(base.deriveFont(base.getSize2D() * fontScale));
        }
        if (component instanceof JTable table) {
            Integer baseHeight = tableBaseHeights.get(table);
            if (baseHeight != null) {
                table.setRowHeight(Math.max(18, Math.round(baseHeight * fontScale)));
            }
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                applyFontScale(child);
            }
        }
    }

    private void refreshFonts(Component root) {
        if (root == null) {
            return;
        }
        registerFontTree(root);
        applyFontScale(root);
    }

    private class RootPanel extends RoundedPanel {
        RootPanel() {
            setPanelBackground(ServerTheme.CARD_BACKGROUND);
            setLayout(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.gridx = 0;
            gbc.gridy = 0;
            gbc.insets = new Insets(12, 12, 12, 12);
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.weightx = 1;

            add(createActionButton("Test laden", ignored -> showTestSelection()), gbc);
            gbc.gridy++;
            add(createActionButton("Test importieren", ignored -> showTestImportDialog()), gbc);
            gbc.gridy++;
            add(createActionButton("Korrekturmodus", ignored -> showCorrectionDialog()), gbc);
            gbc.gridy++;
            add(createActionButton("Notenübersicht", ignored -> showGradeOverviewDialog()), gbc);
            gbc.gridy++;
            add(createActionButton("Server beenden", ignored -> {
                refreshTimer.stop();
                server.shutdown();
                dispose();
            }), gbc);
        }

        private AccentButton createActionButton(String text, java.awt.event.ActionListener listener) {
            AccentButton button = new AccentButton(text);
            button.setPreferredSize(new Dimension(220, 48));
            button.addActionListener(listener);
            return button;
        }
    }

    private class SelectedTestPanel extends RoundedPanel {
        private final JLabel nameLabel = new JLabel();
        private final JLabel taskLabel = new JLabel();
        private final JSpinner durationSpinner = new JSpinner(new SpinnerNumberModel(45, 0, 600, 5));

        SelectedTestPanel() {
            setPanelBackground(ServerTheme.CARD_BACKGROUND);
            setLayout(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.gridx = 0;
            gbc.gridy = 0;
            gbc.gridwidth = 2;
            gbc.anchor = GridBagConstraints.WEST;
            gbc.insets = new Insets(8, 8, 8, 8);

            JLabel title = new JLabel("Testdetails");
            title.setForeground(ServerTheme.TEXT_PRIMARY);
            title.setFont(ServerTheme.FONT_HEADING);
            add(title, gbc);

            gbc.gridy++;
            gbc.gridwidth = 1;
            add(createDescriptorLabel("Name:"), gbc);
            gbc.gridx = 1;
            add(nameLabel, gbc);

            gbc.gridx = 0;
            gbc.gridy++;
            add(createDescriptorLabel("Aufgaben:"), gbc);
            gbc.gridx = 1;
            add(taskLabel, gbc);

            gbc.gridx = 0;
            gbc.gridy++;
            gbc.gridwidth = 2;
            gbc.anchor = GridBagConstraints.CENTER;
            gbc.insets = new Insets(18, 8, 8, 8);

            gbc.gridy++;
            gbc.gridwidth = 1;
            gbc.anchor = GridBagConstraints.WEST;
            gbc.insets = new Insets(8, 8, 8, 8);
            add(createDescriptorLabel("Dauer (Minuten):"), gbc);
            gbc.gridx = 1;
            durationSpinner.setEditor(new JSpinner.NumberEditor(durationSpinner, "##0"));
            JFormattedTextField durationField = ((JSpinner.NumberEditor) durationSpinner.getEditor()).getTextField();
            durationField.setColumns(4);
            durationField.setHorizontalAlignment(JTextField.RIGHT);
            durationField.setFont(ServerTheme.FONT_BODY);
            add(durationSpinner, gbc);

            gbc.gridx = 0;
            gbc.gridy++;
            gbc.gridwidth = 2;
            gbc.anchor = GridBagConstraints.CENTER;
            gbc.insets = new Insets(18, 8, 8, 8);

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
            buttons.setOpaque(false);
            AccentButton hostButton = new AccentButton("Server hosten");
            hostButton.addActionListener(ignored -> showClassSelection());
            AccentButton cancelButton = new AccentButton("Zurueck");
            cancelButton.addActionListener(ignored -> {
                server.resetToRoot();
                refreshState();
            });
            buttons.add(cancelButton);
            buttons.add(hostButton);
            add(buttons, gbc);

            nameLabel.setForeground(ServerTheme.TEXT_PRIMARY);
            nameLabel.setFont(ServerTheme.FONT_BODY);
            taskLabel.setForeground(ServerTheme.TEXT_PRIMARY);
            taskLabel.setFont(ServerTheme.FONT_BODY);
        }

        void refresh() {
            nameLabel.setText(Optional.ofNullable(server.getSelectedTestName()).orElse("-"));
            taskLabel.setText(Integer.toString(server.getSelectedTaskCount()));
        }

        int getDurationMinutes() {
            return Math.max(0, ((Number) durationSpinner.getValue()).intValue());
        }
    }

    private class HostingPanel extends RoundedPanel {
        private final ClientTableModel model = new ClientTableModel();
        private final JLabel tokenLabel = new JLabel();
        private final JLabel durationLabel = new JLabel();

        HostingPanel() {
            setPanelBackground(ServerTheme.CARD_BACKGROUND);
            setLayout(new BorderLayout(18, 18));
            setBorder(new EmptyBorder(24, 24, 24, 24));

            JLabel title = new JLabel("Warteraum - Clients");
            title.setForeground(ServerTheme.TEXT_PRIMARY);
            title.setFont(ServerTheme.FONT_HEADING);
            add(title, BorderLayout.NORTH);

            JTable table = new JTable(model);
            table.setFillsViewportHeight(true);
            table.setBackground(ServerTheme.CANVAS_BACKGROUND);
            table.setForeground(ServerTheme.TEXT_PRIMARY);
            table.setRowHeight(28);
            configureHandRaiseColumn(table);
            JScrollPane scrollPane = new JScrollPane(table);
            scrollPane.getViewport().setBackground(ServerTheme.CANVAS_BACKGROUND);
            add(scrollPane, BorderLayout.CENTER);

            JPanel bottom = new JPanel(new BorderLayout());
            bottom.setOpaque(false);
            tokenLabel.setForeground(ServerTheme.TEXT_SECONDARY);
            tokenLabel.setFont(ServerTheme.FONT_BODY);
            durationLabel.setForeground(ServerTheme.TEXT_SECONDARY);
            durationLabel.setFont(ServerTheme.FONT_BODY);
            JPanel infoPanel = new JPanel();
            infoPanel.setOpaque(false);
            infoPanel.setLayout(new BoxLayout(infoPanel, BoxLayout.Y_AXIS));
            infoPanel.add(tokenLabel);
            infoPanel.add(durationLabel);
            bottom.add(infoPanel, BorderLayout.WEST);

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
            buttons.setOpaque(false);
            AccentButton abortButton = new AccentButton("Testat abbrechen");
            abortButton.addActionListener(ignored -> {
                server.abortHosting();
                refreshState();
            });
            AccentButton startButton = new AccentButton("Testat beginnen");
            startButton.addActionListener(ignored -> {
                server.startTestRun();
                refreshState();
            });
            buttons.add(abortButton);
            buttons.add(startButton);
            bottom.add(buttons, BorderLayout.EAST);
            add(bottom, BorderLayout.SOUTH);
        }

        void refresh(IServer.TimerStatus status) {
            List<IServer.ClientSummary> clients = server.getClientSummaries();
            model.setClients(clients);
            tokenLabel.setText(lastTokenFile == null ? "Tokenliste: -" : "Tokenliste: " + lastTokenFile);
            durationLabel.setText("Geplante Dauer: " + formatDurationMinutes(status));
        }
    }

    private class RunningPanel extends RoundedPanel {
        private final ClientTableModel model = new ClientTableModel();
        private final WarningPanel warningPanel = new WarningPanel();
        private final AccentButton timerPinButton = new AccentButton("");
        private final AccentButton addTimeButton = new AccentButton("Extra-Zeit");
        private final AccentButton pauseButton = new AccentButton("Test pausieren");
        private final JLabel pauseIndicator = new JLabel();
        private boolean paused;
        private final JLabel warningBanner = new JLabel();
        private javax.swing.Timer warningBannerTimer;

        RunningPanel() {
            setPanelBackground(ServerTheme.CARD_BACKGROUND);
            setLayout(new BorderLayout(18, 18));
            setBorder(new EmptyBorder(24, 24, 24, 24));

            JLabel title = new JLabel("Testlauf");
            title.setForeground(ServerTheme.TEXT_PRIMARY);
            title.setFont(ServerTheme.FONT_HEADING);
            warningBanner.setForeground(new Color(231, 76, 60));
            warningBanner.setFont(ServerTheme.FONT_BODY);
            warningBanner.setHorizontalAlignment(SwingConstants.RIGHT);
            warningBanner.setVisible(false);
            JPanel header = new JPanel(new BorderLayout());
            header.setOpaque(false);
            header.add(title, BorderLayout.WEST);
            header.add(warningBanner, BorderLayout.EAST);
            add(header, BorderLayout.NORTH);

            JTable table = new JTable(model);
            table.setFillsViewportHeight(true);
            table.setBackground(ServerTheme.CANVAS_BACKGROUND);
            table.setForeground(ServerTheme.TEXT_PRIMARY);
            table.setRowHeight(28);
            configureHandRaiseColumn(table);
            JScrollPane scrollPane = new JScrollPane(table);
            scrollPane.getViewport().setBackground(ServerTheme.CANVAS_BACKGROUND);

            JPanel center = new JPanel(new BorderLayout(18, 0));
            center.setOpaque(false);
            center.add(scrollPane, BorderLayout.CENTER);
            center.add(warningPanel, BorderLayout.EAST);
            add(center, BorderLayout.CENTER);

            timerPinButton.addActionListener(ignored -> {
                setCountdownPinned(!countdownPinned);
            });
            addTimeButton.addActionListener(ignored -> promptExtraTime());

            pauseIndicator.setForeground(new Color(231, 76, 60));
            pauseIndicator.setFont(ServerTheme.FONT_BODY);
            pauseIndicator.setText("");

            pauseButton.addActionListener(ignored -> {
                if (paused) {
                    server.resumeTestRun();
                } else {
                    server.pauseTestRun();
                }
                refreshState();
            });

            AccentButton endButton = new AccentButton("Test beenden");
            endButton.addActionListener(ignored -> {
                server.endRunningTest();
                refreshState();
            });
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
            buttons.setOpaque(false);
            buttons.add(timerPinButton);
            buttons.add(addTimeButton);
            buttons.add(pauseButton);
            buttons.add(endButton);
            JPanel bottom = new JPanel(new BorderLayout());
            bottom.setOpaque(false);
            bottom.add(pauseIndicator, BorderLayout.WEST);
            bottom.add(buttons, BorderLayout.EAST);
            add(bottom, BorderLayout.SOUTH);

            updateCountdownPinButton();
        }

        void updateCountdownPinButton() {
            if (timerPinButton == null) {
                return;
            }
            boolean hasCountdown = countdownFrame != null;
            timerPinButton.setEnabled(hasCountdown);
            if (!hasCountdown) {
                timerPinButton.setText("Timer andocken");
            } else {
                timerPinButton.setText(countdownPinned ? "Timer loesen" : "Timer andocken");
            }
        }

        private void promptExtraTime() {
            SpinnerNumberModel model = new SpinnerNumberModel(5, 1, 240, 1);
            JSpinner spinner = new JSpinner(model);
            spinner.setEditor(new JSpinner.NumberEditor(spinner, "#"));
            int result = JOptionPane.showConfirmDialog(
                    ServerDashboardFrame.this,
                    spinner,
                    "Extra-Zeit (Minuten)",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.QUESTION_MESSAGE
            );
            if (result == JOptionPane.OK_OPTION) {
                try {
                    spinner.commitEdit();
                } catch (ParseException ignored) {
                    // ignore invalid input, fallback to current model value
                }
                Number value = (Number) spinner.getValue();
                int minutes = value == null ? 0 : value.intValue();
                if (minutes > 0) {
                    server.addExtraTimeMinutes(minutes);
                    ServerDashboardFrame.this.refreshState();
                }
            }
        }

        void refresh(IServer.TimerStatus status, List<IServer.Warning> warnings) {
            model.setClients(server.getClientSummaries());
            List<IServer.Warning> safe = warnings == null ? List.of() : warnings;
            warningPanel.setWarnings(safe);
            if (safe.isEmpty()) {
                hideWarningBanner();
            }
            boolean isPaused = status != null && status.paused();
            this.paused = isPaused;
            pauseButton.setText(isPaused ? "Test fortsetzen" : "Test pausieren");
            pauseIndicator.setText(isPaused ? "Test pausiert" : "");
            updateCountdownPinButton();
        }

        void showWarningBanner(IServer.Warning warning) {
            if (warning == null) {
                return;
            }
            String headline = (warning.studentName() == null || warning.studentName().isBlank())
                    ? warning.clientId()
                    : warning.studentName();
            String text = headline == null || headline.isBlank()
                    ? warning.message()
                    : headline + " - " + warning.message();
            warningBanner.setText("Neue Warnung: " + text);
            warningBanner.setVisible(true);
            if (warningBannerTimer != null && warningBannerTimer.isRunning()) {
                warningBannerTimer.stop();
            }
            javax.swing.Timer timer = new javax.swing.Timer(8000, evt -> warningBanner.setVisible(false));
            timer.setRepeats(false);
            timer.start();
            warningBannerTimer = timer;
        }

        void hideWarningBanner() {
            if (warningBannerTimer != null) {
                warningBannerTimer.stop();
                warningBannerTimer = null;
            }
            warningBanner.setVisible(false);
            warningBanner.setText("");
        }
    }

    private class CountdownFrame extends JFrame {
        private final JLabel timeLabel = new JLabel("00:00:00", SwingConstants.CENTER);
        private final JLabel statusLabel = new JLabel("", SwingConstants.CENTER);
        private final Color pausedColor = new Color(231, 76, 60);
        private final Font timeBaseFont = ServerTheme.FONT_HEADING.deriveFont(38f);
        private final Font statusBaseFont = ServerTheme.FONT_BODY.deriveFont(18f);
        private float appliedScale = Float.NaN;

        CountdownFrame() {
            super("Testzeit");
            if (APPLICATION_ICON != null) {
                setIconImage(APPLICATION_ICON);
            }
            setAlwaysOnTop(true);
            setResizable(true);
            setMinimumSize(new Dimension(260, 160));
            setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            JPanel content = new JPanel(new BorderLayout());
            content.setBackground(ServerTheme.CANVAS_BACKGROUND);
            content.setBorder(new EmptyBorder(18, 24, 18, 24));
            timeLabel.setForeground(ServerTheme.ACCENT);
            statusLabel.setForeground(ServerTheme.TEXT_SECONDARY);
            content.add(timeLabel, BorderLayout.CENTER);
            content.add(statusLabel, BorderLayout.SOUTH);
            setContentPane(content);

            MouseAdapter zoomAdapter = new MouseAdapter() {
                @Override
                public void mouseWheelMoved(MouseWheelEvent e) {
                    if (!e.isControlDown()) {
                        return;
                    }
                    int rotation = e.getWheelRotation();
                    if (rotation < 0) {
                        adjustCountdownFontScale(COUNTDOWN_FONT_STEP);
                    } else if (rotation > 0) {
                        adjustCountdownFontScale(-COUNTDOWN_FONT_STEP);
                    }
                    e.consume();
                }
            };
            addMouseWheelListener(zoomAdapter);
            content.addMouseWheelListener(zoomAdapter);
            addComponentListener(new ComponentAdapter() {
                @Override
                public void componentMoved(ComponentEvent e) {
                    if (countdownMoveProgrammatic) {
                        countdownMoveProgrammatic = false;
                    } else {
                        setCountdownPinned(false);
                    }
                }
            });

            applyFontScale(countdownFontScale);
            pack();
        }

        void applyFontScale(float scale) {
            float clamped = Math.max(MIN_COUNTDOWN_FONT_SCALE, Math.min(MAX_COUNTDOWN_FONT_SCALE, scale));
            if (!Float.isNaN(appliedScale) && Math.abs(clamped - appliedScale) < 0.001f) {
                return;
            }
            appliedScale = clamped;
            timeLabel.setFont(timeBaseFont.deriveFont(timeBaseFont.getSize2D() * clamped));
            statusLabel.setFont(statusBaseFont.deriveFont(statusBaseFont.getSize2D() * clamped));
            revalidate();
            repaint();
        }

        void update(IServer.TimerStatus status) {
            timeLabel.setText(formatTime(status.remainingSeconds()));
            if (status.paused()) {
                timeLabel.setForeground(pausedColor);
                statusLabel.setForeground(pausedColor);
                statusLabel.setText("Test pausiert");
            } else if (!status.running() && status.remainingSeconds() <= 0) {
                timeLabel.setForeground(ServerTheme.TEXT_PRIMARY);
                statusLabel.setForeground(pausedColor);
                statusLabel.setText("Zeit abgelaufen");
            } else {
                timeLabel.setForeground(ServerTheme.ACCENT);
                statusLabel.setForeground(ServerTheme.TEXT_SECONDARY);
                statusLabel.setText("Test laeuft");
            }
            if (!isVisible()) {
                setVisible(true);
            }
        }
    }

    private class WarningPanel extends JPanel {
        private final JPanel listPanel = new JPanel();

        WarningPanel() {
            setOpaque(false);
            setPreferredSize(new Dimension(320, 0));
            setLayout(new BorderLayout(8, 8));
            setBorder(new EmptyBorder(0, 12, 0, 0));

            JLabel label = new JLabel("Warnungen");
            label.setForeground(ServerTheme.TEXT_PRIMARY);
            label.setFont(ServerTheme.FONT_LABEL);
            add(label, BorderLayout.NORTH);

            listPanel.setOpaque(false);
            listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
            JScrollPane scrollPane = new JScrollPane(listPanel);
            scrollPane.setPreferredSize(new Dimension(300, 0));
            scrollPane.getViewport().setBackground(ServerTheme.CANVAS_BACKGROUND);
            scrollPane.setBorder(BorderFactory.createLineBorder(ServerTheme.CANVAS_BACKGROUND));
            add(scrollPane, BorderLayout.CENTER);
        }

        void setWarnings(List<IServer.Warning> warnings) {
            listPanel.removeAll();
            if (warnings == null || warnings.isEmpty()) {
                JLabel none = new JLabel("Keine Warnungen");
                none.setForeground(ServerTheme.TEXT_SECONDARY);
                none.setFont(ServerTheme.FONT_BODY);
                listPanel.add(none);
            } else {
                for (IServer.Warning warning : warnings) {
                    JPanel row = new JPanel(new BorderLayout());
                    row.setOpaque(false);
                    row.setBorder(new EmptyBorder(6, 6, 6, 6));
                    String headline = warning.studentName() == null || warning.studentName().isBlank()
                            ? warning.clientId()
                            : warning.studentName();
                    JLabel info = new JLabel("<html><b>" + headline + "</b><br/>" + warning.message()
                            + "<br/><small>" + warning.timestamp() + "</small></html>");
                    info.setForeground(ServerTheme.TEXT_PRIMARY);
                    info.setFont(ServerTheme.FONT_BODY);
                    row.add(info, BorderLayout.CENTER);

                    AccentButton dismiss = new AccentButton("Ok");
                    dismiss.addActionListener(ignored -> {
                        server.dismissWarning(warning.id());
                        refreshState();
                    });
                    JPanel btnWrapper = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
                    btnWrapper.setOpaque(false);
                    btnWrapper.add(dismiss);
                    row.add(btnWrapper, BorderLayout.EAST);
                    listPanel.add(row);
                    listPanel.add(Box.createVerticalStrut(8));
                }
            }
            ServerDashboardFrame.this.registerFontTree(listPanel);
            ServerDashboardFrame.this.applyFontScale(listPanel);
            listPanel.revalidate();
            listPanel.repaint();
        }
    }

    private JLabel createDescriptorLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(ServerTheme.TEXT_SECONDARY);
        label.setFont(ServerTheme.FONT_BODY);
        return label;
    }

    private void configureHandRaiseColumn(JTable table) {
        if (table == null || table.getColumnModel().getColumnCount() == 0) {
            return;
        }
        TableColumn column = table.getColumnModel().getColumn(0);
        column.setMinWidth(44);
        column.setPreferredWidth(48);
        column.setMaxWidth(56);
        column.setResizable(false);
        DefaultTableCellRenderer renderer = new DefaultTableCellRenderer();
        renderer.setHorizontalAlignment(SwingConstants.CENTER);
        renderer.setForeground(ServerTheme.TEXT_PRIMARY);
        renderer.setBackground(ServerTheme.CANVAS_BACKGROUND);
        column.setCellRenderer(renderer);
    }

    private static class ClientTableModel extends AbstractTableModel {
        private static final String HAND_ICON = "\uD83D\uDC4B";
        private final String[] columns = {"", "Client", "Schueler", "IP", "Status"};
        private List<IServer.ClientSummary> clients = List.of();

        void setClients(List<IServer.ClientSummary> clients) {
            this.clients = clients;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return clients.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            IServer.ClientSummary client = clients.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> client.handRaised() ? HAND_ICON : "";
                case 1 -> client.clientId();
                case 2 -> client.studentName();
                case 3 -> client.remoteIp();
                case 4 -> formatStatus(client);
                default -> "";
            };
        }

        boolean isHandRaised(int row) {
            if (row < 0 || row >= clients.size()) {
                return false;
            }
            return clients.get(row).handRaised();
        }

        private String formatStatus(IServer.ClientSummary client) {
            String status = Optional.ofNullable(client.status()).orElse("-").trim();
            int total = client.totalTasks();
            if (total <= 0) {
                return status;
            }
            int current = client.currentTaskNumber();
            if (current < 0) {
                current = 0;
            }
            return status + " (Frage " + current + " / " + total + ")";
        }
    }

    private static class TestSelectionDialog extends JDialog {
        private final IServer server;
        private IServer.TestSummary selected;
        private final DefaultListModel<IServer.TestSummary> model = new DefaultListModel<>();
        private final JList<IServer.TestSummary> list = new JList<>(model);

        TestSelectionDialog(JFrame owner, IServer server) {
            super(owner, "Test auswaehlen", true);
            this.server = server;
            buildUi();
            loadData();
            if (owner instanceof ServerDashboardFrame frame) {
                frame.refreshFonts(getContentPane());
            }
        }

        private void buildUi() {
            setSize(480, 360);
            setLocationRelativeTo(getOwner());
            setLayout(new BorderLayout(12, 12));
            ((JComponent) getContentPane()).setBorder(new EmptyBorder(16, 16, 16, 16));
            list.setCellRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                    JLabel lbl = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                    if (value instanceof IServer.TestSummary ts) {
                        int taskCount = server.getTaskCountForTest(ts.id());
                        lbl.setText(ts.name() + " (" + taskCount + " Aufgaben)");
                    }
                    lbl.setFont(ServerTheme.FONT_BODY);
                    return lbl;
                }
            });
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            add(new JScrollPane(list), BorderLayout.CENTER);

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
            AccentButton cancel = new AccentButton("Abbrechen");
            cancel.addActionListener(ignored -> {
                selected = null;
                dispose();
            });
            AccentButton confirm = new AccentButton("Auswaehlen");
            confirm.addActionListener(ignored -> {
                selected = list.getSelectedValue();
                dispose();
            });
            buttons.add(cancel);
            buttons.add(confirm);
            add(buttons, BorderLayout.SOUTH);
        }

        private void loadData() {
            model.clear();
            for (IServer.TestSummary summary : server.listTests()) {
                model.addElement(summary);
            }
            if (!model.isEmpty()) {
                list.setSelectedIndex(0);
            }
        }

        IServer.TestSummary open() {
            setVisible(true);
            return selected;
        }
    }

    private static class ClassSelectionDialog extends JDialog {
        private final IServer server;
        private IServer.ClassSummary selected;
        private final DefaultListModel<IServer.ClassSummary> model = new DefaultListModel<>();
        private final JList<IServer.ClassSummary> list = new JList<>(model);

        ClassSelectionDialog(JFrame owner, IServer server) {
            super(owner, "Klasse waehlen", true);
            this.server = server;
            buildUi();
            loadData();
            if (owner instanceof ServerDashboardFrame frame) {
                frame.refreshFonts(getContentPane());
            }
        }

        private void buildUi() {
            setSize(420, 320);
            setLocationRelativeTo(getOwner());
            setLayout(new BorderLayout(12, 12));
            ((JComponent) getContentPane()).setBorder(new EmptyBorder(16, 16, 16, 16));
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.setCellRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                    JLabel lbl = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                    if (value instanceof IServer.ClassSummary cs) {
                        lbl.setText(cs.name());
                    }
                    lbl.setFont(ServerTheme.FONT_BODY);
                    return lbl;
                }
            });
            list.setFont(ServerTheme.FONT_BODY);
            add(new JScrollPane(list), BorderLayout.CENTER);
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
            AccentButton cancel = new AccentButton("Abbrechen");
            cancel.addActionListener(ignored -> {
                selected = null;
                dispose();
            });
            AccentButton confirm = new AccentButton("Starten");
            confirm.addActionListener(ignored -> {
                selected = list.getSelectedValue();
                dispose();
            });
            buttons.add(cancel);
            buttons.add(confirm);
            add(buttons, BorderLayout.SOUTH);
        }

        private void loadData() {
            model.clear();
            for (IServer.ClassSummary summary : server.listClasses()) {
                model.addElement(summary);
            }
            if (!model.isEmpty()) {
                list.setSelectedIndex(0);
            }
        }

        IServer.ClassSummary open() {
            setVisible(true);
            return selected;
        }
    }

    // Dialog für Notenübersicht
    private static class GradeOverviewDialog extends JDialog implements AutoCloseable {
        private static final int BASE_SCHOOL_YEAR_START = 2025;
        private static final String HALF_YEAR_SUFFIX = "HJ1";

        private final CorrectionService service;
        private final JTable table = new JTable();
        private final javax.swing.DefaultComboBoxModel<ClassOption> classModel = new javax.swing.DefaultComboBoxModel<>();
        private final javax.swing.DefaultComboBoxModel<SubjectOption> subjectModel = new javax.swing.DefaultComboBoxModel<>();
        private final javax.swing.DefaultComboBoxModel<String> yearModel = new javax.swing.DefaultComboBoxModel<>();
        private final JComboBox<ClassOption> classBox = new JComboBox<>(classModel);
        private final JComboBox<SubjectOption> subjectBox = new JComboBox<>(subjectModel);
        private final JComboBox<String> yearBox = new JComboBox<>(yearModel);
        private List<Map<String, Object>> allNotes = List.of();

        GradeOverviewDialog(JFrame owner) throws Exception {
            super(owner, "Noten\u00fcbersicht", true);
            this.service = new CorrectionService();
            this.allNotes = service.getAllNotesWithNames();
            buildUi();
            loadClasses();
            if (classModel.getSize() > 0) {
                classBox.setSelectedIndex(0);
            } else {
                updateSubjects(null);
                updateYears(null);
            }
            loadNotes();
        }

        private void buildUi() {
            setSize(960, 620);
            setLocationRelativeTo(getOwner());
            JPanel root = new JPanel(new BorderLayout(20, 20));
            root.setBackground(ServerTheme.WINDOW_BACKGROUND);
            root.setBorder(new EmptyBorder(24, 24, 24, 24));
            setContentPane(root);

            JLabel title = new JLabel("Noten\u00fcbersicht");
            title.setForeground(ServerTheme.TEXT_PRIMARY);
            title.setFont(ServerTheme.FONT_HEADING);

            JPanel header = new JPanel(new BorderLayout(0, 18));
            header.setOpaque(false);
            header.add(title, BorderLayout.NORTH);
            header.add(buildFilterCard(), BorderLayout.CENTER);
            root.add(header, BorderLayout.NORTH);

            table.setRowHeight(28);
            table.setFont(ServerTheme.FONT_BODY);
            table.setForeground(ServerTheme.TEXT_PRIMARY);
            table.setBackground(ServerTheme.CANVAS_BACKGROUND);
            table.setGridColor(ServerTheme.CARD_BORDER);
            table.setFillsViewportHeight(true);
            table.setAutoCreateRowSorter(true);
            javax.swing.table.JTableHeader tableHeader = table.getTableHeader();
            tableHeader.setFont(ServerTheme.FONT_LABEL.deriveFont(Font.PLAIN, 14f));
            tableHeader.setForeground(ServerTheme.TEXT_SECONDARY);
            tableHeader.setBackground(ServerTheme.CANVAS_BACKGROUND);

            JScrollPane scroll = new JScrollPane(table);
            scroll.setBorder(null);
            scroll.getViewport().setBackground(ServerTheme.CANVAS_BACKGROUND);
            root.add(scroll, BorderLayout.CENTER);

            classBox.setRenderer(new ComboRenderer());
            subjectBox.setRenderer(new ComboRenderer());
            yearBox.setRenderer(new ComboRenderer());
            styleCombo(classBox);
            styleCombo(subjectBox);
            styleCombo(yearBox);

            classBox.addActionListener(e -> {
                ClassOption selected = (ClassOption) classBox.getSelectedItem();
                updateSubjects(selected);
                updateYears(selected);
                loadNotes();
            });
            subjectBox.addActionListener(e -> loadNotes());
            yearBox.addActionListener(e -> loadNotes());
        }

        private JPanel buildFilterCard() {
            RoundedPanel card = new RoundedPanel();
            card.setPanelBackground(ServerTheme.CANVAS_BACKGROUND);
            card.setLayout(new GridBagLayout());
            card.setBorder(new EmptyBorder(18, 24, 18, 24));

            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(0, 0, 12, 18);
            gbc.anchor = GridBagConstraints.WEST;

            gbc.gridx = 0;
            gbc.gridy = 0;
            card.add(createFilterLabel("Klasse"), gbc);
            gbc.gridx = 1;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.weightx = 1.0;
            card.add(classBox, gbc);

            gbc.gridx = 0;
            gbc.gridy = 1;
            gbc.weightx = 0;
            gbc.fill = GridBagConstraints.NONE;
            card.add(createFilterLabel("Fach"), gbc);
            gbc.gridx = 1;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.weightx = 1.0;
            card.add(subjectBox, gbc);

            gbc.gridx = 0;
            gbc.gridy = 2;
            gbc.weightx = 0;
            gbc.fill = GridBagConstraints.NONE;
            card.add(createFilterLabel("Schuljahr"), gbc);
            gbc.gridx = 1;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.weightx = 1.0;
            gbc.insets = new Insets(0, 0, 0, 0);
            card.add(yearBox, gbc);

            return card;
        }

        private JLabel createFilterLabel(String text) {
            JLabel label = new JLabel(text);
            label.setFont(ServerTheme.FONT_BODY);
            label.setForeground(ServerTheme.TEXT_SECONDARY);
            return label;
        }

        private void styleCombo(JComboBox<?> combo) {
            combo.setFont(ServerTheme.FONT_BODY);
            combo.setForeground(ServerTheme.TEXT_PRIMARY);
            combo.setBackground(ServerTheme.CARD_BACKGROUND);
            combo.setOpaque(false);
            combo.setMaximumRowCount(12);
        }

        private void loadClasses() {
            classModel.removeAllElements();
            for (CorrectionService.SimpleClassInfo info : service.loadAllClasses()) {
                classModel.addElement(new ClassOption(info.id(), info.name()));
            }
        }

        private void updateSubjects(ClassOption classOption) {
            subjectModel.removeAllElements();
            if (classOption == null) {
                subjectBox.setEnabled(false);
                return;
            }
            List<CorrectionService.SubjectInfo> subjects = service.loadSubjectsForClass(classOption.id());
            if (!subjects.isEmpty()) {
                subjects.stream()
                    .sorted(Comparator.comparing(CorrectionService.SubjectInfo::name, String.CASE_INSENSITIVE_ORDER))
                    .forEach(info -> subjectModel.addElement(new SubjectOption(info.id(), info.name())));
            } else {
                Set<String> fallbackSubjects = allNotes.stream()
                    .filter(n -> classOption.name().equals(sanitize(n.get("klassenname"))))
                    .map(n -> sanitize(n.get("fachname")))
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toCollection(() -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER)));
                for (String name : fallbackSubjects) {
                    subjectModel.addElement(new SubjectOption(-1, name));
                }
            }
            subjectBox.setEnabled(subjectModel.getSize() > 0);
            if (subjectModel.getSize() > 0) {
                subjectBox.setSelectedIndex(0);
            }
        }

        private void updateYears(ClassOption classOption) {
            yearModel.removeAllElements();
            List<String> years = allowedYearsForClass(classOption == null ? null : classOption.name());
            LinkedHashSet<String> unique = new LinkedHashSet<>();
            for (String year : years) {
                String sanitized = year == null ? "" : year.trim();
                if (!sanitized.isEmpty() && unique.add(sanitized)) {
                    yearModel.addElement(sanitized);
                }
            }
            if (yearModel.getSize() == 0) {
                for (String fallback : collectAllKnownYears()) {
                    yearModel.addElement(fallback);
                }
            }
            yearBox.setEnabled(yearModel.getSize() > 0);
            if (yearModel.getSize() > 0) {
                yearBox.setSelectedIndex(0);
            }
        }

        private void loadNotes() {
            ClassOption classOption = (ClassOption) classBox.getSelectedItem();
            SubjectOption subjectOption = (SubjectOption) subjectBox.getSelectedItem();
            String year = (String) yearBox.getSelectedItem();
            Set<String> allowedYears = classOption == null ? Set.of()
                : new LinkedHashSet<>(allowedYearsForClass(classOption.name()));

            List<Map<String, Object>> filtered = allNotes.stream()
                .filter(n -> classOption == null || classOption.name().equals(sanitize(n.get("klassenname"))))
                .filter(n -> subjectOption == null || subjectOption.name().equals(sanitize(n.get("fachname"))))
                .filter(n -> allowedYears.isEmpty() || allowedYears.contains(sanitize(n.get("schuljahr"))))
                .filter(n -> year == null || year.isBlank() || year.equals(sanitize(n.get("schuljahr"))))
                .sorted(Comparator.comparing((Map<String, Object> n) -> sanitize(n.get("schuelername")), String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(n -> sanitize(n.get("fachname")), String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(n -> sanitize(n.get("datum")), String.CASE_INSENSITIVE_ORDER))
                .toList();

            String[] columns = {"Sch\u00fcler", "Fach", "Test", "Note", "Datum", "Uhrzeit", "Schuljahr", "Lehrer"};
            Object[][] data = new Object[filtered.size()][columns.length];
            for (int i = 0; i < filtered.size(); i++) {
                Map<String, Object> row = filtered.get(i);
                data[i][0] = sanitize(row.get("schuelername"));
                data[i][1] = sanitize(row.get("fachname"));
                data[i][2] = sanitize(row.get("testname"));
                data[i][3] = sanitize(row.get("note"));
                data[i][4] = sanitize(row.get("datum"));
                data[i][5] = sanitize(row.get("uhrzeit"));
                data[i][6] = sanitize(row.get("schuljahr"));
                data[i][7] = sanitize(row.get("lehrername"));
            }
            table.setModel(new javax.swing.table.DefaultTableModel(data, columns) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return false;
                }
            });
        }

        private String sanitize(Object value) {
            return value == null ? "" : value.toString().trim();
        }

        private List<String> allowedYearsForClass(String className) {
            if (className == null || className.isBlank()) {
                return collectAllKnownYears();
            }
            int grade = extractLeadingNumber(className);
            if (grade < 5 || grade > 12) {
                return collectAllKnownYears();
            }
            int count = Math.max(1, grade - 4);
            List<String> years = new ArrayList<>(count);
            for (int offset = 0; offset < count; offset++) {
                years.add(formatSchoolYear(BASE_SCHOOL_YEAR_START - offset));
            }
            return years;
        }

        private List<String> collectAllKnownYears() {
            return new ArrayList<>(allNotes.stream()
                .map(n -> sanitize(n.get("schuljahr")))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(() -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER))));
        }

        private int extractLeadingNumber(String className) {
            int value = 0;
            boolean found = false;
            for (int i = 0; i < className.length(); i++) {
                char ch = className.charAt(i);
                if (Character.isDigit(ch)) {
                    value = value * 10 + Character.digit(ch, 10);
                    found = true;
                } else if (found) {
                    break;
                }
            }
            return found ? value : -1;
        }

        private String formatSchoolYear(int startYear) {
            int endYear = startYear + 1;
            return startYear + "/" + endYear + " " + HALF_YEAR_SUFFIX;
        }

        @Override
        public void close() throws Exception {
            service.close();
        }

        private record ClassOption(int id, String name) {
            @Override
            public String toString() {
                return name;
            }
        }

        private record SubjectOption(int id, String name) {
            @Override
            public String toString() {
                return name;
            }
        }

        private static class ComboRenderer extends DefaultListCellRenderer {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel lbl = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                lbl.setFont(ServerTheme.FONT_BODY);
                if (!isSelected) {
                    lbl.setForeground(value == null ? ServerTheme.PLACEHOLDER : ServerTheme.TEXT_PRIMARY);
                }
                if (value == null) {
                    lbl.setText("(Bitte ausw\u00e4hlen)");
                }
                return lbl;
            }
        }
    }



    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
            } catch (Exception ignored) {
            }
            new ServerDashboardFrame().setVisible(true);
        });
    }
}

