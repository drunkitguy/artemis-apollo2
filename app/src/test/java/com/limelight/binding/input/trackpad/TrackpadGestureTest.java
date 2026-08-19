package com.limelight.binding.input.trackpad;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure JVM tests for the trackpad's gesture handling. */
public class TrackpadGestureTest {

    private static TrackpadGesture pad() {
        return new TrackpadGesture(1f);
    }

    // ----------------------------------------------------------------- taps

    @Test
    public void aQuickStillTouchIsALeftClick() {
        TrackpadGesture pad = pad();
        pad.onTouchDown(100, 100, 0, 1);
        TrackpadGesture.Action up = pad.onTouchUp(101, 100, 50);
        assertEquals(TrackpadGesture.Kind.LEFT_CLICK, up.kind);
    }

    @Test
    public void aSlowTouchIsNotATapEvenIfItDidNotMove() {
        TrackpadGesture pad = pad();
        pad.onTouchDown(100, 100, 0, 1);
        TrackpadGesture.Action up = pad.onTouchUp(100, 100, TrackpadGesture.TAP_TIME_MS + 1);
        assertEquals(TrackpadGesture.Kind.NONE, up.kind);
    }

    @Test
    public void aQuickTouchThatMovedIsADragNotATap() {
        TrackpadGesture pad = pad();
        pad.onTouchDown(100, 100, 0, 1);
        pad.onTouchMove(100 + TrackpadGesture.TAP_SLOP_PX + 5, 100, 20, 1);
        TrackpadGesture.Action up = pad.onTouchUp(100 + TrackpadGesture.TAP_SLOP_PX + 5, 100, 40);
        assertEquals(TrackpadGesture.Kind.NONE, up.kind);
    }

    @Test
    public void tinyMovementInsideTheSlopStillCountsAsATap() {
        // No finger is perfectly still. A tap that wobbles a few pixels is
        // still a tap, or clicking becomes unreliable.
        TrackpadGesture pad = pad();
        pad.onTouchDown(100, 100, 0, 1);
        pad.onTouchMove(103, 102, 10, 1);
        assertEquals(TrackpadGesture.Kind.LEFT_CLICK, pad.onTouchUp(103, 102, 40).kind);
    }

    @Test
    public void twoFingerTapIsARightClick() {
        TrackpadGesture pad = pad();
        pad.onTouchDown(100, 100, 0, 1);
        pad.onPointerAdded(140, 100, 5, 2);
        assertEquals(TrackpadGesture.Kind.RIGHT_CLICK, pad.onTouchUp(100, 100, 40).kind);
    }

    @Test
    public void aTwoFingerTapStaysRightClickEvenAfterOneFingerLifts() {
        // Fingers rarely leave together. The peak count is what decides.
        TrackpadGesture pad = pad();
        pad.onTouchDown(100, 100, 0, 1);
        pad.onPointerAdded(140, 100, 5, 2);
        pad.onPointerRemoved(140, 100, 30, 1);
        assertEquals(TrackpadGesture.Kind.RIGHT_CLICK, pad.onTouchUp(100, 100, 45).kind);
    }

    // ------------------------------------------------------------ movement

    @Test
    public void draggingOneFingerMovesThePointer() {
        TrackpadGesture pad = pad();
        pad.onTouchDown(100, 100, 0, 1);
        TrackpadGesture.Action move = pad.onTouchMove(130, 120, 20, 1);

        assertEquals(TrackpadGesture.Kind.MOVE, move.kind);
        assertEquals(30, move.dx);
        assertEquals(20, move.dy);
    }

    @Test
    public void movementIsRelativeToTheLastEventNotTheTouchDown() {
        TrackpadGesture pad = pad();
        pad.onTouchDown(0, 0, 0, 1);
        pad.onTouchMove(10, 0, 10, 1);
        TrackpadGesture.Action second = pad.onTouchMove(25, 0, 20, 1);
        assertEquals("only the new 15 pixels", 15, second.dx);
    }

    @Test
    public void sensitivityScalesMovement() {
        TrackpadGesture pad = new TrackpadGesture(2f);
        pad.onTouchDown(0, 0, 0, 1);
        assertEquals(20, pad.onTouchMove(10, 0, 10, 1).dx);
    }

