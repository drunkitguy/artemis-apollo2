package com.limelight.ui;

import android.content.Context;
import android.view.KeyEvent;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

public class ExternalControllerView extends FrameLayout {
    private InputCallbacks inputCallbacks;

    // When enabled, we expose an InputConnection so that soft keyboards can send
    // commitText() events (e.g. swipe typing). Default disabled.
    private boolean commitTextEnabled = false;

    // Set while the host reports that a text field has focus. A soft keyboard only
    // renders a specific layout (a number pad, say) if the view it is attached to
    // declares itself a text editor and names an input type, so acting on a host
    // focus hint means becoming a text editor for as long as the hint lasts. This is
    // independent of commitTextEnabled and reverts to it when the hint clears.
    //
    // Thread ownership: both fields are touched only on the main thread, from the
    // control activity and from IME callbacks, which run on the same looper.
    private boolean textFocusActive = false;
    private int textFocusInputType = android.text.InputType.TYPE_CLASS_TEXT;

    public void setInputCallbacks(InputCallbacks callbacks) {
        this.inputCallbacks = callbacks;
    }

    public void setTextFocusInputType(int inputType) {
        this.textFocusActive = true;
        this.textFocusInputType = inputType;
        setFocusableInTouchMode(true);
        requestFocus();
    }

    public void clearTextFocusInputType() {
        this.textFocusActive = false;
        this.textFocusInputType = android.text.InputType.TYPE_CLASS_TEXT;
    }

    public boolean isTextFocusActive() {
        return textFocusActive;
    }

    public void setCommitTextEnabled(boolean enabled) {
        this.commitTextEnabled = enabled;
        // Request focus so that IME targets this view when enabled
        if (enabled) {
            setFocusableInTouchMode(true);
            requestFocus();
        }
    }

    public ExternalControllerView(@NonNull Context context) {
        super(context);
    }

    @Override
    public boolean onKeyPreIme(int keyCode, KeyEvent event) {
        // This callbacks allows us to override dumb IME behavior like when
        // Samsung's default keyboard consumes Shift+Space.
        if (inputCallbacks != null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (inputCallbacks.handleKeyDown(event)) {
                    return true;
                }
            }
            else if (event.getAction() == KeyEvent.ACTION_UP) {
                if (inputCallbacks.handleKeyUp(event)) {
                    return true;
                }
            }
        }

        return super.onKeyPreIme(keyCode, event);
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return textFocusActive || commitTextEnabled || super.onCheckIsTextEditor();
    }

    @Override
    public android.view.inputmethod.InputConnection onCreateInputConnection(android.view.inputmethod.EditorInfo outAttrs) {
        if (!commitTextEnabled && !textFocusActive) {
            return super.onCreateInputConnection(outAttrs);
        }

        // Basic text editor flags – we don't need extract UI or enter action.
        // A live host focus hint picks the keyboard layout; otherwise plain text.
        outAttrs.inputType = textFocusActive
                ? textFocusInputType
                : android.text.InputType.TYPE_CLASS_TEXT;
        outAttrs.imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI;

        return new android.view.inputmethod.BaseInputConnection(this, false) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                if (inputCallbacks != null && inputCallbacks.handleCommitText(text)) {
                    return true;
                }
                return super.commitText(text, newCursorPosition);
            }

            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                if (inputCallbacks != null && inputCallbacks.handleDeleteSurroundingText(beforeLength, afterLength)) {
                    return true;
                }
                return super.deleteSurroundingText(beforeLength, afterLength);
            }
        };
    }

    public interface InputCallbacks {
        boolean handleKeyUp(KeyEvent event);
        boolean handleKeyDown(KeyEvent event);
        boolean handleCommitText(CharSequence text);
        boolean handleDeleteSurroundingText(int beforeLength, int afterLength);
    }
}
