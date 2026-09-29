package dev.vitrail.api;

import java.util.Map;

/**
 * Defines an add-on posts to the pack, next to the ones Vitrail poses itself.
 * <p>
 * They reach every program and are part of what the translation cache keys on, so a define that
 * changes value makes a new translation rather than serving the old one. A name Vitrail poses is
 * refused with a log line and the engine's value stays, because a pack reading {@code MC_VERSION}
 * or {@code IRIS_VERSION} must be able to trust it. That covers the names Vitrail withholds where
 * the machine lacks something, {@code DISTANT_HORIZONS} among them, since their absence is an
 * answer too, and every name of the families the machine picks one of: {@code MC_OS_},
 * {@code MC_GL_VENDOR_}, {@code MC_GL_RENDERER_} and {@code MC_TEXTURE_FORMAT_}. A name two
 * sources write with different values belongs to the first, in the order they were registered.
 */
public interface DefineSource {

	/**
	 * Adds this add-on's defines, name to value; an empty value is a bare {@code #define NAME}.
	 * Names follow the preprocessor's rule, an ASCII letter or an underscore and then ASCII
	 * letters, digits and underscores; a value holds no line break, since it is written on one
	 * line. Called whenever Vitrail gathers the pack's defines, on the thread that reads the pack:
	 * a loader thread for the first reading, while the game starts, and the render thread after
	 * it. {@link #revision} is asked on the same terms.
	 */
	void write(Map<String, String> defines);

	/**
	 * A number that changes whenever {@link #write} would write something else. Vitrail compares
	 * it between frames and reloads the pack when it moved, which is the only way a define can
	 * change under an open pack.
	 */
	default long revision() {
		return 0;
	}
}
