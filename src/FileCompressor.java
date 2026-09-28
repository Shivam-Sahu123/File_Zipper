import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Ties the compression stages together and defines the .huf file format.
 * Contains NO Swing code.
 *
 * COMPRESSED FILE FORMAT (version 2)
 * ----------------------------------
 *   4 bytes : magic number "HUF2"
 *   1 byte  : method  (0 = stored, 1 = Huffman, 2 = LZ77 + Huffman)
 *   8 bytes : original file length (long)
 *   payload :
 *       stored         -> the original bytes, unchanged
 *       Huffman        -> one Huffman block of the original bytes
 *       LZ77+Huffman   -> one Huffman block of the LZ77 token stream
 *
 * Files written by the first version of this program ("HUF1" = magic + one
 * Huffman block) can still be decompressed.
 *
 * MODES
 * -----
 *   HUFFMAN_ONLY  : always Huffman.
 *   LZ77_HUFFMAN  : always LZ77 first, then Huffman.
 *   AUTO          : tries Huffman and LZ77+Huffman, then keeps the smallest of
 *                   those two and "stored". So a file never grows by more than
 *                   the 13-byte header, even if it is already compressed.
 */
public class FileCompressor {

    /** What the user can choose in the UI. */
    public enum Mode {
        AUTO("Auto (pick smallest)"),
        HUFFMAN_ONLY("Huffman only"),
        LZ77_HUFFMAN("LZ77 + Huffman");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static final int METHOD_STORED = 0;
    public static final int METHOD_HUFFMAN = 1;
    public static final int METHOD_LZ77_HUFFMAN = 2;

    private static final int MAGIC_V1 = 0x48554631;          // "HUF1"
    private static final int MAGIC_V2 = 0x48554632;          // "HUF2"
    private static final int HEADER_SIZE = 4 + 1 + 8;
    private static final int BUFFER_SIZE = 64 * 1024;

    private final HuffmanCompressor huffman = new HuffmanCompressor();
    private final LZ77Codec lz77 = new LZ77Codec();

    /** Human readable name of a method code, for the UI. */
    public static String methodName(int method) {
        switch (method) {
            case METHOD_STORED:
                return "Stored (no compression)";
            case METHOD_HUFFMAN:
                return "Huffman";
            case METHOD_LZ77_HUFFMAN:
                return "LZ77 + Huffman";
            default:
                return "Unknown";
        }
    }

    // =====================================================================
    //  COMPRESSION
    // =====================================================================

    /**
     * Compresses 'input' into 'output'.
     *
     * @return the method that was actually used (one of the METHOD_ constants)
     */
    public int compress(File input, File output, Mode mode, ProgressListener listener)
            throws IOException {
        switch (mode) {
            case HUFFMAN_ONLY:
                writeHuffmanContainer(output, METHOD_HUFFMAN, input.length(), input, listener, 0, 100);
                return METHOD_HUFFMAN;

            case LZ77_HUFFMAN: {
                File tokens = tempFile();
                try {
                    lz77.encode(input, tokens, listener, 0, 40);
                    writeHuffmanContainer(output, METHOD_LZ77_HUFFMAN, input.length(),
                            tokens, listener, 40, 100);
                } finally {
                    tokens.delete();
                }
                return METHOD_LZ77_HUFFMAN;
            }

            default:
                return compressAuto(input, output, listener);
        }
    }

    /** Builds every candidate in temporary files and keeps the smallest one. */
    private int compressAuto(File input, File output, ProgressListener listener) throws IOException {
        long originalLength = input.length();
        File huffmanFile = tempFile();
        File tokens = tempFile();
        File lzHuffmanFile = tempFile();
        try {
            // Candidate 1: Huffman only.
            writeHuffmanContainer(huffmanFile, METHOD_HUFFMAN, originalLength, input, listener, 0, 30);
            // Candidate 2: LZ77 followed by Huffman.
            lz77.encode(input, tokens, listener, 30, 60);
            writeHuffmanContainer(lzHuffmanFile, METHOD_LZ77_HUFFMAN, originalLength,
                    tokens, listener, 60, 95);

            // Candidate 3 is "stored": header + the original bytes.
            int bestMethod = METHOD_STORED;
            long bestSize = HEADER_SIZE + originalLength;
            File best = null;
            if (huffmanFile.length() < bestSize) {
                bestMethod = METHOD_HUFFMAN;
                bestSize = huffmanFile.length();
                best = huffmanFile;
            }
            if (lzHuffmanFile.length() < bestSize) {
                bestMethod = METHOD_LZ77_HUFFMAN;
                best = lzHuffmanFile;
            }

            if (best == null) {
                writeStored(input, output, listener, 95, 100);
            } else {
                Files.copy(best.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            if (listener != null) {
                listener.onProgress(100);
            }
            return bestMethod;
        } finally {
            huffmanFile.delete();
            tokens.delete();
            lzHuffmanFile.delete();
        }
    }

    /** Writes header + a Huffman block of 'source' (the file or its LZ77 tokens). */
    private void writeHuffmanContainer(File dest, int method, long originalLength, File source,
                                       ProgressListener listener, int from, int to) throws IOException {
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(dest), BUFFER_SIZE)) {
            writeHeader(out, method, originalLength);
            huffman.encode(source, out, listener, from, to);
        }
    }

