package dev.vitrail.cache;

import dev.vitrail.glsl.LocalZeroes;
import dev.vitrail.render.PackChain;
import dev.vitrail.render.PackNames;
import dev.vitrail.render.RawLocals;
import dev.vitrail.render.SamplerReach;
import dev.vitrail.render.ShaderDebugInfo;
import dev.vitrail.Vitrail;

import org.jspecify.annotations.Nullable;
import org.lwjgl.Version;
import org.lwjgl.system.MemoryUtil;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Keeps what the game's compiler makes of each shader unit, on disk, so a second load of the same
 * pack does not pay for the compile again.
 * <p>
 * <strong>The 26.3 half, and what it stores is the SPIR-V alone.</strong> The 26.2 half stores the
 * module the compiler made together with the reflection SPIRV-Cross read off it, serialised through
 * the records of the game's {@code IntermediaryShaderModule}, because 26.2 reflected every module as
 * it was made. 26.3 removed those records: its module is the words and the stage, and reflects
 * itself on demand when a pipeline is built from it. So the words are all there is to keep, as the
 * compiler left them and this engine's passes patched them, and a served unit is a file read and a
 * module made around it, which reflects when asked exactly as a compiled one does. What the
 * reflection then leaves out, {@code SamplerReach}, is read off the served words the same way.
 * <p>
 * The text a unit is keyed on is the one handed to {@code GlslCompiler.compileToSpv}, and on 26.3
 * that is not the whole input: the defines travel beside it and are handed to shaderc as macros,
 * so they are keyed too, and a unit that includes another file is not stored at all, the included
 * text being fetched by the compiler rather than handed over. This engine's units are flattened
 * before they reach the compiler, so none of its own has an include.
 * <p>
 * <strong>The key IS the input, hashed</strong>, and nothing else: the exact text handed to the
 * compiler, the stage it is compiled for, and everything that decides what that text turns into,
 * which is the mod's version and, on a development build, the commit behind it, the game's, the
 * loader with its own version, and the LWJGL build whose bundled shaderc and SPIRV-Cross do the
 * work. Nothing is keyed on a pack name, a file path or the debug name the module carries, so
 * there is no invalidation to get wrong and none is written. An edited shader, a moved pack
 * setting, a translator that emits one word differently, a loader that patches the compiler: each
 * is a different key, and the blob under the old one is never asked for again.
 * <p>
 * <strong>The debug name stays out of the key on purpose.</strong> The game's pipeline cache is
 * keyed on the identifier and never on the text, so this engine puts the load number in that name
 * ({@code pack/<load>/...}): two chains must not share an identifier when their GLSL differs
 * ({@code docs/internals/game-graphics-api.md}). The disk key already carries the text, so hashing
 * the load number as well would make every Apply, every R and every portal a miss of identical
 * GLSL. F3+T does not bump the load and already hit; a pack reload now hits too. A module on this
 * game carries no name at all, so a served one is the same object a compiled one would have been.
 * Two texts colliding under one blob would need a SHA-256 collision of the source, which is not
 * the trap the load number exists to prevent.
 * <p>
 * <strong>One bit of the name is keyed all the same</strong>, because it changes the bytes: whether
 * the unit is this engine's, which is what {@code RawLocals.ours} reads off the name. Only such a
 * unit is compiled with its locations assigned and the OpenGL names of two builtins, and only such
 * a unit is given its zeroes and has its names stripped, so one text compiled under a name of
 * ours and under the game's comes out as two different modules. The bit goes in and the name,
 * load number and all, stays out.
 * <p>
 * <strong>What is stored is the module as its maker handed it over</strong>, at the one instant at
 * which it is finished and nothing has yet read it: {@code PipelineBuilder} rewrites the bindings
 * of a module it builds a pipeline from, so a module is stored before any caller has had it, and
 * every hit is an allocation of its own.
 * <p>
 * A wrong blob is worse than a slow load: it is a picture that is wrong, or a lost device, with
 * nothing on screen pointing back here. A length and the SPIR-V magic word are not enough on their
 * own: a truncation on a four byte boundary keeps the magic word and a length both checks accept,
 * and what it then reaches is a native parser that no Java catch stands in front of. So every file
 * carries a digest of its own bytes behind them, and a blob its digest does not answer for is never
 * handed on. Absent, truncated, corrupt, unreadable, or of a shape this build cannot rebuild a
 * module from: every one of them is a MISS rather than an error, and ends in the compiler running
 * exactly as it did before this class existed.
 * <p>
 * A write lands through a neighbouring file and a move, so a process killed halfway through
 * leaves a neighbour rather than a half module under a whole name. The move is atomic where the
 * file system offers it and a plain replace where it does not, and nothing here is forced to the
 * platter, so what answers for a file after a power cut is the digest behind it and not the move.
 * <p>
 * <strong>The disk is bounded</strong>, at half a gigabyte by default, and bounded per edition:
 * the files sit under a directory named for the mod and game versions, and for a development
 * build the commit as well, and a directory named for another edition is deleted when this one
 * opens. Without that an update would fill a fresh set of keys on top of the set it had just made
 * unreachable, and two packs plus one update would go over the ceiling with nothing in the way. A
 * build carrying a commit keeps one neighbour and so holds two of those ceilings rather than one,
 * and {@link #dropOtherEditions} says which neighbour and why. The Sodium slider writes the number,
 * and a store already over it is swept at once. Past the ceiling the units nothing has asked for
 * lately go first, down to three quarters of it so that the sweep is not paid again at the very
 * next write.
 * <p>
 * <strong>The folder's name is narrower than the key</strong>, and deliberately: the key also
 * carries the loader, its version and the LWJGL build, so a NeoForge or an LWJGL bump makes every
 * blob unreachable without moving the folder, and what those blobs then cost is space until a
 * sweep collects them. Naming the folder after all five would sweep the whole store on a loader
 * bump, which is the same space spent on the same day for no reading. What the folder does carry
 * beyond the two versions is the commit, and only on a development build: without it, two builds
 * declaring one version would share the folder AND the keys, which is every build made between
 * two releases, and a translator changed since yesterday would be served yesterday's modules with
 * nothing saying so. A release build carries no commit, because a player's store is worth keeping
 * across every jar of one version and nothing but a release changes what those jars compile.
 * <p>
 * {@code -Dvitrail.moduleCache=false} turns the whole thing off, and the line is still printed, so
 * one jar answers the question in both directions.
 */
public final class ModuleCache {

	/** First word of any SPIR-V module, and the cheapest proof that a blob is one. */
	private static final int MAGIC = 0x07230203;

	/** Five words is the header alone, so nothing shorter can be a module. */
	private static final int SHORTEST = 20;


	/**
	 * How large a file may be before it is refused unread. The largest module of the corpus is
	 * under a megabyte, so this is two orders of magnitude of room; what it is really for is a
	 * file that grew for a reason nothing here can name, which would otherwise be read whole
	 * into the heap before anything got the chance to refuse it.
	 */
	private static final long MOST_BYTES = 64L * 1024L * 1024L;

	/**
	 * How large the store may grow, in mebibytes, offered on the Sodium page. Half a gigabyte is
	 * what this class shipped as a constant; the slider keeps that as its untouched value.
	 */
	public static final int MIN_CEILING_MIB = ModuleStore.MIN_CEILING_MIB;
	public static final int MAX_CEILING_MIB = ModuleStore.MAX_CEILING_MIB;
	public static final int DEFAULT_CEILING_MIB = ModuleStore.DEFAULT_CEILING_MIB;
	public static final int CEILING_STEP_MIB = ModuleStore.CEILING_STEP_MIB;

	/**
	 * Bumped by hand when the layout of a file changes rather than its content. This is the 26.3
	 * layout, the words behind their length and nothing else, and its name is not the 26.2 one, so
	 * the two games never read each other's blobs: the key carries the game's version as well, and
	 * the folder the edition, so this is the third fence and not the first. What the zero pass emits
	 * for a module can move without this layout moving, so the pass carries a version of its own
	 * that {@link #keyOf} hashes beside this.
	 */
	private static final String FORMAT = "vitrail-module-26.3-1";

	private ModuleCache() {
	}

	/**
	 * How large the store may grow, in mebibytes, which is what the Sodium slider reads. An
	 * absent or unreadable file is {@link #DEFAULT_CEILING_MIB}.
	 */
	public static int ceilingMib() {
		return ModuleStore.ceilingMib();
	}

	/**
	 * Writes the ceiling and keeps the live answer, so the next store sees it. A store already
	 * over the new number is swept at once: no pack reload and no restart.
	 */
	public static void setCeilingMib(int mib) {
		ModuleStore.setCeilingMib(mib);
	}

	/**
	 * What names this unit on disk, or null when there is nowhere to look and nowhere to write.
	 * <p>
	 * Worked out once by the caller and handed to both ends, because the source of a composite runs
	 * to hundreds of kilobytes and hashing it twice to answer one question is work for nothing.
	 * <p>
	 * The debug name is not an argument. It used to be, and a pack reload then missed every unit
	 * whose GLSL had not moved, because the name carries the load number ({@code pack/<load>/...})
	 * and {@code PackChain} increments that number on every new chain. The file layout did not
	 * change, so the format token stays; old blobs under the names-in-the-key hashes sit until
	 * the sweep collects them.
	 *
	 * @param source  the text the compiler was handed, the pipeline's defines already injected
	 * @param stage   vertex, fragment, or the compute recipe token, which decides the whole compile
	 * @param defines the defines handed to shaderc beside the text, as macros
	 * @param ours    whether {@code RawLocals.ours} claims the unit's debug name, which decides the
	 *                options it is compiled with and the passes run over what comes out
	 */
	public static @Nullable String keyOf(String source, String stage, String defines,
			boolean ours) {
		if (ModuleStore.directory() == null || source.contains("#include")) {
			return null;
		}

		MessageDigest digest = ModuleStore.sha256();

		ModuleStore.feed(digest, FORMAT);
		ModuleStore.feed(digest, Vitrail.cacheVersion());

		// The commit, on a development build and only there. The folder already keeps two such
		// builds apart, and this is the same claim made where the class makes every other one: the
		// key IS the input, and on a development build the version alone does not name the
		// translator that produced the text. A release feeds nothing extra, which is what leaves
		// every key a player already holds exactly where it was.
		String build = Vitrail.buildIdentity();
		if (!build.isEmpty()) {
			ModuleStore.feed(digest, build);
		}

		ModuleStore.feed(digest, Vitrail.platform().minecraftVersion());
		ModuleStore.feed(digest, Vitrail.platform().loaderName());
		ModuleStore.feed(digest, Vitrail.platform().loaderVersion());
		ModuleStore.feed(digest, Version.getVersion());
		// The two switches that change the bytes a compile produces without changing its text: each
		// state keeps its own set of blobs, and a reading taken under one never draws another's.
		ModuleStore.feed(digest, RawLocals.cacheWord());
		ModuleStore.feed(digest, LocalZeroes.VERSION);
		ModuleStore.feed(digest, ShaderDebugInfo.cacheWord());
		ModuleStore.feed(digest, PackNames.cacheWord());
		// Whose unit this is: a unit of the game's that shares its text with one of ours is compiled
		// with other options and walked by neither pass, so the same text is two modules.
		ModuleStore.feed(digest, ours ? "ours" : "theirs");
		ModuleStore.feed(digest, stage);
		ModuleStore.feed(digest, defines);
		ModuleStore.feed(digest, source);

		return HexFormat.of().formatHex(digest.digest());
	}

	/**
	 * The words the compiler would have made of this unit, or null when it has to make them.
	 * <p>
	 * A hit costs a file read and one allocation; nothing native runs. What comes back is native
	 * memory the module made around it owns and frees at its {@code close}, holding bytes of its
	 * own so that the binding rewrite a pipeline builder makes to its module rewrites nobody else's.
	 */
	public static @Nullable ByteBuffer lookup(@Nullable String key) {
		Path root = ModuleStore.directory();
		if (key == null || root == null) {
			return null;
		}

		Path file = root.resolve(key + ModuleStore.SUFFIX);
		byte[] raw;
		try {
			if (Files.size(file) > MOST_BYTES) {
				ModuleStore.sayAboutReading("a stored module is larger than any module is");

				return null;
			}

			raw = Files.readAllBytes(file);
		} catch (IOException e) {
			// Absent is the ordinary case and unreadable the rare one, and neither is worth a word:
			// what follows either way is the compile that would have happened anyway.
			return null;
		} catch (OutOfMemoryError e) {
			// The size was asked for above, so this is a heap that was already at its edge rather
			// than a file that lied about itself. It is still a miss and never a dead load.
			ModuleStore.sayAboutReading("there was no room to read a stored module");

			return null;
		}

		int length = raw.length - ModuleStore.DIGEST_BYTES;
		if (length <= 0 || !ModuleStore.answersForItself(raw, length)) {
			ModuleStore.sayAboutReading("a stored module did not answer for its own bytes");

			return null;
		}

		ByteBuffer spirv = rebuild(raw, length);
		if (spirv == null) {
			return null;
		}

		ModuleStore.touch(file);
		ModuleStore.SERVED.incrementAndGet();
		ModuleStore.SERVED_SINCE_LAUNCH.incrementAndGet();
		ModuleStore.lastUnitNanos = System.nanoTime();

		return spirv;
	}

	/**
	 * The words a stored file holds, or null when they are not SPIR-V.
	 * <p>
	 * The buffer is allocated where the game's own is, in native memory, because what frees it is
	 * the module's own {@code close} and that is a {@code memFree}. It is freed here, and only here,
	 * when the read gives up part way through: nothing else has been handed it yet.
	 */
	private static @Nullable ByteBuffer rebuild(byte[] raw, int length) {
		ByteBuffer spirv = null;
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw, 0, length))) {
			int size = in.readInt();
			if (size < SHORTEST || size % 4 != 0 || size > length) {
				throw new IOException("a stored module claims " + size + " bytes of SPIR-V");
			}

			byte[] words = new byte[size];
			in.readFully(words);
			if (ByteBuffer.wrap(words).order(ByteOrder.nativeOrder()).getInt(0) != MAGIC) {
				throw new IOException("a stored module does not open on the SPIR-V magic word");
			}

			spirv = MemoryUtil.memAlloc(size);
			spirv.put(words);
			spirv.flip();

			return spirv;
		} catch (IOException | RuntimeException | OutOfMemoryError e) {
			// The Error is in the list on purpose. Everything else in here is a miss, and a length
			// this could not allocate for would otherwise be the one shape of damaged file that
			// takes the pack load down instead.
			if (spirv != null) {
				MemoryUtil.memFree(spirv);
			}

			ModuleStore.sayAboutReading("a stored module could not be read back (" + e + ")");

			return null;
		}
	}

	/**
	 * Counts a unit the compiler is about to build, said BEFORE it builds it, and names it for the
	 * line at the end of the load.
	 *
	 * @param filename the debug name the compile was given, which says whose unit it is
	 */
	public static void building(String filename) {
		ModuleStore.building(filename);
	}

	/**
	 * Keeps the words the compiler has just made, under the key of the text they were made from.
	 * <p>
	 * Called with the words of the module the caller is about to receive and before anything has
	 * reflected it, which is the one instant at which it is both finished and untouched.
	 */
	public static void store(@Nullable String key, ByteBuffer spirv) {
		Path root = ModuleStore.directory();
		if (key == null || root == null) {
			return;
		}

		byte[] raw;
		try {
			raw = describe(spirv);
		} catch (IOException | RuntimeException e) {
			ModuleStore.sayAboutStoring("a module could not be written down (" + e + ")");

			return;
		}

		Path file = root.resolve(key + ModuleStore.SUFFIX);
		Path part = root.resolve(key + "-"
				+ Long.toHexString(Thread.currentThread().threadId()) + ModuleStore.PART_SUFFIX);

		try {
			// The module, then the digest that answers for it. Behind rather than in front, so
			// nothing inside has to move to make room for it.
			Files.write(part, raw);
			Files.write(part, ModuleStore.sha256(raw), StandardOpenOption.APPEND);
			ModuleStore.move(part, file);
			ModuleStore.BYTES.addAndGet(raw.length + (long) ModuleStore.DIGEST_BYTES);
		} catch (IOException e) {
			ModuleStore.sayAboutWriting("a module could not be stored", e);

			try {
				Files.deleteIfExists(part);
			} catch (IOException ignored) {
				// The next sweep collects it: every scan deletes the neighbours it comes across.
			}

			return;
		}

		if (ModuleStore.BYTES.get() > ModuleStore.ceilingBytes()) {
			ModuleStore.sweep(root);
		}
	}

	/** The words, behind their length, in the order {@link #rebuild} reads them back. */
	private static byte[] describe(ByteBuffer spirv) throws IOException {
		// A view of its own, so the caller's position and limit are left where they were.
		ByteBuffer view = spirv.duplicate();
		byte[] words = new byte[view.remaining()];
		view.get(words);
		ByteBuffer raw = ByteBuffer.allocate(Integer.BYTES + words.length);
		raw.putInt(words.length).put(words);

		return raw.array();
	}

	/**
	 * One line for the load that has just finished, in both directions and whatever happened; called
	 * at every client tick and silent until the compiler has been quiet long enough for a load to be
	 * over. {@link ModuleStore#say} says what it prints.
	 */
	public static void say() {
		ModuleStore.say("compiled");
	}

	/**
	 * Deletes what another edition left, but for the one neighbour a build carrying a commit spares.
	 * {@link ModuleStore#dropOtherEditions} says which and why.
	 */
	static String dropOtherEditions(Path root, Path mine, String family) {
		return ModuleStore.dropOtherEditions(root, mine, family);
	}

}
