/*
 * Pothole Detector - EECS 1021-E Final Project
 * Author: Eric Xihuan Shi (222476709)
 */

import javax.swing.SwingUtilities;

/**
 * Entry point. Builds the window on the Swing event thread.
 *
 * Select the Pico's serial port from the Source dropdown and press Start to see
 * the live distance graph, the event log, and the LED/buzzer feedback fire on
 * each detected pothole.
 */
public class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new MainFrame().setVisible(true));
    }
}