    /** Writes header + the original bytes unchanged. */
    private void writeStored(File input, File dest, ProgressListener listener,
                             int from, int to) throws IOException {
        Progress progress = new Progress(listener);
        long length = input.length();
        try (InputStream in = new FileInputStream(input);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(dest), BUFFER_SIZE)) {
            writeHeader(out, METHOD_STORED, length);
            byte[] buffer = new byte[BUFFER_SIZE];
            long done = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                done += n;
                progress.report(done, length, from, to);
            }
        }
    }

    private void writeHeader(OutputStream out, int method, long originalLength) throws IOException {
        DataOutputStream header = new DataOutputStream(out);
        header.writeInt(MAGIC_V2);
        header.writeByte(method);
        header.writeLong(originalLength);
    }

    // =====================================================================
    //  DECOMPRESSION
    // =====================================================================

    /**
     * Restores the original file. The method is read from the file header,
     * so the user does not have to choose anything.
     *
     * @return the method the file had been compressed with
     */
    public int decompress(File input, File output, ProgressListener listener) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(input), BUFFER_SIZE)) {
            DataInputStream header = new DataInputStream(in);
            int magic = header.readInt();

            if (magic == MAGIC_V1) {                         // old files: magic + Huffman block
                try (OutputStream out = new BufferedOutputStream(new FileOutputStream(output), BUFFER_SIZE)) {
                    huffman.decode(in, out, listener, 0, 100);
                }
                return METHOD_HUFFMAN;
            }
            if (magic != MAGIC_V2) {
                throw new IOException("This is not a file created by Huffman File Zipper.");
            }

            int method = header.readUnsignedByte();
            long originalLength = header.readLong();
            if (originalLength < 0) {
                throw new IOException("The file header is corrupt.");
            }

            switch (method) {
                case METHOD_STORED:
                    copyExactly(in, output, originalLength, listener);
                    break;

                case METHOD_HUFFMAN:
                    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(output), BUFFER_SIZE)) {
                        long decoded = huffman.decode(in, out, listener, 0, 100);
                        if (decoded != originalLength) {
                            throw new IOException("The compressed file is corrupt (length mismatch).");
                        }
                    }
                    break;

                case METHOD_LZ77_HUFFMAN: {
                    // Step 1: undo Huffman -> LZ77 token stream (temporary file).
                    // Step 2: undo LZ77 -> the original bytes.
                    File tokens = tempFile();
                    try {
                        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(tokens), BUFFER_SIZE)) {
                            huffman.decode(in, out, listener, 0, 50);
                        }
                        lz77.decode(tokens, output, originalLength, listener, 50, 100);
                    } finally {
                        tokens.delete();
                    }
                    break;
                }

                default:
                    throw new IOException("Unknown compression method (" + method + ").");
            }
            return method;

        } catch (EOFException e) {
            throw new IOException("The compressed file is truncated or corrupt.", e);
        }
    }

    private void copyExactly(InputStream in, File output, long length, ProgressListener listener)
            throws IOException {
        Progress progress = new Progress(listener);
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(output), BUFFER_SIZE)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            long remaining = length;
            while (remaining > 0) {
                int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (n == -1) {
                    throw new EOFException("Unexpected end of stored data.");
                }
                out.write(buffer, 0, n);
                remaining -= n;
                progress.report(length - remaining, length, 0, 100);
            }
        }
        progress.report(1, 1, 100, 100);
    }

    private File tempFile() throws IOException {
        File f = File.createTempFile("huffzip_", ".tmp");
        f.deleteOnExit();                                    // safety net if the program is killed
        return f;
    }
}
