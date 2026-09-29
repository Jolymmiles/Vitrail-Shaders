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
	 * draws, and does not restart when the pack is reloaded.
	 */
	long frame();

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
	 * over is the half the next pass of the pack's chain reads, which is the one the opaque
	 * geometry wrote unless the pack states a {@code flip.deferred_pre}. Whatever the add-on writes
	 * to it is what the deferred passes read first. {@code depthtex0} and {@code depthtex1} are the
	 * opaque world's depth, which the deferred passes read under both names, and {@code depthtex2}
	 * is the depth from before the player's hand where the engine took one and the same image as
	 * the other two where it did not. A depth image is to be read and not written.
	 * <p>
	 * The image is in {@code VK_IMAGE_LAYOUT_GENERAL}, and is left there.
	 */
	@Nullable AddonImage target(String name);
}
