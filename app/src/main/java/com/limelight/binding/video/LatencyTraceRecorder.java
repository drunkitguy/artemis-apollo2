package com.limelight.binding.video;

import android.content.Context;
import android.os.Build;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;
import com.limelight.nvstream.jni.MoonBridge;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/*
 * NOTE ON THE FLUSH PRECONDITION, because it has been got wrong twice:
 * the flush must run after ALL THREE producers below have stopped, not just the
 * two that MediaCodecDecoderRenderer itself owns. The video receive thread is a
 * producer and is joined by stopVideoStream() in the native layer, not by the
 * renderer. That is why flushToCsv() is called from cleanup() and not stop().
 */

/**
 * In-memory per-frame latency trace ring buffer for the Apollo 2.0 measurement
 * harness (SPEC.md §3).
 *
 * <h3>Why a ring buffer</h3>
 * Instrumentation that perturbs the thing it measures is worthless, so nothing
 * here touches the filesystem while the stream is running. Rows are written into
 * preallocated primitive arrays and the whole buffer is serialised to CSV once,
 * at session end, from {@link #flushToCsv}.
 *
 * <p>Parallel primitive arrays rather than an object per row: at 120 fps a
 * 20 minute session is 144 000 rows, and one small object per row would be
 * 144 000 allocations feeding straight into GC pressure on the render path.
 *
 * <h3>Thread ownership and synchronisation</h3>
 * <ul>
 *   <li>The <b>video receive thread</b> (native, via
 *       {@code submitTraceTimestamps} then {@code submitDecodeUnit}) calls
 *       {@link #beginFrame} and {@link #recordDecodeSubmit}.</li>
 *   <li>The <b>renderer thread</b> calls {@link #recordDecodeComplete},
 *       {@link #recordPresentCalled} and {@link #abandonFrame}.</li>
 *   <li>The <b>Choreographer monitor thread</b> calls
 *       {@link #recordPresentVsync}.</li>
 *   <li>The <b>connection teardown thread</b> calls {@link #flushToCsv}, from
 *       {@code MediaCodecDecoderRenderer.cleanup()}.</li>
 * </ul>
 * All mutating methods synchronise on {@code this}. The critical sections are a
 * bounded number of array stores with no allocation, no I/O and no nested locks,
 * so they cannot block a producer for a measurable time and cannot deadlock.
 *
 * <p><b>Flush precondition.</b> {@link #flushToCsv} must be called only after
 * <em>all three</em> producer threads above have stopped. The single call site
 * is {@code MediaCodecDecoderRenderer.cleanup()}, which
 * {@code stopVideoStream()} invokes as its last statement:
 * <ul>
 *   <li>the renderer thread was joined, and the vsync monitor stopped, earlier
 *       in {@code stop()};</li>
 *   <li>the <b>video receive thread</b> is interrupted and joined by
 *       {@code stopVideoStream()} itself, between {@code stop()} and
 *       {@code cleanup()} — which is precisely why the flush cannot live in
 *       {@code stop()};</li>
 *   <li>and it is still before {@code destroyControlStream()}, two stages later
 *       in {@code LiStopConnection()}, which deletes the native clock-sync mutex
 *       the metadata block reads through.</li>
 * </ul>
 *
 * <h3>Buffer full behaviour</h3>
 * This is a bounded recorder, not a true overwriting ring: once
 * {@link #CAPACITY} rows are written it stops recording and counts the
 * overflow. Overwriting would silently discard the beginning of the session and
 * produce a CSV whose row order does not match wall time, which is worse than a
 * short trace with an honest dropped-row count in the metadata.
 */
public class LatencyTraceRecorder {
    /**
     * 262 144 rows. At 120 fps that is roughly 36 minutes of continuous capture.
     */
    public static final int CAPACITY = 262144;

    /** Filename shape, shared with the exporter so the two cannot drift apart. */
    public static final String FILE_PREFIX = "apollo2-latency-trace-";
    public static final String FILE_SUFFIX = ".csv";

