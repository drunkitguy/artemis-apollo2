package com.limelight.nvstream;

public interface NvConnectionListener {
    void stageStarting(String stage);
    void stageComplete(String stage);
    boolean stageFailed(String stage, int portFlags, int errorCode);
    
    void connectionStarted();
    void connectionTerminated(int errorCode);
    void connectionStatusUpdate(int connectionStatus);
    
    void displayMessage(String message);
    void displayTransientMessage(String message);

    void rumble(short controllerNumber, short lowFreqMotor, short highFreqMotor);
    void rumbleTriggers(short controllerNumber, short leftTrigger, short rightTrigger);

    void setHdrMode(boolean enabled, byte[] hdrMetadata);

    void setMotionEventState(short controllerNumber, byte motionType, short reportRateHz);

    void setControllerLED(short controllerNumber, byte r, byte g, byte b);

    // Apollo protocol extension. fieldKind is one of MoonBridge.TEXT_FIELD_*, flags is a
    // mask of MoonBridge.TEXT_FIELD_FLAG_*, and inputScope is reserved (always 0 today).
    // This reports ABSOLUTE STATE: the most recent call always describes the current host
    // focus. It is never called when streaming from a host that does not implement it.
    void setTextFieldFocus(byte fieldKind, byte flags, int inputScope);
}
