package dev.vitrail.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.vitrail.api.AddonImage;
import dev.vitrail.render.storage.StorageImages;

import org.junit.jupiter.api.Test;

/**
 * Holds what a descriptor is bound to for the answer an add-on gave: the image where the shader can
 * use it, and otherwise a stand-in of the kind the shader declared, never the placeholder texel.
 */
class AddonImagesTest {

	private static final long BLACK = 0xB1ACL;
	private static final long SCRATCH = 0x5CA7L;

	private static AddonImage image(long view, int width, int height, boolean storage) {
		return new AddonImage(view, 0x1AL, 37, width, height, storage);
	}

	@Test
	void aUsableImageIsBoundAsItIsAndTypedByTheDeclaration() {
		StorageImages.Bound sampled = AddonImages.choose(image(7L, 1920, 1080, true), false, BLACK, SCRATCH);
		StorageImages.Bound stored = AddonImages.choose(image(7L, 1920, 1080, true), true, BLACK, SCRATCH);

		assertEquals(new StorageImages.Bound(7L, false, false), sampled);
		assertEquals(new StorageImages.Bound(7L, true, false), stored);
	}

	@Test
	void anImageThatCannotBeStoredIntoStillServesASamplerDeclaration() {
		assertEquals(new StorageImages.Bound(7L, false, false),
				AddonImages.choose(image(7L, 64, 64, false), false, BLACK, SCRATCH));
	}

	@Test
	void nothingIsAnsweredWithTheBlackStandInForASamplerAndTheScratchForAStorageImage() {
		assertEquals(new StorageImages.Bound(BLACK, false, false),
				AddonImages.choose(null, false, BLACK, SCRATCH));
		assertEquals(new StorageImages.Bound(SCRATCH, true, false),
				AddonImages.choose(null, true, BLACK, SCRATCH));
	}

	@Test
	void anImageTheDeclarationCannotUseIsAnsweredWithTheStandIn() {
		assertEquals(new StorageImages.Bound(BLACK, false, false),
				AddonImages.choose(image(0L, 64, 64, false), false, BLACK, SCRATCH));
		assertEquals(new StorageImages.Bound(BLACK, false, false),
				AddonImages.choose(image(7L, 0, 64, false), false, BLACK, SCRATCH));
		assertEquals(new StorageImages.Bound(SCRATCH, true, false),
				AddonImages.choose(image(7L, 64, 64, false), true, BLACK, SCRATCH));
	}

	@Test
	void aStandInThatIsNotAllocatedLeavesTheNameToItsPlaceholder() {
		assertNull(AddonImages.choose(null, false, 0L, SCRATCH));
		assertNull(AddonImages.choose(null, true, BLACK, 0L));
	}

	@Test
	void everyRefusalSaysWhy() {
		assertNotNull(AddonImages.refusal(image(0L, 64, 64, true), false));
		assertNotNull(AddonImages.refusal(image(7L, 0, 64, true), false));
		assertNotNull(AddonImages.refusal(image(7L, 64, -1, true), false));
		assertNotNull(AddonImages.refusal(image(7L, 64, 64, false), true));
		assertNull(AddonImages.refusal(image(7L, 64, 64, false), false));
		assertNull(AddonImages.refusal(image(7L, 64, 64, true), true));
		assertFalse(AddonImages.refusal(image(0L, 64, 64, true), false).isEmpty());
	}
}
