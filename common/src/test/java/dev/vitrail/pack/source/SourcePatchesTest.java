package dev.vitrail.pack.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.addon.AddonRegistry;
import dev.vitrail.api.PackIdentity;
import dev.vitrail.api.SourcePatcher;
import dev.vitrail.pack.option.SettingSet;
import dev.vitrail.pack.program.ProgramSet;
import dev.vitrail.pack.source.IncludeExpander.ExpandedUnit;
import dev.vitrail.pack.source.SyntheticPacks.Shape;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Holds what an add-on's {@link SourcePatcher} does to a pack as it is opened: a patch changes the
 * lines an include expands to, an added file is a file to every lookup, a path the pack already has
 * or that leaves it is refused, the identity is the same for a folder and a zip of the same files, and
 * a patcher that misbehaves is cut off without the pack noticing.
 * <p>
 * Every pack is built on disk in both shapes where the answer must not depend on which, and the
 * patchers are fakes made of fields.
 */
class SourcePatchesTest {

	private static final String COMPOSITE = "#version 330\n#include \"lib/a.glsl\"\nvoid main() {}\n";

	@TempDir
	Path temp;

	private int packs;

	private Path pack(Shape shape, String name, Map<String, String> files) throws IOException {
		Path parent = Files.createDirectories(this.temp.resolve("pack" + this.packs++));

		return shape.build(parent, name, files);
	}

	private static Map<String, String> files(String... pairs) {
		Map<String, String> files = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			files.put("shaders/" + pairs[i], pairs[i + 1]);
		}

