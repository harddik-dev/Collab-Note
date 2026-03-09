import javax.swing.*;
import javax.swing.event.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;

/**
 * NotepadClient.java
 * ==================
 * The full Swing GUI client for the Collaborative Notepad.
 *
 * Key Features:
 *  - Connects to NotepadServer via TCP socket.
 *  - Detects local edits using a DocumentListener.
 *  - ANTI-FEEDBACK LOOP: Uses a boolean flag `isRemoteUpdate` to distinguish
 *    between text changes caused by remote (server) updates vs. local user edits.
 *    When `isRemoteUpdate` is true, the DocumentListener does NOT send the change
 *    back to the server, breaking the infinite echo loop.
 *  - CARET PRESERVATION: When applying a remote update, saves the current caret
 *    (cursor) position, applies the text change, then restores the caret. This
 *    prevents the jarring "cursor jumps to position 0" problem.
 *  - Runs a background listener thread so the GUI never freezes.
 *  - Green/Red status indicator for connection state.
 *  - Save button to write document to a local .txt file.
 */
public class NotepadClient extends JFrame {

    // ── UI Components ──────────────────────────────────────────────────────────
    private JTextArea textArea;
    private JTextField ipField;
    private JTextField portField;
    private JButton connectButton;
    private JButton saveButton;
    private JLabel statusLight;    // The colored dot
    private JLabel statusLabel;    // "Connected" / "Offline" text

    // ── Networking ─────────────────────────────────────────────────────────────
    private Socket socket;
    private PrintWriter out;
    private Thread listenerThread;

    // ── Anti-Feedback-Loop Flag ────────────────────────────────────────────────
    /**
     * THE KEY TO PREVENTING INFINITE LOOPS.
     *
     * Problem without this flag:
     *   1. Server sends update → client sets text → DocumentListener fires →
     *      client sends text back → server broadcasts → client sets text again → ∞
     *
     * Solution:
     *   When we are about to apply a REMOTE update to the JTextArea, we set
     *   isRemoteUpdate = true BEFORE making the change.
     *   The DocumentListener checks this flag: if true, it skips sending to the server.
     *   We set it back to false AFTER the change is applied.
     *
     * This is safe because all UI operations happen on the Swing Event Dispatch Thread (EDT),
     * so there's no race condition between setting the flag and the listener firing.
     */
    private boolean isRemoteUpdate = false;

    // ── Constructor ────────────────────────────────────────────────────────────
    public NotepadClient() {
        super("Collaborative Notepad");
        buildUI();
        attachDocumentListener();
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(800, 600);
        setLocationRelativeTo(null); // Center on screen
        setVisible(true);
    }

    // ── UI Construction ────────────────────────────────────────────────────────

