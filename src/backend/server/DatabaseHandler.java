package backend.server;

import java.sql.*;
import java.util.*;

/** DatabaseHandler class to handle database operations
 *  This class is responsible for managing the SQLite database connection,
 *  creating tables, and performing CRUD operations on the database.
 * 
 *  Callable methods:
 *  - setEntry: Insert or update an entry in a specified table.
 *  - getEntry: Retrieve a single entry by ID from a specified table.
 *  - delEntry: Delete an entry by ID from a specified table.
 *  - getAllEntries: Retrieve all entries from a specified table.
 * 
 * The database schema includes tables for subjects, teachers, classes, tests, tasks, answers, labels, and their relationships.
 * The LogfileHandler is used to log database operations.
 */
public class DatabaseHandler {

    private static final String DB_DRIVER = "org.sqlite.JDBC";

    private final String dbUrl;
    private final Connection conn;
    private final LogfileHandler logger = new LogfileHandler();

    /**
     * Constructor for DatabaseHandler class
     * Initializes the database connection and creates necessary tables if they do not exist.
     * @short Establishes a connection to the SQLite database and initializes the schema.
     * @throws SQLException if there is an error connecting to the database or executing SQL statements.
     */
    public DatabaseHandler(String path) throws SQLException {
        this.dbUrl = "jdbc:sqlite:" + path;

        try {
            Class.forName(DB_DRIVER);
            this.conn = DriverManager.getConnection(dbUrl);
            initializeDatabase();
        } catch (ClassNotFoundException e) {
            throw new SQLException("JDBC-Treiber konnte nicht geladen werden: " + e.getMessage());
        }
    }

