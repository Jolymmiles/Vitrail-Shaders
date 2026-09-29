package dev.vitrail.addon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.api.AddonRegistrar;
import dev.vitrail.api.DistantFrame;
import dev.vitrail.api.DistantMesh;
import dev.vitrail.api.DistantMeshes;
import dev.vitrail.api.DistantSection;
import dev.vitrail.api.DistantSections;
import dev.vitrail.api.DistantTerrainSource;
import dev.vitrail.api.DistantWindow;
import dev.vitrail.api.VitrailAddon;
import dev.vitrail.dh.DhDepth;
import dev.vitrail.dh.DhLods.Piece;
import dev.vitrail.dh.DhLods.Section;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import org.joml.Vector2f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Holds a far terrain source as the engine reads it: the window turned into the row the frame
 * publishes, the answers that fall back when a source has none or stops answering, and the lists of
 * a frame turned into the sections the far terrain is drawn from.
 */
class FarSourceTest {

	/** A source whose answers the test sets, and that hands the test its mesh table. */
	private static class Fake implements DistantTerrainSource {

		boolean present = true;
		boolean usable = true;
		int distance = 512;
		DistantWindow window = new DistantWindow(4.0F, 4096.0F);
		Function<DistantFrame, DistantSections> frame = view -> DistantSections.NONE;
		DistantMeshes meshes;
		int frames;

		@Override
		public boolean present() {
			return this.present;
		}

		@Override
		public boolean usable() {
			return this.usable;
		}

		@Override
		public int renderDistanceBlocks() {
			return this.distance;
		}

		@Override
		public void attach(DistantMeshes attached) {
			this.meshes = attached;
		}

		@Override
		public DistantWindow window() {
			return this.window;
		}

		@Override
		public DistantSections frame(DistantFrame view) {
			this.frames++;

			return this.frame.apply(view);
		}
	}

	/** Every piece a fake GPU made, so that a mesh can be told from another by what it draws. */
	private static final class Gpu implements MeshTable.Gpu {

		final List<Piece> destroyed = new ArrayList<>();

		@Override
		public Piece create(ByteBuffer vertices, ByteBuffer indices, int indexCount) {
			return new Piece(null, null, indexCount);
		}

		@Override
		public void destroy(Piece piece) {
			this.destroyed.add(piece);
		}
	}

	@AfterEach
	void forget() {
		AddonRegistry.clear();
		FarSources.reset();
	}

	private static FarSource sourceOf(Fake fake) {
		AddonRegistry.load(List.of(new VitrailAddon() {
			@Override
			public String id() {
				return "fake";
			}

			@Override
			public void register(AddonRegistrar registrar) {
				registrar.distant(fake);
			}
		}));
		FarSource source = new FarSource(AddonRegistry.distant().get(0));
		source.attach();

		return source;
	}

	private static DistantMesh mesh(FarSource source, int triangles) {
		ByteBuffer vertices = ByteBuffer.allocate(4 * MeshTable.VERTEX_BYTES);
		ByteBuffer indices = ByteBuffer.allocate(triangles * 12).order(ByteOrder.LITTLE_ENDIAN);
		for (int at = 0; at < triangles * 3; at++) {
			indices.putInt(at % 4);
		}

		indices.flip();

		return source.meshes().upload(vertices, indices);
	}

	private static DistantSection section(int x, DistantMesh... meshes) {
		return new DistantSection(x, 64, -x, List.of(meshes));
	}

	private static final DistantFrame VIEW = new DistantFrame(1.0, 2.0, 3.0, true);

	// The window and the row.

	@Test
	void aWindowBecomesTheRowThatTheFrameTurnsBackIntoTheSamePlanes() {
		Fake fake = new Fake();
		fake.window = new DistantWindow(7.5F, 30000.0F);
		FarSource source = sourceOf(fake);
		Vector2f row = new Vector2f();
		Vector2f planes = new Vector2f();

		assertTrue(source.zRow(row));
		assertTrue(DhDepth.planes(row.x, row.y, planes));

		assertEquals(7.5F, planes.x, 7.5F * 1.0e-5F);
		assertEquals(30000.0F, planes.y, 30000.0F * 1.0e-5F);
	}

	@Test
	void theRowPutsTheNearPlaneAtOneAndTheFarPlaneAtNought() {
		// The row is (scale, offset) of a reversed Z: depth = offset / distance - scale, which is
		// one at the near plane and nought at the far one.
		Fake fake = new Fake();
		fake.window = new DistantWindow(2.0F, 100.0F);
		Vector2f row = new Vector2f();

		assertTrue(sourceOf(fake).zRow(row));

		assertEquals(1.0F, row.y / 2.0F - row.x, 1.0e-5F);
		assertEquals(0.0F, row.y / 100.0F - row.x, 1.0e-5F);
	}

