import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The "brains" of the project: turns a stream of raw distance readings into
 * pothole detections.
 *
 * ALGORITHM (moving-average baseline + threshold):
 *   1. Keep a sliding window of recent NORMAL readings and average them. This
 *      average is the current "road baseline" (the expected ride height).
 *   2. For each new reading, compute its deviation from the baseline.
 *   3. If a reading is more than {@code thresholdCm} ABOVE the baseline, the road
 *      has dropped away -> it's a pothole.
 *   4. Anomalous readings are NOT added to the window, so one big pothole reading
 *      cannot drag the baseline up and hide the next one.
 *   5. A simple state flag debounces detection: one event is fired when we ENTER
 *      a pothole, not once per reading while we are still over it.
 */
public class PotholeAnalyzer {

    /** Notified once each time a new pothole is entered. */
    public interface Listener {
        void onPothole(double depthCm, double baselineCm, double readingCm);
    }

    private final int windowSize;
    private final Deque<Double> window = new ArrayDeque<>();
    private double windowSum = 0;

    private double thresholdCm;
    private boolean inPothole = false;
    private Listener listener;

    public PotholeAnalyzer(int windowSize, double thresholdCm) {
        this.windowSize = windowSize;
        this.thresholdCm = thresholdCm;
    }

    public void setThresholdCm(double thresholdCm) { this.thresholdCm = thresholdCm; }
    public double getThresholdCm() { return thresholdCm; }
    public void setListener(Listener listener) { this.listener = listener; }

    /** True once the window is full, i.e. the baseline is established. */
    public boolean isCalibrated() { return window.size() >= windowSize; }

    /** How many samples are in the window so far (0..windowSize). */
    public int sampleCount() { return window.size(); }

    /** Target number of samples for a full baseline. */
    public int windowSize() { return windowSize; }

    /** Clear all state so the next readings re-calibrate from scratch. */
    public void reset() {
        window.clear();
        windowSum = 0;
        inPothole = false;
    }

    /** Current road baseline, or NaN until the window has data. */
    public double baseline() {
        return window.isEmpty() ? Double.NaN : windowSum / window.size();
    }

    /**
     * Feed one reading into the analyzer.
     *
     * @return how far this reading is above the baseline, in cm
     *         (0 until a baseline exists). A pothole event may be fired as a
     *         side effect via the listener.
     */
    public double accept(double readingCm) {
        double base = baseline();
        double deviation = Double.isNaN(base) ? 0 : (readingCm - base);

        // Calibration phase: still filling the window. Detect nothing yet; just
        // add every reading so the baseline can form.
        if (!isCalibrated()) {
            inPothole = false;
            pushWindow(readingCm);
            return deviation;
        }

        boolean anomalous = deviation > thresholdCm;
        if (anomalous) {
            if (!inPothole) {
                inPothole = true;
                if (listener != null) listener.onPothole(deviation, base, readingCm);
            }
            // Deliberately do NOT add this reading to the baseline window.
        } else {
            inPothole = false;
            pushWindow(readingCm);
        }
        return deviation;
    }

    private void pushWindow(double value) {
        window.addLast(value);
        windowSum += value;
        while (window.size() > windowSize) {
            windowSum -= window.removeFirst();
        }
    }
}
