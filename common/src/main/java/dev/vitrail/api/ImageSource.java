package dev.vitrail.api;

import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * Images an add-on renders itself and hands to pack programs by name, as a sampler or as a
 * storage image.
 * <p>
 * The pack declares them in its own source, which for a pack that never heard of the add-on means
 * a declaration added through a {@link SourcePatcher}: {@code uniform sampler2D name;} to read one,
 * or {@code layout(rgba8) uniform image2D name;} to store into one. The declared type is what
 * makes a name a sampler or a storage image, and a storage declaration carries its own format
 * qualifier. Both graphics passes and compute dispatches of the pack see the image.
 * <p>
 * A name must not be one the pack or Vitrail already gives a meaning to, such as
 * {@code colortex0}, {@code depthtex1}, {@code shadowtex0}, {@code noisetex}, one of the pack's own
 * {@code image.} names or a texture it ships. A claim on such a name is refused with a log line and
 * the name stays unserved by the add-on; of two add-ons claiming one name, the one registered
 * first keeps it.
 * <p>
 * <strong>The image layout is {@code VK_IMAGE_LAYOUT_GENERAL}, for a sampled image as much as
 * for a storage one.</strong> Vitrail and the game write every image descriptor with that layout
 * and keep every image of their own in it, and nothing Vitrail records changes the layout of an
 * image it was handed. An image that is bound in {@code VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL}
 * disagrees with its descriptor, which is a validation error and on some drivers a wrong picture.
 * The add-on therefore moves an image to {@code GENERAL} once, when it creates it, and leaves it
 * there; if it needs another layout while it records in a {@link StageListener}, it moves the
 * image back before it returns.
 * <p>
 * Memory follows the same rule as the layout: the add-on needs no barrier of its own for the
 * pack's reads. What it wrote in a {@link StageListener} is made visible to every pass that follows
 * by the barrier Vitrail records after the listeners, and what it wrote in an earlier frame by
 * the barriers between the passes of the frame.
 * <p>
 * <strong>What the image has to be.</strong> A two dimensional view of one level, created for
 * sampling, and for storage as well when {@link AddonImage#storage()} says so. It is sampled with
 * nearest filtering and clamped addressing, so a pack that wants interpolation does it itself; a
 * pack that reads with {@code texelFetch} needs neither. The pack's declared sampler type has to
 * agree with the format: {@code sampler2D} for a float or normalised one, {@code usampler2D} for
 * an unsigned integer one.
 * <p>
 * The image must stay alive and in that layout until the last frame that bound it has finished
 * on the GPU, which is up to two frames after the one that was handed it.
 */
public interface ImageSource {

	/** The names this add-on serves, fixed for as long as the add-on is loaded. */
	Set<String> names();

	/**
	 * The image to bind to {@code name} for the pass or dispatch being recorded, or null when the
	 * add-on has nothing this frame.
	 * <p>
	 * Called on the render thread, while the pass is recording and once for every descriptor push
	 * of the name, so it answers from a field and records nothing: no command, and nothing on the
	 * game's encoder.
	 * <p>
	 * For a null, an image that has no view, or a storage declaration answered with an image that
	 * cannot be stored into, Vitrail binds a stand-in the size of the screen, and logs the last two
	 * once: opaque black for a name the pack samples, and for a name it declares as a storage image
	 * a scratch image of the same size that the pack may write and nothing reads. Never a single
	 * texel, because a pack may read the name with {@code texelFetch} at any pixel of the screen.
	 * A storage name should be served on every frame the pack reads it, since the scratch is not the
	 * pack's data.
	 */
	@Nullable AddonImage image(String name);
}