    /**
     * Directory traces are written to.
     *
     * <p>External app-specific storage, which needs no permission on any API
     * level. Note this is <em>not</em> reachable over MTP on Android 11+, nor
     * from other apps — see {@code LatencyTraceExporter}, which exists precisely
     * because a file written here is otherwise unreachable on a device without
     * working adb.
     */
    public static File getTraceDirectory(Context context) {
        File dir = context.getExternalFilesDir(null);
        return dir != null ? dir : context.getFilesDir();
    }

    /** Row flag bits, mirrored into the CSV so a consumer can filter. */
    public static final int FLAG_HOST_VALID = 0x01;
    public static final int FLAG_LAST_PACKET_RX_VALID = 0x02;
    public static final int FLAG_DECODE_SUBMIT = 0x04;
    public static final int FLAG_DECODE_COMPLETE = 0x08;
    public static final int FLAG_PRESENT_CALLED = 0x10;
    public static final int FLAG_PRESENT_VSYNC = 0x20;
    public static final int FLAG_FRAME_RENDERED = 0x40;
    /** Set when a stage timestamp went backwards relative to the previous stage. */
    public static final int FLAG_NEGATIVE_DELTA = 0x80;

    /**
     * Host per-stage validity, as delivered in the upper bits of traceFlags.
     * Stored in its own column so a consumer can tell "the host did not measure
     * this stage on this frame" from "the stage took zero time" — zero is a
     * legal monotonic timestamp, so the value alone cannot distinguish them.
     */
    private final int[] hostStampMask = new int[CAPACITY];

    // t_tx_pipeline_entry_us is deliberately NOT called t_first_packet_tx_us.
    // The host stamps it before FEC parity, per-shard encryption and pacing, so
    // it is not a transmit time and one-way delay must not be computed from it.
    private static final String CSV_HEADER =
            "row_seq,frame_id,frame_type,flags,host_stamp_mask," +
            "t_capture_requested_us,t_capture_complete_us," +
            "t_encode_submit_us,t_encode_complete_us,t_tx_pipeline_entry_us," +
            "t_last_packet_rx_us,t_decode_submit_us,t_decode_complete_us," +
            "t_present_called_us,t_frame_rendered_us,t_present_vsync_us,vsync_lag_us";

    // Column storage. Index i holds row i. All timestamps are monotonic
    // microseconds on the client epoch; 0 means "not recorded", and the
    // corresponding flag bit is the authoritative signal.
    private final long[] rowSeq = new long[CAPACITY];
    private final int[] frameId = new int[CAPACITY];
    private final int[] frameType = new int[CAPACITY];
    private final int[] flags = new int[CAPACITY];
    private final long[] tCaptureRequested = new long[CAPACITY];
    private final long[] tCaptureComplete = new long[CAPACITY];
    private final long[] tEncodeSubmit = new long[CAPACITY];
    private final long[] tEncodeComplete = new long[CAPACITY];
    private final long[] tTxPipelineEntry = new long[CAPACITY];
    private final long[] tLastPacketRx = new long[CAPACITY];
    private final long[] tDecodeSubmit = new long[CAPACITY];
    private final long[] tDecodeComplete = new long[CAPACITY];
    private final long[] tPresentCalled = new long[CAPACITY];
    private final long[] tFrameRendered = new long[CAPACITY];
    private final long[] tPresentVsync = new long[CAPACITY];

    private int rowCount;
    private long droppedRows;
    private long nextRowSeq;

    // Diagnostics surfaced in the metadata block. A hole in the data must be
    // countable, not invisible.
    private long ptsEvictions;
    private long ptsInsertFailures;
    private long negativeDeltaRows;
    private long vsyncBeforePresentCalled;
    private long unmatchedVsyncs;

