/**
 * A node of the Huffman binary tree.
 *
 * - A LEAF node stores one byte value (0-255) and how often it occurs.
 * - An INTERNAL node has two children and stores the sum of their frequencies.
 *
 * Nodes are Comparable so a PriorityQueue can use them as a Min Heap:
 * the node with the SMALLEST frequency is always polled first.
 */
public class HuffmanNode implements Comparable<HuffmanNode> {

    private final int symbol;          // byte value 0..255 for leaves, -1 for internal nodes
    private final long frequency;      // how many times this symbol (or subtree) occurs
    private final HuffmanNode left;    // child reached by bit 0
    private final HuffmanNode right;   // child reached by bit 1
    private final int order;           // tie-breaker so the tree is built deterministically

    /** Creates a leaf node. */
    public HuffmanNode(int symbol, long frequency, int order) {
        this.symbol = symbol;
        this.frequency = frequency;
        this.left = null;
        this.right = null;
        this.order = order;
    }

    /** Creates an internal node whose frequency is the sum of its children. */
    public HuffmanNode(HuffmanNode left, HuffmanNode right, int order) {
        this.symbol = -1;
        this.frequency = left.frequency + right.frequency;
        this.left = left;
        this.right = right;
        this.order = order;
    }

    public boolean isLeaf() {
        return left == null && right == null;
    }

    public int getSymbol() {
        return symbol;
    }

    public long getFrequency() {
        return frequency;
    }

    public HuffmanNode getLeft() {
        return left;
    }

    public HuffmanNode getRight() {
        return right;
    }

    /** Lower frequency = higher priority. Ties are broken by creation order. */
    @Override
    public int compareTo(HuffmanNode other) {
        int cmp = Long.compare(this.frequency, other.frequency);
        return (cmp != 0) ? cmp : Integer.compare(this.order, other.order);
    }
}
