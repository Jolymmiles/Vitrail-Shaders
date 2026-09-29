package dev.vitrail.pack.source;

import dev.vitrail.Vitrail;
import dev.vitrail.addon.AddonRegistry;
import dev.vitrail.api.PackIdentity;
import dev.vitrail.api.SourcePatcher;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What the add-ons' {@link SourcePatcher}s do to one opening of one pack: which of them apply, the
 * files they add, and the patches they make to the files the pack has.
 * <p>
 * Decided once, when the pack is opened, and fixed for that opening. Whether a patcher applies is a
 * question about the pack, asked with its identity; what can change while the game runs is what
 * {@link SourcePatcher#fingerprint} is for, and {@link #moved} is the answer to it.
 * <p>
 * A patcher is called once per file per opening and its answer is kept, whichever reader asks
 * first: {@link ShaderPackSource#readLines} for the expander and the properties files, and
 * {@link ShaderPackSource#searchableText} for the scan of what the pack mentions. A patch that
 * answered differently the second time would let the two disagree about one file. Only the files a
 * patch really changed are kept here; the rest are the readers' own to hold.
 * <p>
 * Every call into a patcher goes through {@link AddonRegistry#call}, and whatever it hands back is
 * checked before it is used: a null, or a list with a null in it, is the add-on's fault and cuts it
 * off like a throw. What a cut off patcher leaves behind is the files read up to then as it patched
 * them and the ones after as the pack wrote them; the fingerprint then differs from the opening's,
 * so nothing keeps that opening for the next load. Like the opening itself, it is used from one
 * thread.
 */
final class SourcePatches {

	/** What a patcher is given in front of a path under {@code shaders/}. */
	private static final String PREFIX = "shaders/";

	/** Refusals already said, by add-on, pack and path: a load opens one pack a great many times. */
	private static final Set<String> REFUSED = ConcurrentHashMap.newKeySet();

	/** An opening no patcher applies to, which is every opening while no add-on patches. */
	static final SourcePatches NONE = new SourcePatches(List.of(), null, Map.of());

	/**
	 * What the opening can say about a file an add-on wants to put in the pack, which is the part
	 * of admitting one that needs the filesystem.
	 */
	@FunctionalInterface
	interface Site {

		/**
		 * Why the pack cannot take a file of this size at this path relative to {@code shaders/},
		 * or null when it can.
		 */
		String refuse(String relative, long bytes);
	}

	private final List<AddonRegistry.Entry<SourcePatcher>> applying;
	private final PackIdentity identity;
	private final Map<String, List<String>> added;

	/** What {@link #fingerprint} said when the opening was made, which {@link #moved} compares. */
	private List<String> opened = List.of();

	private final Set<String> asked = new HashSet<>();
	private final Map<String, List<String>> changed = new HashMap<>();

	private SourcePatches(List<AddonRegistry.Entry<SourcePatcher>> applying, PackIdentity identity,
			Map<String, List<String>> added) {
		this.applying = applying;
		this.identity = identity;
		this.added = added;
	}

	/**
	 * Asks every registered patcher whether it applies to this pack, and takes the files of those
	 * that do.
	 *
	 * @param site says what the pack's own files rule out
	 */
	static SourcePatches resolve(List<AddonRegistry.Entry<SourcePatcher>> registered, PackIdentity pack,
			Site site) {
		List<AddonRegistry.Entry<SourcePatcher>> applying = new ArrayList<>();
		for (AddonRegistry.Entry<SourcePatcher> entry : registered) {
			boolean[] applies = new boolean[1];
			if (AddonRegistry.call(entry, "decide whether it applies to a pack",
					patcher -> applies[0] = patcher.appliesTo(pack)) && applies[0]) {
				applying.add(entry);
			}
		}

		if (applying.isEmpty()) {
			return NONE;
		}

		Map<String, List<String>> added = new LinkedHashMap<>();
		Map<String, String> owners = new HashMap<>();
		for (AddonRegistry.Entry<SourcePatcher> entry : applying) {
			Map<String, List<String>> offered = new LinkedHashMap<>();
			// Taken whole or not at all, like everything else from an add-on that throws: a patcher
			// cut off half way through its list would leave the pack with some of its files.
			if (!AddonRegistry.call(entry, "list the files it adds", patcher -> {
				for (Map.Entry<String, List<String>> file : patcher.addedFiles(pack).entrySet()) {
					offered.put(Objects.requireNonNull(file.getKey(), "path"),
							normalise(Objects.requireNonNull(file.getValue(), "lines")));
				}
			})) {
				continue;
			}

			offered.forEach((path, lines) -> {
				String relative = underShaders(path);
				String reason = relative == null
						? "is not a path under shaders/, with forward slashes and no dot segments"
						: owners.containsKey(relative)
								? "is a file add-on " + owners.get(relative) + " adds"
								: site.refuse(relative, textBytes(lines));
				if (reason != null) {
					refuse(entry.addon(), pack.name(), path, reason);
				} else {
					added.put(relative, lines);
					owners.put(relative, entry.addon());
				}
			});
		}

		SourcePatches patches = new SourcePatches(List.copyOf(applying), pack, added);
		patches.opened = patches.fingerprint();

		return patches;
	}

	private static void refuse(String addon, String pack, String path, String reason) {
		if (REFUSED.add(addon + '\0' + pack + '\0' + path)) {
			Vitrail.logger().warn("Add-on {} adds {} to {}, which {}; the file is refused", addon, path, pack,
					reason);
		}
	}

	/**
	 * The path an add-on wrote, relative to {@code shaders/}, or null when it does not name a file
	 * there. The dot segments are what would climb out; the backslash is what a zip and a folder
	 * read differently.
	 */
	private static String underShaders(String path) {
		if (!path.startsWith(PREFIX) || path.endsWith("/")) {
			return null;
		}

		String relative = path.substring(PREFIX.length());
		for (String segment : relative.split("/", -1)) {
			if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
					|| segment.indexOf('\\') >= 0 || segment.indexOf('\0') >= 0) {
				return null;
			}
		}

		return relative;
	}

	/** Whether any patcher applies to this opening. */
	boolean active() {
		return !this.applying.isEmpty();
	}

	/** The files added, by path relative to {@code shaders/}, in the order they were taken. */
	Map<String, List<String>> added() {
		return this.added;
	}

	/**
	 * The lines of a file of the pack as the patchers that apply want them read: the same lines,
	 * when none of them changed anything.
	 *
	 * @param relative the path relative to {@code shaders/}
	 * @param raw      the file as the pack wrote it
	 */
	List<String> apply(String relative, List<String> raw) {
		if (this.applying.isEmpty()) {
			return raw;
		}

		if (!this.asked.add(relative)) {
			List<String> known = this.changed.get(relative);

			return known == null ? raw : known;
		}

		String path = PREFIX + relative;
		List<String> lines = raw;
		for (AddonRegistry.Entry<SourcePatcher> entry : this.applying) {
			List<String> before = lines;
			AtomicReference<List<String>> patched = new AtomicReference<>();
			if (AddonRegistry.call(entry, "patch a pack file", patcher -> patched.set(normalise(
					Objects.requireNonNull(patcher.patch(this.identity, path, before), "lines"))))) {
				lines = patched.get();
			}
		}

		if (lines.equals(raw)) {
			return raw;
		}

		this.changed.put(relative, lines);

		return lines;
	}

	/**
	 * What each applying patcher says would change in this pack, one entry per patcher and none
	 * where no patcher applies. A patcher that has been cut off, or is cut off by being asked,
	 * stands as that.
	 */
	List<String> fingerprint() {
		List<String> prints = new ArrayList<>(this.applying.size());
		for (AddonRegistry.Entry<SourcePatcher> entry : this.applying) {
			AtomicReference<String> print = new AtomicReference<>("(cut off)");
			AddonRegistry.call(entry, "fingerprint its changes to a pack", patcher -> print.set(
					Objects.requireNonNull(patcher.fingerprint(this.identity), "fingerprint")));
			prints.add(entry.addon() + '\0' + print.get());
		}

		return prints;
	}

	/**
	 * Whether the patchers would change this pack otherwise than they did when it was opened, which
	 * is whether a flattened unit kept from this opening is still the one they would make.
	 */
	boolean moved() {
		return !this.applying.isEmpty() && !fingerprint().equals(this.opened);
	}

	/**
	 * A file's lines as text, the way a file of the pack is written: joined by line feeds, so that
	 * what was one line is one line and a last empty line is the trailing newline.
	 */
	static byte[] textOf(List<String> lines) {
		return String.join("\n", lines).getBytes(StandardCharsets.UTF_8);
	}

	private static long textBytes(List<String> lines) {
		return textOf(lines).length;
	}

	/**
	 * The lines with none of them holding a line break, since everything downstream takes an
	 * element of the list for one line and a directive is read off its own. Copied, so that an
	 * add-on cannot change a file after handing it over.
	 */
	private static List<String> normalise(List<String> lines) {
		List<String> out = new ArrayList<>(lines.size());
		for (String line : lines) {
			if (line.indexOf('\n') < 0 && line.indexOf('\r') < 0) {
				out.add(line);
			} else {
				out.addAll(Arrays.asList(line.split("\r\n|\n|\r", -1)));
			}
		}

		return List.copyOf(out);
	}
}
