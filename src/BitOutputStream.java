import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Writes individual bits to an underlying byte stream.
 *
 * Bits are collected in a small buffer (one byte). As soon as 8 bits have
 * been collected, the byte is written out. Bits are packed starting from the
 * most significant bit (MSB) of each byte.
 */
public class BitOutputStream implements Closeable, Flushable {

    private final OutputStream out;
    private int currentByte = 0;   // bits collected so far
    private int bitCount = 0;      // how many bits are in currentByte (0..7)

    public BitOutputStream(OutputStream out) {
        this.out = out;
    }

    /** Writes a single bit (0 or 1). */
    public void writeBit(int bit) throws IOException {
        currentByte = (currentByte << 1) | (bit & 1);
        bitCount++;
        if (bitCount == 8) {
            out.write(currentByte);
            currentByte = 0;
            bitCount = 0;
        }
    }

    /** Writes the lowest 'count' bits of 'value', most significant bit first. */
    public void writeBits(int value, int count) throws IOException {
        for (int i = count - 1; i >= 0; i--) {
            writeBit((value >> i) & 1);
        }
    }

    /**
     * Writes any partially filled last byte (padded with zero bits) and flushes
     * the underlying stream. Call this ONCE, after the last bit has been written.
     * The decoder never reads the padding because the header stores the exact
     * number of original bytes to decode.
     */
    @Override
    public void flush() throws IOException {
        if (bitCount > 0) {
            out.write(currentByte << (8 - bitCount));
            currentByte = 0;
            bitCount = 0;
        }
        out.flush();
    }

    @Override
    public void close() throws IOException {
        flush();
        out.close();
    }
}
