package dev.vitrail.render;

import dev.vitrail.addon.AddonRegistry;
import dev.vitrail.addon.FrameClock;
import dev.vitrail.api.FrameStage;
import dev.vitrail.api.VulkanHandles;
import dev.vitrail.mixin.access.CommandEncoderAccessor;
import dev.vitrail.mixin.access.GpuDeviceAccessor;
import dev.vitrail.mixin.access.VulkanCommandEncoderAccessor;
import dev.vitrail.render.storage.GpuRecording;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Calls the add-ons' {@link dev.vitrail.api.StageListener}s at the points of the pack's frame the
 * API names, with a command buffer they may record into.
 * <p>
 * <strong>What a listener may do, and what it must leave.</strong> It records into the frame's
 * command buffer, outside any render pass, and returns. It may read and write the images it is
 * handed and its own, and it may bind any pipeline or descriptor set it likes. It may not begin or
 * end a render pass or a debug label it does not end, submit the buffer, write a query it does not
 * own, or leave an image in another layout than {@code VK_IMAGE_LAYOUT_GENERAL}, which is the
 * layout every image of Vitrail and of the game is in whenever a pass or a compute reads it.
 * <p>
 * Nothing else has to be put back. Every pass of the game and of the pack opens with its own
 * pipeline, viewport, scissor and descriptors, so what a listener left bound is never read by
 * them; that includes the compute bind point, which every dispatch of the pack sets itself. What
 * would be read is memory, and that is Vitrail's to cover: the same full memory barrier the game
 * records after each of its own passes is recorded before the listeners, so they see what earlier
 * passes wrote, and after them, so the passes that follow see what they wrote. The clears the
 * colour targets are still owed are paid before the listeners run, so that no later pass empties
 * what a listener wrote.
 * <p>
 * A listener that opened a pass through the game's encoder anyway has it ended here, because the
 * next pass of the frame cannot open over it.
 */
final class AddonStages {

	/** Per session and never reset: the number an add-on counts frames by does not restart on a reload. */
	private static final FrameClock CLOCK = new FrameClock();

	/** The handles of the device they were read from, which is the same object for the session. */
	private static @Nullable VulkanDevice handlesOf;
	private static @Nullable VulkanHandles handles;

	private AddonStages() {
	}

	/**
	 * Whether any stage listener is registered. The one question every frame asks, so that a player
	 * with no add-on, or with add-ons that record nothing, pays no more than this.
	 */
	static boolean wanted() {
		return AddonRegistry.any() && !AddonRegistry.stages().isEmpty();
	}

	/**
	 * {@link FrameStage#BEFORE_DEFERRED}: the opaque world is drawn, the depth copies are taken and
	 * no pass is open, and the first deferred pass is about to run.
	 *
	 * @param next the pass of the chain that draws next, whose reads decide which half of each
	 *             colour target the listeners are handed; null where the chain has none left
	 */
	static void beforeDeferred(GpuDevice device, ColorTargets targets, @Nullable PackPass next) {
		CommandEncoder encoder = device.createCommandEncoder();
		VulkanCommandEncoder recorder = recorder(encoder);
		VulkanDevice vulkan = vulkan(device);
		if (recorder == null || vulkan == null) {
			return;
		}

		// The same three steps the pack's computes take before they record, in the same order: the
		// hold that keeps a pass open across draws first, and only then the pass under it.
		GeometryHold.flush(() -> "the add-on stage BEFORE_DEFERRED");
		GpuRecording.endPass(encoder);
		GraphicsApi.suspendLevelPass();
		// The clears still owed are paid first, whatever was drawn so far: a target no pass has
		// attached yet this frame is emptied by the load-op of the first one that does, and that
		// would erase what a listener wrote into it, or hand it the frame before's image to read.
		targets.flushPending(encoder);

		VkCommandBuffer commands = ((VulkanCommandEncoderAccessor) recorder).vitrail$commandBuffer();
		StageFrame frame = new StageFrame(CLOCK.tick(), commands.address(), handles(vulkan), targets,
				next == null ? null : next.step());
		barrier(commands);
		try {
			AddonRegistry.each(AddonRegistry.stages(), "record at BEFORE_DEFERRED",
					listener -> listener.onStage(FrameStage.BEFORE_DEFERRED, frame));
		} finally {
			frame.close();
			GpuRecording.endPass(encoder);
			barrier(commands);
		}
	}

	private static void barrier(VkCommandBuffer commands) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VulkanCommandEncoder.memoryBarrier(commands, stack);
		}
	}

	private static VulkanHandles handles(VulkanDevice vulkan) {
		VulkanHandles known = handles;
		if (known != null && handlesOf == vulkan) {
			return known;
		}

		VulkanHandles read = read(vulkan);
		handlesOf = vulkan;
		handles = read;

		return read;
	}

	/**
	 * The device's handles as raw numbers. Every one is a public member of the game's own objects,
	 * so no accessor is needed: the instance and the queue are what the backend keeps, and the
	 * physical device is the one LWJGL's {@code VkDevice} was created on.
	 */
	private static VulkanHandles read(VulkanDevice vulkan) {
		var queue = vulkan.graphicsQueue();

		return new VulkanHandles(vulkan.instance().vkInstance().address(),
				vulkan.vkDevice().getPhysicalDevice().address(), vulkan.vkDevice().address(),
				vulkan.vma(), queue.vkQueue().address(), queue.queueFamilyIndex());
	}

	private static @Nullable VulkanCommandEncoder recorder(CommandEncoder encoder) {
		return ((CommandEncoderAccessor) encoder).vitrail$backend() instanceof VulkanCommandEncoder vulkan
				? vulkan
				: null;
	}

	private static @Nullable VulkanDevice vulkan(GpuDevice device) {
		GpuDeviceBackend backend = ((GpuDeviceAccessor) device).vitrail$backend();

		return backend instanceof VulkanDevice found ? found : null;
	}
}
