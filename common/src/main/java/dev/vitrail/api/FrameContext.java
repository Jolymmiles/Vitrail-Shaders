package dev.vitrail.api;

import org.jspecify.annotations.Nullable;

/**
 * The frame as a {@link StageListener} sees it. Every handle here is valid only during the call
 * that was handed it.
 */
public interface FrameContext {

	/** A number that grows by one for every frame the pack draws. */
	long frame();

	/**
	 * The frame's {@code VkCommandBuffer}, recording and outside any render pass. The add-on
	 * records into it and returns; whatever it leaves bound or in a layout other than the one it
	 * found is its own mistake.
	 */
	long commandBuffer();

	/** The device and queue the frame runs on. */
	VulkanHandles vulkan();

	/**
	 * One of the pack's targets by the name the pack reads it under, {@code colortex0} to
	 * {@code colortex15} or {@code depthtex0} to {@code depthtex2}, as it stands at this stage; null
	 * for a name the pack does not use.
	 */
	@Nullable AddonImage target(String name);
}
