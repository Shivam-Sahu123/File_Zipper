import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JSeparator;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;

/**
 * Huffman File Zipper - a simple Swing front end for FileCompressor.
 * All compression work happens in a SwingWorker (background thread),
 * so the window never freezes.
 */
public class HuffmanFileZipper extends JFrame {

    private static final Color HEADER_COLOR = new Color(40, 70, 120);
    private static final String EXTENSION = ".huf";

    private final FileCompressor compressor = new FileCompressor();

    private File selectedFile;
    private File lastDirectory;

    private final JLabel fileLabel = new JLabel("No file selected");
    private final JLabel originalLabel = new JLabel("-");
    private final JLabel compressedLabel = new JLabel("-");
    private final JLabel savedLabel = new JLabel("-");
    private final JLabel methodLabel = new JLabel("-");
    private final JLabel statusLabel = new JLabel("Status: Ready");
    private final JButton chooseButton = new JButton("Choose File");
    private final JButton compressButton = new JButton("Compress");
    private final JButton decompressButton = new JButton("Decompress");
    private final JComboBox<FileCompressor.Mode> modeBox =
            new JComboBox<>(FileCompressor.Mode.values());
    private final JProgressBar progressBar = new JProgressBar(0, 100);

    public HuffmanFileZipper() {
        super("Huffman File Zipper");
        buildUi();
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        pack();
        setSize(Math.max(getWidth(), 460), getHeight());
        setResizable(false);
        setLocationRelativeTo(null);
    }

    // =====================================================================
    //  UI construction
    // =====================================================================

    private void buildUi() {
        setLayout(new BorderLayout());

        // ---- Header (title + subtitle) ----
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setBackground(HEADER_COLOR);
        header.setBorder(BorderFactory.createEmptyBorder(15, 10, 15, 10));

        JLabel title = new JLabel("HUFFMAN FILE ZIPPER");
        title.setFont(new Font("SansSerif", Font.BOLD, 22));
        title.setForeground(Color.WHITE);
        title.setAlignmentX(CENTER_ALIGNMENT);

        JLabel subtitle = new JLabel("File Compression Tool");
        subtitle.setFont(new Font("SansSerif", Font.PLAIN, 13));
        subtitle.setForeground(Color.WHITE);
        subtitle.setAlignmentX(CENTER_ALIGNMENT);

        header.add(title);
        header.add(subtitle);
        add(header, BorderLayout.NORTH);

        // ---- Main content ----
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(15, 20, 15, 20));

        // Selected file row
        JLabel selectedTitle = new JLabel("Selected File:");
        JPanel titleRow = row(new FlowLayout(FlowLayout.LEFT, 0, 0));
        titleRow.add(selectedTitle);

        JPanel fileRow = row(new BorderLayout(10, 0));
        fileRow.add(chooseButton, BorderLayout.WEST);
        fileRow.add(fileLabel, BorderLayout.CENTER);

        // Compression method row (only used when compressing)
        modeBox.setToolTipText("Used when compressing. Decompression detects the method automatically.");
        JPanel methodRow = row(new FlowLayout(FlowLayout.LEFT, 10, 0));
        methodRow.add(new JLabel("Method:"));
        methodRow.add(modeBox);

        // Action buttons
        JPanel buttonRow = row(new FlowLayout(FlowLayout.CENTER, 20, 0));
        buttonRow.add(compressButton);
        buttonRow.add(decompressButton);

        // Statistics
        JPanel stats = row(new GridLayout(4, 2, 5, 5));
        stats.add(new JLabel("Original Size:"));
        stats.add(originalLabel);
        stats.add(new JLabel("Compressed Size:"));
        stats.add(compressedLabel);
        stats.add(new JLabel("Space Saved:"));
        stats.add(savedLabel);
        stats.add(new JLabel("Method Used:"));
        stats.add(methodLabel);

        // Progress + status
        progressBar.setStringPainted(true);
        JPanel progressRow = row(new BorderLayout());
        progressRow.add(progressBar, BorderLayout.CENTER);

        JPanel statusRow = row(new FlowLayout(FlowLayout.LEFT, 0, 0));
        statusRow.add(statusLabel);

