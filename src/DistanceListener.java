/**
 * Called once for every new distance measurement (in centimetres).
 *
 * A DistanceSource pushes readings to a listener. This keeps the rest of the
 * program independent of WHERE the numbers come from (simulation today, a real
 * ultrasonic sensor over serial later).
 */
public interface DistanceListener {
    void onReading(double distanceCm);
}
