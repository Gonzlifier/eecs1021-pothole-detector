/**
 * A source of distance readings.
 *
 * This is the key abstraction in the project: the GUI, the pothole detection
 * logic, the chart and the logging all depend only on this interface. They do
 * not care whether the readings come from a simulator or from a real Pico.
 *
 * Implementations:
 *   - SimulatedSource   : generates fake "road" data, no hardware needed.
 *   - PicoSerialSource  : (added when the ultrasonic sensor arrives) reads real
 *                         measurements from the Pico over USB serial.
 *
 * Because both implement this interface, switching from simulation to real
 * hardware is a ONE-LINE change in MainFrame and no detection/UI code changes.
 */
public interface DistanceSource {

    /** Begin producing readings, delivering each one to {@code listener}. */
    void start(DistanceListener listener);

    /** Stop producing readings and release any resources. */
    void stop();

    /** Short human-readable name, shown in the UI (e.g. "Simulated"). */
    String name();
}
