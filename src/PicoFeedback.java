/**
 * The actuator (output) side of the system: drive the LED and buzzer.
 *
 * Kept separate from {@link DistanceSource} (the input side) so the two
 * directions of the protocol are clearly distinguished. SimulatedSource does NOT
 * implement this (simulation has no real actuators); PicoSerialSource does.
 */
public interface PicoFeedback {
    void led(boolean on);
    void buzzer(boolean on);

    /** Move the severity-gauge servo to an angle (0-180 degrees). */
    void servo(int angleDeg);
}
