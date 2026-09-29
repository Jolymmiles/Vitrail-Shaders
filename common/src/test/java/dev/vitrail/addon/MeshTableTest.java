package dev.vitrail.addon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.api.DistantMesh;
import dev.vitrail.dh.DhLods.Piece;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Holds the handle bookkeeping of a far terrain source's meshes: what an upload refuses, what the
 * copy keeps of the caller's bytes, the order and the budget the render thread copies them in, and
 * that a mesh freed from any thread is given back once and only once, whichever side of its own
 * copy the free lands on.
 */
class MeshTableTest {

	/** A GPU that makes a piece per mesh and remembers what it was asked for. */
	private static class RecordingGpu implements MeshTable.Gpu {

		final List<Piece> created = new ArrayList<>();
		final Map<Piece, Integer> destroyed = new IdentityHashMap<>();
		final List<byte[]> vertexBytes = new ArrayList<>();
		final List<byte[]> indexBytes = new ArrayList<>();
		boolean failNext;

		@Override
		public Piece create(ByteBuffer vertices, ByteBuffer indices, int indexCount) {
			if (this.failNext) {
				this.failNext = false;
				throw new IllegalStateException("planted");
			}

			assertEquals(0, vertices.position());
			assertEquals(0, indices.position());
			this.vertexBytes.add(bytes(vertices));
			this.indexBytes.add(bytes(indices));
			Piece piece = new Piece(null, null, indexCount);
			this.created.add(piece);

			return piece;
		}

		@Override
		public synchronized void destroy(Piece piece) {
			this.destroyed.merge(piece, 1, Integer::sum);
		}

		boolean destroyedOnce(Piece piece) {
			return Integer.valueOf(1).equals(this.destroyed.get(piece));
		}
	}

	private static byte[] bytes(ByteBuffer buffer) {
		byte[] out = new byte[buffer.remaining()];
		buffer.duplicate().get(out);

		return out;
	}

	private static ByteBuffer vertices(int count, int fill) {
		ByteBuffer buffer = ByteBuffer.allocateDirect(count * MeshTable.VERTEX_BYTES);
		for (int at = 0; at < buffer.limit(); at++) {
			buffer.put(at, (byte) (fill + at));
		}

		return buffer;
	}

	/** {@code triangles} triangles over {@code max + 1} vertices, in little-endian words. */
	private static ByteBuffer indices(int triangles, int max) {
		ByteBuffer buffer = ByteBuffer.allocate(triangles * 12).order(ByteOrder.LITTLE_ENDIAN);
		for (int at = 0; at < triangles * 3; at++) {
			buffer.putInt(at % (max + 1));
		}

		buffer.flip();

		return buffer;
	}

	private static MeshTable.Handle upload(MeshTable table, int triangles) {
		return (MeshTable.Handle) table.upload(vertices(4, triangles), indices(triangles, 3));
	}

	// What an upload refuses.

	@Test
	void vertexBytesThatAreNoWholeNumberOfVerticesAreRefused() {
		MeshTable table = new MeshTable();

		assertThrows(IllegalArgumentException.class,
				() -> table.upload(ByteBuffer.allocate(0), indices(1, 0)));
		assertThrows(IllegalArgumentException.class,
				() -> table.upload(ByteBuffer.allocate(20), indices(1, 0)));
		assertEquals(0L, table.pendingBytes());
	}

	@Test
	void indexBytesThatAreNoWholeNumberOfTrianglesAreRefused() {
		MeshTable table = new MeshTable();

		assertThrows(IllegalArgumentException.class,
				() -> table.upload(vertices(3, 0), ByteBuffer.allocate(0)));
		assertThrows(IllegalArgumentException.class,
				() -> table.upload(vertices(3, 0), ByteBuffer.allocate(16)));
		assertEquals(0L, table.pendingBytes());
	}

	@Test
	void anIndexPastTheLastVertexIsRefusedWhereItCanStillBeNamed() {
		MeshTable table = new MeshTable();

		// Three vertices, and a triangle that names the fourth.
		ByteBuffer past = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
		past.putInt(0).putInt(1).putInt(3).flip();

		assertThrows(IllegalArgumentException.class, () -> table.upload(vertices(3, 0), past));
		assertEquals(0L, table.pendingBytes());
	}

