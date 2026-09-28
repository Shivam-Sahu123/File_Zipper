import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * LZ77 (LZSS variant) compressor, written from scratch. Contains NO Swing code.
 *
 * IDEA
 * ----
 * Huffman coding only looks at how often single bytes occur. LZ77 finds
 * REPEATED SEQUENCES instead. While scanning the file, for the current position
 * it searches the previous 64 KB (the "sliding window") for the longest earlier
 * copy of the upcoming bytes:
 *
 *   - if a copy of at least MIN_MATCH bytes exists, output a MATCH token
 *         "go back <distance> bytes and copy <length> bytes"
 *   - otherwise output the byte itself as a LITERAL token
 *
 * Example:  "abcabcabcabc"  ->  literal a, literal b, literal c, match(distance 3, length 9)
 *
 * The token stream is a plain byte stream, so the Huffman stage can then squeeze
 * it further. That is exactly the idea behind ZIP / GZIP / PNG (DEFLATE).
 *
 * TOKEN STREAM FORMAT
 * -------------------
 * Tokens come in groups of up to 8, each group starting with one FLAG byte.
 * Bit i of the flag byte (least significant bit first) describes token i:
 *     0 = literal : 1 byte  (the byte itself)
 *     1 = match   : 3 bytes (distance-1 as 16 bits big-endian, then length-MIN_MATCH as 1 byte)
 *
 * The decoder stops as soon as it has produced the original file length, so
 * unused bits in the last flag byte are simply ignored.
 */
public class LZ77Codec {

    private static final int WINDOW_SIZE = 64 * 1024;          // maximum match distance
    private static final int WINDOW_MASK = WINDOW_SIZE - 1;
    private static final int MIN_MATCH = 4;                    // shorter matches are not worth a 3-byte token
    private static final int MAX_MATCH = MIN_MATCH + 255;      // length is stored in one byte
    private static final int HASH_BITS = 15;
    private static final int HASH_SIZE = 1 << HASH_BITS;
    private static final int MAX_CHAIN = 128;                  // how many candidates to try (speed vs. ratio)
    private static final int CHUNK_SIZE = 1 << 20;             // 1 MB of new data read at a time
    private static final int BUFFER_CAPACITY = WINDOW_SIZE + CHUNK_SIZE;
    private static final int IO_BUFFER = 64 * 1024;

    // =====================================================================
    //  ENCODING
    // =====================================================================

