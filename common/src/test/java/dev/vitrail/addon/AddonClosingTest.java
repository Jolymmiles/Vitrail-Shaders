package dev.vitrail.addon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.api.AddonRegistrar;
import dev.vitrail.api.DeviceClosingListener;
import dev.vitrail.api.StageListener;
import dev.vitrail.api.VitrailAddon;
import dev.vitrail.api.VulkanHandles;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Holds the promises of the device closing notice: it is given once, in the order the listeners
 * were registered, a listener that throws costs only itself, and after it nothing of any add-on is
 * called.
 */
class AddonClosingTest {

	private static final VulkanHandles HANDLES = new VulkanHandles(1, 2, 3, 4, 5, 6);

	@AfterEach
	void forget() {
		AddonRegistry.clear();
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

	@Test
	void theListenersAreToldInTheOrderTheAddonsWereFoundAndRegistered() {
		List<String> told = new ArrayList<>();
		AddonRegistry.load(List.of(
				addon("first", registrar -> {
					registrar.closing(handles -> told.add("first.a"));
					registrar.closing(handles -> told.add("first.b"));
				}),
				addon("second", registrar -> registrar.closing(handles -> told.add("second")))));

		assertTrue(AddonRegistry.closeDevice(HANDLES));

		assertEquals(List.of("first.a", "first.b", "second"), told);
	}

	@Test
	void everyListenerIsHandedTheHandlesOfTheDeviceThatIsClosing() {
		List<VulkanHandles> seen = new ArrayList<>();
		AddonRegistry.load(List.of(addon("two", registrar -> {
			registrar.closing(seen::add);
			registrar.closing(seen::add);
		})));

		AddonRegistry.closeDevice(HANDLES);

		assertEquals(2, seen.size());
		assertSame(HANDLES, seen.get(0));
		assertSame(HANDLES, seen.get(1));
	}

	@Test
	void theNoticeIsGivenOnceWhoeverAsksAgain() {
		List<String> told = new ArrayList<>();
		AddonRegistry.load(List.of(addon("once", registrar -> registrar.closing(handles -> told.add("told")))));

		assertTrue(AddonRegistry.closeDevice(HANDLES));
		assertFalse(AddonRegistry.closeDevice(HANDLES));
		assertFalse(AddonRegistry.closeDevice(new VulkanHandles(7, 8, 9, 10, 11, 12)));

		assertEquals(List.of("told"), told);
	}

	@Test
	void aListenerThatThrowsIsCutOffAndTheOthersAreStillTold() {
		List<String> told = new ArrayList<>();
		DeviceClosingListener throwing = handles -> {
			throw new IllegalStateException("planted");
		};
		AddonRegistry.load(List.of(
				addon("broken", registrar -> {
					registrar.closing(throwing);
					registrar.closing(handles -> told.add("same add-on, next listener"));
				}),
				addon("fine", registrar -> registrar.closing(handles -> told.add("other add-on")))));

		AddonRegistry.closeDevice(HANDLES);

		assertTrue(AddonRegistry.closing().get(0).cutOff());
		assertEquals(List.of("same add-on, next listener", "other add-on"), told);
	}

	@Test
	void aLinkageErrorInAListenerIsItsFailureAndNotTheGames() {
		List<String> told = new ArrayList<>();
		AddonRegistry.load(List.of(addon("stale", registrar -> {
			registrar.closing(handles -> {
				throw new NoSuchMethodError("built against another API");
			});
			registrar.closing(handles -> told.add("after"));
		})));

		AddonRegistry.closeDevice(HANDLES);

		assertEquals(List.of("after"), told);
	}

	@Test
	void afterTheNoticeNothingOfAnyAddonIsCalled() {
		List<String> called = new ArrayList<>();
		StageListener stage = (frame, context) -> called.add("stage");
		AddonRegistry.load(List.of(addon("late", registrar -> {
			registrar.stages(stage);
			registrar.closing(handles -> called.add("closing"));
		})));

		AddonRegistry.each(AddonRegistry.stages(), "draw", listener -> listener.onStage(null, null));
		AddonRegistry.closeDevice(HANDLES);
		AddonRegistry.each(AddonRegistry.stages(), "draw", listener -> listener.onStage(null, null));
		boolean asked = AddonRegistry.call(AddonRegistry.stages().get(0), "draw",
				listener -> listener.onStage(null, null));

		assertEquals(List.of("stage", "closing"), called);
		assertFalse(asked);
		assertFalse(AddonRegistry.stages().get(0).cutOff(), "refused, not cut off by a failure");
	}

	@Test
	void aPieceCutOffEarlierLeavesItsAddonsClosingListenerAlone() {
		List<String> told = new ArrayList<>();
		StageListener throwing = (frame, context) -> {
			throw new IllegalStateException("planted");
		};
		AddonRegistry.load(List.of(addon("frees", registrar -> {
			registrar.stages(throwing);
			registrar.closing(handles -> told.add("freed what the stage made"));
		})));

		AddonRegistry.each(AddonRegistry.stages(), "draw", listener -> listener.onStage(null, null));
		assertTrue(AddonRegistry.stages().get(0).cutOff());

		AddonRegistry.closeDevice(HANDLES);

		assertEquals(List.of("freed what the stage made"), told);
	}

	@Test
	void anAddonThatFailsToRegisterLeavesNoListenerBehind() {
		List<String> told = new ArrayList<>();
		AddonRegistry.load(List.of(addon("half", registrar -> {
			registrar.closing(handles -> told.add("half"));
			throw new IllegalStateException("planted");
		})));

		assertTrue(AddonRegistry.closing().isEmpty());
		AddonRegistry.closeDevice(HANDLES);

		assertEquals(List.of(), told);
	}

	@Test
	void anAddonThatOnlyClosesStillCountsAsRegistered() {
		assertFalse(AddonRegistry.any());

		AddonRegistry.load(List.of(addon("frees", registrar -> registrar.closing(handles -> {
		}))));

		assertTrue(AddonRegistry.any());
	}

	@Test
	void noListenersStillEndsTheAddonsCalls() {
		StageListener stage = (frame, context) -> {
		};
		AddonRegistry.load(List.of(addon("plain", registrar -> registrar.stages(stage))));

		assertTrue(AddonRegistry.closeDevice(HANDLES));

		assertFalse(AddonRegistry.call(AddonRegistry.stages().get(0), "draw", listener -> {
		}));
	}
}