	@Test
	void aWindowThatIsNoWindowIsNoRowAndLeavesTheDestinationAlone() {
		Fake fake = new Fake();
		FarSource source = sourceOf(fake);
		float nan = Float.NaN;
		float infinity = Float.POSITIVE_INFINITY;

		for (DistantWindow bad : Arrays.asList(null, new DistantWindow(0.0F, 100.0F),
				new DistantWindow(-1.0F, 100.0F), new DistantWindow(10.0F, 10.0F),
				new DistantWindow(10.0F, 5.0F), new DistantWindow(nan, 100.0F),
				new DistantWindow(1.0F, nan), new DistantWindow(1.0F, infinity))) {
			fake.window = bad;
			Vector2f dest = new Vector2f(-1.0F, -2.0F);

			assertFalse(source.zRow(dest), String.valueOf(bad));
			assertEquals(-1.0F, dest.x);
			assertEquals(-2.0F, dest.y);
		}

		assertFalse(source.cutOff());
	}

	@Test
	void aDistanceOfNoneOrLessIsTheOneTheFrameFallsBackOn() {
		Fake fake = new Fake();
		FarSource source = sourceOf(fake);

		assertEquals(512, source.renderDistanceBlocks());

		fake.distance = 0;
		assertEquals(-1, source.renderDistanceBlocks());

		fake.distance = -7;
		assertEquals(-1, source.renderDistanceBlocks());
	}

	// A source that stops answering.

	@Test
	void aSourceThatThrowsIsCutOffAndAnswersNothingAfterwards() {
		Fake fake = new Fake() {
			@Override
			public int renderDistanceBlocks() {
				throw new IllegalStateException("planted");
			}
		};
		FarSource source = sourceOf(fake);

		assertEquals(-1, source.renderDistanceBlocks());

		assertTrue(source.cutOff());
		assertFalse(source.present());
		assertFalse(source.usable());
		assertFalse(source.zRow(new Vector2f()));
		assertSame(FarSource.Lists.NONE, source.frame(VIEW));
		assertEquals(0, fake.frames);
	}

	@Test
	void aSourceBuiltAgainstAnotherVersionOfTheApiIsCutOffLikeOneThatThrows() {
		Fake fake = new Fake() {
			@Override
			public DistantWindow window() {
				throw new NoSuchMethodError("built against another API");
			}
		};
		FarSource source = sourceOf(fake);

		assertFalse(source.zRow(new Vector2f()));
		assertTrue(source.cutOff());
	}

	@Test
	void aCutOffSourceGivesEveryMeshBackAtTheNextDrain() {
		Fake fake = new Fake();
		FarSource source = sourceOf(fake);
		Gpu gpu = new Gpu();
		DistantMesh first = mesh(source, 1);
		source.drain(gpu);
		DistantMesh waiting = mesh(source, 2);

		AddonRegistry.call(AddonRegistry.distant().get(0), "throw", cutting -> {
			throw new IllegalStateException("planted");
		});
		source.drain(gpu);

		assertEquals(1, gpu.destroyed.size());
		assertFalse(first.ready());
		assertFalse(waiting.ready());
		assertEquals(0L, source.meshes().pendingBytes());
	}

	// The lists of a frame.

	@Test
	void theSectionsAreTheOnesWithSomethingOnTheGpuInTheOrderListed() {
		Fake fake = new Fake();
		FarSource source = sourceOf(fake);
		Gpu gpu = new Gpu();
		DistantMesh a = mesh(source, 1);
		DistantMesh b = mesh(source, 2);
		DistantMesh c = mesh(source, 3);
		source.drain(gpu);
		DistantMesh late = mesh(source, 4);
		fake.frame = view -> new DistantSections(
				List.of(section(1, a), section(2, late), section(3, b, c)), List.of());

		FarSource.Lists lists = source.frame(VIEW);

		// The section holding only the mesh that has not been copied yet is left out.
		assertEquals(2, lists.opaque().size());
		Section first = lists.opaque().get(0);
		Section second = lists.opaque().get(1);
		assertEquals(1, first.x());
		assertEquals(64, first.y());
		assertEquals(-1, first.z());
		assertEquals(List.of(3), first.pieces().stream().map(Piece::indexCount).toList());
		assertEquals(3, second.x());
		assertEquals(List.of(6, 9), second.pieces().stream().map(Piece::indexCount).toList());
		assertTrue(lists.water().isEmpty());
	}

