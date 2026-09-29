package dev.vitrail.addon;

import dev.vitrail.api.DistantMesh;
import dev.vitrail.api.DistantMeshes;
import dev.vitrail.dh.DhLods.Piece;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.jspecify.annotations.Nullable;

/**
 * The meshes one far terrain source has handed over: which wait for the render thread to copy them
 * to the GPU, which are there, and which are to be given back.
 * <p>
 * <strong>Two sides that never share a lock.</strong> The add-on's threads call {@link #upload} and
 * {@link Handle#free}, and both only put a handle on a queue; the render thread calls {@link #drain}
 * at the head of a frame and is the only one that ever touches the device. So a worker generating
 * terrain is never held up by the GPU, and nothing here needs the render thread to be in a
 * particular place when the add-on calls.
 * <p>
 * <strong>The bytes are copied at the call and not kept as the caller's.</strong> The caller's
 * buffers may be reused the moment {@code upload} returns, and a copy that is dropped with its
 * handle can never leak, which a buffer taken over from a caller who allocated it some other way
 * could. The copy is where the indices are checked: it is read once anyway, and an index past the
 * last vertex is a read past the buffer on the GPU, which ends in a lost device that names nobody.
 * <p>
 * <strong>Releasing is left to the game's own retirement.</strong> A buffer is closed on the render
 * thread, at the head of a frame, and closing a buffer queues it on the backend's two-deep
 * destruction queue, the one {@code GpuRecording#destroyLater} queues on: it is destroyed only
 * once the submissions that may still read it are done. The frame that listed a mesh handed it
 * over before the head of the next frame, so a mesh freed while that frame draws is still there
 * for the frame's last draw, the light's, at its tail.
 * <p>
 * What is drawn out of a handle is its {@link Handle#piece piece}, one object made when the mesh is
 * copied, so a frame that lists thousands of meshes allocates nothing per mesh.
 */
public final class MeshTable implements DistantMeshes {

	/**
	 * Bytes copied to the GPU in one {@link #drain}, counted after each mesh so that one larger
	 * than the budget still goes through by itself instead of waiting for ever. The copy is a
	 * transfer into the frame's own command buffer, so what this bounds is the length of the head
	 * of the frame and the size of the staging memory the backend has to have to hand.
	 */
	public static final long UPLOAD_BUDGET = 16L << 20;

	/**
	 * Bytes of meshes that may wait for the render thread at once. The render thread is not always
	 * drawing frames: a world is being joined, a menu is up, or the pack is off, and meanwhile the
	 * add-on's own threads go on generating terrain. Without a limit the copies made at the call
	 * would grow with them, so an upload that would go past it throws and the add-on paces itself
	 * on {@link #pendingBytes}. Generous, because it is only ever reached when nothing drains: at
	 * {@link #UPLOAD_BUDGET} a frame it is sixteen frames of copying.
	 */
	public static final long MAX_PENDING_BYTES = 256L << 20;

	/** What one vertex is, which is the layout of Distant Horizons' own mesh. */
	public static final int VERTEX_BYTES = 16;

	private static final int INDEX_BYTES = 4;
	private static final int TRIANGLE_BYTES = 3 * INDEX_BYTES;

	/**
	 * What the GPU side owes the table. Kept apart so that the bookkeeping needs no device and can
	 * be checked without one.
	 */
	public interface Gpu {

		/**
		 * Copies one mesh into buffers of the GPU and returns what draws them. Both buffers hold
		 * the whole mesh from position 0.
		 */
		Piece create(ByteBuffer vertices, ByteBuffer indices, int indexCount);

		/** Gives the buffers of one piece back to the game's retirement. */
		void destroy(Piece piece);
	}

	/** Meshes handed over and not yet copied, in the order they arrived. */
	private final Queue<Handle> pending = new ConcurrentLinkedQueue<>();

	/** Meshes freed by the add-on and not yet given back. */
	private final Queue<Handle> retired = new ConcurrentLinkedQueue<>();

	/** Every mesh that holds bytes or buffers, which is what a close has to walk. */
	private final Set<Handle> live = ConcurrentHashMap.newKeySet();

	private final AtomicLong pendingBytes = new AtomicLong();

	private final long budget;
	private final long cap;

	private volatile boolean closed;

	public MeshTable() {
		this(UPLOAD_BUDGET, MAX_PENDING_BYTES);
	}

	/** A table with limits of its own, so that they can be reached with small meshes. */
	MeshTable(long budget, long cap) {
		this.budget = budget;
		this.cap = cap;
	}

	/**
	 * One mesh. Made on any thread, copied to the GPU and given back on the render thread, and
	 * asked about and freed on any.
	 */
	public final class Handle implements DistantMesh {

		private final int indexCount;
		private final long bytes;

		/** Set at birth and dropped on the render thread, the only place it is read. */
		private @Nullable ByteBuffer vertices;
		private @Nullable ByteBuffer indices;

		/** Render thread only: what draws it, once it is on the GPU. */
		private @Nullable Piece piece;

		private volatile boolean ready;
		private final AtomicBoolean freed = new AtomicBoolean();

		private Handle(ByteBuffer vertices, ByteBuffer indices, int indexCount) {
			this.vertices = vertices;
			this.indices = indices;
			this.indexCount = indexCount;
			this.bytes = (long) vertices.remaining() + indices.remaining();
		}

		@Override
		public boolean ready() {
			// Both halves are read because the render thread may raise the first after the add-on
			// has lowered the second: a handle that was freed must never answer true again.
			return this.ready && !this.freed.get();
		}

		@Override
		public void free() {
			if (this.freed.compareAndSet(false, true)) {
				MeshTable.this.retired.add(this);
			}
		}

