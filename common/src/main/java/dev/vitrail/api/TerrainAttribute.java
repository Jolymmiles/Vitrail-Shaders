package dev.vitrail.api;

/**
 * Attributes a terrain vertex can carry beyond Sodium's own, the ones packs read.
 * <p>
 * Each takes one 32-bit word at the offset {@link TerrainVertexLayout#offsets()} gives, in the byte
 * order of the mesh. What belongs to the quad and not to a corner is the same on its four vertices.
 */
public enum TerrainAttribute {

	/**
	 * The block's id from the pack's {@code block.properties}, as {@code mc_Entity} reads it. The word
	 * is {@code ((id + 1) << 1) | isFluid}: the id is {@code (word >>> 1) - 1}, so nought is a block
	 * the pack gives no id, which is every block when no pack is loaded, and the low bit is set on
	 * the quads of a fluid.
	 */
	BLOCK_ID,

	/**
	 * The middle of the quad's texture rectangle in the atlas, the centre of the sprite for a quad
	 * that maps all of it, as {@code mc_midTexCoord}. Two unsigned 16-bit halves, u in the low one,
	 * each the mean of the quad's four corner coordinates times 32768, rounded.
	 */
	MID_TEX_COORD,

	/**
	 * The offset to the block's centre and the block's light emission, as {@code at_midBlock}. Four
	 * signed bytes: the first three are the offset from the vertex to the middle of its block in
	 * sixty-fourths of a block, the fourth is the light the block gives off, 0 to 15.
	 */
	MID_BLOCK,

	/**
	 * The quad's normal, the tangent of its texture mapping and the handedness of the frame the two
	 * build, as {@code at_tangent} and the normal. Bits 0 to 11 are the normal's octahedral x as a
	 * signed number over 2047, bits 12 to 22 its octahedral y as a signed number over 1023 (the
	 * third component is {@code 1 - |x| - |y|}, folded over the diagonals where that is negative),
	 * bit 23 is set when the tangent's handedness sign is positive, and bits 24 to 31 are the tangent as an angle
	 * round the normal's plane, measured in Frisvad's basis of the decoded normal.
	 */
	TANGENT_FRAME
}
