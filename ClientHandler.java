import java.io.*;
import java.net.*;

/**
 * ClientHandler.java
 * ==================
 * Runs on the SERVER side. One instance per connected client.
 * Each handler runs in its own thread, listening for incoming
 * text updates from its specific client.
 *
 * Communication Protocol (simple and robust):
 *   - Every message is a single line ending with '\n'.
 *   - Newlines WITHIN the document text are encoded as the
 *     literal string "\\n" (two characters) before sending,
 *     and decoded back to '\n' upon receipt.
 *   - This keeps every network message a single line,
 *     making BufferedReader.readLine() perfectly safe to use.
 */
public class ClientHandler implements Runnable {

    private final Socket clientSocket;
    private final NotepadServer server;
    private PrintWriter out;
    private final String clientAddress;

    public ClientHandler(Socket socket, NotepadServer server) {
        this.clientSocket = socket;
        this.server = server;
        this.clientAddress = socket.getInetAddress().getHostAddress()
                + ":" + socket.getPort();
    }

    @Override
    public void run() {
        try (
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(clientSocket.getInputStream()));
        ) {
            // Initialize the output writer (kept open for the lifetime of the connection)
            out = new PrintWriter(
                    new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream())), true);

            // ── Step 1: Send the current master copy to the newly connected client ──
            // Encode newlines so the entire document travels as one line.
            String currentText = encode(server.getMasterText());
            out.println(currentText);
            System.out.println("[ClientHandler] Sent master copy to " + clientAddress);

            // ── Step 2: Listen in a loop for updates from this client ──
            String line;
            while ((line = in.readLine()) != null) {
                // Decode the escaped newlines back to real newlines
                String decodedText = decode(line);
                System.out.println("[ClientHandler] Received update from " + clientAddress);

                // Tell the server to update master copy & broadcast to others
                server.updateAndBroadcast(decodedText, this);
            }

        } catch (IOException e) {
            System.out.println("[ClientHandler] Client disconnected: " + clientAddress);
        } finally {
            // Clean up: remove from the server's active list
            server.removeClient(this);
            try { clientSocket.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Sends a text update to THIS client.
     * Called by the server during a broadcast from another client's edit.
     */
    public void sendText(String text) {
        if (out != null) {
            out.println(encode(text));
        }
    }

    /**
     * Encodes real newline characters (\n) as the two-character literal "\\n"
     * so the entire document can be sent as a single line over the socket.
     */
    private String encode(String text) {
        // Replace literal newline with escaped representation
        return text.replace("\\", "\\\\").replace("\n", "\\n");
    }

    /**
     * Reverses the encoding: turns "\\n" back into real '\n' characters.
     */
    private String decode(String line) {
        // Process character by character to correctly handle \\n vs \n
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length()) {
                char next = line.charAt(i + 1);
                if (next == 'n') {
                    sb.append('\n');
                    i += 2;
                    continue;
                } else if (next == '\\') {
                    sb.append('\\');
                    i += 2;
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    public String getClientAddress() {
        return clientAddress;
    }
}