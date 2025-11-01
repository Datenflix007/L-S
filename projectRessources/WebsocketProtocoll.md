<div style="text-align: justify;">

# Websocket - Protokoll


![Bild](https://git.uni-jena.de/fusion/teaching/thesis/projektarbeit-tobias-friedrich-und-felix-staacke/docu/-/raw/main/modelledWorkflow/authentification.png)

## Gliederung
[Konvention: Instanznamen](#konvention-instanznamen)<br />
[Konvention: Message-Type](#konvention-message-type)<br />
[Konvention: Arguments](#konvention-arguments)<br />
[Exemplarische Kommunikationssituationen](#exemplarische-kommunikationssituationen)<br />
- [Neuer Verbindungsaufbau erfolgreich](#beispiel-neuer-verbindungsaufbau-erfolgreich)<br />
- [Neuer Verbindungsaufbau nicht erfolgreich](#beispiel-neuer-verbindungsaufbau-nicht-erfolgreich)<br />
- [Aufgabenantwort und nächste Aufgabe](#beispiel-aufgabenantwort-übermitteln-und-nächste-aufgabe-erhalten)<br />
- [Aufgabenabgabe durch Schüler](#beispiel-schüler-möchte-aufgaben-abgeben)<br />
- [Fensterverlust durch Schüler (WARNING)](#beispiel-schüler-verlässt-das-fenster)<br />
- [Zeitablauf: Testende durch Server](#beispiel-zeit-abgelaufen--test-wird-bei-allen-beendet)<br />

[Template für die Implementierung mit API](#template-implementierung-mit-api)<br />
[Template für die Implementierung mit API](#template-implementierung-ohne-api)<br />

## Organisation
- branch: [``websocket_communication``](https://git.uni-jena.de/fusion/teaching/thesis/projektarbeit-tobias-friedrich-und-felix-staacke/lues/-/tree/websocket_communication?ref_type=heads)

## Konvention: Instanznamen
- Server-Instanz: `SERVER`
- Client-Instanz (vor Verbindungsaufbau): `NEWCLIENT`
- Client-Instanz (nach Verbindungsaufbau): `Client1`, ..., `ClientN` (die Zahl entspricht dem Index der verbundenen Geräte; der Instanzname wird vom Server konkateniert und als String dem anfragenden Client übermittelt)

## Konvention: Message-Type
![Bild](https://git.uni-jena.de/fusion/teaching/thesis/projektarbeit-tobias-friedrich-und-felix-staacke/docu/-/raw/main/modelledWorkflow/websocketProtocoll.png?ref_type=heads)
Der Nachrichtentyp beschreibt den Zweck der Nachricht. Er ist der zweite Bestandteil jeder übertragenen Nachricht und erlaubt die Auswertung auf Empfängerseite.

> **Änderungen** (August 2025):  
> - `NEXT_TASK` hinzugefügt (war bereits implementiert, aber nicht in der Tabelle geführt).  
> - `ASK_OVERVIEW` ersetzt `WANT_OVERVIEW` (Namensangleichung zum Code).  
> - `ASK_TASK_BY_ID` für Review-Navigation ergänzt.  
> - `SEND_TASK` enthält nun zusätzlich `answers` (Antwortoptionen) und optional `isPreview`.

| Message-Type     | Richtung | Beschreibung                                                                 |
|------------------|----------|-------------------------------------------------------------------------------|
| `AUTH`           | C→S      | Anmeldung eines Clients mit Token                                             |
| `AUTH_ERR`       | S→C      | Fehler bei der Authentifizierung                                              |
| `AUTH_OK`        | S→C      | Bestätigung erfolgreicher Authentifizierung                                   |
| `ASK_TASK`       | C→S      | **Alias** für `NEXT_TASK` (erste/nächste Aufgabe anfordern)                   |
| `NEXT_TASK`      | C→S      | Nächste Aufgabe explizit anfordern                                            |
| `ASK_TASK_BY_ID` | C→S      | Konkrete Aufgabe anhand ihrer ID anfordern (Review/Übersicht)                 |
| `ASK_OVERVIEW`   | C→S      | Übersicht anfordern (ersetzt `WANT_OVERVIEW`)                                 |
| `END_TEST`       | S→C      | Server beendet Test auf den Geräten                                           |
| `ERROR`          | S→C      | Allgemeine Fehlermeldung                                                      |
| `EXIT_ALL`       | S→C      | Server beendet auf allen Client-Geräten die Anwendung                         |
| `HAND`           | C→S      | Hand heben                                                                    |
| `MSG`            | S→C      | Nutzerdefinierte Nachricht zwischen Clients (Server leitet weiter)            |
| `SEND_ANSWER`    | C→S      | Sende Antwort für Aufgabe                                                     |
| `SEND_DELIVER`   | C→S      | Schüler gibt das Testat ab                                                    |
| `SEND_OVERVIEW`  | S→C      | Server schickt alle Daten für die Übersicht                                   |
| `SEND_TASK`      | S→C      | Sende Aufgabenstellung                                                        |
| `WARNING`        | C→S      | Warnung bei Verlassen des Fensters / verbotener Aktion                        |

> **Hinweis:** `WANT_OVERVIEW` ist **veraltet** – bitte `ASK_OVERVIEW` verwenden.

## Konvention: Arguments

Die folgende Tabelle beschreibt für jeden Message-Type die erwarteten Argumente:

| Message-Type     | Argumente                                                                                | Beschreibung der Argumente                                                                                   |
|------------------|-------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------|
| `AUTH`           | `String:token`                                                                            | Token zur Authentifizierung                                                                                   |
| `AUTH_ERR`       | `String:fehlernachricht`                                                                  | Grund der Ablehnung                                                                                           |
| `AUTH_OK`        | `String:clientId`, `int:countOfTasks`, `String:testName`                                  | Zuweisung eines Instanz-Namens, Anzahl der Aufgaben und Name des aktiven Tests                                |
| `ASK_TASK`       | *(keine)*                                                                                 | —                                                                                                             |
| `NEXT_TASK`      | *(keine)*                                                                                 | —                                                                                                             |
| `ASK_TASK_BY_ID` | `String:taskId`                                                                           | Öffnet eine bestimmte Aufgabe ohne den Aufgabenindex zu erhöhen (Review)                                      |
| `ASK_OVERVIEW`   | *(keine)*                                                                                 | —                                                                                                             |
| `END_TEST`       | *(keine)*                                                                                 | —                                                                                                             |
| `ERROR`          | `String:fehlernachricht`                                                                  | Beschreibung des Fehlers                                                                                      |
| `EXIT_ALL`       | *(keine)*                                                                                 | —                                                                                                             |
| `HAND`           | *(keine)*                                                                                 | —                                                                                                             |
| `MSG`            | `String:empfänger`, `String:nachricht`                                                    | Wenn `empfänger` nicht leer: Nachricht an **bestimmten Client**; ansonsten an **alle Clients**                |
| `SEND_ANSWER`    | `String:taskId`, `String:antwort`                                                         | Antwort eines Clients auf eine gestellte Aufgabe                                                              |
| `SEND_DELIVER`   | `String:testatId`                                                                         | Testat-ID zur Identifikation der Abgabe                                                                       |
| `SEND_OVERVIEW`  | `String:data`                                                                             | Daten für Darstellung der Übersicht                                                                           |
| `SEND_TASK`      | `String:taskId`, `int:taskNr`, `String:task`, `String:answers`, `String:isPreview` (opt.) | Neue Aufgabe; `answers` enthält Antwortoptionen separiert durch `||`; `isPreview` = `"1"` kennzeichnet Preview |
| `WARNING`        | `String:grund`, `String:zeitstempel`                                                      | Grund + ISO-Zeitstempel                                                                                       |

<details open>
<summary><div id="exemplarische-kommunikationssituationen" style="font-size: 2em; font-weight: bold;"> Exemplarische Kommunikationssituationen</div></summary>

### Beispiel: Neuer Verbindungsaufbau erfolgreich
| Absender    | Message-Type | Argumente                                           | Bedeutung                                                                            |
| ----------- | ------------ | --------------------------------------------------- | ------------------------------------------------------------------------------------ |
| `NEWCLIENT` | `AUTH`       | `String:meinToken123`                               | Client meldet sich beim Server mit einem Token an                                    |
| `SERVER`    | `AUTH_OK`    | `String:Client3`, `int:5`, `String:Mathe-Test 1`   | Server akzeptiert Token, weist Instanznamen zu und teilt Aufgabenanzahl + Testnamen mit |

### Beispiel: Neuer Verbindungsaufbau nicht erfolgreich
| Absender    | Message-Type | Argumente                 | Bedeutung                                              |
| ----------- | ------------ | ------------------------- | ------------------------------------------------------ |
| `NEWCLIENT` | `AUTH`       | `String:falscherToken456` | Client versucht sich mit ungültigem Token zu verbinden |
| `SERVER`    | `AUTH_ERR`   | `String:Ungültiger Token` | Server lehnt Verbindung ab                             |

### Beispiel: Aufgabenantwort übermitteln und nächste Aufgabe erhalten
| Absender  | Message-Type  | Argumente                                        | Bedeutung                                                   |
| --------- | ------------- | ------------------------------------------------ | ----------------------------------------------------------- |
| `Client3` | `SEND_ANSWER` | `String:task1`, `String:Antwort A`               | Client übermittelt seine Antwort zur Aufgabe mit ID `task1` |
| `Client3` | `NEXT_TASK`   | *(keine)*                                        | Client fordert aktiv die nächste Aufgabe an                 |
| `SERVER`  | `SEND_TASK`   | `String:task2`, `int:2`, `String:Beschreibe ...`, `String:answers`, `String:isPreview` (opt.) | Server sendet neue Aufgabe mit ID, Nummer, Beschreibung und Antwortoptionen |

### Beispiel: Schüler möchte Aufgaben abgeben
| Absender  | Message-Type     | Argumente          | Bedeutung                                             |
| --------- | ---------------- | ------------------ | ----------------------------------------------------- |
| `Client3` | `ASK_OVERVIEW`   | *(keine)*          | Schüler fordert Übersicht zur Kontrolle vor Abgabe an |
| `SERVER`  | `SEND_OVERVIEW`  | `String:data`      | Server schickt Übersichtsdaten zurück                 |
| `Client3` | `SEND_DELIVER`   | `String:testatId1` | Schüler gibt das Testat mit zugehöriger ID final ab   |

### Beispiel: Schüler verlässt das Fenster
| Absender  | Message-Type | Argumente                                           | Bedeutung                                                |
| --------- | ------------ | --------------------------------------------------- | -------------------------------------------------------- |
| `Client3` | `WARNING`    | `String:WindowsTaste`, `String:2025-07-26T10:12:30` | Schüler verlässt das Fenster; Server/Lehrer wird gewarnt |

### Beispiel: Zeit abgelaufen – Test wird bei allen beendet
| Absender | Message-Type | Argumente | Bedeutung                                                      |
| -------- | ------------ | --------- | -------------------------------------------------------------- |
| `SERVER` | `END_TEST`   | *(keine)* | Die Zeit ist abgelaufen – alle Testverbindungen werden beendet |

</details>

## Template Implementierung mit API:
Eine Socket‑Kommunikation ist eine direkte, bidirektionale Verbindung zwischen zwei Programmen (Client ↔ Server) über TCP. Statt rohe Zeichenketten quer durch den Code zu schieben, kapselt eine kleine API‑Klasse Message jede Nachricht in ein einheitliches Format (z. B. sender, type, args). So kann man Nachrichten sauber (de)serialisieren, prüfen und im Code eindeutig verarbeiten.

**Message.java**<br />
```
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class Message {

    public enum Type {
        AUTH, AUTH_OK, AUTH_ERR,
        MSG,
        NEXT_TASK, SEND_ANSWER,
        ERROR
    }

    private final String sender;     // z.B. "Client1" oder "SERVER"
    private final Type type;         // z.B. Type.MSG
    private final List<String> args; // vereinfachte Argumente als Strings

    public Message(String sender, Type type, List<String> args) {
        this.sender = sender;
        this.type = type;
        this.args = (args != null) ? args : new ArrayList<>();
    }

    public String getSender() { return sender; }
    public Type getType() { return type; }
    public List<String> getArgs() { return args; }

    /** Sehr einfache Serialisierung: sender, type, argCount, dann arg[i] als UTF */
    public void writeTo(DataOutputStream out) throws IOException {
        out.writeUTF(sender);
        out.writeUTF(type.name());
        out.writeInt(args.size());
        for (String a : args) {
            out.writeUTF(a != null ? a : "");
        }
        out.flush();
    }

    public static Message readFrom(DataInputStream in) throws IOException {
        String sender = in.readUTF();
        Type type = Type.valueOf(in.readUTF());
        int n = in.readInt();
        List<String> args = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            args.add(in.readUTF());
        }
        return new Message(sender, type, args);
    }

    @Override
    public String toString() {
        return "Message{sender='" + sender + "', type=" + type + ", args=" + args + "}";
    }

    // kleine Convenience-Factory
    public static Message of(String sender, Type type, String... args) {
        List<String> list = new ArrayList<>();
        if (args != null) for (String a : args) list.add(a);
        return new Message(sender, type, list);
    }
}
```
**Server.java**<br />
```
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.Executors;

public class Server {

    private final int port;

    public Server(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        var pool = Executors.newCachedThreadPool();
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("[SERVER] Listening on port " + port + " …");
            while (true) {
                Socket socket = serverSocket.accept();
                pool.submit(() -> handleClient(socket));
            }
        }
    }

    private void handleClient(Socket socket) {
        String remote = socket.getRemoteSocketAddress().toString();
        System.out.println("[SERVER] Connected: " + remote);
        try (socket;
             DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {

            // Einfache Auth‑Handshake-Demo
            Message first = Message.readFrom(in);
            if (first.getType() == Message.Type.AUTH && !first.getArgs().isEmpty()) {
                String token = first.getArgs().get(0);
                if (isValidToken(token)) {
                    Message.of("SERVER", Message.Type.AUTH_OK, "Client1", "5").writeTo(out);
                } else {
                    Message.of("SERVER", Message.Type.AUTH_ERR, "Ungültiger Token").writeTo(out);
                    return;
                }
            } else {
                Message.of("SERVER", Message.Type.ERROR, "Erwarte AUTH").writeTo(out);
                return;
            }
            out.flush();

            // Danach: einfache Echo-/Demo-Schleife
            while (true) {
                Message msg = Message.readFrom(in);
                System.out.println("[SERVER] " + msg);
                switch (msg.getType()) {
                    case MSG -> {
                        String payload = msg.getArgs().isEmpty() ? "" : msg.getArgs().get(0);
                        // Echo an den Absender zurück
                        Message.of("SERVER", Message.Type.MSG, "Echo: " + payload).writeTo(out);
                        out.flush();
                    }
                    case NEXT_TASK -> {
                        // Demo: schicke pseudo Aufgabe zurück
                        Message.of("SERVER", Message.Type.MSG, "Neue Aufgabe: Beschreibe ...").writeTo(out);
                        out.flush();
                    }
                    default -> {
                        // nichts
                    }
                }
            }
        } catch (EOFException e) {
            System.out.println("[SERVER] Client disconnected: " + remote);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private boolean isValidToken(String token) {
        // Minimal-Logik fürs Template
        return token != null && !token.isBlank();
    }

    public static void main(String[] args) throws IOException {
        new Server(5050).start();
    }
}
```

**Client.java**<br />
```
import java.io.*;
import java.net.Socket;

public class Client {

    private final String host;
    private final int port;
    private final String name;

    public Client(String host, int port, String name) {
        this.host = host;
        this.port = port;
        this.name = name;
    }

    public void run() throws IOException {
        try (Socket socket = new Socket(host, port);
             DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {

            // 1) AUTH
            Message.of(name, Message.Type.AUTH, "meinToken123").writeTo(out);
            out.flush();

            Message authReply = Message.readFrom(in);
            System.out.println("[CLIENT] AUTH Reply: " + authReply);

            if (authReply.getType() == Message.Type.AUTH_ERR) return;

            // 2) MSG senden
            Message.of(name, Message.Type.MSG, "Hallo Server!").writeTo(out);
            out.flush();

            // 3) Antwort lesen
            Message reply = Message.readFrom(in);
            System.out.println("[CLIENT] Reply: " + reply);

            // 4) Nächste Aufgabe anfordern
            Message.of(name, Message.Type.NEXT_TASK).writeTo(out);
            out.flush();
            Message task = Message.readFrom(in);
            System.out.println("[CLIENT] Task: " + task);
        }
    }

    public static void main(String[] args) throws IOException {
        new Client("127.0.0.1", 5050, "NEWCLIENT").run();
    }
}

```

## Template Implementierung ohne API:
Hier ein Template, welches ich in FPP erstellt hatte, angepasst für dieses Projekt. Dabei sind die Kommentare mit einem TODO zu ersetzen
```
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class App {

    /** TODO: einen aufrufenden Kontext definieren
    * darin enthalten:
    * DataInputStream in = new DataInputStream(socket.getInputStream());
    * App.readMessage(in); **/

    public static void readMessage(DataInputStream in) throws IOException {
        // 1. Instanzname und Nachrichtentyp lesen
        String sender = in.readUTF();        // z. B. "Client1"
        String messageType = in.readUTF();   // z. B. "SEND_ANSWER"

        // 2. Anzahl der Argumente lesen
        int argCount = in.readInt();

        // 3. Argumente dynamisch einlesen
        List<Object> args = new ArrayList<>();
        for (int i = 0; i < argCount; i++) {
            String type = in.readUTF(); // z. B. "int", "String", etc.
            Object value = switch (type) {
                case "int" -> in.readInt();
                case "float" -> in.readFloat();
                case "double" -> in.readDouble();
                case "boolean" -> in.readBoolean();
                case "String" -> in.readUTF();
                default -> throw new IOException("Unbekannter Argumenttyp: " + type);
            };
            args.add(value);
        }

        // TODO: 4. Hier erfolgt die weitere Auswertung der Nachricht
        // Beispiel: if (messageType.equals("SEND_ANSWER")) { ... }

        // TODO: die folgenden Zeilen ersetzen durch einen Aufruf von dem LogFileHandler
        System.out.println("Empfangen von " + sender + ": " + messageType + " mit " + argCount + " Argument(en)");
        for (int i = 0; i < args.size(); i++) {
            System.out.println("  Argument " + (i + 1) + ": " + args.get(i));
        }
    }
}
```


</div>