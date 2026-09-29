package dev.vitrail.addon;

import dev.vitrail.api.AddonRegistrar;
import dev.vitrail.api.DefineSource;
import dev.vitrail.api.DistantTerrainSource;
import dev.vitrail.api.ImageSource;
import dev.vitrail.api.SourcePatcher;
import dev.vitrail.api.StageListener;
import dev.vitrail.api.TerrainMeshListener;
import dev.vitrail.api.VitrailAddon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the add-ons registered, kind by kind, and the guard every call into them goes through.
 * <p>
 * Filled once from the loader's client entry point and read on the render thread from then on, so
 * the lists are built before anything reads them and never change after; nothing here is locked.
 * <p>
 * An add-on is taken whole or not at all. Its pieces are gathered while its
 * {@link VitrailAddon#register} runs and joined to the lists only when it returns, so an add-on that
 * throws halfway leaves nothing half-registered behind. Linkage errors count as throwing: an add-on
 * built against another version of the API fails with one when it calls a method this version does
 * not have, and that is its fault, not a reason to stop the game.
 */
public final class AddonRegistry {

	private static final Logger LOGGER = LoggerFactory.getLogger("Vitrail");

	private static final List<Entry<DefineSource>> DEFINES = new ArrayList<>();
	private static final List<Entry<ImageSource>> IMAGES = new ArrayList<>();
	private static final List<Entry<SourcePatcher>> SOURCES = new ArrayList<>();
	private static final List<Entry<StageListener>> STAGES = new ArrayList<>();
	private static final List<Entry<DistantTerrainSource>> DISTANT = new ArrayList<>();
	private static final List<Entry<TerrainMeshListener>> TERRAIN = new ArrayList<>();

	private AddonRegistry() {
	}

	/**
	 * One registered piece and the add-on it came from. Cut off for the session the first time a
	 * call into it throws.
	 */
	public static final class Entry<T> {

		private final String addon;
		private final T piece;
		private boolean cutOff;

		Entry(String addon, T piece) {
			this.addon = addon;
			this.piece = piece;
		}

		/** The id of the add-on that registered it. */
		public String addon() {
			return addon;
		}

		/** The registered piece itself. */
		public T piece() {
			return piece;
		}

		/** Whether a call into it has thrown, after which it is skipped. */
		public boolean cutOff() {
			return cutOff;
		}
	}

	/** Registers every add-on the loader found, in the order it found them. */
	public static void load(List<VitrailAddon> addons) {
		for (VitrailAddon addon : addons) {
			load(addon);
		}
	}

	private static void load(VitrailAddon addon) {
		String id;
		try {
			id = Objects.requireNonNull(addon.id(), "id");
		} catch (RuntimeException | LinkageError failure) {
			LOGGER.error("An add-on of class {} has no usable id and is skipped", addon.getClass().getName(), failure);
			return;
		}

		Gathering gathering = new Gathering(id);
		try {
			addon.register(gathering);
		} catch (RuntimeException | LinkageError failure) {
			LOGGER.error("Add-on {} failed to register and is skipped", id, failure);
			return;
		}

		gathering.done = true;
		DEFINES.addAll(gathering.defines);
		IMAGES.addAll(gathering.images);
		SOURCES.addAll(gathering.sources);
		STAGES.addAll(gathering.stages);
		DISTANT.addAll(gathering.distant);
		TERRAIN.addAll(gathering.terrain);
		LOGGER.info("Add-on {} registered {} piece(s)", id, gathering.count());
	}

	/** Registered define sources. */
	public static List<Entry<DefineSource>> defines() {
		return Collections.unmodifiableList(DEFINES);
	}

	/** Registered image sources. */
	public static List<Entry<ImageSource>> images() {
		return Collections.unmodifiableList(IMAGES);
	}

	/** Registered source patchers. */
	public static List<Entry<SourcePatcher>> sources() {
		return Collections.unmodifiableList(SOURCES);
	}

	/** Registered stage listeners. */
	public static List<Entry<StageListener>> stages() {
		return Collections.unmodifiableList(STAGES);
	}

	/** Registered far terrain sources. */
	public static List<Entry<DistantTerrainSource>> distant() {
		return Collections.unmodifiableList(DISTANT);
	}

	/** Registered terrain mesh listeners. */
	public static List<Entry<TerrainMeshListener>> terrain() {
		return Collections.unmodifiableList(TERRAIN);
	}

	/** Whether any add-on registered anything, so that callers can skip their work outright. */
	public static boolean any() {
		return !DEFINES.isEmpty() || !IMAGES.isEmpty() || !SOURCES.isEmpty() || !STAGES.isEmpty()
				|| !DISTANT.isEmpty() || !TERRAIN.isEmpty();
	}

	/**
	 * Calls {@code call} on every entry that has not been cut off. An entry whose call throws is cut
	 * off, logged once with its add-on's id and {@code what} it was asked to do, and skipped from
	 * then on; the others are still called.
	 */
	public static <T> void each(List<Entry<T>> entries, String what, Consumer<? super T> call) {
		for (Entry<T> entry : entries) {
			call(entry, what, call);
		}
	}

	/**
	 * Calls {@code call} on one entry under the same rule as {@link #each}, and says whether it
	 * returned. False for an entry already cut off.
	 */
	public static <T> boolean call(Entry<T> entry, String what, Consumer<? super T> call) {
		if (entry.cutOff) {
			return false;
		}

		try {
			call.accept(entry.piece);
			return true;
		} catch (RuntimeException | LinkageError failure) {
			entry.cutOff = true;
			LOGGER.error("Add-on {} failed to {} and is cut off for this session", entry.addon, what, failure);
			return false;
		}
	}

	/** Forgets every add-on, for tests. */
	static void clear() {
		DEFINES.clear();
		IMAGES.clear();
		SOURCES.clear();
		STAGES.clear();
		DISTANT.clear();
		TERRAIN.clear();
	}

	/** The registrar one add-on is handed, which refuses to be used once its register returned. */
	private static final class Gathering implements AddonRegistrar {

		private final String addon;
		private final List<Entry<DefineSource>> defines = new ArrayList<>();
		private final List<Entry<ImageSource>> images = new ArrayList<>();
		private final List<Entry<SourcePatcher>> sources = new ArrayList<>();
		private final List<Entry<StageListener>> stages = new ArrayList<>();
		private final List<Entry<DistantTerrainSource>> distant = new ArrayList<>();
		private final List<Entry<TerrainMeshListener>> terrain = new ArrayList<>();
		private boolean done;

		Gathering(String addon) {
			this.addon = addon;
		}

		@Override
		public void defines(DefineSource source) {
			defines.add(entry(source));
		}

		@Override
		public void images(ImageSource source) {
			images.add(entry(source));
		}

		@Override
		public void sources(SourcePatcher patcher) {
			sources.add(entry(patcher));
		}

		@Override
		public void stages(StageListener listener) {
			stages.add(entry(listener));
		}

		@Override
		public void distant(DistantTerrainSource source) {
			distant.add(entry(source));
		}

		@Override
		public void terrain(TerrainMeshListener listener) {
			terrain.add(entry(listener));
		}

		private <T> Entry<T> entry(T piece) {
			if (done) {
				throw new IllegalStateException("Add-on " + addon + " registered after its register() returned");
			}

			return new Entry<>(addon, Objects.requireNonNull(piece, "piece"));
		}

		int count() {
			return defines.size() + images.size() + sources.size() + stages.size() + distant.size()
					+ terrain.size();
		}
	}
}
