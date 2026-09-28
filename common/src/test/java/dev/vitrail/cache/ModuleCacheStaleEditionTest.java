package dev.vitrail.cache;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Holds the module cache's clearing of other editions to what it is: a courtesy to the disk, which
 * a file that will not go can make incomplete and never make fatal.
 * <p>
 * A folder whose write permission is taken away is how a refusal is planted, a file inside it then
 * being one nothing may delete. That is the POSIX shape of what a scanner or an indexer holding a
 * file does on Windows. The permission is put back in a {@code finally}, so the folder JUnit made
 * can still be cleared after the test.
 */
class ModuleCacheStaleEditionTest {

	private static final String FAMILY = "0.13.0+mc26.2";

	@TempDir
	Path temp;

	@Test
	void clearsEveryOtherEditionItCanAndNamesTheOneThatStays() throws IOException {
		Path root = Files.createDirectories(temp.resolve("modules"));
		Path mine = Files.createDirectories(root.resolve(FAMILY));
		Files.writeString(mine.resolve("kept.mod"), "mine");

		Path gone = Files.createDirectories(root.resolve("0.11.0+mc26.2"));
		Files.writeString(gone.resolve("a.mod"), "old");

		Path stuck = Files.createDirectories(root.resolve("0.12.0+mc26.2"));
		Path locked = Files.createDirectories(stuck.resolve("locked"));
		Path held = Files.writeString(locked.resolve("b.mod"), "held");

		assertTrue(locked.toFile().setWritable(false), "the planted folder could not be locked");
		try {
			// Root deletes whatever it likes, so there a refusal cannot be planted this way.
			assumeFalse(Files.isWritable(locked), "a folder without write permission is still "
					+ "writable here, as it is to root");

			String left = ModuleCache.dropOtherEditions(root, mine, FAMILY);

			assertTrue(Files.exists(mine.resolve("kept.mod")),
					"this edition's own store was touched");
			assertFalse(Files.exists(gone), "an edition that could go was left behind");
			assertTrue(Files.exists(held), "the planted refusal did not refuse");
			assertTrue(left.contains("0.12.0+mc26.2"),
					"the folder left behind is not named: " + left);
			assertFalse(left.contains("0.11.0+mc26.2"), "a folder that went is named: " + left);
		} finally {
			locked.toFile().setWritable(true);
		}
	}
}
