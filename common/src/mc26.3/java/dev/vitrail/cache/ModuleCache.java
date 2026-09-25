package dev.vitrail.cache;

import dev.vitrail.render.PackNames;
import dev.vitrail.render.RawLocals;
import dev.vitrail.render.SamplerReach;
import dev.vitrail.Vitrail;

import com.mojang.renderpearl.backend.api.SpvModule;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The disk store of compiled shader modules, as far as it goes on Minecraft 26.3.
 * <p>
 * <strong>The 26.3 half keeps nothing on disk yet, and says so.</strong> The 26.2 store keeps each
 * module with the reflection SPIRV-Cross read off it, serialised through the records of the game's
 * {@code IntermediaryShaderModule}, and serves both back so a warm load runs neither shaderc nor the
 * reflection. 26.3 removed those records: its module is the SPIR-V alone, reflected on demand by the
 * pipeline builder. A store for that shape is a smaller thing than the 26.2 one and it is not
 * written yet, so every unit compiles at every load, which is what this engine did before the store
 * existed. {@link #keyOf} answers null, which is what every caller already reads as "no store".
 * <p>
 * What stays is everything outside the store that other code reads: the ceiling a player sets in
 * the video settings, kept in the same file and on the same scale so the setting survives a trip
 * between the two games, and the count of units built, which {@link #say} reports at the quiet
 * moment after a load together with what the SPIR-V passes did to them.
 */
public final class ModuleCache {

	/** The smallest ceiling the setting offers, in mebibytes. */
	public static final int MIN_CEILING_MIB = 128;

	/** The largest ceiling the setting offers, in mebibytes. */
	public static final int MAX_CEILING_MIB = 2048;

	/** The ceiling where a player has set none, in mebibytes. */
	public static final int DEFAULT_CEILING_MIB = 512;

	/** The step between two ceilings the setting offers, in mebibytes. */
	public static final int CEILING_STEP_MIB = 128;

	/** Where the ceiling is kept, under the mod's folder in the game directory. */
	private static final String CEILING_FILE = "module-cache-ceiling.txt";

	/** How long after the last unit a load counts as settled, which is when {@link #say} speaks. */
	private static final long QUIET_NANOS = 2_000_000_000L;

	private static final AtomicLong COMPILED = new AtomicLong();
	private static final AtomicLong COMPILED_SINCE_LAUNCH = new AtomicLong();
	private static final Object LOCK = new Object();

	private static volatile int ceilingMib = DEFAULT_CEILING_MIB;
	private static volatile boolean ceilingLoaded;
	private static volatile long lastUnitNanos;

	private ModuleCache() {
	}

	/** The ceiling the player set, or the default. */
	public static int ceilingMib() {
		if (!ceilingLoaded) {
			synchronized (LOCK) {
				if (!ceilingLoaded) {
					ceilingMib = readCeiling();
					ceilingLoaded = true;
				}
			}
		}

		return ceilingMib;
	}

	/** Sets and keeps the ceiling, snapped to the setting's steps. */
	public static void setCeilingMib(int mib) {
		int asked = snapCeiling(mib);
		try {
			Path file = ceilingFile();
			Files.createDirectories(file.getParent());
			Files.writeString(file, asked + "\n", StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException e) {
			Vitrail.logger().error("Vitrail could not write the module cache ceiling to vitrail/{}",
					CEILING_FILE, e);
		}

		ceilingMib = asked;
		ceilingLoaded = true;
	}

	private static int readCeiling() {
		try {
			Path file = ceilingFile();
			if (!Files.isRegularFile(file)) {
				return DEFAULT_CEILING_MIB;
			}

			return snapCeiling(Integer.parseInt(Files.readString(file, StandardCharsets.UTF_8)
					.trim()));
		} catch (NumberFormatException e) {
			Vitrail.logger().warn("vitrail/{} is not a size in mebibytes, so the default {} is used",
					CEILING_FILE, DEFAULT_CEILING_MIB);
			return DEFAULT_CEILING_MIB;
		} catch (IOException | RuntimeException e) {
			return DEFAULT_CEILING_MIB;
		}
	}

	private static Path ceilingFile() {
		return Vitrail.platform().gameDirectory().resolve(Vitrail.MOD_ID).resolve(CEILING_FILE);
	}

	private static int snapCeiling(int asked) {
		int clamped = Math.clamp(asked, MIN_CEILING_MIB, MAX_CEILING_MIB);
		int steps = (clamped - MIN_CEILING_MIB + CEILING_STEP_MIB / 2) / CEILING_STEP_MIB;
		return Math.clamp(MIN_CEILING_MIB + steps * CEILING_STEP_MIB, MIN_CEILING_MIB,
				MAX_CEILING_MIB);
	}

	/**
	 * The key a unit would be kept under. Null on this game, which keeps nothing: every caller
	 * reads null as "no store" and builds the unit.
	 */
	public static @Nullable String keyOf(String source, String stage) {
		return null;
	}

	/** A kept module for {@code key}, which this game never has. */
	public static @Nullable SpvModule lookup(@Nullable String key, String filename) {
		return null;
	}

	/** Counts one unit the compiler is building, for {@link #say}. */
	public static void building(String filename) {
		COMPILED.incrementAndGet();
		COMPILED_SINCE_LAUNCH.incrementAndGet();
		lastUnitNanos = System.nanoTime();
	}

	/** Keeps a built module under {@code key}, which this game does not do. */
	public static void store(@Nullable String key, SpvModule module) {
		// Nothing kept; see the class comment.
	}

	/** Reports the units built since the last report, once a load has settled. */
	public static void say() {
		if (COMPILED.get() == 0L || System.nanoTime() - lastUnitNanos < QUIET_NANOS) {
			return;
		}

		long built = COMPILED.getAndSet(0L);
		Vitrail.logger().info("Module cache not kept on Minecraft 26.3 yet, so all {} units of this "
				+ "load were compiled ({} since this launch)", built, COMPILED_SINCE_LAUNCH.get());
		RawLocals.say(built);
		PackNames.say(built);
		SamplerReach.say(built);
	}
}
