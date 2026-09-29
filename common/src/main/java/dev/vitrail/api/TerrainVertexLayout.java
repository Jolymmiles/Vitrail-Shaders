package dev.vitrail.api;

import java.util.Map;

/**
 * Where the attributes sit in a terrain vertex.
 * <p>
 * The first 20 bytes are Sodium's compact vertex, as Sodium 0.9 writes it and the same in every
 * layout: the position as two 32-bit words at offset 0, colour at 8, texture coordinate at 12, and
 * light and data at 16.
 * <p>
 * The position is three 20-bit fixed-point numbers. A coordinate {@code p}, in blocks from the
 * minimum corner of the section the vertex belongs to, is stored as the integer part of
 * {@code (8 + p) / 32 * 2^20} taken to 20 bits: steps of 2^-15 of a block over {@code [-8, 24)},
 * where anything outside wraps round. Word 0 holds the high ten bits of x, y and z at bits 0, 10
 * and 20, word 1 the low ten bits in the same places, so {@code p = (hi * 1024 + lo) / 32768 - 8}.
 * The corner is 16 times the section's own coordinates, in {@link TerrainSection}: not the corner of
 * the region and not the world's origin.
 * <p>
 * Light and data are one byte each, in order: block light, sky light, Sodium's material bits, and
 * the section's index in its region, {@code (x & 7) << 5 | (z & 7) << 2 | (y & 3)}. That index is
 * a function of the section's coordinates and is not needed to place the vertex.
 *
 * @param stride bytes per vertex
 * @param offsets byte offset of each attribute this layout carries beyond the first 20 bytes
 */
public record TerrainVertexLayout(int stride, Map<TerrainAttribute, Integer> offsets) {

	public TerrainVertexLayout {
		offsets = Map.copyOf(offsets);
	}
}
