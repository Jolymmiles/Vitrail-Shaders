package dev.vitrail.api;

import java.util.Set;

/**
 * A copy of the terrain meshes, taken as Sodium hands a built section over for upload, and the
 * attributes those meshes must carry.
 */
public interface TerrainMeshListener {

	/**
	 * The attributes this add-on needs in every terrain vertex whether the pack reads them or not.
	 * Read once, before Sodium's renderer is created, by whichever thread first needs it: the
	 * loader thread that reads the pack while the game starts, or the render thread when no pack is
	 * read first. A change of the set the vertex carries rebuilds the world, as a pack that reads a
	 * new attribute does.
	 */
	Set<TerrainAttribute> attributes();

	/**
	 * A section was built or rebuilt. Called on the render thread before its meshes are uploaded;
	 * the buffers are Sodium's and are freed right after this returns, so the add-on copies what
	 * it keeps and returns quickly.
	 * <p>
	 * A build replaces everything the section held: a pass with no mesh in it has no geometry any
	 * more, and a section that became empty arrives here with an empty list of meshes, not through
	 * {@link #removed}.
	 */
	void built(TerrainSection section);

	/**
	 * A section is gone, in section coordinates (block coordinates divided by 16). Called once for
	 * each section Sodium drops, including ones that never had a mesh, when a chunk unloads and for
	 * every section when the renderer is torn down, which is when the world is rebuilt. Meshes built
	 * afterwards may have another layout.
	 */
	void removed(int x, int y, int z);
}
