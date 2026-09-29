package dev.vitrail.sodium;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.api.TerrainAttribute;
import dev.vitrail.glsl.SodiumVertex;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Where the appended elements of a chunk vertex sit for every combination of them, and how the
 * attributes an add-on may ask for are named and unioned into what the mesh carries.
 * <p>
 * The order is pinned with literal names rather than with {@link SodiumVertex}'s constants, because
 * it is a contract with three places that cannot be loaded here: the encoder writes its words in it,
 * the format is built in it, and the pack's programs declare their inputs in it. The mesh checks the
 * encoder's order against this one when it is built, and this test is what holds the other side.
 */
class TerrainLayoutTest {

	private static final List<String> ORDER = List.of("a_BlockId", "a_MidTexCoord", "a_MidBlock",
			"a_TangentFrame", "a_TintAndAo");

	private static final List<String> OWN = List.of("a_Position", "a_Color", "a_TexCoord",
			"a_LightAndData");

	/** The five appended elements one bit each, in layout order, as a set of names. */
	private static List<String> subset(int mask) {
		List<String> names = new ArrayList<>(OWN);
		for (int at = 0; at < ORDER.size(); at++) {
			if ((mask >> at & 1) != 0) {
				names.add(ORDER.get(at));
			}
		}

		return names;
	}

	@Test
	void theAppendedElementsAreTheFiveInTheOrderTheEncoderWritesThem() {
		assertEquals(ORDER, TerrainLayout.APPENDED);
		assertEquals(OWN, SodiumVertex.ATTRIBUTES.subList(0, OWN.size()));
	}

	@Test
	void everyCombinationOfTheFiveCountsItsWordsFromTheEndOfSodiumsBytes() {
		for (int mask = 0; mask < 1 << ORDER.size(); mask++) {
			List<String> carried = subset(mask);

			for (int at = 0; at < ORDER.size(); at++) {
				String element = ORDER.get(at);
				int offset = TerrainLayout.appendedOffset(element, carried);
				if ((mask >> at & 1) == 0) {
					assertEquals(TerrainLayout.ABSENT, offset, element + " of " + mask);
				} else {
					// An element sits after as many words as there are carried elements before it,
					// which is a popcount of the lower bits and not a walk like the code's own.
					assertEquals(Integer.BYTES * Integer.bitCount(mask & ((1 << at) - 1)), offset,
							element + " of " + mask);
				}
			}
		}
	}

	@Test
	void anElementLeftOutClosesTheGapInsteadOfLeavingAHole() {
		List<String> carried = List.of("a_BlockId", "a_MidBlock", "a_TangentFrame");

		assertEquals(0, TerrainLayout.appendedOffset("a_BlockId", carried));
		assertEquals(4, TerrainLayout.appendedOffset("a_MidBlock", carried));
		assertEquals(8, TerrainLayout.appendedOffset("a_TangentFrame", carried));
		assertEquals(TerrainLayout.ABSENT, TerrainLayout.appendedOffset("a_MidTexCoord", carried));
	}

	@Test
	void theSeparatedColourTakesItsWordWhereverItSits() {
		List<String> carried = List.of("a_TintAndAo", "a_TangentFrame");

		assertEquals(0, TerrainLayout.appendedOffset("a_TangentFrame", carried));
		assertEquals(4, TerrainLayout.appendedOffset("a_TintAndAo", carried));
		assertEquals(4, TerrainLayout.appendedOffset("a_TintAndAo", List.of("a_BlockId", "a_TintAndAo")));
	}

	@Test
	void namesOutsideTheFiveAndRepeatsChangeNothing() {
		assertEquals(TerrainLayout.ABSENT, TerrainLayout.appendedOffset("a_Position", OWN));
		assertEquals(TerrainLayout.ABSENT, TerrainLayout.appendedOffset("a_Unknown", subset(31)));
		assertEquals(4, TerrainLayout.appendedOffset("a_MidBlock",
				List.of("a_Unknown", "a_BlockId", "a_BlockId", "a_MidBlock")));
	}

