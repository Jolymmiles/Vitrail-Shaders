package dev.vitrail.api;

import java.util.List;
import java.util.Map;

/**
 * Files an add-on adds to a pack and changes it makes to the pack's own files, applied as Vitrail
 * reads them and never written back to the pack.
 * <p>
 * Paths are relative to the pack's root with forward slashes, {@code shaders/lib/settings.glsl},
 * and must stay inside {@code shaders/}. What a patch does when the file is not what it expected
 * is the add-on's business: returning the lines unchanged is always safe.
 */
public interface SourcePatcher {

	/** Whether this add-on touches this pack at all; asked once each time the pack is opened. */
	boolean appliesTo(PackIdentity pack);

	/**
	 * Files that exist only through the add-on, path to lines. A path the pack already has is
	 * refused with a log line: changing a pack file is {@link #patch}.
	 */
	default Map<String, List<String>> addedFiles(PackIdentity pack) {
		return Map.of();
	}

	/**
	 * The lines of one of the pack's files as the add-on wants them read. Called once per file per
	 * opening, before includes are expanded, so an anchor matches the file as its author wrote it.
	 */
	default List<String> patch(PackIdentity pack, String path, List<String> lines) {
		return lines;
	}

	/**
	 * A text that changes whenever the added files or the patches would change for this pack. It
	 * joins the key under which Vitrail keeps an opened pack, so that a new version of the add-on
	 * does not reuse units flattened with the old patches.
	 */
	String fingerprint(PackIdentity pack);
}
