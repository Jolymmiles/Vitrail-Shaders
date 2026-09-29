package dev.vitrail.api;

/** The points of the pack's frame at which a {@link StageListener} is called. */
public enum FrameStage {

	/**
	 * After the pack's opaque gbuffers and before its first deferred pass. The depth copies are
	 * made and no render pass is open, so the add-on can read the pack's targets and write images
	 * the deferred passes will read.
	 * <p>
	 * Every listener is called here, once a frame.
	 */
	BEFORE_DEFERRED,

	/**
	 * Right after one of the pack's own programs has run and right before the next one, with no
	 * render pass open, so the add-on can read what that program wrote and write what the next
	 * one reads. {@link FrameContext#program()} says which program it was.
	 * <p>
	 * Only the listeners that name the program in {@link StageListener#programs()} are called, and
	 * only at the programs they name. The programs are the ones the pack's frame runs as full
	 * screen passes: {@code begin}, {@code prepare}, {@code deferred}, {@code composite} and
	 * {@code final}, numbered as the pack numbers them ({@code deferred3}, {@code composite1}).
	 * <p>
	 * "After the program" is after everything the pack runs for it: the compute files that hang
	 * off it, which run first, and then its pass. A program that has a compute file and no pass is
	 * called after the compute. The program's own writes are complete when the listener starts, and
	 * are visible to it.
	 */
	AFTER_PROGRAM
}