    /**
     * Compresses 'input' into a token stream in 'output'.
     * The file is processed in 1 MB chunks, keeping the last 64 KB as the
     * window, so memory use stays small even for huge files.
     */
    public void encode(File input, File output, ProgressListener listener,
                       int from, int to) throws IOException {
        Progress progress = new Progress(listener);
        long fileLength = input.length();

        try (InputStream in = new FileInputStream(input);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(output), IO_BUFFER)) {

            byte[] buf = new byte[BUFFER_CAPACITY];      // [ window | new data ]
            MatchFinder finder = new MatchFinder(buf);
            TokenWriter writer = new TokenWriter(out);

            int end = 0;                                 // number of valid bytes in buf
            int pos = 0;                                 // next byte to encode
            boolean eof = false;
            long processed = 0;

            while (true) {
                // Refill the buffer with new data from the file.
                while (!eof && end < BUFFER_CAPACITY) {
                    int n = in.read(buf, end, BUFFER_CAPACITY - end);
                    if (n == -1) {
                        eof = true;
                    } else {
                        end += n;
                    }
                }
                finder.end = end;

                // Only encode positions that still have a full MAX_MATCH lookahead,
                // unless this is the end of the file.
                int limit = eof ? end : end - MAX_MATCH;

                while (pos < limit) {
                    finder.find(pos);
                    if (finder.bestLen >= MIN_MATCH) {
                        writer.match(finder.bestDist, finder.bestLen);
                        for (int i = 0; i < finder.bestLen; i++) {
                            finder.insert(pos + i);      // keep the hash chains up to date
                        }
                        pos += finder.bestLen;
                        processed += finder.bestLen;
                    } else {
                        writer.literal(buf[pos] & 0xFF);
                        finder.insert(pos);
                        pos++;
                        processed++;
                    }
                }
                progress.report(processed, fileLength, from, to);

                if (eof) {
                    break;
                }

                // Slide the window: keep only the last WINDOW_SIZE bytes before 'pos'.
                int keep = Math.min(pos, WINDOW_SIZE);
                int shift = pos - keep;
                System.arraycopy(buf, shift, buf, 0, end - shift);
                end -= shift;
                pos = keep;
                finder.end = end;
                finder.rebuild(pos);                     // positions moved, so re-index the window
            }
            writer.finish();
        }
        progress.report(1, 1, to, to);
    }

    /**
     * Finds the longest earlier occurrence of the upcoming bytes using a hash table.
     *
     * head[h] = most recent position whose next 4 bytes hash to h
     * prev[p] = the previous position with the same hash as position p
     * Following prev[] gives a "chain" of candidates, newest first.
     * Only MAX_CHAIN candidates are checked, which keeps the search fast.
     */
    private static class MatchFinder {
        final byte[] buf;
        int end;
        final int[] head = new int[HASH_SIZE];
        final int[] prev = new int[WINDOW_SIZE];
        int bestLen;
        int bestDist;

        MatchFinder(byte[] buf) {
            this.buf = buf;
            Arrays.fill(head, -1);
        }

        private int hash(int p) {
            int v = (buf[p] & 0xFF)
                  | ((buf[p + 1] & 0xFF) << 8)
                  | ((buf[p + 2] & 0xFF) << 16)
                  | ((buf[p + 3] & 0xFF) << 24);
            return (v * 0x9E3779B1) >>> (32 - HASH_BITS);
        }

        void insert(int p) {
            if (p + MIN_MATCH <= end) {
                int h = hash(p);
                prev[p & WINDOW_MASK] = head[h];
                head[h] = p;
            }
        }

        void rebuild(int upTo) {
            Arrays.fill(head, -1);
            for (int p = 0; p < upTo; p++) {
                insert(p);
            }
        }

        /** Sets bestLen / bestDist for position 'pos' (bestLen = 0 when nothing useful is found). */
        void find(int pos) {
            bestLen = 0;
            bestDist = 0;
            if (pos + MIN_MATCH > end) {
                return;
            }
            int maxLen = Math.min(MAX_MATCH, end - pos);
            int cand = head[hash(pos)];
            int chain = MAX_CHAIN;
            while (cand >= 0 && pos - cand <= WINDOW_SIZE && chain-- > 0) {
                // Quick reject: a better match must also agree at index bestLen.
                if (buf[cand + bestLen] == buf[pos + bestLen]) {
                    int len = 0;
                    while (len < maxLen && buf[cand + len] == buf[pos + len]) {
                        len++;
                    }
                    if (len > bestLen) {
                        bestLen = len;
                        bestDist = pos - cand;
                        if (len >= maxLen) {
                            return;                      // cannot do better
                        }
                    }
                }
                int next = prev[cand & WINDOW_MASK];
                if (next >= cand) {
                    break;                               // safety: chains must go backwards
                }
                cand = next;
            }
        }
    }

    /** Collects tokens into groups of 8 and writes each group behind its flag byte. */
    private static class TokenWriter {
        private final OutputStream out;
        private final byte[] group = new byte[1 + 8 * 3];    // flag byte + up to 8 tokens of 3 bytes
        private int used = 1;
        private int flags = 0;
        private int count = 0;

        TokenWriter(OutputStream out) {
            this.out = out;
        }

        void literal(int b) throws IOException {
            group[used++] = (byte) b;
            tokenAdded();
        }

        void match(int distance, int length) throws IOException {
            int d = distance - 1;                            // 1..65536 -> 0..65535
            group[used++] = (byte) (d >> 8);
            group[used++] = (byte) d;
            group[used++] = (byte) (length - MIN_MATCH);
            flags |= 1 << count;                             // mark this token as a match
            tokenAdded();
        }

        private void tokenAdded() throws IOException {
            count++;
            if (count == 8) {
                writeGroup();
            }
        }

        void finish() throws IOException {
            if (count > 0) {
                writeGroup();
            }
        }

        private void writeGroup() throws IOException {
            group[0] = (byte) flags;
            out.write(group, 0, used);
            used = 1;
            flags = 0;
            count = 0;
        }
    }

    // =====================================================================
    //  DECODING
    // =====================================================================

    /**
     * Rebuilds the original data from a token stream.
     *
     * The decoder only needs the last 64 KB of its own output (a circular
     * buffer). For a match it copies bytes from 'distance' positions back.
     * If length > distance the copy overlaps itself, which is intended: copying
     * one byte at a time produces repeating patterns ("aaaa...").
     *
     * @param expectedLength number of bytes the output must have (stored in the file header)
     */
    public void decode(File input, File output, long expectedLength, ProgressListener listener,
                       int from, int to) throws IOException {
        Progress progress = new Progress(listener);
        byte[] window = new byte[WINDOW_SIZE];
        int wpos = 0;
        long produced = 0;

        try (InputStream in = new BufferedInputStream(new FileInputStream(input), IO_BUFFER);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(output), IO_BUFFER)) {

            while (produced < expectedLength) {
                int flags = readByte(in);
                for (int i = 0; i < 8 && produced < expectedLength; i++) {
                    if (((flags >> i) & 1) == 0) {
                        // literal
                        int b = readByte(in);
                        window[wpos] = (byte) b;
                        wpos = (wpos + 1) & WINDOW_MASK;
                        out.write(b);
                        produced++;
                    } else {
                        // match
                        int hi = readByte(in);
                        int lo = readByte(in);
                        int distance = ((hi << 8) | lo) + 1;
                        int length = readByte(in) + MIN_MATCH;
                        if (distance > produced || produced + length > expectedLength) {
                            throw new IOException("The compressed file is corrupt (bad match token).");
                        }
                        for (int k = 0; k < length; k++) {
                            byte b = window[(wpos - distance) & WINDOW_MASK];
                            window[wpos] = b;
                            wpos = (wpos + 1) & WINDOW_MASK;
                            out.write(b);
                        }
                        produced += length;
                    }
                }
                progress.report(produced, expectedLength, from, to);
            }
        }
        progress.report(1, 1, to, to);
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b == -1) {
            throw new EOFException("Unexpected end of compressed data.");
        }
        return b;
    }
}
