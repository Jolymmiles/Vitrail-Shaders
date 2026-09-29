package dev.vitrail.addon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.api.AddonRegistrar;
import dev.vitrail.api.DefineSource;
import dev.vitrail.api.StageListener;
import dev.vitrail.api.VitrailAddon;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Holds the two promises the API's package javadoc makes about failure: an add-on is taken whole
 * or not at all, and a piece that throws is cut off without taking the others with it.
 */
class AddonRegistryTest {

	@AfterEach
	void forget() {
		AddonRegistry.clear();
	}

	@Test
	void anAddonThatThrowsHalfwayLeavesNothingBehind() {
		DefineSource kept = defines -> defines.put("KEPT", "");
		AddonRegistry.load(List.of(
				addon("broken", registrar -> {
					registrar.defines(defines -> defines.put("HALF", ""));
					throw new IllegalStateException("planted");
				}),
				addon("whole", registrar -> registrar.defines(kept))));

		assertEquals(1, AddonRegistry.defines().size());
		assertEquals("whole", AddonRegistry.defines().get(0).addon());
		assertEquals(kept, AddonRegistry.defines().get(0).piece());
	}

	@Test
	void aLinkageErrorIsAFailureOfTheAddonNotOfTheGame() {
		AddonRegistry.load(List.of(addon("stale", registrar -> {
			throw new NoSuchMethodError("built against another API");
		})));

		assertFalse(AddonRegistry.any());
	}

	@Test
	void aPieceThatThrowsIsCutOffAndTheOthersAreStillCalled() {
		List<String> called = new ArrayList<>();
		StageListener throwing = (stage, frame) -> {
			throw new IllegalStateException("planted");
		};
		StageListener fine = (stage, frame) -> called.add("fine");
		AddonRegistry.load(List.of(addon("two", registrar -> {
			registrar.stages(throwing);
			registrar.stages(fine);
		})));

		AddonRegistry.each(AddonRegistry.stages(), "draw", listener -> listener.onStage(null, null));
		AddonRegistry.each(AddonRegistry.stages(), "draw", listener -> listener.onStage(null, null));

		assertTrue(AddonRegistry.stages().get(0).cutOff());
		assertFalse(AddonRegistry.stages().get(1).cutOff());
		assertEquals(List.of("fine", "fine"), called);
	}

	@Test
	void aRegistrarKeptPastRegisterRefusesLateRegistrations() {
		List<AddonRegistrar> kept = new ArrayList<>();
		AddonRegistry.load(List.of(addon("late", kept::add)));

		assertThrows(IllegalStateException.class, () -> kept.get(0).defines(Map::clear));
	}

	@Test
	void theOrderIsTheOrderFoundThenTheOrderRegistered() {
		DefineSource a = defines -> defines.put("A", "");
		DefineSource b = defines -> defines.put("B", "");
		DefineSource c = defines -> defines.put("C", "");
		AddonRegistry.load(List.of(
				addon("first", registrar -> {
					registrar.defines(a);
					registrar.defines(b);
				}),
				addon("second", registrar -> registrar.defines(c))));

		List<DefineSource> order = new ArrayList<>();
		AddonRegistry.each(AddonRegistry.defines(), "write defines", order::add);

		assertEquals(List.of(a, b, c), order);
	}

	private static VitrailAddon addon(String id, Consumer<AddonRegistrar> register) {
		return new VitrailAddon() {

			@Override
			public String id() {
				return id;
			}

			@Override
			public void register(AddonRegistrar registrar) {
				register.accept(registrar);
			}
		};
	}
}
