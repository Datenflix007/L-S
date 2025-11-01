package frontend.server;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;

/** Dialog zum Durchklicken des Korrekturmodus. */
class CorrectionDialog extends JDialog implements AutoCloseable {

    private enum CorrectionMode {
        STUDENT,
        TASK
    }

    private final CorrectionService service;

    private final JComboBox<CorrectionService.CorrectionTest> testCombo = new JComboBox<>();
    private final JComboBox<CorrectionService.CorrectionClass> classCombo = new JComboBox<>();
    private final DefaultListModel<CorrectionService.CorrectionStudent> studentModel = new DefaultListModel<>();
    private final DefaultListModel<CorrectionService.CorrectionTask> taskModel = new DefaultListModel<>();
    private final JList<CorrectionService.CorrectionStudent> studentList = new JList<>(studentModel);
    private final JList<CorrectionService.CorrectionTask> taskList = new JList<>(taskModel);
    private final CardLayout listCardLayout = new CardLayout();
    private final JPanel listCardPanel = new JPanel(listCardLayout);
    private final CorrectionAnswerTableModel answerModel = new CorrectionAnswerTableModel();
    private final JTable answerTable = new JTable(answerModel);

    private final JTextArea questionArea = buildReadOnlyArea();
    private final JTextArea solutionArea = buildReadOnlyArea();
    private final JTextArea studentAnswerArea = buildReadOnlyArea();
    private final JTextField studentField = buildReadOnlyField();
    private final JTextField bewertungField = new JTextField();
    private final JTextField punkteField = new JTextField();
    private final JTextArea kommentarArea = new JTextArea(3, 20);

    private final JRadioButton studentModeButton = new JRadioButton("Schuelermodus");
    private final JRadioButton taskModeButton = new JRadioButton("Aufgabenmodus");

    private final JComboBox<CorrectionService.CorrectionGradeScale> gradeScaleCombo = new JComboBox<>();
    private final JTextField maxPointsField = new JTextField(6);
    private final AccentButton gradeButton = new AccentButton("Note berechnen");
    private final JLabel gradeStatusLabel = new JLabel(" ");

    private final Map<Integer, GradeConfig> gradeConfigs = new HashMap<>();

    private CorrectionMode mode = CorrectionMode.STUDENT;

    CorrectionDialog(JFrame owner) throws Exception {
        super(owner, "Korrekturmodus", true);
        this.service = new CorrectionService();
        buildUi();
        loadGradeScales();
        loadTests();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                try {
                    close();
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void buildUi() {
        setSize(1100, 720);
        setLocationRelativeTo(getOwner());
        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBackground(ServerTheme.WINDOW_BACKGROUND);
        root.setBorder(new EmptyBorder(16, 16, 16, 16));
        setContentPane(root);

        JPanel combos = new JPanel(new GridLayout(1, 3, 12, 0));
        combos.setOpaque(false);

        JPanel testPanel = new JPanel(new BorderLayout());
        testPanel.setOpaque(false);
        JLabel testLabel = new JLabel("Test");
        testLabel.setForeground(ServerTheme.TEXT_SECONDARY);
        testLabel.setFont(ServerTheme.FONT_BODY);
        testPanel.add(testLabel, BorderLayout.NORTH);
        testCombo.setRenderer(new ComboRenderer());
        testCombo.addActionListener(ignored -> onTestSelected());
        testPanel.add(testCombo, BorderLayout.CENTER);

        JPanel classPanel = new JPanel(new BorderLayout());
        classPanel.setOpaque(false);
        JLabel classLabel = new JLabel("Klasse");
        classLabel.setForeground(ServerTheme.TEXT_SECONDARY);
        classLabel.setFont(ServerTheme.FONT_BODY);
        classPanel.add(classLabel, BorderLayout.NORTH);
        classCombo.setRenderer(new ComboRenderer());
        classCombo.addActionListener(ignored -> onClassSelected());
        classPanel.add(classCombo, BorderLayout.CENTER);

        JPanel modePanel = new JPanel(new GridLayout(2, 1, 0, 4));
        modePanel.setOpaque(false);
        JLabel modeLabel = new JLabel("Modus");
        modeLabel.setForeground(ServerTheme.TEXT_SECONDARY);
        modeLabel.setFont(ServerTheme.FONT_BODY);
        modePanel.add(modeLabel);
        JPanel modeButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        modeButtons.setOpaque(false);
        ButtonGroup modeGroup = new ButtonGroup();
        studentModeButton.setOpaque(false);
        studentModeButton.setFont(ServerTheme.FONT_BODY);
        studentModeButton.setSelected(true);
        studentModeButton.addActionListener(ignored -> onModeChanged(CorrectionMode.STUDENT));
        taskModeButton.setOpaque(false);
        taskModeButton.setFont(ServerTheme.FONT_BODY);
        taskModeButton.addActionListener(ignored -> onModeChanged(CorrectionMode.TASK));
        modeGroup.add(studentModeButton);
        modeGroup.add(taskModeButton);
        modeButtons.add(studentModeButton);
        modeButtons.add(taskModeButton);
        modePanel.add(modeButtons);

        combos.add(testPanel);
        combos.add(classPanel);
        combos.add(modePanel);

        JPanel north = new JPanel(new BorderLayout(0, 12));
        north.setOpaque(false);
        north.add(combos, BorderLayout.NORTH);
        north.add(buildGradePanel(), BorderLayout.SOUTH);
        root.add(north, BorderLayout.NORTH);

        studentList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        studentList.setFont(ServerTheme.FONT_BODY);
        studentList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                onStudentSelected();
            }
        });

