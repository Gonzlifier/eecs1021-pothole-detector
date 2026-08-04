/*
 * Pothole Detector - EECS 1021-E Final Project
 * Author: Eric Xihuan Shi (222476709)
 */

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;

/**
 * Main window: wires the data source, the detector, the live chart, the log and
 * the feedback (LED + buzzer) together.
 *
 * Data flow:
 *   DistanceSource --(background thread)--> onReading() --(invokeLater)-->
 *       PotholeAnalyzer.accept() --> chart + log, and on a detection --> feedback
 *
 * The data source is a Pico on a serial port, chosen from the dropdown.
 * Everything that touches Swing or the analyzer runs on the Swing event thread;
 * the serial worker marshals onto it.
 */
public class MainFrame extends JFrame {

    private static final int    WINDOW_SIZE       = 25;  // baseline = last 25 normal readings
    private static final double DEFAULT_THRESHOLD = 3.0;
    private static final int    ALERT_MS          = 600; // how long the alert stays on
    private static final double SEVERITY_MAX_CM   = 10.0; // depth that maps to full servo swing
    private static final int    SERVO_REST_DEG    = 0;    // gauge resting angle
    private static final int    NOISE_BUF         = 60;   // samples used for the noise metric
    private static final int    BENCH_SAMPLES     = 100;  // samples per benchmark run

    private final PotholeAnalyzer analyzer = new PotholeAnalyzer(WINDOW_SIZE, DEFAULT_THRESHOLD);
    private final LiveChartPanel chart = new LiveChartPanel();
    private final JTextArea log = new JTextArea();

    private DistanceSource source;
    private PicoFeedback feedback;     // non-null only when using real hardware
    private boolean running = false;

    // --- controls ---
    private final JComboBox<String> sourceSelect = new JComboBox<>();
    private final JButton refreshPorts = new JButton("Refresh"); // re-scan serial ports
    private final JButton startStop = new JButton("Start");
    private final JButton injectBtn = new JButton("Inject pothole");
    private final JSlider thresholdSlider = new JSlider(5, 100, (int) (DEFAULT_THRESHOLD * 10));
    private final JLabel thresholdValue = new JLabel();
    private final JCheckBox ledFeedback = new JCheckBox("LED feedback", true);
    private final JCheckBox buzzerFeedback = new JCheckBox("Buzzer feedback", true);
    private final JCheckBox servoFeedback = new JCheckBox("Servo gauge", false);

    // Metrics (performance) status bar.
    private final JLabel metricsLabel = new JLabel("rate --   |   latency --   |   noise --");
    private final JButton benchmarkBtn = new JButton("Benchmark");
    private long metricFirstNanos = 0;
    private long metricSamples = 0;
    private int latCount = 0;
    private double latSum = 0, latMin = Double.MAX_VALUE, latMax = 0;
    private final Deque<Double> noiseBuf = new ArrayDeque<>();
    private int benchRemaining = 0;

    // On-screen LED indicator (mirrors the real LED so the demo is visible).
    private final JPanel ledIndicator = new JPanel();
    private Timer alertOffTimer;

    // Calibration status shown while the baseline window fills.
    private final JLabel statusLabel = new JLabel("Idle");

    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss.SSS");
    private int potholeCount = 0;

