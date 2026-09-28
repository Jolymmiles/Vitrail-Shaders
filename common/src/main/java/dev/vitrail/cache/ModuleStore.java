package dev.vitrail.cache;

import dev.vitrail.render.PackNames;
import dev.vitrail.render.RawLocals;
import dev.vitrail.render.SamplerReach;
import dev.vitrail.Vitrail;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * What the two games' {@link ModuleCache} classes have in common, kept once: everything about the
 * cache that does not touch a type the game changed between 26.2 and 26.3. The class that differs
 * stays a thin {@link ModuleCache} beside each game's module, and says what a stored module is.
 * <p>
 * Without this there are two copies of the disk layout, the key, the sweep and the folder handling
 * that a fix has to reach one by one, and the day one is missed a player's cache behaves differently
 * on one game for no reason anyone chose.
 */
final class ModuleStore {

	/**
	 * What {@link Vitrail#cacheEdition()} puts between the family and the commit, and what a
	 * neighbour of this family is therefore matched on.
	 */
	private static final String EDITION_SEPARATOR = "+";

	/** SHA-256, sitting behind the module in every file and answering for it. */
	static final int DIGEST_BYTES = 32;

	/** Off by property rather than by rebuild, so a before and an after come out of one jar. */
	private static final boolean ENABLED = Boolean.parseBoolean(
			System.getProperty("vitrail.moduleCache", "true"));

	/** The range the Sodium slider offers, which {@link ModuleCache} republishes. */
	static final int MIN_CEILING_MIB = 128;
	static final int MAX_CEILING_MIB = 2048;
	static final int DEFAULT_CEILING_MIB = 512;
	static final int CEILING_STEP_MIB = 128;

	private static final String CEILING_FILE = "module-cache-ceiling.txt";

	private static volatile int ceilingMib = DEFAULT_CEILING_MIB;
	private static volatile boolean ceilingLoaded;

	/** How long a sweep that could not finish stays out of the way of the next write. */
	private static final long SWEEP_BACKOFF_NANOS = 60_000_000_000L;

	private static final String FOLDER = "modules";

	/** How many of a load's rebuilt units are named on the line that counts them. */
	private static final int NAMED_MISSES = 12;

	/** The debug names of the units this load built, the first {@link #NAMED_MISSES} of them. */
	private static final List<String> BUILT_NAMES = new ArrayList<>();
	static final String SUFFIX = ".mod";
	static final String PART_SUFFIX = ".part";

	/** How long the compiler has to stay quiet before a load is taken to be over. */
	private static final long QUIET_NANOS = 2_000_000_000L;

	static final AtomicLong SERVED = new AtomicLong();
	private static final AtomicLong COMPILED = new AtomicLong();
	static final AtomicLong SERVED_SINCE_LAUNCH = new AtomicLong();
	private static final AtomicLong COMPILED_SINCE_LAUNCH = new AtomicLong();
	static final AtomicLong BYTES = new AtomicLong();

	/** Held for the first look at the directory and for every sweep, which are the two scans. */
	private static final Object LOCK = new Object();

	private static volatile @Nullable Path directory;
	private static volatile boolean unavailable;
	static volatile long lastUnitNanos;
	private static volatile long nextSweepNanos;
	private static volatile boolean saidAboutReading;
	private static volatile boolean saidAboutStoring;
	private static volatile boolean saidAboutWriting;

	private ModuleStore() {
	}

	static byte[] sha256(byte[] raw) {
		return sha256().digest(raw);
	}

