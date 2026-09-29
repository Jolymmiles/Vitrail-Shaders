package dev.vitrail.api;

/** Attributes a terrain vertex can carry beyond Sodium's own, the ones packs read. */
public enum TerrainAttribute {

	/** The block's id from the pack's {@code block.properties}, as {@code mc_Entity} reads it. */
	BLOCK_ID,

	/** The centre of the vertex's sprite in the atlas, as {@code mc_midTexCoord}. */
	MID_TEX_COORD,

	/** The offset to the block's centre and the block's light emission, as {@code at_midBlock}. */
	MID_BLOCK,

	/** The tangent frame, as {@code at_tangent} and the normal. */
	TANGENT_FRAME
}