		/**
		 * What draws this mesh, or null before it is on the GPU and after it has been given back.
		 * Render thread only. A mesh freed and not yet given back still answers: the frame that
		 * listed it may draw it, and the buffers stay until the head of the next frame.
		 */
		public @Nullable Piece piece() {
			return this.piece;
		}

		/** Render thread only. Drops what was copied and not yet uploaded, once. */
		private void dropBytes() {
			if (this.vertices != null) {
				MeshTable.this.pendingBytes.addAndGet(-this.bytes);
				this.vertices = null;
				this.indices = null;
			}
		}

		/** Render thread only. Gives everything back, and does nothing the second time. */
		private void release(Gpu gpu) {
			dropBytes();
			Piece held = this.piece;
			this.piece = null;
			this.ready = false;
			MeshTable.this.live.remove(this);
			if (held != null) {
				gpu.destroy(held);
			}
		}
	}

	@Override
	public DistantMesh upload(ByteBuffer vertices, ByteBuffer indices) {
		int vertexBytes = vertices.remaining();
		int indexBytes = indices.remaining();
		if (vertexBytes == 0 || vertexBytes % VERTEX_BYTES != 0) {
			throw new IllegalArgumentException("A mesh's vertices are a whole number of " + VERTEX_BYTES
					+ " byte vertices and not empty, and these are " + vertexBytes + " bytes");
		}

		if (indexBytes == 0 || indexBytes % TRIANGLE_BYTES != 0) {
			throw new IllegalArgumentException("A mesh's indices are a whole number of triangles of "
					+ TRIANGLE_BYTES + " bytes and not empty, and these are " + indexBytes + " bytes");
		}

		// Before the copy, which is what the limit is for. One mesh is always taken when nothing waits,
		// so a mesh larger than the whole limit is not refused for ever.
		long waiting = this.pendingBytes.get();
		if (waiting > 0L && waiting + vertexBytes + indexBytes > this.cap) {
			throw new IllegalStateException(waiting + " bytes of meshes are already waiting for the render "
					+ "thread to copy them, which is as many as Vitrail keeps; pace uploads on pendingBytes()");
		}

		ByteBuffer vertexCopy = copy(vertices, vertexBytes);
		ByteBuffer indexCopy = copy(indices, indexBytes);
		checkIndices(indexCopy, vertexBytes / VERTEX_BYTES);

		Handle handle = new Handle(vertexCopy, indexCopy, indexBytes / INDEX_BYTES);
		if (this.closed) {
			// The source was cut off, or the game is shutting down: nothing will ever draw this, and
			// nothing is queued, so the copy goes with the handle.
			handle.freed.set(true);
			handle.vertices = null;
			handle.indices = null;

			return handle;
		}

		this.pendingBytes.addAndGet(handle.bytes);
		this.live.add(handle);
		this.pending.add(handle);

		return handle;
	}

	@Override
	public long pendingBytes() {
		return this.pendingBytes.get();
	}

	/**
	 * Copies what the render thread has been waiting to copy, within the budget, and gives back what
	 * the add-on has freed. Called at the head of a frame, before anything is recorded.
	 * <p>
	 * The copies come first and the giving back after them, which is what closes a race with no lock
	 * in it: a mesh freed while it is being copied is on the retired queue by the time that queue is
	 * walked, and is given back in the same call rather than one frame later.
	 * <p>
	 * A copy that throws costs its own mesh and nothing else: the handle is off the queue and never
	 * becomes ready, and the ones behind it wait for the next call.
	 */
	public void drain(Gpu gpu) {
		try {
			long spent = 0L;
			for (Handle handle = this.pending.poll(); handle != null; handle = this.pending.poll()) {
				if (handle.freed.get()) {
					handle.dropBytes();
					continue;
				}

				ByteBuffer vertices = handle.vertices;
				ByteBuffer indices = handle.indices;
				if (vertices == null || indices == null) {
					continue;
				}

				try {
					handle.piece = gpu.create(vertices, indices, handle.indexCount);
					handle.ready = true;
				} finally {
					handle.dropBytes();
				}

				spent += handle.bytes;
				if (spent >= this.budget) {
					break;
				}
			}
		} finally {
			retire(gpu);
		}
	}

	private void retire(Gpu gpu) {
		for (Handle handle = this.retired.poll(); handle != null; handle = this.retired.poll()) {
			handle.release(gpu);
		}
	}

	/**
	 * Gives every mesh back and refuses every later one, for the source being cut off and for the
	 * end of the session. Render thread only, while the device is still alive.
	 */
	public void close(Gpu gpu) {
		this.closed = true;
		this.pending.clear();
		this.retired.clear();
		for (Handle handle : this.live) {
			handle.freed.set(true);
			handle.release(gpu);
		}
	}

	private static ByteBuffer copy(ByteBuffer source, int length) {
		ByteBuffer copy = ByteBuffer.allocateDirect(length).order(ByteOrder.LITTLE_ENDIAN);
		// Through a duplicate, so that the caller's position and limit are left as they were.
		copy.put(source.duplicate());
		copy.flip();

		return copy;
	}

	private static void checkIndices(ByteBuffer indices, int vertexCount) {
		IntBuffer view = indices.asIntBuffer();
		for (int at = 0; at < view.limit(); at++) {
			// Unsigned, which is what the GPU reads them as: a negative int is a huge index.
			if (Integer.compareUnsigned(view.get(at), vertexCount) >= 0) {
				throw new IllegalArgumentException("Index " + at + " of a mesh names vertex "
						+ Integer.toUnsignedString(view.get(at)) + " and the mesh has " + vertexCount);
			}
		}
	}
}
