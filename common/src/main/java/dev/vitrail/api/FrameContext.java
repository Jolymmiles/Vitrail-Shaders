package dev.vitrail.api;

import org.jspecify.annotations.Nullable;

/**
 * The frame as a {@link StageListener} sees it. Every handle here is valid only during the call
 * that was handed it; keeping this object past the call and asking it for the command buffer or
 * a target throws.
 */
public interface FrameContext {

	/**
	 * A number that grows by one for every frame the pack draws, from 0 for the first the session
	 * draws, and does not restart when the pack is reloaded. Every stage of one frame is called
	 * with the same number.
	 */
	long frame();

	/**
	 * The pack program a {@link FrameStage#AFTER_PROGRAM} call follows, by the name
	 * {@link StageListener#programs()} gives it: {@code deferred3}, {@code composite1},
	 * {@code prepare}. Null at every other stage.
	 */
	default @Nullable String program() {
		return null;
	}

	/**
	 * The frame's {@code VkCommandBuffer}, recording and outside any render pass. The add-on
	 * records into it and returns.
	 * <p>
	 * It may bind any pipeline, descriptor set or state it likes: every pass and every dispatch of
	 * the pack begins with its own. It must not begin or end a render pass through the game's
	 * encoder, submit or reset the buffer, or leave a debug label open, and every image it was
	 * handed or serves has to be in {@code VK_IMAGE_LAYOUT_GENERAL} when it returns, as it was when
	 * it started. Vitrail records a full memory barrier before the listeners and another after
	 * them, so what earlier passes wrote is visible to the add-on and what the add-on wrote is
	 * visible to the passes that follow.
	 */
	long commandBuffer();

	/** The device and queue the frame runs on. */
	VulkanHandles vulkan();

	/**
	 * One of the pack's targets by the name the pack reads it under, {@code colortex0} to
	 * {@code colortex15} or {@code depthtex0} to {@code depthtex2}, as it stands at this stage; null
	 * for a name the pack does not use, or one that holds nothing yet.
	 * <p>
	 * A colour target the pack turns over between passes carries two images, and the one handed
	 * over is the half the next pass of the pack's chain reads, so whatever the add-on writes to it
	 * is what that pass reads first. At {@link FrameStage#BEFORE_DEFERRED} that is the half the
	 * opaque geometry wrote unless the pack states a {@code flip.deferred_pre}. At
	 * {@link FrameStage#AFTER_PROGRAM} it is the half the program that draws after the named one
	 * reads, which is the half the named one wrote unless the pack turns the target over in
	 * between; a program the pack gives a compute file and no pass is not counted as one that
	 * draws. Where no pass is left, it is the half the pack's frame ends on.
	 * <p>
	 * The depths are the images the pass before the stage read under those names (at
	 * {@link FrameStage#BEFORE_DEFERRED}, the deferred passes about to run), and a depth image is
	 * to be read and not written. {@code depthtex1} is the opaque world's depth and
	 * {@code depthtex2} the depth from before the player's hand where the engine took one, the
	 * same image as {@code depthtex1} where it did not. {@code depthtex0} is the opaque world's
	 * depth for the deferred programs, the whole scene's, translucents included, for the composite
	 * programs and the final, and nothing (null) for {@code begin} and {@code prepare}, which run
	 * before the world is drawn and read the frame before's images under the other two names.
	 * <p>
	 * The image is in {@code VK_IMAGE_LAYOUT_GENERAL}, and is left there.
	 */
	@Nullable AddonImage target(String name);
}
