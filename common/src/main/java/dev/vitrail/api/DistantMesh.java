package dev.vitrail.api;

/**
 * A mesh Vitrail keeps on the GPU for an add-on, held by the handle {@link DistantMeshes#upload}
 * returned. Vitrail implements it; an add-on only holds and lists it.
 * <p>
 * Its lifetime is the add-on's to end: the buffers live until {@link #free} is called, and
 * however many frames list it meanwhile. What Vitrail owes in return is that a freed mesh is not
 * released while a frame that may still draw it is in flight.
 */
public interface DistantMesh {

	/**
	 * Whether the mesh has reached the GPU and is drawn when listed. False from the call to
	 * {@code upload} until the render thread has copied it, which is the next frame or a few
	 * after when many are waiting, and false for good once it is freed.
	 * <p>
	 * A source replacing a tile uploads the new mesh, keeps listing the old one until this is true
	 * for the new, and only then swaps them and frees the old, so that the tile is never missing
	 * from the picture. Any thread may ask.
	 */
	boolean ready();

	/**
	 * Gives the mesh back. Any thread may call it, at any time, and calling it again does nothing.
	 * The mesh must not be listed in a frame handed over after this call. A frame already handed
	 * over may still draw it, and does so safely: the buffers are released on the render thread at
	 * the head of a later frame, and then only once no frame in flight can read them.
	 */
	void free();
}
