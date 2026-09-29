package dev.vitrail.api;

import java.util.List;

/**
 * One section as Sodium built it: its place and a mesh per pass that has any geometry.
 *
 * @param x section x, block x divided by 16
 * @param y section y, block y divided by 16
 * @param z section z, block z divided by 16
 * @param meshes one mesh per non-empty pass; empty when the section has no geometry left
 */
public record TerrainSection(int x, int y, int z, List<TerrainSectionMesh> meshes) {

	public TerrainSection {
		meshes = List.copyOf(meshes);
	}
}
