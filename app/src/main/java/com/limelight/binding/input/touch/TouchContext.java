package com.limelight.binding.input.touch;

public interface TouchContext {
    /** Notified when a tap on the touch surface has produced a left click. */
    interface ClickListener {
        void onTouchLeftClick();
    }

    int getActionIndex();
    void setPointerCount(int pointerCount);
    boolean touchDownEvent(int eventX, int eventY, long eventTime, boolean isNewFinger);
    boolean touchMoveEvent(int eventX, int eventY, long eventTime);
    void touchUpEvent(int eventX, int eventY, long eventTime);
    void cancelTouch();
    boolean isCancelled();
}
