package dev.vitrail.addon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.api.DefineSource;
import dev.vitrail.pack.option.DefineNames;
import dev.vitrail.pack.option.EngineDefines;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

/**
 * Holds what joins the engine's define table from an add-on and what does not: the order it is
 * gathered in, the names refused, the engine's own value staying, and the revision that makes the
 * pack be read again.
 * <p>
 * Everything here runs on pieces made by hand, so no test depends on the session's registry or on
 * the game. The reload itself is {@code PackDefines.stale}, which reads the game and is not reached
 * from here; what it asks, {@link AddonDefines#moved}, is.
 */
class AddonDefinesTest {

	private static final EngineDefines.Environment MACHINE = EngineDefines.Environment.of(260200);
	private static final Predicate<String> RESERVED = DefineNames.reservedIn(MACHINE);

	private static AddonRegistry.Entry<DefineSource> source(String addon, String... pairs) {
		return RegisteredPieces.of(addon, defines -> {
			for (int i = 0; i < pairs.length; i += 2) {
				defines.put(pairs[i], pairs[i + 1]);
			}
		});
	}

	private static Map<String, String> gather(List<AddonRegistry.Entry<DefineSource>> sources) {
		return AddonDefines.gather(sources, RESERVED, new HashSet<>());
	}

	@Test
	void postsInAddonOrderAndThenInRegistrationOrder() {
		Map<String, String> posed = gather(List.of(
				source("first", "Z_LAST_ALPHABETICALLY", "1", "A_FIRST_ALPHABETICALLY", "2"),
				source("first", "MIDDLE", ""),
				source("second", "FROM_THE_SECOND", "3")));

		assertEquals(List.of("Z_LAST_ALPHABETICALLY", "A_FIRST_ALPHABETICALLY", "MIDDLE", "FROM_THE_SECOND"),
				List.copyOf(posed.keySet()));
		assertEquals("", posed.get("MIDDLE"));
	}

	@Test
	void theTableCarriesThemAfterEverythingTheEngineSaysInTheOrderGathered() {
		Map<String, String> posed = gather(List.of(source("a", "ZED", "1", "ALPHA", "")));

		List<String> names = List.copyOf(EngineDefines.table(MACHINE.withAddonDefines(posed)).keySet());

		assertEquals(List.of("ZED", "ALPHA"), names.subList(names.size() - 2, names.size()));
		assertEquals("1", EngineDefines.table(MACHINE.withAddonDefines(posed)).get("ZED"));
	}

	@Test
	void refusesANameTheEngineAlreadyPosesAndTheEnginesValueStays() {
		Map<String, String> posed = gather(List.of(source("greedy",
				"MC_VERSION", "1", "IS_IRIS", "no", "DH_BLOCK_LAVA", "0", "MAX_COLOR_BUFFERS", "1",
				"WANTED", "yes")));

		assertEquals(Map.of("WANTED", "yes"), posed);

		Map<String, String> table = EngineDefines.table(MACHINE.withAddonDefines(posed));
		assertEquals(Integer.toString(MACHINE.mcVersion()), table.get("MC_VERSION"));
		assertEquals("", table.get("IS_IRIS"));
		assertEquals("6", table.get("DH_BLOCK_LAVA"));
		assertEquals("yes", table.get("WANTED"));
	}

	@Test
	void refusesTheNamesTheEngineWithholdsOnThisMachineToo() {
		// Absent is an answer: DISTANT_HORIZONS missing says the far terrain is not there, and a
		// posed one would say it is.
		assertFalse(EngineDefines.table(MACHINE).containsKey("DISTANT_HORIZONS"));

		Map<String, String> posed = gather(List.of(source("liar",
				"DISTANT_HORIZONS", "", "IRIS_FEATURE_PER_BUFFER_BLENDING", "", "MC_OS_LINUX", "",
				"MC_GL_VENDOR_NVIDIA", "", "MC_TEXTURE_FORMAT_LAB_PBR", "", "FINE", "")));

		assertEquals(List.of("FINE"), List.copyOf(posed.keySet()));
	}

	@Test
	void refusesANameThePreprocessorCouldNotReadAndAValueThatCouldNotStandOnOneLine() {
		Map<String, String> written = new LinkedHashMap<>();
		written.put("1LEADING_DIGIT", "");
		written.put("HAS-DASH", "");
		written.put("HAS SPACE", "");
		written.put("", "");
		written.put("NON_ASCII_é", "");
		written.put(null, "");
		written.put("LINE_BREAK", "1\n#define INJECTED 1");
		written.put("CARRIAGE_RETURN", "1\r");
		written.put("NO_VALUE", null);
		written.put("_UNDERSCORE_FIRST_9", "ok");
		written.put("lower_case", "ok");

		Map<String, String> posed = gather(List.of(RegisteredPieces.of("sloppy", defines -> defines.putAll(written))));

		assertEquals(List.of("_UNDERSCORE_FIRST_9", "lower_case"), List.copyOf(posed.keySet()));
	}

