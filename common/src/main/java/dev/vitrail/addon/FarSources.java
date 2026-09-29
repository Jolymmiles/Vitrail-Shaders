package dev.vitrail.addon;

import dev.vitrail.addon.AddonRegistry.Entry;
import dev.vitrail.api.DistantTerrainSource;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * The registered far terrain sources, which one of them draws, and the meshes they hold.
 * <p>
 * Built from {@link AddonRegistry#distant} the first time anything asks, on the render thread and
 * after the registry is filled, which is when each source is handed its mesh table. Nothing changes
 * after that: a source that is cut off stays in the list and answers nothing.
 */
public final class FarSources {

	/** Null until something asks, and then a list that is never changed. */
	private static @Nullable List<FarSource> sources;

	private FarSources() {
	}

	/**
	 * Whether any add-on registered a far terrain source at all. Costs one read, which is what lets
	 * every session without one keep exactly the reads it had before there were any.
	 */
	public static boolean any() {
		return !AddonRegistry.distant().isEmpty();
	}

	/**
	 * The source that draws, or null when there is none of an add-on's, either because Distant
	 * Horizons is the source or because nothing is present.
	 * <p>
	 * <strong>Distant Horizons wins whenever it is present</strong>, and that is the whole of the
	 * rule for it: its far terrain is what every pack was written against, so an add-on standing in
	 * beside it would only be a second landscape. Of the add-ons the first in the order they were
	 * found that says it is present is the one; the ones after it are not asked.
	 *
	 * @param distantHorizonsPresent whether Distant Horizons has far terrain in this session
	 */
	public static @Nullable FarSource select(boolean distantHorizonsPresent) {
		if (distantHorizonsPresent) {
			return null;
		}

		for (FarSource source : all()) {
			if (source.present()) {
				return source;
			}
		}

		return null;
	}

	/** Gives every source's meshes what the render thread owes them, at the head of a frame. */
	public static void drain(MeshTable.Gpu gpu) {
		for (FarSource source : all()) {
			source.drain(gpu);
		}
	}

	/** Gives every mesh of every source back, at the end of the session. */
	public static void close(MeshTable.Gpu gpu) {
		if (sources == null) {
			return;
		}

		for (FarSource source : sources) {
			source.meshes().close(gpu);
		}
	}

	private static List<FarSource> all() {
		List<FarSource> built = sources;
		if (built == null) {
			built = new ArrayList<>();
			for (Entry<DistantTerrainSource> entry : AddonRegistry.distant()) {
				built.add(new FarSource(entry));
			}

			sources = built;
			built.forEach(FarSource::attach);
		}

		return built;
	}

	/** Forgets the list, for tests. */
	static void reset() {
		sources = null;
	}
}