    /**
     * Maps a decoder presentation timestamp (microseconds) to the row index for
     * that frame. Frames can be dropped, so a positional assumption would
     * misattribute every subsequent row.
     *
     * <p>Open addressed with a <b>bounded</b> probe length. An unbounded probe
     * degrades into a full-table scan once the table fills, and this table is
     * read up to three times per frame under a lock the video receive thread
     * also takes — exactly the instrumentation-perturbs-the-measurement failure
     * SPEC.md §3 prohibits. With a bounded probe every operation is O(1) in the
     * worst case; on insert collision the oldest entry in the probe window is
     * evicted and counted.
     */
    private static final int PTS_TABLE_SIZE = 4096;
    private static final int PTS_MAX_PROBE = 8;
    private static final int PTS_MASK = PTS_TABLE_SIZE - 1;
    private final long[] ptsKeys = new long[PTS_TABLE_SIZE];
    private final int[] ptsRows = new int[PTS_TABLE_SIZE];
    private final long[] ptsInsertSeq = new long[PTS_TABLE_SIZE];
    private long ptsSeqCounter;

    /** Row index of the frame currently between beginFrame() and decode submit. */
    private int pendingRow = -1;

    // Static session metadata, captured once at session start off the render path.
    private final Object metaLock = new Object();
    private String codec = "?";
    private int metaWidth;
    private int metaHeight;
    private float metaFps;
    private int metaBitrateKbps;
    private String framePacing = "?";
    private boolean ultraLowLatency;
    private String decoderName = "?";
    private String networkPath = "?";
    private String rendererDiagnostics = "";

    public LatencyTraceRecorder() {
        for (int i = 0; i < PTS_TABLE_SIZE; i++) {
            ptsRows[i] = -1;
        }
    }

    /**
     * Records the static half of the SPEC.md §4.6 metadata block. Called once at
     * session start, off the render path. The dynamic half (clock sync state) is
     * sampled at flush time.
     */
    public void setSessionInfo(String codec, int width, int height, float fps, int bitrateKbps,
                               String framePacing, boolean ultraLowLatency,
                               String decoderName, String networkPath) {
        synchronized (metaLock) {
            this.codec = codec;
            this.metaWidth = width;
            this.metaHeight = height;
            this.metaFps = fps;
            this.metaBitrateKbps = bitrateKbps;
            this.framePacing = framePacing;
            this.ultraLowLatency = ultraLowLatency;
            this.decoderName = decoderName;
            this.networkPath = networkPath;
        }
    }

    /**
     * Fills in the codec and decoder, which are only known once the format has
     * been negotiated. Called from {@code MediaCodecDecoderRenderer.stop()}
     * immediately before the flush.
     */
    public void setDecoderInfo(String codec, String decoderName) {
        synchronized (metaLock) {
            this.codec = codec;
            this.decoderName = decoderName;
        }
    }

    /**
     * Free-form renderer diagnostics to embed in the metadata block, as
     * {@code key=value} pairs separated by spaces.
     *
     * <p>This exists because the CSV is the only diagnostic channel that reaches
     * the user on a device without working adb: anything written to logcat is
     * unreachable there. Anything a reader would need in order to interpret a
     * run, or to tell whether a self-tuning mechanism actually engaged, belongs
     * here rather than in a log line.
     *
     * <p>Called once at flush time, off the streaming path.
     */
    public void setRendererDiagnostics(String diagnostics) {
        synchronized (metaLock) {
            this.rendererDiagnostics = diagnostics == null ? "" : diagnostics;
        }
    }

