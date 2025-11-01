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

    private final String sender;
    private final Type type;
    private final List<String> args;

    public Message(String sender, Type type, List<String> args) {
        this.sender = sender;
        this.type = type;
        this.args = (args != null) ? args : new ArrayList<>();
    }

    public String getSender() { return sender; }
    public Type getType() { return type; }
    public List<String> getArgs() { return args; }

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

    public static Message of(String sender, Type type, String... args) {
        List<String> list = new ArrayList<>();
        if (args != null) for (String a : args) list.add(a);
        return new Message(sender, type, list);
    }

    @Override
    public String toString() {
        return "Message{sender='" + sender + "', type=" + type + ", args=" + args + "}";
    }
}
