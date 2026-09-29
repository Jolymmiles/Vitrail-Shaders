package dev.vitrail.api;

import java.util.Set;

/** Work an add-on records at fixed points of the pack's frame. */
public interface StageListener {

	/**
	 * Called on the render thread at {@code stage} of every frame the pack draws. What the add-on
	 * may do with the frame, and for how long its handles are valid, is in {@link FrameContext}.
	 * <p>
	 * {@link FrameStage#BEFORE_DEFERRED} reaches every listener. {@link FrameStage#AFTER_PROGRAM}
	 * reaches only the ones that name the program in {@link #programs()}, so a listener written for
	 * the first stage alone is never called with the second, and one that wants only the second
	 * returns at once for the first.
	 */
	void onStage(FrameStage stage, FrameContext frame);

	/**
	 * The pack's programs this listener is called after, at {@link FrameStage#AFTER_PROGRAM}. None
	 * by default.
	 * <p>
	 * A name is the pack's own name for the program: {@code prepare}, {@code deferred3},
	 * {@code composite1}, {@code final}, {@code begin}. A number is the pack's slot, so
	 * {@code deferred0} is {@code deferred}. The name of a compute file, {@code composite3_a},
	 * names the program it hangs off, {@code composite3}. This is the name of a program that
	 * exists in the pack once its {@link SourcePatcher}s have run, which is how an add-on that adds
	 * a program to a pack has it called afterwards.
	 * <p>
	 * Asked on the render thread, once for every load of the pack, when the pack's passes are
	 * built: the answer holds until the next load and is not asked again in between. A name that is
	 * not a program of the pack's frame, or that this pack does not run, is logged and never
	 * called. A player whose add-ons name no program pays nothing for any of this.
	 * <p>
	 * A null answer, or a null in it, counts as the listener throwing.
	 */
	default Set<String> programs() {
		return Set.of();
	}
}
