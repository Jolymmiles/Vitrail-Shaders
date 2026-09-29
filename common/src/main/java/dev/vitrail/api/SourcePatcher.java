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
 * <p>
 * Called from whichever thread opens the pack, which is the render thread or one of the workers
 * that read the rest of the pack's programs while the world is played, and two openings can
 * overlap. An implementation therefore changes no state it holds without a lock.
 */
public interface SourcePatcher {

	/**
	 * Whether this add-on touches this pack at all; asked once each time the pack is opened, and
	 * the answer holds for as long as that opening does, which may be several loads. What can
	 * change from one load to the next is {@link #fingerprint}'s to say.
	 */
	boolean appliesTo(PackIdentity pack);

	/**
	 * Files that exist only through the add-on, path to lines; a line holding a line break is
	 * split there. A path the pack already has is refused with a log line: changing a pack file is
	 * {@link #patch}. So is a path that does not begin with {@code shaders/}, that holds an empty
	 * or dot segment or a backslash, or that another add-on added first. Added files are not
	 * passed to {@link #patch}.
	 */
	default Map<String, List<String>> addedFiles(PackIdentity pack) {
		return Map.of();
	}

	/**
	 * The lines of one of the pack's files as the add-on wants them read. Called once per file per
	 * opening, before includes are expanded, so an anchor matches the file as its author wrote it.
	 * Every file Vitrail reads as text is offered, the pack's properties files among the shaders.
	 * What comes back must not be null or hold a null.
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
