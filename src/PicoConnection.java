/*
 * Pothole Detector - EECS 1021-E Final Project
 * Author: Eric Xihuan Shi (222476709)
 */

import com.fazecast.jSerialComm.SerialPort;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * Low-level UART driver for the Pico bridge firmware.
 *
 * This is the "custom driver" layer: it knows how to talk to the firmware's
 * line-based ASCII protocol over USB serial (115200 8N1) and nothing about
 * potholes. One command line goes out; the firmware replies with a single line
 * beginning "OK" or "ERROR".
 *
 * THREADING: this class is NOT thread-safe. Exactly one thread (the serial
 * worker in PicoSerialSource) should call {@link #send}. That keeps all serial
 * I/O on a single thread, so no locking is needed.
 */
public class PicoConnection {

    private static final int BAUD = 115200;
    private static final int READ_TIMEOUT_MS = 600;

    private final String portName;
    private SerialPort port;
    private BufferedReader in;
    private BufferedWriter out;

    public PicoConnection(String portName) {
        this.portName = portName;
    }

    /** Open and configure the port. Throws if the port cannot be opened. */
    public void open() throws IOException {
        port = SerialPort.getCommPort(portName);
        port.setBaudRate(BAUD);
        port.setNumDataBits(8);
        port.setNumStopBits(SerialPort.ONE_STOP_BIT);
        port.setParity(SerialPort.NO_PARITY);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, READ_TIMEOUT_MS, 0);

        if (!port.openPort()) {
            throw new IOException("Could not open serial port: " + portName);
        }
        in = new BufferedReader(new InputStreamReader(port.getInputStream(), StandardCharsets.US_ASCII));
        out = new BufferedWriter(new OutputStreamWriter(port.getOutputStream(), StandardCharsets.US_ASCII));

        // Let the board settle after the host asserts the serial connection.
        sleep(300);
    }

    /**
     * Send one command and return the firmware's response line.
     *
     * Asynchronous banner lines from the firmware (e.g. "READY:...") are skipped
     * so we return the actual "OK..."/"ERROR..." reply to this command.
     *
     * @return the response line, or {@code null} if nothing arrived in time.
     */
    public String send(String command) throws IOException {
        out.write(command);
        out.write('\n');
        out.flush();

        long deadline = System.currentTimeMillis() + READ_TIMEOUT_MS * 2L;
        while (System.currentTimeMillis() < deadline) {
            String line = readLine();
            if (line == null) continue;          // read timed out, try again
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("OK") || line.startsWith("ERROR")) {
                return line;
            }
            // otherwise it's a banner/other output -> ignore and keep reading
        }
        return null;
    }

    /** Read one '\n'-terminated line, or null on timeout / closed port. */
    private String readLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return sb.toString();
            if (c != '\r') sb.append((char) c);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    public void close() {
        try {
            if (port != null) port.closePort();
        } catch (Exception ignore) {
            // closing best-effort
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
