package dev.vitrail.cache;

import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import dev.vitrail.glsl.LocalZeroes;
import dev.vitrail.mixin.game.IntermediaryShaderModuleAccessor;
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
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Keeps the whole of what the game's compiler makes of each shader unit, on disk, so a second load
 * of the same pack pays for neither the compile nor the reading of what came out of it.
 * <p>
 * Loading a pack costs seconds, and a clock around the two halves of that number said where they
 * go: shaderc turning GLSL into SPIR-V, and the SPIRV-Cross reflection walking the result to find
 * out what it binds. Both are pure functions of the same input, so both are cached by that input,
 * and a served unit costs a file read and no native work at all. Caching only the first half was
 * built and measured first, and it left the second half standing, which is why the reflection is
 * stored beside the bytes it read rather than replayed over them.
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
 * GLSL. F3+T does not bump the load and already hit; a pack reload now hits too. The live name is
 * still handed to {@link #lookup} so the rebuilt module carries the identifier this chain's
 * pipelines will ask for. Two texts colliding under one blob would need a SHA-256 collision of
 * the source, which is not the trap the load number exists to prevent.
 * <p>
 * <strong>One bit of the name is keyed all the same</strong>, because it changes what is stored:
 * whether the unit is this engine's, which is what {@code RawLocals.ours} reads off the name. Only
 * such a unit is given its zeroes and has its names stripped, and only such a unit has the
 * samplers its entry point never reaches dropped from its table, so one text compiled under a name
 * of ours and under the game's comes out as two different modules. The bit goes in and the name,
 * load number and all, stays out.
 * <p>
 * <strong>What is stored is the module as its maker handed it over</strong>, at the one instant at
 * which it is finished and nothing has yet bent it to a pipeline: the bytes, the uniform buffers
 * and samplers the reflection found, the inputs and outputs it numbered, and the storage images and
 * buffers this engine appends behind them because the game never asks for those. {@code rebind}
 * comes later and rewrites the bytes for one pipeline's bindings, so a module is stored before any
 * caller has had it, and every hit is an allocation of its own.
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

	/** Off by property rather than by rebuild, so a before and an after come out of one jar. */
	private static final boolean ENABLED = Boolean.parseBoolean(
			System.getProperty("vitrail.moduleCache", "true"));

	/** First word of any SPIR-V module, and the cheapest proof that a blob is one. */
	private static final int MAGIC = 0x07230203;

	/** Five words is the header alone, so nothing shorter can be a module. */
	private static final int SHORTEST = 20;

	/**
	 * How many entries of one kind a stored module may claim before the file is read as damaged
	 * rather than as a module. Far above what any pack reaches, and low enough that a wild count
	 * cannot ask for an allocation that the digest was going to refuse a moment later anyway.
	 */
	private static final int MOST_ENTRIES = 65_536;

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
	public static final int MIN_CEILING_MIB = 128;
	public static final int MAX_CEILING_MIB = 2048;
	public static final int DEFAULT_CEILING_MIB = 512;
	public static final int CEILING_STEP_MIB = 128;

	private static final String CEILING_FILE = "module-cache-ceiling.txt";

	private static volatile int ceilingMib = DEFAULT_CEILING_MIB;
	private static volatile boolean ceilingLoaded;

	/** How long a sweep that could not finish stays out of the way of the next write. */
	private static final long SWEEP_BACKOFF_NANOS = 60_000_000_000L;

	/**
	 * Bumped by hand when the layout of a file changes rather than its content, or when what the
	 * tables in it hold does. Every blob written under an older one is then unreachable, and the
	 * sweep is what eventually collects it. Two is where the uniform buffer table first carries the
	 * storage blocks: every blob before it lists none, a hit skips the reflection that would add
	 * them, and a pipeline built off such a blob leaves every storage block on the binding its pack
	 * wrote. Three is where the SPIR-V itself first carries the zeroes of {@link RawLocals}: a
	 * blob before it holds the compiler's bare variables, and a hit on one would draw the black
	 * faces the pass exists to take away, with nothing in the log to say why. What the pass emits
	 * for a module can move without this layout moving, so the pass carries a version of its own
	 * that {@link #keyOf} hashes beside this.
	 */
	private static final String FORMAT = "vitrail-module-3";

	private static final String FOLDER = "modules";

	/** How many of a load's rebuilt units are named on the line that counts them. */
	private static final int NAMED_MISSES = 12;

	/** The debug names of the units this load built, the first {@link #NAMED_MISSES} of them. */
	private static final List<String> BUILT_NAMES = new ArrayList<>();
	private static final String SUFFIX = ".mod";
	private static final String PART_SUFFIX = ".part";

	/** How long the compiler has to stay quiet before a load is taken to be over. */
	private static final long QUIET_NANOS = 2_000_000_000L;

	private static final AtomicLong SERVED = new AtomicLong();
	private static final AtomicLong COMPILED = new AtomicLong();
	private static final AtomicLong SERVED_SINCE_LAUNCH = new AtomicLong();
	private static final AtomicLong COMPILED_SINCE_LAUNCH = new AtomicLong();
	private static final AtomicLong BYTES = new AtomicLong();

	/** Held for the first look at the directory and for every sweep, which are the two scans. */
	private static final Object LOCK = new Object();

	private static volatile @Nullable Path directory;
	private static volatile boolean unavailable;
	private static volatile long lastUnitNanos;
	private static volatile long nextSweepNanos;
	private static volatile boolean saidAboutReading;
	private static volatile boolean saidAboutStoring;
	private static volatile boolean saidAboutWriting;

	private ModuleCache() {
	}

	/**
	 * How large the store may grow, in mebibytes, which is what the Sodium slider reads. An
	 * absent or unreadable file is {@link #DEFAULT_CEILING_MIB}.
	 */
	public static int ceilingMib() {
		if (!ceilingLoaded) {
			loadCeiling();
		}

		return ceilingMib;
	}

	/**
	 * Writes the ceiling and keeps the live answer, so the next store sees it. A store already
	 * over the new number is swept at once: no pack reload and no restart.
	 */
	public static void setCeilingMib(int mib) {
		int asked = snapCeiling(mib);
		writeCeiling(asked);
		ceilingMib = asked;
		ceilingLoaded = true;
		nextSweepNanos = 0L;
		Path root = directory;
		if (root != null && BYTES.get() > bytesOf(asked)) {
			sweep(root);
		}
	}

	private static void loadCeiling() {
		synchronized (LOCK) {
			if (ceilingLoaded) {
				return;
			}

			ceilingMib = readCeilingFile();
			ceilingLoaded = true;
		}
	}

	private static int readCeilingFile() {
		Path file;
		try {
			file = Vitrail.platform().gameDirectory().resolve(Vitrail.MOD_ID).resolve(CEILING_FILE);
		} catch (RuntimeException e) {
			return DEFAULT_CEILING_MIB;
		}

		try {
			if (!Files.isRegularFile(file)) {
				return DEFAULT_CEILING_MIB;
			}

			String text = Files.readString(file, StandardCharsets.UTF_8).trim();

			return snapCeiling(Integer.parseInt(text));
		} catch (NumberFormatException e) {
			Vitrail.logger().warn("vitrail/{} is not a size in mebibytes, so the default {} is used",
					CEILING_FILE, DEFAULT_CEILING_MIB);

			return DEFAULT_CEILING_MIB;
		} catch (IOException | RuntimeException e) {
			return DEFAULT_CEILING_MIB;
		}
	}

	private static void writeCeiling(int mib) {
		try {
			Path file = Vitrail.platform().gameDirectory().resolve(Vitrail.MOD_ID)
					.resolve(CEILING_FILE);
			Files.createDirectories(file.getParent());
			Files.writeString(file, mib + "\n", StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException e) {
			Vitrail.logger().error("Vitrail could not write the module cache ceiling to vitrail/{}",
					CEILING_FILE, e);
		}
	}

	private static int snapCeiling(int asked) {
		int clamped = Math.clamp(asked, MIN_CEILING_MIB, MAX_CEILING_MIB);
		int steps = (clamped - MIN_CEILING_MIB + CEILING_STEP_MIB / 2) / CEILING_STEP_MIB;

		return Math.clamp(MIN_CEILING_MIB + steps * CEILING_STEP_MIB, MIN_CEILING_MIB,
				MAX_CEILING_MIB);
	}

	private static long bytesOf(int mib) {
		return (long) mib * 1024L * 1024L;
	}

	private static long ceilingBytes() {
		return bytesOf(ceilingMib());
	}

	private static long sweepTarget() {
		return ceilingBytes() / 4L * 3L;
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
	 * @param source the text the compiler was handed, the pipeline's defines already injected
	 * @param stage  vertex, fragment, or the compute recipe token, which decides the whole compile
	 * @param ours   whether {@code RawLocals.ours} claims the unit's debug name, which decides the
	 *               passes run over what the compiler made and so what is stored
	 */
	public static @Nullable String keyOf(String source, String stage, boolean ours) {
		if (directory() == null || !ModuleShape.available()) {
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
		ModuleStore.feed(digest, SamplerReach.cacheWord());
		// Whose unit this is: a unit of the game's that shares its text with one of ours is walked
		// by none of those passes, so the same text is two modules.
		ModuleStore.feed(digest, ours ? "ours" : "theirs");
		ModuleStore.feed(digest, stage);
		ModuleStore.feed(digest, source);

		return HexFormat.of().formatHex(digest.digest());
	}

	/**
	 * The key of a unit of this engine's own compute road, which is this engine's by construction:
	 * {@code PackCompute} labels every unit it builds {@code pack/<load>/...}, and the stage token
	 * that road keys under is its own, so no unit of the game's can share it.
	 *
	 * @param source the text shaderc is to read
	 * @param stage  the stage token the compute road keys under
	 */
	public static @Nullable String keyOf(String source, String stage) {
		return keyOf(source, stage, true);
	}

	/**
	 * The module the compiler would have built for this unit, or null when it has to build one.
	 * <p>
	 * A hit costs a file read, one allocation and a handful of small records; nothing native runs.
	 * What comes back is the caller's exactly as a compiled module is, freed by the same
	 * {@code close}, and holding bytes of its own so that the {@code rebind} that follows rewrites
	 * nobody else's.
	 */
	public static @Nullable IntermediaryShaderModule lookup(@Nullable String key, String filename) {
		Path root = directory();
		if (key == null || root == null) {
			return null;
		}

		Path file = root.resolve(key + SUFFIX);
		byte[] raw;
		try {
			if (Files.size(file) > MOST_BYTES) {
				sayAboutReading("a stored module is larger than any module is");

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
			sayAboutReading("there was no room to read a stored module");

			return null;
		}

		int length = raw.length - ModuleStore.DIGEST_BYTES;
		if (length <= 0 || !ModuleStore.answersForItself(raw, length)) {
			sayAboutReading("a stored module did not answer for its own bytes");

			return null;
		}

		IntermediaryShaderModule module = rebuild(filename, raw, length);
		if (module == null) {
			return null;
		}

		ModuleStore.touch(file);
		SERVED.incrementAndGet();
		SERVED_SINCE_LAUNCH.incrementAndGet();
		lastUnitNanos = System.nanoTime();

		return module;
	}

	/**
	 * Builds the module a stored file describes, or null when this build cannot.
	 * <p>
	 * The buffer is allocated where the game's own is, in native memory, because what frees it is
	 * the game's own {@code close} and that is a {@code memFree}. The game reaches for
	 * {@code memCalloc} and this reaches for {@code memAlloc}, which is the same allocator and one
	 * less pass over bytes that are all written anyway. It is freed here, and only here, when the
	 * build gives up part way through: nothing else has been handed it yet.
	 */
	private static @Nullable IntermediaryShaderModule rebuild(String filename, byte[] raw,
			int length) {
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

			List<Object> uniformBuffers = new ArrayList<>();
			for (int left = count(in); left > 0; left--) {
				uniformBuffers.add(ModuleShape.uniformBuffer(in.readUTF(), in.readInt()));
			}

			List<Object> samplers = new ArrayList<>();
			for (int left = count(in); left > 0; left--) {
				samplers.add(ModuleShape.sampler(in.readUTF(), in.readInt(), in.readInt()));
			}

			List<Object> outputs = new ArrayList<>();
			for (int left = count(in); left > 0; left--) {
				outputs.add(ModuleShape.variable(in.readUTF(), in.readInt()));
			}

			List<Object> inputs = new ArrayList<>();
			for (int left = count(in); left > 0; left--) {
				inputs.add(ModuleShape.variable(in.readUTF(), in.readInt()));
			}

			spirv = MemoryUtil.memAlloc(size);
			spirv.put(words);
			spirv.flip();

			return ModuleShape.module(filename, spirv, uniformBuffers, samplers, outputs, inputs);
		} catch (IOException | ReflectiveOperationException | RuntimeException
				| OutOfMemoryError e) {
			// The Error is in the list on purpose. Everything else in here is a miss, and a length
			// this could not allocate for would otherwise be the one shape of damaged file that
			// takes the pack load down instead.
			if (spirv != null) {
				MemoryUtil.memFree(spirv);
			}

			sayAboutReading("a stored module could not be rebuilt (" + e + ")");

			return null;
		}
	}

	private static int count(DataInputStream in) throws IOException {
		int size = in.readInt();
		if (size < 0 || size > MOST_ENTRIES) {
			throw new IOException("a stored module claims " + size + " entries of one kind");
		}

		return size;
	}

	/**
	 * Counts a unit the compiler is about to build, said BEFORE it builds it, and keeps its name
	 * while there is room for one more: the line at the end of the load names the first few, so
	 * that a warm load rebuilding sixty modules says which sixty rather than how many.
	 * <p>
	 * Before and not after, because a unit a pack broke throws out of the compile and would
	 * otherwise be counted by neither side, which is exactly the silence the line at the end of a
	 * load exists to remove.
	 *
	 * @param filename the debug name the compile was given, which says whose unit it is
	 */
	public static void building(String filename) {
		COMPILED.incrementAndGet();
		COMPILED_SINCE_LAUNCH.incrementAndGet();
		lastUnitNanos = System.nanoTime();
		synchronized (BUILT_NAMES) {
			if (BUILT_NAMES.size() < NAMED_MISSES) {
				BUILT_NAMES.add(filename);
			}
		}
	}

	/**
	 * Keeps the module the compiler has just built, under the key of the text it was built from.
	 * <p>
	 * Called with the module the caller is about to receive and before anything has been done to
	 * it, which is the one instant at which it is both finished and untouched.
	 */
	public static void store(@Nullable String key, IntermediaryShaderModule module) {
		Path root = directory();
		if (key == null || root == null || module.spirv() == null) {
			return;
		}

		byte[] raw;
		try {
			raw = describe(module);
		} catch (IOException | ReflectiveOperationException | RuntimeException e) {
			sayAboutStoring("a module could not be written down (" + e + ")");

			return;
		}

		Path file = root.resolve(key + SUFFIX);
		Path part = root.resolve(key + "-"
				+ Long.toHexString(Thread.currentThread().threadId()) + PART_SUFFIX);

		try {
			// The module, then the digest that answers for it. Behind rather than in front, so
			// nothing inside has to move to make room for it.
			Files.write(part, raw);
			Files.write(part, ModuleStore.sha256(raw), StandardOpenOption.APPEND);
			ModuleStore.move(part, file);
			BYTES.addAndGet(raw.length + (long) ModuleStore.DIGEST_BYTES);
		} catch (IOException e) {
			sayAboutWriting("a module could not be stored", e);

			try {
				Files.deleteIfExists(part);
			} catch (IOException ignored) {
				// The next sweep collects it: every scan deletes the neighbours it comes across.
			}

			return;
		}

		if (BYTES.get() > ceilingBytes()) {
			sweep(root);
		}
	}

	/** Everything a module is, in the order {@link #rebuild} reads it back. */
	private static byte[] describe(IntermediaryShaderModule module)
			throws IOException, ReflectiveOperationException {
		IntermediaryShaderModuleAccessor access = (IntermediaryShaderModuleAccessor) (Object) module;
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		try (DataOutputStream out = new DataOutputStream(bytes)) {
			// A view of its own, so the caller's position and limit are left where they were.
			ByteBuffer view = module.spirv().duplicate();
			byte[] words = new byte[view.remaining()];
			view.get(words);
			out.writeInt(words.length);
			out.write(words);

			List<?> uniformBuffers = access.vitrail$uniformBuffers();
			out.writeInt(uniformBuffers.size());
			for (Object buffer : uniformBuffers) {
				out.writeUTF(ModuleShape.uniformBufferName(buffer));
				out.writeInt(ModuleShape.uniformBufferBinding(buffer));
			}

			List<?> samplers = access.vitrail$samplers();
			out.writeInt(samplers.size());
			for (Object sampler : samplers) {
				out.writeUTF(ModuleShape.samplerName(sampler));
				out.writeInt(ModuleShape.samplerBinding(sampler));
				out.writeInt(ModuleShape.samplerDimensions(sampler));
			}

			writeVariables(out, access.vitrail$outputs());
			writeVariables(out, access.vitrail$inputs());
		}

		return bytes.toByteArray();
	}

	private static void writeVariables(DataOutputStream out, List<?> variables)
			throws IOException, ReflectiveOperationException {
		out.writeInt(variables.size());
		for (Object variable : variables) {
			out.writeUTF(ModuleShape.variableName(variable));
			out.writeInt(ModuleShape.variableLocation(variable));
		}
	}

	/**
	 * One line for the load that has just finished, in both directions and whatever happened.
	 * <p>
	 * Called at every client tick and silent at almost all of them: it speaks once the compiler has
	 * been quiet long enough for a load to be over, which is what makes the line a load's total
	 * rather than a running commentary. A load whose leftovers straggle in after a longer pause than
	 * that comes out as two lines, and the totals since launch are on the line so that the two can
	 * still be added up without ambiguity.
	 * <p>
	 * <strong>It says the misses as loudly as the hits.</strong> A cache that only speaks when it
	 * helps makes every later reading of a log ambiguous, and a silence that could mean either
	 * nothing happened or everything did is worse than no line at all.
	 */
	public static void say() {
		if (SERVED.get() == 0L && COMPILED.get() == 0L) {
			return;
		}

		if (System.nanoTime() - lastUnitNanos < QUIET_NANOS) {
			return;
		}

		long hits = SERVED.getAndSet(0L);
		long misses = COMPILED.getAndSet(0L);
		List<String> named;
		synchronized (BUILT_NAMES) {
			named = List.copyOf(BUILT_NAMES);
			BUILT_NAMES.clear();
		}

		if (!ENABLED) {
			Vitrail.logger().info("Module cache off, so all {} units of this load were compiled and "
					+ "reflected ({} since this launch)", misses, COMPILED_SINCE_LAUNCH.get());
			RawLocals.say(misses);
			PackNames.say(misses);
			SamplerReach.say(misses);

			return;
		}

		Path root = directory;
		Vitrail.logger().info("Module cache: {} units served, {} built by the compiler, {} and {} "
						+ "since this launch, {} MB in {}",
				hits, misses, SERVED_SINCE_LAUNCH.get(), COMPILED_SINCE_LAUNCH.get(),
				ModuleStore.megabytes(BYTES.get()), root == null ? "nowhere" : root);
		// Said only where a load rebuilt something a store already held units of: a cold first
		// load builds everything for a reason nobody needs told, a warm one rebuilding a handful
		// has a reason worth finding, and the names are where the search starts.
		if (misses > 0L && hits > 0L) {
			Vitrail.logger().info("The {} built this load {}: {}", misses,
					misses > named.size() ? "begin with" : "are", String.join(", ", named));
		}

		// At the same quiet moment and about the same compiles: what the passes did to the modules
		// this line counts as built.
		RawLocals.say(misses);
		PackNames.say(misses);
		SamplerReach.say(misses);
	}

	/** The directory, made and measured at the first unit of the run, or null when there is none. */
	private static @Nullable Path directory() {
		if (!ENABLED || unavailable) {
			return null;
		}

		Path known = directory;
		if (known != null) {
			return known;
		}

		synchronized (LOCK) {
			if (directory == null && !unavailable) {
				open();
			}

			return directory;
		}
	}

	/**
	 * Makes the edition's directory, clears out what other editions left but for the one neighbour
	 * {@link #dropOtherEditions} spares, and measures what is left.
	 * <p>
	 * The only moment at which a leftover neighbour can be swept up: nothing else has been handed
	 * the directory yet, because {@link #directory} is set on the last line, so a {@code .part} seen
	 * here is a dead one from a run that was killed and never a live write of a worker's.
	 * <p>
	 * <strong>Only this edition's own directory decides whether there is a cache this run.</strong>
	 * What another build left is cleared on the way in and is nothing this build reads, so a file in
	 * it that will not go, held by a scanner or an indexer or made read-only by hand, costs the disk
	 * it sits on and is said once. It used to throw out of here, and one stale file in a folder no
	 * build would ever read again then turned the store off at every launch for as long as the file
	 * stayed.
	 */
	private static void open() {
		try {
			Path root = Vitrail.platform().gameDirectory().resolve(Vitrail.MOD_ID).resolve(FOLDER);
			Path mine = root.resolve(Vitrail.cacheEdition());
			Files.createDirectories(mine);
			ModuleStore.touch(mine);
			String left = dropOtherEditions(root, mine, Vitrail.cacheEditionFamily());
			if (!left.isEmpty()) {
				Vitrail.logger().warn("The module cache could not take away all that another build "
						+ "left in {}: {}. This build keeps its own store all the same, and the next "
						+ "launch tries again", root, left);
			}

			BYTES.set(total(scan(mine, true)));
			directory = mine;
		} catch (IOException | RuntimeException e) {
			unavailable = true;
			Vitrail.logger().warn("No module cache this run, so every shader is compiled: {}",
					e.toString());
		}
	}

	/**
	 * Deletes what another edition left, but for the one neighbour a build carrying a commit spares.
	 * {@link ModuleStore#dropOtherEditions} says which and why.
	 */
	static String dropOtherEditions(Path root, Path mine, String family) {
		return ModuleStore.dropOtherEditions(root, mine, family);
	}

	/**
	 * Every unit on disk, oldest stamp first.
	 * <p>
	 * <strong>A neighbour is only ever deleted when {@code prunePartials} says so, which is at
	 * {@link #open} and nowhere else.</strong> A sweep runs while other workers are in the middle of
	 * their own writes, and deleting what they are holding open takes their unit down on one system
	 * and aborts the whole sweep with a refusal on the other. What a sweep does with a neighbour is
	 * ignore it: it is not reachable, it is about to become a unit, and it is nobody's to count.
	 * <p>
	 * A file that goes missing between the listing and the question is skipped rather than fatal,
	 * for the same reason: the listing is a snapshot and the directory is not frozen behind it.
	 */
	private static List<Unit> scan(Path root, boolean prunePartials) throws IOException {
		List<Unit> units = new ArrayList<>();

		try (Stream<Path> entries = Files.list(root)) {
			for (Path entry : entries.toList()) {
				if (entry.getFileName().toString().endsWith(PART_SUFFIX)) {
					if (prunePartials) {
						try {
							Files.deleteIfExists(entry);
						} catch (IOException ignored) {
							// Dead, and held by something outside this process. It is not reachable
							// and not counted, and the next open tries again: refused out of here it
							// would have turned the whole store off over one file nothing reads.
						}
					}
				} else {
					try {
						units.add(new Unit(entry, Files.getLastModifiedTime(entry).toMillis(),
								Files.size(entry)));
					} catch (IOException ignored) {
						// Gone, or momentarily unreadable. One unit uncounted, and the next sweep
						// counts it.
					}
				}
			}
		}

		units.sort(Comparator.comparingLong(Unit::stamp));

		return units;
	}

	private static long total(List<Unit> units) {
		long sum = 0L;
		for (Unit unit : units) {
			sum += unit.size();
		}

		return sum;
	}

	/**
	 * Brings the directory back under the ceiling, oldest stamp first.
	 * <p>
	 * It rescans rather than trusting the running count, which is also what puts that count right
	 * again: a unit written twice under one key is added twice and subtracted once, so the two only
	 * come back together on the other side of a sweep.
	 * <p>
	 * <strong>What it leaves behind on the way out matters more than what it frees.</strong> The
	 * count is put down in a {@code finally} and a sweep that ends still over the ceiling stands
	 * back for a while, because the caller's test is that same count: a single refusal anywhere in
	 * here, without both of those, leaves the count high and turns every later write into a full
	 * walk of the directory under this lock, for the rest of the session and with nothing said.
	 * <p>
	 * It is put down by the difference the sweep found and not as the figure, because a store adds
	 * to the count outside this lock: a unit that lands after the listing and is added before the
	 * count is set would otherwise be wiped from it, and a count that runs short is one the ceiling
	 * is late to catch. What the difference can do instead is count a unit the listing saw twice,
	 * which is the direction the count already errs in, and the next sweep's rescan puts it right.
	 */
	private static void sweep(Path root) {
		synchronized (LOCK) {
			if (System.nanoTime() < nextSweepNanos) {
				return;
			}

			long counted = BYTES.get();
			long total = counted;
			try {
				List<Unit> units = scan(root, false);
				total = total(units);
				long before = total;

				for (Unit unit : units) {
					if (total <= sweepTarget()) {
						break;
					}

					Files.deleteIfExists(unit.path());
					total -= unit.size();
				}

				if (total < before) {
					Vitrail.logger().info("The module cache went over its ceiling, so the units "
							+ "nothing has asked for lately were dropped, {} MB left",
							ModuleStore.megabytes(total));
				}
			} catch (IOException e) {
				sayAboutWriting("the cache could not be swept", e);
			} finally {
				BYTES.addAndGet(total - counted);

				if (total > ceilingBytes()) {
					nextSweepNanos = System.nanoTime() + SWEEP_BACKOFF_NANOS;
				}
			}
		}
	}

	private static void sayAboutReading(String what) {
		if (!saidAboutReading) {
			saidAboutReading = true;
			Vitrail.logger().warn("In the module cache, {}, so it was compiled instead. Said once a "
					+ "run: nothing about it stops a pack loading", what);
		}
	}

	/**
	 * Said when a module was built and could not be kept, which is not the same failure as a stored
	 * one that could not be read: the load it happened in paid nothing extra and the picture is the
	 * picture a compile makes. Only the load after it pays, by compiling again.
	 */
	private static void sayAboutStoring(String what) {
		if (!saidAboutStoring) {
			saidAboutStoring = true;
			Vitrail.logger().warn("In the module cache, {}, so it was used and not kept. Said once "
					+ "a run: the next load compiles it again and nothing else changes", what);
		}
	}

	private static void sayAboutWriting(String what, IOException cause) {
		if (!saidAboutWriting) {
			saidAboutWriting = true;
			Vitrail.logger().warn("In the module cache, {}: {}. Said once a run: the next load pays "
					+ "for the compile again and nothing else changes", what, cause.toString());
		}
	}

	/** One file of the cache, with what the sweep needs to order it and to subtract it. */
	private record Unit(Path path, long stamp, long size) {
	}

	/**
	 * The three record types a module is made of, reached by reflection because they are package
	 * private and this package is not theirs.
	 * <p>
	 * Naming them is what a mixin accessor cannot do either, which is why the lists it hands back
	 * are raw: an interface of ours declaring {@code List<SpvSampler>} would not compile, and a
	 * class of ours in their package would not boot. So an entry is read and written one component
	 * at a time, and the module's own canonical constructor is called the same way, its parameter
	 * types being those three.
	 * <p>
	 * <strong>A build that cannot find them serves nothing and stores nothing</strong>, rather than
	 * failing at the first pack: a game update that moves one of these leaves the cache silent and
	 * the compiler doing exactly what it did before.
	 */
	private static final class ModuleShape {

		private static final @Nullable Constructor<?> UNIFORM_BUFFER;
		private static final @Nullable Constructor<?> SAMPLER;
		private static final @Nullable Constructor<?> VARIABLE;
		private static final @Nullable Constructor<?> MODULE;
		private static final @Nullable Method UNIFORM_BUFFER_NAME;
		private static final @Nullable Method UNIFORM_BUFFER_BINDING;
		private static final @Nullable Method SAMPLER_NAME;
		private static final @Nullable Method SAMPLER_BINDING;
		private static final @Nullable Method SAMPLER_DIMENSIONS;
		private static final @Nullable Method VARIABLE_NAME;
		private static final @Nullable Method VARIABLE_LOCATION;

		static {
			Shape shape;
			try {
				shape = find();
			} catch (ReflectiveOperationException | RuntimeException e) {
				Vitrail.logger().warn("No module cache this run, because a shader module is not the "
						+ "shape this build expects: {}", e.toString());
				shape = new Shape(null, null, null, null, null, null, null, null, null, null, null);
			}

			UNIFORM_BUFFER = shape.uniformBuffer();
			SAMPLER = shape.sampler();
			VARIABLE = shape.variable();
			MODULE = shape.module();
			UNIFORM_BUFFER_NAME = shape.uniformBufferName();
			UNIFORM_BUFFER_BINDING = shape.uniformBufferBinding();
			SAMPLER_NAME = shape.samplerName();
			SAMPLER_BINDING = shape.samplerBinding();
			SAMPLER_DIMENSIONS = shape.samplerDimensions();
			VARIABLE_NAME = shape.variableName();
			VARIABLE_LOCATION = shape.variableLocation();
		}

		private ModuleShape() {
		}

		/** Everything reached in one go, so that a half found shape can never be a usable one. */
		private record Shape(@Nullable Constructor<?> uniformBuffer, @Nullable Constructor<?> sampler,
				@Nullable Constructor<?> variable, @Nullable Constructor<?> module,
				@Nullable Method uniformBufferName, @Nullable Method uniformBufferBinding,
				@Nullable Method samplerName, @Nullable Method samplerBinding,
				@Nullable Method samplerDimensions, @Nullable Method variableName,
				@Nullable Method variableLocation) {
		}

		private static Shape find() throws ReflectiveOperationException {
			Class<?> buffers = Class.forName("com.mojang.blaze3d.vulkan.glsl.SpvUniformBuffer");
			Class<?> samplers = Class.forName("com.mojang.blaze3d.vulkan.glsl.SpvSampler");
			Class<?> variables = Class.forName("com.mojang.blaze3d.vulkan.glsl.SpvVariable");

			return new Shape(
					make(buffers, String.class, int.class),
					make(samplers, String.class, int.class, int.class),
					make(variables, String.class, int.class),
					make(IntermediaryShaderModule.class, String.class, ByteBuffer.class, List.class,
							List.class, List.class, List.class),
					open(buffers, "name"), open(buffers, "bindingOffset"),
					open(samplers, "name"), open(samplers, "bindingOffset"),
					open(samplers, "dimensions"),
					open(variables, "name"), open(variables, "locationOffset"));
		}

		private static Constructor<?> make(Class<?> owner, Class<?>... parameters)
				throws ReflectiveOperationException {
			Constructor<?> constructor = owner.getDeclaredConstructor(parameters);
			constructor.setAccessible(true);

			return constructor;
		}

		private static Method open(Class<?> owner, String component)
				throws ReflectiveOperationException {
			Method method = owner.getDeclaredMethod(component);
			method.setAccessible(true);

			return method;
		}

		static boolean available() {
			return MODULE != null;
		}

		static Object uniformBuffer(String name, int bindingOffset)
				throws ReflectiveOperationException {
			return require(UNIFORM_BUFFER).newInstance(name, bindingOffset);
		}

		static Object sampler(String name, int bindingOffset, int dimensions)
				throws ReflectiveOperationException {
			return require(SAMPLER).newInstance(name, bindingOffset, dimensions);
		}

		static Object variable(String name, int locationOffset) throws ReflectiveOperationException {
			return require(VARIABLE).newInstance(name, locationOffset);
		}

		static IntermediaryShaderModule module(String name, ByteBuffer spirv,
				List<?> uniformBuffers, List<?> samplers, List<?> outputs, List<?> inputs)
				throws ReflectiveOperationException {
			return (IntermediaryShaderModule) require(MODULE)
					.newInstance(name, spirv, uniformBuffers, samplers, outputs, inputs);
		}

		static String uniformBufferName(Object entry) throws ReflectiveOperationException {
			return (String) require(UNIFORM_BUFFER_NAME).invoke(entry);
		}

		static int uniformBufferBinding(Object entry) throws ReflectiveOperationException {
			return (Integer) require(UNIFORM_BUFFER_BINDING).invoke(entry);
		}

		static String samplerName(Object entry) throws ReflectiveOperationException {
			return (String) require(SAMPLER_NAME).invoke(entry);
		}

		static int samplerBinding(Object entry) throws ReflectiveOperationException {
			return (Integer) require(SAMPLER_BINDING).invoke(entry);
		}

		static int samplerDimensions(Object entry) throws ReflectiveOperationException {
			return (Integer) require(SAMPLER_DIMENSIONS).invoke(entry);
		}

		static String variableName(Object entry) throws ReflectiveOperationException {
			return (String) require(VARIABLE_NAME).invoke(entry);
		}

		static int variableLocation(Object entry) throws ReflectiveOperationException {
			return (Integer) require(VARIABLE_LOCATION).invoke(entry);
		}

		private static Constructor<?> require(@Nullable Constructor<?> constructor)
				throws ReflectiveOperationException {
			if (constructor == null) {
				throw new NoSuchMethodException("a shader module record is not where it was");
			}

			return constructor;
		}

		private static Method require(@Nullable Method method) throws ReflectiveOperationException {
			if (method == null) {
				throw new NoSuchMethodException("a shader module component is not where it was");
			}

			return method;
		}
	}
}
