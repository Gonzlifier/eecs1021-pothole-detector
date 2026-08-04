/*
 * Pothole Detector - EECS 1021-E Final Project
 * Author: Eric Xihuan Shi (222476709)
 */

import com.fazecast.jSerialComm.SerialPort;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The real hardware distance source: reads the ultrasonic sensor on the Pico
 * over USB serial, and also drives the LED + buzzer actuators.
 *
 * It owns the single serial connection and runs ONE background worker thread
 * that does all the serial I/O:
 *   - polls "ULTRASONIC_READ" ~16 times/second and pushes each distance to the
 *     DistanceListener;
 *   - drains a small queue of outgoing actuator commands (LED/buzzer) between
 *     polls, so feedback calls from the UI thread never block on serial I/O and
 *     no locking is required.
 *
 * MainFrame creates this from the selected serial port; the detector, chart and
 * logging depend only on the DistanceSource interface, not on this class.
 */
public class PicoSerialSource implements DistanceSource, PicoFeedback {

    // Wiring (see README): change here if you wire to different pins.
    private static final int TRIG_PIN   = 3;
    private static final int ECHO_PIN   = 2;
    private static final int LED_PIN    = 15;
    private static final int BUZZER_PIN = 14;
    private static final int SERVO_PIN  = 16;   // severity-gauge servo (external 5V!)
    private static final int POLL_MS    = 60;   // ~16 readings/second

    /** Optional sink for connection/protocol errors (shown in the UI log). */
    public interface ErrorHandler { void onError(String message); }

    /** Optional sink for per-read round-trip latency, in milliseconds. */
    public interface MetricsListener { void onSample(double latencyMs); }

    private final String portName;
    private final PicoConnection conn;
    private final ConcurrentLinkedQueue<String> outgoing = new ConcurrentLinkedQueue<>();

    private volatile boolean running = false;
    private Thread worker;
    private ErrorHandler errorHandler;
    private MetricsListener metricsListener;

    public PicoSerialSource(String portName) {
        this.portName = portName;
        this.conn = new PicoConnection(portName);
    }

    public void setErrorHandler(ErrorHandler handler) {
        this.errorHandler = handler;
    }

    public void setMetricsListener(MetricsListener listener) {
        this.metricsListener = listener;
    }

    /** List the serial ports currently available (for the UI dropdown). */
    public static String[] listPorts() {
        SerialPort[] ports = SerialPort.getCommPorts();
        String[] names = new String[ports.length];
        for (int i = 0; i < ports.length; i++) {
            names[i] = ports[i].getSystemPortName();
        }
        return names;
    }

    @Override
    public void start(DistanceListener listener) {
        running = true;
        worker = new Thread(() -> run(listener), "pico-serial");
        worker.setDaemon(true);
        worker.start();
    }

    private void run(DistanceListener listener) {
        try {
            conn.open();

            // Configure actuators and attach the sensor.
            conn.send("GPIO_MODE," + LED_PIN + ",OUTPUT");   // LED on/off
            conn.send("BUZZER_ATTACH," + BUZZER_PIN);        // passive buzzer (tone)
            conn.send("SERVO_ATTACH," + SERVO_PIN);          // severity-gauge servo
            conn.send("SERVO_WRITE,0");                      // start at rest
            String attach = conn.send("ULTRASONIC_ATTACH," + TRIG_PIN + "," + ECHO_PIN);
            if (attach == null || !attach.startsWith("OK")) {
                reportError("ULTRASONIC_ATTACH failed: " + attach);
            }

            while (running) {
                // 1) flush any pending actuator commands (LED / buzzer / servo)
                String cmd;
                while ((cmd = outgoing.poll()) != null) {
                    conn.send(cmd);
                }

                // 2) take a distance reading (timed for the latency metric)
                long t0 = System.nanoTime();
                String resp = conn.send("ULTRASONIC_READ");
                double latencyMs = (System.nanoTime() - t0) / 1_000_000.0;
                if (metricsListener != null) metricsListener.onSample(latencyMs);

                if (resp != null && resp.startsWith("OK:")) {
                    try {
                        double cm = Double.parseDouble(resp.substring(3).trim());
                        listener.onReading(cm);
                    } catch (NumberFormatException nfe) {
                        // malformed value -> skip this sample
                    }
                }
                // ERROR:TIMEOUT just means nothing in range; skip the sample.

                sleep(POLL_MS);
            }
        } catch (Exception e) {
            reportError("Serial error: " + e.getMessage());
        } finally {
            running = false;
            try { conn.send("ULTRASONIC_DETACH"); } catch (Exception ignore) { }
            try { conn.send("SERVO_DETACH"); } catch (Exception ignore) { }
            conn.close();
        }
    }

    @Override
    public void stop() {
        running = false;
        if (worker != null) {
            try { worker.join(800); } catch (InterruptedException ignore) { }
        }
    }

    @Override
    public String name() {
        return "Pico @ " + portName;
    }

    // ---- PicoFeedback (actuators) : just enqueue; the worker thread sends them ----

    @Override
    public void led(boolean on) {
        outgoing.add("GPIO_" + (on ? "HIGH" : "LOW") + "," + LED_PIN);
    }

    @Override
    public void buzzer(boolean on) {
        // PASSIVE buzzer (HW-508): the firmware drives it with tone()/noTone()
        // via BUZZER_ON / BUZZER_OFF. Set the pitch once with BUZZER_FREQ if
        // desired (default 2500 Hz).
        outgoing.add(on ? "BUZZER_ON" : "BUZZER_OFF");
    }

    @Override
    public void servo(int angleDeg) {
        int a = Math.max(0, Math.min(180, angleDeg));
        outgoing.add("SERVO_WRITE," + a);
    }

    private void reportError(String msg) {
        if (errorHandler != null) errorHandler.onError(msg);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
