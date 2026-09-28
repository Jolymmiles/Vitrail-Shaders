package dev.vitrail.pack.source;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Holds where a block comment stands from one line to the next, read as a compiler reads it: a line
 * comment ends the walk of its line, and inside a block only the closing pair means anything.
 */
class BlockCommentsTest {

	@Test
	void opensOnASlashStarAndClosesOnAStarSlash() {
		assertTrue(BlockComments.openAfter("/* open", false));
		assertFalse(BlockComments.openAfter("/* one line */", false));
		assertTrue(BlockComments.openAfter("code /* a */ more /* b", false));
		assertFalse(BlockComments.openAfter("plain code;", false));
		assertFalse(BlockComments.openAfter("", false));
	}

	@Test
	void aLineCommentHidesAnOpeningOnTheSameLine() {
		assertFalse(BlockComments.openAfter("// /* not a block", false));
		assertFalse(BlockComments.openAfter("x = 1; // /*", false));
		assertTrue(BlockComments.openAfter("/* a // b", false));
	}

	@Test
	void insideABlockOnlyTheClosingPairIsLookedFor() {
		assertTrue(BlockComments.openAfter("still inside", true));
		assertTrue(BlockComments.openAfter("// not a line comment here", true));
		assertTrue(BlockComments.openAfter("/* nor a nested open", true));
		assertFalse(BlockComments.openAfter("done */", true));
		assertTrue(BlockComments.openAfter("done */ and /* reopened", true));
		assertFalse(BlockComments.openAfter("*/", true));
	}

	@Test
	void slashStarSlashOpensAndDoesNotCloseItself() {
		assertTrue(BlockComments.openAfter("/*/", false));
		assertFalse(BlockComments.openAfter("/**/", false));
		assertTrue(BlockComments.openAfter("*", true));
	}
}
