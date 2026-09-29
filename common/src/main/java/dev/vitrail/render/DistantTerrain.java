package dev.vitrail.render;

import dev.vitrail.dh.DhDepth;
import dev.vitrail.dh.DhLods;

import org.joml.Vector2f;

/**
 * Where the engine reads its far terrain from.
 * <p>
 * The far terrain has one source, Distant Horizons, and this is the one place the rest of the engine
 * asks about it: {@code PackDefines} whether there is any, {@code DistantProgram} whether its
 * programs are worth compiling ahead, {@code PackChain} to put the engine in DH's place and take it
 * out again, and {@code FrameState} for the numbers a frame publishes. Every answer is
 * {@link DhDepth}'s or {@link DhLods}'. It is a facade so that a second kind of source can stand
 * behind the same questions without any of those callers learning it exists.
 * <p>
 * <strong>The reads a frame takes about the far terrain are one reading and not four
 * questions.</strong> {@link #reading} settles the source once, and the distance, the z row and
 * whether the answers still hold all come from it, so that a source that stops answering half way
 * through cannot hand one frame answers of two minds. {@code render/FrameState} takes them in that
 * order and publishes them together.
 */
public final class DistantTerrain {

	/**
	 * What one frame asks of the source it settled on, in the order it asks it. The answers are taken
	 * one after another and published together; {@link #coherent} is asked after all of them and says
	 * whether any of them could have been given by a source that then stopped answering, in which
	 * case the frame takes the whole fallback.
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

	private DistantTerrain() {
	}

	/**
	 * Whether there is far terrain to serve the pack: Distant Horizons answering with its rendering
	 * on. This is what {@code DISTANT_HORIZONS} is defined on, read live so that a flip reads the
	 * pack again.
	 */
	public static boolean present() {
		return DhDepth.present();
	}

	/**
	 * Whether the far terrain's programs are worth compiling ahead: DH's far terrain is taken out of
	 * it. May be asked off the render thread.
	 */
	public static boolean drawable() {
		return DhLods.usable();
	}

	/** The source this frame's reads go to, settled once. */
	public static Reading reading() {
		return DISTANT_HORIZONS;
	}

	/**
	 * The two clip planes a volume's z row stands for.
	 *
	 * @see DhDepth#planes
	 */
	public static boolean planes(float scale, float offset, Vector2f dest) {
		return DhDepth.planes(scale, offset, dest);
	}

	/**
	 * Puts the engine in Distant Horizons' place for its far terrain, and keeps it there, every frame
	 * a pack is drawn.
	 */
	public static void install() {
		DhLods.install();
	}

	/** Hands Distant Horizons its far terrain back because no pack is drawn any more. */
	public static void handBack() {
		DhLods.handBack();
	}
}
