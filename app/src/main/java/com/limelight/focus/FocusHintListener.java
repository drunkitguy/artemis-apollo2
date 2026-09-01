package com.limelight.focus;

import com.limelight.LimeLog;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;

/**
 * Listens for focus reports from the host, and asks for them.
 *
 * Deliberately narrow. It binds one UDP port, accepts datagrams only from the
 * host currently being streamed from, requires a token this client generated,
 * and the most an accepted datagram can do is change which keyboard is drawn
 * on the second screen. Nothing here can start a session, send input, or reach
 * anything outside the panel.
 *
 * <p>The one thing it sends is a hello, repeated every {@link #HELLO_INTERVAL_MS}
 * to the host it is streaming from. That is what makes the reporter on the PC
 * cheap to leave installed: with nobody streaming it hears no hellos, runs no
 * UI Automation and does nothing at all. It starts watching when the hellos
 * start and stops when they stop, so "runs while I stream, idle when I do not"
 * needs no hook into the host software and no prep command.
 *
 * <p>The hellos go out on the same socket the reports come back on, so the
 * reporter learns where to answer from the datagram itself and neither end has
 * to be told the other's port.
 */
public class FocusHintListener {

    /** Where this client listens for reports. */
    public static final int DEFAULT_PORT = 47996;
    /** Where focus_reporter.exe listens for hellos on the PC. */
    public static final int DEFAULT_HOST_PORT = 47997;

    /**
     * How often the hello repeats.
     *
     * The reporter gives up on a client that has gone quiet for about ten
     * seconds, so this has to be comfortably shorter than that: three of these
     * can be lost - and these are the only datagrams in this design whose loss
     * matters - before the watcher shuts down on someone who is still there.
     */
    public static final long HELLO_INTERVAL_MS = 3000;

    public interface Callback {
        /** Called on the listener thread; the implementation must hop to the UI. */
        void onFocusHint(FocusHint.Report report);

        /**
         * Called about once a second on the listener thread, so a report that
         * never arrives can still time something out.
         */
        void onQuietTick();
    }

    private final int port;
    private final int hostPort;
    private final String token;
    private final String expectedSourceHost;
    private final Callback callback;

    private static volatile boolean announced;

    private volatile boolean running;
    private DatagramSocket socket;
    private Thread thread;

    /**
     * Resolved on the listener thread, never on the caller's.
     *
     * The host is whatever string the launch intent carried, which is usually a
     * literal address but is allowed to be a name, and resolving a name is a
     * network operation. Doing it on the UI thread would be an
     * android.os.NetworkOnMainThreadException in the good case and a stalled
     * frame in the bad one.
     */
    private volatile InetAddress expectedSource;

    // Enough state to answer "why is nothing happening?" without a logcat. The
    // person using this is on a handheld with a game running; asking them to
    // attach a cable to find out that a token is wrong is not a real answer,
    // so the panel shows it instead.
    private volatile boolean bound;
    private volatile String bindError;
    private volatile int datagramsSeen;
    private volatile int datagramsAccepted;
    private volatile int rejectedBySource;
    private volatile int rejectedByToken;
    private volatile String lastSourceAddress;
    private volatile long lastAcceptedAt;

    /**
     * @param expectedSourceHost the host being streamed from, or null to accept
     *                           any source on the network and send no hellos.
     *                           Passing the host is strongly preferred: the
     *                           token is short and only guards a keyboard, so
     *                           the address is the real filter - and without it
     *                           there is nowhere to send the hello, so nothing
     *                           will ever report anything.
     */
    public FocusHintListener(int port, int hostPort, String token,
                             String expectedSourceHost, Callback callback) {
        this.port = port > 0 ? port : DEFAULT_PORT;
        this.hostPort = hostPort > 0 ? hostPort : DEFAULT_HOST_PORT;
        this.token = token;
        this.expectedSourceHost = expectedSourceHost;
        this.callback = callback;
    }