	@Test
	void aFreedMeshDrawsNothingOnceItHasBeenGivenBack() {
		Fake fake = new Fake();
		FarSource source = sourceOf(fake);
		Gpu gpu = new Gpu();
		DistantMesh kept = mesh(source, 1);
		DistantMesh gone = mesh(source, 2);
		source.drain(gpu);
		fake.frame = view -> new DistantSections(List.of(section(1, kept, gone)), List.of());

		gone.free();
		// Freed and not yet given back: the frame that listed it may still draw it.
		assertEquals(2, source.frame(VIEW).opaque().get(0).pieces().size());

		source.drain(gpu);

		assertEquals(1, source.frame(VIEW).opaque().get(0).pieces().size());
	}

	@Test
	void theLightIsHandedTheCameraListsWhenTheSourceHasNoneOfItsOwn() {
		Fake fake = new Fake();
		FarSource source = sourceOf(fake);
		Gpu gpu = new Gpu();
		DistantMesh a = mesh(source, 1);
		source.drain(gpu);
		fake.frame = view -> new DistantSections(List.of(section(1, a)), List.of(section(2, a)));

		FarSource.Lists lists = source.frame(VIEW);

		// One list converted once and used twice.
		assertSame(lists.opaque(), lists.shadowOpaque());
		assertSame(lists.water(), lists.shadowWater());
	}

	@Test
	void theLightIsHandedItsOwnListsWhenTheSourceHasThem() {
		Fake fake = new Fake();
		FarSource source = sourceOf(fake);
		Gpu gpu = new Gpu();
		DistantMesh a = mesh(source, 1);
		DistantMesh b = mesh(source, 2);
		source.drain(gpu);
		fake.frame = view -> new DistantSections(List.of(section(1, a)), List.of(),
				List.of(section(1, a), section(9, b)), List.of(section(5, b)));

		FarSource.Lists lists = source.frame(VIEW);

		assertEquals(1, lists.opaque().size());
		assertEquals(2, lists.shadowOpaque().size());
		assertEquals(1, lists.shadowWater().size());
		assertEquals(5, lists.shadowWater().get(0).x());
	}

	@Test
	void theLightsListsAreNotBuiltWhenThePackDrawsNoFarTerrainIntoItsMap() {
		Fake fake = new Fake();
		FarSource source = sourceOf(fake);
		Gpu gpu = new Gpu();
		DistantMesh a = mesh(source, 1);
		source.drain(gpu);
		fake.frame = view -> new DistantSections(List.of(section(1, a)), List.of(),
				List.of(section(1, a), section(9, a)), List.of(section(5, a)));

		FarSource.Lists lists = source.frame(new DistantFrame(0.0, 0.0, 0.0, false));

		assertEquals(1, lists.opaque().size());
		assertTrue(lists.shadowOpaque().isEmpty());
		assertTrue(lists.shadowWater().isEmpty());
	}

	@Test
	void theFrameIsToldWhereTheCameraIs() {
		Fake fake = new Fake();
		FarSource source = sourceOf(fake);
		List<DistantFrame> seen = new ArrayList<>();
		fake.frame = view -> {
			seen.add(view);

			return null;
		};

		FarSource.Lists lists = source.frame(VIEW);

		assertEquals(List.of(VIEW), seen);
		assertSame(FarSource.Lists.NONE, lists);
		assertFalse(source.cutOff());
	}

	@Test
	void aListHoldingANullCutsTheSourceOffAndTheFrameGoesOnWithoutIt() {
		Fake fake = new Fake();
		FarSource source = sourceOf(fake);
		List<DistantSection> broken = new ArrayList<>();
		broken.add(null);
		fake.frame = view -> new DistantSections(broken, List.of());

		assertSame(FarSource.Lists.NONE, source.frame(VIEW));

		assertTrue(source.cutOff());
	}

	@Test
	void aMeshThatIsNotOneOfTheTablesIsLeftOutAndDoesNotCutTheSourceOff() {
		Fake fake = new Fake();
		FarSource source = sourceOf(fake);
		Gpu gpu = new Gpu();
		DistantMesh own = mesh(source, 1);
		source.drain(gpu);
		DistantMesh foreign = new DistantMesh() {
			@Override
			public boolean ready() {
				return true;
			}

			@Override
			public void free() {
			}
		};
		fake.frame = view -> new DistantSections(
				List.of(section(1, foreign), section(2, foreign, own)), List.of());

		FarSource.Lists lists = source.frame(VIEW);

		assertEquals(1, lists.opaque().size());
		assertEquals(2, lists.opaque().get(0).x());
		assertFalse(source.cutOff());
	}
}