        taskList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        taskList.setFont(ServerTheme.FONT_BODY);
        taskList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                onTaskSelected();
            }
        });

        listCardPanel.setOpaque(false);
        listCardPanel.add(new JScrollPane(studentList), CorrectionMode.STUDENT.name());
        listCardPanel.add(new JScrollPane(taskList), CorrectionMode.TASK.name());

        JSplitPane split = new JSplitPane();
        split.setResizeWeight(0.25);
        split.setBorder(null);
        split.setLeftComponent(listCardPanel);

        JPanel answerPanel = new JPanel(new BorderLayout(12, 12));
        answerPanel.setOpaque(false);
        answerTable.setRowHeight(28);
        answerTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showAnswerDetails(answerTable.getSelectedRow());
                maybeRefreshGradeStatus();
            }
        });
        JScrollPane answerScroll = new JScrollPane(answerTable);
        answerPanel.add(answerScroll, BorderLayout.CENTER);
        answerPanel.add(buildDetailPanel(), BorderLayout.SOUTH);

        split.setRightComponent(answerPanel);
        root.add(split, BorderLayout.CENTER);
    }

    private JPanel buildGradePanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        panel.setBorder(new EmptyBorder(0, 0, 8, 0));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(0, 0, 0, 8);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JLabel scaleLabel = new JLabel("Bewertungsmassstab");
        scaleLabel.setForeground(ServerTheme.TEXT_SECONDARY);
        scaleLabel.setFont(ServerTheme.FONT_BODY);
        gbc.gridx = 0;
        gbc.gridy = 0;
        panel.add(scaleLabel, gbc);

        gradeScaleCombo.setRenderer(new ComboRenderer());
        gradeScaleCombo.addActionListener(ignored -> {
            updateGradeButtonState();
            updateGradeStatusLabel(null);
        });
        gbc.gridx = 1;
        gbc.gridy = 0;
        gbc.weightx = 0.6;
        panel.add(gradeScaleCombo, gbc);

        JLabel maxLabel = new JLabel("Max. Punkte");
        maxLabel.setForeground(ServerTheme.TEXT_SECONDARY);
        maxLabel.setFont(ServerTheme.FONT_BODY);
        gbc.gridx = 2;
        gbc.gridy = 0;
        gbc.weightx = 0;
        panel.add(maxLabel, gbc);

        maxPointsField.setColumns(6);
        gbc.gridx = 3;
        gbc.gridy = 0;
        panel.add(maxPointsField, gbc);

        gradeButton.addActionListener(ignored -> calculateGradeForSelection());
        gbc.gridx = 4;
        gbc.gridy = 0;
        gbc.weightx = 0;
        panel.add(gradeButton, gbc);

        gradeStatusLabel.setForeground(ServerTheme.TEXT_SECONDARY);
        gradeStatusLabel.setFont(ServerTheme.FONT_BODY);
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.gridwidth = 5;
        gbc.insets = new Insets(6, 0, 0, 0);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(gradeStatusLabel, gbc);

        return panel;
    }

    private JPanel buildDetailPanel() {
        JPanel panel = new JPanel(new BorderLayout(12, 12));
        panel.setOpaque(false);
        panel.setBorder(new EmptyBorder(12, 0, 0, 0));

        JPanel textAreaPanel = new JPanel(new GridLayout(1, 3, 12, 0));
        textAreaPanel.setOpaque(false);
        textAreaPanel.add(wrapLabeled("Aufgabe", questionArea));
        textAreaPanel.add(wrapLabeled("Loesung", solutionArea));
        textAreaPanel.add(wrapLabeled("Antwort", studentAnswerArea));
        panel.add(textAreaPanel, BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 0, 4, 12);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0;
        gbc.gridy = 0;
        form.add(createDescriptorLabel("Schueler"), gbc);
        gbc.gridx = 1;
        gbc.gridy = 0;
        gbc.gridwidth = 3;
        form.add(studentField, gbc);

        gbc.gridwidth = 1;
        gbc.gridx = 0;
        gbc.gridy = 1;
        form.add(createDescriptorLabel("Bewertung"), gbc);
        gbc.gridx = 1;
        gbc.gridy = 1;
        form.add(bewertungField, gbc);

        gbc.gridx = 2;
        gbc.gridy = 1;
        form.add(createDescriptorLabel("Punkte"), gbc);
        gbc.gridx = 3;
        gbc.gridy = 1;
        form.add(punkteField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        form.add(createDescriptorLabel("Kommentar"), gbc);
        gbc.gridx = 1;
        gbc.gridy = 2;
        gbc.gridwidth = 3;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.BOTH;
        kommentarArea.setLineWrap(true);
        kommentarArea.setWrapStyleWord(true);
        form.add(new JScrollPane(kommentarArea), gbc);

        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.gridwidth = 4;
        gbc.weightx = 1;
        gbc.weighty = 0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.EAST;
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
        buttons.setOpaque(false);
        AccentButton saveButton = new AccentButton("Speichern");
        saveButton.addActionListener(this::saveEvaluation);
        AccentButton nextButton = new AccentButton("Weiter");
        nextButton.addActionListener(ignored -> jumpToNextAnswer());
        buttons.add(saveButton);
        buttons.add(nextButton);
        form.add(buttons, gbc);

        panel.add(form, BorderLayout.CENTER);
        return panel;
    }

    private JLabel createDescriptorLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(ServerTheme.TEXT_SECONDARY);
        label.setFont(ServerTheme.FONT_BODY);
        return label;
    }

    private static JTextArea buildReadOnlyArea() {
        JTextArea area = new JTextArea(5, 20);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        return area;
    }

    private static JTextField buildReadOnlyField() {
        JTextField field = new JTextField();
        field.setEditable(false);
        field.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        return field;
    }

    private JPanel wrapLabeled(String title, JComponent component) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        JLabel label = new JLabel(title);
        label.setForeground(ServerTheme.TEXT_SECONDARY);
        label.setFont(ServerTheme.FONT_BODY);
        panel.add(label, BorderLayout.NORTH);
        panel.add(new JScrollPane(component), BorderLayout.CENTER);
        return panel;
    }

    private void loadGradeScales() {
        DefaultComboBoxModel<CorrectionService.CorrectionGradeScale> model = new DefaultComboBoxModel<>();
        for (CorrectionService.CorrectionGradeScale scale : service.loadGradeScales()) {
            model.addElement(scale);
        }
        gradeScaleCombo.setModel(model);
        gradeScaleCombo.setSelectedIndex(-1);
        updateGradeButtonState();
    }

    private void loadTests() {
        DefaultComboBoxModel<CorrectionService.CorrectionTest> model = new DefaultComboBoxModel<>();
        for (CorrectionService.CorrectionTest test : service.loadTests()) {
            model.addElement(test);
        }
        testCombo.setModel(model);
        if (model.getSize() > 0) {
            testCombo.setSelectedIndex(0);
        }
        onTestSelected();
    }

    private void onTestSelected() {
        CorrectionService.CorrectionTest selectedTest = (CorrectionService.CorrectionTest) testCombo.getSelectedItem();
        DefaultComboBoxModel<CorrectionService.CorrectionClass> classModel = new DefaultComboBoxModel<>();
        if (selectedTest != null) {
            for (CorrectionService.CorrectionClass c : service.loadClasses(selectedTest.id())) {
                classModel.addElement(c);
            }
        }
        classCombo.setModel(classModel);
        if (classModel.getSize() > 0) {
            classCombo.setSelectedIndex(0);
        }
        applyGradeConfigForTest();
        reloadPrimaryList();
    }

    private void onClassSelected() {
        reloadPrimaryList();
    }

    private void reloadPrimaryList() {
        answerModel.setAnswers(List.of());
        clearDetails();
        CorrectionService.CorrectionTest test = getSelectedTest();
        CorrectionService.CorrectionClass klasse = getSelectedClass();
        if (test == null || klasse == null) {
            studentModel.clear();
            taskModel.clear();
            maybeRefreshGradeStatus();
            return;
        }
        if (mode == CorrectionMode.STUDENT) {
            studentModel.clear();
            for (CorrectionService.CorrectionStudent student : service.loadStudents(test.id(), klasse.id())) {
                studentModel.addElement(student);
            }
            if (!studentModel.isEmpty()) {
                studentList.setSelectedIndex(0);
            }
        } else {
            taskModel.clear();
            for (CorrectionService.CorrectionTask task : service.loadTasks(test.id(), klasse.id())) {
                taskModel.addElement(task);
            }
            if (!taskModel.isEmpty()) {
                taskList.setSelectedIndex(0);
            }
        }
        listCardLayout.show(listCardPanel, mode.name());
        maybeRefreshGradeStatus();
    }

    private void onStudentSelected() {
        CorrectionService.CorrectionStudent student = studentList.getSelectedValue();
        CorrectionService.CorrectionTest test = getSelectedTest();
        if (mode != CorrectionMode.STUDENT) {
            return;
        }
        if (student == null || test == null) {
            answerModel.setAnswers(List.of());
            clearDetails();
            return;
        }
        answerModel.setMode(CorrectionMode.STUDENT);
        List<CorrectionService.CorrectionAnswer> answers = service.loadAnswers(test.id(), student.id());
        answerModel.setAnswers(answers);
        if (!answers.isEmpty()) {
            answerTable.setRowSelectionInterval(0, 0);
            showAnswerDetails(0);
        } else {
            clearDetails();
        }
        maybeRefreshGradeStatus();
    }

    private void onTaskSelected() {
        CorrectionService.CorrectionTask task = taskList.getSelectedValue();
        CorrectionService.CorrectionTest test = getSelectedTest();
        CorrectionService.CorrectionClass klasse = getSelectedClass();
        if (mode != CorrectionMode.TASK) {
            return;
        }
        if (task == null || test == null || klasse == null) {
            answerModel.setAnswers(List.of());
            clearDetails();
            return;
        }
        answerModel.setMode(CorrectionMode.TASK);
        List<CorrectionService.CorrectionAnswer> answers = service.loadTaskAnswers(test.id(), klasse.id(), task.id());
        answerModel.setAnswers(answers);
        if (!answers.isEmpty()) {
            answerTable.setRowSelectionInterval(0, 0);
            showAnswerDetails(0);
        } else {
            clearDetails();
        }
        maybeRefreshGradeStatus();
    }

    private void showAnswerDetails(int row) {
        CorrectionService.CorrectionAnswer answer = answerModel.getAnswer(row);
        if (answer == null) {
            clearDetails();
            return;
        }
        questionArea.setText(Optional.ofNullable(answer.question()).orElse(""));
        solutionArea.setText(Optional.ofNullable(answer.solution()).orElse(""));
        studentAnswerArea.setText(Optional.ofNullable(answer.studentAnswer()).orElse(""));
        bewertungField.setText(Optional.ofNullable(answer.bewertung()).orElse(""));
        kommentarArea.setText(Optional.ofNullable(answer.kommentar()).orElse(""));
        punkteField.setText(answer.punkte() == null ? "" : Double.toString(answer.punkte()));
        studentField.setText(Optional.ofNullable(answer.studentName()).orElse(""));
    }

    private void clearDetails() {
        questionArea.setText("");
        solutionArea.setText("");
        studentAnswerArea.setText("");
        bewertungField.setText("");
        kommentarArea.setText("");
        punkteField.setText("");
        studentField.setText("");
    }

    private void saveEvaluation(ActionEvent event) {
        int row = answerTable.getSelectedRow();
        CorrectionService.CorrectionAnswer answer = answerModel.getAnswer(row);
        if (answer == null) {
            return;
        }
        Double punkte = parsePoints();
        service.saveEvaluation(answer.antwortId(), bewertungField.getText(), kommentarArea.getText(), punkte);
        refreshAnswersAfterSave(answer);
        maybeRefreshGradeStatus();
    }

    private void refreshAnswersAfterSave(CorrectionService.CorrectionAnswer savedAnswer) {
        if (mode == CorrectionMode.STUDENT) {
            int rowBefore = answerModel.indexOfAnswer(savedAnswer.antwortId());
            onStudentSelected();
            int row = answerModel.indexOfAnswer(savedAnswer.antwortId());
            if (row >= 0) {
                answerTable.setRowSelectionInterval(row, row);
            } else if (rowBefore >= 0 && rowBefore < answerTable.getRowCount()) {
                answerTable.setRowSelectionInterval(rowBefore, rowBefore);
            }
        } else {
            int rowBefore = answerModel.indexOfAnswer(savedAnswer.antwortId());
            onTaskSelected();
            int row = answerModel.indexOfAnswer(savedAnswer.antwortId());
            if (row >= 0) {
                answerTable.setRowSelectionInterval(row, row);
            } else if (rowBefore >= 0 && rowBefore < answerTable.getRowCount()) {
                answerTable.setRowSelectionInterval(rowBefore, rowBefore);
            }
        }
    }

    private Double parsePoints() {
        String text = punkteField.getText();
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(text.replace(',', '.'));
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Punkte konnten nicht interpretiert werden.", "Hinweis", JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
    }

    private Double parseMaxPoints() {
        String text = maxPointsField.getText();
        if (text == null || text.isBlank()) {
            JOptionPane.showMessageDialog(this, "Bitte maximale Punktzahl angeben.", "Hinweis", JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        try {
            double value = Double.parseDouble(text.replace(',', '.'));
            if (value > 0) {
                return value;
            }
        } catch (NumberFormatException ignored) {
        }
        JOptionPane.showMessageDialog(this, "Maximale Punktzahl muss eine positive Zahl sein.", "Hinweis", JOptionPane.INFORMATION_MESSAGE);
        return null;
    }

    private void jumpToNextAnswer() {
        int row = answerTable.getSelectedRow();
        if (row >= 0 && row < answerTable.getRowCount() - 1) {
            answerTable.setRowSelectionInterval(row + 1, row + 1);
        } else {
            if (mode == CorrectionMode.STUDENT) {
                int studentIndex = studentList.getSelectedIndex();
                if (studentIndex >= 0 && studentIndex < studentModel.size() - 1) {
                    studentList.setSelectedIndex(studentIndex + 1);
                }
            } else {
                int taskIndex = taskList.getSelectedIndex();
                if (taskIndex >= 0 && taskIndex < taskModel.size() - 1) {
                    taskList.setSelectedIndex(taskIndex + 1);
                }
            }
        }
    }

    private void onModeChanged(CorrectionMode newMode) {
        if (mode == newMode) {
            return;
        }
        mode = newMode;
        answerModel.setMode(newMode);
        listCardLayout.show(listCardPanel, newMode.name());
        reloadPrimaryList();
    }

    private void updateGradeButtonState() {
        gradeButton.setEnabled(gradeScaleCombo.getItemCount() > 0);
    }

    private void calculateGradeForSelection() {
        CorrectionService.CorrectionTest test = getSelectedTest();
        if (test == null) {
            return;
        }
        CorrectionService.CorrectionGradeScale scale = (CorrectionService.CorrectionGradeScale) gradeScaleCombo.getSelectedItem();
        if (scale == null) {
            JOptionPane.showMessageDialog(this, "Bitte einen Bewertungsmassstab waehlen.", "Hinweis", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        Double maxPoints = parseMaxPoints();
        if (maxPoints == null) {
            return;
        }
        Integer studentId = getFocusedStudentId();
        String studentName = getFocusedStudentName();
        if (studentId == null || studentName == null) {
            JOptionPane.showMessageDialog(this, "Bitte zuerst einen Schueler auswaehlen.", "Hinweis", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        Optional<CorrectionService.GradeResult> result = service.calculateGrade(test.id(), studentId, scale, maxPoints);
        if (result.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Fuer diesen Schueler sind noch nicht alle Antworten mit Punkten bewertet.", "Hinweis", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        rememberGradeConfig(test.id(), scale, maxPoints);
        CorrectionService.GradeResult grade = result.get();
        updateGradeStatusLabel(String.format(Locale.GERMAN,
                "%s: %.2f / %.2f Punkte (%.1f%%) -> Note %s",
                studentName,
                grade.points(),
                grade.maxPoints(),
                grade.percentage(),
                grade.grade()));

        // --- NEU: Note abspeichern ---
        String datum = java.time.LocalDate.now().toString();
        String uhrzeit = java.time.LocalTime.now().withNano(0).toString();
        String schuljahr = "2025/26"; // Optional: Hole das echte Schuljahr aus Auswahl
        int lehrerId = 1; // Optional: Hole den echten Lehrer aus Kontext
        service.saveNote(studentId, test.id(), grade.grade(), datum, uhrzeit, schuljahr, lehrerId);
        System.out.println("Note gespeichert für Schüler " + studentId + " Test " + test.id() + " Note " + grade.grade());
    }

    private void rememberGradeConfig(int testId, CorrectionService.CorrectionGradeScale scale, double maxPoints) {
        gradeConfigs.put(testId, new GradeConfig(scale.id(), maxPoints));
    }

    private void applyGradeConfigForTest() {
        CorrectionService.CorrectionTest test = getSelectedTest();
        if (test == null) {
            gradeScaleCombo.setSelectedIndex(-1);
            maxPointsField.setText("");
            updateGradeStatusLabel(null);
            return;
        }
        GradeConfig config = gradeConfigs.get(test.id());
        if (config == null) {
            gradeScaleCombo.setSelectedIndex(-1);
            maxPointsField.setText("");
        } else {
            CorrectionService.CorrectionGradeScale scale = findScaleById(config.scaleId());
            gradeScaleCombo.setSelectedItem(scale);
            maxPointsField.setText(Double.toString(config.maxPoints()));
        }
        updateGradeStatusLabel(null);
    }

    private void maybeRefreshGradeStatus() {
        CorrectionService.CorrectionTest test = getSelectedTest();
        if (test == null) {
            updateGradeStatusLabel(null);
            return;
        }
        GradeConfig config = gradeConfigs.get(test.id());
        if (config == null) {
            updateGradeStatusLabel(null);
            return;
        }
        CorrectionService.CorrectionGradeScale scale = findScaleById(config.scaleId());
        if (scale == null) {
            updateGradeStatusLabel(null);
            return;
        }
        Integer studentId = getFocusedStudentId();
        String studentName = getFocusedStudentName();
        if (studentId == null || studentName == null) {
            updateGradeStatusLabel(null);
            return;
        }
        Optional<CorrectionService.GradeResult> result = service.calculateGrade(test.id(), studentId, scale, config.maxPoints());
        if (result.isPresent()) {
            CorrectionService.GradeResult grade = result.get();
            updateGradeStatusLabel(String.format(Locale.GERMAN,
                    "%s: %.2f / %.2f Punkte (%.1f%%) -> Note %s",
                    studentName,
                    grade.points(),
                    grade.maxPoints(),
                    grade.percentage(),
                    grade.grade()));
        } else {
            updateGradeStatusLabel(null);
        }
    }

    private void updateGradeStatusLabel(String text) {
        gradeStatusLabel.setText(text == null || text.isBlank() ? " " : text);
    }

    private Integer getFocusedStudentId() {
        if (mode == CorrectionMode.STUDENT) {
            CorrectionService.CorrectionStudent student = studentList.getSelectedValue();
            return student == null ? null : student.id();
        }
        CorrectionService.CorrectionAnswer answer = answerModel.getAnswer(answerTable.getSelectedRow());
        return answer == null ? null : answer.studentId();
    }

    private String getFocusedStudentName() {
        if (mode == CorrectionMode.STUDENT) {
            CorrectionService.CorrectionStudent student = studentList.getSelectedValue();
            return student == null ? null : student.name();
        }
        CorrectionService.CorrectionAnswer answer = answerModel.getAnswer(answerTable.getSelectedRow());
        return answer == null ? null : answer.studentName();
    }

    private CorrectionService.CorrectionGradeScale findScaleById(String id) {
        if (id == null) {
            return null;
        }
        ComboBoxModel<CorrectionService.CorrectionGradeScale> model = gradeScaleCombo.getModel();
        for (int i = 0; i < model.getSize(); i++) {
            CorrectionService.CorrectionGradeScale scale = model.getElementAt(i);
            if (scale != null && id.equals(scale.id())) {
                return scale;
            }
        }
        return null;
    }

    private CorrectionService.CorrectionTest getSelectedTest() {
        return (CorrectionService.CorrectionTest) testCombo.getSelectedItem();
    }

    private CorrectionService.CorrectionClass getSelectedClass() {
        return (CorrectionService.CorrectionClass) classCombo.getSelectedItem();
    }

    @Override
    public void close() throws Exception {
        service.close();
    }

    private static class ComboRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            JLabel lbl = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            lbl.setFont(ServerTheme.FONT_BODY);
            if (value == null) {
                lbl.setText("(Bitte auswaehlen)");
            }
            return lbl;
        }
    }

    private static class CorrectionAnswerTableModel extends AbstractTableModel {
        private CorrectionMode mode = CorrectionMode.STUDENT;
        private List<CorrectionService.CorrectionAnswer> answers = List.of();

        void setMode(CorrectionMode mode) {
            if (this.mode != mode) {
                this.mode = mode;
                fireTableStructureChanged();
            }
        }

        void setAnswers(List<CorrectionService.CorrectionAnswer> answers) {
            this.answers = answers;
            fireTableDataChanged();
        }

        CorrectionService.CorrectionAnswer getAnswer(int row) {
            if (row < 0 || row >= answers.size()) {
                return null;
            }
            return answers.get(row);
        }

        int indexOfAnswer(int antwortId) {
            for (int i = 0; i < answers.size(); i++) {
                if (answers.get(i).antwortId() == antwortId) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public int getRowCount() {
            return answers.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int column) {
            return switch (mode) {
                case STUDENT -> switch (column) {
                    case 0 -> "Aufgabe";
                    case 1 -> "Bewertung";
                    case 2 -> "Punkte";
                    default -> "";
                };
                case TASK -> switch (column) {
                    case 0 -> "Schueler";
                    case 1 -> "Bewertung";
                    case 2 -> "Punkte";
                    default -> "";
                };
            };
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            CorrectionService.CorrectionAnswer answer = answers.get(rowIndex);
            return switch (mode) {
                case STUDENT -> switch (columnIndex) {
                    case 0 -> answer.taskId();
                    case 1 -> Optional.ofNullable(answer.bewertung()).orElse("");
                    case 2 -> answer.punkte() == null ? "" : answer.punkte();
                    default -> "";
                };
                case TASK -> switch (columnIndex) {
                    case 0 -> Optional.ofNullable(answer.studentName()).orElse("");
                    case 1 -> Optional.ofNullable(answer.bewertung()).orElse("");
                    case 2 -> answer.punkte() == null ? "" : answer.punkte();
                    default -> "";
                };
            };
        }
    }

    private record GradeConfig(String scaleId, double maxPoints) {
    }
}
