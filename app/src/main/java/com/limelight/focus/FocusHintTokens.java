package com.limelight.focus;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

/**
 * The shared secret between this client and the reporter on the PC.
 *
 * Generated once and kept, so the token typed into focus_reporter.exe on the
 * PC stays valid across sessions and reinstalls of nothing in particular. It
 * guards nothing more valuable than which keyboard is drawn, but it means a
 * stray datagram from elsewhere on the network cannot move the panel, and it
 * means the reporter will not start watching for anyone who happens to find
 * its port.
 */
public final class FocusHintTokens {

    private static final String PREFS = "focus_hints";
    private static final String KEY = "token";

    private FocusHintTokens() {
    }

    /** The token for this install, creating one on first use. */
    public static synchronized String get(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String token = prefs.getString(KEY, null);
        if (token != null && !token.isEmpty()) {
            return token;
        }

        token = FocusHint.newToken(new SecureRandom());
        prefs.edit().putString(KEY, token).apply();
        return token;
    }

    /** Replaces the token, for when it should stop being accepted. */
    public static synchronized String regenerate(Context context) {
        String token = FocusHint.newToken(new SecureRandom());
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, token).apply();
        return token;
    }
}
