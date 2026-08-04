# Car-Mounted Automatic Pothole Detector

EECS 1021-E final project. A Raspberry Pi Pico 2 W reads an ultrasonic sensor
pointed down at the road; a Java (Swing) app on the computer reads the distance
stream over USB serial, detects potholes in real time, draws a live graph, logs
events, and drives LED + buzzer feedback.

The Java app reads the sensor over USB serial: pick the Pico's serial port from
the dropdown and press Start.

---

**Author:** Eric Xihuan Shi  
**Student ID:** 222476709  
**Course:** EECS 1021-E (SU) — Instructor: Navid Mohaghegh  
**Date:** ________________

© 2026 Eric Xihuan Shi. Created for EECS 1021-E coursework. All rights reserved.

---

## System architecture

```
 ULTRASONIC SENSOR        PICO 2 W (firmware)            JAVA APP (Swing)
 +--------------+   trig  +------------------+  UART /   +-------------------------+
 |   HC-SR04    |<--------|  pulse + timing  |  USB      | PicoConnection (driver) |
 |  (distance)  |-------->|  ULTRASONIC_READ |<========> | PicoSerialSource        |
 +--------------+   echo  |  LED / buzzer    |  115200   |   -> PotholeAnalyzer    |
                          +---------+--------+  8N1      |   -> LiveChartPanel/log |
                                    | GPIO              |   -> PicoFeedback        |
                              LED + BUZZER  <===========+   (LED/buzzer commands)  |
                                                           +-------------------------+
```

**Layer separation (a grading requirement):**
- *Hardware* — sensor + actuators + wiring.
- *Embedded* — `pothole_detector_firmware.ino`: drives the sensor (trigger pulse +
  echo timing), drives the LED/buzzer GPIO, speaks the serial protocol.
- *Software* — the Java app: a custom serial **driver** (`PicoConnection`), a
  data-**source** abstraction (`DistanceSource`), detection, visualization, logging.
- *Communication* — line-based ASCII command/response over UART (see below).

---

## Build and run

1. **Upload the firmware:** open `firmware/pothole_detector_firmware/` in the
   Arduino IDE and upload it to the Raspberry Pi Pico 2 W.
2. **Wire it up** (see the table below).
3. **Open the Java app in IntelliJ IDEA:**
   - Open this `PotholeDetector` folder as a project.
   - Right-click `src` -> **Mark Directory as -> Sources Root**.
   - Add the serial library: **File -> Project Structure -> Libraries -> + ->**
     select `lib/jSerialComm-2.11.0.jar`.
4. **Run** `Main.java`, click **Refresh** to list serial ports, pick the Pico's
   port from **Source** (on macOS it looks like `cu.usbmodemXXXX`), press **Start**.

Command line:
```
javac -cp lib/jSerialComm-2.11.0.jar -d out src/*.java
java  -cp "out:lib/jSerialComm-2.11.0.jar" Main      # Windows: use ';' instead of ':'
```

After you press Start the app calibrates a baseline (~1.5 s), then detects
potholes live: the graph spikes, the event log records each one, and the LED +
buzzer fire. Use the **Threshold** slider and the **LED / Buzzer feedback**
checkboxes to tune behaviour; the **Inject pothole** button forces a detection for
demos.

### Wiring

| Component | Pin | Pico pin | Notes |
|-----------|-----|----------|-------|
| HC-SR04 VCC | → | **VBUS** (pin 40) | sensor needs 5 V |
| HC-SR04 GND | → | GND | |
| HC-SR04 TRIG | → | **GP3** | 3.3 V trigger is fine |
| HC-SR04 ECHO | → | **GP2** via divider | **5 V — must divide down** |
| LED (+) | → 220–330 Ω → | **GP15** | |
| LED (−) | → | GND | |
| Buzzer signal (S/I/O) | → | **GP14** | passive buzzer (tone signal) |
| Buzzer −/GND | → | GND | |
| Buzzer + (if 3-pin) | → | 3V3 | some modules have a VCC pin |
| Servo signal (orange) | → | **GP16** | severity gauge (optional) |
| Servo power (red) | → | **external 5 V** | NOT the Pico — see warning |
| Servo GND (brown) | → | GND (shared) | common ground with Pico |

> **Servo power warning:** an SG90 can pull 500-700 mA in spikes and inject
> noise that browns out / resets the Pico. Power the servo from a **separate 5 V
> supply** (battery pack or external 5 V), share the ground with the Pico, and run
> only the **signal** wire to GP16. Never the servo's red wire to VBUS/VSYS.

ECHO voltage divider (5 V -> ~3.3 V):
```
ECHO --[10 kΩ]--+-- GP2
                |
           [20 kΩ = 2×10 kΩ]
                |
               GND
```
`Vout = 5 V × 20/(10+20) = 3.33 V`. Built from three 10 kΩ resistors.
If you only have two 10 kΩ, use 10 kΩ + 10 kΩ -> 2.5 V; still safe and reads as a
logic HIGH, just with less margin.