        content.add(titleRow);
        content.add(spacer(5));
        content.add(fileRow);
        content.add(spacer(12));
        content.add(methodRow);
        content.add(spacer(12));
        content.add(buttonRow);
        content.add(spacer(15));
        content.add(new JSeparator(SwingConstants.HORIZONTAL));
        content.add(spacer(10));
        content.add(stats);
        content.add(spacer(15));
        content.add(progressRow);
        content.add(spacer(10));
        content.add(statusRow);
        add(content, BorderLayout.CENTER);

        // ---- Button actions ----
        chooseButton.addActionListener(e -> chooseFile());
        compressButton.addActionListener(e -> startCompression());
        decompressButton.addActionListener(e -> startDecompression());
    }

    /** Creates a left-aligned row panel with the given layout (keeps BoxLayout tidy). */
    private JPanel row(java.awt.LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        return panel;
    }

    private JPanel spacer(int height) {
        JPanel p = new JPanel();
        p.setPreferredSize(new java.awt.Dimension(1, height));
        p.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, height));
        p.setAlignmentX(LEFT_ALIGNMENT);
        return p;
    }

    // =====================================================================
    //  Button handlers
    // =====================================================================

    /** Lets the user pick the input file. */
    private void chooseFile() {
        JFileChooser chooser = new JFileChooser(lastDirectory);
        chooser.setDialogTitle("Choose a file");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            selectedFile = chooser.getSelectedFile();
            lastDirectory = chooser.getCurrentDirectory();
            fileLabel.setText(selectedFile.getName());
            fileLabel.setToolTipText(selectedFile.getAbsolutePath());
            resetDisplay();
            statusLabel.setText("Status: File selected");
        }
    }

    private void startCompression() {
        if (!validateInput()) {
            return;
        }
        File output = chooseOutput("Save Compressed File", selectedFile.getName() + EXTENSION);
        if (output != null) {
            runTask(true, selectedFile, output, (FileCompressor.Mode) modeBox.getSelectedItem());
        }
    }

    private void startDecompression() {
        if (!validateInput()) {
            return;
        }
        String name = selectedFile.getName();
        String suggested = name.toLowerCase().endsWith(EXTENSION)
                ? name.substring(0, name.length() - EXTENSION.length())
                : name + ".decoded";
        if (suggested.isEmpty()) {
            suggested = "decoded_file";
        }
        File output = chooseOutput("Save Decompressed File", suggested);
        if (output != null) {
            runTask(false, selectedFile, output, null);
        }
    }

    // =====================================================================
    //  Validation and file dialogs
    // =====================================================================

    /** Checks that a readable, regular file has been selected. */
    private boolean validateInput() {
        if (selectedFile == null) {
            warn("Please choose a file first.");
            return false;
        }
        if (!selectedFile.exists()) {
            warn("The selected file no longer exists:\n" + selectedFile.getAbsolutePath());
            return false;
        }
        if (!selectedFile.isFile()) {
            warn("The selection is not a regular file.");
            return false;
        }
        if (!selectedFile.canRead()) {
            warn("The selected file cannot be read (permission denied).");
            return false;
        }
        return true;
    }

    /**
     * Shows a "Save" dialog for the output file.
     * Returns null if the user cancels or picks an unsafe target.
     */
    private File chooseOutput(String dialogTitle, String suggestedName) {
        File dir = (lastDirectory != null) ? lastDirectory : selectedFile.getParentFile();
        JFileChooser chooser = new JFileChooser(dir);
        chooser.setDialogTitle(dialogTitle);
        chooser.setSelectedFile(new File(dir, suggestedName));

        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        File output = chooser.getSelectedFile();
        lastDirectory = chooser.getCurrentDirectory();

        if (sameFile(output, selectedFile)) {
            warn("The output file must be different from the input file.");
            return null;
        }
        if (output.exists()) {
            int choice = JOptionPane.showConfirmDialog(this,
                    output.getName() + " already exists.\nDo you want to replace it?",
                    "Confirm Overwrite", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) {
                return null;
            }
        }
        return output;
    }

    private boolean sameFile(File a, File b) {
        try {
            return a.getCanonicalFile().equals(b.getCanonicalFile());
        } catch (IOException e) {
            return a.getAbsoluteFile().equals(b.getAbsoluteFile());
        }
    }

    // =====================================================================
    //  Background work
    // =====================================================================

    /**
     * Runs compression or decompression in a SwingWorker so the UI stays responsive.
     * The worker returns the method that was used (Huffman, LZ77 + Huffman or stored).
     */
    private void runTask(final boolean compress, final File input, final File output,
                         final FileCompressor.Mode mode) {
        setBusy(true);
        resetDisplay();
        statusLabel.setText(compress ? "Status: Compressing..." : "Status: Decompressing...");

        SwingWorker<Integer, Void> worker = new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() throws Exception {
                // setProgress() is thread-safe; the bar is updated on the UI thread below.
                ProgressListener listener = percent -> setProgress(percent);
                if (compress) {
                    return compressor.compress(input, output, mode, listener);
                }
                return compressor.decompress(input, output, listener);
            }

            @Override
            protected void done() {
                setBusy(false);
                try {
                    int method = get();                        // rethrows any exception from the background thread
                    progressBar.setValue(100);
                    long compressedSize = compress ? output.length() : input.length();
                    long originalSize = compress ? input.length() : output.length();
                    showStats(originalSize, compressedSize);
                    methodLabel.setText(FileCompressor.methodName(method));
                    statusLabel.setText(compress ? "Status: Compression complete"
                                                 : "Status: Decompression complete");

                    String message = (compress ? "File compressed successfully.\n"
                                               : "File decompressed successfully.\n")
                            + "Saved to: " + output.getAbsolutePath();
                    if (compress && method == FileCompressor.METHOD_STORED) {
                        message += "\n\nThis file could not be made smaller (it is probably already\n"
                                + "compressed, like a PDF, JPG or ZIP), so it was stored as-is.";
                    }
                    JOptionPane.showMessageDialog(HuffmanFileZipper.this,
                            message, "Done", JOptionPane.INFORMATION_MESSAGE);
                } catch (ExecutionException e) {
                    output.delete();                           // remove the incomplete output file
                    progressBar.setValue(0);
                    statusLabel.setText("Status: Failed");
                    Throwable cause = (e.getCause() != null) ? e.getCause() : e;
                    JOptionPane.showMessageDialog(HuffmanFileZipper.this,
                            "The operation failed:\n" + cause.getMessage(),
                            "Error", JOptionPane.ERROR_MESSAGE);
                } catch (InterruptedException e) {
                    output.delete();
                    Thread.currentThread().interrupt();
                    statusLabel.setText("Status: Interrupted");
                }
            }
        };

        // Update the progress bar whenever the worker's "progress" property changes.
        worker.addPropertyChangeListener(evt -> {
            if ("progress".equals(evt.getPropertyName())) {
                progressBar.setValue((Integer) evt.getNewValue());
            }
        });
        worker.execute();
    }

    // =====================================================================
    //  Display helpers
    // =====================================================================

    private void setBusy(boolean busy) {
        chooseButton.setEnabled(!busy);
        compressButton.setEnabled(!busy);
        decompressButton.setEnabled(!busy);
        modeBox.setEnabled(!busy);
    }

    private void resetDisplay() {
        originalLabel.setText("-");
        compressedLabel.setText("-");
        savedLabel.setText("-");
        methodLabel.setText("-");
        progressBar.setValue(0);
    }

    private void showStats(long originalSize, long compressedSize) {
        originalLabel.setText(formatSize(originalSize));
        compressedLabel.setText(formatSize(compressedSize));
        if (originalSize == 0) {
            savedLabel.setText("N/A (empty file)");
            return;
        }
        double ratio = (double) compressedSize / originalSize;
        double saved = (1.0 - ratio) * 100.0;
        String text = String.format("%.2f%%  (ratio %.3f)", saved, ratio);
        if (saved < 0) {
            text += "  - file grew";
        }
        savedLabel.setText(text);
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) {
            return String.format("%,d bytes", bytes);
        }
        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format("%,d bytes (%.2f %s)", bytes, value, units[unit]);
    }

    private void warn(String message) {
        JOptionPane.showMessageDialog(this, message, "Warning", JOptionPane.WARNING_MESSAGE);
    }

    // =====================================================================
    //  Entry point
    // =====================================================================

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // fall back to the default look and feel
        }
        SwingUtilities.invokeLater(() -> new HuffmanFileZipper().setVisible(true));
    }
}
