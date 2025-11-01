package backend.server;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** LogfileHandler class to handle logging operations 
 *  This class is responsible for writing logs to a file.
 *  It creates a log file if it does not exist and appends log entries to it
*/

public class LogfileHandler {

    /* 
     * Konstruktor der LogfileHandler-Klasse
     */
    public LogfileHandler() {
        // Konstruktor kann leer bleiben
    }

    /**
     * Methode zum Schreiben von LogeintrÃƒÂ¤gen in eine Logdatei
     * @param entity Die EntitÃƒÂ¤t, die im Log vermerkt wird (z.B. "Database")
     * @param ip Die IP-Adresse des Clients, der die Aktion ausgefÃƒÂ¼hrt hat
     * @param message Die Nachricht, die im Log vermerkt wird
     */
    public void writeLog(String entity, String ip, String message) {
        try {
            new File("data").mkdirs(); // Stelle sicher, dass der Ordner existiert
            File logFile = new File("data/log.txt");
            boolean fileExists = logFile.exists();

            // Erstelle die Logdatei, falls sie nicht existiert
            try (FileWriter writer = new FileWriter(logFile, true)) {
                if (!fileExists) {
                    writer.write("+---------------------+-----------+---------------+------------+----------+------------------------------------------+\n");
                    writer.write("| Diese Logdatei wurde automatisch erstellt und gehÃƒÂ¶rt zu der LernstandsÃƒÂ¼berprÃƒÂ¼fungssoftware.\n");
                    writer.write("+---------------------+-----------+---------------+------------+----------+------------------------------------------+\n");
                    writer.write("| Uhrzeit             | EntitÃƒÂ¤t   | IPv4-Adresse  | Operation  | Table    | Details                                  |\n");
                    writer.write("+---------------------+-----------+---------------+------------+----------+------------------------------------------+\n");
                }
                LocalDateTime jetzt = LocalDateTime.now();
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
                String formatiert = jetzt.format(formatter);

                // Extrahiere Operation, Table und Details aus message
                String[] parts = message.split("\\|");
                String operation = "";
                String table = "";
                String details = "";
                if (parts.length >= 4) {
                    operation = parts[1].trim();
                    table = parts[2].trim();
                    details = parts[3].trim();
                } else {
                    details = message.trim();
                }

                // Schreibe den Logeintrag
                writer.write(String.format(
                    "| %-19s | %-9s | %-13s | %-10s | %-8s | %-40s |\n",
                    formatiert,
                    entity,
                    ip,
                    operation,
                    table,
                    details
                ));
            }
        } catch (IOException e) {
            System.err.println("Fehler beim Schreiben in die Logdatei: " + e.getMessage());
        }
    }
}
