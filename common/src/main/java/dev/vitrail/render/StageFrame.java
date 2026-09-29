package dev.vitrail.render;

import dev.vitrail.addon.TargetRef;
import dev.vitrail.api.AddonImage;
import dev.vitrail.api.FrameContext;
import dev.vitrail.api.VulkanHandles;
import dev.vitrail.pack.target.TargetSchedule;

import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanConst;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import org.jspecify.annotations.Nullable;

/**
 * The frame as a stage listener is handed it: the frame's command buffer, the device's handles and
 * the pack's targets as they stand at the moment the stage runs.
 * <p>
 * Made for one call and closed when it returns, so that an add-on which keeps the object past it
 * gets an exception at its next use rather than a stale command buffer. The handles it hands out
 * are the ones Vitrail itself records with; nothing here is copied or wrapped, which is why the
 * add-on may not keep them either.
 * <p>
 * <strong>Which half of a colour target {@code target} answers.</strong> A target the pack turns
 * over carries two textures, and where the pass now reading it stands is a matter of the schedule
 * and not of the target. At the stage it is the half the next pass of the pack's chain reads, the
 * step that pass is bound to: at the deferred stage the half the opaque geometry wrote, moved on by
 * whatever {@code flip.deferred_pre} the pack states, and after a program the half it wrote moved
 * on by whatever the pack flips before the pass that follows. Where the chain has no pass left, or
 * the pass that follows is the final, which has no step, the half the frame ends on stands, which
 * is the main half unless the pack turns the target over in the end. The add-on that writes it is
 * writing what that pass reads first; the one that reads it is reading what that pass is about to
 * read.
 * <p>
 * <strong>Which depth {@code target} answers.</strong> The images the pass before the stage read
 * under the three names, by the same rule a pass binds them ({@link PackPass#depth}). The first
 * name is the depth of the range the pass belongs to, and null where that range runs before the
 * world is drawn and the pass reads the far plane.
 */
final class StageFrame implements FrameContext {

	private final long frame;
	private final long commandBuffer;
	private final VulkanHandles handles;
	private final ColorTargets targets;
	private final @Nullable String program;
	private final TargetSchedule.@Nullable Bound step;
	private final @Nullable GpuTextureView depthView;
	private boolean closed;

	/**
	 * @param program   the pack program an {@code AFTER_PROGRAM} call follows, null at any other stage
	 * @param step      the step of the pass that draws next, whose reads decide which half of each
	 *                  colour target is handed over; null where none does
	 * @param depthView what the passes of the stage's range read as {@code depthtex0}, null for the
	 *                  far plane
	 */
	StageFrame(long frame, long commandBuffer, VulkanHandles handles, ColorTargets targets,
			@Nullable String program, TargetSchedule.@Nullable Bound step,
			@Nullable GpuTextureView depthView) {
		this.frame = frame;
		this.commandBuffer = commandBuffer;
		this.handles = handles;
		this.targets = targets;
		this.program = program;
		this.step = step;
		this.depthView = depthView;
	}

	@Override
	public long frame() {
		return this.frame;
	}

	@Override
	public @Nullable String program() {
		return this.program;
	}

	@Override
	public long commandBuffer() {
		live();

		return this.commandBuffer;
	}

	@Override
	public VulkanHandles vulkan() {
		return this.handles;
	}

	@Override
	public @Nullable AddonImage target(String name) {
		live();

		TargetRef ref = TargetRef.parse(name);
		if (ref == null) {
			return null;
		}

		return ref.depth() ? depth(ref.index()) : colour(ref.index());
	}

	/** Ends the frame's validity, called by the stage once every listener has returned. */
	void close() {
		this.closed = true;
	}

	private void live() {
		if (this.closed) {
			throw new IllegalStateException("The FrameContext is only valid during the onStage call "
					+ "it was handed to");
		}
	}

	private @Nullable AddonImage colour(int index) {
		TargetSurface surface = this.targets.surface(index, side(index));
		if (surface == null) {
			return null;
		}

		// The base level alone where the target can be stored into, which is the one view a storage
		// descriptor takes; otherwise the whole chain, which is what a sampler reads.
		return image(surface.storage() ? surface.storageView() : surface.view(), surface.storage());
	}

	/**
	 * The half the pass that draws next reads, and where none does, or it is the final, the half the
	 * frame ends on: the final has no step of its own and reads that one.
	 */
	private TargetSchedule.Side side(int index) {
		if (this.step != null) {
			return this.step.read(index);
		}

		return this.targets.schedule().flippedAtEnd().contains(index)
				? TargetSchedule.Side.ALT
				: TargetSchedule.Side.MAIN;
	}

	/**
	 * What a pass binds under the name, as {@link PackPass#depth} settles it: {@code depthtex0} is
	 * the depth of the pass's own range, {@code depthtex1} the opaque copy and {@code depthtex2}
	 * the copy from before the hand where the engine took one and the opaque one where not, each
	 * falling back to the range's depth while nothing has filled it. Null where that is nothing
	 * too, and never the one-texel constant a pass falls back to: a coordinate past that texel is
	 * not an image an add-on can use.
	 */
	private @Nullable AddonImage depth(int index) {
		GpuTextureView view = null;
		if (index != 0) {
			PackDepth depth = this.targets.depth();
			view = index == 2 ? depth.preHand() : null;
			if (view == null) {
				view = depth.opaque();
			}
		}

		return image(view == null ? this.depthView : view, false);
	}

	private static @Nullable AddonImage image(@Nullable GpuTextureView view, boolean storage) {
		if (!(view instanceof VulkanGpuTextureView vulkan)) {
			return null;
		}

		VulkanGpuTexture texture = vulkan.texture();

		return new AddonImage(vulkan.vkImageView(), texture.vkImage(),
				VulkanConst.toVk(texture.getFormat()), vulkan.getWidth(0), vulkan.getHeight(0), storage);
	}
}