    /**
     * Starts a row for a frame, recording the host half of the trace.
     * Called on the video receive thread.
     *
     * @param traceFlags bit 0 = host timestamps valid, bit 1 = lastPacketRx valid
     */
    public synchronized void beginFrame(int frameNumber, int type, int traceFlags,
                                        long lastPacketRxUs,
                                        long captureRequestedUs, long captureCompleteUs,
                                        long encodeSubmitUs, long encodeCompleteUs,
                                        long txPipelineEntryUs) {
        if (rowCount >= CAPACITY) {
            droppedRows++;
            pendingRow = -1;
            return;
        }

        boolean hostValid = (traceFlags & 0x01) != 0;
        boolean rxValid = (traceFlags & 0x02) != 0;

        int row = rowCount++;
        rowSeq[row] = nextRowSeq++;
        frameId[row] = frameNumber;
        frameType[row] = type;
        flags[row] = 0;
        tLastPacketRx[row] = 0;
        tDecodeSubmit[row] = 0;
        tDecodeComplete[row] = 0;
        tPresentCalled[row] = 0;
        tFrameRendered[row] = 0;
        tPresentVsync[row] = 0;

        if (rxValid) {
            tLastPacketRx[row] = lastPacketRxUs;
            flags[row] |= FLAG_LAST_PACKET_RX_VALID;
        }
        // Always clear first: a stage the host did not measure must not inherit
        // whatever was in the array, and must be emitted blank rather than 0.
        tCaptureRequested[row] = 0;
        tCaptureComplete[row] = 0;
        tEncodeSubmit[row] = 0;
        tEncodeComplete[row] = 0;
        tTxPipelineEntry[row] = 0;
        hostStampMask[row] = 0;

        if (hostValid) {
            int stampMask = (traceFlags >>> MoonBridge.TRACE_HOST_STAMP_MASK_SHIFT) & 0xFF;
            hostStampMask[row] = stampMask;
            flags[row] |= FLAG_HOST_VALID;

            // Per stage, not all-or-nothing. The host legitimately cannot stamp
            // capture-requested on the synchronous capture path, or
            // capture-complete on a repeated static-content frame.
            if ((stampMask & MoonBridge.TRACE_STAMP_CAPTURE_REQUESTED) != 0) {
                tCaptureRequested[row] = captureRequestedUs;
            }
            if ((stampMask & MoonBridge.TRACE_STAMP_CAPTURE_COMPLETE) != 0) {
                tCaptureComplete[row] = captureCompleteUs;
            }
            if ((stampMask & MoonBridge.TRACE_STAMP_ENCODE_SUBMIT) != 0) {
                tEncodeSubmit[row] = encodeSubmitUs;
            }
            if ((stampMask & MoonBridge.TRACE_STAMP_ENCODE_COMPLETE) != 0) {
                tEncodeComplete[row] = encodeCompleteUs;
            }
            if ((stampMask & MoonBridge.TRACE_STAMP_TX_PIPELINE_ENTRY) != 0) {
                tTxPipelineEntry[row] = txPipelineEntryUs;
            }
        }
        pendingRow = row;
    }

    /**
     * Records t_decode_submit and binds the decoder PTS to this row.
     * Called on the video receive thread, immediately after queueInputBuffer().
     */
    public synchronized void recordDecodeSubmit(long presentationTimeUs, long nowUs) {
        int row = pendingRow;
        pendingRow = -1;
        if (row < 0) {
            return;
        }
        tDecodeSubmit[row] = nowUs;
        flags[row] |= FLAG_DECODE_SUBMIT;
        if ((flags[row] & FLAG_LAST_PACKET_RX_VALID) != 0 && nowUs < tLastPacketRx[row]) {
            markNegative(row);
        }
        putPtsRow(presentationTimeUs, row);
    }

    /** Records t_decode_complete. Called on the renderer thread. */
    public synchronized void recordDecodeComplete(long presentationTimeUs, long nowUs) {
        int row = lookupPtsRow(presentationTimeUs);
        if (row < 0) {
            return;
        }
        // A buffer is dequeued once; if it somehow repeats, keep the first.
        if ((flags[row] & FLAG_DECODE_COMPLETE) != 0) {
            return;
        }
        tDecodeComplete[row] = nowUs;
        flags[row] |= FLAG_DECODE_COMPLETE;
        if (nowUs < tDecodeSubmit[row]) {
            markNegative(row);
        }
    }

