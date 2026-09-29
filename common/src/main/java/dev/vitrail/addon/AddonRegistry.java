package dev.vitrail.addon;

import dev.vitrail.api.AddonRegistrar;
import dev.vitrail.api.DefineSource;
import dev.vitrail.api.DeviceClosingListener;
import dev.vitrail.api.DistantTerrainSource;
import dev.vitrail.api.ImageSource;
import dev.vitrail.api.SourcePatcher;
import dev.vitrail.api.StageListener;
import dev.vitrail.api.TerrainMeshListener;
import dev.vitrail.api.VitrailAddon;
import dev.vitrail.api.VulkanHandles;

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
 * Filled once from the loader's client entry point, before any thread that reads them exists, and
 * never changed after, so the lists need no lock. Most calls come from the render thread, but a
 * source patcher is also called from the pack-load workers, which is why the one field that does
 * change, an entry's cut-off flag, is volatile: a piece cut off on one thread must be skipped on
 * the others.
 * <p>
 * An add-on is taken whole or not at all. Its pieces are gathered while its
 * {@link VitrailAddon#register} runs and joined to the lists only when it returns, so an add-on that
 * throws halfway leaves nothing half-registered behind. Linkage errors count as throwing: an add-on
 * built against another version of the API fails with one when it calls a method this version does
 * not have, and that is its fault, not a reason to stop the game.
 * <p>
 * The device closing notice is the last call any add-on gets: {@link #closeDevice} gives it once,
 * and from then on every guard here refuses, so nothing of an add-on runs against a device it has
 * been told is gone.
 */
public final class AddonRegistry {

	private static final Logger LOGGER = LoggerFactory.getLogger("Vitrail");

	private static final List<Entry<DefineSource>> DEFINES = new ArrayList<>();
	private static final List<Entry<ImageSource>> IMAGES = new ArrayList<>();
	private static final List<Entry<SourcePatcher>> SOURCES = new ArrayList<>();
	private static final List<Entry<StageListener>> STAGES = new ArrayList<>();
	private static final List<Entry<DistantTerrainSource>> DISTANT = new ArrayList<>();
	private static final List<Entry<TerrainMeshListener>> TERRAIN = new ArrayList<>();
	private static final List<Entry<DeviceClosingListener>> CLOSING = new ArrayList<>();

	/** Whether the device closing notice has been started, which is given once. */
	private static boolean noticed;

	/** Whether the notice has been given, after which no add-on is called at all. */
	private static volatile boolean ended;

	private AddonRegistry() {
	}

	/**
	 * One registered piece and the add-on it came from. Cut off for the session the first time a
	 * call into it throws.
	 */
	public static final class Entry<T> {

		private final String addon;
		private final T piece;
		private volatile boolean cutOff;

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
		CLOSING.addAll(gathering.closing);
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

	/** Registered device closing listeners. */
	public static List<Entry<DeviceClosingListener>> closing() {
		return Collections.unmodifiableList(CLOSING);
	}

	/** Whether any add-on registered anything, so that callers can skip their work outright. */
	public static boolean any() {
		return !DEFINES.isEmpty() || !IMAGES.isEmpty() || !SOURCES.isEmpty() || !STAGES.isEmpty()
				|| !DISTANT.isEmpty() || !TERRAIN.isEmpty() || !CLOSING.isEmpty();
	}

	/**
	 * Tells every add-on that asked that the device is closing, in the order the listeners were
	 * registered, and then refuses every later call into any add-on.
	 * <p>
	 * Once: the second call, from wherever it comes, gives no notice and answers false. A listener
	 * that throws is cut off and logged like any other piece and the others are still told, the
	 * add-on's other pieces having no bearing on this one: a stage listener that was cut off
	 * earlier leaves its add-on's closing listener as it was, since that is the one that frees what
	 * the stage listener made.
	 *
	 * @return whether this call gave the notice
	 */
	public static synchronized boolean closeDevice(VulkanHandles handles) {
		if (noticed) {
			return false;
		}

		noticed = true;
		try {
			each(CLOSING, "handle the device closing", listener -> listener.deviceClosing(handles));
		} finally {
			ended = true;
		}

		return true;
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
	 * returned. False for an entry already cut off, and for every entry once the device closing
	 * notice has been given.
	 */
	public static <T> boolean call(Entry<T> entry, String what, Consumer<? super T> call) {
		if (entry.cutOff || ended) {
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
		CLOSING.clear();
		noticed = false;
		ended = false;
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
		private final List<Entry<DeviceClosingListener>> closing = new ArrayList<>();
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

		@Override
		public void closing(DeviceClosingListener listener) {
			closing.add(entry(listener));
		}

		private <T> Entry<T> entry(T piece) {
			if (done) {
				throw new IllegalStateException("Add-on " + addon + " registered after its register() returned");
			}

			return new Entry<>(addon, Objects.requireNonNull(piece, "piece"));
		}

		int count() {
			return defines.size() + images.size() + sources.size() + stages.size() + distant.size()
					+ terrain.size() + closing.size();
		}
	}
}
