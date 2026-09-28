package dev.vitrail.cache;

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
	 * The edition's own directory is stamped the same way at {@link ModuleCache#open}, and that is the half
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
	 * answered for {@link ModuleCache#open} to say, and the next launch finds it and tries again.
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
}
