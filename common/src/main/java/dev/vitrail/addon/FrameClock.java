package dev.vitrail.addon;

/**
 * The number {@code FrameContext.frame()} answers, which grows by one for every frame the pack
 * draws and never wraps or restarts within a session.
 * <p>
 * Kept apart from the pack's {@code frameCounter}, which wraps so that a pack's own arithmetic
 * stays exact and which starts over with every pack load: an add-on that tells one frame from the
 * next, or counts them, needs a number that does neither.
 */
public final class FrameClock {

	private long next;

	/** The number of the frame that is starting, and the one after it from the next call. */
	public long tick() {
		return this.next++;
	}
}
