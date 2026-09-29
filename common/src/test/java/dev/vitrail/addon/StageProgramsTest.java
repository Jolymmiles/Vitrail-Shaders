package dev.vitrail.addon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.api.FrameContext;
import dev.vitrail.api.FrameStage;
import dev.vitrail.api.StageListener;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Holds what a stage listener's program names come to: which spellings are one program, which are
 * none, and that the set a load settles holds only what the pack runs, for listeners still alive.
 */
class StageProgramsTest {

	private static final Set<String> RUNNING = Set.of("begin", "prepare", "deferred", "deferred3",
			"composite", "composite1", "composite2", "final");

	@AfterEach
	void forget() {
		AddonRegistry.clear();
	}

	/** A listener that names the programs it is handed, and remembers what it was called with. */
	private static final class Wants implements StageListener {

		private final Supplier<Set<String>> programs;

		Wants(String... programs) {
			this.programs = () -> new HashSet<>(Arrays.asList(programs));
		}

		Wants(Supplier<Set<String>> programs) {
			this.programs = programs;
		}

		@Override
		public void onStage(FrameStage stage, FrameContext frame) {
		}

		@Override
		public Set<String> programs() {
			return this.programs.get();
		}
	}

	private static AddonRegistry.Entry<StageListener> entry(String addon, StageListener listener) {
		return RegisteredPieces.of(addon, listener);
	}

	private static StagePrograms settle(List<AddonRegistry.Entry<StageListener>> listeners) {
		return StagePrograms.settle(listeners, RUNNING, "pack");
	}

	@Test
	void everyFamilyTheChainDrawsIsNamedByItsOwnProgramName() {
		for (String program : List.of("begin", "begin1", "prepare", "prepare2", "deferred",
				"deferred3", "composite", "composite1", "composite99", "final")) {
			assertEquals(Optional.of(program), StagePrograms.nameOf(program), program);
		}
	}

	@Test
	void aComputeFileNamesTheProgramItHangsOff() {
		assertEquals(Optional.of("composite3"), StagePrograms.nameOf("composite3_a"));
		assertEquals(Optional.of("deferred"), StagePrograms.nameOf("deferred_c"));
		assertEquals(Optional.of("prepare1"), StagePrograms.nameOf("prepare1_b"));
		assertEquals(Optional.of("begin"), StagePrograms.nameOf("begin_a"));
		assertEquals(Optional.of("final"), StagePrograms.nameOf("final_a"));
	}

	@Test
	void slotNoughtAndTheBareNameAreOneProgram() {
		assertEquals(Optional.of("deferred"), StagePrograms.nameOf("deferred0"));
		assertEquals(Optional.of("composite"), StagePrograms.nameOf("composite0"));
		assertEquals(Optional.of("composite"), StagePrograms.nameOf("composite0_a"));
	}

	@Test
	void aNameThatIsNoProgramOfTheFrameIsNone() {
		for (String name : List.of("", "gbuffers_terrain", "shadow", "dh_terrain", "shadowcomp",
				"shadowcomp1", "setup", "setup1", "deferred_pre", "composite100", "composite_", "Deferred",
				"deferred3.fsh", "colortex0", "nonsense")) {
			assertEquals(Optional.empty(), StagePrograms.nameOf(name), name);
		}
	}

	@Test
	void nobodyNamingAProgramSettlesToTheOneSharedEmptySet() {
		assertSame(StagePrograms.NONE, StagePrograms.settle(List.of(), RUNNING, "pack"));
		assertSame(StagePrograms.NONE, settle(List.of(entry("plain", new Wants()))));
		assertSame(StagePrograms.NONE, settle(List.of(entry("other", new Wants("gbuffers_terrain")))));
		assertFalse(StagePrograms.NONE.wants("deferred3"));
		assertEquals(Set.of(), StagePrograms.NONE.programs());
		assertEquals(List.of(), StagePrograms.NONE.listeners("deferred3"));
	}

	@Test
	void aListenerThatDoesNotOverrideProgramsNamesNone() {
		StageListener plain = (stage, frame) -> {
		};

		assertEquals(Set.of(), plain.programs());
		assertSame(StagePrograms.NONE, settle(List.of(entry("plain", plain))));
	}

	@Test
	void theSetHoldsOnlyWhatTheListenersNameAndThePackRuns() {
		StagePrograms settled = settle(List.of(
				entry("ray", new Wants("deferred3", "composite1", "composite5")),
				entry("bloom", new Wants("final", "prepare9"))));

		assertEquals(Set.of("deferred3", "composite1", "final"), settled.programs());
		assertTrue(settled.wants("deferred3"));
		assertTrue(settled.wants("composite1"));
		assertTrue(settled.wants("final"));
		assertFalse(settled.wants("composite5"), "the pack does not run it");
		assertFalse(settled.wants("composite"), "nobody named it");
		assertFalse(settled.wants("deferred"), "another slot of the same family");
	}

