/*
 * Pothole Detector - EECS 1021-E Final Project
 * Author: Eric Xihuan Shi (222476709)
 */

/**
 * A source of distance readings.
 *
 * This interface isolates the rest of the program -- the GUI, the pothole
 * detection logic, the chart and the logging -- from the details of how the
 * readings are obtained. The concrete implementation, PicoSerialSource, reads
 * real measurements from the Pico over USB serial; the detection and UI code
 * depend only on this interface, not on the serial layer.
 */
public interface DistanceSource {

    /** Begin producing readings, delivering each one to {@code listener}. */
    void start(DistanceListener listener);

    /** Stop producing readings and release any resources. */
    void stop();

    /** Short human-readable name, shown in the UI. */
    String name();
}
