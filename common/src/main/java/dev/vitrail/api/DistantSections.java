package dev.vitrail.api;

import java.util.List;
import java.util.Objects;

/**
 * The far terrain of one frame: what the camera's two halves draw and what the light's two halves
 * draw.
 * <p>
 * The opaque half is drawn just before the game's own opaque terrain and the water half just
 * before its translucent terrain, so that the near world covers the far one. Sections are drawn in
 * the order listed. The opaque half is depth tested and any order does; the water half blends over
 * what is behind it, and a source that wants it right lists its water from far to near.
 * <p>
 * <strong>The lists are read after {@link DistantTerrainSource#frame} returns, up to the end of
 * the frame, and are never copied.</strong> A list must therefore not change until the next call
 * to {@code frame}. A source that keeps its sections in lists it also edits hands over a copy; a
 * source that rebuilds them only when something moved hands the same immutable lists again.
 *
 * @param opaque the camera's opaque half, culled against what the camera sees
 * @param water the camera's water half, from the same culling
 * @param shadowOpaque what the light's opaque half draws. It is not the camera's list because a
 *     hill behind the camera still casts a shadow in front of it, so a source with the means to
 *     does not cull this against the camera
 * @param shadowWater what the light's water half draws
 */
public record DistantSections(
		List<DistantSection> opaque,
		List<DistantSection> water,
		List<DistantSection> shadowOpaque,
		List<DistantSection> shadowWater) {

	/** Nothing to draw this frame. */
	public static final DistantSections NONE = new DistantSections(List.of(), List.of());

	public DistantSections {
		Objects.requireNonNull(opaque, "opaque");
		Objects.requireNonNull(water, "water");
		Objects.requireNonNull(shadowOpaque, "shadowOpaque");
		Objects.requireNonNull(shadowWater, "shadowWater");
	}

	/** Sections for the camera, which the light draws as they stand. */
	public DistantSections(List<DistantSection> opaque, List<DistantSection> water) {
		this(opaque, water, opaque, water);
	}
}
