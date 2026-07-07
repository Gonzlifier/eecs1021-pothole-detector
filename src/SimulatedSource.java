import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Fake distance source for developing and demoing WITHOUT any hardware.
 *
 * It imitates an ultrasonic sensor mounted on a car pointing down at the road:
 *   - Normally it reads a steady "ride height" ({@link #BASELINE_CM}) with a
 *     little measurement noise.
 *   - Every so often the simulated car drives over a pothole: the road surface
 *     drops away, so the measured distance jumps UP for a few readings.
 *
 * Readings are produced on a background timer thread (just like the real serial
 * reader will be), so the rest of the app already handles cross-thread updates
 * correctly. MainFrame marshals each reading onto the Swing event thread.
 */
public class SimulatedSource implements DistanceSource {

    private static final double BASELINE_CM = 8.0;   // sensor height above the road
    private static final double NOISE_CM    = 0.25;  // sensor jitter (std-dev)
    private static final int    PERIOD_MS   = 50;    // 20 readings per second
    private static final double POTHOLE_CHANCE = 0.012; // per-reading chance to start one

    private final Random rng = new Random();
    private Timer timer;

    // State while "driving over" a pothole.
    private int    potholeTicksLeft = 0;
    private double potholeDepthCm   = 0;

    @Override
    public void start(DistanceListener listener) {
        timer = new Timer("sim-distance", true); // daemon thread
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override public void run() {
                listener.onReading(nextReading());
            }
        }, 0, PERIOD_MS);
    }

    private double nextReading() {
        // Occasionally begin a new pothole event.
        if (potholeTicksLeft == 0 && rng.nextDouble() < POTHOLE_CHANCE) {
            potholeTicksLeft = 4 + rng.nextInt(6);          // lasts ~4-9 readings
            potholeDepthCm   = 3.0 + rng.nextDouble() * 4.0; // 3-7 cm deeper
        }

        double value = BASELINE_CM + rng.nextGaussian() * NOISE_CM;
        if (potholeTicksLeft > 0) {
            value += potholeDepthCm; // road dropped away -> larger distance
            potholeTicksLeft--;
        }
        return Math.max(0, value);
    }

    @Override
    public void stop() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    @Override
    public String name() {
        return "Simulated";
    }
}
