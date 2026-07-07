import javax.swing.SwingUtilities;

/**
 * Entry point. Builds the window on the Swing event thread.
 *
 * Run this with NO hardware attached: it starts in simulation mode, so press
 * "Start" and you will see the live distance graph, the event log, and the
 * LED/buzzer feedback fire whenever a simulated pothole goes by.
 */
public class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new MainFrame().setVisible(true));
    }
}
