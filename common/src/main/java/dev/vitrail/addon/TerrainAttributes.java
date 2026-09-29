package dev.vitrail.addon;

import dev.vitrail.api.TerrainAttribute;
import dev.vitrail.api.TerrainMeshListener;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * What the add-ons need every terrain vertex to carry, whether or not the pack reads it.
 * <p>
 * Read once and kept, because two places answer with it and have to agree for the whole session:
 * the pack's chunk programs are translated against a mesh that includes it, and the mesh is built
 * from it. An add-on whose {@link TerrainMeshListener#attributes} answered differently on a second
 * call would leave the programs declaring one format and the renderer binding another, which is a
 * pack put away rather than drawn. Read where the first of those two asks, which is after every
 * add-on has registered: the loader registers them in its client entry point, before the pack is
 * read and long before Sodium builds its renderer.
 */
public final class TerrainAttributes {

	private static @Nullable Set<TerrainAttribute> forced;

	private TerrainAttributes() {
	}

	/**
	 * The union of every listener's {@link TerrainMeshListener#attributes}. A listener that throws
	 * is cut off by {@link AddonRegistry#each}, and what it asked for is not in it.
	 */
	public static synchronized Set<TerrainAttribute> forced() {
		if (forced != null) {
			return forced;
		}

		List<AddonRegistry.Entry<TerrainMeshListener>> listeners = AddonRegistry.terrain();
		if (listeners.isEmpty()) {
			// Not kept, so that a call made before the add-ons registered cannot fix the answer at
			// nothing for the session; with no listener there is nothing to read and nothing to save.
			return Set.of();
		}

		Set<TerrainAttribute> union = EnumSet.noneOf(TerrainAttribute.class);
		AddonRegistry.each(listeners, "list the terrain attributes it needs", listener -> {
			// Copied whole before any of it is added, so that a set with a null in it costs its
			// add-on everything it asked for and not the half that came before the null.
			union.addAll(Set.copyOf(listener.attributes()));
		});
		forced = Collections.unmodifiableSet(union);

		return forced;
	}

	/** Forgets what was read, for tests. */
	static synchronized void forget() {
		forced = null;
	}
}