    @Test
    public void slowMovementIsNotLostToRounding() {
        // Half a pixel per event, twenty events. Without carrying the
        // remainder every one of these truncates to zero and the pointer
        // simply refuses to move.
        TrackpadGesture pad = new TrackpadGesture(0.5f);
        pad.onTouchDown(0, 0, 0, 1);

        int total = 0;
        float x = 0;
        for (int i = 0; i < 20; i++) {
            x += 1f;
            TrackpadGesture.Action a = pad.onTouchMove(x, 0, 10 * (i + 1), 1);
            total += a.dx;
        }
        assertEquals("twenty pixels at half sensitivity", 10, total);
    }

    @Test
    public void addingASecondFingerDoesNotFlingThePointer() {
        // The new finger lands somewhere else entirely. Treating that jump as
        // movement throws the cursor across the screen.
        TrackpadGesture pad = pad();
        pad.onTouchDown(100, 100, 0, 1);
        pad.onPointerAdded(400, 400, 10, 2);
        TrackpadGesture.Action next = pad.onTouchMove(405, 400, 20, 2);
        assertTrue("no giant jump", Math.abs(next.dy) < 5);
    }

    // -------------------------------------------------------------- scroll

    @Test
    public void twoFingerDragScrolls() {
        TrackpadGesture pad = pad();
        pad.onTouchDown(100, 100, 0, 1);
        pad.onPointerAdded(140, 100, 5, 2);

        TrackpadGesture.Action a = pad.onTouchMove(140, 100 + TrackpadGesture.SCROLL_STEP_PX, 30, 2);
        assertEquals(TrackpadGesture.Kind.SCROLL, a.kind);
        assertEquals(1, a.dy);
    }

    @Test
    public void scrollBelowOneClickAccumulatesRatherThanBeingDropped() {
        TrackpadGesture pad = pad();
        pad.onTouchDown(100, 100, 0, 1);
        pad.onPointerAdded(140, 100, 5, 2);

        float y = 100;
        int clicks = 0;
        // Quarter steps, ten of them: 2.5 steps in total. Deliberately not a
        // whole number of clicks, because a total that lands exactly on the
        // boundary makes the assertion depend on float rounding rather than on
        // the accumulation being right.
        float perStep = TrackpadGesture.SCROLL_STEP_PX / 4f;
        for (int i = 0; i < 10; i++) {
            y += perStep;
            clicks += pad.onTouchMove(140, y, 30 + i * 10, 2).dy;
        }
        assertEquals("2.5 steps of movement is two whole clicks", 2, clicks);
    }

    @Test
    public void oneFingerMovementIsNeverScroll() {
        TrackpadGesture pad = pad();
        pad.onTouchDown(100, 100, 0, 1);
        TrackpadGesture.Action a = pad.onTouchMove(100, 100 + TrackpadGesture.SCROLL_STEP_PX * 3, 30, 1);
        assertEquals(TrackpadGesture.Kind.MOVE, a.kind);
    }

    // --------------------------------------------------------------- state

    @Test
    public void movesBeforeATouchDownAreIgnored() {
        TrackpadGesture pad = pad();
        assertEquals(TrackpadGesture.Kind.NONE, pad.onTouchMove(10, 10, 5, 1).kind);
        assertEquals(TrackpadGesture.Kind.NONE, pad.onTouchUp(10, 10, 10).kind);
    }

    @Test
    public void resetDropsATouchInProgress() {
        TrackpadGesture pad = pad();
        pad.onTouchDown(100, 100, 0, 1);
        pad.reset();
        assertEquals(TrackpadGesture.Kind.NONE, pad.onTouchUp(100, 100, 20).kind);
    }

    @Test
    public void carriedRemainderDoesNotLeakIntoTheNextTouch() {
        TrackpadGesture pad = new TrackpadGesture(0.5f);
        pad.onTouchDown(0, 0, 0, 1);
        pad.onTouchMove(1, 0, 10, 1);
        pad.onTouchUp(1, 0, 20);

        pad.onTouchDown(0, 0, 100, 1);
        assertEquals("a fresh touch starts from zero", 0, pad.onTouchMove(1, 0, 110, 1).dx);
    }
}
