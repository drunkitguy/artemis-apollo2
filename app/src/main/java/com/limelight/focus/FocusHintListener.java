package com.limelight.focus;

import com.limelight.LimeLog;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;

/**
 * Listens for focus reports from the host.
 *
 * Deliberately narrow. It binds one UDP port, accepts datagrams only from the
 * host currently being streamed from, requires a token this client generated,
 * and the most an accepted datagram can do is change which keyboard is drawn
 * on the second screen. Nothing here can start a session, send input, or reach
 * anything outside the panel.
 */
public class FocusHintListener {

    public static final int DEFAULT_PORT = 47996;

    public interface Callback {
        /** Called on the listener thread; the implementation must hop to the UI. */
        void onFocusHint(FocusHint.State state);
    }

    private final int port;
    private final String token;
    private final InetAddress expectedSource;
    private final Callback callback;

    private volatile boolean running;
    private DatagramSocket socket;
    private Thread thread;

    /**
     * @param expectedSource the host being streamed from, or null to accept any
     *                       source on the network. Passing the host is strongly
     *                       preferred: the token is short and only guards a
     *                       keyboard, so the address is the real filter.
     */
    public FocusHintListener(int port, String token, InetAddress expectedSource, Callback callback) {
        this.port = port > 0 ? port : DEFAULT_PORT;
        this.token = token;
        this.expectedSource = expectedSource;
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
            socket = null;
            return;
        }

        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                receiveLoop();
            }
        }, "Focus hint listener");
        thread.setDaemon(true);
        thread.start();

        LimeLog.info("Focus hints: listening on " + port
                + (expectedSource != null ? " from " + expectedSource.getHostAddress() : ""));
    }

    public synchronized void stop() {
        running = false;
        if (socket != null) {
            socket.close();
            socket = null;
        }
        thread = null;
    }

    private void receiveLoop() {
        byte[] buffer = new byte[FocusHint.MAX_BYTES];

        while (running) {
            DatagramSocket local = socket;
            if (local == null) {
                break;
            }

            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                local.receive(packet);
            } catch (SocketTimeoutException e) {
                // Expected. The timeout exists so stop() is noticed promptly.
                continue;
            } catch (Exception e) {
                if (running) {
                    LimeLog.warning("Focus hints: receive failed: " + e);
                }
                break;
            }

            if (expectedSource != null && !expectedSource.equals(packet.getAddress())) {
                // Not from the machine we are streaming from.
                continue;
            }

            String payload;
            try {
                payload = new String(packet.getData(), packet.getOffset(), packet.getLength(), "US-ASCII");
            } catch (Exception e) {
                continue;
            }

            FocusHint.State state = FocusHint.parse(payload, token);
            if (state != null && callback != null) {
                callback.onFocusHint(state);
            }
        }
    }
}
