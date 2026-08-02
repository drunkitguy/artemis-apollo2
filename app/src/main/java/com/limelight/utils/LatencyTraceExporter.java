package com.limelight.utils;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.video.LatencyTraceRecorder;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/**
 * Gets a latency trace CSV off the device.
 *
 * <h3>Why this exists</h3>
 * {@link LatencyTraceRecorder} writes to external app-specific storage, which
 * needs no permission but on Android 11+ is <b>invisible to MTP and to every
 * other app</b>. On a device where adb is not available — which is the case for
 * the AYN Thor Pro this is being built for — a trace written there cannot be
 * retrieved by any means at all, which would make the whole measurement harness
 * useless on the one device it was built to measure.
 *
 * <p>Two routes out, in order of reliability:
 * <ul>
 *   <li><b>Share sheet</b> ({@code ACTION_SEND} through the existing
 *       {@code FileProvider}). Works on every API level and every storage
 *       configuration, needs no permission, and lets the file go to mail, Drive,
 *       a messaging app or anything else already installed. This is the primary
 *       path.</li>
 *   <li><b>Copy to Downloads.</b> Puts the file somewhere MTP genuinely shows,
 *       which is what a user with a USB cable and no adb actually wants. Uses
 *       {@link MediaStore.Downloads}, which needs no permission — but only
 *       exists on API 29+. Below that the same operation would require
 *       {@code WRITE_EXTERNAL_STORAGE}, which this app does not declare, so the
 *       option is reported unsupported rather than failing at runtime.</li>
 * </ul>
 *
 * <p>All methods are called from the UI thread and do their I/O inline. A trace
 * CSV is a few MB at most and this only runs on an explicit user action, never
 * during a stream, so it cannot perturb a measurement.
 */
public final class LatencyTraceExporter {
    private static final String MIME_TYPE = "text/csv";

    private LatencyTraceExporter() {
    }

    /**
     * Traces currently on the device, newest first.
     * Never null; empty when no session has produced one.
     */
    public static List<File> listTraces(Context context) {
        File dir = LatencyTraceRecorder.getTraceDirectory(context);
        File[] found = dir.listFiles((d, name) ->
                name.startsWith(LatencyTraceRecorder.FILE_PREFIX)
                        && name.endsWith(LatencyTraceRecorder.FILE_SUFFIX));

        if (found == null || found.length == 0) {
            return new ArrayList<>();
        }

        // Newest first. The filename embeds a sortable yyyyMMdd-HHmmss stamp, but
        // lastModified() is the thing that is actually true if a clock changed.
        Arrays.sort(found, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        return new ArrayList<>(Arrays.asList(found));
    }

    /** Human-readable label for a trace, for a list dialog. */
    public static String describe(Context context, File trace) {
        DateFormat fmt = android.text.format.DateFormat.getDateFormat(context);
        DateFormat timeFmt = android.text.format.DateFormat.getTimeFormat(context);
        Date when = new Date(trace.lastModified());
        long kb = Math.max(1, trace.length() / 1024);
        return fmt.format(when) + " " + timeFmt.format(when) + "  (" + kb + " KB)";
    }

    /**
     * Offers the trace to the share sheet. Works on every supported API level.
     *
     * @return false if no app could handle it, having already told the user
     */
    public static boolean share(Context context, File trace) {
        try {
            Uri uri = FileProvider.getUriForFile(context,
                    context.getPackageName() + ".fileprovider", trace);

            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(MIME_TYPE);
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.putExtra(Intent.EXTRA_SUBJECT, trace.getName());
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            context.startActivity(Intent.createChooser(intent,
                    context.getString(R.string.title_export_latency_trace)));
            return true;
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(context, R.string.toast_trace_no_share_target, Toast.LENGTH_LONG).show();
            return false;
        } catch (Exception e) {
            LimeLog.severe("Latency trace: share failed: " + e);
            Toast.makeText(context, R.string.toast_trace_export_failed, Toast.LENGTH_LONG).show();
            return false;
        }
    }

    /**
     * True if {@link #saveToDownloads} can work on this device.
     *
     * <p>API 29 introduced the MediaStore Downloads collection, which an app may
     * write to without holding any storage permission. Before that the only way
     * to reach the public Downloads folder is a direct filesystem write under
     * {@code WRITE_EXTERNAL_STORAGE}, which this app does not request, so there
     * is nothing to fall back to and the caller should hide the option.
     */
    public static boolean isSaveToDownloadsSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

    /**
     * Copies the trace into the public Downloads collection, where MTP can see
     * it. Returns the display path on success, null on failure (having already
     * told the user why).
     */
    public static String saveToDownloads(Context context, File trace) {
        if (!isSaveToDownloadsSupported()) {
            Toast.makeText(context, R.string.toast_trace_downloads_unsupported,
                    Toast.LENGTH_LONG).show();
            return null;
        }

        ContentResolver resolver = context.getContentResolver();
        Uri item = null;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, trace.getName());
            values.put(MediaStore.Downloads.MIME_TYPE, MIME_TYPE);
            // IS_PENDING hides the row until the bytes are actually there, so a
            // reader can never observe a half-written file.
            values.put(MediaStore.Downloads.IS_PENDING, 1);

            item = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (item == null) {
                Toast.makeText(context, R.string.toast_trace_export_failed, Toast.LENGTH_LONG).show();
                return null;
            }

            OutputStream out = resolver.openOutputStream(item);
            if (out == null) {
                throw new IOException("openOutputStream returned null");
            }
            try {
                InputStream in = new FileInputStream(trace);
                try {
                    byte[] buf = new byte[1 << 16];
                    int read;
                    while ((read = in.read(buf)) > 0) {
                        out.write(buf, 0, read);
                    }
                } finally {
                    in.close();
                }
            } finally {
                out.close();
            }

            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(item, values, null, null);

            return Environment.DIRECTORY_DOWNLOADS + "/" + trace.getName();
        } catch (Exception e) {
            LimeLog.severe("Latency trace: save to Downloads failed: " + e);
            if (item != null) {
                // Do not leave a permanently pending, invisible row behind.
                try {
                    resolver.delete(item, null, null);
                } catch (Exception ignored) {
                    // Nothing useful to do.
                }
            }
            Toast.makeText(context, R.string.toast_trace_export_failed, Toast.LENGTH_LONG).show();
            return null;
        }
    }
}
