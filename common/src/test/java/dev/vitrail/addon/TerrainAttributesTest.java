package dev.vitrail.addon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.api.AddonRegistrar;
import dev.vitrail.api.TerrainAttribute;
import dev.vitrail.api.TerrainMeshListener;
import dev.vitrail.api.TerrainSection;
import dev.vitrail.api.VitrailAddon;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * What the add-ons ask every terrain vertex to carry, read from the registry: the union over the
 * listeners, once, and never at the cost of another add-on's answer.
 */
class TerrainAttributesTest {

	@AfterEach
	void forget() {
		AddonRegistry.clear();
		TerrainAttributes.forget();
	}

	@Test
	void nothingRegisteredAsksForNothing() {
		assertEquals(Set.of(), TerrainAttributes.forced());
	}

	@Test
	void theUnionOfEveryListenersAttributesIsWhatIsForced() {
		AddonRegistry.load(List.of(
				addon("ray", () -> Set.of(TerrainAttribute.BLOCK_ID, TerrainAttribute.MID_BLOCK)),
				addon("light", () -> Set.of(TerrainAttribute.MID_BLOCK, TerrainAttribute.TANGENT_FRAME))));

		assertEquals(Set.of(TerrainAttribute.BLOCK_ID, TerrainAttribute.MID_BLOCK,
				TerrainAttribute.TANGENT_FRAME), TerrainAttributes.forced());
	}

	@Test
	void aListenerThatAsksForNothingForcesNothing() {
		AddonRegistry.load(List.of(addon("plain", Set::of)));

		assertEquals(Set.of(), TerrainAttributes.forced());
	}

	@Test
	void anEmptyAnswerBeforeTheAddonsRegisteredIsNotTheAnswerForTheSession() {
		assertEquals(Set.of(), TerrainAttributes.forced());

		AddonRegistry.load(List.of(addon("late", () -> Set.of(TerrainAttribute.MID_TEX_COORD))));

		assertEquals(Set.of(TerrainAttribute.MID_TEX_COORD), TerrainAttributes.forced());
	}

	@Test
	void theAnswerIsReadOnceAndKept() {
		int[] asked = {0};
		Supplier<Set<TerrainAttribute>> changing = () -> asked[0]++ == 0
				? Set.of(TerrainAttribute.BLOCK_ID)
				: Set.of(TerrainAttribute.TANGENT_FRAME);
		AddonRegistry.load(List.of(addon("fickle", changing)));

		assertEquals(Set.of(TerrainAttribute.BLOCK_ID), TerrainAttributes.forced());
		assertEquals(Set.of(TerrainAttribute.BLOCK_ID), TerrainAttributes.forced());
		assertEquals(1, asked[0]);
	}

	@Test
	void aListenerThatThrowsIsCutOffAndAsksForNothingWhileTheOthersStillDo() {
		AddonRegistry.load(List.of(
				addon("broken", () -> {
					throw new IllegalStateException("planted");
				}),
				addon("fine", () -> Set.of(TerrainAttribute.MID_BLOCK))));

		assertEquals(Set.of(TerrainAttribute.MID_BLOCK), TerrainAttributes.forced());
		assertTrue(AddonRegistry.terrain().get(0).cutOff());
		assertFalse(AddonRegistry.terrain().get(1).cutOff());
	}

	@Test
	void aLinkageErrorIsTheAddonsFailureToo() {
		AddonRegistry.load(List.of(addon("stale", () -> {
			throw new NoSuchMethodError("built against another API");
		})));

		assertEquals(Set.of(), TerrainAttributes.forced());
		assertTrue(AddonRegistry.terrain().get(0).cutOff());
	}

	@Test
	void aSetWithANullInItCostsItsAddonEverythingItAskedFor() {
		Set<TerrainAttribute> holed = new HashSet<>();
		holed.add(TerrainAttribute.BLOCK_ID);
		holed.add(null);
		AddonRegistry.load(List.of(addon("holed", () -> holed)));

		assertEquals(Set.of(), TerrainAttributes.forced());
		assertTrue(AddonRegistry.terrain().get(0).cutOff());
	}

	@Test
	void aNullAnswerCutsTheAddonOff() {
		AddonRegistry.load(List.of(addon("null", () -> null)));

		assertEquals(Set.of(), TerrainAttributes.forced());
		assertTrue(AddonRegistry.terrain().get(0).cutOff());
	}

	private static VitrailAddon addon(String id, Supplier<Set<TerrainAttribute>> attributes) {
		TerrainMeshListener listener = new TerrainMeshListener() {

			@Override
			public Set<TerrainAttribute> attributes() {
				return attributes.get();
			}

			@Override
			public void built(TerrainSection section) {
			}

			@Override
			public void removed(int x, int y, int z) {
			}
		};

		return new VitrailAddon() {

			@Override
			public String id() {
				return id;
			}

			@Override
			public void register(AddonRegistrar registrar) {
				registrar.terrain(listener);
			}
		};
	}
}