    /** Records t_present_called. Called on the renderer thread. */
    public synchronized void recordPresentCalled(long presentationTimeUs, long nowUs) {
        int row = lookupPtsRow(presentationTimeUs);
        if (row < 0) {
            return;
        }
        if ((flags[row] & FLAG_PRESENT_CALLED) != 0) {
            return;
        }
        tPresentCalled[row] = nowUs;
        flags[row] |= FLAG_PRESENT_CALLED;
        if (nowUs < tDecodeComplete[row]) {
            markNegative(row);
        }
    }

    /**
     * Records t_present_vsync from a real vsync signal, together with the
     * MediaCodec render timestamp it was derived from, and releases the PTS
     * mapping. Called on the Choreographer monitor thread.
     *
     * <p>SPEC.md §3 is explicit that this must come from a vsync callback and not
     * from the return of a present call; see the call site in
     * {@code MediaCodecDecoderRenderer.TraceVsyncMonitor}.
     *
     * <p>{@code renderedUs} is recorded as its own column precisely so the
     * remaining attribution uncertainty is visible in the data:
     * {@code vsync_lag_us = t_present_vsync - t_frame_rendered} shows how long
     * after the surface render the matched vsync fell. A consumer can bucket or
     * reject rows whose lag exceeds one refresh interval instead of silently
     * absorbing a frame of apparatus noise into a latency figure.
     */
    public synchronized void recordPresentVsync(long presentationTimeUs, long renderedUs, long vsyncUs) {
        int row = lookupPtsRow(presentationTimeUs);
        if (row < 0) {
            unmatchedVsyncs++;
            return;
        }
        tFrameRendered[row] = renderedUs;
        tPresentVsync[row] = vsyncUs;
        flags[row] |= FLAG_FRAME_RENDERED | FLAG_PRESENT_VSYNC;

        // A vsync earlier than the present call is physically impossible and
        // means the correlation mismatched. Flag and count it rather than emit
        // it as if it were a measurement.
        if ((flags[row] & FLAG_PRESENT_CALLED) != 0 && vsyncUs < tPresentCalled[row]) {
            vsyncBeforePresentCalled++;
            markNegative(row);
        }
        removePtsRow(presentationTimeUs);
    }

    /**
     * Drops a PTS binding for a frame that will never be presented, so the table
     * does not fill with entries for discarded output buffers.
     * Called on the renderer thread.
     */
    public synchronized void abandonFrame(long presentationTimeUs) {
        removePtsRow(presentationTimeUs);
    }

    private void markNegative(int row) {
        if ((flags[row] & FLAG_NEGATIVE_DELTA) == 0) {
            flags[row] |= FLAG_NEGATIVE_DELTA;
            negativeDeltaRows++;
        }
    }

    public synchronized int getRowCount() {
        return rowCount;
    }

