package dev.vitrail.api;

import java.util.Set;

/**
 * A copy of the terrain meshes, taken as Sodium hands a built section over for upload, and the
 * attributes those meshes must carry.
 */
public interface TerrainMeshListener {

	/**
	 * The attributes this add-on needs in every terrain vertex whether the pack reads them or not.
	 * Read once, before Sodium's renderer is created; a change of the set the vertex carries
	 * rebuilds the world, as a pack that reads a new attribute does.
	 */
	Set<TerrainAttribute> attributes();

	/**
	 * A section was built or rebuilt. Called on the render thread before its meshes are uploaded;
	 * the buffers are Sodium's and are freed right after this returns, so the add-on copies what
	 * it keeps and returns quickly.
	 */
	void built(TerrainSection section);

	/** A section's meshes are gone, in section coordinates (block coordinates divided by 16). */
	void removed(int x, int y, int z);
}
