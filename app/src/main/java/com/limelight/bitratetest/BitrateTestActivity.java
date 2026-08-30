package com.limelight.bitratetest;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.TrafficStats;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.PlatformBinding;
import com.limelight.binding.video.CrashListener;
import com.limelight.binding.video.MediaCodecDecoderRenderer;
import com.limelight.binding.video.MediaCodecHelper;
import com.limelight.binding.video.PerfOverlayListener;
import com.limelight.binding.video.StreamCounters;
import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.NvConnectionListener;
import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.GlPreferences;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.TrafficStatsHelper;
import com.limelight.utils.UiHelper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test connection / find optimal bitrate.
 *
 * This does not simulate anything. It runs a short, real streaming session against the
 * selected host -- the host's own "Desktop" entry, which is static and launches no game --
 * once per rung of {@link BitrateLadder}, and reads the counters Artemis already keeps for
 * the performance overlay: frames lost, decode time, host processing latency, and the bytes
 * that actually arrived. The ladder stops at the first rung that degrades, because that is
 * the ceiling and there is no point bothering the host past it.
 *
 * Everything that decides anything lives in the pure-Java classes next to this one
 * ({@link BitrateLadder}, {@link BitrateTestAnalyzer}); this class is only plumbing and UI.
 *
 * Session lifetime is the delicate part. Every session started here is torn down on
 * success, failure, cancel, back press, surface loss and activity destruction, because a
 * leaked session leaves the host believing a client is still attached.
 */
