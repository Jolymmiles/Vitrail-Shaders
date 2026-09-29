package dev.vitrail.api;

/** Sodium's three terrain passes, which is also how a mesh says whether its texels may be cut. */
public enum TerrainPass {

	/** Opaque geometry. */
	SOLID,

	/** Geometry whose texels are discarded below an alpha threshold: leaves, grass, flowers. */
	CUTOUT,

	/** Blended geometry: water, glass, ice. Its vertices are in build order, not sorted. */
	TRANSLUCENT
}
