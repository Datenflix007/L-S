package backend.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class TestImportService {

    private TestImportService() {
    }

    static Server.ImportResult importFromJson(DatabaseHandler db,
                                              Path file,
                                              LogfileHandler log,
                                              String boundIp) {
        if (db == null) {
            return new Server.ImportResult(false, "Interner Fehler: Datenbank nicht initialisiert.");
        }
        if (file == null) {
            return new Server.ImportResult(false, "Keine Datei ausgewählt.");
        }
        if (!Files.exists(file)) {
            return new Server.ImportResult(false, "Datei nicht gefunden: " + file.toAbsolutePath());
        }
        if (!Files.isRegularFile(file)) {
            return new Server.ImportResult(false, "Die ausgewählte Datei ist ungültig.");
        }

        ImportedTest imported;
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            Object parsed = SimpleJsonParser.parse(json);
            if (!(parsed instanceof Map<?, ?> rootMap)) {
                throw new IllegalArgumentException("JSON-Wurzel muss ein Objekt sein.");
            }
            imported = buildImportedTest(toObjectMap(rootMap));
        } catch (IOException e) {
            return new Server.ImportResult(false, "Datei konnte nicht gelesen werden: " + e.getMessage());
        } catch (JsonParseException e) {
            return new Server.ImportResult(false, "Ungültige JSON-Datei: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return new Server.ImportResult(false, e.getMessage());
        }

        try {
            int classId = ensureClass(db, imported.className());
            int subjectId = ensureSubject(db, imported.subject());
            int ctId = ensureClassSubject(db, classId, subjectId);

            Integer testId = findTestId(db, imported.name());
            boolean updated = testId != null;
            if (updated) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("typ", imported.name());
                data.put("idCT_KLF", ctId);
                db.setEntry("Test", testId, data);
            } else {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("typ", imported.name());
                data.put("idCT_KLF", ctId);
                db.setEntry("Test", data);
                testId = findTestId(db, imported.name());
            }

            if (testId == null) {
                throw new SQLException("Test-ID konnte nicht ermittelt werden.");
            }

            clearExistingTaskLinks(db, testId);

            int createdTasks = 0;
            for (ImportedTask task : imported.tasks()) {
                String markdown = applyPromptPrefix(task.prompt(), task.type());
                String solution = resolveSolutionString(task);

                Map<String, Object> taskData = new LinkedHashMap<>();
                taskData.put("typ", task.type());
                taskData.put("aufgabeMarkdown", markdown);
                taskData.put("loesung", solution);
                db.setEntry("Aufgabe", taskData);

                int aufgabeId = findLatestTaskId(db, task.type(), markdown, solution);

                Map<String, Object> linkData = new LinkedHashMap<>();
                linkData.put("idTest", testId);
                linkData.put("idAufgabe", aufgabeId);
                linkData.put("idLoesung", null);
                db.setEntry("CT_TestAufgabeLoesung", linkData);
                createdTasks++;
            }

            if (log != null) {
                String targetIp = boundIp == null || boundIp.isBlank() ? "127.0.0.1" : boundIp;
                String op = (updated ? "UPDATE" : "INSERT") + "|Test|" + imported.name() + " (" + createdTasks + " Aufgaben)";
                log.writeLog("Server", targetIp, op);
            }

            String message = (updated ? "Test aktualisiert: " : "Test importiert: ") + imported.name()
                    + " (" + imported.tasks().size() + " Aufgaben).";
            return new Server.ImportResult(true, message);
        } catch (SQLException e) {
            return new Server.ImportResult(false, "Datenbankfehler: " + e.getMessage());
        }
    }

    private static ImportedTest buildImportedTest(Map<String, Object> root) {
        String name = requireNonEmptyString(root.get("name"), "Testname fehlt oder ist leer.");
        String subject = requireNonEmptyString(root.get("subject"), "Fach fehlt oder ist leer.");
        String className = requireNonEmptyString(root.get("className"), "Klassenname fehlt oder ist leer.");

        Object tasksObj = root.get("tasks");
        if (!(tasksObj instanceof List<?> tasksList)) {
            throw new IllegalArgumentException("Feld \"tasks\" fehlt oder ist kein Array.");
        }
        if (tasksList.isEmpty()) {
            throw new IllegalArgumentException("Der Test enthält keine Aufgaben.");
        }

        List<ImportedTask> tasks = new ArrayList<>();
        int index = 0;
        for (Object entry : tasksList) {
            index++;
            if (!(entry instanceof Map<?, ?> taskMapRaw)) {
                throw new IllegalArgumentException("Aufgabe " + index + " ist kein Objekt.");
            }
            Map<String, Object> taskMap = toObjectMap(taskMapRaw);
            String type = requireNonEmptyString(taskMap.get("type"),
                    "Aufgabe " + index + ": Feld \"type\" fehlt oder ist leer.");
            String prompt = requireNonEmptyString(taskMap.get("prompt"),
                    "Aufgabe " + index + ": Feld \"prompt\" fehlt oder ist leer.");
            String solution = normalizeString(taskMap.get("solution"));
            List<String> options = toStringList(taskMap.get("options"));
            tasks.add(new ImportedTask(type, prompt, solution, options));
        }

        return new ImportedTest(name, subject, className, tasks);
    }

    private static void clearExistingTaskLinks(DatabaseHandler db, int testId) throws SQLException {
        List<Map<String, Object>> links = db.getAllEntries("CT_TestAufgabeLoesung");
        for (Map<String, Object> row : links) {
            if (row.get("idTest") instanceof Number idTestNumber
                    && row.get("idCT") instanceof Number idCtNumber
                    && idTestNumber.intValue() == testId) {
                db.delEntry("CT_TestAufgabeLoesung", idCtNumber.intValue());
            }
        }
    }

    private static int findLatestTaskId(DatabaseHandler db, String type, String markdown, String solution) throws SQLException {
        List<Map<String, Object>> tasks = db.getAllEntries("Aufgabe");
        int candidate = -1;
        for (Map<String, Object> row : tasks) {
            Object idObj = row.get("idAufgabe");
            if (!(idObj instanceof Number number)) {
                continue;
            }
            String rowType = normalizeString(row.get("typ"));
            String rowMarkdown = normalizeString(row.get("aufgabeMarkdown"));
            String rowSolution = normalizeString(row.get("loesung"));
            if (Objects.equals(rowType, normalizeString(type))
                    && Objects.equals(rowMarkdown, normalizeString(markdown))
                    && Objects.equals(rowSolution, normalizeString(solution))) {
                candidate = Math.max(candidate, number.intValue());
            }
        }
        if (candidate < 0) {
            throw new SQLException("Aufgabe konnte nach dem Einfügen nicht gefunden werden.");
        }
        return candidate;
    }

    private static Integer findTestId(DatabaseHandler db, String testName) throws SQLException {
        List<Map<String, Object>> tests = db.getAllEntries("Test");
        Integer candidate = null;
        String target = normalizeString(testName);
        for (Map<String, Object> row : tests) {
            Object idObj = row.get("idTest");
            if (!(idObj instanceof Number number)) {
                continue;
            }
            String name = normalizeString(row.get("typ"));
            if (Objects.equals(name, target)) {
                if (candidate == null || number.intValue() > candidate) {
                    candidate = number.intValue();
                }
            }
        }
        return candidate;
    }

    private static int ensureClass(DatabaseHandler db, String className) throws SQLException {
        String target = normalizeString(className);
        List<Map<String, Object>> entries = db.getAllEntries("Klasse");
        for (Map<String, Object> row : entries) {
            Object idObj = row.get("idKlasse");
            if (!(idObj instanceof Number number)) {
                continue;
            }
            if (Objects.equals(target, normalizeString(row.get("klassenname")))) {
                return number.intValue();
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("klassenname", className);
        db.setEntry("Klasse", data);
        return ensureClass(db, className);
    }

    private static int ensureSubject(DatabaseHandler db, String subjectName) throws SQLException {
        String target = normalizeString(subjectName);
        List<Map<String, Object>> entries = db.getAllEntries("Fach");
        for (Map<String, Object> row : entries) {
            Object idObj = row.get("idFach");
            if (!(idObj instanceof Number number)) {
                continue;
            }
            if (Objects.equals(target, normalizeString(row.get("fachname")))) {
                return number.intValue();
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fachname", subjectName);
        db.setEntry("Fach", data);
        return ensureSubject(db, subjectName);
    }

    private static int ensureClassSubject(DatabaseHandler db, int classId, int subjectId) throws SQLException {
        List<Map<String, Object>> entries = db.getAllEntries("CT_KlasseLehrerFach");
        for (Map<String, Object> row : entries) {
            Object idObj = row.get("idCT");
            Object classObj = row.get("idKlasse");
            Object subjectObj = row.get("idFach");
            if (idObj instanceof Number idNumber
                    && classObj instanceof Number classNumber
                    && subjectObj instanceof Number subjectNumber
                    && classNumber.intValue() == classId
                    && subjectNumber.intValue() == subjectId) {
                return idNumber.intValue();
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("idFach", subjectId);
        data.put("idLehrer", null);
        data.put("idKlasse", classId);
        db.setEntry("CT_KlasseLehrerFach", data);
        return ensureClassSubject(db, classId, subjectId);
    }

    private static String applyPromptPrefix(String prompt, String type) {
        String trimmedPrompt = prompt == null ? "" : prompt.trim();
        if (trimmedPrompt.isEmpty()) {
            return "";
        }
        String normalizedType = normalizeString(type);
        if ("singlechoice".equals(normalizedType)) {
            return trimmedPrompt.startsWith("[]") ? trimmedPrompt : "[] " + trimmedPrompt;
        }
        if ("multichoice".equals(normalizedType)) {
            return trimmedPrompt.startsWith("[_]") ? trimmedPrompt : "[_] " + trimmedPrompt;
        }
        return trimmedPrompt;
    }

    private static String resolveSolutionString(ImportedTask task) {
        if (task == null) {
            return "";
        }
        String type = normalizeString(task.type());
        if ("frei".equals(type)) {
            return normalizeString(task.solution());
        }
        List<String> values = new ArrayList<>();
        for (String option : task.options()) {
            String trimmed = normalizeString(option);
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        String preparedSolution = normalizeString(task.solution());
        if (values.isEmpty() && !preparedSolution.isEmpty()) {
            values.add(preparedSolution);
        }
        return String.join("||", values);
    }

    private static Map<String, Object> toObjectMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            Object key = entry.getKey();
            if (!(key instanceof String keyString)) {
                throw new IllegalArgumentException("JSON-Schlüssel müssen vom Typ String sein.");
            }
            result.put(keyString, entry.getValue());
        }
        return result;
    }

    private static List<String> toStringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("Feld \"options\" muss ein Array sein.");
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            result.add(String.valueOf(item));
        }
        return result;
    }

    private static String requireNonEmptyString(Object value, String errorMessage) {
        String normalized = normalizeString(value);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(errorMessage);
        }
        return normalized;
    }

    private static String normalizeString(Object value) {
        if (value == null) {
            return "";
        }
        String string = String.valueOf(value);
        return string == null ? "" : string.trim();
    }

    private record ImportedTest(String name, String subject, String className, List<ImportedTask> tasks) {
    }

    private record ImportedTask(String type, String prompt, String solution, List<String> options) {
    }

    private static final class SimpleJsonParser {
        private final String input;
        private int index;

        private SimpleJsonParser(String input) {
            this.input = input == null ? "" : input;
            this.index = 0;
        }

        static Object parse(String input) throws JsonParseException {
            SimpleJsonParser parser = new SimpleJsonParser(input);
            Object value = parser.parseValue();
            parser.skipWhitespace();
            if (!parser.endOfInput()) {
                throw parser.error("Unerwartetes Zeichen nach JSON-Ende.");
            }
            return value;
        }

        private Object parseValue() throws JsonParseException {
            skipWhitespace();
            if (endOfInput()) {
                throw error("Unerwartetes Ende der Eingabe.");
            }
            char ch = currentChar();
            return switch (ch) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> {
                    if (ch == '-' || Character.isDigit(ch)) {
                        yield parseNumber();
                    }
                    throw error("Unerwartetes Zeichen: " + ch);
                }
            };
        }

        private Map<String, Object> parseObject() throws JsonParseException {
            expect('{');
            Map<String, Object> result = new LinkedHashMap<>();
            skipWhitespace();
            if (peek('}')) {
                expect('}');
                return result;
            }
            while (true) {
                skipWhitespace();
                if (currentChar() != '"') {
                    throw error("Objektschlüssel müssen Strings sein.");
                }
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                result.put(key, value);
                skipWhitespace();
                if (peek(',')) {
                    expect(',');
                    continue;
                }
                expect('}');
                break;
            }
            return result;
        }

        private List<Object> parseArray() throws JsonParseException {
            expect('[');
            List<Object> result = new ArrayList<>();
            skipWhitespace();
            if (peek(']')) {
                expect(']');
                return result;
            }
            while (true) {
                Object value = parseValue();
                result.add(value);
                skipWhitespace();
                if (peek(',')) {
                    expect(',');
                    continue;
                }
                expect(']');
                break;
            }
            return result;
        }

        private String parseString() throws JsonParseException {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (!endOfInput()) {
                char ch = currentChar();
                index++;
                if (ch == '"') {
                    return sb.toString();
                }
                if (ch == '\\') {
                    if (endOfInput()) {
                        throw error("Unvollständige Escape-Sequenz am Ende des Strings.");
                    }
                    char escaped = currentChar();
                    index++;
                    switch (escaped) {
                        case '"', '\\', '/' -> sb.append(escaped);
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> sb.append(parseUnicodeEscape());
                        default -> throw error("Ungültige Escape-Sequenz: \\" + escaped);
                    }
                } else {
                    sb.append(ch);
                }
            }
            throw error("Unterminierter String.");
        }

        private char parseUnicodeEscape() throws JsonParseException {
            if (index + 4 > input.length()) {
                throw error("Unvollständige Unicode-Escape-Sequenz.");
            }
            int codePoint = 0;
            for (int i = 0; i < 4; i++) {
                char ch = input.charAt(index++);
                int digit = Character.digit(ch, 16);
                if (digit < 0) {
                    throw error("Ungültiges Hex-Zeichen in Unicode-Escape: " + ch);
                }
                codePoint = (codePoint << 4) + digit;
            }
            return (char) codePoint;
        }

        private Object parseNumber() throws JsonParseException {
            int start = index;
            if (currentChar() == '-') {
                index++;
            }
            consumeDigits("Ziffer nach dem Vorzeichen erforderlich.");
            if (peek('.')) {
                index++;
                consumeDigits("Ziffern nach dem Dezimalpunkt erforderlich.");
            }
            if (peek('e') || peek('E')) {
                index++;
                if (peek('+') || peek('-')) {
                    index++;
                }
                consumeDigits("Ziffern im Exponenten erforderlich.");
            }
            String numberText = input.substring(start, index);
            try {
                if (numberText.contains(".") || numberText.contains("e") || numberText.contains("E")) {
                    return Double.parseDouble(numberText);
                }
                long value = Long.parseLong(numberText);
                if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
                    return (int) value;
                }
                return value;
            } catch (NumberFormatException ex) {
                throw error("Ungültige Zahl: " + numberText);
            }
        }

        private void consumeDigits(String errorMessage) throws JsonParseException {
            if (endOfInput() || !Character.isDigit(currentChar())) {
                throw error(errorMessage);
            }
            while (!endOfInput() && Character.isDigit(currentChar())) {
                index++;
            }
        }

        private Object parseLiteral(String literal, Object value) throws JsonParseException {
            int length = literal.length();
            if (index + length > input.length()) {
                throw error("Unvollständiges Literal: " + literal);
            }
            String slice = input.substring(index, index + length);
            if (!slice.equals(literal)) {
                throw error("Unerwartetes Literal: " + slice);
            }
            index += length;
            return value;
        }

        private void expect(char expected) throws JsonParseException {
            skipWhitespace();
            if (endOfInput() || currentChar() != expected) {
                throw error("Erwartet '" + expected + "'.");
            }
            index++;
        }

        private boolean peek(char expected) {
            skipWhitespace();
            return !endOfInput() && currentChar() == expected;
        }

        private void skipWhitespace() {
            while (!endOfInput()) {
                char ch = currentChar();
                if (ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n') {
                    index++;
                } else {
                    break;
                }
            }
        }

        private boolean endOfInput() {
            return index >= input.length();
        }

        private char currentChar() {
            return input.charAt(index);
        }

        private JsonParseException error(String message) {
            return new JsonParseException(message + " (Position " + index + ")");
        }
    }

    private static final class JsonParseException extends Exception {
        JsonParseException(String message) {
            super(message);
        }
    }
}