	@Test
	void everySpellingOfOneProgramIsOneEntryForTheListener() {
		AddonRegistry.Entry<StageListener> ray = entry("ray", new Wants("deferred", "deferred0",
				"deferred_a", "deferred_b"));
		StagePrograms settled = settle(List.of(ray));

		assertEquals(Set.of("deferred"), settled.programs());
		assertEquals(List.of(ray), settled.listeners("deferred"));
	}

	@Test
	void theListenersOfOneProgramComeInRegistryOrder() {
		AddonRegistry.Entry<StageListener> first = entry("first", new Wants("composite1"));
		AddonRegistry.Entry<StageListener> second = entry("second", new Wants("composite1", "final"));
		AddonRegistry.Entry<StageListener> third = entry("third", new Wants("composite1"));
		StagePrograms settled = settle(List.of(first, second, third));

		assertEquals(List.of(first, second, third), settled.listeners("composite1"));
		assertEquals(List.of(second), settled.listeners("final"));
		assertEquals(List.of(), settled.listeners("composite2"));
	}

	@Test
	void aListenerCutOffLaterIsNoLongerWantedAndTheOthersStay() {
		AddonRegistry.Entry<StageListener> doomed = entry("doomed", new Wants("composite1"));
		AddonRegistry.Entry<StageListener> steady = entry("steady", new Wants("composite1", "final"));
		StagePrograms settled = settle(List.of(doomed, steady));

		AddonRegistry.call(doomed, "record", listener -> {
			throw new IllegalStateException("planted");
		});

		assertTrue(doomed.cutOff());
		assertTrue(settled.wants("composite1"), "the steady one still wants it");
		assertTrue(settled.wants("final"));

		AddonRegistry.call(steady, "record", listener -> {
			throw new IllegalStateException("planted");
		});

		assertFalse(settled.wants("composite1"), "nobody alive wants it now");
		assertFalse(settled.wants("final"));
	}

	@Test
	void aListenerThatThrowsWhileAnsweringIsCutOffAndTheOthersAreStillAsked() {
		AddonRegistry.Entry<StageListener> broken = entry("broken", new Wants(() -> {
			throw new IllegalStateException("planted");
		}));
		AddonRegistry.Entry<StageListener> fine = entry("fine", new Wants("final"));
		StagePrograms settled = settle(List.of(broken, fine));

		assertTrue(broken.cutOff());
		assertFalse(fine.cutOff());
		assertEquals(Set.of("final"), settled.programs());
		assertEquals(List.of(fine), settled.listeners("final"));
	}

	@Test
	void aNullAnswerOrANullInItCountsAsAThrow() {
		AddonRegistry.Entry<StageListener> nothing = entry("nothing", new Wants(() -> null));
		AddonRegistry.Entry<StageListener> hole = entry("hole", new Wants(() -> {
			Set<String> named = new HashSet<>();
			named.add("final");
			named.add(null);

			return named;
		}));

		assertSame(StagePrograms.NONE, settle(List.of(nothing, hole)));
		assertTrue(nothing.cutOff());
		assertTrue(hole.cutOff());
	}

	@Test
	void aLoadSettlesAgainstWhatItsOwnPackRuns() {
		AddonRegistry.Entry<StageListener> ray = entry("ray", new Wants("deferred3", "composite2"));

		StagePrograms bare = StagePrograms.settle(List.of(ray), Set.of("composite2", "final"), "bare");
		StagePrograms full = StagePrograms.settle(List.of(ray), RUNNING, "full");

		assertEquals(Set.of("composite2"), bare.programs());
		assertEquals(Set.of("deferred3", "composite2"), full.programs());
		assertFalse(bare.wants("deferred3"));
		assertTrue(full.wants("deferred3"));
	}

	@Test
	void aListenerIsAskedOncePerSettleAndNotPerQuestion() {
		List<Integer> asked = new ArrayList<>();
		AddonRegistry.Entry<StageListener> ray = entry("ray", new Wants(() -> {
			asked.add(asked.size());

			return Set.of("composite1");
		}));
		StagePrograms settled = settle(List.of(ray));

		for (int frame = 0; frame < 100; frame++) {
			assertTrue(settled.wants("composite1"));
			assertFalse(settled.wants("composite2"));
			settled.listeners("composite1");
		}

		assertEquals(1, asked.size());
	}
}