	@Test
	void anIndexWithTheHighBitSetIsHugeAndNotNegative() {
		MeshTable table = new MeshTable();
		ByteBuffer negative = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
		negative.putInt(0).putInt(1).putInt(-1).flip();

		assertThrows(IllegalArgumentException.class, () -> table.upload(vertices(3, 0), negative));
	}

	@Test
	void theIndicesAreReadLittleEndianWhateverOrderTheCallerBufferIsIn() {
		MeshTable table = new MeshTable();
		// The same three words in big-endian order name vertices far past four, so a table that
		// trusted the caller's order would accept the first and refuse this one.
		ByteBuffer big = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN);
		big.putInt(0).putInt(1).putInt(2).flip();

		assertThrows(IllegalArgumentException.class, () -> table.upload(vertices(4, 0), big));
	}

	@Test
	void theCallersBuffersAreLeftAsTheyWere() {
		MeshTable table = new MeshTable();
		// Five vertices with the first one skipped, so that four are left for the indices to name.
		ByteBuffer vertices = vertices(5, 7);
		ByteBuffer indices = indices(2, 3);
		vertices.position(16);
		indices.position(12);
		int vertexLimit = vertices.limit();

		table.upload(vertices, indices);

		assertEquals(16, vertices.position());
		assertEquals(vertexLimit, vertices.limit());
		assertEquals(12, indices.position());
	}

	@Test
	void onlyTheBytesFromPositionToLimitAreTaken() {
		MeshTable table = new MeshTable();
		RecordingGpu gpu = new RecordingGpu();
		ByteBuffer vertices = vertices(4, 7);
		vertices.position(16);
		vertices.limit(48);

		table.upload(vertices, indices(1, 1));
		table.drain(gpu);

		assertEquals(32, gpu.vertexBytes.get(0).length);
		assertEquals((byte) (7 + 16), gpu.vertexBytes.get(0)[0]);
	}

	@Test
	void whatWaitsForTheRenderThreadIsBoundedAndTheAddonPacesItselfOnTheCount() {
		// One mesh is 76 bytes: three fit under the limit of 250 and a fourth does not.
		MeshTable table = new MeshTable(MeshTable.UPLOAD_BUDGET, 250);
		RecordingGpu gpu = new RecordingGpu();
		upload(table, 1);
		upload(table, 1);
		upload(table, 1);

		assertThrows(IllegalStateException.class, () -> upload(table, 1));
		assertEquals(3 * 76L, table.pendingBytes());

		table.drain(gpu);

		assertEquals(0L, table.pendingBytes());
		assertNotNull(upload(table, 1));
	}

	@Test
	void aMeshLargerThanTheWholeLimitIsStillTakenWhenNothingWaits() {
		MeshTable table = new MeshTable(MeshTable.UPLOAD_BUDGET, 10);

		assertNotNull(upload(table, 1));
		assertThrows(IllegalStateException.class, () -> upload(table, 1));
	}

	// What the copy keeps.

	@Test
	void theCopyIsMadeAtTheCallSoTheCallerMayReuseItsBuffers() {
		MeshTable table = new MeshTable();
		RecordingGpu gpu = new RecordingGpu();
		ByteBuffer vertices = vertices(4, 1);
		ByteBuffer indices = indices(1, 3);
		byte[] expectedVertices = bytes(vertices);
		byte[] expectedIndices = bytes(indices);

		table.upload(vertices, indices);
		for (int at = 0; at < vertices.limit(); at++) {
			vertices.put(at, (byte) 0);
		}

		indices.putInt(0, 99);
		table.drain(gpu);

		assertTrue(Arrays.equals(expectedVertices, gpu.vertexBytes.get(0)));
		assertTrue(Arrays.equals(expectedIndices, gpu.indexBytes.get(0)));
	}

	@Test
	void aHeapBufferAndADirectOneUploadAlike() {
		MeshTable table = new MeshTable();
		RecordingGpu gpu = new RecordingGpu();
		ByteBuffer heap = ByteBuffer.wrap(bytes(vertices(4, 3)));

		table.upload(heap, indices(1, 3));
		table.drain(gpu);

		assertTrue(Arrays.equals(bytes(vertices(4, 3)), gpu.vertexBytes.get(0)));
	}

	// The copy to the GPU.

	@Test
	void aMeshIsNotReadyUntilTheRenderThreadHasCopiedIt() {
		MeshTable table = new MeshTable();
		RecordingGpu gpu = new RecordingGpu();

		MeshTable.Handle mesh = upload(table, 2);

		assertFalse(mesh.ready());
		assertNull(mesh.piece());
		assertEquals(4 * MeshTable.VERTEX_BYTES + 2 * 12, table.pendingBytes());

		table.drain(gpu);

		assertTrue(mesh.ready());
		assertSame(gpu.created.get(0), mesh.piece());
		assertEquals(6, mesh.piece().indexCount());
		assertEquals(0L, table.pendingBytes());
	}

	@Test
	void meshesAreCopiedInTheOrderTheyArrived() {
		MeshTable table = new MeshTable();
		RecordingGpu gpu = new RecordingGpu();

		upload(table, 1);
		upload(table, 2);
		upload(table, 3);
		table.drain(gpu);

		assertEquals(List.of(3, 6, 9), gpu.created.stream().map(Piece::indexCount).toList());
	}

	@Test
	void aDrainStopsAtTheBudgetAndTheRestWaitForTheNext() {
		// One mesh is 64 + 12 = 76 bytes, and a budget of 100 is reached by the second.
		MeshTable table = new MeshTable(100, MeshTable.MAX_PENDING_BYTES);
		RecordingGpu gpu = new RecordingGpu();

		MeshTable.Handle first = upload(table, 1);
		MeshTable.Handle second = upload(table, 1);
		MeshTable.Handle third = upload(table, 1);
		table.drain(gpu);

		assertTrue(first.ready());
		assertTrue(second.ready());
		assertFalse(third.ready());
		assertEquals(76L, table.pendingBytes());

		table.drain(gpu);

		assertTrue(third.ready());
	}

	@Test
	void aMeshLargerThanTheBudgetStillGoesThroughByItself() {
		MeshTable table = new MeshTable(10, MeshTable.MAX_PENDING_BYTES);
		RecordingGpu gpu = new RecordingGpu();

		MeshTable.Handle big = upload(table, 1);
		MeshTable.Handle next = upload(table, 1);
		table.drain(gpu);

		assertTrue(big.ready());
		assertFalse(next.ready());
	}

	@Test
	void aCopyThatThrowsCostsItsOwnMeshAndNoOther() {
		MeshTable table = new MeshTable();
		RecordingGpu gpu = new RecordingGpu();

		MeshTable.Handle failing = upload(table, 1);
		MeshTable.Handle fine = upload(table, 2);
		gpu.failNext = true;

		assertThrows(IllegalStateException.class, () -> table.drain(gpu));

		assertFalse(failing.ready());
		assertNull(failing.piece());
		assertFalse(fine.ready());
		assertEquals(4 * MeshTable.VERTEX_BYTES + 24, table.pendingBytes());

		table.drain(gpu);

		assertTrue(fine.ready());
		assertFalse(failing.ready());
		assertEquals(0L, table.pendingBytes());
	}

	// Giving a mesh back.

	@Test
	void aMeshFreedBeforeItsCopyIsNeverCopiedAndHoldsNothing() {
		MeshTable table = new MeshTable();
		RecordingGpu gpu = new RecordingGpu();

		MeshTable.Handle mesh = upload(table, 1);
		mesh.free();
		table.drain(gpu);

		assertTrue(gpu.created.isEmpty());
		assertFalse(mesh.ready());
		assertEquals(0L, table.pendingBytes());
	}

	@Test
	void aMeshFreedAfterItsCopyIsGivenBackAtTheNextDrainAndOnce() {
		MeshTable table = new MeshTable();
		RecordingGpu gpu = new RecordingGpu();
		MeshTable.Handle mesh = upload(table, 1);
		table.drain(gpu);
		Piece piece = mesh.piece();

		mesh.free();
		mesh.free();

		// Freed but not yet given back: the frame that listed it may still draw it, so the piece
		// answers until the render thread lets go, and the mesh no longer calls itself ready.
		assertFalse(mesh.ready());
		assertSame(piece, mesh.piece());
		assertTrue(gpu.destroyed.isEmpty());

		table.drain(gpu);
		table.drain(gpu);

		assertTrue(gpu.destroyedOnce(piece));
		assertNull(mesh.piece());
	}

	@Test
	void aMeshFreedWhileItIsBeingCopiedIsGivenBackInTheSameDrain() {
		MeshTable table = new MeshTable();
		List<MeshTable.Handle> meshes = new ArrayList<>();
		RecordingGpu gpu = new RecordingGpu() {
			@Override
			public Piece create(ByteBuffer vertices, ByteBuffer indices, int indexCount) {
				// The add-on's thread frees the mesh in the middle of its own copy.
				meshes.get(0).free();

				return super.create(vertices, indices, indexCount);
			}
		};
		meshes.add(upload(table, 1));

		table.drain(gpu);

		assertTrue(gpu.destroyedOnce(gpu.created.get(0)));
		assertNull(meshes.get(0).piece());
		assertFalse(meshes.get(0).ready());
	}

	@Test
	void closingGivesEverythingBackAndRefusesWhatComesAfter() {
		MeshTable table = new MeshTable();
		RecordingGpu gpu = new RecordingGpu();
		MeshTable.Handle on = upload(table, 1);
		table.drain(gpu);
		MeshTable.Handle waiting = upload(table, 2);

		table.close(gpu);

		assertTrue(gpu.destroyedOnce(gpu.created.get(0)));
		assertFalse(on.ready());
		assertFalse(waiting.ready());
		assertEquals(0L, table.pendingBytes());

		MeshTable.Handle late = upload(table, 3);
		table.drain(gpu);

		assertFalse(late.ready());
		assertEquals(1, gpu.created.size());
		assertEquals(0L, table.pendingBytes());
	}

	// Any thread against the render thread.

	@Test
	void uploadsAndFreesFromManyThreadsAgainstDrainsLeaveNothingHeldOrGivenBackTwice() throws Exception {
		MeshTable table = new MeshTable(4096, MeshTable.MAX_PENDING_BYTES);
		RecordingGpu gpu = new RecordingGpu();
		int workers = 4;
		int perWorker = 300;
		ExecutorService pool = Executors.newFixedThreadPool(workers);
		CountDownLatch done = new CountDownLatch(workers);
		List<MeshTable.Handle> all = Collections.synchronizedList(new ArrayList<>());

		for (int worker = 0; worker < workers; worker++) {
			pool.execute(() -> {
				List<MeshTable.Handle> mine = new ArrayList<>();
				for (int at = 0; at < perWorker; at++) {
					MeshTable.Handle mesh = upload(table, 1 + at % 3);
					mine.add(mesh);
					all.add(mesh);
					// Half are freed at once, and the rest a little later, so that the frees land on
					// both sides of the copy.
					if (at % 2 == 0) {
						mesh.free();
					} else if (at % 5 == 0 && !mine.isEmpty()) {
						mine.get(mine.size() / 2).free();
					}
				}

				done.countDown();
			});
		}

		while (done.getCount() > 0) {
			table.drain(gpu);
		}

		pool.shutdown();
		assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

		while (table.pendingBytes() > 0) {
			table.drain(gpu);
		}

		all.forEach(DistantMesh::free);
		table.drain(gpu);
		table.drain(gpu);

		assertEquals(0L, table.pendingBytes());
		for (Piece piece : gpu.created) {
			assertTrue(gpu.destroyedOnce(piece), "every piece made is given back exactly once");
		}

		assertEquals(gpu.created.size(), gpu.destroyed.size());
		for (MeshTable.Handle mesh : all) {
			assertFalse(mesh.ready());
			assertNull(mesh.piece());
		}
	}
}
