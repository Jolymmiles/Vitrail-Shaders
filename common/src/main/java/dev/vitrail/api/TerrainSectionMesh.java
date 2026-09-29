package dev.vitrail.api;

import java.nio.ByteBuffer;

/**
 * One pass of one built section: all of that pass's quads, four vertices each, in the layout
 * {@code layout} describes.
 * <p>
 * A quad's four vertices run round its edge and are drawn as the triangles (0, 1, 2) and (2, 3, 0).
 * The mesh is whole: Sodium leaves out at draw time the groups of quads facing away from where the
 * camera stands, and those are here too.
 *
 * @param pass which of Sodium's passes the quads belong to
 * @param vertices read-only view of Sodium's vertex data, in the platform's byte order, valid only
 *     during the call it was handed in; position 0, limit {@code vertexCount * layout.stride()}
 * @param vertexCount number of vertices, a multiple of four
 * @param layout where each attribute sits in a vertex
 */
public record TerrainSectionMesh(TerrainPass pass, ByteBuffer vertices, int vertexCount, TerrainVertexLayout layout) {
}
