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
 * step that pass is bound to: the half the opaque geometry wrote, moved on by whatever
 * {@code flip.deferred_pre} the pack states. Where the chain has no pass left the main half stands.
 * The add-on that writes it is writing what that pass reads first; the one that reads it is
 * reading what the deferred stage is about to read.
 */
final class StageFrame implements FrameContext {

	private final long frame;
	private final long commandBuffer;
	private final VulkanHandles handles;
	private final ColorTargets targets;
	private final TargetSchedule.@Nullable Bound step;
	private boolean closed;

	StageFrame(long frame, long commandBuffer, VulkanHandles handles, ColorTargets targets,
			TargetSchedule.@Nullable Bound step) {
		this.frame = frame;
		this.commandBuffer = commandBuffer;
		this.handles = handles;
		this.targets = targets;
		this.step = step;
	}

	@Override
	public long frame() {
		return this.frame;
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
		TargetSchedule.Side side = this.step == null ? TargetSchedule.Side.MAIN : this.step.read(index);
		TargetSurface surface = this.targets.surface(index, side);
		if (surface == null) {
			return null;
		}

		// The base level alone where the target can be stored into, which is the one view a storage
		// descriptor takes; otherwise the whole chain, which is what a sampler reads.
		return image(surface.storage() ? surface.storageView() : surface.view(), surface.storage());
	}

	/**
	 * {@code depthtex0} and {@code depthtex1} are the opaque world's depth, which is what the
	 * deferred passes read under both names, and {@code depthtex2} is the copy from before the hand
	 * where the engine took one. Null while no copy has been taken, and never the one-texel
	 * constant a pass falls back to: a coordinate past that texel is not an image an add-on can
	 * use.
	 */
	private @Nullable AddonImage depth(int index) {
		PackDepth depth = this.targets.depth();
		GpuTextureView view = index == 2 ? depth.preHand() : null;
		if (view == null) {
			view = depth.opaque();
		}

		return image(view, false);
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
