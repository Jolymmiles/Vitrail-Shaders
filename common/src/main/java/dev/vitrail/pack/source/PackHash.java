package dev.vitrail.pack.source;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The hash that says which pack an add-on is looking at: SHA-256 over the files under
 * {@code shaders/}, each as its path and the digest of its bytes, in path order.
 * <p>
 * Paths are taken relative to {@code shaders/} and contents are read as bytes, so a folder and a
 * zip of it agree, and so does a zip packed one level down. It is worked out only for an
 * opening that has a patcher to ask, since hashing reads every texture of the pack, and it is
 * remembered per pack: a load opens the same pack dozens of times, once per program some of them,
 * and reading it whole at each would cost more than the load.
 * <p>
 * What the memory is keyed on is a cheap answer to whether the pack moved, the archive's size and
 * stamp for a zip and every file's for a folder, which is the trade {@link KeptPack} makes. What
 * that misses is an edit that leaves size and stamp alone.
 */
final class PackHash {

	/** More than a session has packs in use, and few enough that the memory holds only digests. */
	private static final int REMEMBERED = 8;

	private static final Map<Path, Remembered> KNOWN = Collections.synchronizedMap(
			new LinkedHashMap<>(16, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<Path, Remembered> eldest) {
					return size() > REMEMBERED;
				}
			});

	private PackHash() {
	}

	private record Remembered(String stamp, String hash) {
	}

	/**
	 * The hash of {@code files}, read again only when the pack's stamp is not the one remembered.
	 *
	 * @param pack  the pack's own path, which is what the memory is filed under
	 * @param zip   whether the pack is an archive, whose stamp is the archive's alone
	 * @param files every file that counts, already in path order
	 * @param rel   a file's path relative to {@code shaders/}, with forward slashes
	 */
	static String of(Path pack, boolean zip, List<Path> files, Function<Path, String> rel) {
		Path key = pack.toAbsolutePath().normalize();
		String stamp = zip ? archiveStamp(pack) : folderStamp(files, rel);
		Remembered known = KNOWN.get(key);
		if (stamp != null && known != null && known.stamp().equals(stamp)) {
			return known.hash();
		}

		MessageDigest outer = sha256();
		for (Path file : files) {
			outer.update(rel.apply(file).getBytes(StandardCharsets.UTF_8));
			outer.update((byte) 0);
			outer.update(digestOf(file));
		}

		String hash = HexFormat.of().formatHex(outer.digest());
		if (stamp != null) {
			KNOWN.put(key, new Remembered(stamp, hash));
		}

		return hash;
	}

	/**
	 * A file's digest, taken apart from the outer one so that no length has to be written in front
	 * of the bytes and a file that grows while it is read cannot shift the ones after it. One that
	 * cannot be read at all stands as a marker no digest can equal by length, so that the pack
	 * hashes rather than being refused over a file nothing may need.
	 */
	private static byte[] digestOf(Path file) {
		MessageDigest digest = sha256();
		try (InputStream in = Files.newInputStream(file)) {
			byte[] buffer = new byte[64 * 1024];
			for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
				digest.update(buffer, 0, read);
			}
		} catch (IOException e) {
			return "unreadable".getBytes(StandardCharsets.UTF_8);
		}

		return digest.digest();
	}

	private static String archiveStamp(Path archive) {
		try {
			return Files.size(archive) + ":" + Files.getLastModifiedTime(archive).toInstant();
		} catch (IOException e) {
			return null;
		}
	}

	/**
	 * Every file's name, size and last write, folded to one string, or null when one cannot be
	 * statted, in which case the hash is worked out and not remembered.
	 */
	private static String folderStamp(List<Path> files, Function<Path, String> rel) {
		MessageDigest digest = sha256();
		for (Path file : files) {
			BasicFileAttributes attributes;
			try {
				attributes = Files.readAttributes(file, BasicFileAttributes.class);
			} catch (IOException e) {
				return null;
			}

			digest.update((rel.apply(file) + ' ' + attributes.size() + ' '
					+ attributes.lastModifiedTime().toInstant()).getBytes(StandardCharsets.UTF_8));
			digest.update((byte) 0);
		}

		return HexFormat.of().formatHex(digest.digest());
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is required of every Java runtime", e);
		}
	}
}
