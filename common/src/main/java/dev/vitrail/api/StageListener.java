package dev.vitrail.api;

/** Work an add-on records at fixed points of the pack's frame. */
public interface StageListener {

	/**
	 * Called on the render thread at {@code stage} of every frame the pack draws. What the add-on
	 * may do with the frame, and for how long its handles are valid, is in {@link FrameContext}.
	 */
	void onStage(FrameStage stage, FrameContext frame);
}