> The plain HC-SR04 runs at 5 V and its ECHO outputs 5 V; the Pico is 3.3 V-only.
> The divider protects GP2. (A 3.3 V HC-SR04P/RCWL-1601 would not need it.)
> The LED uses the separate 220 Ω resistor — don't put it in the divider.

> **Buzzer:** this project uses a **passive** buzzer (HW-508). A passive buzzer
> has no internal oscillator, so the firmware drives it with `tone()`/`noTone()`
> via `BUZZER_ON`/`BUZZER_OFF` (default 2500 Hz). Connect the signal pin to GP14.

---

## Communication protocol (UART)

- **Transport:** USB CDC serial, **115200 baud, 8N1**.
- **Framing:** one command per line, terminated by `\n` (ASCII).
- **Request:** `COMMAND` or `COMMAND,arg1,arg2` (case-insensitive).
- **Response:** exactly one line, `OK`, `OK:<data>`, or `ERROR:<reason>`.
- **Pattern:** strict request/response (the host sends, then reads one reply).

Commands used by this project:
| Command | Reply | Meaning |
|---------|-------|---------|
| `ULTRASONIC_ATTACH,3,2` | `OK:ULTRASONIC_ATTACHED,3,2` | set TRIG=GP3, ECHO=GP2 |
| `ULTRASONIC_READ` | `OK:<cm>` or `ERROR:TIMEOUT` | one distance measurement |
| `GPIO_MODE,15,OUTPUT` | `OK` | configure LED pin |
| `GPIO_HIGH,15` / `GPIO_LOW,15` | `OK` | LED on / off |
| `BUZZER_ATTACH,14` | `OK:BUZZER_ATTACHED,14` | set passive buzzer pin |
| `BUZZER_ON` / `BUZZER_OFF` | `OK` | tone on / off (passive buzzer) |
| `SERVO_ATTACH,16` | `OK:SERVO_ATTACHED,16` | attach severity-gauge servo |
| `SERVO_WRITE,<angle>` | `OK:SERVO_POSITION,<angle>` | swing needle 0-180 deg |

---

## Performance metrics (report section 3.7)

The status bar shows live **rate / latency / noise**, and the **Benchmark** button
captures 100 samples and logs a summary you can paste into the report:

- **Sample rate (Hz)** — how many distance readings/second the pipeline sustains.
- **Latency (ms)** — round-trip time of one `ULTRASONIC_READ` (send → sensor ping
  → reply), measured on the serial worker thread.
- **Noise (± cm)** — standard deviation of resting readings; a proxy for sensor
  precision / repeatability. Feeds the choice of detection threshold.

Detection is also gated by a **calibration window**: the baseline is a 25-sample
moving average, and detection stays off (status shows `Calibrating n/25`) until it
fills (~1.5 s), so warm-up transients don't fire false positives.

## Actuators / feedback

| Output | Pin | Behaviour |
|--------|-----|-----------|
| LED | GP15 | flashes on each detection |
| Passive buzzer | GP14 | short tone on each detection |
| Severity-gauge servo | GP16 | needle swings proportional to pothole depth, then returns to rest |

Each is independently toggled by a checkbox. Depth-to-angle mapping:
`angle = min(180, depth / 10 cm × 180)`.

## Detection algorithm

1. Keep a sliding window of recent **normal** readings; their average is the
   **baseline** (expected ride height).
2. For each reading, compute its deviation above the baseline.
3. If deviation > **threshold**, the road dropped away -> pothole.
4. Anomalous readings are **excluded** from the window so one deep pothole can't
   drag the baseline up and mask the next one.
5. A state flag fires **one** event on entering a pothole (debounce).

---

## File map

| File | Layer | Role |
|------|-------|------|
| `firmware/.../pothole_detector_firmware.ino` | Embedded | Sensor driver (pulse/timing), actuators, serial protocol |
| `src/PicoConnection.java` | Software (driver) | Low-level UART send/receive |
| `src/DistanceSource.java` | Software | Interface: "something that produces readings" |
| `src/PicoSerialSource.java` | Software | Reads the sensor over serial, drives actuators |
| `src/PicoFeedback.java` | Software | Interface: LED/buzzer actuator output |
| `src/PotholeAnalyzer.java` | Software | Moving-average baseline + threshold detection |
| `src/LiveChartPanel.java` | Software | Custom-painted live graph |
| `src/MainFrame.java` | Software | Window, controls, log, wiring |
| `src/Main.java` | Software | Entry point |

---

## Dependencies
- Java 8+ (Swing, built in).
- [jSerialComm 2.11.0](https://github.com/Fazecast/jSerialComm) — `lib/jSerialComm-2.11.0.jar` (serial I/O).

## Skills demonstrated
Custom UART driver development · serial protocol parsing · real-time filtering &
anomaly detection · multithreading (single serial worker + Swing event thread) ·
live GUI charting · event-driven UI · clean hardware/software layer separation ·
embedded C/C++ with GPIO pulse timing.
