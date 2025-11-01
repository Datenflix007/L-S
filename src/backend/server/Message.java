package backend.server;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @title Message
 * @short Transportobjekt der API: enthÃ¤lt Instanzname (sender), Message-Typ, personalisierten Token und Argumente.
 * @args String sender, String messageType, String token, Map<String,String> args
 */
public class Message {

    public final String sender;         // Instanzname (z.B. SERVER, NEWCLIENT, Client3)
    public final String messageType;    // z.B. AUTH, SEND_TASK, SEND_ANSWER ...
    public final String token;          // personalisierter Token des Absenders
    public final LinkedHashMap<String,String> args; // Argumente als Key/Value (Strings)

    /**
     * @title Konstruktor
     * @short Erstellt eine Message mit leerer Argumentliste.
     * @args String sender, String messageType, String token
     */
    public Message(String sender, String messageType, String token) {
        this.sender = sender;
        this.messageType = messageType;
        this.token = token == null ? "" : token;
        this.args = new LinkedHashMap<>();
    }

    /**
     * @title Konstruktor mit Args
     * @short Erstellt eine Message und fÃ¼llt Argumente aus einer Liste [k1,v1,k2,v2,...].
     * @args String sender, String messageType, String token, Iterable<String> kv
     */
    public Message(String sender, String messageType, String token, Iterable<String> kv) {
        this(sender, messageType, token);
        if (kv != null) {
            String key = null;
            for (String s : kv) {
                if (key == null) key = s;
                else { this.args.put(key, s == null ? "" : s); key = null; }
            }
        }
    }

    /**
     * @title put
     * @short FÃ¼gt ein Argument hinzu.
     * @args String key, String value
     */
    public Message put(String key, String value) {
        args.put(key, value == null ? "" : value);
        return this;
    }

    /**
     * @title getString
     * @short Liefert ein Argument als String.
     * @args String key
     */
    public String getString(String key) { return args.get(key); }

    /**
     * @title getInt
     * @short Liefert ein Argument als Integer (oder null).
     * @args String key
     */
    public Integer getInt(String key) {
        String value = args.get(key);
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(normalized);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * @title toSimpleString
     * @short Einzeilige Kurzform zur Protokollierung.
     * @args keine
     */
    public String toSimpleString() {
        return sender + "|" + messageType + "|" + token + "|" + args;
    }

    /**
     * @title write
     * @short Serialisiert eine Message in den Stream (sender, type, token, count, key/value*count).
     * @args DataOutputStream out, Message msg
     */
    public static void write(DataOutputStream out, Message msg) throws IOException {
        out.writeUTF(msg.sender == null ? "" : msg.sender);
        out.writeUTF(msg.messageType == null ? "" : msg.messageType);
        out.writeUTF(msg.token == null ? "" : msg.token);
        out.writeInt(msg.args.size());
        for (Map.Entry<String,String> e : msg.args.entrySet()) {
            out.writeUTF(e.getKey());
            out.writeUTF(e.getValue() == null ? "" : e.getValue());
        }
        out.flush();
    }

    /**
     * @title read
     * @short Liest eine Message aus dem Stream (Format siehe write).
     * @args DataInputStream in
     */
    public static Message read(DataInputStream in) {
        try {
            String sender = in.readUTF();
            String type   = in.readUTF();
            String token  = in.readUTF();
            int count     = in.readInt();
            Message m = new Message(sender, type, token);
            for (int i = 0; i < count; i++) {
                String k = in.readUTF();
                String v = in.readUTF();
                m.args.put(k, v);
            }
            return m;
        } catch (IOException e) {
            return null;
        }
    }
}

