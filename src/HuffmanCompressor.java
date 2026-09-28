import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.PriorityQueue;

/**
 * Huffman coding engine. Contains NO Swing code.
 *
 * It reads or writes one "Huffman block":
 *
 *   8 bytes : number of bytes that were encoded (long)
 *   then, only if that number > 0, one continuous BIT stream:
 *       [ serialized Huffman tree ][ encoded data bits ][ zero padding to a byte boundary ]
 *
 * TREE SERIALIZATION (pre-order traversal)
 *   leaf node      -> bit 1, followed by the 8 bits of the byte value
 *   internal node  -> bit 0, followed by the left subtree, then the right subtree
 *
 * The block does not know what the bytes mean. They can be the original file
 * (Huffman-only mode) or the token stream produced by the LZ77 stage.
 * The caller (FileCompressor) is responsible for opening and closing the streams.
 */
public class HuffmanCompressor {

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int MAX_SYMBOLS = 256;        // number of possible byte values

    // =====================================================================
    //  ENCODING
    // =====================================================================

    /**
     * Huffman-encodes the whole 'input' file and writes one Huffman block to 'out'.
     * 'out' should be buffered by the caller and is NOT closed here.
     * Progress is reported inside the range [from, to] (percent).
     */
    public void encode(File input, OutputStream out, ProgressListener listener,
                       int from, int to) throws IOException {
        Progress progress = new Progress(listener);
        int mid = from + (to - from) * 3 / 10;              // pass 1 uses 30 % of the range
        long fileLength = input.length();

        // ---- PASS 1: count how often each byte value occurs ----
        long[] frequencies = new long[MAX_SYMBOLS];
        long total = 0;
        try (InputStream in = new FileInputStream(input)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buffer)) != -1) {
                for (int i = 0; i < n; i++) {
                    frequencies[buffer[i] & 0xFF]++;
                }
                total += n;
                progress.report(total, fileLength, from, mid);
            }
        }

        // ---- Build the Huffman tree and the code table ----
        HuffmanNode root = buildTree(frequencies);           // null for an empty input
        byte[][] codes = buildCodeTable(root);               // codes[b] = array of 0/1 bits

        // ---- PASS 2: write the block length, the tree, then the encoded data ----
        new DataOutputStream(out).writeLong(total);
        if (total > 0) {                                     // empty input: length only
            BitOutputStream bits = new BitOutputStream(out);
            writeTree(root, bits);

            try (InputStream in = new FileInputStream(input)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                long written = 0;
                int n;
                while ((n = in.read(buffer)) != -1) {
                    for (int i = 0; i < n; i++) {
                        byte[] code = codes[buffer[i] & 0xFF];
                        if (code == null) {
                            throw new IOException("The file changed while it was being compressed.");
                        }
                        // Real bit-level output: one bit per code element.
                        for (byte bit : code) {
                            bits.writeBit(bit);
                        }
                    }
                    written += n;
                    progress.report(written, total, mid, to);
                }
                if (written != total) {
                    throw new IOException("The file changed while it was being compressed.");
                }
            }
            bits.flush();                                    // pad and write the last partial byte
        }
        progress.report(1, 1, to, to);
    }

    /**
     * Builds the Huffman tree using a PriorityQueue as a Min Heap.
     *
     * Algorithm:
     *   1. Put one leaf per used byte value into the heap.
     *   2. Repeatedly remove the two lowest-frequency nodes, join them under a new
     *      parent whose frequency is their sum, and put the parent back.
     *   3. When one node remains, it is the root.
     *
     * Bytes that occur often end up close to the root (short codes);
     * rare bytes end up deep in the tree (long codes).
     */
    private HuffmanNode buildTree(long[] frequencies) {
        PriorityQueue<HuffmanNode> heap = new PriorityQueue<>();
        for (int symbol = 0; symbol < MAX_SYMBOLS; symbol++) {
            if (frequencies[symbol] > 0) {
                heap.add(new HuffmanNode(symbol, frequencies[symbol], symbol));
            }
        }
        if (heap.isEmpty()) {
            return null;                                     // empty input
        }
        int order = MAX_SYMBOLS;                             // tie-breaker for new internal nodes
        while (heap.size() > 1) {
            HuffmanNode a = heap.poll();
            HuffmanNode b = heap.poll();
            heap.add(new HuffmanNode(a, b, order++));
        }
        return heap.poll();
    }

    /**
     * Generates the code of every symbol by walking the tree:
     * going LEFT appends bit 0, going RIGHT appends bit 1.
     * Each code is kept as a byte[] of 0/1 values (a code can be up to 255 bits
     * long, which does not fit in a long).
     *
     * Special case: if the input has only ONE distinct byte, the tree is a single
     * leaf and its code has length 0. No data bits are written at all; the
     * decoder simply repeats that byte 'length' times.
     */
    private byte[][] buildCodeTable(HuffmanNode root) {
        byte[][] codes = new byte[MAX_SYMBOLS][];
        if (root == null) {
            return codes;
        }
        if (root.isLeaf()) {
            codes[root.getSymbol()] = new byte[0];
            return codes;
        }
        assignCodes(root, new byte[MAX_SYMBOLS], 0, codes);
        return codes;
    }

    private void assignCodes(HuffmanNode node, byte[] path, int depth, byte[][] codes) {
        if (node.isLeaf()) {
            codes[node.getSymbol()] = Arrays.copyOf(path, depth);
            return;
        }
        path[depth] = 0;
        assignCodes(node.getLeft(), path, depth + 1, codes);
        path[depth] = 1;
        assignCodes(node.getRight(), path, depth + 1, codes);
    }

    /** Writes the tree shape to the bit stream (pre-order). See class comment for the format. */
    private void writeTree(HuffmanNode node, BitOutputStream bits) throws IOException {
        if (node.isLeaf()) {
            bits.writeBit(1);
            bits.writeBits(node.getSymbol(), 8);
        } else {
            bits.writeBit(0);
            writeTree(node.getLeft(), bits);
            writeTree(node.getRight(), bits);
        }
    }

    // =====================================================================
    //  DECODING
    // =====================================================================

    /**
     * Reads one Huffman block from 'in' and writes the decoded bytes to 'out'.
     * Both streams should be buffered by the caller and are NOT closed here.
     *
     * @return the number of bytes that were decoded
     */
    public long decode(InputStream in, OutputStream out, ProgressListener listener,
                       int from, int to) throws IOException {
        Progress progress = new Progress(listener);

        long total = new DataInputStream(in).readLong();
        if (total < 0) {
            throw new IOException("The file header is corrupt.");
        }
        if (total == 0) {                                    // nothing was encoded
            progress.report(1, 1, to, to);
            return 0;
        }

        // ---- Rebuild the Huffman tree from the bit stream ----
        BitInputStream bits = new BitInputStream(in);
        HuffmanNode root = readTree(bits, 0, new int[1]);

        byte[] buffer = new byte[BUFFER_SIZE];

        if (root.isLeaf()) {
            // Only one distinct byte was encoded: just repeat it.
            Arrays.fill(buffer, (byte) root.getSymbol());
            long remaining = total;
            while (remaining > 0) {
                int chunk = (int) Math.min(buffer.length, remaining);
                out.write(buffer, 0, chunk);
                remaining -= chunk;
                progress.report(total - remaining, total, from, to);
            }
        } else {
            // ---- Decode: follow bits from the root until a leaf is reached ----
            int pos = 0;
            long done = 0;
            for (long i = 0; i < total; i++) {
                HuffmanNode node = root;
                while (!node.isLeaf()) {
                    node = (bits.readBit() == 0) ? node.getLeft() : node.getRight();
                }
                buffer[pos++] = (byte) node.getSymbol();
                done++;
                if (pos == buffer.length) {
                    out.write(buffer, 0, pos);
                    pos = 0;
                    progress.report(done, total, from, to);
                }
            }
            out.write(buffer, 0, pos);
        }
        progress.report(1, 1, to, to);
        return total;
    }

    /**
     * Reconstructs the tree from the serialized form (mirror of writeTree).
     * Depth and leaf-count limits protect against corrupt files.
     */
    private HuffmanNode readTree(BitInputStream bits, int depth, int[] leafCount) throws IOException {
        if (depth > MAX_SYMBOLS) {
            throw new IOException("The compressed file's Huffman tree is corrupt.");
        }
        if (bits.readBit() == 1) {                           // leaf
            if (++leafCount[0] > MAX_SYMBOLS) {
                throw new IOException("The compressed file's Huffman tree is corrupt.");
            }
            return new HuffmanNode(bits.readBits(8), 0, 0);
        }
        HuffmanNode left = readTree(bits, depth + 1, leafCount);
        HuffmanNode right = readTree(bits, depth + 1, leafCount);
        return new HuffmanNode(left, right, 0);
    }
}
