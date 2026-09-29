package dev.vitrail.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.addon.AddonRegistry;
import dev.vitrail.addon.AddonTestSupport;
import dev.vitrail.api.AddonRegistrar;
import dev.vitrail.api.DistantFrame;
import dev.vitrail.api.DistantMeshes;
import dev.vitrail.api.DistantSections;
import dev.vitrail.api.DistantTerrainSource;
import dev.vitrail.api.DistantWindow;
import dev.vitrail.api.VitrailAddon;
import dev.vitrail.dh.DhDepth;

import java.util.List;
import org.joml.Vector2f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Holds what the engine reads its far terrain through: with nothing registered the reading is
 * Distant Horizons' own and the add-on side is never reached, and with an add-on source and no
 * Distant Horizons every one of the frame's reads comes from that source, which a throw between two
 * of them turns into the incoherent frame the fallback exists for.
 * <p>
 * There is no Distant Horizons in these tests, which is the case an add-on source exists for. The
 * one that draws when both are there is the choice {@code FarSourcesTest} holds.
 */
class DistantTerrainTest {

	private static final class Source implements DistantTerrainSource {

		boolean present = true;
		int distance = 768;
		DistantWindow window = new DistantWindow(3.0F, 6000.0F);
		boolean throwsOnWindow;

		@Override
		public boolean present() {
			return this.present;
		}

		@Override
		public boolean usable() {
			return true;
		}

		@Override
		public int renderDistanceBlocks() {
			return this.distance;
		}

		@Override
		public void attach(DistantMeshes meshes) {
		}

		@Override
		public DistantWindow window() {
			if (this.throwsOnWindow) {
				throw new IllegalStateException("planted");
			}

			return this.window;
		}

		@Override
		public DistantSections frame(DistantFrame view) {
			return DistantSections.NONE;
		}
	}

	@AfterEach
	void forget() {
		AddonTestSupport.forget();
	}

	private static void register(Source source) {
		AddonRegistry.load(List.of(new VitrailAddon() {
			@Override
			public String id() {
				return "test";
			}

			@Override
			public void register(AddonRegistrar registrar) {
				registrar.distant(source);
			}
		}));
	}

	@Test
	void withNothingRegisteredTheReadingIsDistantHorizonsAndAnswersNothingHere() {
		DistantTerrain.Reading reading = DistantTerrain.reading();
		Vector2f dest = new Vector2f(-1.0F, -2.0F);

		assertSame(reading, DistantTerrain.reading());
		assertFalse(DistantTerrain.present());
		assertEquals(-1, reading.renderDistanceBlocks());
		assertFalse(reading.zRow(dest));
		assertEquals(-1.0F, dest.x);
		assertEquals(DhDepth.usable(), reading.coherent());
	}

	@Test
	void anAddonSourceIsThereWhereDistantHorizonsIsNotAndTheFrameReadsItsNumbers() {
		register(new Source());

		assertTrue(DistantTerrain.present());
		assertTrue(DistantTerrain.drawable());

		DistantTerrain.Reading reading = DistantTerrain.reading();
		Vector2f row = new Vector2f();
		Vector2f planes = new Vector2f();

		assertEquals(768, reading.renderDistanceBlocks());
		assertTrue(reading.zRow(row));
		assertTrue(DistantTerrain.planes(row.x, row.y, planes));
		assertEquals(3.0F, planes.x, 3.0F * 1.0e-5F);
		assertEquals(6000.0F, planes.y, 6000.0F * 1.0e-5F);
		assertTrue(reading.coherent());
	}

	@Test
	void aSourceThatIsNotPresentIsNoFarTerrainAtAll() {
		Source source = new Source();
		source.present = false;
		register(source);

		assertFalse(DistantTerrain.present());
		assertFalse(DistantTerrain.drawable());
		assertEquals(-1, DistantTerrain.reading().renderDistanceBlocks());
	}

	@Test
	void aSourceThatStopsAnsweringBetweenTwoReadsMakesTheWholeReadingIncoherent() {
		Source source = new Source();
		register(source);
		DistantTerrain.Reading reading = DistantTerrain.reading();

		assertEquals(768, reading.renderDistanceBlocks());
		source.throwsOnWindow = true;
		Vector2f dest = new Vector2f(-1.0F, -2.0F);

		// The row is not answered, the source is cut off, and the frame that asks after all the
		// reads is told that some of them may have come from a source that then stopped.
		assertFalse(reading.zRow(dest));
		assertFalse(reading.coherent());
		assertEquals(-1.0F, dest.x);
	}

	@Test
	void theReadingIsSettledOnceAndDoesNotFollowASourceThatLeavesAfterwards() {
		Source source = new Source();
		register(source);
		DistantTerrain.Reading reading = DistantTerrain.reading();

		source.present = false;

		assertEquals(768, reading.renderDistanceBlocks());
		assertTrue(reading.coherent());
		assertFalse(DistantTerrain.present());
	}
}