		return files;
	}

	private static Map<String, List<String>> added(Object... pairs) {
		Map<String, List<String>> added = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			added.put((String) pairs[i], Arrays.asList(((String) pairs[i + 1]).split("\n", -1)));
		}

		return added;
	}

	private static List<String> rels(ShaderPackSource source, List<Path> files) {
		return files.stream().map(source::rel).toList();
	}

	private static ExpandedUnit expand(ShaderPackSource source, String entry) throws IOException {
		return new IncludeExpander(source, SettingSet.defaults()).expand(source.file(entry).orElseThrow());
	}

	// --- patches --------------------------------------------------------------------------------

	@ParameterizedTest
	@EnumSource(Shape.class)
	void aPatchChangesWhatAnIncludeExpandsTo(Shape shape) throws IOException {
		Path pack = pack(shape, "patched", files("composite.fsh", COMPOSITE, "lib/a.glsl", "const int Q = 1;\n"));
		FakePatcher fake = new FakePatcher();
		fake.edit = (path, lines) -> path.equals("shaders/lib/a.glsl")
				? lines.stream().map(line -> line.replace("Q = 1", "Q = 2")).toList()
				: lines;

		try (ShaderPackSource source = ShaderPackSource.open(pack, FakePatcher.entries(fake))) {
			assertEquals(List.of("#version 330", "const int Q = 2;", "", "void main() {}", ""),
					expand(source, "composite.fsh").lines());
		}

		// The paths are the ones the API promises: from the pack's root, forward slashes.
		assertEquals(List.of("shaders/composite.fsh", "shaders/lib/a.glsl"), fake.patched);

		// And nothing is written back: the same pack read without the add-on says what it always said.
		try (ShaderPackSource plain = ShaderPackSource.open(pack, List.of())) {
			assertEquals(List.of("#version 330", "const int Q = 1;", "", "void main() {}", ""),
					expand(plain, "composite.fsh").lines());
		}
	}

	@Test
	void aPatchToOneFileDoesNotTouchTheOthersAndChainsInAddonOrder() throws IOException {
		Path pack = pack(Shape.DIRECTORY, "chain", files("a.glsl", "x\n", "b.glsl", "x\n"));
		FakePatcher first = new FakePatcher();
		first.edit = (path, lines) -> path.endsWith("a.glsl") ? List.of("first(" + lines.get(0) + ")") : lines;
		FakePatcher second = new FakePatcher();
		second.edit = (path, lines) -> path.endsWith("a.glsl") ? List.of("second(" + lines.get(0) + ")") : lines;

		try (ShaderPackSource source = ShaderPackSource.open(pack, FakePatcher.entries(first, second))) {
			assertEquals(List.of("second(first(x))"), source.readLines(source.file("a.glsl").orElseThrow()));
			assertEquals(List.of("x", ""), source.readLines(source.file("b.glsl").orElseThrow()));
		}
	}

	@Test
	void aPatchThatHoldsALineBreakIsSplitSoThatEveryElementIsOneLine() throws IOException {
		Path pack = pack(Shape.DIRECTORY, "split", files("a.glsl", "x\n"));
		FakePatcher fake = new FakePatcher();
		fake.edit = (path, lines) -> List.of("one\ntwo\r\nthree");

		try (ShaderPackSource source = ShaderPackSource.open(pack, FakePatcher.entries(fake))) {
			assertEquals(List.of("one", "two", "three"), source.readLines(source.file("a.glsl").orElseThrow()));
		}
	}

	@Test
	void everyReaderIsGivenTheSamePatchedFileAndThePatcherIsAskedOncePerFile() throws IOException {
		Path pack = pack(Shape.DIRECTORY, "once", files("a.glsl", "x\n", "notes.txt", "x\n"));
		FakePatcher fake = new FakePatcher();
		fake.edit = (path, lines) -> List.of(path + " " + fake.patched.size());

		try (ShaderPackSource source = ShaderPackSource.open(pack, FakePatcher.entries(fake))) {
			Path notes = source.file("notes.txt").orElseThrow();
			Path glsl = source.file("a.glsl").orElseThrow();

			// The scan reads the text file first and the expander reads it after, and the two must not
			// be handed different answers for one file.
			assertEquals("shaders/notes.txt 1", source.searchableText(notes));
			assertEquals(List.of("shaders/notes.txt 1"), source.readLines(notes));
			assertEquals(List.of("shaders/a.glsl 2"), source.readLines(glsl));
			assertEquals(List.of("shaders/a.glsl 2"), source.readLines(glsl));
			assertEquals("shaders/a.glsl 2", source.searchableText(glsl));
		}

		assertEquals(List.of("shaders/notes.txt", "shaders/a.glsl"), fake.patched);
	}

	@Test
	void aMentionScanSeesTheTextAsPatchedAndAsAdded() throws IOException {
		Path pack = pack(Shape.DIRECTORY, "mentions", files(
				"composite.fsh", "void main() {}\n", "notes.txt", "OLD_NAME is here\n"));
		FakePatcher fake = new FakePatcher();
		fake.edit = (path, lines) -> switch (path) {
			case "shaders/notes.txt" -> List.of("PATCHED_NAME is here");
			case "shaders/composite.fsh" -> List.of("// SOURCE_NAME", "void main() {}");
			default -> lines;
		};
		fake.added = added("shaders/extra.txt", "ADDED_NAME");

		try (ShaderPackSource source = ShaderPackSource.open(pack, FakePatcher.entries(fake))) {
			SourceMentions mentions = SourceMentions.of(source,
					Set.of("OLD_NAME", "PATCHED_NAME", "SOURCE_NAME", "ADDED_NAME"));

			assertTrue(mentions.maybe("PATCHED_NAME"));
			assertTrue(mentions.maybe("SOURCE_NAME"));
			assertTrue(mentions.maybe("ADDED_NAME"));
			// A name a patch took out is not in the pack any more, and the proof is what a guard needs.
			assertFalse(mentions.maybe("OLD_NAME"));
		}
	}

	@Test
	void aFileNoPatchChangedIsSearchedAsTheBytesTheyAre() throws IOException {
		Path pack = pack(Shape.DIRECTORY, "raw", files("notes.txt", "café NAME\n"));
		FakePatcher fake = new FakePatcher();

		try (ShaderPackSource source = ShaderPackSource.open(pack, FakePatcher.entries(fake))) {
			String text = source.searchableText(source.file("notes.txt").orElseThrow());

			// Latin-1 of the UTF-8 bytes, as ever: two characters where the pack wrote one.
			assertEquals("cafÃ© NAME\n", text);
		}
	}

	// --- added files ----------------------------------------------------------------------------

	@ParameterizedTest
	@EnumSource(Shape.class)
	void anAddedFileIsFoundByAnIncludeAndListedWhereverTheFileWouldBe(Shape shape) throws IOException {
		Path pack = pack(shape, "added", files(
				"composite.fsh", "#version 330\n#include \"/lib/addon.glsl\"\nvoid main() {}\n",
				"lib/a.glsl", "#include \"addon.glsl\"\n"));
		FakePatcher fake = new FakePatcher();
		fake.added = added(
				"shaders/lib/addon.glsl", "// added\nconst int ADDED = 1;",
				"shaders/composite1.fsh", "void main() {}",
				"shaders/addon/notes.txt", "notes");

		try (ShaderPackSource source = ShaderPackSource.open(pack, FakePatcher.entries(fake))) {
			// Found through the two kinds of include, each by its own resolution.
			assertEquals(List.of("#version 330", "// added", "const int ADDED = 1;", "void main() {}", ""),
					expand(source, "composite.fsh").lines());
			Path lib = source.file("lib/a.glsl").orElseThrow();
			assertEquals(List.of("// added", "const int ADDED = 1;", ""), expand(source, "lib/a.glsl").lines());
			assertEquals("lib/addon.glsl", source.rel(source.resolveRelativeTo(lib, "addon.glsl").orElseThrow()));
			assertEquals("lib/addon.glsl", source.rel(source.resolveInsideShaders("/lib/addon.glsl").orElseThrow()));
			assertEquals("lib/addon.glsl", source.rel(source.file("lib/addon.glsl").orElseThrow()));

			// Listed, sources and the rest apart, in the fixed order.
			assertEquals(List.of("composite.fsh", "composite1.fsh", "lib/a.glsl", "lib/addon.glsl"),
					rels(source, source.sourceFiles()));
			assertEquals(List.of("addon/notes.txt"), rels(source, source.otherFiles()));
			assertEquals(List.of("addon", "lib"), source.topLevelDirectories());

			// And the program enumeration, which walks the source list, sees the program the add-on brings.
			ProgramSet programs = ProgramSet.enumerate(source, DimensionSet.discover(source));
			assertTrue(programs.keys().stream().anyMatch(key -> key.file().equals("composite1.fsh")));
		}
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void everyReaderOfAnAddedFileGetsItsTextWithoutTouchingTheDisk(Shape shape) throws IOException {
		Path pack = pack(shape, "readers", files("a.glsl", "x\n"));
		FakePatcher fake = new FakePatcher();
		fake.added = added("shaders/lib/addon.txt", "abc\ndef");
		byte[] text = "abc\ndef".getBytes(StandardCharsets.UTF_8);

		try (ShaderPackSource source = ShaderPackSource.open(pack, FakePatcher.entries(fake))) {
			Path file = source.file("lib/addon.txt").orElseThrow();

			assertEquals(List.of("abc", "def"), source.readLines(file));
			assertEquals("abc\ndef", source.searchableText(file));
			assertEquals(text.length, source.size(file));
			assertEquals("abc\ndef", new String(source.bytes(file), StandardCharsets.UTF_8));
			assertEquals("abc\n", new String(source.head(file, 4), StandardCharsets.UTF_8));
			assertThrows(IOException.class, () -> source.head(file, text.length + 1));
		}
	}

	@Test
	void anAddedFileIsFoundIgnoringCaseLikeTheOnesOnDisk() throws IOException {
		Path pack = pack(Shape.ZIP, "case", files("a.glsl", "x\n"));
		FakePatcher fake = new FakePatcher();
		fake.added = added("shaders/lib/Addon.glsl", "// added");

		try (ShaderPackSource source = ShaderPackSource.open(pack, FakePatcher.entries(fake))) {
			assertEquals(0, source.caseInsensitiveHits());
			assertEquals("lib/Addon.glsl", source.rel(source.file("lib/Addon.glsl").orElseThrow()));
			assertEquals(0, source.caseInsensitiveHits());

			assertEquals("lib/Addon.glsl", source.rel(source.file("LIB/addon.GLSL").orElseThrow()));
			assertEquals(1, source.caseInsensitiveHits());
			assertEquals(Optional.empty(), source.file("lib/other.glsl"));
		}
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void aPathThePackAlreadyHasOrThatLeavesTheShadersIsRefusedAndTheFirstAdderOfAPathKeepsIt(Shape shape)
			throws IOException {
		Path pack = pack(shape, "collide", files("composite.fsh", COMPOSITE, "lib/a.glsl", "// the pack's own\n"));
		FakePatcher first = new FakePatcher();
		first.added = added(
				"shaders/lib/a.glsl", "// from the add-on",
				"shaders/composite.fsh", "// replaced",
				"shaders/lib", "// a file where the pack has a folder",
				"shaders/../outside.glsl", "// climbs out",
				"shaders/lib/../../outside.glsl", "// climbs out",
				"shaders//double.glsl", "// empty segment",
				"shaders/lib\\back.glsl", "// backslash",
				"shaders/", "// no name",
				"lib/nowhere.glsl", "// not under shaders",
				"/shaders/lib/rooted.glsl", "// rooted",
				"shaders/lib/ok.glsl", "// ok");
		FakePatcher second = new FakePatcher();
		second.added = added("shaders/lib/ok.glsl", "// second", "shaders/lib/two.glsl", "// two");

		try (ShaderPackSource source = ShaderPackSource.open(pack, FakePatcher.entries(first, second))) {
			assertEquals(List.of("// the pack's own", ""), source.readLines(source.file("lib/a.glsl").orElseThrow()));
			assertEquals(List.of("// ok"), source.readLines(source.file("lib/ok.glsl").orElseThrow()));
			assertEquals(List.of("composite.fsh", "lib/a.glsl", "lib/ok.glsl", "lib/two.glsl"),
					rels(source, source.sourceFiles()));
			assertEquals(List.of(), source.otherFiles());
			assertEquals(List.of("lib"), source.topLevelDirectories());
			assertTrue(source.readLines(source.file("composite.fsh").orElseThrow()).contains("#version 330"));
		}
	}

	@Test
	void aPathThatLeadsOutOfAFolderPackThroughALinkIsRefused() throws IOException {
		Path root = Files.createDirectories(this.temp.resolve("linked"));
		Path outside = Files.createDirectories(root.resolve("outside"));
		Path shaders = Files.createDirectories(root.resolve("pack/shaders"));
		Files.writeString(shaders.resolve("a.glsl"), "x\n");
		try {
			Files.createSymbolicLink(shaders.resolve("lib"), Path.of("../../outside"));
		} catch (IOException | UnsupportedOperationException e) {
			Assumptions.abort("this filesystem holds no symbolic links");
		}

		FakePatcher fake = new FakePatcher();
		fake.added = added("shaders/lib/new.glsl", "// through the link", "shaders/fine.glsl", "// fine");

		try (ShaderPackSource source = ShaderPackSource.open(root.resolve("pack"), FakePatcher.entries(fake))) {
			assertEquals(Optional.empty(), source.file("lib/new.glsl"));
			assertEquals(List.of("a.glsl", "fine.glsl"), rels(source, source.sourceFiles()));
		}

		assertFalse(Files.exists(outside.resolve("new.glsl")));
	}

	@Test
	void anAddedFileHoldsToTheCeilingAFileOfThePackDoesAndToTheTotalOfText() throws IOException {
		Path pack = pack(Shape.DIRECTORY, "caps", files("a.glsl", "x\n"));
		FakePatcher fake = new FakePatcher();
		String past = "x".repeat(9 * 1024 * 1024);
		String nearly = "x".repeat(7_900_000);
		Map<String, List<String>> offered = new LinkedHashMap<>();
		offered.put("shaders/past.glsl", List.of(past));
		for (int i = 0; i < 9; i++) {
			offered.put("shaders/big" + i + ".glsl", List.of(nearly));
		}

		fake.added = offered;

		try (ShaderPackSource source = ShaderPackSource.open(pack, FakePatcher.entries(fake))) {
			assertEquals(Optional.empty(), source.file("past.glsl"));

			// Nine of them are 71 MB where a pack may hold 64 MiB of text, and it is the ninth that
			// takes the pack past it, as it would for a file the pack shipped.
			int read = 0;
			IOException refused = null;
			for (int i = 0; i < 9 && refused == null; i++) {
				try {
					source.readLines(source.file("big" + i + ".glsl").orElseThrow());
					read++;
				} catch (IOException e) {
					refused = e;
				}
			}

			assertEquals(8, read);
			assertTrue(refused.getMessage().contains("bytes of text"), refused.getMessage());
		}
	}

	// --- identity -------------------------------------------------------------------------------

	private String hashOf(Path pack) throws IOException {
		FakePatcher fake = new FakePatcher();
		try (ShaderPackSource source = ShaderPackSource.open(pack, FakePatcher.entries(fake))) {
			assertEquals(source.packName(), fake.identities.get(0).name());
		}

		return fake.identities.get(0).contentHash();
	}

	@Test
	void theIdentityHashIsTheSameForAFolderAndAZipOfTheSameFilesWhateverTheirOrder() throws IOException {
		Map<String, String> files = files("composite.fsh", COMPOSITE, "lib/a.glsl", "x\n", "notes.txt", "n\n");
		Map<String, String> reversed = new LinkedHashMap<>();
		List<String> names = new ArrayList<>(files.keySet());
		Collections.reverse(names);
		names.forEach(name -> reversed.put(name, files.get(name)));
		Map<String, String> wrapped = new LinkedHashMap<>();
		files.forEach((name, text) -> wrapped.put("Wrapper/" + name, text));

		String folder = hashOf(pack(Shape.DIRECTORY, "Same", files));
		String zip = hashOf(pack(Shape.ZIP, "Same", files));

		assertTrue(folder.matches("[0-9a-f]{64}"), folder);
		assertEquals(folder, zip);
		assertEquals(folder, hashOf(pack(Shape.ZIP, "Same", reversed)));
		assertEquals(folder, hashOf(pack(Shape.DIRECTORY, "Same", reversed)));
		// A pack re-zipped one folder down is the same pack: paths are taken from the shaders root.
		assertEquals(folder, hashOf(pack(Shape.ZIP, "Same", wrapped)));
	}

	@Test
	void theIdentityHashIsOverPathsAndContentsUnderShadersAndNothingElse() throws IOException, NoSuchAlgorithmException {
		Path pack = pack(Shape.DIRECTORY, "format", files("a.glsl", "x\n"));
		Files.writeString(pack.resolve("README.txt"), "outside the shaders folder");

		MessageDigest inner = MessageDigest.getInstance("SHA-256");
		MessageDigest outer = MessageDigest.getInstance("SHA-256");
		outer.update("a.glsl".getBytes(StandardCharsets.UTF_8));
		outer.update((byte) 0);
		outer.update(inner.digest("x\n".getBytes(StandardCharsets.UTF_8)));

		assertEquals(HexFormat.of().formatHex(outer.digest()), hashOf(pack));
	}

	@Test
	void theIdentityHashMovesWhenAFileDoesWhateverItIsChangedTo() throws IOException {
		Path pack = pack(Shape.DIRECTORY, "moves", files("composite.fsh", COMPOSITE, "lib/a.glsl", "aaa\n"));
		String before = hashOf(pack);
		assertEquals(before, hashOf(pack));

		Path file = pack.resolve("shaders/lib/a.glsl");
		Files.writeString(file, "bbb\n");
		// The same size, so that only the stamp tells the two apart, and stamped later, as any edit is.
		Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 60_000));
		String edited = hashOf(pack);
		assertNotEquals(before, edited);

		Files.writeString(file, "a much longer line than the one before\n");
		String longer = hashOf(pack);
		assertNotEquals(edited, longer);

		Files.move(file, pack.resolve("shaders/lib/renamed.glsl"));
		String renamed = hashOf(pack);
		assertNotEquals(longer, renamed);

		Files.writeString(pack.resolve("shaders/lib/extra.glsl"), "new\n");
		assertNotEquals(renamed, hashOf(pack));

		// And put back it is the hash it was, which is what makes it a key rather than a counter.
		Files.delete(pack.resolve("shaders/lib/extra.glsl"));
		Files.move(pack.resolve("shaders/lib/renamed.glsl"), file);
		Files.writeString(file, "aaa\n");
		assertEquals(before, hashOf(pack));
	}

	@Test
	void aPatcherThatDoesNotApplyIsNeverAskedAgainForThisOpeningAndOneThatDoesIsAskedOncePerOpening()
			throws IOException {
		Path pack = pack(Shape.DIRECTORY, "applies", files("a.glsl", "x\n"));
		FakePatcher yes = new FakePatcher();
		FakePatcher no = new FakePatcher();
		no.applies = false;
		no.edit = (path, lines) -> {
			throw new AssertionError("a patcher that does not apply must not patch");
		};
		no.added = added("shaders/never.glsl", "never");

		try (ShaderPackSource source = ShaderPackSource.open(pack, FakePatcher.entries(yes, no))) {
			no.applies = true;
			source.readLines(source.file("a.glsl").orElseThrow());
			source.readLines(source.file("a.glsl").orElseThrow());

			assertEquals(Optional.empty(), source.file("never.glsl"));
			assertEquals(1, yes.appliesAsked);
			assertEquals(1, no.appliesAsked);
		}

		ShaderPackSource.open(pack, FakePatcher.entries(yes, no)).close();

		assertEquals(2, yes.appliesAsked);
		assertEquals(2, no.appliesAsked);
	}

	// --- the fingerprint ------------------------------------------------------------------------

	@Test
	void theOpeningSaysItsPatchesMovedWhenAFingerprintOfAnApplyingPatcherDoes() throws IOException {
		Path pack = pack(Shape.DIRECTORY, "print", files("a.glsl", "x\n"));
		FakePatcher applying = new FakePatcher();
		FakePatcher other = new FakePatcher();
		other.applies = false;

		try (ShaderPackSource source = ShaderPackSource.open(pack, FakePatcher.entries(applying, other))) {
			assertFalse(source.patchesMoved());

			other.fingerprint = "not asked";
			assertFalse(source.patchesMoved());

			applying.fingerprint = "v2";
			assertTrue(source.patchesMoved());

			applying.fingerprint = "v1";
			assertFalse(source.patchesMoved());
		}
	}

	@Test
	void anOpeningWithoutPatchersNeverMoves() throws IOException {
		Path pack = pack(Shape.DIRECTORY, "none", files("a.glsl", "x\n"));

		try (ShaderPackSource source = ShaderPackSource.open(pack, List.of())) {
			assertFalse(source.patchesMoved());
		}

		FakePatcher unrelated = new FakePatcher();
		unrelated.applies = false;
		try (ShaderPackSource source = ShaderPackSource.open(pack, FakePatcher.entries(unrelated))) {
			assertFalse(source.patchesMoved());
		}
	}

	// --- a patcher that misbehaves --------------------------------------------------------------

	@Test
	void aPatcherThatThrowsIsCutOffAndTheOthersAndThePackStillRead() throws IOException {
		Path pack = pack(Shape.DIRECTORY, "throws", files("composite.fsh", COMPOSITE, "lib/a.glsl", "const int Q = 1;\n"));
		FakePatcher broken = new FakePatcher();
		broken.edit = (path, lines) -> {
			throw new IllegalStateException("planted");
		};
		FakePatcher good = new FakePatcher();
		good.edit = (path, lines) -> lines.stream().map(line -> line.replace("Q = 1", "Q = 2")).toList();
		List<AddonRegistry.Entry<SourcePatcher>> entries = FakePatcher.entries(broken, good);

		try (ShaderPackSource source = ShaderPackSource.open(pack, entries)) {
			assertEquals(List.of("#version 330", "const int Q = 2;", "", "void main() {}", ""),
					expand(source, "composite.fsh").lines());
		}

		assertTrue(entries.get(0).cutOff());
		assertFalse(entries.get(1).cutOff());
		// Cut off at its first patch, so it was never asked about the second file.
		assertEquals(1, broken.patched.size());
		assertEquals(2, good.patched.size());

		// A cut off patcher is not even asked whether it applies to the next pack.
		ShaderPackSource.open(pack, entries).close();
		assertEquals(1, broken.appliesAsked);
	}

	@Test
	void aPatcherThatAnswersWithNothingUsableIsCutOffLikeOneThatThrows() throws IOException {
		Path pack = pack(Shape.DIRECTORY, "unusable", files("a.glsl", "x\n", "b.glsl", "y\n"));
		FakePatcher nothing = new FakePatcher();
		nothing.edit = (path, lines) -> null;
		FakePatcher hole = new FakePatcher();
		hole.edit = (path, lines) -> Arrays.asList("fine", null);
		List<AddonRegistry.Entry<SourcePatcher>> entries = FakePatcher.entries(nothing, hole);

		try (ShaderPackSource source = ShaderPackSource.open(pack, entries)) {
			assertEquals(List.of("x", ""), source.readLines(source.file("a.glsl").orElseThrow()));
			assertEquals(List.of("y", ""), source.readLines(source.file("b.glsl").orElseThrow()));
		}

		assertTrue(entries.get(0).cutOff());
		assertTrue(entries.get(1).cutOff());
	}

	@Test
	void aPatcherThatThrowsWhereverItIsAskedLeavesThePackAsItShipped() throws IOException {
		Path pack = pack(Shape.DIRECTORY, "everywhere", files("composite.fsh", COMPOSITE, "lib/a.glsl", "x\n"));

		FakePatcher applies = new FakePatcher();
		applies.appliesThrows = true;
		FakePatcher adds = new FakePatcher();
		adds.addedThrows = true;
		adds.added = added("shaders/lib/half.glsl", "// never");
		FakePatcher prints = new FakePatcher();
		prints.fingerprintThrows = true;
		List<AddonRegistry.Entry<SourcePatcher>> entries = FakePatcher.entries(applies, adds, prints);

		try (ShaderPackSource source = ShaderPackSource.open(pack, entries)) {
			assertEquals(List.of("#version 330", "x", "", "void main() {}", ""),
					expand(source, "composite.fsh").lines());
			assertEquals(List.of("composite.fsh", "lib/a.glsl"), rels(source, source.sourceFiles()));
			// All three were cut off before the first line was read, so the whole opening is read
			// without them and is as fit to be kept as an opening nothing patched.
			assertFalse(source.patchesMoved());
		}

		assertTrue(entries.stream().allMatch(AddonRegistry.Entry::cutOff));
	}

	@Test
	void anOpeningCutOffMidwayIsNotKept() throws IOException {
		Path pack = pack(Shape.DIRECTORY, "midway", files("a.glsl", "x\n", "b.glsl", "y\n"));
		FakePatcher fragile = new FakePatcher();
		fragile.edit = (path, lines) -> {
			if (path.endsWith("b.glsl")) {
				throw new IllegalStateException("planted");
			}

			return List.of("patched");
		};

		try (ShaderPackSource source = ShaderPackSource.open(pack, FakePatcher.entries(fragile))) {
			assertEquals(List.of("patched"), source.readLines(source.file("a.glsl").orElseThrow()));
			assertFalse(source.patchesMoved());

			assertEquals(List.of("y", ""), source.readLines(source.file("b.glsl").orElseThrow()));
			assertTrue(source.patchesMoved());
		}
	}

	@Test
	void identityCarriesThePacksNameAsThePackListShowsIt() throws IOException {
		FakePatcher fake = new FakePatcher();
		Path zip = pack(Shape.ZIP, "Complementary", files("a.glsl", "x\n"));

		ShaderPackSource.open(zip, FakePatcher.entries(fake)).close();

		PackIdentity identity = fake.identities.get(0);
		assertEquals("Complementary", identity.name());
	}
}