	@Test
	void everyAttributeHasItsOwnElementOfTheFiveAndComesBackFromIt() {
		Set<String> seen = new HashSet<>();
		for (TerrainAttribute attribute : TerrainAttribute.values()) {
			String element = TerrainLayout.element(attribute);

			assertTrue(TerrainLayout.APPENDED.contains(element), attribute + " is appended");
			assertTrue(seen.add(element), attribute + " shares its element");
			assertEquals(attribute, TerrainLayout.attribute(element));
		}

		assertEquals("a_BlockId", TerrainLayout.element(TerrainAttribute.BLOCK_ID));
		assertEquals("a_MidTexCoord", TerrainLayout.element(TerrainAttribute.MID_TEX_COORD));
		assertEquals("a_MidBlock", TerrainLayout.element(TerrainAttribute.MID_BLOCK));
		assertEquals("a_TangentFrame", TerrainLayout.element(TerrainAttribute.TANGENT_FRAME));
	}

	@Test
	void sodiumsOwnFourAndTheSeparatedColourAnswerNoAttribute() {
		for (String own : OWN) {
			assertNull(TerrainLayout.attribute(own), own);
		}

		assertNull(TerrainLayout.attribute("a_TintAndAo"));
		assertNull(TerrainLayout.attribute("a_Unknown"));
	}

	@Test
	void theNamesOfASetAreTheNamesOfItsMembers() {
		assertEquals(Set.of(), TerrainLayout.elements(Set.of()));
		assertEquals(Set.of("a_BlockId", "a_MidBlock"),
				TerrainLayout.elements(EnumSet.of(TerrainAttribute.MID_BLOCK, TerrainAttribute.BLOCK_ID)));
	}

	@Test
	void nothingForcedLeavesWhatTheProgramsAskedForAsItWas() {
		List<String> nothing = List.of();
		List<String> own = OWN;
		List<String> some = subset(0b10001);

		// The very list and not an equal one: an empty answer stays the empty answer, which the
		// mesh reads as no pack asking, and is not turned into Sodium's own four.
		assertSame(nothing, TerrainLayout.withForced(nothing, Set.of()));
		assertSame(own, TerrainLayout.withForced(own, Set.of()));
		assertSame(some, TerrainLayout.withForced(some, Set.of()));
	}

	@Test
	void forcedElementsJoinWhatTheProgramsReadInLayoutOrder() {
		assertEquals(subset(0b00100),
				TerrainLayout.withForced(List.of(), EnumSet.of(TerrainAttribute.MID_BLOCK)));
		assertEquals(subset(0b11001),
				TerrainLayout.withForced(subset(0b10001), EnumSet.of(TerrainAttribute.TANGENT_FRAME)));
		assertEquals(subset(0b01111),
				TerrainLayout.withForced(subset(0b00101), EnumSet.of(TerrainAttribute.TANGENT_FRAME,
						TerrainAttribute.MID_TEX_COORD, TerrainAttribute.BLOCK_ID)));
	}

	@Test
	void anElementBothAskedForAndForcedIsCarriedOnce() {
		List<String> asked = subset(0b00011);

		assertEquals(asked, TerrainLayout.withForced(asked,
				EnumSet.of(TerrainAttribute.BLOCK_ID, TerrainAttribute.MID_TEX_COORD)));
	}

	@Test
	void everyForcedCombinationYieldsTheListTheProgramsAreTranslatedAgainst() {
		// The mesh unions in settle and the programs union in loadTerrain, and they are only the same
		// list if the union is the same operation on either side of the pack's own reads.
		TerrainAttribute[] all = TerrainAttribute.values();
		for (int forcedMask = 0; forcedMask < 1 << all.length; forcedMask++) {
			Set<TerrainAttribute> forced = EnumSet.noneOf(TerrainAttribute.class);
			for (int at = 0; at < all.length; at++) {
				if ((forcedMask >> at & 1) != 0) {
					forced.add(all[at]);
				}
			}

			for (int readMask = 0; readMask < 1 << ORDER.size(); readMask++) {
				List<String> reads = subset(readMask);
				Set<String> readsAndForced = new LinkedHashSet<>(reads);
				readsAndForced.addAll(TerrainLayout.elements(forced));

				List<String> programs = SodiumVertex.carried(readsAndForced);
				List<String> mesh = TerrainLayout.withForced(programs, forced);

				assertEquals(programs, mesh, "forced " + forced + " reads " + readMask);
			}
		}
	}
}
