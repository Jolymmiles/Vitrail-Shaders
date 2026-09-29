package dev.vitrail.api;

/** The points of the pack's frame at which a {@link StageListener} is called. */
public enum FrameStage {

	/**
	 * After the pack's opaque gbuffers and before its first deferred pass. The depth copies are
	 * made and no render pass is open, so the add-on can read the pack's targets and write images
	 * the deferred passes will read.
	 */
	BEFORE_DEFERRED
}
