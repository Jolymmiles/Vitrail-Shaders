package dev.vitrail.render;

import dev.vitrail.Vitrail;
import dev.vitrail.addon.FarSource;
import dev.vitrail.addon.FarSources;
import dev.vitrail.api.DistantFrame;
import dev.vitrail.dh.DhDepth;
import dev.vitrail.dh.DhLods;
import dev.vitrail.render.timing.PassTimings;

import com.mojang.blaze3d.GpuDeviceLossException;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.world.phys.Vec3;

import org.joml.Vector2f;
import org.jspecify.annotations.Nullable;

/**
 * Where the engine reads its far terrain from, whichever source that is: Distant Horizons, or a
 * source an add-on registered, and nothing at all.
 * <p>
 * <strong>Distant Horizons is the source whenever it is present.</strong> Every answer is
 * {@link DhDepth}'s and every draw is {@link DhLods}', and a session without an add-on source never
 * reaches any of the add-on code: what decides it is a list being empty, and the DH read the frame
 * takes anyway. Only when DH is not present does the first add-on source that is take its place,
 * and then the same questions are put to it through the same reading.
 * <p>
 * <strong>The reads a frame takes about the far terrain are one reading and not four
 * questions.</strong> {@link #reading} settles the source once, and the distance, the z row and
 * whether the answers still hold all come from that source, so that a source which stops answering
 * half way through, or flips from present to absent between two of the reads, cannot hand one frame
 * answers of two minds. {@code render/FrameState} takes them in that order and publishes them
 * together.
 * <p>
 * <strong>An add-on source is driven from three points of the frame, and the game is hooked at
 * none of its own.</strong> {@link #beginFrame} runs at the head of the level frame, before any pass
 * has been recorded: it copies what the add-on has handed over to the GPU, and asks the source for
 * the frame's sections. {@link #drawOpaque} runs just before the game's opaque terrain and
 * {@link #drawWater} once the game's translucent features are down and its translucent terrain is
 * next, which are the two places Distant Horizons' own draw is made from, so the far terrain lands
 * in the frame exactly where DH's does. The light's halves are drawn at the tail of the frame from
 * the lists this fed to {@link DistantDraw}, and what the pack is handed of the far terrain's depth
 * is taken from the image those draws leave, all of it as for DH.
 */
public final class DistantTerrain {

	/**
	 * What one frame asks of the source it settled on, in the order it asks it. The three answers
	 * are taken one after another and published together; {@link #coherent} is asked after all of
	 * them and says whether any of them could have been given by a source that then stopped
	 * answering, in which case the frame takes the whole fallback.
	 */
	public interface Reading {

		/** How far the far terrain reaches in blocks, or -1 for the game's own distance to stand in. */
		int renderDistanceBlocks();

		/**
		 * The z row of the far terrain's volume as its scale and its offset, or false while there is
		 * none, leaving {@code dest} alone.
		 */
		boolean zRow(Vector2f dest);

		/** Whether the source is still being read at all, which is a question about latches. */
		boolean coherent();
	}

	/** Distant Horizons' own reads: {@link DhDepth}'s calls, in the order a frame asks them. */
	private static final Reading DISTANT_HORIZONS = new Reading() {
		@Override
		public int renderDistanceBlocks() {
			return DhDepth.renderDistanceBlocks();
		}

		@Override
		public boolean zRow(Vector2f dest) {
			return DhDepth.zRow(dest);
		}

		@Override
		public boolean coherent() {
			return DhDepth.usable();
		}
	};

	private record SourceReading(FarSource source) implements Reading {

		@Override
		public int renderDistanceBlocks() {
			return this.source.renderDistanceBlocks();
		}

		@Override
		public boolean zRow(Vector2f dest) {
			return this.source.zRow(dest);
		}

		@Override
		public boolean coherent() {
			return !this.source.cutOff();
		}
	}

	/**
	 * Whether an add-on source had far terrain the last time it was asked, volatile for the reader
	 * off the render thread, {@code DistantProgram.warmAhead}: a stale answer there costs a skipped
	 * or a wasted warm-up, never an image.
	 */
	private static volatile boolean addonPresent;

	/** What this frame's source listed, from {@link #beginFrame} until the water half is drawn. */
	private static FarSource.@Nullable Lists lists;

	/** Said once: the copy of an add-on's meshes to the GPU failed. */
	private static boolean uploadSaid;

	private DistantTerrain() {
	}

	/**
	 * Whether there is far terrain to serve the pack: Distant Horizons answering with its rendering
	 * on, or an add-on source that is present. This is what {@code DISTANT_HORIZONS} is defined on,
	 * read live so that a flip of either reads the pack again.
	 */
	public static boolean present() {
		return DhDepth.present() || selected(false) != null;
	}