    public synchronized void start() {
        if (running || token == null || token.isEmpty()) {
            return;
        }

        try {
            socket = new DatagramSocket(port);
            socket.setSoTimeout(1000);
        } catch (Exception e) {
            LimeLog.warning("Focus hints: could not bind port " + port + ": " + e);
            bindError = e.getClass().getSimpleName();
            socket = null;
            return;
        }

        bound = true;

        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                resolveSource();
                receiveLoop();
            }
        }, "Focus hint listener");
        thread.setDaemon(true);
        thread.start();

        // Logged once per process rather than once per panel: this is on by
        // default now, and the panel is rebuilt whenever the page changes.
        if (!announced) {
            announced = true;
            LimeLog.info("Focus hints: listening on " + port
                    + (expectedSourceHost != null ? " from " + expectedSourceHost : ""));
        }
    }

    public synchronized void stop() {
        running = false;
        if (socket != null) {
            socket.close();
            socket = null;
        }
        thread = null;
    }

    /**
     * One short line for the second screen, so a report of "it didn't work"
     * can say which of the four things went wrong instead of just that.
     */
    public String describeStatus() {
        if (token == null || token.isEmpty()) {
            return "PC reporter: no token";
        }
        if (!bound) {
            return "PC reporter: port " + port + " busy"
                    + (bindError != null ? " (" + bindError + ")" : "");
        }
        if (datagramsAccepted > 0) {
            long age = System.currentTimeMillis() - lastAcceptedAt;
            if (age < 4000) {
                return "PC reporter: connected";
            }
            return "PC reporter: quiet for " + (age / 1000) + "s";
        }
        if (rejectedByToken > 0) {
            return "PC reporter: token mismatch";
        }
        if (rejectedBySource > 0) {
            return "PC reporter: packets from " + lastSourceAddress
                    + ", expected " + (expectedSource != null ? expectedSource.getHostAddress() : "?");
        }
        if (expectedSourceHost == null) {
            return "PC reporter: no host address";
        }
        if (expectedSource == null) {
            return "PC reporter: cannot resolve " + expectedSourceHost;
        }
        return "PC reporter: waiting on " + port;
    }

    private void resolveSource() {
        if (expectedSourceHost == null || expectedSourceHost.isEmpty()) {
            return;
        }
        try {
            expectedSource = InetAddress.getByName(expectedSourceHost);
        } catch (Exception e) {
            LimeLog.warning("Focus hints: cannot resolve " + expectedSourceHost + ": " + e);
        }
    }

    private void receiveLoop() {
        byte[] buffer = new byte[FocusHint.MAX_BYTES];
        byte[] hello;
        try {
            hello = FocusHint.hello(token).getBytes("US-ASCII");
        } catch (Exception e) {
            hello = null;
        }
        long nextHelloAt = 0;

        while (running) {
            DatagramSocket local = socket;
            if (local == null) {
                break;
            }

            long now = android.os.SystemClock.uptimeMillis();
            if (hello != null && expectedSource != null && now >= nextHelloAt) {
                nextHelloAt = now + HELLO_INTERVAL_MS;
                try {
                    local.send(new DatagramPacket(hello, hello.length, expectedSource, hostPort));
                } catch (Exception e) {
                    // The PC may be asleep, unreachable or simply not running
                    // the reporter. None of that is worth a line per attempt,
                    // and none of it stops reports arriving later.
                }
            }

            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                local.receive(packet);
            } catch (SocketTimeoutException e) {
                // Expected. The timeout exists so stop() is noticed promptly,
                // and so the hello above keeps going out while nothing answers.
                if (callback != null) {
                    callback.onQuietTick();
                }
                continue;
            } catch (Exception e) {
                if (running) {
                    LimeLog.warning("Focus hints: receive failed: " + e);
                }
                break;
            }

            datagramsSeen++;
            if (packet.getAddress() != null) {
                lastSourceAddress = packet.getAddress().getHostAddress();
            }

            if (expectedSource != null && !expectedSource.equals(packet.getAddress())) {
                // Not from the machine we are streaming from.
                rejectedBySource++;
                continue;
            }

            String payload;
            try {
                payload = new String(packet.getData(), packet.getOffset(), packet.getLength(), "US-ASCII");
            } catch (Exception e) {
                continue;
            }

            FocusHint.Report report = FocusHint.parse(payload, token);
            if (report == null) {
                rejectedByToken++;
                continue;
            }

            datagramsAccepted++;
            lastAcceptedAt = System.currentTimeMillis();
            if (callback != null) {
                callback.onFocusHint(report);
            }
        }
    }
}
