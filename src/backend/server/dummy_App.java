package backend.server;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

class ConsoleLoadingBar {
    private final int totalSteps;
    private int current = 0;
    private final PrintStream out;

    ConsoleLoadingBar(int totalSteps) {
        this.totalSteps = Math.max(1, totalSteps);
        this.out = System.out;
    }

    public void step(String message) {
        current++;
        render(message);
    }
    public void set(String message, int step) {
        current = Math.max(0, Math.min(totalSteps, step));
        render(message);
    }
    public void finish(String message) {
        current = totalSteps;
        render(message != null ? message : "Fertig");
    }

    private void clearConsole() {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                new ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor();
            } else {
                System.out.print("\033[H\033[2J");
                System.out.flush();
            }
        } catch (Exception ignored) { }
    }

    private void render(String message) {
        clearConsole();
        out.println();
        int width = 30;
        int filled = (int) Math.round((current / (double) totalSteps) * width);
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < width; i++) bar.append(i < filled ? '#' : ' ');
        int percent = (int) Math.round((current / (double) totalSteps) * 100);
        String line = String.format("[%s] %3d%%  %s", bar.toString(), percent, message);
        out.println(line);
        out.flush();
        if (current >= totalSteps) out.println();
    }
}

public class dummy_App {

    private static final int STUDENTS_PER_CLASS = 20;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final String NOTE_TIME = "08:30";
    private static final String[] GRADE_SEQUENCE = {"1", "2", "3", "4"};

    private static final List<String> FIRST_NAMES = List.of(
            "Anna", "Ben", "Clara", "David", "Ella",
            "Finn", "Greta", "Henry", "Ida", "Jonas",
            "Kira", "Leo", "Mara", "Noah", "Olivia",
            "Paul", "Quentin", "Romy", "Samuel", "Tilda"
    );

    private static final List<String> LAST_NAMES = List.of(
            "Mueller", "Schmidt", "Schneider", "Fischer", "Weber",
            "Meyer", "Wagner", "Becker", "Schulz", "Hoffmann",
            "Koch", "Richter", "Klein", "Wolf", "Schroeder",
            "Neumann", "Zimmermann", "Braun", "Krueger", "Hofmann"
    );

    private static final List<String> SUBJECTS = List.of("Deutsch", "Mathematik", "Englisch", "MBI", "Informatik");

    private static Map<String, TeacherMeta> buildTeacherMeta() {
        Map<String, TeacherMeta> map = new LinkedHashMap<>();
        map.put("Deutsch", new TeacherMeta("w", "Julia", "Kaiser"));
        map.put("Mathematik", new TeacherMeta("m", "Lukas", "Franke"));
        map.put("Englisch", new TeacherMeta("w", "Sophie", "Brandt"));
        map.put("MBI", new TeacherMeta("m", "Martin", "Staacke"));
        map.put("Informatik", new TeacherMeta("w", "Lisa", "Dietrich"));
        return map;
    }

    private record TeacherMeta(String sex, String firstName, String lastName) {
    }

    private record ClassSubjectKey(String className, String subjectName) {
    }

    private record DummyTask(String markdown, String type, String solution) {
    }

