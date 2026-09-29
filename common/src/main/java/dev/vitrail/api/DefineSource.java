package dev.vitrail.api;

import java.util.Map;

/**
 * Defines an add-on posts to the pack, next to the ones Vitrail poses itself.
 * <p>
 * They reach every program and are part of what the translation cache keys on, so a define that
 * changes value makes a new translation rather than serving the old one. A name Vitrail already
 * poses is refused with a log line and the engine's value stays, because a pack reading
 * {@code MC_VERSION} or {@code IRIS_VERSION} must be able to trust it.
 */
public interface DefineSource {

	/**
	 * Adds this add-on's defines, name to value; an empty value is a bare {@code #define NAME}.
	 * Names follow the preprocessor's rule, a letter or an underscore and then letters, digits
	 * and underscores. Called on the render thread whenever Vitrail gathers the pack's defines.
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
