package dev.vitrail.addon;

import dev.vitrail.api.DefineSource;
import dev.vitrail.pack.option.DefineNames;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns what the add-ons' {@link DefineSource}s write into the one map that joins the engine's
 * table, and says whether it would come out differently from an earlier reading.
 * <p>
 * Every rule about which name gets in lives here so that the table has one answer to it: add-on
 * order and then registration order, the first source to pose a name keeping it, and the engine's
 * own symbols never moved. A source is handed a map of its own and not the running one, so that it
 * cannot read or overwrite what an earlier add-on posed, and so that a refusal can name the add-on
 * it belongs to.
 */
public final class AddonDefines {

	private static final Logger LOGGER = LoggerFactory.getLogger("Vitrail");

	/**
	 * Names already refused once. Vitrail gathers the defines at every load, and an add-on that
	 * writes a bad name writes it at every one of them: said once, not once per portal.
	 */
	private static final Set<String> REFUSED = ConcurrentHashMap.newKeySet();

	private AddonDefines() {
	}

	/**
	 * One source's revision as it stood, and whether it had been cut off, which also changes what
	 * the sources write: a source that stopped answering takes its defines with it, even when the
	 * number it last returned is the one it would have returned again.
	 */
	public record Revision(long value, boolean cutOff) {
	}

	/**
	 * What the sources write, in the order they were registered, without the names that may not be
	 * posed.
	 *
	 * @param reserved the names that are the engine's, which a source may not pose
	 */
	public static Map<String, String> gather(List<AddonRegistry.Entry<DefineSource>> sources,
			Predicate<String> reserved) {
		return gather(sources, reserved, REFUSED);
	}

	/** As above, remembering what it refused in {@code refused} rather than in the session's own. */
	static Map<String, String> gather(List<AddonRegistry.Entry<DefineSource>> sources,
			Predicate<String> reserved, Set<String> refused) {
		Map<String, String> posed = new LinkedHashMap<>();
		Map<String, String> owners = new LinkedHashMap<>();
		for (AddonRegistry.Entry<DefineSource> entry : sources) {
			Map<String, String> written = new LinkedHashMap<>();
			// A source that throws part way has written something it did not mean to give: none of
			// it is taken, since what the cut off source would have finished is unknown.
			if (!AddonRegistry.call(entry, "write its defines", source -> source.write(written))) {
				continue;
			}

			written.forEach((name, value) -> {
				String reason = refusal(name, value, reserved, posed, owners);
				if (reason != null) {
					refuse(refused, entry.addon(), name, reason);
				} else if (posed.putIfAbsent(name, value) == null) {
					owners.put(name, entry.addon());
				}
			});
		}

		return posed;
	}

	/**
	 * Why a name may not join, or null when it may. A name posed twice to the same value is not a
	 * refusal, because nothing a pack can read differs: two add-ons that both want a symbol on can
	 * both have it.
	 */
	private static String refusal(String name, String value, Predicate<String> reserved,
			Map<String, String> posed, Map<String, String> owners) {
		if (!DefineNames.valid(name)) {
			return "is not a name the preprocessor reads";
		}

		if (!DefineNames.validValue(value)) {
			return "has a value that is missing or holds a line break";
		}

		if (reserved.test(name)) {
			return "is a symbol Vitrail poses itself, and its value stays";
		}

		String taken = posed.get(name);
		if (taken != null && !taken.equals(value)) {
			return "is already posed by add-on " + owners.get(name) + " under another value";
		}

		return null;
	}

	private static void refuse(Set<String> refused, String addon, String name, String reason) {
		if (refused.add(addon + ' ' + name)) {
			LOGGER.warn("Add-on {} defined {}, which {}; the define is refused", addon,
					name == null ? "(null)" : name, reason);
		}
	}

	/**
	 * Where every source stands, to be compared with a later reading through {@link #moved}. A
	 * source that throws is cut off and read as cut off from then on.
	 */
	public static List<Revision> revisions(List<AddonRegistry.Entry<DefineSource>> sources) {
		List<Revision> read = new ArrayList<>(sources.size());
		for (AddonRegistry.Entry<DefineSource> entry : sources) {
			read.add(read(entry));
		}

		return read;
	}

	/**
	 * Whether the sources answer otherwise than they did when {@code recorded} was read, which is
	 * whether the pack has to be read again to carry what they write. Asked every frame, so it
	 * stops at the first source that moved and allocates nothing where there are none.
	 */
	public static boolean moved(List<AddonRegistry.Entry<DefineSource>> sources, List<Revision> recorded) {
		if (sources.size() != recorded.size()) {
			return true;
		}

		for (int i = 0; i < sources.size(); i++) {
			if (!read(sources.get(i)).equals(recorded.get(i))) {
				return true;
			}
		}

		return false;
	}

	private static Revision read(AddonRegistry.Entry<DefineSource> entry) {
		long[] value = new long[1];
		AddonRegistry.call(entry, "read its define revision", source -> value[0] = source.revision());

		return new Revision(value[0], entry.cutOff());
	}
}
