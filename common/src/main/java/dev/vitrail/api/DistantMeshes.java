package dev.vitrail.api;

import java.nio.ByteBuffer;

/**
 * Where a far terrain source puts its geometry: Vitrail copies it to the GPU and keeps it there,
 * and the source lists what it wants drawn each frame by the handles this gives back.
 * <p>
 * Vitrail owns the buffers, which is what keeps the game's graphics types out of this package and
 * what lets a mesh be handed over from any thread: the copy to the GPU is made on the render
 * thread, a little at a time (16 MiB a frame), and does not stall a worker that is busy generating
 * terrain. Handed over once, a mesh is drawn from the same buffers every frame after.
 * <p>
 * <strong>The vertex layout is Distant Horizons' own, sixteen bytes a vertex, little-endian, and
 * the pack's {@code dh_} programs read it as they read Distant Horizons' meshes.</strong>
 * <ul>
 * <li>bytes 0 to 5: the position, three unsigned 16-bit integers, whole blocks inside the section
 *     (see {@link DistantSection});</li>
 * <li>bytes 6 and 7: an unsigned 16-bit word of meta. Bits 0 to 3 are the sky light and bits 4 to 7
 *     the block light, each 0 to 15. Bits 8 and 12 say the x and the z of the vertex are nudged by
 *     a hundredth of a block, which pulls two faces meeting at a corner apart so that the seam
 *     between them does not flicker, and bits 9 and 13 make that nudge negative instead. The other
 *     bits are unused;</li>
 * <li>bytes 8 to 11: the colour, red, green, blue and alpha as unsigned bytes;</li>
 * <li>byte 12: the material, the block kind a pack reads as {@code dhMaterialId}, the value of one
 *     of the {@code DH_BLOCK_*} defines (0 to 15);</li>
 * <li>byte 13: the face the vertex belongs to, 0 to 5 for down, up, north, south, west, east;</li>
 * <li>bytes 14 and 15: not read, and part of the vertex all the same.</li>
 * </ul>
 * Triangles are wound as Distant Horizons winds its own, because the opaque half is drawn with back
 * faces culled. Indices are unsigned 32-bit integers, three to a triangle, counted from the first
 * vertex of the same mesh.
 */
public interface DistantMeshes {

	/**
	 * Copies one mesh and returns its handle at once; the GPU has it a frame or more later, and
	 * {@link DistantMesh#ready()} says when. Any thread may call it.
	 * <p>
	 * The bytes from each buffer's position to its limit are read during this call and copied, so
	 * the caller may reuse or free both buffers as soon as it returns, and their positions and
	 * limits are left as they were. Either may be a heap buffer or a direct one. The indices are
	 * checked against the vertices here, on the caller's thread and while they are being copied
	 * anyway, because an index past the last vertex is a read past the buffer on the GPU and that
	 * ends in a lost device with nothing to say which mesh did it.
	 *
	 * @param vertices vertex data in the layout above; a multiple of sixteen bytes
	 * @param indices unsigned 32-bit indices, a multiple of twelve bytes, each less than the number
	 *     of vertices
	 * @throws IllegalArgumentException if either buffer is empty or does not hold a whole number of
	 *     vertices or triangles, or an index does not name a vertex
	 * @throws IllegalStateException if 256 MiB of meshes are already waiting, which happens when the
	 *     render thread is not drawing frames, as while a world is being joined. A source paces
	 *     itself on {@link #pendingBytes()} and uploads again once it has fallen
	 */
	DistantMesh upload(ByteBuffer vertices, ByteBuffer indices);

	/**
	 * How many bytes of meshes are waiting for the render thread to copy them, which is what a
	 * source pacing itself against the GPU reads. It falls as the copies are made, at most 16 MiB a
	 * frame, and only frames of a level being drawn make any: it does not fall while a world is
	 * being joined.
	 */
	long pendingBytes();
}
