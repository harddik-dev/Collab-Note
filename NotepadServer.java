import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * NotepadServer.java
 * ==================
 * The central coordinator for the Collaborative Notepad.
 * Maintains the "Master Copy" of the shared document.
 * Each connecting client gets its own ClientHandler thread.
 *
 * Usage: Run this first, then launch NotepadClient instances.
 */
public class NotepadServer {

    private static final int DEFAULT_PORT = 9090;

    // The single "Master Copy" of the document text.
    // volatile ensures changes are visible across all threads immediately.
    private volatile String masterText = "";

    // Thread-safe list of all currently connected client handlers.
    private final List<ClientHandler> connectedClients = new CopyOnWriteArrayList<>();

    public void start(int port) {
        System.out.println("[Server] Starting on port " + port + "...");

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("[Server] Ready. Waiting for connections...");

            // Main accept loop — runs forever, accepting new clients
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("[Server] New client connected: " + clientSocket.getInetAddress());

                // Create a handler for this client and run it in its own thread
                ClientHandler handler = new ClientHandler(clientSocket, this);
                connectedClients.add(handler);
                new Thread(handler).start();
            }
        } catch (IOException e) {
            System.err.println("[Server] Server error: " + e.getMessage());
        }
    }

    /**
     * Called by a ClientHandler when it receives new text from its client.
     * Updates the master copy and broadcasts to ALL OTHER clients.
     *
     * @param newText      The full updated text received from a client.
     * @param senderHandler The handler that sent this update (excluded from broadcast).
     */
    public synchronized void updateAndBroadcast(String newText, ClientHandler senderHandler) {
        masterText = newText; // Update master copy

        System.out.println("[Server] Broadcasting update from " +
                senderHandler.getClientAddress() + " to " +
                (connectedClients.size() - 1) + " other client(s).");

        // Send the update to every client EXCEPT the one who sent it
        for (ClientHandler client : connectedClients) {
            if (client != senderHandler) {
                client.sendText(newText);
            }
        }
    }

    /**
     * Returns the current master copy of the document.
     * Called when a new client connects so they get the latest state.
     */
    public String getMasterText() {
        return masterText;
    }

    /**
     * Removes a disconnected client from the active list.
     */
    public void removeClient(ClientHandler handler) {
        connectedClients.remove(handler);
        System.out.println("[Server] Client removed. Active clients: " + connectedClients.size());
    }

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("[Server] Invalid port argument. Using default: " + DEFAULT_PORT);
            }
        }
        new NotepadServer().start(port);
    }
}