	/**
	 * Whether the far terrain's programs are worth compiling ahead: DH's far terrain is taken out of
	 * it, or an add-on source has far terrain to draw. May be asked off the render thread, and
	 * answers what the render thread last saw.
	 */
	public static boolean drawable() {
		return DhLods.usable() || addonPresent;
	}

	/**
	 * The source this frame's reads go to, settled once. Distant Horizons' own, which is what also
	 * answers when nothing is present, unless it is not present and an add-on source is.
	 */
	public static Reading reading() {
		if (!FarSources.any()) {
			return DISTANT_HORIZONS;
		}

		FarSource source = selected(DhDepth.present());

		return source == null ? DISTANT_HORIZONS : new SourceReading(source);
	}

	/**
	 * The two clip planes a volume's z row stands for, which is the arithmetic that undoes the one
	 * {@code FarSource.zRow} makes and the one that turns DH's own row into the two planes it
	 * published.
	 *
	 * @see DhDepth#planes
	 */
	public static boolean planes(float scale, float offset, Vector2f dest) {
		return DhDepth.planes(scale, offset, dest);
	}

	/**
	 * Puts the engine in Distant Horizons' place for its far terrain, and keeps it there, every frame
	 * a pack is drawn. An add-on source has no renderer to stand in front of, so this is DH's alone,
	 * and it is made whichever source draws: a DH that is installed and not drawing is left as it is.
	 */
	public static void install() {
		DhLods.install();
	}

	/** Hands Distant Horizons its far terrain back because no pack is drawn any more. */
	public static void handBack() {
		DhLods.handBack();
	}

	/**
	 * At the head of the level frame: copies what add-ons have handed over to the GPU and gives back
	 * what they freed, and, when an add-on source is the one drawing, asks it for the frame.
	 * <p>
	 * The copies are made whether or not a pack draws, and whichever source is drawing: what waits
	 * is a copy of the add-on's bytes in memory, and it must not pile up because a pack serves no
	 * far terrain. The source is asked only when the pack really draws one, which is a pack that
	 * serves the camera's two halves of it, and only while the source says it can. Its answer is
	 * kept for the draws that follow and the light's lists are handed to {@link DistantDraw} now,
	 * for the frame's own close to take over.
	 *
	 * @param camera the game's camera, which the frame is drawn against
	 */
	public static void beginFrame(Vec3 camera) {
		lists = null;
		if (!FarSources.any()) {
			return;
		}

		GpuDevice device = RenderSystem.tryGetDevice();
		if (device == null) {
			return;
		}

		try {
			FarSources.drain(new DistantUploads(device));
		} catch (GpuDeviceLossException e) {
			throw e;
		} catch (RuntimeException e) {
			if (!uploadSaid) {
				uploadSaid = true;
				Vitrail.logger().warn("Copying an add-on's far terrain mesh to the GPU failed, so that mesh "
						+ "is never drawn; later ones are tried as usual", e);
			}
		}

		FarSource source = selected(DhDepth.present());
		if (source == null || !DistantDraw.drawsFarTerrain() || !source.usable()) {
			return;
		}

		FarSource.Lists frame = source.frame(
				new DistantFrame(camera.x, camera.y, camera.z, DistantDraw.drawsShadows()));
		PassTimings.censusFarSections(frame.opaque().size() + frame.water().size());
		DistantDraw.feedShadow(frame.shadowOpaque(), frame.shadowWater());
		lists = frame;
	}

	/**
	 * Just before the game's opaque terrain: draws the add-on's opaque half. Nothing when Distant
	 * Horizons is the source, whose own draw is made from this very point by a hook of its own.
	 */
	public static void drawOpaque() {
		FarSource.Lists frame = lists;
		if (frame != null) {
			DistantDraw.draw(true, frame.opaque());
		}
	}

	/**
	 * Once the game's translucent features are down and its translucent terrain is next: draws the
	 * add-on's water half, which ends the frame's use of the lists.
	 */
	public static void drawWater() {
		FarSource.Lists frame = lists;
		lists = null;
		if (frame != null) {
			DistantDraw.draw(false, frame.water());
		}
	}

	/**
	 * Gives every mesh of every add-on back, at the end of the session while the device is alive:
	 * the meshes belong to the add-ons and outlive every pack, so nothing but the end of the session
	 * releases them for good.
	 */
	public static void close() {
		GpuDevice device = RenderSystem.tryGetDevice();
		if (device != null) {
			FarSources.close(new DistantUploads(device));
		}
	}

	/**
	 * The add-on source that draws, or null. Distant Horizons being present is the caller's answer to
	 * hand in, because the reading needs it once and the definition of present needs it on its own.
	 */
	private static @Nullable FarSource selected(boolean distantHorizonsPresent) {
		if (!FarSources.any()) {
			return null;
		}

		FarSource source = FarSources.select(distantHorizonsPresent);
		if (!distantHorizonsPresent) {
			addonPresent = source != null;
		}

		return source;
	}
}
