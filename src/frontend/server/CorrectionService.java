package frontend.server;

import backend.server.DatabaseHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Data access helper for the correction modus GUI. */
class CorrectionService implements AutoCloseable {
    private static final String[] GRADE_DIR_NAMES = {"bewertungsmaßstäbe", "bewertungsmassstaebe"};

    private final DatabaseHandler db;

    CorrectionService() throws SQLException {
        this.db = new DatabaseHandler("data/lues.db");
    }

    List<CorrectionTest> loadTests() {
        try {
            List<Map<String, Object>> tests = db.getAllEntries("Test");
            List<Map<String, Object>> answers = db.getAllEntries("Antwort");
            Map<Integer, List<Map<String, Object>>> answersByTest = answers.stream()
                    .collect(Collectors.groupingBy(a -> toInt(a.get("idTest"))));
            Map<Integer, Map<String, Object>> bewertungen = db.getAllEntries("Bewertung").stream()
                    .collect(Collectors.toMap(b -> toInt(b.get("idAntwort")), b -> b));
            List<CorrectionTest> result = new ArrayList<>();
            for (Map<String, Object> test : tests) {
                int id = toInt(test.get("idTest"));
                String typ = asString(test.get("typ"));
                List<Map<String, Object>> answerList = answersByTest.getOrDefault(id, List.of());
                long evaluated = answerList.stream()
                        .map(a -> toInt(a.get("idAntwort")))
                        .filter(bewertungen::containsKey)
                        .count();
                result.add(new CorrectionTest(id, typ, answerList.size(), (int) evaluated));
            }
            result.sort(Comparator.comparing(CorrectionTest::name, String.CASE_INSENSITIVE_ORDER));
            return result;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    List<CorrectionClass> loadClasses(int testId) {
        try {
            List<Map<String, Object>> answers = db.getAllEntries("Antwort");
            List<Map<String, Object>> students = db.getAllEntries("Schueler");
            Map<Integer, Map<String, Object>> studentById = indexBy(students, "idSchueler");
            List<Map<String, Object>> answersForTest = answers.stream()
                    .filter(a -> toInt(a.get("idTest")) == testId)
                    .collect(Collectors.toList());
            Map<Integer, List<Map<String, Object>>> answersByStudent = answersForTest.stream()
                    .collect(Collectors.groupingBy(a -> toInt(a.get("idSchueler")), LinkedHashMap::new, Collectors.toList()));
            Map<Integer, List<Integer>> classToStudents = new LinkedHashMap<>();
            for (Map.Entry<Integer, List<Map<String, Object>>> entry : answersByStudent.entrySet()) {
                int studentId = entry.getKey();
                Map<String, Object> student = studentById.get(studentId);
                if (student == null) {
                    continue;
                }
                int classId = toInt(student.get("idKlasse"));
                classToStudents.computeIfAbsent(classId, ignored -> new ArrayList<>()).add(studentId);
            }
            if (classToStudents.isEmpty()) {
                return List.of();
            }
            Map<Integer, Map<String, Object>> classes = indexBy(db.getAllEntries("Klasse"), "idKlasse");
            Map<Integer, Map<String, Object>> bewertungen = indexBy(db.getAllEntries("Bewertung"), "idAntwort");
            List<CorrectionClass> result = new ArrayList<>();
            for (Map.Entry<Integer, List<Integer>> entry : classToStudents.entrySet()) {
                int classId = entry.getKey();
                String className = Optional.ofNullable(classes.get(classId))
                        .map(c -> asString(c.get("klassenname")))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .orElse("Klasse " + classId);
                List<Integer> studentIds = entry.getValue().stream().distinct().collect(Collectors.toList());
                long evaluatedStudents = studentIds.stream()
                        .filter(id -> isStudentFullyEvaluated(answersByStudent.getOrDefault(id, List.of()), bewertungen))
                        .count();
                result.add(new CorrectionClass(classId, className, studentIds.size(), (int) evaluatedStudents));
            }
            result.sort(Comparator.comparing(CorrectionClass::name, String.CASE_INSENSITIVE_ORDER));
            return result;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    List<CorrectionStudent> loadStudents(int testId, int classId) {
        try {
            List<Map<String, Object>> answers = db.getAllEntries("Antwort");
            List<Map<String, Object>> students = db.getAllEntries("Schueler");
            Map<Integer, Map<String, Object>> studentById = indexBy(students, "idSchueler");
            List<Map<String, Object>> answersForTest = answers.stream()
                    .filter(a -> toInt(a.get("idTest")) == testId)
                    .filter(a -> {
                        Map<String, Object> student = studentById.get(toInt(a.get("idSchueler")));
                        return student != null && toInt(student.get("idKlasse")) == classId;
                    })
                    .collect(Collectors.toList());
            Map<Integer, List<Map<String, Object>>> answersByStudent = answersForTest.stream()
                    .collect(Collectors.groupingBy(a -> toInt(a.get("idSchueler")), LinkedHashMap::new, Collectors.toList()));
            Map<Integer, Map<String, Object>> bewertungen = indexBy(db.getAllEntries("Bewertung"), "idAntwort");
            List<CorrectionStudent> result = new ArrayList<>();
            for (Map.Entry<Integer, List<Map<String, Object>>> entry : answersByStudent.entrySet()) {
                int studentId = entry.getKey();
                Map<String, Object> student = studentById.get(studentId);
                if (student == null) {
                    continue;
                }
                String name = buildStudentName(student);
                List<Map<String, Object>> answerList = entry.getValue();
                long evaluated = answerList.stream()
                        .map(a -> toInt(a.get("idAntwort")))
                        .filter(bewertungen::containsKey)
                        .count();
                result.add(new CorrectionStudent(studentId, name, answerList.size(), (int) evaluated));
            }
            result.sort(Comparator.comparing(CorrectionStudent::name, String.CASE_INSENSITIVE_ORDER));
            return result;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    List<CorrectionTask> loadTasks(int testId, int classId) {
        try {
            List<Map<String, Object>> answers = db.getAllEntries("Antwort");
            List<Map<String, Object>> tasks = db.getAllEntries("Aufgabe");
            List<Map<String, Object>> students = db.getAllEntries("Schueler");
            Map<Integer, Map<String, Object>> taskById = indexBy(tasks, "idAufgabe");
            Map<Integer, Map<String, Object>> studentById = indexBy(students, "idSchueler");
            List<Map<String, Object>> answersForTest = answers.stream()
                    .filter(a -> toInt(a.get("idTest")) == testId)
                    .collect(Collectors.toList());
            Map<Integer, List<Map<String, Object>>> answersByTask = new LinkedHashMap<>();
            for (Map<String, Object> answer : answersForTest) {
                int studentId = toInt(answer.get("idSchueler"));
                Map<String, Object> student = studentById.get(studentId);
                if (student == null || toInt(student.get("idKlasse")) != classId) {
                    continue;
                }
                int taskId = toInt(answer.get("idAufgabe"));
                answersByTask.computeIfAbsent(taskId, ignored -> new ArrayList<>()).add(answer);
            }
            Map<Integer, Map<String, Object>> bewertungen = indexBy(db.getAllEntries("Bewertung"), "idAntwort");
            List<CorrectionTask> result = new ArrayList<>();
            for (Map.Entry<Integer, List<Map<String, Object>>> entry : answersByTask.entrySet()) {
                int taskId = entry.getKey();
                List<Map<String, Object>> answerList = entry.getValue();
                long evaluated = answerList.stream()
                        .map(a -> toInt(a.get("idAntwort")))
                        .filter(bewertungen::containsKey)
                        .count();
                String name = buildTaskDisplayName(taskById.get(taskId), taskId);
                result.add(new CorrectionTask(taskId, name, answerList.size(), (int) evaluated));
            }
            result.sort(Comparator.comparingInt(CorrectionTask::id));
            return result;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    List<CorrectionAnswer> loadAnswers(int testId, int studentId) {
        try {
            List<Map<String, Object>> answers = db.getAllEntries("Antwort").stream()
                    .filter(a -> toInt(a.get("idTest")) == testId)
                    .filter(a -> toInt(a.get("idSchueler")) == studentId)
                    .sorted(Comparator.comparingInt(a -> toInt(a.get("idAufgabe"))))
                    .collect(Collectors.toList());
            Map<Integer, Map<String, Object>> taskById = indexBy(db.getAllEntries("Aufgabe"), "idAufgabe");
            Map<Integer, Map<String, Object>> studentById = indexBy(db.getAllEntries("Schueler"), "idSchueler");
            Map<Integer, Map<String, Object>> bewertungen = indexBy(db.getAllEntries("Bewertung"), "idAntwort");
            List<CorrectionAnswer> result = new ArrayList<>();
            for (Map<String, Object> answer : answers) {
                result.add(mapAnswer(answer, taskById, studentById, bewertungen));
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    List<CorrectionAnswer> loadTaskAnswers(int testId, int classId, int taskId) {
        try {
            List<Map<String, Object>> answers = db.getAllEntries("Antwort");
            Map<Integer, Map<String, Object>> taskById = indexBy(db.getAllEntries("Aufgabe"), "idAufgabe");
            Map<Integer, Map<String, Object>> studentById = indexBy(db.getAllEntries("Schueler"), "idSchueler");
            Map<Integer, Map<String, Object>> bewertungen = indexBy(db.getAllEntries("Bewertung"), "idAntwort");
            List<CorrectionAnswer> result = new ArrayList<>();
            for (Map<String, Object> answer : answers) {
                if (toInt(answer.get("idTest")) != testId || toInt(answer.get("idAufgabe")) != taskId) {
                    continue;
                }
                int studentId = toInt(answer.get("idSchueler"));
                Map<String, Object> student = studentById.get(studentId);
                if (student == null || toInt(student.get("idKlasse")) != classId) {
                    continue;
                }
                result.add(mapAnswer(answer, taskById, studentById, bewertungen));
            }
            result.sort(Comparator.comparing(CorrectionAnswer::studentName, String.CASE_INSENSITIVE_ORDER));
            return result;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    void saveEvaluation(int antwortId, String bewertung, String kommentar, Double punkte) {
        try {
            db.upsertBewertung(antwortId,
                    bewertung == null || bewertung.isBlank() ? null : bewertung,
                    kommentar == null || kommentar.isBlank() ? null : kommentar,
                    punkte,
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    void saveNote(int schuelerId, int testId, String note, String datum, String uhrzeit, String schuljahr, int lehrerId) {
        System.out.println("Speichere Note: " + schuelerId + ", " + testId + ", " + note + ", " + datum + ", " + uhrzeit + ", " + schuljahr + ", " + lehrerId);
        try {
            db.insertNote(schuelerId, testId, note, datum, uhrzeit, schuljahr, lehrerId);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    List<CorrectionGradeScale> loadGradeScales() {
        Set<Path> directories = resolveGradeDirectories();
        if (directories.isEmpty()) {
            return List.of();
        }
        List<CorrectionGradeScale> scales = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (Path directory : directories) {
            try (Stream<Path> stream = Files.list(directory)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                        .forEach(path -> {
                            try {
                                CorrectionGradeScale scale = parseGradeScale(path);
                                if (scale != null && seenIds.add(scale.id())) {
                                    scales.add(scale);
                                }
                            } catch (Exception ignored) {
                            }
                        });
            } catch (Exception ignored) {
            }
        }
        scales.sort(Comparator.comparing(CorrectionGradeScale::displayName, String.CASE_INSENSITIVE_ORDER));
        return scales;
    }

    Optional<GradeResult> calculateGrade(int testId, int studentId, CorrectionGradeScale scale, double maxPoints) {
        if (scale == null || maxPoints <= 0) {
            return Optional.empty();
        }
        List<CorrectionAnswer> answers = loadAnswers(testId, studentId);
        if (answers.isEmpty()) {
            return Optional.empty();
        }
        double totalPoints = 0.0;
        for (CorrectionAnswer answer : answers) {
            if (answer.punkte() == null) {
                return Optional.empty();
            }
            totalPoints += answer.punkte();
        }
        double percentage = Math.max(0.0, Math.min((totalPoints / maxPoints) * 100.0, 100.0));
        String grade = scale.gradeFor(percentage);
        return Optional.of(new GradeResult(totalPoints, maxPoints, percentage, grade));
    }

    private Set<Path> resolveGradeDirectories() {
        Set<Path> result = new LinkedHashSet<>();
        Path dataRoot = Paths.get("data");
        for (String name : GRADE_DIR_NAMES) {
            Path directory = dataRoot.resolve(name);
            if (Files.isDirectory(directory)) {
                result.add(directory);
            }
        }
        return result;
    }

    private CorrectionGradeScale parseGradeScale(Path path) {
        try {
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
            int dot = filename.lastIndexOf('.');
            String id = dot > 0 ? filename.substring(0, dot) : filename;
            if (displayName == null || displayName.isBlank()) {
                displayName = id;
            }
            return new CorrectionGradeScale(id, displayName, thresholds);
        } catch (Exception e) {
            return null;
        }
    }

    private CorrectionAnswer mapAnswer(Map<String, Object> answer,
                                       Map<Integer, Map<String, Object>> taskById,
                                       Map<Integer, Map<String, Object>> studentById,
                                       Map<Integer, Map<String, Object>> bewertungen) {
        int antwortId = toInt(answer.get("idAntwort"));
        int taskId = toInt(answer.get("idAufgabe"));
        int studentId = toInt(answer.get("idSchueler"));
        Map<String, Object> task = taskById.get(taskId);
        Map<String, Object> student = studentById.get(studentId);
        Map<String, Object> bewertung = bewertungen.get(antwortId);
        String bewertungText = bewertung == null ? "" : asString(bewertung.get("bewertung"));
        String kommentar = bewertung == null ? "" : asString(bewertung.get("kommentar"));
        Double punkte = bewertung == null ? null : toDouble(bewertung.get("punkte"));
        return new CorrectionAnswer(
                antwortId,
                taskId,
                studentId,
                buildStudentName(student),
                task == null ? "" : asString(task.get("aufgabeMarkdown")),
                task == null ? "" : asString(task.get("loesung")),
                asString(answer.get("antwort")),
                bewertungText,
                kommentar,
                punkte
        );
    }

    private Map<Integer, Map<String, Object>> indexBy(List<Map<String, Object>> rows, String idColumn) {
        Map<Integer, Map<String, Object>> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            int key = toInt(row.get(idColumn));
            if (key >= 0) {
                result.put(key, row);
            }
        }
        return result;
    }

    private String buildStudentName(Map<String, Object> student) {
        if (student == null || student.isEmpty()) {
            return "(unbekannter Schueler)";
        }
        String vorname = asString(student.get("vorname")).trim();
        String nachname = asString(student.get("nachname")).trim();
        String combined = (vorname + " " + nachname).trim();
        if (!combined.isEmpty()) {
            return combined;
        }
        int id = toInt(student.get("idSchueler"));
        return id >= 0 ? "Schueler " + id : "(unbekannter Schueler)";
    }

    private String buildTaskDisplayName(Map<String, Object> task, int taskId) {
        if (task == null || task.isEmpty()) {
            return "Aufgabe " + taskId;
        }
        String markdown = asString(task.get("aufgabeMarkdown")).replaceAll("\\s+", " ").trim();
        String typ = asString(task.get("typ")).trim();
        String base = "Aufgabe " + taskId;
        if (!markdown.isEmpty()) {
            String snippet = markdown.length() > 60 ? markdown.substring(0, 57) + "..." : markdown;
            base = snippet;
        }
        if (!typ.isEmpty()) {
            base = base + " (" + typ + ")";
        }
        return base;
    }

    private boolean isStudentFullyEvaluated(List<Map<String, Object>> answers, Map<Integer, Map<String, Object>> bewertungen) {
        if (answers == null || answers.isEmpty()) {
            return false;
        }
        for (Map<String, Object> answer : answers) {
            int antwortId = toInt(answer.get("idAntwort"));
            if (!bewertungen.containsKey(antwortId)) {
                return false;
            }
        }
        return true;
    }

    private static int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof CharSequence sequence) {
            String text = sequence.toString().trim();
            if (!text.isEmpty()) {
                try {
                    return Integer.parseInt(text);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return -1;
    }

    private static Double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof CharSequence sequence) {
            String text = sequence.toString().trim();
            if (!text.isEmpty()) {
                try {
                    return Double.parseDouble(text.replace(',', '.'));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    private static String asString(Object o) {
        return o == null ? "" : o.toString();
    }

    @Override
    public void close() throws Exception {
        db.close();
    }

    public List<Map<String, Object>> getAllNotes() {
        try {
            return db.getAllEntries("Note");
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<Map<String, Object>> getNotesForSchuelerUndJahr(String schuelername, String schuljahr) {
        // Implementiere die DB-Abfrage nach Schülername und Schuljahr
        // Rückgabe: List<Map<String, Object>> mit allen Noten des Schülers im Schuljahr
        // Dummy:
        return getAllNotes().stream()
                .filter(n -> schuljahr.equals(n.get("schuljahr")) && schuelername.equals(n.get("schuelername")))
                .toList();
    }

    public List<Map<String, Object>> getNotesForKlasseFachJahr(String klasse, String fach, String schuljahr) {
        // Implementiere die DB-Abfrage nach Klasse, Fach und Schuljahr
        // Rückgabe: List<Map<String, Object>> mit allen Noten der Klasse im Fach und Schuljahr
        // Dummy:
        return getAllNotes().stream()
                .filter(n -> schuljahr.equals(n.get("schuljahr")) && klasse.equals(n.get("klassenname")) && fach.equals(n.get("fachname")))
                .toList();
    }

    public List<Map<String, Object>> getAllNotesWithNames() {
        try {
            List<Map<String, Object>> notes = db.getAllEntries("Note");
            Map<Integer, Map<String, Object>> schuelerById = indexBy(db.getAllEntries("Schueler"), "idSchueler");
            Map<Integer, Map<String, Object>> klasseById = indexBy(db.getAllEntries("Klasse"), "idKlasse");
            Map<Integer, Map<String, Object>> testById = indexBy(db.getAllEntries("Test"), "idTest");
            Map<Integer, Map<String, Object>> lehrerById = indexBy(db.getAllEntries("Lehrer"), "idLehrer");
            Map<Integer, Map<String, Object>> fachById = indexBy(db.getAllEntries("Fach"), "idFach");
            Map<Integer, Map<String, Object>> ctById = indexBy(db.getAllEntries("CT_KlasseLehrerFach"), "idCT");

            for (Map<String, Object> n : notes) {
                int schuelerId = toInt(n.get("idSchueler"));
                Map<String, Object> s = schuelerById.get(schuelerId);

                String schuelername = "";
                int klasseId = -1;
                if (s != null) {
                    schuelername = (asString(s.get("vorname")) + " " + asString(s.get("nachname"))).trim();
                    klasseId = toInt(s.get("idKlasse"));
                }
                Map<String, Object> k = klasseById.get(klasseId);
                if (k == null) {
                    // no direct class info on the student, fall back to the class referenced by the note's test
                    int testIdFallback = toInt(n.get("idTest"));
                    Map<String, Object> tFallback = testById.get(testIdFallback);
                    Map<String, Object> ctFallback = tFallback == null ? null : ctById.get(toInt(tFallback.get("idCT_KLF")));
                    klasseId = ctFallback == null ? -1 : toInt(ctFallback.get("idKlasse"));
                    k = klasseById.get(klasseId);
                }
                String klassenname = k == null ? "" : asString(k.get("klassenname")).trim();

                int testId = toInt(n.get("idTest"));
                Map<String, Object> t = testById.get(testId);
                String testname = t == null ? "" : asString(t.get("typ")).trim();

                Map<String, Object> ct = t == null ? null : ctById.get(toInt(t.get("idCT_KLF")));
                int fachId = ct == null ? -1 : toInt(ct.get("idFach"));
                Map<String, Object> f = fachById.get(fachId);
                String fachname = f == null ? "" : asString(f.get("fachname")).trim();

                int lehrerId = toInt(n.get("idLehrer"));
                if (lehrerId < 0 && ct != null) {
                    lehrerId = toInt(ct.get("idLehrer"));
                }
                Map<String, Object> l = lehrerById.get(lehrerId);
                String lehrername = l == null ? "" : (asString(l.get("vorname")) + " " + asString(l.get("nachname"))).trim();

                n.put("schuelername", schuelername);
                n.put("klassenname", klassenname);
                n.put("testname", testname);
                n.put("fachname", fachname);
                n.put("lehrername", lehrername);
            }
            return notes;
        } catch (Exception e) {
            e.printStackTrace();
            return java.util.List.of();
        }
    }

    List<SimpleClassInfo> loadAllClasses() {
        try {
            List<Map<String, Object>> rows = db.getAllEntries("Klasse");
            List<SimpleClassInfo> classes = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                int id = toInt(row.get("idKlasse"));
                if (id < 0) {
                    continue;
                }
                String name = asString(row.get("klassenname")).trim();
                if (name.isEmpty()) {
                    name = "Klasse " + id;
                }
                classes.add(new SimpleClassInfo(id, name));
            }
            classes.sort(Comparator.comparing(SimpleClassInfo::name, String.CASE_INSENSITIVE_ORDER));
            return classes;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    List<SubjectInfo> loadSubjectsForClass(int classId) {
        if (classId < 0) {
            return List.of();
        }
        try {
            Map<Integer, Map<String, Object>> subjectById = indexBy(db.getAllEntries("Fach"), "idFach");
            List<Map<String, Object>> relations = db.getAllEntries("CT_KlasseLehrerFach");
            Map<Integer, SubjectInfo> subjects = new LinkedHashMap<>();
            for (Map<String, Object> relation : relations) {
                if (toInt(relation.get("idKlasse")) != classId) {
                    continue;
                }
                int subjectId = toInt(relation.get("idFach"));
                if (subjectId < 0) {
                    continue;
                }
                Map<String, Object> subjectRow = subjectById.get(subjectId);
                String name = subjectRow == null ? "" : asString(subjectRow.get("fachname")).trim();
                if (name.isEmpty()) {
                    name = "Fach " + subjectId;
                }
                subjects.putIfAbsent(subjectId, new SubjectInfo(subjectId, name));
            }
            List<SubjectInfo> result = new ArrayList<>(subjects.values());
            result.sort(Comparator.comparing(SubjectInfo::name, String.CASE_INSENSITIVE_ORDER));
            return result;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // Hilfsfunktion: Hole idFach aus CT_KlasseLehrerFach
    private int getFachIdFromCT_KLF(backend.server.DatabaseHandler db, int idCT_KLF) {
        try {
            Map<String, Object> ct = db.getEntry("CT_KlasseLehrerFach", idCT_KLF);
            if (ct != null && ct.get("idFach") != null) {
                return ((Number)ct.get("idFach")).intValue();
            }
        } catch (Exception e) {
            // ignore
        }
        return -1;
    }

    record SimpleClassInfo(int id, String name) {
    }

    record SubjectInfo(int id, String name) {
    }

    record CorrectionTest(int id, String name, int answerCount, int evaluatedCount) {
        @Override
        public String toString() {
            return name + " (" + evaluatedCount + "/" + answerCount + ")";
        }
    }

    record CorrectionClass(int id, String name, int students, int evaluatedStudents) {
        @Override
        public String toString() {
            return name + " (" + evaluatedStudents + "/" + students + ")";
        }
    }

    record CorrectionStudent(int id, String name, int answers, int evaluated) {
        @Override
        public String toString() {
            return name + " (" + evaluated + "/" + answers + ")";
        }
    }

    record CorrectionTask(int id, String name, int answers, int evaluated) {
        @Override
        public String toString() {
            return name + " (" + evaluated + "/" + answers + ")";
        }
    }

    record CorrectionAnswer(int antwortId, int taskId, int studentId, String studentName,
                            String question, String solution, String studentAnswer,
                            String bewertung, String kommentar, Double punkte) {
    }

    record CorrectionGradeScale(String id, String displayName, NavigableMap<Double, String> thresholds) {
        @Override
        public String toString() {
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

    record GradeResult(double points, double maxPoints, double percentage, String grade) {
    }
}
