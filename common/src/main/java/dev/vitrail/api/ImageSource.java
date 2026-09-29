package dev.vitrail.api;

import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * Images an add-on renders itself and hands to pack programs by name, as a sampler or as a
 * storage image.
 * <p>
 * The pack declares them in its own source, which for a pack that never heard of the add-on means
 * a declaration added through a {@link SourcePatcher}. A name the add-on serves wins over nothing
 * else: it must not be one the pack or Vitrail already gives meaning to, such as
 * {@code colortex0} or {@code shadowtex0}, and such a name is refused with a log line.
 */
public interface ImageSource {

	/** The names this add-on serves, fixed for as long as the add-on is loaded. */
	Set<String> names();

	/**
	 * The image to bind to {@code name} for the pass being recorded, or null when the add-on has
	 * nothing this frame. Vitrail then binds a black image the size of the screen rather than a
	 * single texel, because a pack may read it with {@code texelFetch} at any pixel.
	 */
	@Nullable AddonImage image(String name);
}