    public static void main(String[] args) {
        try {
            resetDatabaseFile();
            populateDatabase();
            System.out.println("Dummy-Daten fuer Klassen, Schueler und Noten wurden erzeugt.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void resetDatabaseFile() throws IOException {
        Path dbPath = Path.of("data", "lues.db");
        Files.createDirectories(dbPath.getParent());
        Files.deleteIfExists(dbPath);
    }

    private static void populateDatabase() throws SQLException {
        DatabaseHandler db = new DatabaseHandler("data/lues.db");
        try {
            int steps = 8;
            ConsoleLoadingBar pb = new ConsoleLoadingBar(steps);

            Map<Integer, List<String>> gradeToClasses = buildGradeMap();
            pb.set("Datenbank & Klassen anlegen", 1);
            createClasses(db, gradeToClasses);
            Map<String, Integer> classIds = loadIds(db, "Klasse", "klassenname", "idKlasse");

            pb.set("Schueler eintragen", 2);
            insertStudents(db, gradeToClasses, classIds);

            pb.set("Faecher anlegen", 3);
            createSubjects(db);
            Map<String, Integer> subjectIds = loadIds(db, "Fach", "fachname", "idFach");

            Map<String, TeacherMeta> teacherMeta = buildTeacherMeta();
            pb.set("Lehrkraefte anlegen", 4);
            createTeachers(db, subjectIds, teacherMeta);
            Map<String, Integer> teacherIds = loadTeacherIds(db, subjectIds);

            pb.set("Klasse-Fach-Zuordnungen", 5);
            createClassSubjectLinks(db, gradeToClasses, classIds, subjectIds, teacherIds);
            Map<ClassSubjectKey, Integer> ctIds = loadCtIds(db, classIds, subjectIds);

            pb.set("Tests anlegen", 6);
            createTests(db, ctIds);
            pb.set("Aufgaben anlegen", 7);
            createDummyTasksForAllTests(db);
            Map<ClassSubjectKey, Integer> testIds = loadTestIds(db, ctIds);

            Map<Integer, List<Integer>> studentsByClass = loadStudentsByClass(db);
            pb.set("Noten generieren", 8);
            insertNotes(db, gradeToClasses, classIds, studentsByClass, testIds, teacherIds);
        
            pb.finish("Fertig");
} finally {
            db.close();
        }
    }

    private static Map<Integer, List<String>> buildGradeMap() {
        Map<Integer, List<String>> map = new LinkedHashMap<>();
        map.put(5, List.of("5a", "5b"));
        map.put(6, List.of("6a", "6b"));
        map.put(7, List.of("7a", "7b"));
        map.put(8, List.of("8a", "8b"));
        map.put(9, List.of("9a", "9b", "9c"));
        map.put(10, List.of("10a", "10b", "10c"));
        map.put(11, List.of("11a", "11b"));
        map.put(12, List.of("12a", "12b"));
        return map;
    }

    private static void createClasses(DatabaseHandler db, Map<Integer, List<String>> gradeToClasses) throws SQLException {
        for (List<String> classes : gradeToClasses.values()) {
            for (String className : classes) {
                db.setEntry("Klasse", Map.of("klassenname", className));
            }
        }
    }

    private static void insertStudents(DatabaseHandler db,
                                       Map<Integer, List<String>> gradeToClasses,
                                       Map<String, Integer> classIds) throws SQLException {
        Random rnd = new Random();
        for (List<String> classes : gradeToClasses.values()) {
            for (String className : classes) {
                Integer classId = classIds.get(className);
                if (classId == null) continue;

                // Erstelle für jede Klasse eine zufällige Reihenfolge der Namen
                List<String> shuffledFirst = new ArrayList<>(FIRST_NAMES);
                List<String> shuffledLast = new ArrayList<>(LAST_NAMES);
                Collections.shuffle(shuffledFirst, rnd);
                Collections.shuffle(shuffledLast, rnd);

                for (int i = 0; i < STUDENTS_PER_CLASS; i++) {
                    String firstName = shuffledFirst.get(i % shuffledFirst.size());
                    String lastName = shuffledLast.get(i % shuffledLast.size()) + "-" + className + String.format(Locale.ROOT, "%02d", i + 1);
                    db.setEntry("Schueler", Map.of(
                            "idKlasse", classId,
                            "vorname", firstName,
                            "nachname", lastName
                    ));
                }
            }
        }
    }

    private static void createSubjects(DatabaseHandler db) throws SQLException {
        for (String subject : SUBJECTS) {
            db.setEntry("Fach", Map.of("fachname", subject));
        }
    }

    private static void createTeachers(DatabaseHandler db,
                                       Map<String, Integer> subjectIds,
                                       Map<String, TeacherMeta> teacherMeta) throws SQLException {
        for (Map.Entry<String, TeacherMeta> entry : teacherMeta.entrySet()) {
            Integer subjectId = subjectIds.get(entry.getKey());
            if (subjectId == null) {
                continue;
            }
            TeacherMeta meta = entry.getValue();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("fach1", subjectId);
            data.put("sex", meta.sex());
            data.put("vorname", meta.firstName());
            data.put("nachname", meta.lastName());
            db.setEntry("Lehrer", data);
        }
    }

    private static void createClassSubjectLinks(DatabaseHandler db,
                                                Map<Integer, List<String>> gradeToClasses,
                                                Map<String, Integer> classIds,
                                                Map<String, Integer> subjectIds,
                                                Map<String, Integer> teacherIds) throws SQLException {
        for (List<String> classes : gradeToClasses.values()) {
            for (String className : classes) {
                Integer classId = classIds.get(className);
                if (classId == null) {
                    continue;
                }
                for (String subject : SUBJECTS) {
                    Integer subjectId = subjectIds.get(subject);
                    Integer teacherId = teacherIds.get(subject);
                    if (subjectId == null || teacherId == null) {
                        continue;
                    }
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("idFach", subjectId);
                    data.put("idLehrer", teacherId);
                    data.put("idKlasse", classId);
                    db.setEntry("CT_KlasseLehrerFach", data);
                }
            }
        }
    }

    private static void createTests(DatabaseHandler db, Map<ClassSubjectKey, Integer> ctIds) throws SQLException {
        for (Map.Entry<ClassSubjectKey, Integer> entry : ctIds.entrySet()) {
            ClassSubjectKey key = entry.getKey();
            Integer ctId = entry.getValue();
            if (ctId == null) {
                continue;
            }
            String testLabel = key.subjectName() + " Test " + key.className();
            db.setEntry("Test", Map.of(
                    "typ", testLabel,
                    "idCT_KLF", ctId
            ));
        }
    }

    // Fügt einen MBI-Test mit 4 Aufgaben für 5a und 5b ein
    private static void insertDummyMBITestFor5a5b(DatabaseHandler db, Map<ClassSubjectKey, Integer> ctIds) throws SQLException {
        String[] aufgaben = {
            "Nenne zwei Beispiele für Software. ( /2 BE)",
            "Deine Freundin möchte einen Text am Laptop kopieren und in einem Dokument einfügen. Dabei will sie Tastenkombinationen verwenden. Erkläre ihr, wie sie das macht. ( /3 BE)",
            "Entscheide UND begründe, ob die folgenden Nachrichten in den MBI-Chat gehören. Verwende die KURZ-Regel. ( /4 BE)\n"
                + "a. Ey yo, Arbeitsblatt jetzt!\n"
                + "b. Guten Tag Herr Staacke, leider war ich heute krank. Würden Sie das Arbeitsblatt hochladen? Liebe Grüße Frederik",
            "Du willst Herr Dietrich über SDUI fragen, ob sie dir das Arbeitsblatt der letzten Stunde in der nächsten Stunde geben kann. Formuliere eine solche Nachricht unter Beachtung der KURZ-Regel. ( /3 BE)"
        };

        for (String klasse : List.of("5a", "5b")) {
            ClassSubjectKey key = new ClassSubjectKey(klasse, "MBI");
            Integer ctId = ctIds.get(key);
            if (ctId == null) continue;

            // Test anlegen
            Map<String, Object> testData = new LinkedHashMap<>();
            testData.put("typ", "MBI Test " + klasse);
            testData.put("idCT_KLF", ctId);
            db.setEntry("Test", testData);

            // Test-ID ermitteln (höchste ID mit passendem Typ)
            int testId = -1;
            for (Map<String, Object> row : db.getAllEntries("Test")) {
                if (("MBI Test " + klasse).equals(row.get("typ"))) {
                    testId = ((Number) row.get("idTest")).intValue();
                }
            }

            // Aufgaben anlegen und verknüpfen
            for (int i = 0; i < aufgaben.length; i++) {
                Map<String, Object> aufgabeData = new LinkedHashMap<>();
                aufgabeData.put("aufgabeMarkdown", aufgaben[i]);
                aufgabeData.put("typ", "Frei");
                aufgabeData.put("loesung", "");
                db.setEntry("Aufgabe", aufgabeData);

                // Aufgabe-ID ermitteln
                int aufgabeId = -1;
                for (Map<String, Object> row : db.getAllEntries("Aufgabe")) {
                    if (aufgaben[i].equals(row.get("aufgabeMarkdown"))) {
                        aufgabeId = ((Number) row.get("idAufgabe")).intValue();
                    }
                }

                // Verknüpfung anlegen
                Map<String, Object> ctTestAufgabe = new LinkedHashMap<>();
                ctTestAufgabe.put("idTest", testId);
                ctTestAufgabe.put("idAufgabe", aufgabeId);
                ctTestAufgabe.put("idLoesung", null);
                db.setEntry("CT_TestAufgabeLoesung", ctTestAufgabe);
            }
        }
    }

    private static final List<DummyTask> DEFAULT_DUMMY_TASKS = List.of(
            new DummyTask("Dummy-Aufgabe 1", "Frei", ""),
            new DummyTask("Dummy-Aufgabe 2", "Frei", ""),
            new DummyTask("Dummy-Aufgabe 3", "Frei", "")
    );

    private static final List<DummyTask> BASE_MBI_5_TASKS = List.of(
            new DummyTask("Nenne zwei Beispiele fuer Software. ( /2 BE)", "Frei", ""),
            new DummyTask("Deine Freundin moechte einen Text am Laptop kopieren und in einem Dokument einfuegen. Dabei will sie Tastenkombinationen verwenden. Erklaere ihr, wie sie das macht. ( /3 BE)", "Frei", ""),
            new DummyTask("Entscheide UND begruende, ob die folgenden Nachrichten in den MBI-Chat gehoeren. Verwende die KURZ-Regel. ( /4 BE)\n"
                    + "a. Ey yo, Arbeitsblatt jetzt!\n"
                    + "b. Guten Tag Herr Staacke, leider war ich heute krank. Wuerden Sie das Arbeitsblatt hochladen? Liebe Gruesse Frederik", "Frei", ""),
            new DummyTask("Du willst Herr Dietrich ueber SDUI fragen, ob sie dir das Arbeitsblatt der letzten Stunde in der naechsten Stunde geben kann. Formuliere eine solche Nachricht unter Beachtung der KURZ-Regel. ( /3 BE)", "Frei", "")
    );

    private static void createDummyTasksForAllTests(DatabaseHandler db) throws SQLException {
        List<Map<String, Object>> tests = db.getAllEntries("Test");
        for (Map<String, Object> test : tests) {
            int testId = ((Number) test.get("idTest")).intValue();
            String typ = String.valueOf(test.get("typ"));
            List<DummyTask> tasks = buildTasksForTestType(typ);
            for (DummyTask task : tasks) {
                Map<String, Object> aufgabeData = new LinkedHashMap<>();
                aufgabeData.put("aufgabeMarkdown", task.markdown());
                aufgabeData.put("typ", task.type());
                aufgabeData.put("loesung", task.solution());
                db.setEntry("Aufgabe", aufgabeData);

                int aufgabeId = -1;
                for (Map<String, Object> row : db.getAllEntries("Aufgabe")) {
                    if (task.markdown().equals(row.get("aufgabeMarkdown"))) {
                        aufgabeId = ((Number) row.get("idAufgabe")).intValue();
                    }
                }

                Map<String, Object> ctTestAufgabe = new LinkedHashMap<>();
                ctTestAufgabe.put("idTest", testId);
                ctTestAufgabe.put("idAufgabe", aufgabeId);
                ctTestAufgabe.put("idLoesung", null);
                db.setEntry("CT_TestAufgabeLoesung", ctTestAufgabe);
            }
        }
    }

    private static List<DummyTask> buildTasksForTestType(String testTyp) {
        if (testTyp == null) {
            return DEFAULT_DUMMY_TASKS;
        }
        if (testTyp.startsWith("MBI Test 5a")) {
            List<DummyTask> tasks = new ArrayList<>(BASE_MBI_5_TASKS);
            tasks.add(new DummyTask(
                    "[] Waehle die richtige Aussage zur Dateiablage der 5a im Informatik- und Medienkunde-Unterricht. (Nur eine Antwort ist korrekt.)",
                    "SingleChoice",
                    String.join("||",
                            "Wir legen Projektdateien im Klassenordner 5a-INF auf dem Schulserver ab, damit jede Gruppe Zugriff hat.",
                            "Wir schicken Quellcode als Foto in den Chat, so geht nichts verloren.",
                            "Wir speichern alles nur lokal am Tablet, weil das am schnellsten ist."
                    )
            ));
            tasks.add(new DummyTask(
                    "[_] Markiere alle Regeln, die im Informatik- und Medienkunde-Unterricht der 5a fuer den Klassenchat gelten. (Mehrere Antworten moeglich.)",
                    "MultiChoice",
                    String.join("||",
                            "Ich melde verdaechtige Links sofort der Lehrkraft.",
                            "Ich teile Passwoerter, damit alle Tools nutzen koennen.",
                            "Ich frage vor dem Teilen von Bildern nach, ob sie passend und erlaubt sind.",
                            "Ich schreibe nur Nachrichten mit Unterrichtsbezug."
                    )
            ));
            return tasks;
        }
        if (testTyp.startsWith("MBI Test 5b")) {
            return BASE_MBI_5_TASKS;
        }
        List<DummyTask> defaults = new ArrayList<>();
        for (DummyTask task : DEFAULT_DUMMY_TASKS) {
            defaults.add(new DummyTask(task.markdown() + " fuer " + testTyp, task.type(), task.solution()));
        }
        return defaults;
    }

    private static Map<String, Integer> loadIds(DatabaseHandler db,
                                                String table,
                                                String nameColumn,
                                                String idColumn) throws SQLException {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map<String, Object> row : db.getAllEntries(table)) {
            Object name = row.get(nameColumn);
            Object idValue = row.get(idColumn);
            if (name != null && idValue instanceof Number number) {
                result.put(name.toString(), number.intValue());
            }
        }
        return result;
    }

    private static Map<String, Integer> loadTeacherIds(DatabaseHandler db,
                                                       Map<String, Integer> subjectIds) throws SQLException {
        Map<Integer, String> subjectNamesById = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : subjectIds.entrySet()) {
            subjectNamesById.put(entry.getValue(), entry.getKey());
        }

        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map<String, Object> row : db.getAllEntries("Lehrer")) {
            Object idObj = row.get("idLehrer");
            if (!(idObj instanceof Number teacherIdNumber)) {
                continue;
            }
            int teacherId = teacherIdNumber.intValue();
            for (String fachColumn : List.of("fach1", "fach2", "fach3")) {
                Object subjectValue = row.get(fachColumn);
                if (subjectValue instanceof Number subjectIdNumber) {
                    String subjectName = subjectNamesById.get(subjectIdNumber.intValue());
                    if (subjectName != null && !result.containsKey(subjectName)) {
                        result.put(subjectName, teacherId);
                    }
                }
            }
        }
        return result;
    }

    private static Map<ClassSubjectKey, Integer> loadCtIds(DatabaseHandler db,
                                                           Map<String, Integer> classIds,
                                                           Map<String, Integer> subjectIds) throws SQLException {
        Map<Integer, String> classNamesById = invertMap(classIds);
        Map<Integer, String> subjectNamesById = invertMap(subjectIds);

        Map<ClassSubjectKey, Integer> result = new LinkedHashMap<>();
        for (Map<String, Object> row : db.getAllEntries("CT_KlasseLehrerFach")) {
            Object ctIdObj = row.get("idCT");
            Object classIdObj = row.get("idKlasse");
            Object subjectIdObj = row.get("idFach");
            if (!(ctIdObj instanceof Number ctNumber)
                    || !(classIdObj instanceof Number classNumber)
                    || !(subjectIdObj instanceof Number subjectNumber)) {
                continue;
            }
            String className = classNamesById.get(classNumber.intValue());
            String subjectName = subjectNamesById.get(subjectNumber.intValue());
            if (className != null && subjectName != null) {
                result.put(new ClassSubjectKey(className, subjectName), ctNumber.intValue());
            }
        }
        return result;
    }

    private static Map<ClassSubjectKey, Integer> loadTestIds(DatabaseHandler db,
                                                             Map<ClassSubjectKey, Integer> ctIds) throws SQLException {
        Map<Integer, ClassSubjectKey> keyByCtId = new LinkedHashMap<>();
        for (Map.Entry<ClassSubjectKey, Integer> entry : ctIds.entrySet()) {
            keyByCtId.put(entry.getValue(), entry.getKey());
        }

        Map<ClassSubjectKey, Integer> result = new LinkedHashMap<>();
        for (Map<String, Object> row : db.getAllEntries("Test")) {
            Object testIdObj = row.get("idTest");
            Object ctIdObj = row.get("idCT_KLF");
            if (!(testIdObj instanceof Number testNumber) || !(ctIdObj instanceof Number ctNumber)) {
                continue;
            }
            ClassSubjectKey key = keyByCtId.get(ctNumber.intValue());
            if (key != null) {
                result.put(key, testNumber.intValue());
            }
        }
        return result;
    }

    private static Map<Integer, List<Integer>> loadStudentsByClass(DatabaseHandler db) throws SQLException {
        Map<Integer, List<Integer>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : db.getAllEntries("Schueler")) {
            Object studentIdObj = row.get("idSchueler");
            Object classIdObj = row.get("idKlasse");
            if (!(studentIdObj instanceof Number studentNumber) || !(classIdObj instanceof Number classNumber)) {
                continue;
            }
            result.computeIfAbsent(classNumber.intValue(), ignored -> new ArrayList<>())
                    .add(studentNumber.intValue());
        }
        for (List<Integer> studentIds : result.values()) {
            studentIds.sort(Integer::compareTo);
        }
        return result;
    }

    private static void insertNotes(DatabaseHandler db,
                                    Map<Integer, List<String>> gradeToClasses,
                                    Map<String, Integer> classIds,
                                    Map<Integer, List<Integer>> studentsByClass,
                                    Map<ClassSubjectKey, Integer> testIds,
                                    Map<String, Integer> teacherIds) throws SQLException {
        Random rnd = new Random();
        for (Map.Entry<Integer, List<String>> gradeEntry : gradeToClasses.entrySet()) {
            int grade = gradeEntry.getKey();
            int maxYearOffset = grade - 5; // z.B. 5. Klasse: 0, 6. Klasse: 1, 7. Klasse: 2, ...
            for (String className : gradeEntry.getValue()) {
                Integer classId = classIds.get(className);
                if (classId == null) continue;
                List<Integer> studentIds = studentsByClass.getOrDefault(classId, List.of());
                if (studentIds.isEmpty()) continue;

                for (int subjectIndex = 0; subjectIndex < SUBJECTS.size(); subjectIndex++) {
                    String subject = SUBJECTS.get(subjectIndex);
                    Integer testId = testIds.get(new ClassSubjectKey(className, subject));
                    Integer teacherId = teacherIds.get(subject);
                    if (testId == null || teacherId == null) continue;

                    // Für jedes relevante Schuljahr
                    for (int yearOffset = 0; yearOffset <= maxYearOffset; yearOffset++) {
                        int year = 2025 - yearOffset;
                        int nextYear = year + 1;
                        String schuljahr = year + "/" + nextYear + " HJ1";

                        int notesPerStudent = (yearOffset == 0) ? 1 : 3; // 2025: 1 Note, sonst 3 Noten

                        for (int studentIdx = 0; studentIdx < studentIds.size(); studentIdx++) {
                            int studentId = studentIds.get(studentIdx);
                            for (int n = 0; n < notesPerStudent; n++) {
                                // Zufällige Note zwischen 1 und 6
                                String note = String.valueOf(1 + rnd.nextInt(6));
                                // Zufälliges Datum im Oktober
                                int day = 1 + rnd.nextInt(28);
                                String datum = String.format("%02d.10.%d", day, year);
                                db.insertNote(
                                        studentId,
                                        testId,
                                        note,
                                        datum,
                                        NOTE_TIME,
                                        schuljahr,
                                        teacherId
                                );
                            }
                        }
                    }
                }
            }
        }
    }

    private static LocalDate buildExamDate(int grade, int subjectIndex, int studentIndex) {
        int offset = Math.max(0, grade - 5);
        int startYear = 2025 - offset;
        int day = 5 + subjectIndex * 3 + studentIndex;
        if (day > 27) {
            day = 10 + ((day - 10) % 18);
        }
        return LocalDate.of(startYear, Month.OCTOBER, Math.max(1, Math.min(day, 28)));
    }

    private static String schoolYearForGrade(int grade) {
        int offset = Math.max(0, grade - 5);
        int startYear = 2025 - offset;
        int endYear = startYear + 1;
        return startYear + "/" + endYear + " HJ1";
    }

    private static Map<Integer, String> invertMap(Map<String, Integer> source) {
        Map<Integer, String> inverted = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : source.entrySet()) {
            inverted.put(entry.getValue(), entry.getKey());
        }
        return inverted;
    }
}
