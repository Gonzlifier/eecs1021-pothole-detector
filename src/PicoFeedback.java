/*
 * Pothole Detector - EECS 1021-E Final Project
 * Author: Eric Xihuan Shi (222476709)
 */

/**
 * The actuator (output) side of the system: drive the LED and buzzer.
 *
 * Kept separate from {@link DistanceSource} (the input side) so the two
 * directions of the protocol are clearly distinguished. PicoSerialSource
 * implements this to drive the real actuators over serial.
 */
public interface PicoFeedback {
    void led(boolean on);
    void buzzer(boolean on);

    /** Move the severity-gauge servo to an angle (0-180 degrees). */
    void servo(int angleDeg);
}
