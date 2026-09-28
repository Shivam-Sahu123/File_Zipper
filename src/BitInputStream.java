import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads individual bits from an underlying byte stream.
 * This is the counterpart of BitOutputStream (MSB first).
 */
public class BitInputStream implements Closeable {

    private final InputStream in;
    private int currentByte = 0;   // the byte currently being consumed
    private int bitsLeft = 0;      // unread bits remaining in currentByte

    public BitInputStream(InputStream in) {
        this.in = in;
    }

    /**
     * Reads the next bit.
     *
     * @return 0 or 1
     * @throws EOFException if the stream ends before another bit is available
     */
    public int readBit() throws IOException {
        if (bitsLeft == 0) {
            int b = in.read();
            if (b == -1) {
                throw new EOFException("Unexpected end of compressed data.");
            }
            currentByte = b;
            bitsLeft = 8;
        }
        bitsLeft--;
        return (currentByte >> bitsLeft) & 1;
    }

    /** Reads 'count' bits and returns them as an int (first bit read = most significant). */
    public int readBits(int count) throws IOException {
        int value = 0;
        for (int i = 0; i < count; i++) {
            value = (value << 1) | readBit();
        }
        return value;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
