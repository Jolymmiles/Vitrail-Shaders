package dev.vitrail.pack.source;

import dev.vitrail.addon.AddonRegistry;
import dev.vitrail.addon.RegisteredPieces;
import dev.vitrail.api.PackIdentity;
import dev.vitrail.api.SourcePatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * A patcher made of fields, so that a test says what the add-on does by assigning it and reads what
 * the engine asked by looking at what was recorded.
 */
final class FakePatcher implements SourcePatcher {

	boolean applies = true;
	Map<String, List<String>> added = Map.of();
	BiFunction<String, List<String>, List<String>> edit = (path, lines) -> lines;
	String fingerprint = "v1";

	boolean appliesThrows;
	boolean addedThrows;
	boolean fingerprintThrows;

	int appliesAsked;
	final List<PackIdentity> identities = new ArrayList<>();
	final List<String> patched = new ArrayList<>();

	@Override
	public boolean appliesTo(PackIdentity pack) {
		this.appliesAsked++;
		this.identities.add(pack);
		if (this.appliesThrows) {
			throw new IllegalStateException("planted");
		}

		return this.applies;
	}

	@Override
	public Map<String, List<String>> addedFiles(PackIdentity pack) {
		if (this.addedThrows) {
			throw new IllegalStateException("planted");
		}

		return this.added;
	}

	@Override
	public List<String> patch(PackIdentity pack, String path, List<String> lines) {
		this.patched.add(path);

		return this.edit.apply(path, lines);
	}

	@Override
	public String fingerprint(PackIdentity pack) {
		if (this.fingerprintThrows) {
			throw new IllegalStateException("planted");
		}

		return this.fingerprint;
	}

	/** The registered pieces of these patchers, as the engine is handed them, add-on by add-on. */
	static List<AddonRegistry.Entry<SourcePatcher>> entries(SourcePatcher... patchers) {
		List<AddonRegistry.Entry<SourcePatcher>> entries = new ArrayList<>();
		for (int i = 0; i < patchers.length; i++) {
			entries.add(RegisteredPieces.of("addon" + i, patchers[i]));
		}

		return entries;
	}
}
