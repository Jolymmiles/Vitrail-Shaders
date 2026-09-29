package dev.vitrail.render;

import dev.vitrail.addon.AddonRegistry;
import dev.vitrail.addon.FrameClock;
import dev.vitrail.addon.StagePrograms;
import dev.vitrail.api.FrameStage;
import dev.vitrail.api.StageListener;
import dev.vitrail.api.VulkanHandles;
import dev.vitrail.mixin.access.CommandEncoderAccessor;
import dev.vitrail.mixin.access.GpuDeviceAccessor;
import dev.vitrail.mixin.access.VulkanCommandEncoderAccessor;
import dev.vitrail.render.storage.GpuRecording;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.Collection;
import java.util.List;

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
 * <p>
 * Both stages take one road, {@link #record}, so that what is true before a listener runs and what
 * is put right after it is one rule and not two that drift: {@code BEFORE_DEFERRED} once a frame
 * from the head of the deferred half, {@code AFTER_PROGRAM} from the walk over the chain's passes
 * after each program a listener named. The second is the one that can be called a dozen times a
 * frame, which is why what it looks up is a set settled once for a load ({@code StagePrograms}).
 */
final class AddonStages {

	/** Per session and never reset: the number an add-on counts frames by does not restart on a reload. */
	private static final FrameClock CLOCK = new FrameClock();

	private static final String RECORD_DEFERRED = "record at " + FrameStage.BEFORE_DEFERRED;
	private static final String RECORD_AFTER = "record at " + FrameStage.AFTER_PROGRAM;

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
	 * The programs the listeners want to be called after, out of the ones this pack runs. Settled
	 * once for a load, where the pack's passes are built, and empty without asking anyone where no
	 * listener is registered.
	 *
	 * @param running the bare names of the programs the chain draws or dispatches a compute for
	 * @param pack    what the log calls the pack by
	 */
	static StagePrograms programs(Collection<String> running, String pack) {
		return wanted() ? StagePrograms.settle(AddonRegistry.stages(), running, pack) : StagePrograms.NONE;
	}

	/** The number of the frame that starts, once for each frame the chain calls a stage in. */
	static long tick() {
		return CLOCK.tick();
	}

	/**
	 * {@link FrameStage#BEFORE_DEFERRED}: the opaque world is drawn, the depth copies are taken and
	 * no pass is open, and the first deferred pass is about to run.
	 *
	 * @param next  the pass of the chain that draws next, whose reads decide which half of each
	 *              colour target the listeners are handed; null where the chain has none left
	 * @param depth what the deferred passes read as {@code depthtex0}
	 * @param frame the frame's number, the same for every stage of it
	 */
	static void beforeDeferred(GpuDevice device, ColorTargets targets, @Nullable PackPass next,
			@Nullable GpuTextureView depth, long frame) {
		record(device, targets, FrameStage.BEFORE_DEFERRED, null, next, depth, frame,
				AddonRegistry.stages(), true);
	}

	/**
	 * {@link FrameStage#AFTER_PROGRAM}: the program has drawn, its computes have run and no pass is
	 * open, and the pass that follows it has not begun.
	 * <p>
	 * The level's pass is not suspended here, unlike at the deferred stage: a program's range is
	 * entered with it already suspended or not yet opened, on both games, and nothing inside the
	 * range gives it a draw to reopen for. The pack's own computes in the same loop record outside
	 * a pass on that footing, and every pass of the range opens through {@code createRenderPass},
	 * which suspends it first on the game that has one.
	 *
	 * @param listeners the listeners that named the program
	 * @param program   the program that has run, by the pack's bare name
	 * @param next      the pass that draws after it, whose reads decide which half of each colour
	 *                  target the listeners are handed; null where the chain has none left
	 * @param depth     what the program read as {@code depthtex0}, null for the far plane
	 * @param frame     the frame's number, the same for every stage of it
	 */
	static void afterProgram(GpuDevice device, ColorTargets targets,
			List<AddonRegistry.Entry<StageListener>> listeners, String program, @Nullable PackPass next,
			@Nullable GpuTextureView depth, long frame) {
		record(device, targets, FrameStage.AFTER_PROGRAM, program, next, depth, frame, listeners, false);
	}

	/**
	 * The one road every stage takes: what has to be true before a listener records, the call, and
	 * what has to be true after, so that two stages cannot disagree about either.
	 */
	private static void record(GpuDevice device, ColorTargets targets, FrameStage stage,
			@Nullable String program, @Nullable PackPass next, @Nullable GpuTextureView depth, long frame,
			List<AddonRegistry.Entry<StageListener>> listeners, boolean levelPass) {
		CommandEncoder encoder = device.createCommandEncoder();
		VulkanCommandEncoder recorder = recorder(encoder);
		VulkanDevice vulkan = vulkan(device);
		if (recorder == null || vulkan == null) {
			return;
		}

		// The same steps the pack's computes take before they record, in the same order: the hold
		// that keeps a pass open across draws first, and only then the pass under it.
		GeometryHold.flush(() -> "the add-on stage " + stage);
		GpuRecording.endPass(encoder);
		if (levelPass) {
			GraphicsApi.suspendLevelPass();
		}

		// The clears still owed are paid first, whatever was drawn so far: a target no pass has
		// attached yet this frame is emptied by the load-op of the first one that does, and that
		// would erase what a listener wrote into it, or hand it the frame before's image to read.
		targets.flushPending(encoder);

		VkCommandBuffer commands = ((VulkanCommandEncoderAccessor) recorder).vitrail$commandBuffer();
		StageFrame handed = new StageFrame(frame, commands.address(), handles(vulkan), targets, program,
				next == null ? null : next.step(), depth);
		barrier(commands);
		try {
			AddonRegistry.each(listeners, what(stage), listener -> listener.onStage(stage, handed));
		} finally {
			handed.close();
			GpuRecording.endPass(encoder);
			barrier(commands);
		}
	}

	/** What a failure is logged as having been asked to do, made once so that a frame allocates none. */
	private static String what(FrameStage stage) {
		return stage == FrameStage.BEFORE_DEFERRED ? RECORD_DEFERRED : RECORD_AFTER;
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
