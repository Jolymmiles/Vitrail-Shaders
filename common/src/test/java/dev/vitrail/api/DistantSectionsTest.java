package dev.vitrail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Holds the shapes an add-on builds its far terrain lists from: a section keeps its own copy of the
 * meshes it was handed, and a source that does not tell the light from the camera hands over two
 * lists and gets the light the same ones.
 */
class DistantSectionsTest {

	private static final DistantMesh MESH = new DistantMesh() {
		@Override
		public boolean ready() {
			return true;
		}

		@Override
		public void free() {
		}
	};

	@Test
	void aSectionKeepsItsOwnCopyOfTheMeshesItWasHanded() {
		List<DistantMesh> working = new ArrayList<>(List.of(MESH));

		DistantSection section = new DistantSection(1, 2, 3, working);
		working.clear();

		assertEquals(List.of(MESH), section.meshes());
		assertThrows(UnsupportedOperationException.class, () -> section.meshes().clear());
	}

	@Test
	void aSectionRefusesANullListAndANullMesh() {
		assertThrows(NullPointerException.class, () -> new DistantSection(0, 0, 0, null));

		List<DistantMesh> withNull = new ArrayList<>();
		withNull.add(null);
		assertThrows(NullPointerException.class, () -> new DistantSection(0, 0, 0, withNull));
	}

	@Test
	void theLightGetsTheCameraListsWhenOnlyTheCameraOnesAreGiven() {
		List<DistantSection> opaque = List.of(new DistantSection(0, 0, 0, List.of(MESH)));
		List<DistantSection> water = List.of();

		DistantSections sections = new DistantSections(opaque, water);

		assertSame(opaque, sections.shadowOpaque());
		assertSame(water, sections.shadowWater());
	}

	@Test
	void theListsAreNotCopiedAndNoneOfThemMayBeNull() {
		List<DistantSection> opaque = new ArrayList<>();
		DistantSections sections = new DistantSections(opaque, List.of());

		opaque.add(new DistantSection(0, 0, 0, List.of(MESH)));

		assertSame(opaque, sections.opaque());
		assertEquals(1, sections.shadowOpaque().size());
		assertThrows(NullPointerException.class, () -> new DistantSections(null, List.of()));
		assertThrows(NullPointerException.class, () -> new DistantSections(List.of(), null));
		assertThrows(NullPointerException.class,
				() -> new DistantSections(List.of(), List.of(), null, List.of()));
		assertThrows(NullPointerException.class,
				() -> new DistantSections(List.of(), List.of(), List.of(), null));
	}

	@Test
	void noneDrawsNothingAnywhere() {
		assertTrue(DistantSections.NONE.opaque().isEmpty());
		assertTrue(DistantSections.NONE.water().isEmpty());
		assertTrue(DistantSections.NONE.shadowOpaque().isEmpty());
		assertTrue(DistantSections.NONE.shadowWater().isEmpty());
	}
}
