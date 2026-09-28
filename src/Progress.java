/**
 * Small helper that converts "done / total" into a percentage inside a chosen
 * range [from, to] and only notifies the listener when the number changes.
 *
 * Because every stage (LZ77, Huffman, copy...) reports inside its own slice of
 * 0-100, several stages can share one progress bar.
 */
final class Progress {

    private final ProgressListener listener;
    private int last = -1;

    Progress(ProgressListener listener) {
        this.listener = listener;
    }

    void report(long done, long total, int from, int to) {
        if (listener == null || total <= 0) {
            return;
        }
        int percent = from + (int) ((to - from) * Math.min(done, total) / total);
        percent = Math.max(0, Math.min(100, percent));
        if (percent != last) {
            last = percent;
            listener.onProgress(percent);
        }
    }
}
