package dev.vitrail.api;

import java.nio.ByteBuffer;

/**
 * One pass of one built section: quads of four vertices each, in the layout {@code layout}
 * describes.
 *
 * @param pass which of Sodium's passes the quads belong to
 * @param vertices read-only view of Sodium's vertex data, valid only during the call it was
 *     handed in; position 0, limit {@code vertexCount * layout.stride()}
 * @param vertexCount number of vertices, a multiple of four
 * @param layout where each attribute sits in a vertex
 */
public record TerrainSectionMesh(TerrainPass pass, ByteBuffer vertices, int vertexCount, TerrainVertexLayout layout) {
}