    public MainFrame() {
        super("EECS 1021 - Pothole Detector");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));

        add(buildControls(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);

        analyzer.setListener(this::onPothole);
        wireControls();
        reloadPorts();
        updateThresholdLabel();

        setSize(860, 640);
        setLocationRelativeTo(null);
    }

    private JPanel buildControls() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));

        bar.add(new JLabel("Source:"));
        sourceSelect.setPreferredSize(new Dimension(170, 28));
        bar.add(sourceSelect);
        refreshPorts.setToolTipText("Refresh serial ports");
        bar.add(refreshPorts);

        bar.add(startStop);
        injectBtn.setToolTipText("Force a pothole detection (for demos)");
        bar.add(injectBtn);

        bar.add(new JLabel("   Threshold:"));
        thresholdSlider.setPreferredSize(new Dimension(150, 30));
        bar.add(thresholdSlider);
        bar.add(thresholdValue);

        bar.add(Box.createHorizontalStrut(8));
        bar.add(ledFeedback);
        bar.add(buzzerFeedback);
        bar.add(servoFeedback);

        bar.add(new JLabel("LED:"));
        ledIndicator.setPreferredSize(new Dimension(22, 22));
        ledIndicator.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
        ledIndicator.setBackground(Color.DARK_GRAY);
        bar.add(ledIndicator);

        bar.add(Box.createHorizontalStrut(10));
        bar.add(statusLabel);

        return bar;
    }

    private JSplitPane buildCenter() {
        log.setEditable(false);
        log.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(log);
        logScroll.setBorder(BorderFactory.createTitledBorder("Event log"));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, chart, logScroll);
        split.setResizeWeight(0.7);
        return split;
    }

    private JPanel buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        metricsLabel.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12));
        bar.add(metricsLabel, BorderLayout.CENTER);
        benchmarkBtn.setToolTipText("Measure rate/latency/noise over " + BENCH_SAMPLES + " samples");
        bar.add(benchmarkBtn, BorderLayout.EAST);
        return bar;
    }

    private void wireControls() {
        startStop.addActionListener(e -> toggleRunning());
        injectBtn.addActionListener(e -> injectPothole());
        benchmarkBtn.addActionListener(e -> startBenchmark());
        refreshPorts.addActionListener(e -> reloadPorts());
        thresholdSlider.addChangeListener(e -> {
            analyzer.setThresholdCm(thresholdSlider.getValue() / 10.0);
            updateThresholdLabel();
        });
    }

    /** Populate the source dropdown with the available serial ports. */
    private void reloadPorts() {
        Object previous = sourceSelect.getSelectedItem();
        sourceSelect.removeAllItems();
        try {
            for (String p : PicoSerialSource.listPorts()) {
                sourceSelect.addItem(p);
            }
        } catch (Throwable t) {
            append("(serial library not loaded)");
        }
        if (sourceSelect.getItemCount() == 0) {
            append("(no serial ports found - plug in the Pico and press Refresh)");
        }
        if (previous != null) sourceSelect.setSelectedItem(previous);
    }

    private void updateThresholdLabel() {
        thresholdValue.setText(String.format("%.1f cm", analyzer.getThresholdCm()));
    }

    private void toggleRunning() {
        if (running) {
            source.stop();
            running = false;
            feedback = null;
            startStop.setText("Start");
            sourceSelect.setEnabled(true);
            setStatusIdle();
            append("--- stopped ---");
            return;
        }

        String sel = (String) sourceSelect.getSelectedItem();
        if (sel == null) {
            append("(no serial port selected - plug in the Pico and press Refresh)");
            return;
        }
        PicoSerialSource pico = new PicoSerialSource(sel);
        pico.setErrorHandler(msg -> SwingUtilities.invokeLater(() -> append("! " + msg)));
        pico.setMetricsListener(ms -> SwingUtilities.invokeLater(() -> onLatencySample(ms)));
        source = pico;
        feedback = pico;      // same object also drives the actuators

        analyzer.reset();              // re-calibrate from scratch each Start
        resetMetrics();
        chart.clear();
        setStatusCalibrating(0, analyzer.windowSize());
        source.start(reading -> SwingUtilities.invokeLater(() -> handleReading(reading)));
        running = true;
        startStop.setText("Stop");
        sourceSelect.setEnabled(false);
        append("--- started (source: " + source.name() + ") : calibrating baseline ---");
    }

    /**
     * Demo helper: force a pothole by feeding one spiked reading through the
     * normal pipeline, so it triggers the real detector, log and LED/buzzer just
     * like a genuine pothole would.
     */
    private void injectPothole() {
        if (!running) {
            append("(press Start before injecting a pothole)");
            return;
        }
        if (!analyzer.isCalibrated()) {
            append("(still calibrating - wait for the baseline to finish)");
            return;
        }
        double base = analyzer.baseline();
        if (Double.isNaN(base)) base = 0;
        double spike = base + analyzer.getThresholdCm() + 2.0; // safely over threshold
        handleReading(spike);
    }

    /** Called on the Swing event thread for each new reading. */
    private void handleReading(double reading) {
        boolean wasCalibrated = analyzer.isCalibrated();
        double deviation = analyzer.accept(reading);
        boolean calibrated = analyzer.isCalibrated();
        boolean anomalous = calibrated && deviation > analyzer.getThresholdCm();

        chart.addPoint(reading, analyzer.baseline(), anomalous, !calibrated);

        if (!calibrated) {
            setStatusCalibrating(analyzer.sampleCount(), analyzer.windowSize());
        } else if (!wasCalibrated) {
            setStatusReady();
            append("--- baseline ready, detection active ---");
        }

        recordMetrics(reading, calibrated, anomalous);
    }

    // ----------------------- performance metrics -----------------------

    private void resetMetrics() {
        metricFirstNanos = 0;
        metricSamples = 0;
        latCount = 0;
        latSum = 0;
        latMin = Double.MAX_VALUE;
        latMax = 0;
        noiseBuf.clear();
        benchRemaining = 0;
    }

    private void startBenchmark() {
        if (!running) { append("(press Start before benchmarking)"); return; }
        resetMetrics();
        benchRemaining = BENCH_SAMPLES;
        append("--- benchmark: collecting " + BENCH_SAMPLES + " samples ---");
    }

    /** Round-trip latency of one ULTRASONIC_READ (hardware only). */
    private void onLatencySample(double latencyMs) {
        latCount++;
        latSum += latencyMs;
        latMin = Math.min(latMin, latencyMs);
        latMax = Math.max(latMax, latencyMs);
        updateMetricsLabel();
    }

    private void recordMetrics(double reading, boolean calibrated, boolean anomalous) {
        if (metricFirstNanos == 0) metricFirstNanos = System.nanoTime();
        metricSamples++;
        if (calibrated && !anomalous) {                 // noise = spread of resting readings
            noiseBuf.addLast(reading);
            while (noiseBuf.size() > NOISE_BUF) noiseBuf.removeFirst();
        }
        updateMetricsLabel();

        if (benchRemaining > 0 && --benchRemaining == 0) {
            logBenchmark();
        }
    }

    private void updateMetricsLabel() {
        double elapsed = metricFirstNanos == 0 ? 0 : (System.nanoTime() - metricFirstNanos) / 1e9;
        double rate = elapsed > 0 ? metricSamples / elapsed : 0;
        String lat = latCount > 0 ? String.format("%.1f ms", latSum / latCount) : "n/a";
        String noise = noiseBuf.size() >= 2 ? String.format("+/-%.2f cm", stddev(noiseBuf)) : "--";
        metricsLabel.setText(String.format("rate %.1f Hz   |   latency %s   |   noise %s", rate, lat, noise));
    }

    private void logBenchmark() {
        double elapsed = (System.nanoTime() - metricFirstNanos) / 1e9;
        double rate = elapsed > 0 ? metricSamples / elapsed : 0;
        append("===== BENCHMARK (" + metricSamples + " samples) =====");
        append(String.format("  sample rate : %.1f Hz", rate));
        if (latCount > 0) {
            append(String.format("  latency     : avg %.1f ms  (min %.1f, max %.1f)",
                    latSum / latCount, latMin, latMax));
        } else {
            append("  latency     : n/a (no samples yet)");
        }
        append(String.format("  noise (rest): +/-%.2f cm std dev",
                noiseBuf.size() >= 2 ? stddev(noiseBuf) : 0.0));
        append("========================================");
    }

    private static double stddev(Deque<Double> values) {
        int n = values.size();
        double mean = 0;
        for (double v : values) mean += v;
        mean /= n;
        double sq = 0;
        for (double v : values) sq += (v - mean) * (v - mean);
        return Math.sqrt(sq / n);
    }

    private void setStatusIdle() {
        statusLabel.setText("Idle");
        statusLabel.setForeground(Color.GRAY);
    }

    private void setStatusCalibrating(int n, int total) {
        statusLabel.setText("Calibrating " + n + "/" + total);
        statusLabel.setForeground(new Color(0xBA7517)); // amber
    }

    private void setStatusReady() {
        statusLabel.setText("Ready");
        statusLabel.setForeground(new Color(0x1D9E75)); // green
    }

    /** Called by the analyzer when a new pothole is entered. */
    private void onPothole(double depthCm, double baselineCm, double readingCm) {
        potholeCount++;
        append(String.format("POTHOLE #%d  depth=%.1f cm  (reading=%.1f, baseline=%.1f)",
                potholeCount, depthCm, readingCm, baselineCm));

        if (ledFeedback.isSelected()) {
            ledIndicator.setBackground(new Color(0xE53935)); // red = pothole
            if (feedback != null) feedback.led(true);
        }
        if (buzzerFeedback.isSelected()) {
            Toolkit.getDefaultToolkit().beep();               // on-screen "buzzer"
            if (feedback != null) feedback.buzzer(true);
        }
        if (servoFeedback.isSelected() && feedback != null) {
            // Severity gauge: deeper pothole -> larger needle swing.
            int angle = (int) Math.min(180, Math.max(0, depthCm / SEVERITY_MAX_CM * 180));
            feedback.servo(angle);
        }
        scheduleAlertOff();
    }

    /** Turn the alert (LED + buzzer) back off shortly after a detection. */
    private void scheduleAlertOff() {
        if (alertOffTimer != null && alertOffTimer.isRunning()) alertOffTimer.stop();
        alertOffTimer = new Timer(ALERT_MS, e -> {
            ledIndicator.setBackground(Color.DARK_GRAY);
            if (feedback != null) {
                feedback.led(false);
                feedback.buzzer(false);
                if (servoFeedback.isSelected()) feedback.servo(SERVO_REST_DEG);
            }
        });
        alertOffTimer.setRepeats(false);
        alertOffTimer.start();
    }

    private void append(String line) {
        log.append("[" + clock.format(new Date()) + "] " + line + "\n");
        log.setCaretPosition(log.getDocument().getLength());
    }
}