    /**
     * Serialises the buffer to a CSV file and returns it, or null if there was
     * nothing to write or the write failed.
     *
     * <p>See the class javadoc for the flush precondition.
     */
    public File flushToCsv(Context context) {
        int rows;
        synchronized (this) {
            rows = rowCount;
        }

        if (rows == 0) {
            LimeLog.info("Latency trace: no rows recorded, not writing a CSV");
            return null;
        }

        String metadataBlock = buildMetadata(rows);

        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File out = new File(getTraceDirectory(context), FILE_PREFIX + stamp + FILE_SUFFIX);

        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8),
                    1 << 16);

            // SPEC.md §4.6: a run without its metadata block is not comparable
            // and should be discarded, so it is written as CSV comment lines at
            // the top of the file rather than into a separate file that can be
            // lost.
            for (String line : metadataBlock.split("\n")) {
                writer.write("# ");
                writer.write(line);
                writer.write('\n');
            }
            writer.write("# clock=CLOCK_MONOTONIC units=microseconds\n");
            writer.write("# a 0 timestamp means not recorded; check the flags column, not the value\n");
            writer.write("# host stage columns are BLANK when the host did not measure that stage"
                    + " on that frame; host_stamp_mask says which are present"
                    + " (0x01 capture_requested 0x02 capture_complete 0x04 encode_submit"
                    + " 0x08 encode_complete 0x10 tx_pipeline_entry)\n");
            writer.write("# t_tx_pipeline_entry_us is NOT the first packet's transmit time: the"
                    + " host stamps it before FEC parity, per-shard encryption and intra-frame"
                    + " pacing. Do not compute one-way delay from it; use the host CSV's"
                    + " t_first_packet_tx and its published bias percentiles\n");
            writer.write("# flags: 0x01 host_valid 0x02 last_packet_rx 0x04 decode_submit"
                    + " 0x08 decode_complete 0x10 present_called 0x20 present_vsync"
                    + " 0x40 frame_rendered 0x80 negative_delta\n");
            // This caveat has to live in the CSV, not only in the source: the
            // file outlives the tree it was produced from.
            writer.write("# t_present_vsync_us is quantised to a vsync boundary and may be one"
                    + " refresh late; use vsync_lag_us (= t_present_vsync_us - t_frame_rendered_us)"
                    + " to bound the error, and do not treat it as exact\n");
            writer.write(CSV_HEADER);
            writer.write('\n');

            StringBuilder sb = new StringBuilder(224);
            for (int i = 0; i < rows; i++) {
                long lag = ((flags[i] & FLAG_PRESENT_VSYNC) != 0
                        && (flags[i] & FLAG_FRAME_RENDERED) != 0)
                        ? tPresentVsync[i] - tFrameRendered[i] : 0;
                int stamps = hostStampMask[i];
                sb.setLength(0);
                sb.append(rowSeq[i]).append(',')
                  .append(frameId[i]).append(',')
                  .append(frameType[i]).append(',')
                  .append(flags[i]).append(',')
                  .append(stamps).append(',');
                // A stage the host did not measure is emitted BLANK, not 0.
                // Zero is a legal monotonic timestamp, so writing 0 here would be
                // indistinguishable from a real measurement to every consumer.
                appendStamp(sb, tCaptureRequested[i],
                        (stamps & MoonBridge.TRACE_STAMP_CAPTURE_REQUESTED) != 0);
                appendStamp(sb, tCaptureComplete[i],
                        (stamps & MoonBridge.TRACE_STAMP_CAPTURE_COMPLETE) != 0);
                appendStamp(sb, tEncodeSubmit[i],
                        (stamps & MoonBridge.TRACE_STAMP_ENCODE_SUBMIT) != 0);
                appendStamp(sb, tEncodeComplete[i],
                        (stamps & MoonBridge.TRACE_STAMP_ENCODE_COMPLETE) != 0);
                appendStamp(sb, tTxPipelineEntry[i],
                        (stamps & MoonBridge.TRACE_STAMP_TX_PIPELINE_ENTRY) != 0);
                sb.append(tLastPacketRx[i]).append(',')
                  .append(tDecodeSubmit[i]).append(',')
                  .append(tDecodeComplete[i]).append(',')
                  .append(tPresentCalled[i]).append(',')
                  .append(tFrameRendered[i]).append(',')
                  .append(tPresentVsync[i]).append(',')
                  .append(lag).append('\n');
                writer.write(sb.toString());
            }
            writer.flush();
        } catch (IOException e) {
            LimeLog.severe("Latency trace: failed to write CSV: " + e.getMessage());
            return null;
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                    // Nothing useful to do at session teardown.
                }
            }
        }

        LimeLog.info("Latency trace: wrote " + rows + " rows to " + out.getAbsolutePath());
        return out;
    }

    /**
     * Builds the SPEC.md §4.6 metadata block plus the completeness counters.
     *
     * <p>The counters exist so a hole in the data cannot be invisible: if a large
     * fraction of rows lack a present timestamp, that shows up here rather than
     * being silently averaged into a latency figure.
     */
    private String buildMetadata(int rows) {
        long missingDecodeComplete = 0;
        long missingPresentCalled = 0;
        long missingPresentVsync = 0;
        long negRows;
        long evictions;
        long insertFailures;
        long vsyncBeforePresent;
        long unmatched;
        long dropped;

        synchronized (this) {
            for (int i = 0; i < rows; i++) {
                if ((flags[i] & FLAG_DECODE_COMPLETE) == 0) {
                    missingDecodeComplete++;
                }
                if ((flags[i] & FLAG_PRESENT_CALLED) == 0) {
                    missingPresentCalled++;
                }
                if ((flags[i] & FLAG_PRESENT_VSYNC) == 0) {
                    missingPresentVsync++;
                }
            }
            negRows = negativeDeltaRows;
            evictions = ptsEvictions;
            insertFailures = ptsInsertFailures;
            vsyncBeforePresent = vsyncBeforePresentCalled;
            unmatched = unmatchedVsyncs;
            dropped = droppedRows;
        }

        long[] sync = new long[MoonBridge.CLOCK_SYNC_ARRAY_LENGTH];
        boolean gotSync = MoonBridge.getClockSyncInfo(sync);

        StringBuilder sb = new StringBuilder(1024);
        sb.append("apollo2_latency_trace v2\n");
        sb.append("timestamp=").append(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
                .format(new Date())).append('\n');
        sb.append("client_app=Artemis ").append(BuildConfig.VERSION_NAME).append('\n');
        sb.append("device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
          .append(" (").append(Build.DEVICE).append(")\n");
        sb.append("android=").append(Build.VERSION.RELEASE)
          .append(" sdk=").append(Build.VERSION.SDK_INT).append('\n');
        sb.append("build_fingerprint=").append(Build.FINGERPRINT).append('\n');
        synchronized (metaLock) {
            sb.append("codec=").append(codec).append('\n');
            sb.append("decoder=").append(decoderName).append('\n');
            sb.append("resolution=").append(metaWidth).append('x').append(metaHeight).append('\n');
            sb.append("fps=").append(metaFps).append('\n');
            sb.append("bitrate_kbps=").append(metaBitrateKbps).append('\n');
            sb.append("frame_pacing=").append(framePacing).append('\n');
            sb.append("ultra_low_latency=").append(ultraLowLatency).append('\n');
            sb.append("network_path=").append(networkPath).append('\n');
            if (!rendererDiagnostics.isEmpty()) {
                sb.append("renderer_diagnostics=").append(rendererDiagnostics).append('\n');
            }
        }
        sb.append("trace_negotiated=").append(MoonBridge.getLatencyTraceEnabled()).append('\n');
        // The format version actually seen on the wire, not the one we can parse.
        // A joined dataset that does not carry this cannot be interpreted later,
        // and 0 here is the signature of a capability or version mismatch --
        // which otherwise presents only as "every host column is empty".
        int extVersion = MoonBridge.getFrameTraceExtVersion();
        sb.append("trace_ext_version=").append(extVersion).append('\n');
        sb.append("trace_ext_version_supported_max=")
          .append(MoonBridge.getLatencyTraceEnabled() ? "2" : "n/a").append('\n');
        if (extVersion == 0) {
            sb.append("trace_ext_version_note=no valid host extension was parsed this session;"
                    + " host columns will be empty (capability declined, or a version this"
                    + " client does not understand)\n");
        }
        sb.append("rows=").append(rows).append(" dropped_rows=").append(dropped).append('\n');
        sb.append("missing_decode_complete=").append(missingDecodeComplete).append('\n');
        sb.append("missing_present_called=").append(missingPresentCalled).append('\n');
        sb.append("missing_present_vsync=").append(missingPresentVsync).append('\n');
        sb.append("rows_with_negative_delta=").append(negRows).append('\n');
        sb.append("vsync_before_present_called=").append(vsyncBeforePresent).append('\n');
        sb.append("unmatched_vsyncs=").append(unmatched).append('\n');
        sb.append("pts_evictions=").append(evictions).append('\n');
        sb.append("pts_insert_failures=").append(insertFailures).append('\n');

        if (gotSync) {
            sb.append("clock_offset_us=").append(sync[MoonBridge.CLOCK_SYNC_OFFSET_US]).append('\n');
            sb.append("clock_best_rtt_us=").append(sync[MoonBridge.CLOCK_SYNC_BEST_RTT_US]).append('\n');
            sb.append("clock_samples=").append(sync[MoonBridge.CLOCK_SYNC_SAMPLE_COUNT]).append('\n');
            sb.append("clock_divergence_events=")
              .append(sync[MoonBridge.CLOCK_SYNC_DIVERGENCE_EVENTS]).append('\n');
            sb.append("clock_unmatched_responses=")
              .append(sync[MoonBridge.CLOCK_SYNC_UNMATCHED_RESPONSES]).append('\n');
            sb.append("clock_offset_valid=")
              .append(sync[MoonBridge.CLOCK_SYNC_VALID] != 0).append('\n');
        } else {
            sb.append("clock_sync=unavailable_at_flush\n");
        }
        return sb.toString();
    }

    /**
     * Writes one host stamp followed by a comma, or an empty field when the host
     * did not measure that stage on this frame.
     */
    private static void appendStamp(StringBuilder sb, long value, boolean present) {
        if (present) {
            sb.append(value);
        }
        sb.append(',');
    }

    // --- PTS -> row, bounded probe. Callers hold the instance lock. ----------

    private static int ptsSlot(long pts) {
        // Fibonacci hashing; PTS values are near-consecutive so the low bits
        // alone would cluster badly.
        long h = pts * 0x9E3779B97F4A7C15L;
        return (int) (h >>> 48) & PTS_MASK;
    }

    private void putPtsRow(long pts, int row) {
        int slot = ptsSlot(pts);
        int oldestIdx = -1;
        long oldestSeq = Long.MAX_VALUE;

        for (int probe = 0; probe < PTS_MAX_PROBE; probe++) {
            int i = (slot + probe) & PTS_MASK;
            if (ptsRows[i] < 0 || ptsKeys[i] == pts) {
                ptsKeys[i] = pts;
                ptsRows[i] = row;
                ptsInsertSeq[i] = ptsSeqCounter++;
                return;
            }
            if (ptsInsertSeq[i] < oldestSeq) {
                oldestSeq = ptsInsertSeq[i];
                oldestIdx = i;
            }
        }

        // Probe window full. Evict the oldest entry in the window rather than
        // extending the probe: an unbounded probe is what turns this into an
        // O(n) scan on the render thread. The evicted frame loses its
        // post-submit columns, and the loss is counted rather than hidden.
        if (oldestIdx >= 0) {
            ptsEvictions++;
            ptsKeys[oldestIdx] = pts;
            ptsRows[oldestIdx] = row;
            ptsInsertSeq[oldestIdx] = ptsSeqCounter++;
        } else {
            ptsInsertFailures++;
        }
    }

    private int lookupPtsRow(long pts) {
        int slot = ptsSlot(pts);
        for (int probe = 0; probe < PTS_MAX_PROBE; probe++) {
            int i = (slot + probe) & PTS_MASK;
            if (ptsRows[i] < 0) {
                return -1;
            }
            if (ptsKeys[i] == pts) {
                return ptsRows[i];
            }
        }
        return -1;
    }

    private void removePtsRow(long pts) {
        int slot = ptsSlot(pts);
        for (int probe = 0; probe < PTS_MAX_PROBE; probe++) {
            int i = (slot + probe) & PTS_MASK;
            if (ptsRows[i] < 0) {
                return;
            }
            if (ptsKeys[i] == pts) {
                // Backward-shift deletion would be unbounded, so the slot is
                // simply cleared. That is safe here: lookup stops at the first
                // empty slot, so clearing can only hide keys later in this same
                // probe window, and putPtsRow refills the freed slot first. The
                // worst case is a lost binding, counted as a miss, never a scan.
                ptsRows[i] = -1;
                ptsKeys[i] = 0;
                ptsInsertSeq[i] = 0;
                return;
            }
        }
    }
}