    private void buildUI() {
        setLayout(new BorderLayout(5, 5));

        // ── Top Panel: Connection controls + Status indicator ──────────────────
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        topPanel.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        topPanel.setBackground(new Color(45, 45, 48));

        // Status light (circle label)
        statusLight = new JLabel("●");
        statusLight.setFont(new Font("Dialog", Font.PLAIN, 22));
        statusLight.setForeground(Color.RED); // Offline by default

        statusLabel = new JLabel("Offline");
        statusLabel.setForeground(Color.LIGHT_GRAY);
        statusLabel.setFont(new Font("SansSerif", Font.BOLD, 13));

        // IP / Port fields
        JLabel ipLabel = makeLabel("Server IP:");
        ipField = new JTextField("127.0.0.1", 12);

        JLabel portLabel = makeLabel("Port:");
        portField = new JTextField("9090", 5);

        connectButton = new JButton("Connect");
        connectButton.setBackground(new Color(0, 122, 204));
        connectButton.setForeground(Color.WHITE);
        connectButton.setFocusPainted(false);
        connectButton.addActionListener(e -> handleConnect());

        saveButton = new JButton("💾 Save");
        saveButton.setEnabled(false);
        saveButton.addActionListener(e -> saveToFile());

        topPanel.add(statusLight);
        topPanel.add(statusLabel);
        topPanel.add(Box.createHorizontalStrut(15));
        topPanel.add(ipLabel);
        topPanel.add(ipField);
        topPanel.add(portLabel);
        topPanel.add(portField);
        topPanel.add(connectButton);
        topPanel.add(Box.createHorizontalStrut(10));
        topPanel.add(saveButton);

        add(topPanel, BorderLayout.NORTH);

        // ── Center Panel: The main text editor ────────────────────────────────
        textArea = new JTextArea();
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 14));
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setBackground(new Color(30, 30, 30));
        textArea.setForeground(new Color(212, 212, 212));
        textArea.setCaretColor(Color.WHITE);
        textArea.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        textArea.setEnabled(false); // Disabled until connected

        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setBorder(BorderFactory.createLineBorder(new Color(63, 63, 70)));
        add(scrollPane, BorderLayout.CENTER);

        // ── Status Bar: Bottom ─────────────────────────────────────────────────
        JLabel hint = new JLabel("  Connect to a server to start collaborating.");
        hint.setForeground(Color.GRAY);
        hint.setFont(new Font("SansSerif", Font.ITALIC, 11));
        hint.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        add(hint, BorderLayout.SOUTH);
    }

    private JLabel makeLabel(String text) {
        JLabel lbl = new JLabel(text);
        lbl.setForeground(Color.LIGHT_GRAY);
        return lbl;
    }

    // ── DocumentListener: Detects Local Edits ─────────────────────────────────

    /**
     * Attaches a DocumentListener to the JTextArea.
     *
     * HOW THE FEEDBACK LOOP IS PREVENTED:
     * ------------------------------------
     * Every keystroke fires changedUpdate/insertUpdate/removeUpdate.
     * Before we send to the server, we check `isRemoteUpdate`.
     *
     *   isRemoteUpdate == false → change came from the USER → send to server ✓
     *   isRemoteUpdate == true  → change came from SERVER  → skip send      ✓
     *
     * Without this guard, the cycle would be:
     *   User types → send to server → server echoes back → listener fires again
     *   → send to server → server echoes back → ... (infinite loop)
     */
    private void attachDocumentListener() {
        textArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { onLocalChange(); }
            @Override public void removeUpdate(DocumentEvent e)  { onLocalChange(); }
            @Override public void changedUpdate(DocumentEvent e) { /* Style changes, not relevant */ }
        });
    }

    /**
     * Called whenever the document text changes.
     * Only sends to the server if the change was made by the LOCAL user.
     */
    private void onLocalChange() {
        // ── THE FEEDBACK LOOP GUARD ──────────────────────────────────────────
        // If this change was triggered by applyRemoteUpdate(), do NOT send it
        // back to the server. This is the single line that prevents the loop.
        if (isRemoteUpdate) return;

        // If not connected, nothing to send
        if (out == null) return;

        // Send the full current text to the server
        String currentText = textArea.getText();
        sendToServer(currentText);
    }

    // ── Network: Connect / Disconnect ─────────────────────────────────────────

    private void handleConnect() {
        if (socket != null && !socket.isClosed()) {
            disconnectFromServer();
            return;
        }

        String ip = ipField.getText().trim();
        String portStr = portField.getText().trim();

        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Invalid port number.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Connect in a background thread to avoid freezing the GUI
        new Thread(() -> connectToServer(ip, port)).start();
    }

    private void connectToServer(String ip, int port) {
        try {
            socket = new Socket(ip, port);
            out = new PrintWriter(
                    new BufferedWriter(new OutputStreamWriter(socket.getOutputStream())), true);

            // Update UI on the Event Dispatch Thread
            SwingUtilities.invokeLater(() -> {
                setConnectedState(true);
                connectButton.setText("Disconnect");
            });

            // Start background thread to receive server updates
            startListenerThread();

        } catch (IOException e) {
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(
                        NotepadClient.this,
                        "Could not connect to server at " + ip + ":" + port + "\n" +
                        "Make sure the server is running.\n\nError: " + e.getMessage(),
                        "Connection Failed",
                        JOptionPane.ERROR_MESSAGE);
                setConnectedState(false);
            });
        }
    }

    private void disconnectFromServer() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {}
        socket = null;
        out = null;
        SwingUtilities.invokeLater(() -> {
            setConnectedState(false);
            connectButton.setText("Connect");
        });
    }

    // ── Network: Background Listener Thread ───────────────────────────────────

    /**
     * Starts a background thread that continuously reads lines from the server.
     * This keeps the Swing GUI thread free and responsive.
     */
    private void startListenerThread() {
        listenerThread = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream()))) {

                String line;
                while ((line = in.readLine()) != null) {
                    final String decoded = decode(line);
                    // Apply the received text on the Swing EDT
                    SwingUtilities.invokeLater(() -> applyRemoteUpdate(decoded));
                }

            } catch (IOException e) {
                // Connection dropped
                SwingUtilities.invokeLater(() -> {
                    if (socket != null && !socket.isClosed()) {
                        JOptionPane.showMessageDialog(
                                NotepadClient.this,
                                "Lost connection to the server.\nThe document is now in read-only mode.",
                                "Connection Lost",
                                JOptionPane.WARNING_MESSAGE);
                    }
                    disconnectFromServer();
                });
            }
        });
        listenerThread.setDaemon(true); // Dies when app closes
        listenerThread.start();
    }

    // ── Remote Update Application: The Caret-Jump Fix ─────────────────────────

    /**
     * Applies a text update received from the server to the JTextArea.
     *
     * HOW THE CARET (CURSOR) JUMP IS PREVENTED:
     * -------------------------------------------
     * When we call textArea.setText(newText), Swing resets the caret to position 0.
     * This would cause the user's cursor to jump to the top of the document
     * every time anyone else types a letter — extremely annoying!
     *
     * The fix is a 3-step process:
     *   1. SAVE   — Record the current caret position before changing text.
     *   2. CHANGE — Set the flag, update the text, clear the flag.
     *   3. RESTORE — Put the caret back to where it was (clamped to valid range).
     *
     * The isRemoteUpdate flag ensures DocumentListener doesn't re-send this
     * change back to the server (feedback loop prevention).
     */
    private void applyRemoteUpdate(String newText) {
        // ── Step 1: Save current caret position ──────────────────────────────
        int caretPosition = textArea.getCaretPosition();

        // ── Step 2: Set flag → update text → clear flag ───────────────────────
        isRemoteUpdate = true;   // Tell DocumentListener: "this is NOT a user edit"
        try {
            textArea.setText(newText);
        } finally {
            isRemoteUpdate = false; // ALWAYS clear the flag, even if setText throws
        }

        // ── Step 3: Restore caret, clamped to new text length ─────────────────
        // After setText, the document may be shorter (deletion by remote user).
        // We clamp to avoid an IndexOutOfBoundsException.
        int newLength = textArea.getDocument().getLength();
        int restoredCaret = Math.min(caretPosition, newLength);
        textArea.setCaretPosition(restoredCaret);
    }

    // ── Send to Server ────────────────────────────────────────────────────────

    private void sendToServer(String text) {
        if (out != null) {
            out.println(encode(text));
        }
    }

    // ── Text Encoding (mirrors ClientHandler's encode/decode) ─────────────────

    private String encode(String text) {
        return text.replace("\\", "\\\\").replace("\n", "\\n");
    }

    private String decode(String line) {
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

    // ── Save to File ──────────────────────────────────────────────────────────

    private void saveToFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save Document");
        chooser.setSelectedFile(new File("document.txt"));

        int result = chooser.showSaveDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            try {
                Files.writeString(file.toPath(), textArea.getText());
                JOptionPane.showMessageDialog(this,
                        "Saved to: " + file.getAbsolutePath(),
                        "Saved", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this,
                        "Failed to save file: " + e.getMessage(),
                        "Save Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    // ── UI State Helpers ──────────────────────────────────────────────────────

    private void setConnectedState(boolean connected) {
        statusLight.setForeground(connected ? new Color(50, 200, 80) : Color.RED);
        statusLabel.setText(connected ? "Connected" : "Offline");
        textArea.setEnabled(connected);
        saveButton.setEnabled(connected);
        ipField.setEnabled(!connected);
        portField.setEnabled(!connected);
        if (!connected) textArea.setText("");
    }

    // ── Entry Point ───────────────────────────────────────────────────────────

    public static void main(String[] args) {
        // Set a modern look and feel
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        // Always create Swing components on the EDT
        SwingUtilities.invokeLater(NotepadClient::new);
    }
}