	@Test
	void theFirstSourceKeepsAWordTwoSourcesDisagreeOn() {
		Map<String, String> posed = gather(List.of(
				source("early", "SHARED", "1", "AGREED", "same"),
				source("late", "SHARED", "2", "AGREED", "same")));

		assertEquals("1", posed.get("SHARED"));
		assertEquals("same", posed.get("AGREED"));
	}

	@Test
	void saysAWordItRefusedOnceWhateverHowOftenItIsGathered() {
		Set<String> refused = new HashSet<>();
		List<AddonRegistry.Entry<DefineSource>> sources = List.of(source("repeat", "MC_VERSION", "1", "bad-name", ""));

		AddonDefines.gather(sources, RESERVED, refused);
		assertEquals(Set.of("repeat MC_VERSION", "repeat bad-name"), refused);

		// A second gathering adds nothing to what was said, which is what keeps a portal from
		// repeating the line.
		AddonDefines.gather(sources, RESERVED, refused);
		assertEquals(2, refused.size());
	}

	@Test
	void aSourceThatThrowsIsCutOffAndNothingItWroteIsTakenWhileTheOthersStay() {
		AtomicInteger asked = new AtomicInteger();
		AddonRegistry.Entry<DefineSource> broken = RegisteredPieces.of("broken", defines -> {
			asked.incrementAndGet();
			defines.put("HALF_WRITTEN", "");
			throw new IllegalStateException("planted");
		});
		List<AddonRegistry.Entry<DefineSource>> sources = List.of(
				source("before", "BEFORE", ""), broken, source("after", "AFTER", ""));

		assertEquals(List.of("BEFORE", "AFTER"), List.copyOf(gather(sources).keySet()));
		assertTrue(broken.cutOff());

		assertEquals(List.of("BEFORE", "AFTER"), List.copyOf(gather(sources).keySet()));
		assertEquals(1, asked.get());
	}

	@Test
	void aRevisionThatMovesMakesTheRecordedReadingStale() {
		AtomicLong revision = new AtomicLong(3);
		AddonRegistry.Entry<DefineSource> moving = RegisteredPieces.of("moving", new DefineSource() {
			@Override
			public void write(Map<String, String> defines) {
				defines.put("LEVEL", Long.toString(revision.get()));
			}

			@Override
			public long revision() {
				return revision.get();
			}
		});
		List<AddonRegistry.Entry<DefineSource>> sources = List.of(source("still", "STILL", ""), moving);

		List<AddonDefines.Revision> recorded = AddonDefines.revisions(sources);
		assertFalse(AddonDefines.moved(sources, recorded));

		revision.set(4);

		assertTrue(AddonDefines.moved(sources, recorded));
		// Read again where it stands now, the reading is quiet again.
		assertFalse(AddonDefines.moved(sources, AddonDefines.revisions(sources)));
	}

	@Test
	void aSourceThatDefaultsItsRevisionNeverMoves() {
		List<AddonRegistry.Entry<DefineSource>> sources = List.of(source("fixed", "FIXED", ""));

		List<AddonDefines.Revision> recorded = AddonDefines.revisions(sources);

		assertFalse(AddonDefines.moved(sources, recorded));
		assertEquals(List.of(new AddonDefines.Revision(0, false)), recorded);
	}

	@Test
	void aSourceCutOffSinceTheReadingMovesItEvenIfItsNumberDidNot() {
		AtomicInteger writes = new AtomicInteger();
		AddonRegistry.Entry<DefineSource> fragile = RegisteredPieces.of("fragile", defines -> {
			if (writes.incrementAndGet() > 1) {
				throw new IllegalStateException("planted");
			}

			defines.put("ONCE", "");
		});
		List<AddonRegistry.Entry<DefineSource>> sources = new ArrayList<>(List.of(fragile));

		assertEquals(List.of("ONCE"), List.copyOf(gather(sources).keySet()));
		List<AddonDefines.Revision> recorded = AddonDefines.revisions(sources);
		assertFalse(AddonDefines.moved(sources, recorded));

		assertTrue(gather(sources).isEmpty());

		// Its defines are gone from the table, so the pack has to be read again without them.
		assertTrue(AddonDefines.moved(sources, recorded));
	}

	@Test
	void noSourcesMeansNothingToMoveAndAnAddedOneMovesTheRecord() {
		List<AddonRegistry.Entry<DefineSource>> none = List.of();

		assertFalse(AddonDefines.moved(none, AddonDefines.revisions(none)));
		assertTrue(AddonDefines.moved(List.of(source("late", "X", "")), List.of()));
	}

	@Test
	void anEnvironmentBuiltByHandStillLetsTheEngineWin() {
		Map<String, String> byHand = new LinkedHashMap<>();
		byHand.put("MC_VERSION", "9");
		byHand.put("EXTRA", "1");

		Map<String, String> table = EngineDefines.table(MACHINE.withAddonDefines(byHand));

		assertEquals(Integer.toString(MACHINE.mcVersion()), table.get("MC_VERSION"));
		assertEquals("1", table.get("EXTRA"));
		assertNull(EngineDefines.table(MACHINE).get("EXTRA"));
	}
}
