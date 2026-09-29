package dev.vitrail.render;

import dev.vitrail.addon.AddonImageNames;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import org.joml.Vector4f;
import org.joml.Vector4fc;

/**
 * The two images {@link AddonImages} binds where an add-on's source has nothing for a name, both
 * the size of the screen: opaque black for a name the shader samples, and a scratch image for one
 * it declares as a storage image.
 * <p>
 * Allocated only while some add-on serves an image name, and sized with the colour targets, so a
 * player with no add-on pays nothing and one with an add-on pays two screens of RGBA8 at most. The
 * scratch is a separate image because a pack may store into it: shared with the black one, the
 * first such store would turn every later read of nothing into whatever the pack wrote.
 * <p>
 * The scratch is RGBA8, and a shader whose layout qualifier names another format stores into it
 * as that format's bits. That only happens on a frame the source served nothing for a storage
 * name, which the log has already said, and the bytes are never read back as anything.
 */
final class AddonStandIns {

	private static final GpuFormat FORMAT = GpuFormat.RGBA8_UNORM;

	private static final Vector4fc OPAQUE_BLACK = new Vector4f(0.0F, 0.0F, 0.0F, 1.0F);

	private TargetSurface black;
	private TargetSurface scratch;

	/**
	 * Makes the images exist at this screen size when an add-on needs them, and publishes their
	 * views. Outside any pass, like everything in {@link ColorTargets#ensure}.
	 *
	 * @return whether anything was allocated, which owes {@link #clear} a run
	 */
	boolean ensure(int width, int height) {
		if (!AddonImageNames.active()) {
			return false;
		}

		boolean changed = false;
		if (this.black == null) {
			this.black = new TargetSurface("Vitrail add-on black", FORMAT, false, width, height);
			changed = true;
		} else {
			changed = this.black.resize(width, height);
		}

		// Only for a pack that declares a served name as a storage image, and only on a device that
		// makes a storage image of the format: the descriptor then has nothing to stand on, and
		// AddonImages says so where it is asked.
		if (AddonImageNames.declaresStorage() && GpuFormats.storageCapable(FORMAT)) {
			if (this.scratch == null) {
				this.scratch = new TargetSurface("Vitrail add-on scratch", FORMAT, false, true, width,
						height);
				changed = true;
			} else {
				changed |= this.scratch.resize(width, height);
			}
		}

		publish();

		return changed;
	}

	/** Empties both after an allocation: what the driver hands back is not black. */
	void clear(CommandEncoder encoder) {
		if (this.black != null && this.black.texture() != null) {
			encoder.clearColorTexture(this.black.texture(), OPAQUE_BLACK);
		}

		if (this.scratch != null && this.scratch.texture() != null) {
			encoder.clearColorTexture(this.scratch.texture(), OPAQUE_BLACK);
		}
	}

	void release() {
		AddonImages.standIns(0L, 0L);
		AddonImages.forget();
		if (this.black != null) {
			this.black.close();
			this.black = null;
		}

		if (this.scratch != null) {
			this.scratch.close();
			this.scratch = null;
		}
	}

	private void publish() {
		AddonImages.standIns(handle(this.black), handle(this.scratch));
	}

	private static long handle(TargetSurface surface) {
		return surface != null && surface.storageView() instanceof VulkanGpuTextureView view
				? view.vkImageView()
				: 0L;
	}
}
