/*
 * Pothole Detector - EECS 1021-E Final Project
 * Author: Eric Xihuan Shi (222476709)
 */

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A simple custom-painted live graph (no external charting library).
 *
 * It keeps a rolling buffer of the most recent points and redraws on every new
 * reading. It plots:
 *   - the raw distance trace (green line)
 *   - the moving-average baseline (grey line)
 *   - readings used to build the baseline during calibration (amber dots)
 *   - readings flagged as a pothole (red dots)
 *
 * Larger distance = deeper = plotted higher, so potholes appear as upward spikes.
 */
public class LiveChartPanel extends JPanel {

    private static final int MAX_POINTS = 240; // ~12 s of history at 20 Hz
    private static final int PAD = 36;

    private static final Color C_READING = new Color(0x4CAF50);
    private static final Color C_BASELINE = new Color(0x888888);
    private static final Color C_CALIB = new Color(0xBA7517);
    private static final Color C_POTHOLE = new Color(0xE53935);

    private final Deque<Double>  readings  = new ArrayDeque<>();
    private final Deque<Double>  baselines = new ArrayDeque<>();
    private final Deque<Boolean> potholes  = new ArrayDeque<>();
    private final Deque<Boolean> calibs    = new ArrayDeque<>();

    public LiveChartPanel() {
        setPreferredSize(new Dimension(640, 320));
        setBackground(new Color(0x1E1E1E));
    }

    /** Add one point and repaint. Must be called on the Swing event thread. */
    public void addPoint(double reading, double baseline, boolean pothole, boolean calibrating) {
        readings.addLast(reading);
        baselines.addLast(baseline);
        potholes.addLast(pothole);
        calibs.addLast(calibrating);
        trim(readings);
        trim(baselines);
        trim(potholes);
        trim(calibs);
        repaint();
    }

    public void clear() {
        readings.clear();
        baselines.clear();
        potholes.clear();
        calibs.clear();
        repaint();
    }

    private static void trim(Deque<?> d) {
        while (d.size() > MAX_POINTS) d.removeFirst();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth(), h = getHeight();
        if (readings.isEmpty()) {
            g2.setColor(Color.GRAY);
            g2.drawString("Waiting for data... press Start", PAD, h / 2);
            return;
        }

        Double[] r = readings.toArray(new Double[0]);
        Double[] b = baselines.toArray(new Double[0]);
        Boolean[] p = potholes.toArray(new Boolean[0]);
        Boolean[] c = calibs.toArray(new Boolean[0]);

        // Vertical (distance) range from the data, with a little padding.
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (double v : r) { min = Math.min(min, v); max = Math.max(max, v); }
        min = Math.max(0, min - 1);
        max = max + 1;
        if (max - min < 2) max = min + 2;

        double plotW = w - 2.0 * PAD;
        double plotH = h - 2.0 * PAD;
        double xStep = plotW / (MAX_POINTS - 1);

        // --- gridlines + Y labels ---
        g2.setStroke(new BasicStroke(1f));
        for (int i = 0; i <= 4; i++) {
            int y = (int) (PAD + plotH * i / 4.0);
            g2.setColor(new Color(0x333333));
            g2.drawLine(PAD, y, w - PAD, y);
            double val = max - (max - min) * i / 4.0;
            g2.setColor(Color.GRAY);
            g2.drawString(String.format("%.1f", val), 2, y + 4);
        }

        int n = r.length;

        // --- baseline (grey) ---
        g2.setColor(C_BASELINE);
        g2.setStroke(new BasicStroke(1.5f));
        drawSeries(g2, b, n, xStep, w, min, max, plotH);

        // --- raw distance trace (green) ---
        g2.setColor(C_READING);
        g2.setStroke(new BasicStroke(2f));
        drawSeries(g2, r, n, xStep, w, min, max, plotH);

        // --- calibration points (amber) : which readings built the baseline ---
        g2.setColor(C_CALIB);
        for (int i = 0; i < n; i++) {
            if (c[i] != null && c[i]) {
                int x = (int) (w - PAD - (n - 1 - i) * xStep);
                int y = yMap(r[i], min, max, plotH);
                g2.fillOval(x - 3, y - 3, 6, 6);
            }
        }

        // --- pothole markers (red) ---
        g2.setColor(C_POTHOLE);
        for (int i = 0; i < n; i++) {
            if (p[i] != null && p[i]) {
                int x = (int) (w - PAD - (n - 1 - i) * xStep);
                int y = yMap(r[i], min, max, plotH);
                g2.fillOval(x - 3, y - 3, 7, 7);
            }
        }

        drawLegend(g2, h);
    }

    private void drawLegend(Graphics2D g2, int h) {
        int y = h - 8;
        legendItem(g2, PAD,       y, C_READING,  "reading");
        legendItem(g2, PAD + 90,  y, C_BASELINE, "baseline");
        legendItem(g2, PAD + 190, y, C_CALIB,    "calibrating");
        legendItem(g2, PAD + 320, y, C_POTHOLE,  "pothole");
    }

    private void legendItem(Graphics2D g2, int x, int y, Color color, String label) {
        g2.setColor(color);
        g2.fillOval(x, y - 8, 8, 8);
        g2.setColor(Color.GRAY);
        g2.drawString(label, x + 12, y);
    }

    private void drawSeries(Graphics2D g2, Double[] s, int n, double xStep,
                            int w, double min, double max, double plotH) {
        int prevX = 0, prevY = 0;
        for (int i = 0; i < n; i++) {
            if (s[i] == null || Double.isNaN(s[i])) continue;
            int x = (int) (w - PAD - (n - 1 - i) * xStep);
            int y = yMap(s[i], min, max, plotH);
            if (i > 0 && s[i - 1] != null && !Double.isNaN(s[i - 1])) {
                g2.drawLine(prevX, prevY, x, y);
            }
            prevX = x;
            prevY = y;
        }
    }

    private int yMap(double value, double min, double max, double plotH) {
        return (int) (PAD + (1 - (value - min) / (max - min)) * plotH);
    }
}
