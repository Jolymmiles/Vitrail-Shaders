package dev.vitrail.addon;

import dev.vitrail.api.ImageSource;
import dev.vitrail.glsl.LegacyGlsl;
import dev.vitrail.pack.target.SamplerPlan;
import dev.vitrail.pack.texture.CustomImages;
import dev.vitrail.render.pbr.PbrMap;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which sampler and image names the add-ons' {@link ImageSource}s serve, once all claims are
 * settled: who owns each name, which of them Vitrail refuses, and which the pack declares as a
 * storage image.
 * <p>
 * A name is served only when nobody else gives it a meaning. Vitrail's own names (colour targets,
 * depths, the shadow map, {@code noisetex}, the far terrain's depth, what the geometry passes bind
 * themselves) and the pack's (its {@code image.} names and the textures it ships) keep theirs, and
 * a claim on one of them is logged once and ignored. Two add-ons claiming one name is settled the
 * same way: the first one registered keeps it.
 * <p>
 * The claims are read from the sources once, on the first question, because the descriptor push
 * asks for every descriptor of every pass of the game and the answer must be a map read.
 * Registration is over by then, and {@link ImageSource#names} is fixed for as long as the add-on
 * is loaded. What depends on the pack is asked each time and so follows a pack reload.
 */
public final class AddonImageNames {

	private static final Logger LOGGER = LoggerFactory.getLogger("Vitrail");

	/**
	 * The names the geometry passes bind themselves: the block atlas under its four spellings, the
	 * light map and the overlay the translation writes. The PBR maps are asked of {@link PbrMap}.
	 */
	private static final Set<String> GEOMETRY = Set.of("gtexture", "tex", "texture", "ofTexture",
			"lightmap", LegacyGlsl.OVERLAY_SAMPLER);

	private static final Object LOCK = new Object();

	private static volatile @Nullable Map<String, AddonRegistry.Entry<ImageSource>> owners;

	/** The names the loaded pack ships a texture under, in any stage. */
	private static volatile Set<String> packTextures = Set.of();

	/**
	 * The claimed names some program declares as an image and not a sampler. Only ever added to:
	 * which of the two a served name is belongs to the declaration the add-on patches in, and an
	 * add-on keeps it the same in every pack.
	 */
	private static final Set<String> IMAGES = ConcurrentHashMap.newKeySet();

	/** The names a refusal has already been logged for. */
	private static final Set<String> SAID = ConcurrentHashMap.newKeySet();

	private AddonImageNames() {
	}

	/** Whether any add-on claims an image name at all, which is the whole cost for everyone else. */
	public static boolean active() {
		return !owners().isEmpty();
	}

	/**
	 * Whether an add-on serves this name for the loaded pack: claimed, and not a name Vitrail or the
	 * pack gives a meaning to.
	 */
	public static boolean serves(String name) {
		if (!owners().containsKey(name)) {
			return false;
		}

		String refusal = refusal(name);
		if (refusal == null) {
			return true;
		}

		said(owners().get(name).addon(), name, refusal);

		return false;
	}

	/** Logs a refusal the first time it is made for a name, and never again. */
	private static void said(String addon, String name, String refusal) {
		if (SAID.add(name)) {
			LOGGER.warn("Add-on {} serves the image {}, and {}, so the name stays unserved by it",
					addon, name, refusal);
		}
	}

	/** The source that serves this name, or null when {@link #serves} says no. */
	public static AddonRegistry.@Nullable Entry<ImageSource> owner(String name) {
		return serves(name) ? owners().get(name) : null;
	}

	/**
	 * Why a claim on this name is refused, or null when it is not. The reason is written to finish
	 * the sentence "and X", naming who already gives the name a meaning.
	 */
	static @Nullable String refusal(String name) {
		String engine = engineRefusal(name);
		if (engine != null) {
			return engine;
		}

		if (CustomImages.named(name)) {
			return "the pack declares an image of that name";
		}

		if (packTextures.contains(name)) {
			return "the pack ships a texture under that name";
		}

		return null;
	}

	/** The refusals that hold whatever pack is loaded, which are Vitrail's own names. */
	private static @Nullable String engineRefusal(String name) {
		if (SamplerPlan.builtIn(name)) {
			return "Vitrail serves that name itself";
		}

		if (GEOMETRY.contains(name) || PbrMap.named(name) != null) {
			return "Vitrail binds that name in its own geometry passes";
		}

		return null;
	}

	/**
	 * Whether a program declares this served name as a storage image, {@code image2D} and its
	 * integer forms, rather than as a sampler. That is the shader's own word for which descriptor
	 * type it needs, so it decides the layout of the bind group before any image exists.
	 */
	public static boolean storageBinding(String name) {
		return IMAGES.contains(name) && serves(name);
	}

	/** Whether any program declares a served name as a storage image. */
	public static boolean declaresStorage() {
		return !IMAGES.isEmpty();
	}

	/** Said by the translation for every uniform of an image type, whatever its name. */
	public static void declaredAsImage(String name) {
		if (owners().containsKey(name)) {
			IMAGES.add(name);
		}
	}

	/**
	 * Said by the reading of a pack, with the names it ships a texture under. Said again by every
	 * program of the same pack with the same names, and by the next pack with its own; never undone,
	 * because a release that lands after the next pack's first program would erase that pack's.
	 */
	public static void packTextures(Set<String> names) {
		packTextures = Set.copyOf(names);
	}

	/** Forgets what was read from the registry, for tests. */
	static void reset() {
		synchronized (LOCK) {
			owners = null;
		}

		packTextures = Set.of();
		IMAGES.clear();
		SAID.clear();
	}

	private static Map<String, AddonRegistry.Entry<ImageSource>> owners() {
		Map<String, AddonRegistry.Entry<ImageSource>> found = owners;
		if (found != null) {
			return found;
		}

		synchronized (LOCK) {
			if (owners == null) {
				owners = gather();
			}

			return owners;
		}
	}

	private static Map<String, AddonRegistry.Entry<ImageSource>> gather() {
		Map<String, AddonRegistry.Entry<ImageSource>> claimed = new LinkedHashMap<>();
		for (AddonRegistry.Entry<ImageSource> entry : AddonRegistry.images()) {
			Set<String> names = new LinkedHashSet<>();
			if (!AddonRegistry.call(entry, "list the image names it serves",
					source -> names.addAll(source.names()))) {
				continue;
			}

			for (String name : names) {
				if (name == null || name.isBlank()) {
					LOGGER.warn("Add-on {} lists an image with no name, which is ignored", entry.addon());
					continue;
				}

				AddonRegistry.Entry<ImageSource> first = claimed.putIfAbsent(name, entry);
				if (first != null && first != entry) {
					LOGGER.warn("Add-on {} serves the image {} as well, and add-on {} was registered "
							+ "first and keeps it", entry.addon(), name, first.addon());
					continue;
				}

				// Said now for the names that are Vitrail's, which hold for every pack; the pack's own
				// are said where the question first arises, when a pack is there to have them.
				String engine = engineRefusal(name);
				if (engine != null) {
					said(entry.addon(), name, engine);
				}
			}
		}

		return Map.copyOf(claimed);
	}
}
