package dev.vitrail.pack.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.addon.RegisteredPieces;
import dev.vitrail.api.AddonRegistrar;
import dev.vitrail.api.SourcePatcher;
import dev.vitrail.api.VitrailAddon;
import dev.vitrail.pack.option.EngineDefines;
import dev.vitrail.pack.option.OptionValue;
import dev.vitrail.pack.option.SettingSet;
import dev.vitrail.pack.source.IncludeExpander.ExpandedUnit;
import dev.vitrail.pack.source.SyntheticPacks.Shape;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Holds what an add-on changes about when {@link KeptPack} may hand one opening to a second load:
 * a patcher's fingerprint and the defines an add-on poses both decide it, and the opening a load is
 * given is read under the patches and the table that load asked for.
 * <p>
 * The patchers here are registered in the session's own registry, because that is where
 * {@code ShaderPackSource.open} looks for them, and every test puts it and the engine's table back.
 */
class KeptPackAddonTest {

	private static final Map<String, OptionValue> NO_CHOICES = Map.of();

	@TempDir
	Path temp;

	private EngineDefines.Environment machine;

	@BeforeEach
	void rememberProcessWideState() {
		OpenedPack.forgetKept();
		RegisteredPieces.forget();
		this.machine = EngineDefines.machine();
	}

	@AfterEach
	void restoreProcessWideState() {
		OpenedPack.forgetKept();
		RegisteredPieces.forget();
		EngineDefines.machine(this.machine);
	}

	private Path pack(String name, String... pairs) throws IOException {
		Map<String, String> files = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			files.put("shaders/" + pairs[i], pairs[i + 1]);
		}

		return Shape.DIRECTORY.build(Files.createDirectories(this.temp.resolve(name + "-parent")), name, files);
	}

	private static VitrailAddon addon(SourcePatcher patcher) {
		return new VitrailAddon() {
			@Override
			public String id() {
				return "test-addon";
			}

			@Override
			public void register(AddonRegistrar registrar) {
				registrar.sources(patcher);
			}
		};
	}

	@Test
	void anOpeningIsHeldWhileEveryFingerprintStandsAndReadUnderThePatchesItWasOpenedWith() throws IOException {
		FakePatcher fake = new FakePatcher();
		fake.edit = (path, lines) -> path.endsWith("a.glsl") ? List.of("// patched under " + fake.fingerprint) : lines;
		RegisteredPieces.load(addon(fake));
		Path packPath = pack("held", "a.glsl", "// as written\n");

		OpenedPack first = OpenedPack.openKept(packPath, NO_CHOICES, "");
		assertEquals(List.of("// patched under v1"), first.source().readLines(first.source().file("a.glsl").orElseThrow()));
		first.close();

		assertSame(first, OpenedPack.openKept(packPath, NO_CHOICES, ""));
		// Asked once, when it was opened, and not again by a load that was served the same opening.
		assertEquals(1, fake.appliesAsked);
	}

	@Test
	void aFingerprintThatMovesIsAnOpeningNoLongerServedAndTheNextOneIsReadUnderTheNewPatches() throws IOException {
		FakePatcher fake = new FakePatcher();
		fake.edit = (path, lines) -> path.endsWith("a.glsl") ? List.of("// patched under " + fake.fingerprint) : lines;
		RegisteredPieces.load(addon(fake));
		Path packPath = pack("moved", "a.glsl", "// as written\n");

		OpenedPack first = OpenedPack.openKept(packPath, NO_CHOICES, "");
		first.source().readLines(first.source().file("a.glsl").orElseThrow());
		first.close();

		fake.fingerprint = "v2";
		OpenedPack second = OpenedPack.openKept(packPath, NO_CHOICES, "");

		assertNotSame(first, second);
		// The old opening is let go, and the new one is the one asked for again.
		assertEquals(0, first.source().filesRead());
		assertEquals(List.of("// patched under v2"),
				second.source().readLines(second.source().file("a.glsl").orElseThrow()));
		assertEquals(2, fake.appliesAsked);
		second.close();
		assertSame(second, OpenedPack.openKept(packPath, NO_CHOICES, ""));
	}

	@Test
	void aPatcherCutOffAfterTheOpeningEndsItsHoldAndTheNextOpeningIsReadWithoutIt() throws IOException {
		FakePatcher fake = new FakePatcher();
		fake.edit = (path, lines) -> {
			if (fake.patched.size() > 1) {
				throw new IllegalStateException("planted");
			}

			return List.of("// patched");
		};
		RegisteredPieces.load(addon(fake));
		Path packPath = pack("cut", "a.glsl", "// a\n", "b.glsl", "// b\n");

		OpenedPack first = OpenedPack.openKept(packPath, NO_CHOICES, "");
		assertEquals(List.of("// patched"), first.source().readLines(first.source().file("a.glsl").orElseThrow()));
		// The second file is read as the pack wrote it, because the patcher went at that point.
		assertEquals(List.of("// b", ""), first.source().readLines(first.source().file("b.glsl").orElseThrow()));
		first.close();

		OpenedPack second = OpenedPack.openKept(packPath, NO_CHOICES, "");

		assertNotSame(first, second);
		assertEquals(List.of("// a", ""), second.source().readLines(second.source().file("a.glsl").orElseThrow()));
	}

	@Test
	void theDefinesAnAddonPosesArePartOfWhatDecidesTheOpeningIsShared() throws IOException {
		Path packPath = pack("defines", "a.glsl", "// a\n");
		EngineDefines.Environment base = EngineDefines.Environment.of(260200);

		EngineDefines.machine(base.withAddonDefines(Map.of("ADDON_LEVEL", "1")));
		OpenedPack first = OpenedPack.openKept(packPath, NO_CHOICES, "");
		first.close();
		assertSame(first, OpenedPack.openKept(packPath, NO_CHOICES, ""));

		EngineDefines.machine(base.withAddonDefines(Map.of("ADDON_LEVEL", "2")));

		assertNotSame(first, OpenedPack.openKept(packPath, NO_CHOICES, ""));
	}

	@Test
	void aDefineAnAddonPosesDecidesWhichBranchOfAPackIsLive() throws IOException {
		Path packPath = pack("branch",
				"composite.fsh", "#ifdef ADDON_FLAG\n#include \"flag.glsl\"\n#else\n#include \"plain.glsl\"\n#endif\n",
				"flag.glsl", "// flag\n",
				"plain.glsl", "// plain\n");
		EngineDefines.Environment base = EngineDefines.Environment.of(260200);

		EngineDefines.machine(base);
		ExpandedUnit without;
		try (ShaderPackSource source = ShaderPackSource.open(packPath, List.of())) {
			without = new IncludeExpander(source, SettingSet.defaults()).expand(source.file("composite.fsh").orElseThrow());
		}

		EngineDefines.machine(base.withAddonDefines(Map.of("ADDON_FLAG", "")));
		ExpandedUnit with;
		try (ShaderPackSource source = ShaderPackSource.open(packPath, List.of())) {
			with = new IncludeExpander(source, SettingSet.defaults()).expand(source.file("composite.fsh").orElseThrow());
		}

		assertTrue(without.lines().contains("// plain"), without.lines().toString());
		assertTrue(with.lines().contains("// flag"), with.lines().toString());
		assertTrue(with.defines().containsKey("ADDON_FLAG"));
	}
}
