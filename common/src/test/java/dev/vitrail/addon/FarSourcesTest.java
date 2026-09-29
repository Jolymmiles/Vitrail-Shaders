package dev.vitrail.addon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.api.AddonRegistrar;
import dev.vitrail.api.DistantFrame;
import dev.vitrail.api.DistantMesh;
import dev.vitrail.api.DistantMeshes;
import dev.vitrail.api.DistantSections;
import dev.vitrail.api.DistantTerrainSource;
import dev.vitrail.api.DistantWindow;
import dev.vitrail.api.VitrailAddon;
import dev.vitrail.dh.DhLods.Piece;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Holds which far terrain source draws: Distant Horizons whenever it is present, and otherwise the
 * first add-on source that says it is, in the order the add-ons were found, with a source that
 * throws skipped from then on and the ones behind the first present one never asked.
 */
class FarSourcesTest {

	private static final class Fake implements DistantTerrainSource {

		final String name;
		final List<String> log;
		boolean present;
		boolean throwsOnPresent;
		boolean throwsOnAttach;
		DistantMeshes meshes;

		Fake(String name, boolean present, List<String> log) {
			this.name = name;
			this.present = present;
			this.log = log;
		}

		@Override
		public boolean present() {
			this.log.add(this.name + ".present");
			if (this.throwsOnPresent) {
				throw new IllegalStateException("planted");
			}

			return this.present;
		}

		@Override
		public boolean usable() {
			return true;
		}

		@Override
		public int renderDistanceBlocks() {
			return 0;
		}

		@Override
		public void attach(DistantMeshes attached) {
			this.log.add(this.name + ".attach");
			if (this.throwsOnAttach) {
				throw new IllegalStateException("planted");
			}

			this.meshes = attached;
		}

		@Override
		public DistantWindow window() {
			return null;
		}

		@Override
		public DistantSections frame(DistantFrame view) {
			return null;
		}
	}

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

	private static void register(String addon, Fake... sources) {
		AddonRegistry.load(List.of(new VitrailAddon() {
			@Override
			public String id() {
				return addon;
			}

			@Override
			public void register(AddonRegistrar registrar) {
				for (Fake source : sources) {
					registrar.distant(source);
				}
			}
		}));
	}

	private static DistantMesh mesh(DistantMeshes meshes) {
		ByteBuffer indices = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
		indices.putInt(0).putInt(1).putInt(2).flip();

		return meshes.upload(ByteBuffer.allocate(3 * MeshTable.VERTEX_BYTES), indices);
	}

	@Test
	void nothingIsRegisteredAndNothingIsSelected() {
		assertFalse(FarSources.any());
		assertNull(FarSources.select(false));
	}

	@Test
	void distantHorizonsWinsWheneverItIsPresentAndTheAddonIsNotAsked() {
		List<String> log = new ArrayList<>();
		register("one", new Fake("a", true, log));

		assertTrue(FarSources.any());
		assertNull(FarSources.select(true));
		assertEquals(List.of(), log);
	}

	@Test
	void withoutItTheFirstAddonThatIsPresentDraws() {
		List<String> log = new ArrayList<>();
		Fake absent = new Fake("absent", false, log);
		Fake first = new Fake("first", true, log);
		Fake second = new Fake("second", true, log);
		register("one", absent, first);
		register("two", second);

		FarSource chosen = FarSources.select(false);

		assertNotNull(chosen);
		assertEquals("one", chosen.addon());
		// The order they were found in, and the source behind the chosen one is not asked.
		assertEquals(List.of("absent.attach", "first.attach", "second.attach", "absent.present",
				"first.present"), log);
	}

	@Test
	void theOneChosenFollowsWhatTheSourcesSayFromOneFrameToTheNext() {
		List<String> log = new ArrayList<>();
		Fake first = new Fake("first", true, log);
		Fake second = new Fake("second", true, log);
		register("one", first);
		register("two", second);

		assertEquals("one", FarSources.select(false).addon());

		first.present = false;
		assertEquals("two", FarSources.select(false).addon());

		second.present = false;
		assertNull(FarSources.select(false));

		first.present = true;
		assertEquals("one", FarSources.select(false).addon());
	}

	@Test
	void aSourceThatThrowsIsSkippedFromThenOnAndTheNextOneDraws() {
		List<String> log = new ArrayList<>();
		Fake broken = new Fake("broken", true, log);
		broken.throwsOnPresent = true;
		Fake fine = new Fake("fine", true, log);
		register("one", broken);
		register("two", fine);

		assertEquals("two", FarSources.select(false).addon());
		log.clear();
		assertEquals("two", FarSources.select(false).addon());

		assertEquals(List.of("fine.present"), log);
		assertTrue(AddonRegistry.distant().get(0).cutOff());
	}

	@Test
	void everySourceIsHandedItsMeshTableOnceBeforeAnythingElseIsAsked() {
		List<String> log = new ArrayList<>();
		Fake a = new Fake("a", true, log);
		Fake b = new Fake("b", false, log);
		register("one", a, b);

		FarSources.select(false);
		FarSources.select(false);
		FarSources.drain(new Gpu());

		assertEquals("a.attach", log.get(0));
		assertEquals("b.attach", log.get(1));
		assertEquals(1, log.stream().filter("a.attach"::equals).count());
		assertNotNull(a.meshes);
		assertNotNull(b.meshes);
		assertFalse(a.meshes == b.meshes);
	}

	@Test
	void aSourceThatThrowsWhenAttachedIsCutOffAndNeverChosen() {
		List<String> log = new ArrayList<>();
		Fake broken = new Fake("broken", true, log);
		broken.throwsOnAttach = true;
		Fake fine = new Fake("fine", true, log);
		register("one", broken);
		register("two", fine);

		assertEquals("two", FarSources.select(false).addon());

		assertTrue(AddonRegistry.distant().get(0).cutOff());
		assertFalse(log.contains("broken.present"));
	}

	@Test
	void drainingCopiesEverySourcesMeshesAndClosingGivesThemAllBack() {
		List<String> log = new ArrayList<>();
		Fake a = new Fake("a", true, log);
		Fake b = new Fake("b", true, log);
		register("one", a, b);
		FarSources.select(false);
		Gpu gpu = new Gpu();
		DistantMesh fromA = mesh(a.meshes);
		DistantMesh fromB = mesh(b.meshes);

		FarSources.drain(gpu);

		assertTrue(fromA.ready());
		assertTrue(fromB.ready());

		FarSources.close(gpu);

		assertFalse(fromA.ready());
		assertFalse(fromB.ready());
		assertEquals(2, gpu.destroyed.size());
		assertNotSame(gpu.destroyed.get(0), gpu.destroyed.get(1));
	}

	@Test
	void closingBeforeAnythingAskedTouchesNothing() {
		Gpu gpu = new Gpu();
		register("one", new Fake("a", true, new ArrayList<>()));

		FarSources.close(gpu);

		assertTrue(gpu.destroyed.isEmpty());
	}
}
