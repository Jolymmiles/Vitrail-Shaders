package dev.vitrail.api;

import java.util.List;

/**
 * One piece of far terrain: a corner in the world and the meshes drawn from it.
 * <p>
 * The vertices of a mesh hold whole-block positions inside the section, three unsigned 16-bit
 * integers wide, and the corner is what places them: a vertex at {@code (px, py, pz)} stands at
 * {@code (x + px, y + py, z + pz)}. So a section reaches at most 65535 blocks from its corner along
 * each axis, and a source whose tiles are larger than that cuts them into several sections. The
 * corner is taken from the camera in double precision before it is narrowed, so a corner far from
 * the origin of the world costs no precision, and the whole range is worth using.
 * <p>
 * Every mesh of one section shares the corner. A section is a plain value and may be listed in any
 * number of frames and in more than one list of {@link DistantSections}.
 *
 * @param x world x of the corner, in blocks
 * @param y world y of the corner, in blocks
 * @param z world z of the corner, in blocks
 * @param meshes what is drawn from it. A mesh that is not {@linkplain DistantMesh#ready() ready}
 *     yet, or that has been freed, is skipped when the section is drawn
 */
public record DistantSection(int x, int y, int z, List<DistantMesh> meshes) {

	public DistantSection {
		meshes = List.copyOf(meshes);
	}
}
