package com.limelight.binding.video;

import android.content.Context;

import com.limelight.LimeLog;
import com.limelight.nvstream.jni.MoonBridge;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Writes the input round-trip probe samples to their own CSV at session end.
 *
 * <h3>Why a separate file rather than columns on the frame trace</h3>
 * There is no join key. The frame trace has one row per frame, keyed by
 * frame_id; probes are sampled at up to 20 per second and keyed by their own
 * sequence number. Nothing relates a given input event to a given frame — the
 * host does not tell us which frame an input influenced, and inferring it from
 * timing would be a guess dressed as a measurement. Padding probes into the
 * frame trace would produce a file that is almost entirely empty in either the
 * frame columns or the input columns depending on the row, which is worse to
 * read and invites exactly that false join.
 *
 * <p>Both files go to the same directory and through the same export path, so
 * the user retrieves them the same way and neither needs adb.
 *
 * <h3>What the columns mean</h3>
 * Every timestamp is client monotonic microseconds; host timestamps have
 * already been converted with the clock offset estimate. A blank means not
 * recorded, never zero — a zero would read as an instantaneous stage.
 *
 * <p>The derived columns are the attribution the whole exercise exists for:
 * <ul>
 *   <li>{@code event_to_send_us} — kernel saw it, to the packet leaving us.
 *       This is Android input plumbing AND our own handling combined. Splitting
 *       them needs a handler-entry stamp that does not exist yet, so it is
 *       reported as one term rather than guessed apart.</li>
 *   <li>{@code net_to_host_us} — send, to the host reading it.</li>
 *   <li>{@code host_inject_us} — host reading it, to host injecting it.</li>
 *   <li>{@code return_path_us} — host injection, to the echo arriving.</li>
 *   <li>{@code round_trip_us} — send to echo, the number a user would feel
 *       half of.</li>
 * </ul>
 */
public final class InputProbeTraceWriter {

    public static final String FILE_PREFIX = "apollo2-input-trace-";
    private static final String FILE_SUFFIX = ".csv";

    /** 8 longs per sample; matches the JNI packing. */
    private static final int LONGS_PER_SAMPLE = 8;
    private static final int MAX_SAMPLES = 256;

    private static final long FLAG_BATCH_DELAYED = 0x1;
    private static final long FLAG_HOST_NO_INPUT = 0x2;
    private static final long FLAG_COMPLETE = 0x4;

    private InputProbeTraceWriter() {}

    /**
     * Drains the probe ring and writes a CSV. Returns the file, or null when
     * there was nothing to write.
     *
     * <p>Called once at session end, after the streaming threads have stopped.
     * There is no per-event disk I/O anywhere in this feature: samples live in
     * a fixed native ring until this runs.
     */
    public static File flush(Context context, String metadataHeader) {
        long[] raw = new long[MAX_SAMPLES * LONGS_PER_SAMPLE];
        int count = MoonBridge.drainInputProbes(raw);

        if (count <= 0) {
            LimeLog.info("Input probe: no samples recorded, not writing a CSV");
            return null;
        }

        int[] stats = MoonBridge.getInputProbeStats();

        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File out = new File(LatencyTraceRecorder.getTraceDirectory(context),
                FILE_PREFIX + stamp + FILE_SUFFIX);

        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8),
                    1 << 14);

            writer.write("# apollo2_input_trace v1\n");
            if (metadataHeader != null) {
                for (String line : metadataHeader.split("\n")) {
                    writer.write("# ");
                    writer.write(line);
                    writer.write('\n');
                }
            }
            if (stats != null && stats.length >= 4) {
                writer.write("# probes_sent=" + stats[0]
                        + " suppressed_by_rate_limit=" + stats[1]
                        + " echoes_matched=" + stats[2]
                        + " clock_conversion_failures=" + stats[3] + "\n");
            }
            writer.write("# clock=CLOCK_MONOTONIC units=microseconds\n");
            writer.write("# blank means not recorded; never read a blank as zero\n");
            writer.write("# client_event_time is the KERNEL event time from"
                    + " MotionEvent/KeyEvent.getEventTime(), which on API 33 has"
                    + " MILLISECOND resolution, so event_to_send_us carries about"
                    + " 1000 us of quantisation. Do not read differences below that as real.\n");
            writer.write("# batch_delayed=1 means the input batching limiter actually slept"
                    + " before this send. If this is 0 everywhere, the limiter is not"
                    + " contributing to input latency on this device.\n");
            writer.write("# host columns are blank when the host did not echo, or when the"
                    + " clock offset could not convert its timestamp\n");
            writer.write("# host_inject blank with host_no_input=1 means the host echoed but"
                    + " associated no input packet with the probe\n");

            writer.write("seq,batch_delayed,host_no_input,complete,"
                    + "t_client_event_us,t_client_send_us,t_host_recv_us,t_host_inject_us,t_client_echo_us,"
                    + "event_to_send_us,net_to_host_us,host_inject_us,"
                    + "return_path_us,round_trip_us\n");

            StringBuilder sb = new StringBuilder(256);
            for (int i = 0; i < count; i++) {
                int b = i * LONGS_PER_SAMPLE;
                long seq = raw[b];
                long eventUs = raw[b + 1];
                long sendUs = raw[b + 2];
                long echoUs = raw[b + 3];
                long hostRecvUs = raw[b + 4];
                long hostInjectUs = raw[b + 5];
                long flags = raw[b + 6];

                sb.setLength(0);
                sb.append(seq).append(',');
                sb.append((flags & FLAG_BATCH_DELAYED) != 0 ? 1 : 0).append(',');
                sb.append((flags & FLAG_HOST_NO_INPUT) != 0 ? 1 : 0).append(',');
                sb.append((flags & FLAG_COMPLETE) != 0 ? 1 : 0).append(',');

                appendTime(sb, eventUs);
                appendTime(sb, sendUs);
                appendTime(sb, hostRecvUs);
                appendTime(sb, hostInjectUs);
                appendTime(sb, echoUs);

                appendDelta(sb, eventUs, sendUs);
                appendDelta(sb, sendUs, hostRecvUs);
                appendDelta(sb, hostRecvUs, hostInjectUs);
                appendDelta(sb, hostInjectUs, echoUs);
                appendDelta(sb, sendUs, echoUs);

                sb.setLength(sb.length() - 1);
                sb.append('\n');
                writer.write(sb.toString());
            }

            writer.flush();
            LimeLog.info("Input probe: wrote " + count + " samples to " + out.getAbsolutePath());
            return out;
        }
        catch (Exception e) {
            LimeLog.warning("Input probe: failed to write CSV: " + e);
            return null;
        }
        finally {
            if (writer != null) {
                try {
                    writer.close();
                }
                catch (Exception ignored) {
                    // Nothing useful to do; the file is either readable or not.
                }
            }
        }
    }

    private static void appendTime(StringBuilder sb, long us) {
        if (us != 0) {
            sb.append(us);
        }
        sb.append(',');
    }

    private static void appendDelta(StringBuilder sb, long fromUs, long toUs) {
        if (fromUs != 0 && toUs != 0 && toUs >= fromUs) {
            sb.append(toUs - fromUs);
        }
        sb.append(',');
    }
}