    /**
     * Initializes the database schema by creating necessary tables.
     * @short Creates tables for subjects, teachers, classes, tests, tasks, answers, labels, and their relationships.
     * @throws SQLException if there is an error executing SQL statements.
     */
    private void initializeDatabase() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("""
        CREATE TABLE IF NOT EXISTS Fach (
            idFach INTEGER PRIMARY KEY,
            fachname TEXT NOT NULL
        );
        """);
            stmt.executeUpdate("""
        CREATE TABLE IF NOT EXISTS Lehrer (
            idLehrer INTEGER PRIMARY KEY,
            fach1 INTEGER,
            fach2 INTEGER,
            fach3 INTEGER,
            sex CHAR(1),
            vorname TEXT,
            nachname TEXT,
            FOREIGN KEY(fach1) REFERENCES Fach(idFach),
            FOREIGN KEY(fach2) REFERENCES Fach(idFach),
            FOREIGN KEY(fach3) REFERENCES Fach(idFach)
        );
        """);
            stmt.executeUpdate("""
        CREATE TABLE IF NOT EXISTS Klasse (
            idKlasse INTEGER PRIMARY KEY,
            klassenname TEXT
        );
        """);
            stmt.executeUpdate("""
        CREATE TABLE IF NOT EXISTS CT_KlasseLehrerFach (
            idCT INTEGER PRIMARY KEY,
            idFach INTEGER,
            idLehrer INTEGER,
            idKlasse INTEGER,
            FOREIGN KEY(idFach) REFERENCES Fach(idFach),
            FOREIGN KEY(idLehrer) REFERENCES Lehrer(idLehrer),
            FOREIGN KEY(idKlasse) REFERENCES Klasse(idKlasse)
        );
        """);
            stmt.executeUpdate("""
        CREATE TABLE IF NOT EXISTS Schueler (
            idSchueler INTEGER PRIMARY KEY,
            idKlasse INTEGER,
            vorname TEXT,
            nachname TEXT,
            FOREIGN KEY(idKlasse) REFERENCES Klasse(idKlasse)
        );
        """);
            stmt.executeUpdate("""
        CREATE TABLE IF NOT EXISTS Test (
            idTest INTEGER PRIMARY KEY,
            typ TEXT,
            idCT_KLF INTEGER,
            FOREIGN KEY(idCT_KLF) REFERENCES CT_KlasseLehrerFach(idCT)
        );
        """);
            stmt.executeUpdate("""
        CREATE TABLE IF NOT EXISTS Aufgabe (
            idAufgabe INTEGER PRIMARY KEY,
            typ TEXT,
            aufgabeMarkdown TEXT,
            loesung TEXT
        );
        """);
            stmt.executeUpdate("""
        CREATE TABLE IF NOT EXISTS CT_TestAufgabeLoesung (
            idCT INTEGER PRIMARY KEY,
            idTest INTEGER,
            idAufgabe INTEGER,
            idLoesung INTEGER,
            FOREIGN KEY(idTest) REFERENCES Test(idTest),
            FOREIGN KEY(idAufgabe) REFERENCES Aufgabe(idAufgabe)
        );
        """);
            stmt.executeUpdate("""
        CREATE TABLE IF NOT EXISTS Antwort (
            idAntwort INTEGER PRIMARY KEY,
            idSchueler INTEGER,
            idTest INTEGER,
            idAufgabe INTEGER,
            antwort TEXT,
            FOREIGN KEY(idSchueler) REFERENCES Schueler(idSchueler),
            FOREIGN KEY(idTest) REFERENCES Test(idTest),
            FOREIGN KEY(idAufgabe) REFERENCES Aufgabe(idAufgabe)
        );
        """);
            stmt.executeUpdate("""
        CREATE TABLE IF NOT EXISTS Label (
            idLabel INTEGER PRIMARY KEY,
            content TEXT
        );
        """);
            stmt.executeUpdate("""
        CREATE TABLE IF NOT EXISTS CT_TestLabel (
            idTest INTEGER,
            idLabel INTEGER,
            art TEXT,
            PRIMARY KEY(idTest, idLabel),
            FOREIGN KEY(idTest) REFERENCES Test(idTest),
            FOREIGN KEY(idLabel) REFERENCES Label(idLabel)
        );
        """);
            stmt.executeUpdate("""
        CREATE TABLE IF NOT EXISTS Bewertung (
            idBewertung INTEGER PRIMARY KEY,
            idAntwort INTEGER UNIQUE,
            bewertung TEXT,
            kommentar TEXT,
            punkte REAL,
            bewertetAm TEXT,
            FOREIGN KEY(idAntwort) REFERENCES Antwort(idAntwort)
        );
        """);
            stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS Note (
                idNote INTEGER PRIMARY KEY AUTOINCREMENT,
                idSchueler INTEGER,
                idTest INTEGER,
                note TEXT,
                datum TEXT,
                uhrzeit TEXT,
                schuljahr TEXT,
                idLehrer INTEGER
            )
        """);
        }
    }

    /**
     * Inserts a new entry into the specified table or updates an existing entry.
     * @short Inserts or updates an entry in a specified table.
     * @param table The name of the table to insert into.
     * @param data A map containing column names and their corresponding values.
     * @short The map should include all required fields, including the primary key if updating.
     * @throws SQLException if there is an error executing SQL statements.
     */
    private String resolveIdColumn(String table) {
        return switch (table) {
            case "Test" -> "idTest";
            case "CT_KlasseLehrerFach", "CT_TestAufgabeLoesung" -> "idCT";
            case "Aufgabe" -> "idAufgabe";
            case "Lehrer" -> "idLehrer";
            case "Fach" -> "idFach";
            case "Klasse" -> "idKlasse";
            case "Schueler" -> "idSchueler";
            case "Antwort" -> "idAntwort";
            case "Label" -> "idLabel";
            default -> "id" + table;
        };
    }

    public void setEntry(String table, Map<String, Object> data) throws SQLException {
        String idColumn = resolveIdColumn(table);
        int newId = 1;
        String getMaxIdSql = "SELECT MAX(" + idColumn + ") FROM " + table;
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(getMaxIdSql)) {
            if (rs.next()) {
                newId = rs.getInt(1) + 1;
            }
        }
        Map<String, Object> fullData = new LinkedHashMap<>();
        fullData.put(idColumn, newId);
        fullData.putAll(data);
        String columns = String.join(", ", fullData.keySet());
        String placeholders = String.join(", ", Collections.nCopies(fullData.size(), "?"));
        String sql = "INSERT INTO " + table + " (" + columns + ") VALUES (" + placeholders + ")";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            int index = 1;
            for (Object value : fullData.values()) {
                pstmt.setObject(index++, value);
            }
            pstmt.executeUpdate();
        }
        logger.writeLog("[Database]", "127.0.0.1", String.format(
            "| %-10s | %-8s | %-40s |",
            "INSERT", table, data.toString()
        ));
    }

    /**
     * Updates an existing entry in the specified table by ID.
     * @short Updates an entry in a specified table by ID.
     * @param table The name of the table to update.
     * @param id The ID of the entry to update.
     * @param data A map containing column names and their corresponding values to update.
     * @throws SQLException if there is an error executing SQL statements.
     */
    public void setEntry(String table, int id, Map<String, Object> data) throws SQLException {
        String idColumn = resolveIdColumn(table);
        List<String> assignments = data.keySet().stream().map(key -> key + " = ?").toList();
        String sql = "UPDATE " + table + " SET " + String.join(", ", assignments) + " WHERE " + idColumn + " = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            int index = 1;
            for (Object value : data.values()) {
                pstmt.setObject(index++, value);
            }
            pstmt.setObject(index, id);
            pstmt.executeUpdate();
        }
        logger.writeLog("[Database]", "127.0.0.1", String.format(
            "| %-10s | %-8s | id=%-5d | %-40s |",
            "UPDATE", table, id, data.toString()
        ));
    }

    /**
     * Retrieves a single entry by ID from the specified table.
     * @short Retrieves a single entry by ID from a specified table.
     * @param table The name of the table to query.
     * @param id The ID of the entry to retrieve.
     * @return A map containing the column names and their corresponding values for the entry.
     * @throws SQLException if there is an error executing SQL statements.
     */
    public Map<String, Object> getEntry(String table, int id) throws SQLException {
        String idColumn = resolveIdColumn(table);
        String sql = "SELECT * FROM " + table + " WHERE " + idColumn + " = ?";
        Map<String, Object> result = new LinkedHashMap<>();
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setObject(1, id);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    ResultSetMetaData metaData = rs.getMetaData();
                    for (int i = 1; i <= metaData.getColumnCount(); i++) {
                        result.put(metaData.getColumnName(i), rs.getObject(i));
                    }
                }
            }
        }
        logger.writeLog("[Database]", "127.0.0.1", String.format(
            "| %-10s | %-8s | id=%-5d | %-40s |",
            "SELECT", table, id, result.toString()
        ));
        return result;
    }

    /**
     * Retrieves the first entry by matching a column value.
     * @short Retrieves the first entry from a specified table filtered by a column.
     * @param table The name of the table to query.
     * @param column The column used for filtering.
     * @param value The value to match within the specified column.
     * @return A map of column names to values for the matching entry, or null if none is found.
     * @throws SQLException if there is an error executing SQL statements.
     */
    public Map<String, Object> getEntryWhere(String table, String column, Object value) throws SQLException {
        String sql = "SELECT * FROM " + table + " WHERE " + column + " = ? LIMIT 1";
        Map<String, Object> result = new LinkedHashMap<>();
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setObject(1, value);
            try (ResultSet rs = pstmt.executeQuery()) {
                ResultSetMetaData metaData = rs.getMetaData();
                if (rs.next()) {
                    for (int i = 1; i <= metaData.getColumnCount(); i++) {
                        result.put(metaData.getColumnName(i), rs.getObject(i));
                    }
                }
            }
        }
        logger.writeLog("[Database]", "127.0.0.1", String.format(
            "| %-10s | %-8s | %s=%s | %-40s |",
            "SELECT", table, column, String.valueOf(value), result.isEmpty() ? "{}" : result.toString()
        ));
        return result.isEmpty() ? null : result;
    }

    /**
     * Inserts or updates a Bewertung entry using the Antwort identifier.
     * @short Upserts a Bewertung row keyed by idAntwort.
     * @param idAntwort The answer ID to associate with the Bewertung.
     * @param bewertung The textual Bewertung (e.g. korrekt, falsch).
     * @param kommentar Optional free-text comment.
     * @param punkte Optional numeric score.
     * @param bewertetAm Timestamp string when the Bewertung was made.
     * @throws SQLException if there is an error executing SQL statements.
     */
    public void upsertBewertung(int idAntwort, String bewertung, String kommentar, Double punkte, String bewertetAm) throws SQLException {
        Map<String, Object> existing = getEntryWhere("Bewertung", "idAntwort", idAntwort);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("idAntwort", idAntwort);
        data.put("bewertung", bewertung);
        data.put("kommentar", kommentar);
        data.put("punkte", punkte);
        data.put("bewertetAm", bewertetAm);
        if (existing == null) {
            setEntry("Bewertung", data);
        } else {
            int idBewertung = ((Number) existing.get("idBewertung")).intValue();
            setEntry("Bewertung", idBewertung, data);
        }
    }

    /**
     * Deletes an entry by ID from the specified table.
     * @short Deletes an entry by ID from a specified table.
     * @param table The name of the table to delete from.
     * @param id The ID of the entry to delete.
     * @throws SQLException if there is an error executing SQL statements.
     */
    public void delEntry(String table, int id) throws SQLException {
        String idColumn = resolveIdColumn(table);
        String sql = "DELETE FROM " + table + " WHERE " + idColumn + " = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setObject(1, id);
            pstmt.executeUpdate();
        }
        logger.writeLog("[Database]", "127.0.0.1", String.format(
            "| %-10s | %-8s | id=%-5d |",
            "DELETE", table, id
        ));
    }

    /**
     * Retrieves all entries from the specified table.
     * @short Retrieves all entries from a specified table.
     * @param table The name of the table to query.
     * @return A list of maps, each containing column names and their corresponding values for each entry.
     * @throws SQLException if there is an error executing SQL statements.
     */
    public List<Map<String, Object>> getAllEntries(String table) throws SQLException {
        String sql = "SELECT * FROM " + table;
        List<Map<String, Object>> results = new ArrayList<>();
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            ResultSetMetaData metaData = rs.getMetaData();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= metaData.getColumnCount(); i++) {
                    row.put(metaData.getColumnName(i), rs.getObject(i));
                }
                results.add(row);
            }
        }
        logger.writeLog("[Database]", "127.0.0.1", String.format(
            "| %-10s | %-8s | %-40s |",
            "SELECT ALL", table, "Rows: " + results.size()
        ));
        return results;
    }

    /**
     * Closes the database connection.
     * @short Closes the SQLite database connection.
     * @throws SQLException if there is an error closing the connection.
     */
    public void close() throws SQLException {
        if (conn != null) {
            conn.close();
        }
    }

    public void insertNote(int idSchueler, int idTest, String note, String datum, String uhrzeit, String schuljahr, int idLehrer) throws SQLException {
        String sql = "INSERT INTO Note (idSchueler, idTest, note, datum, uhrzeit, schuljahr, idLehrer) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, idSchueler);
            pstmt.setInt(2, idTest);
            pstmt.setString(3, note);
            pstmt.setString(4, datum);
            pstmt.setString(5, uhrzeit);
            pstmt.setString(6, schuljahr);
            pstmt.setInt(7, idLehrer);
            pstmt.executeUpdate();
        }
        logger.writeLog("[Database]", "127.0.0.1", String.format(
            "| %-10s | %-8s | %-40s |",
            "INSERT", "Note", String.format("Schueler=%d, Test=%d, Note=%s", idSchueler, idTest, note)
        ));
    }

  
}
