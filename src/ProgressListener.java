/** Callback used by the compression classes to report progress (0-100) to the UI. */
public interface ProgressListener {
    void onProgress(int percent);
}
