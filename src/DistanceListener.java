/*
 * Pothole Detector - EECS 1021-E Final Project
 * Author: Eric Xihuan Shi (222476709)
 */

/**
 * Called once for every new distance measurement (in centimetres).
 *
 * A DistanceSource pushes readings to a listener. This keeps the rest of the
 * program independent of the serial I/O layer that produces the readings.
 */
public interface DistanceListener {
    void onReading(double distanceCm);
}
