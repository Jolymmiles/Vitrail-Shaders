package dev.vitrail.api;

import java.util.Map;

/**
 * Where the attributes sit in a terrain vertex.
 * <p>
 * The first 20 bytes are Sodium's compact vertex, the same in every layout: the position as two
 * 32-bit words at offset 0, colour at 8, texture coordinate at 12, and light and data at 16. The
 * position is three 20-bit fixed-point numbers, {@code (8 + p) / 32 * 2^20} for a position
 * {@code p} in blocks relative to the section's corner; word 0 holds the high ten bits of x, y
 * and z at bits 0, 10 and 20, word 1 the low ten bits in the same places.
 *
 * @param stride bytes per vertex
 * @param offsets byte offset of each attribute this layout carries beyond the first 20 bytes
 */
public record TerrainVertexLayout(int stride, Map<TerrainAttribute, Integer> offsets) {

	public TerrainVertexLayout {
		offsets = Map.copyOf(offsets);
	}
}