	static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is required of every Java runtime", e);
		}
	}

	/**
	 * One piece of the key, behind its own length so that two different splits of the same
	 * characters cannot hash alike.
	 * <p>
	 * <strong>The versions are the half that is easy to leave out and expensive to leave
	 * out</strong>: the text alone names none of them, and a translator that emits differently, a
	 * game that compiles differently, a loader that patches the compiler, or an LWJGL bump that
	 * brings a new shaderc and a new SPIRV-Cross with it are each a different answer to a question
	 * whose text has not moved. Serving the old blob for one of those is exactly the failure this
	 * class has to be unable to have, and not one of them announces itself any other way.
	 */
	static void feed(MessageDigest digest, String text) {
		byte[] raw = text.getBytes(StandardCharsets.UTF_8);
		digest.update(new byte[] {
				(byte) (raw.length >>> 24), (byte) (raw.length >>> 16),
				(byte) (raw.length >>> 8), (byte) raw.length,
		});
		digest.update(raw);
	}

	/** The neighbour and then the move, which is what makes a half written file impossible. */
	static void move(Path part, Path file) throws IOException {
		try {
			Files.move(part, file, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/**
	 * Marks a unit as asked for, so that the sweep drops what nothing loads rather than what was
	 * written longest ago. A stamp that cannot be set costs a worse choice later and nothing now.
	 * <p>
	 * The edition's own directory is stamped the same way at {@link #open}, and that is the half
	 * that has to be written down: a file system already moves a directory's stamp when a unit is
	 * created inside it, so a run that WROTE is stamped whether this line exists or not, while a run
	 * that hit on everything wrote nothing and would read as abandoned by the very next build.
	 * Between the two, the stamp is the last time the edition was used at all.
	 */
	static void touch(Path file) {
		try {
			Files.setLastModifiedTime(file, FileTime.from(Instant.now()));
		} catch (IOException ignored) {
			// Deliberately silent, and deliberately not a miss: the blob itself is fine either way.
		}
	}

	static String megabytes(long amount) {
		return String.format(Locale.ROOT, "%.1f", amount / 1048576.0D);
	}

	/**
	 * Whether the digest a file carries answers for the module in front of it.
	 * <p>
	 * The magic word and the length are cheap and they are not enough: a file cut on a four byte
	 * boundary keeps both, and what a cut module reaches next is a native parser with nothing
	 * between it and the process. This is the check that makes the class's promise true.
	 */
	static boolean answersForItself(byte[] raw, int length) {
		MessageDigest digest = sha256();
		digest.update(raw, 0, length);

		return Arrays.equals(digest.digest(), 0, DIGEST_BYTES, raw, length, raw.length);
	}

	/**
	 * Deletes what another edition left, because not one of its keys can be asked for by this build
	 * and the ceiling has to be about the blobs that are still reachable.
	 * <p>
	 * <strong>A build carrying a commit spares one of them</strong>, the edition of its own family
	 * used most recently. Two builds of one version are two editions, and a developer moving between
	 * them would otherwise have each of them empty the other's folder on the way in, which is every
	 * pack compiled from cold at every swap and exactly what an edition exists to prevent. One is
	 * what a swap needs, and it is also what bounds the disk: two editions under one ceiling each,
	 * rather than a folder for every build ever made. A third build's folder goes at the launch
	 * after it.
	 * <p>
	 * A build whose edition IS the family spares none, so a player's store holds the one edition it
	 * always held. That is every RELEASE, and also a development build git could answer no question
	 * for, which carries no commit to name a folder of its own with.
	 * <p>
	 * <strong>It never throws</strong>, and goes on past whatever refuses it: every folder is
	 * attempted, and within one every file that will go does. What it could not take away is
	 * answered for {@link #open} to say, and the next launch finds it and tries again.
	 *
	 * @return each folder left behind with the first refusal it gave, or empty when all of it went
	 */
	static String dropOtherEditions(Path root, Path mine, String family) {
		List<Path> entries;
		try (Stream<Path> found = Files.list(root)) {
			entries = found.toList();
		} catch (IOException | RuntimeException e) {
			return "the folder could not be listed (" + e + ")";
		}

		String kept = mine.getFileName().toString().equals(family)
				? "" : newestSibling(entries, mine, family);
		List<String> left = new ArrayList<>();

		for (Path entry : entries) {
			if (!entry.equals(mine) && !entry.getFileName().toString().equals(kept)) {
				Exception refusal = dropTree(entry);
				if (refusal != null) {
					left.add(entry.getFileName() + " (" + refusal + ")");
				}
			}
		}

		return String.join(", ", left);
	}

	/**
	 * The name of the edition of this family used most recently, or empty when this build is the
	 * only one of its family to have run here. A directory has a name, so an empty answer matches
	 * nothing. A folder whose stamp cannot be read is not a candidate, which costs a swap one
	 * store at worst.
	 */
	private static String newestSibling(List<Path> entries, Path mine, String family) {
		String newest = "";
		long stamp = Long.MIN_VALUE;

		for (Path entry : entries) {
			String name = entry.getFileName().toString();
			if (entry.equals(mine) || !ofFamily(name, family)) {
				continue;
			}

			long when;
			try {
				when = Files.getLastModifiedTime(entry).toMillis();
			} catch (IOException e) {
				continue;
			}

			if (when > stamp) {
				stamp = when;
				newest = name;
			}
		}

		return newest;
	}

	/**
	 * Whether a directory name is an edition of this family: the family entire, or the family and
	 * then a commit behind the separator {@link Vitrail#cacheEdition()} puts between them.
	 * <p>
	 * The separator is what makes this an answer and not a guess. A name beginning with the family
	 * is not an edition of it: {@code ...+mc26.20} begins with {@code ...+mc26.2} and is another
	 * game version altogether, whose blobs are exactly the ones nothing can ever ask for again, so a
	 * prefix on its own would spare the folder that most needs sweeping.
	 */
	private static boolean ofFamily(String name, String family) {
		return name.equals(family) || name.startsWith(family + EDITION_SEPARATOR);
	}

	/**
	 * Deletes what it can of one folder, deepest first, and goes on past a file that refuses: the
	 * files beside it are space as well. The folders above a refusal then refuse in their turn, not
	 * being empty, so the FIRST refusal is the one that says why.
	 *
	 * @return that first refusal, or null when the whole folder went
	 */
	private static @Nullable Exception dropTree(Path entry) {
		List<Path> tree;
		try (Stream<Path> walk = Files.walk(entry)) {
			tree = walk.sorted(Comparator.reverseOrder()).toList();
		} catch (IOException | RuntimeException e) {
			// A folder inside it that cannot be read, which the walk throws unchecked.
			return e;
		}

		Exception first = null;
		for (Path found : tree) {
			try {
				Files.deleteIfExists(found);
			} catch (IOException e) {
				if (first == null) {
					first = e;
				}
			}
		}

		return first;
	}

	/**
	 * How large the store may grow, in mebibytes, which is what the Sodium slider reads. An
	 * absent or unreadable file is {@link #DEFAULT_CEILING_MIB}.
	 */
	static int ceilingMib() {
		if (!ceilingLoaded) {
			loadCeiling();
		}

		return ceilingMib;
	}

	/**
	 * Writes the ceiling and keeps the live answer, so the next store sees it. A store already
	 * over the new number is swept at once: no pack reload and no restart.
	 */
	static void setCeilingMib(int mib) {
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

	static long ceilingBytes() {
		return bytesOf(ceilingMib());
	}

	private static long sweepTarget() {
		return ceilingBytes() / 4L * 3L;
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
	static void building(String filename) {
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
	static void say(String compiled) {
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
			Vitrail.logger().info("Module cache off, so all {} units of this load were " + compiled
					+ " ({} since this launch)", misses, COMPILED_SINCE_LAUNCH.get());
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
	static @Nullable Path directory() {
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
	static void sweep(Path root) {
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

	static void sayAboutReading(String what) {
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
	static void sayAboutStoring(String what) {
		if (!saidAboutStoring) {
			saidAboutStoring = true;
			Vitrail.logger().warn("In the module cache, {}, so it was used and not kept. Said once "
					+ "a run: the next load compiles it again and nothing else changes", what);
		}
	}

	static void sayAboutWriting(String what, IOException cause) {
		if (!saidAboutWriting) {
			saidAboutWriting = true;
			Vitrail.logger().warn("In the module cache, {}: {}. Said once a run: the next load pays "
					+ "for the compile again and nothing else changes", what, cause.toString());
		}
	}

	/** One file of the cache, with what the sweep needs to order it and to subtract it. */
	private record Unit(Path path, long stamp, long size) {
	}
}
