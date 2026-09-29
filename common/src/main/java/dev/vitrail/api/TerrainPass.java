package dev.vitrail.api;

/** Sodium's three terrain passes, which is also how a mesh says whether its texels may be cut. */
public enum TerrainPass {

	/** Opaque geometry. */
	SOLID,

	/** Geometry whose texels are discarded below an alpha threshold: leaves, grass, flowers. */
	CUTOUT,

	/**
	 * Blended geometry: water, glass, ice. Its quads are not sorted by depth: Sodium orders them
	 * for blending with an index buffer, which is not part of the copy.
	 */
	TRANSLUCENT
}