public class BitrateTestActivity extends AppCompatActivity
        implements SurfaceHolder.Callback, PerfOverlayListener {

    /** Optional: the UUID of the PC to test. Without it the user is asked which PC. */
    public static final String EXTRA_PC_UUID = "PcUuid";
    /**
     * Name of a {@link com.limelight.sweep.SweepPlan.Depth} to run a settings
     * sweep instead of the bitrate ladder. Absent means the ladder.
     */
    public static final String EXTRA_SWEEP_DEPTH = "SweepDepth";

    /** Discarded at the start of each step so the encoder has settled before we look. */
    private static final long SETTLE_MS = 2000;

    /** Length of the measured window in each step. */
    private static final long MEASURE_MS = 6000;

    /** How long to wait for the host to bring a session up before giving up on it. */
    private static final long CONNECT_TIMEOUT_MS = 40000;

    /** After interrupting a stuck connection, how long to wait for it to resolve. */
    private static final long POST_INTERRUPT_GRACE_MS = 10000;

    /** How long to look for a reachable PC when none was named by the caller. */
    private static final long HOST_DISCOVERY_TIMEOUT_MS = 8000;

    /** UI refresh interval while a step is being held. */
    private static final long POLL_INTERVAL_MS = 500;

    /** How long surfaceDestroyed() will block waiting for the session to go away. */
    private static final long SURFACE_TEARDOWN_WAIT_MS = 4000;

    // --- UI ---
    private SurfaceView surfaceView;
    private View progressCard;
    private View resultCard;
    private TextView statusText;
    private TextView stepText;
    private TextView readingsText;
    private ProgressBar progressBar;
    private TextView recommendedText;
    private TextView factorText;
    private TextView explanationText;
    private TextView ladderText;
    private Button cancelButton;
    private Button applyButton;

    // --- Configuration ---
    private PreferenceConfiguration prefConfig;
    private PreferenceConfiguration decoderPrefs;
    /**
     * Set while a sweep step is running, null during the plain bitrate ladder.
     *
     * runStep consults this rather than taking another parameter, so the
     * working ladder path is untouched.
     */
    private com.limelight.sweep.SweepVariant activeVariant;

    /** Null for the bitrate ladder, set for a sweep. */
    private com.limelight.sweep.SweepPlan.Depth sweepDepth;
    private String glRenderer = "";
    private boolean meteredNetwork;

    // --- Service ---
    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private boolean serviceBound;

    // --- Test state ---
    private Thread worker;
    private volatile boolean cancelled;
    private volatile boolean surfaceValid;
    private volatile boolean sessionActive;
    private final Object stateLock = new Object();
    private final AtomicBoolean startedAnAppOnHost = new AtomicBoolean(false);
    private volatile ComputerDetails testedComputer;
    private volatile String testedUniqueId;
    private volatile int recommendedKbps;
    private volatile String currentStepLabel;
    private volatile boolean testFinished;

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        UiHelper.setLocale(this);
        setContentView(R.layout.activity_bitrate_test);

        surfaceView = findViewById(R.id.vl_bt_surface);
        progressCard = findViewById(R.id.vl_bt_progress_card);
        resultCard = findViewById(R.id.vl_bt_result_card);
        statusText = findViewById(R.id.vl_bt_status);
        stepText = findViewById(R.id.vl_bt_step);
        readingsText = findViewById(R.id.vl_bt_readings);
        progressBar = findViewById(R.id.vl_bt_progress);
        recommendedText = findViewById(R.id.vl_bt_recommended);
        factorText = findViewById(R.id.vl_bt_factor);
        explanationText = findViewById(R.id.vl_bt_explanation);
        ladderText = findViewById(R.id.vl_bt_ladder);
        cancelButton = findViewById(R.id.vl_bt_cancel);
        applyButton = findViewById(R.id.vl_bt_apply);

        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!testFinished && worker != null && worker.isAlive()) {
                    requestCancel();
                    cancelButton.setEnabled(false);
                    setStatus(getString(R.string.vl_bt_status_cancelled));
                }
                else {
                    finish();
                }
            }
        });

        applyButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyRecommendation();
            }
        });

        prefConfig = PreferenceConfiguration.readPreferences(this);

        // A second copy for the decoder, with the performance overlay switched off. The
        // raw counters we read are accumulated either way; this only avoids building
        // overlay strings we would throw away.
        String depthName = getIntent().getStringExtra(EXTRA_SWEEP_DEPTH);
        if (depthName != null) {
            try {
                sweepDepth = com.limelight.sweep.SweepPlan.Depth.valueOf(depthName);
            } catch (IllegalArgumentException e) {
                LimeLog.warning("Unknown sweep depth '" + depthName + "', running the bitrate ladder");
            }
        }

        decoderPrefs = PreferenceConfiguration.readPreferences(this);
        decoderPrefs.enablePerfOverlay = false;
        decoderPrefs.enablePerfLogging = false;

        try {
            glRenderer = GlPreferences.readPreferences(this).glRenderer;
            MediaCodecHelper.initialize(this, glRenderer);
        } catch (Throwable t) {
            LimeLog.warning("Bitrate test: could not initialize MediaCodecHelper: " + t);
        }

        try {
            ConnectivityManager connMgr = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            meteredNetwork = connMgr != null && connMgr.isActiveNetworkMetered();
        } catch (Throwable t) {
            meteredNetwork = false;
        }

        SurfaceHolder holder = surfaceView.getHolder();
        holder.addCallback(this);
        if (prefConfig.width > 0 && prefConfig.height > 0) {
            holder.setFixedSize(prefConfig.width, prefConfig.height);
        }

        setStatus(getString(R.string.vl_bt_status_preparing));

        serviceBound = bindService(new Intent(this, ComputerManagerService.class), serviceConnection,
                Context.BIND_AUTO_CREATE);
        if (!serviceBound) {
            showError(getString(R.string.vl_bt_error_manager));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Whatever brought us here, no session may outlive this activity.
        requestCancel();

        if (serviceBound) {
            try {
                unbindService(serviceConnection);
            } catch (Throwable t) {
                LimeLog.warning("Bitrate test: unbind failed: " + t);
            }
            serviceBound = false;
        }
    }

    @Override
    public void onBackPressed() {
        if (worker != null && worker.isAlive()) {
            requestCancel();
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------
    // Surface
    // ------------------------------------------------------------------

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        synchronized (stateLock) {
            surfaceValid = true;
            stateLock.notifyAll();
        }
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        // The decoder is rendering into this surface. Stop the test and give the worker a
        // moment to tear the session down before the surface goes away underneath it.
        requestCancel();

        long deadline = SystemClock.elapsedRealtime() + SURFACE_TEARDOWN_WAIT_MS;
        synchronized (stateLock) {
            surfaceValid = false;
            while (sessionActive) {
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) {
                    break;
                }
                try {
                    stateLock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    @Override
    public void onPerfUpdate(final String text) {
        // The renderer's overlay is disabled for the test; this is only here to satisfy
        // the constructor.
    }

    // ------------------------------------------------------------------
    // Service binding
    // ------------------------------------------------------------------

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (stateLock) {
                managerBinder = (ComputerManagerService.ComputerManagerBinder) binder;
                stateLock.notifyAll();
            }
            startWorker();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (stateLock) {
                managerBinder = null;
            }
            requestCancel();
        }
    };

    private void startWorker() {
        synchronized (stateLock) {
            if (worker != null) {
                return;
            }
            worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        runTest();
                    } catch (Throwable t) {
                        LimeLog.severe("Bitrate test failed: " + t);
                        t.printStackTrace();
                        showError(String.valueOf(t.getMessage()));
                    } finally {
                        quitHostApp();
                    }
                }
            }, "BitrateTest");
            worker.setName("BitrateTest");
        }
        worker.start();
    }

    // ------------------------------------------------------------------
    // The test itself
    // ------------------------------------------------------------------

    private void runTest() {
        ComputerManagerService.ComputerManagerBinder binder = managerBinder;
        if (binder == null) {
            showError(getString(R.string.vl_bt_error_manager));
            return;
        }

        binder.waitForReady();
        if (cancelled) {
            return;
        }

        ComputerDetails computer = resolveComputer(binder);
        if (computer == null || cancelled) {
            return;
        }
        testedComputer = computer;

        String pcName = computer.name != null ? computer.name : "";
        setStatus(getString(R.string.vl_bt_status_checking, pcName));

        if (computer.pairState != PairingManager.PairState.PAIRED) {
            showError(getString(R.string.vl_bt_error_not_paired, pcName));
            return;
        }
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            showError(getString(R.string.vl_bt_error_offline, pcName));
            return;
        }
        if (computer.runningGameId != 0) {
            // Launching Desktop would terminate whatever is running. Refuse instead.
            showError(getString(R.string.vl_bt_error_game_running, pcName));
            return;
        }

        String uniqueId = binder.getUniqueId();
        testedUniqueId = uniqueId;

        // Stop background serverinfo polling: from here on the host is ours.
        try {
            binder.stopPolling();
        } catch (Throwable t) {
            LimeLog.warning("Bitrate test: stopPolling failed: " + t);
        }

        NvApp desktop;
        try {
            NvHTTP http = new NvHTTP(computer.activeAddress, computer.httpsPort, uniqueId,
                    computer.serverCert, PlatformBinding.getCryptoProvider(this));
            desktop = findDesktopApp(http.getAppList());
        } catch (Exception e) {
            showError(getString(R.string.vl_bt_error_applist, pcName, String.valueOf(e.getMessage())));
            return;
        }

        if (desktop == null) {
            showError(getString(R.string.vl_bt_error_no_desktop, pcName));
            return;
        }

        if (!awaitSurface()) {
            if (!cancelled) {
                showError(getString(R.string.vl_bt_error_no_surface));
            }
            return;
        }

        if (sweepDepth != null) {
            runSweep(computer, uniqueId, desktop);
            return;
        }

        int[] ladder = BitrateLadder.build(prefConfig.width, prefConfig.height, prefConfig.fps);
        List<BitrateStepMeasurement> results = new ArrayList<>();

        for (int i = 0; i < ladder.length; i++) {
            if (cancelled) {
                break;
            }

            BitrateStepMeasurement measurement = runStep(computer, uniqueId, desktop, ladder[i], i, ladder.length);
            if (measurement == null) {
                // Aborted or already reported as an error.
                break;
            }

            results.add(measurement);

            if (!BitrateTestAnalyzer.isClean(measurement, prefConfig.fps)) {
                // This is the ceiling. Going higher only annoys the host.
                break;
            }
        }

        if (results.isEmpty()) {
            if (!cancelled) {
                showError(getString(R.string.vl_bt_error_timeout, pcName));
            }
            return;
        }

        showResults(results, BitrateTestAnalyzer.analyze(results, prefConfig.fps));
    }

    /**
     * Walks every configuration in the plan, measuring each one several times.
     *
     * Unlike the bitrate ladder this never stops early. The ladder can stop at
     * the first dirty rung because bitrate is monotonic; a sweep's axes are
     * not, and abandoning it partway would leave some configurations with
     * fewer repeats than others, which is exactly the comparison the analyzer
     * is trying to make fair.
     */
    private void runSweep(ComputerDetails computer, String uniqueId, NvApp app) {
        MediaCodecDecoderRenderer probe;
        try {
            probe = new MediaCodecDecoderRenderer(this, decoderPrefs, crashListener, 0,
                    meteredNetwork, false, false, glRenderer, this);
        } catch (Throwable t) {
            showError(getString(R.string.vl_bt_error_no_decoder));
            return;
        }

        List<com.limelight.sweep.SweepPlan.Codec> codecs = new ArrayList<>();
        codecs.add(new com.limelight.sweep.SweepPlan.Codec(MoonBridge.VIDEO_FORMAT_H264, "H.264"));
        if (probe.isHevcSupported()) {
            codecs.add(new com.limelight.sweep.SweepPlan.Codec(MoonBridge.VIDEO_FORMAT_H265, "HEVC"));
        }
        if (probe.isAv1Supported()) {
            codecs.add(new com.limelight.sweep.SweepPlan.Codec(MoonBridge.VIDEO_FORMAT_AV1_MAIN8, "AV1"));
        }

        List<com.limelight.sweep.SweepPlan.Pacing> pacings = new ArrayList<>();
        pacings.add(new com.limelight.sweep.SweepPlan.Pacing(
                PreferenceConfiguration.FRAME_PACING_MIN_LATENCY, "min latency"));
        pacings.add(new com.limelight.sweep.SweepPlan.Pacing(
                PreferenceConfiguration.FRAME_PACING_BALANCED, "balanced"));

        // Bitrate only becomes an axis at the deepest setting. Around the
        // configured value rather than from zero: the ladder already found the
        // ceiling, and the question here is how each codec behaves near it.
        List<Integer> bitrates = new ArrayList<>();
        bitrates.add(prefConfig.bitrate);
        if (sweepDepth == com.limelight.sweep.SweepPlan.Depth.EXHAUSTIVE) {
            bitrates.add(Math.max(500, prefConfig.bitrate / 2));
            bitrates.add(prefConfig.bitrate * 3 / 2);
        }

        List<com.limelight.sweep.SweepVariant> plan = com.limelight.sweep.SweepPlan.build(
                codecs, bitrates, pacings,
                com.limelight.utils.CpuAffinity.isSupported(), sweepDepth);

        List<com.limelight.sweep.SweepAnalyzer.Run> runs = new ArrayList<>();

        for (int i = 0; i < plan.size(); i++) {
            if (cancelled) {
                break;
            }

            com.limelight.sweep.SweepVariant variant = plan.get(i);
            activeVariant = variant;
            BitrateStepMeasurement measurement;
            try {
                measurement = runStep(computer, uniqueId, app, variant.bitrateKbps, i, plan.size());
            } finally {
                activeVariant = null;
            }

            if (measurement == null) {
                // Cancelled, or already reported. A sweep that lost a run still
                // has the runs it did complete, so keep them.
                break;
            }

            runs.add(new com.limelight.sweep.SweepAnalyzer.Run(
                    variant,
                    measurement.getAverageDecodeTimeMs(),
                    measurement.getFrameLossPercent(),
                    measurement.getAverageHostProcessingLatencyMs(),
                    measurement.isFailed()));
        }

        if (runs.isEmpty()) {
            if (!cancelled) {
                showError(getString(R.string.vl_bt_error_timeout,
                        computer.name != null ? computer.name : ""));
            }
            return;
        }

        showSweepResults(runs);
    }

    /** One rung: connect, settle, measure, tear down. Null means the run was aborted. */
    private BitrateStepMeasurement runStep(ComputerDetails computer, String uniqueId, NvApp app,
                                           int bitrateKbps, int index, int total) {
        final String label = BitrateTestAnalyzer.mbps(bitrateKbps);
        final String pcName = computer.name != null ? computer.name : "";

        currentStepLabel = label;
        setStep(getString(R.string.vl_bt_step_of, index + 1, total) + "   " + label);
        setStatus(getString(R.string.vl_bt_status_connecting, label));
        setProgress(index, total, 0);
        setReadings("");

        if (activeVariant != null) {
            // decoderPrefs is this activity's own copy, so varying it per run
            // never touches the user's saved settings.
            decoderPrefs.pinThreadsToFastCores = activeVariant.pinCores;
            if (activeVariant.framePacing >= 0) {
                decoderPrefs.framePacing = activeVariant.framePacing;
            }
        }

        MediaCodecDecoderRenderer decoder;
        try {
            decoder = new MediaCodecDecoderRenderer(this, decoderPrefs, crashListener, 0,
                    meteredNetwork, false, false, glRenderer, this);
        } catch (Throwable t) {
            LimeLog.severe("Bitrate test: decoder creation failed: " + t);
            showError(getString(R.string.vl_bt_error_no_decoder));
            return null;
        }

        if (!decoder.isAvcSupported()) {
            showError(getString(R.string.vl_bt_error_no_decoder));
            return null;
        }

        Surface surface = surfaceView.getHolder().getSurface();
        if (!surfaceValid || surface == null || !surface.isValid()) {
            if (!cancelled) {
                showError(getString(R.string.vl_bt_error_no_surface));
            }
            return null;
        }
        decoder.setRenderTarget(surface);

        int supportedVideoFormats = MoonBridge.VIDEO_FORMAT_H264;
        if (decoder.isHevcSupported()) {
            supportedVideoFormats |= MoonBridge.VIDEO_FORMAT_H265;
        }
        if (decoder.isAv1Supported()) {
            supportedVideoFormats |= MoonBridge.VIDEO_FORMAT_AV1_MAIN8;
        }

        if (activeVariant != null) {
            // Offer only the one codec, which is how a client forces the host
            // to encode with it.
            supportedVideoFormats = activeVariant.videoFormatMask;
        }

        StreamConfiguration config = new StreamConfiguration.Builder()
                .setResolution(prefConfig.width, prefConfig.height)
                .setLaunchRefreshRate(prefConfig.fps)
                .setRefreshRate(prefConfig.fps)
                .setClientRefreshRateX100((int) (prefConfig.fps * 100))
                .setApp(app)
                .setBitrate(bitrateKbps)
                .setEnableSops(prefConfig.enableSops)
                .enableLocalAudioPlayback(false)
                .setMaxPacketSize(1392)
                .setRemoteConfiguration(StreamConfiguration.STREAM_CFG_AUTO)
                .setSupportedVideoFormats(supportedVideoFormats)
                .setAttachedGamepadMask(0)
                .setAudioConfiguration(MoonBridge.AUDIO_CONFIGURATION_STEREO)
                .setColorSpace(decoder.getPreferredColorSpace())
                .setColorRange(decoder.getPreferredColorRange())
                .setPersistGamepadsAfterDisconnect(false)
                .build();

        TestSession session = new TestSession();
        session.decoder = decoder;

        NvConnection conn = new NvConnection(getApplicationContext(), computer.activeAddress,
                computer.httpsPort, uniqueId, config, PlatformBinding.getCryptoProvider(this),
                computer.serverCert);
        session.conn = conn;

        synchronized (stateLock) {
            sessionActive = true;
        }

        conn.start(new SilentAudioRenderer(), decoder, session);

        if (!awaitStarted(session, CONNECT_TIMEOUT_MS, true)) {
            boolean userCancelled = cancelled;
            String refusal = session.describeFailure();

            if (!session.resolved()) {
                // Never came up and never said why: moonlight-common may still be
                // mid-handshake, holding its connection semaphore. Interrupt it, then give
                // it a moment. NvConnection.stop() is only safe once the connection has
                // actually started, so teardownSession() decides that for itself.
                try {
                    MoonBridge.interruptConnection();
                } catch (Throwable t) {
                    LimeLog.warning("Bitrate test: interruptConnection failed: " + t);
                }
                awaitStarted(session, POST_INTERRUPT_GRACE_MS, false);
            }

            teardownSession(session);

            if (userCancelled) {
                return null;
            }
            if (refusal != null) {
                showError(getString(R.string.vl_bt_error_refused, pcName, label, refusal));
            }
            else {
                showError(getString(R.string.vl_bt_error_timeout, pcName));
            }
            return null;
        }

        startedAnAppOnHost.set(true);

        // Let the encoder settle before anything is counted.
        setStatus(getString(R.string.vl_bt_status_settling, label));
        boolean survived = hold(session, SETTLE_MS, null, index, total, 0);

        StreamCounters base = decoder.snapshotCounters();
        long baseRxBytes = rxBytes();
        long baseTime = SystemClock.elapsedRealtime();

        if (survived) {
            setStatus(getString(R.string.vl_bt_status_measuring, label));
            survived = hold(session, MEASURE_MS, base, index, total, SETTLE_MS);
        }

        StreamCounters end = decoder.snapshotCounters();
        long endRxBytes = rxBytes();
        long durationMs = SystemClock.elapsedRealtime() - baseTime;

        boolean died = session.terminated;
        String terminationReason = session.describeTermination();

        teardownSession(session);

        if (cancelled) {
            return null;
        }

        if (died && !survived) {
            return BitrateStepMeasurement.failed(bitrateKbps, terminationReason);
        }

        StreamCounters delta = end.minus(base);
        long rx = (baseRxBytes >= 0 && endRxBytes >= baseRxBytes) ? endRxBytes - baseRxBytes : -1;

        return BitrateStepMeasurement.measured(bitrateKbps,
                delta.framesReceived,
                delta.framesLost,
                delta.frameLossEvents,
                delta.decoderTimeMs,
                delta.hostProcessingLatencyTenthsMs,
                delta.framesWithHostProcessingLatency,
                rx,
                durationMs);
    }

    /**
     * Holds a live session for the given time, refreshing the live readings as it goes.
     * Returns false if the run was cancelled or the session died.
     */
    private boolean hold(TestSession session, long durationMs, StreamCounters base,
                         int stepIndex, int stepCount, long elapsedOffsetMs) {
        long start = SystemClock.elapsedRealtime();
        long deadline = start + durationMs;

        while (true) {
            if (cancelled || session.terminated) {
                return false;
            }

            long now = SystemClock.elapsedRealtime();
            long remaining = deadline - now;

            MediaCodecDecoderRenderer decoder = session.decoder;
            if (decoder != null) {
                StreamCounters counters = decoder.snapshotCounters();
                StreamCounters delta = base != null ? counters.minus(base) : counters;
                long window = base != null ? (now - start) : (now - start + elapsedOffsetMs);
                setReadings(formatReadings(delta, window));
            }

            long stepElapsed = elapsedOffsetMs + (now - start);
            setProgress(stepIndex, stepCount, (int) Math.min(100,
                    100 * stepElapsed / Math.max(1, SETTLE_MS + MEASURE_MS)));
            setStepRemaining(stepIndex, stepCount, remaining);

            if (remaining <= 0) {
                return true;
            }

            synchronized (stateLock) {
                if (cancelled || session.terminated) {
                    return false;
                }
                try {
                    stateLock.wait(Math.min(POLL_INTERVAL_MS, remaining));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
    }

    /**
     * Stops a session and releases everything it owns.
     *
     * NvConnection.stop() releases the single-connection semaphore that
     * NvConnection.start() takes, so it must only be called for a connection that actually
     * started. Calling it for one that never got that far would hand out a permit nobody
     * took, and a later stream could then run concurrently with another.
     */
    private void teardownSession(TestSession session) {
        if (session == null) {
            return;
        }

        setStatus(getString(R.string.vl_bt_status_stopping));

        MediaCodecDecoderRenderer decoder = session.decoder;
        NvConnection conn = session.conn;
        boolean started = session.started;

        session.decoder = null;
        session.conn = null;

        if (decoder != null) {
            try {
                decoder.prepareForStop();
            } catch (Throwable t) {
                LimeLog.warning("Bitrate test: prepareForStop failed: " + t);
            }
        }

        if (conn != null && started) {
            try {
                conn.stop();
            } catch (Throwable t) {
                LimeLog.severe("Bitrate test: connection stop failed: " + t);
            }
        }

        synchronized (stateLock) {
            sessionActive = false;
            stateLock.notifyAll();
        }
    }

    /**
     * Ends the Desktop session we launched, so the host does not sit there believing a
     * client is still attached. Only runs if we actually launched something.
     */
    private void quitHostApp() {
        if (!startedAnAppOnHost.getAndSet(false)) {
            return;
        }

        final ComputerDetails computer = testedComputer;
        final String uniqueId = testedUniqueId;
        if (computer == null || computer.activeAddress == null || uniqueId == null) {
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Give the host a moment to finish tearing down the session we just
                    // stopped, exactly as Game does before quitting an app.
                    Thread.sleep(1000);

                    NvHTTP http = new NvHTTP(computer.activeAddress, computer.httpsPort, uniqueId,
                            computer.serverCert, PlatformBinding.getCryptoProvider(
                                    getApplicationContext()));
                    http.quitApp();
                } catch (Throwable t) {
                    LimeLog.warning("Bitrate test: could not quit the host session: " + t);
                }
            }
        }, "BitrateTestQuit").start();
    }

    // ------------------------------------------------------------------
    // Host selection
    // ------------------------------------------------------------------

    private ComputerDetails resolveComputer(ComputerManagerService.ComputerManagerBinder binder) {
        String uuid = getIntent().getStringExtra(EXTRA_PC_UUID);
        if (uuid != null) {
            ComputerDetails computer = binder.getComputer(uuid);
            if (computer == null) {
                showError(getString(R.string.vl_bt_error_pc_unknown));
            }
            return computer;
        }

        setStatus(getString(R.string.vl_bt_status_finding_host));

        List<ComputerDetails> candidates = discoverComputers(binder);
        if (cancelled) {
            return null;
        }
        if (candidates.isEmpty()) {
            showError(getString(R.string.vl_bt_error_no_pc));
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        return promptForComputer(candidates);
    }

    /** Polls for a short while and returns the paired, reachable PCs it heard from. */
    private List<ComputerDetails> discoverComputers(ComputerManagerService.ComputerManagerBinder binder) {
        final Map<String, ComputerDetails> seen = new LinkedHashMap<>();

        try {
            binder.startPolling(new ComputerManagerListener() {
                @Override
                public void notifyComputerUpdated(ComputerDetails details) {
                    if (details == null || details.uuid == null) {
                        return;
                    }
                    synchronized (stateLock) {
                        seen.put(details.uuid, details);
                        stateLock.notifyAll();
                    }
                }
            });
        } catch (Throwable t) {
            LimeLog.warning("Bitrate test: startPolling failed: " + t);
        }

        long deadline = SystemClock.elapsedRealtime() + HOST_DISCOVERY_TIMEOUT_MS;
        List<ComputerDetails> usable = new ArrayList<>();
        try {
            synchronized (stateLock) {
                while (!cancelled) {
                    usable.clear();
                    for (ComputerDetails details : seen.values()) {
                        if (isUsable(details)) {
                            usable.add(details);
                        }
                    }
                    if (!usable.isEmpty()) {
                        break;
                    }

                    long remaining = deadline - SystemClock.elapsedRealtime();
                    if (remaining <= 0) {
                        break;
                    }
                    try {
                        stateLock.wait(remaining);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } finally {
            try {
                binder.stopPolling();
            } catch (Throwable t) {
                LimeLog.warning("Bitrate test: stopPolling failed: " + t);
            }
        }

        return usable;
    }

    private static boolean isUsable(ComputerDetails details) {
        return details != null
                && details.state == ComputerDetails.State.ONLINE
                && details.pairState == PairingManager.PairState.PAIRED
                && details.activeAddress != null;
    }

    /** Asks the user which PC to test. Blocks the worker thread until they answer. */
    private ComputerDetails promptForComputer(final List<ComputerDetails> candidates) {
        final ComputerDetails[] chosen = new ComputerDetails[1];
        final boolean[] answered = new boolean[1];

        final CharSequence[] names = new CharSequence[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            names[i] = candidates.get(i).name != null ? candidates.get(i).name : "";
        }

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) {
                    answer(null);
                    return;
                }
                new AlertDialog.Builder(BitrateTestActivity.this)
                        .setTitle(R.string.vl_bt_host_picker_title)
                        .setItems(names, (dialog, which) -> {
                            if (which >= 0 && which < candidates.size()) {
                                answer(candidates.get(which));
                            }
                            else {
                                answer(null);
                            }
                        })
                        .setOnCancelListener(dialog -> answer(null))
                        .show();
            }

            private void answer(ComputerDetails details) {
                synchronized (stateLock) {
                    chosen[0] = details;
                    answered[0] = true;
                    stateLock.notifyAll();
                }
            }
        });

        synchronized (stateLock) {
            while (!answered[0] && !cancelled) {
                try {
                    stateLock.wait(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }

        if (chosen[0] == null && !cancelled) {
            requestCancel();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    finish();
                }
            });
        }
        return chosen[0];
    }

    /**
     * Finds the host's Desktop entry. It exists on every Sunshine / Apollo style host, it
     * shows static content, and streaming it launches no game.
     */
    static NvApp findDesktopApp(List<NvApp> appList) {
        if (appList == null) {
            return null;
        }
        for (NvApp app : appList) {
            if (app != null && "Desktop".equalsIgnoreCase(app.getAppName())) {
                return app;
            }
        }
        for (NvApp app : appList) {
            if (app != null && app.getAppName() != null
                    && app.getAppName().toLowerCase(Locale.ROOT).contains("desktop")) {
                return app;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Waiting helpers
    // ------------------------------------------------------------------

    private void requestCancel() {
        synchronized (stateLock) {
            cancelled = true;
            stateLock.notifyAll();
        }
    }

    private boolean awaitSurface() {
        long deadline = SystemClock.elapsedRealtime() + CONNECT_TIMEOUT_MS;
        synchronized (stateLock) {
            while (!surfaceValid && !cancelled) {
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) {
                    return false;
                }
                try {
                    stateLock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return surfaceValid && !cancelled;
        }
    }

    /** Returns true only if the session reported that it started streaming. */
    private boolean awaitStarted(TestSession session, long timeoutMs, boolean honorCancel) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        synchronized (stateLock) {
            while (true) {
                if (session.started) {
                    return true;
                }
                if (session.launchFailed || session.terminated) {
                    return false;
                }
                if (honorCancel && cancelled) {
                    return false;
                }
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) {
                    return false;
                }
                try {
                    stateLock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
    }

    private long rxBytes() {
        try {
            long rx = TrafficStatsHelper.getPackageRxBytes(Process.myUid());
            return rx == TrafficStats.UNSUPPORTED ? -1 : rx;
        } catch (Throwable t) {
            return -1;
        }
    }

    // ------------------------------------------------------------------
    // UI updates
    // ------------------------------------------------------------------

    private void setStatus(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statusText.setText(text);
            }
        });
    }

    private void setStep(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                stepText.setText(text);
            }
        });
    }

    private void setStepRemaining(final int index, final int total, final long remainingMs) {
        final long seconds = Math.max(0, (remainingMs + 999) / 1000);
        final String label = currentStepLabel;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                stepText.setText(getString(R.string.vl_bt_step_of, index + 1, total)
                        + "   " + (label != null ? label + "   " : "")
                        + getString(R.string.vl_bt_seconds_remaining, (int) seconds));
            }
        });
    }

    private void setReadings(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                readingsText.setText(text);
            }
        });
    }

    private void setProgress(final int index, final int total, final int withinStepPercent) {
        final int value = total <= 0 ? 0
                : (int) Math.min(100, (100L * index + withinStepPercent) / total);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                progressBar.setProgress(value);
            }
        });
    }

    private String formatReadings(StreamCounters delta, long windowMs) {
        BitrateStepMeasurement live = BitrateStepMeasurement.measured(0,
                delta.framesReceived, delta.framesLost, delta.frameLossEvents,
                delta.decoderTimeMs, delta.hostProcessingLatencyTenthsMs,
                delta.framesWithHostProcessingLatency, -1, windowMs);

        String host = live.hasHostProcessingLatency()
                ? String.format(Locale.US, "%.1f ms", live.getAverageHostProcessingLatencyMs())
                : getString(R.string.vl_bt_unavailable);

        return getString(R.string.vl_bt_readings,
                live.getFrameLossPercent(),
                live.getAverageDecodeTimeMs(),
                host,
                String.valueOf(live.getFramesReceived()));
    }

    private void showError(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                testFinished = true;
                progressCard.setVisibility(View.GONE);
                resultCard.setVisibility(View.VISIBLE);
                recommendedText.setText(R.string.vl_bt_error_heading);
                factorText.setVisibility(View.GONE);
                explanationText.setText(message);
                ladderText.setText("");
                findViewById(R.id.vl_bt_ladder_header).setVisibility(View.GONE);
                applyButton.setEnabled(false);
                cancelButton.setEnabled(true);
                cancelButton.setText(R.string.vl_bt_close);
            }
        });
    }

    /**
     * Renders the sweep as a ranked table.
     *
     * Deliberately reports the spread alongside each median, and refuses to
     * name a winner when the gap between the best two is smaller than how much
     * their own repeats disagreed. A confident looking ranking of differences
     * that are inside the noise is worse than saying it could not tell.
     */
    private void showSweepResults(final List<com.limelight.sweep.SweepAnalyzer.Run> runs) {
        final List<com.limelight.sweep.SweepAnalyzer.Summary> summaries =
                com.limelight.sweep.SweepAnalyzer.summarize(runs);

        java.util.Collections.sort(summaries,
                new java.util.Comparator<com.limelight.sweep.SweepAnalyzer.Summary>() {
            @Override
            public int compare(com.limelight.sweep.SweepAnalyzer.Summary a,
                               com.limelight.sweep.SweepAnalyzer.Summary b) {
                if (a.isDisqualified() != b.isDisqualified()) {
                    return a.isDisqualified() ? 1 : -1;
                }
                return Double.compare(a.medianDecodeMs, b.medianDecodeMs);
            }
        });

        final com.limelight.sweep.SweepAnalyzer.Summary best =
                com.limelight.sweep.SweepAnalyzer.best(summaries);
        final boolean conclusive = com.limelight.sweep.SweepAnalyzer.isConclusive(summaries);

        final StringBuilder report = new StringBuilder();
        report.append(runs.size()).append(" runs across ")
                .append(summaries.size()).append(" configurations\n\n");

        if (best == null) {
            report.append("No configuration completed cleanly. Every one either failed to\n")
                    .append("connect or lost more than 0.5% of frames, so there is nothing\n")
                    .append("here worth adopting.\n\n");
        } else if (conclusive) {
            report.append("Best: ").append(best.variant.label()).append('\n');
            report.append("It is clear of the runner up by more than its own repeats vary,\n")
                    .append("so this is a real difference.\n\n");
        } else {
            report.append("Closest: ").append(best.variant.label()).append('\n');
            report.append("But the gap to the next one is smaller than the spread of its own\n")
                    .append("repeats, so this is not a real difference. Treat these as tied\n")
                    .append("and pick on other grounds.\n\n");
        }

        for (com.limelight.sweep.SweepAnalyzer.Summary s : summaries) {
            report.append(s.variant.label()).append('\n');
            if (s.isDisqualified()) {
                report.append("    unusable (")
                        .append(s.anyFailed ? "a run failed" : String.format(java.util.Locale.US,
                                "%.2f%% frame loss", s.medianLossPercent))
                        .append(")\n");
                continue;
            }
            report.append(String.format(java.util.Locale.US,
                    "    decode %.2f +/- %.2f ms   loss %.2f%%   host %.1f ms   (%d runs)\n",
                    s.medianDecodeMs, s.decodeSpreadMs, s.medianLossPercent,
                    s.medianHostLatencyMs, s.runs));
        }

        report.append("\nDecode time is measured from submitting a frame to the decoder to\n")
                .append("getting it back. It is not glass to glass latency, which the client\n")
                .append("cannot see, so this ranks decoding rather than what you feel.\n");

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                setStatus("");
                setStep("");
                setReadings(report.toString());
            }
        });
    }

    private void showResults(final List<BitrateStepMeasurement> steps,
                             final BitrateRecommendation recommendation) {
        recommendedKbps = recommendation.getRecommendedKbps();

        final String ladder = formatLadder(steps);
        final int factorLabel = factorLabelResource(recommendation.getLimitingFactor());

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                testFinished = true;
                setStatusNow(cancelled ? getString(R.string.vl_bt_status_cancelled)
                        : getString(R.string.vl_bt_status_done));
                progressBar.setProgress(100);
                resultCard.setVisibility(View.VISIBLE);
                recommendedText.setText(getString(R.string.vl_bt_recommended,
                        BitrateTestAnalyzer.mbps(recommendation.getRecommendedKbps())));
                factorText.setText(factorLabel);
                explanationText.setText(recommendation.getExplanation());
                ladderText.setText(ladder);
                applyButton.setEnabled(recommendation.isApplicable());
                cancelButton.setEnabled(true);
                cancelButton.setText(R.string.vl_bt_close);
            }
        });
    }

    private void setStatusNow(String text) {
        statusText.setText(text);
    }

    private int factorLabelResource(LimitingFactor factor) {
        switch (factor) {
            case NETWORK:
                return R.string.vl_bt_factor_network;
            case DECODER:
                return R.string.vl_bt_factor_decoder;
            case HOST:
                return R.string.vl_bt_factor_host;
            case STREAM_FAILURE:
                return R.string.vl_bt_factor_stream_failure;
            case NONE:
                return R.string.vl_bt_factor_none;
            default:
                return R.string.vl_bt_factor_no_data;
        }
    }

    private String formatLadder(List<BitrateStepMeasurement> steps) {
        StringBuilder sb = new StringBuilder();
        for (BitrateStepMeasurement step : steps) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            String label = BitrateTestAnalyzer.mbps(step.getBitrateKbps());
            if (!step.hasVideo()) {
                sb.append(getString(R.string.vl_bt_ladder_row_failed, label));
                continue;
            }

            String host = step.hasHostProcessingLatency()
                    ? String.format(Locale.US, "%.1f ms", step.getAverageHostProcessingLatencyMs())
                    : getString(R.string.vl_bt_unavailable);
            int receivedKbps = step.getReceivedKbps();
            String received = receivedKbps >= 0
                    ? BitrateTestAnalyzer.mbps(receivedKbps)
                    : getString(R.string.vl_bt_unavailable);

            sb.append(getString(R.string.vl_bt_ladder_row, label,
                    step.getFrameLossPercent(), step.getAverageDecodeTimeMs(), host, received));
        }
        return sb.toString();
    }

    private void applyRecommendation() {
        int kbps = recommendedKbps;
        if (kbps <= 0) {
            return;
        }

        // The existing bitrate preference, written exactly the way the settings screen
        // writes it. No new key is introduced.
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.edit().putInt(PreferenceConfiguration.BITRATE_PREF_STRING, kbps).apply();

        Toast.makeText(this, getString(R.string.vl_bt_applied, BitrateTestAnalyzer.mbps(kbps)),
                Toast.LENGTH_LONG).show();
        applyButton.setEnabled(false);
    }

    private final CrashListener crashListener = new CrashListener() {
        @Override
        public void notifyCrash(Exception e) {
            LimeLog.severe("Bitrate test: decoder crash: " + e);
            requestCancel();
        }
    };

    // ------------------------------------------------------------------
    // Connection listener for one step
    // ------------------------------------------------------------------

    private final class TestSession implements NvConnectionListener {
        volatile boolean started;
        volatile boolean launchFailed;
        volatile boolean terminated;
        volatile int stageErrorCode;
        volatile int terminationErrorCode;
        volatile String failedStage;
        volatile String message;
        volatile MediaCodecDecoderRenderer decoder;
        volatile NvConnection conn;

        boolean resolved() {
            return started || launchFailed || terminated;
        }

        String describeFailure() {
            if (!launchFailed && !terminated) {
                return null;
            }
            if (message != null) {
                return message;
            }
            if (launchFailed) {
                return (failedStage != null ? failedStage : "connection") + " (error " + stageErrorCode + ")";
            }
            return describeTermination();
        }

        String describeTermination() {
            if (!terminated) {
                return null;
            }
            switch (terminationErrorCode) {
                case MoonBridge.ML_ERROR_NO_VIDEO_TRAFFIC:
                    return getString(R.string.no_video_received_error);
                case MoonBridge.ML_ERROR_NO_VIDEO_FRAME:
                    return getString(R.string.no_frame_received_error);
                case MoonBridge.ML_ERROR_UNEXPECTED_EARLY_TERMINATION:
                case MoonBridge.ML_ERROR_PROTECTED_CONTENT:
                    return getString(R.string.early_termination_error);
                default:
                    return message;
            }
        }

        private void wake() {
            synchronized (stateLock) {
                stateLock.notifyAll();
            }
        }

        @Override
        public void stageStarting(String stage) {
            LimeLog.info("Bitrate test stage starting: " + stage);
        }

        @Override
        public void stageComplete(String stage) {
        }

        @Override
        public boolean stageFailed(String stage, int portFlags, int errorCode) {
            this.failedStage = stage;
            this.stageErrorCode = errorCode;
            this.launchFailed = true;
            wake();
            // Never retry: the test owns the pace, and a retry loop would keep the host
            // busy long after the user asked us to stop.
            return false;
        }

        @Override
        public void connectionStarted() {
            started = true;
            wake();
        }

        @Override
        public void connectionTerminated(int errorCode) {
            terminationErrorCode = errorCode;
            terminated = true;
            wake();
        }

        @Override
        public void connectionStatusUpdate(int connectionStatus) {
            if (connectionStatus == MoonBridge.CONN_STATUS_POOR) {
                LimeLog.info("Bitrate test: host reported a poor connection");
            }
        }

        @Override
        public void displayMessage(String message) {
            this.message = message;
            wake();
        }

        @Override
        public void displayTransientMessage(String message) {
            LimeLog.info("Bitrate test: " + message);
        }

        @Override
        public void rumble(short controllerNumber, short lowFreqMotor, short highFreqMotor) {
        }

        @Override
        public void rumbleTriggers(short controllerNumber, short leftTrigger, short rightTrigger) {
        }

        @Override
        public void setHdrMode(boolean enabled, byte[] hdrMetadata) {
            MediaCodecDecoderRenderer d = decoder;
            if (d != null) {
                d.setHdrMode(enabled, hdrMetadata);
            }
        }

        @Override
        public void setMotionEventState(short controllerNumber, byte motionType, short reportRateHz) {
        }

        @Override
        public void setControllerLED(short controllerNumber, byte r, byte g, byte b) {
        }

        @Override
        public void setTextFieldFocus(byte fieldKind, byte flags, int inputScope) {
            // The bitrate test drives a headless session with no keyboard on
            // it, so there is nothing here for a focused text field to do.
        }
    }
}
