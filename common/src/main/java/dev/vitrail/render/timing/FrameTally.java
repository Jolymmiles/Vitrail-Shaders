package dev.vitrail.render.timing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The sums behind {@link FrameCensus}, and the words they are printed in.
 * <p>
 * Held apart from the hooks that feed them so that the arithmetic can be read without a game
 * running: which bind was redundant, which draw belongs to which family, how many programs wrote
 * their block twice, and what a window's averages come to are all answered here from plain calls.
 * The hooks own the switch and the family of a pipeline; this owns nothing but numbers.
 * <p>
 * <strong>One thread writes, the render thread.</strong> There is no fence anywhere in here, and the
 * reliance is the one the rest of the census stands on: the frame is recorded on one thread, and the
 * two callers that clear a tally are not frames. A descriptor pushed off that thread can lose a count
 * to the race, and nothing here is worth a fence to keep it.
 */
final class FrameTally {

	/** Where a bind or a draw is filed. The order is the order of the rows. */
	enum Family {
		ENTITY("entity"),
		TERRAIN("terrain"),
		PARTICLE("particle"),
		DISTANT("far terrain"),
		SKY("sky, weather and cloud"),
		CHAIN("chain passes"),
		OTHER("not this engine's");

		private final String label;

		Family(String label) {
			this.label = label;
		}

		/**
		 * The family a geometry program names itself by, {@code GeometryProgram.Pass.family}. A name
		 * this census has no row for lands in {@link #OTHER}, which reads as the game's own draws
		 * being heavier than they are and is the one place a renamed family would show.
		 */
		static Family named(String family) {
			return switch (family) {
				case "chunk" -> TERRAIN;
				case "entity" -> ENTITY;
				case "particles" -> PARTICLE;
				case "far terrain" -> DISTANT;
				case "sky", "weather", "cloud" -> SKY;
				default -> OTHER;
			};
		}
	}

	private static final int FAMILIES = Family.values().length;

	private final long[] binds = new long[FAMILIES];
	private final long[] redundant = new long[FAMILIES];
	private final long[] draws = new long[FAMILIES];

	/** The last pass a pipeline was set on, and what was set, so that a repeat can be told. */
	private Object lastPass;
	private Object lastBound;
	private Family lastFamily = Family.OTHER;

	private long pushes;
	private long allocatedSets;
	private long descriptors;
	private long programBinds;
	private long programKept;

	private long geometryWrites;
	private long geometryPrograms;
	private long geometryRewritten;
	private long chainWrites;
	private long farWrites;

	private long rotations;

	private long farCameraSections;
	private long farCameraPasses;
	private long farShadowSections;
	private long farShadowPasses;

	private long readyCalls;
	private long readyPrepared;
	private long readyDone;

	private long frames;

	/**
	 * Which frame is in progress, counted for good. A block remembers the frame it was last written in
	 * against this, so a window that is emptied at a report does not make every program look new.
	 */
	private long frameNumber;

	/**
	 * A pipeline set on a pass.
	 *
	 * @param pass     the pass, by identity
	 * @param pipeline what the pass now holds, by identity
	 * @param family   whose it is
	 */
	void bound(Object pass, Object pipeline, Family family) {
		int at = family.ordinal();
		this.binds[at]++;
		if (pass == this.lastPass && pipeline == this.lastBound) {
			this.redundant[at]++;
		}

		this.lastPass = pass;
		this.lastBound = pipeline;
		this.lastFamily = family;
	}

	/**
	 * A draw recorded into a pass, filed under the family of the pipeline that pass holds. A pass
	 * this tally has seen no bind on has none to go by, and that is a draw of somebody else's.
	 */
	void drawn(Object pass) {
		this.draws[(pass == this.lastPass ? this.lastFamily : Family.OTHER).ordinal()]++;
	}

	/** One descriptor set written for a draw, and whether it was bound as an allocated set instead. */
	void pushed(boolean allocated) {
		this.pushes++;
		if (allocated) {
			this.allocatedSets++;
		}
	}

	/** One descriptor of a push. */
	void described() {
		this.descriptors++;
	}

	void programBound() {
		this.programBinds++;
	}

	/** A program bound into a pass it was already standing in. */
	void programKept() {
		this.programKept++;
	}

	/** A geometry program's block, written. The block says whether this is its second time. */
	void geometryWritten(FrameCensus.Block block) {
		this.geometryWrites++;
		if (block.frame != this.frameNumber) {
			block.frame = this.frameNumber;
			block.writes = 0;
			this.geometryPrograms++;
		}

		if (++block.writes == 2) {
			this.geometryRewritten++;
		}
	}

	void chainWritten() {
		this.chainWrites++;
	}

	void farWritten() {
		this.farWrites++;
	}

	void rotated() {
		this.rotations++;
	}

	/** One section of the far terrain given a uniform of its own inside a pass. */
	void farSection(boolean shadow) {
		if (shadow) {
			this.farShadowSections++;
		} else {
			this.farCameraSections++;
		}
	}

	/** One pass of the far terrain that walks its sections. */
	void farPass(boolean shadow) {
		if (shadow) {
			this.farShadowPasses++;
		} else {
			this.farCameraPasses++;
		}
	}

	void readied() {
		this.readyCalls++;
	}

	void readyPrepared() {
		this.readyPrepared++;
	}

	void readyDone() {
		this.readyDone++;
	}

	/**
	 * The end of a frame: counted, and the pass forgotten. Holding the last pass past its frame would
	 * keep a closed one reachable, and a bind on the next frame's pass is a first bind whatever the
	 * pipeline is.
	 */
	void endFrame() {
		this.frames++;
		this.frameNumber++;
		this.lastPass = null;
		this.lastBound = null;
		this.lastFamily = Family.OTHER;
	}

	long frames() {
		return this.frames;
	}

	long binds(Family family) {
		return this.binds[family.ordinal()];
	}

	long redundant(Family family) {
		return this.redundant[family.ordinal()];
	}

	long draws(Family family) {
		return this.draws[family.ordinal()];
	}

	/** Empties the window and keeps the frame number, which only ever goes up. */
	void clear() {
		Arrays.fill(this.binds, 0L);
		Arrays.fill(this.redundant, 0L);
		Arrays.fill(this.draws, 0L);
		this.lastPass = null;
		this.lastBound = null;
		this.lastFamily = Family.OTHER;
		this.pushes = 0;
		this.allocatedSets = 0;
		this.descriptors = 0;
		this.programBinds = 0;
		this.programKept = 0;
		this.geometryWrites = 0;
		this.geometryPrograms = 0;
		this.geometryRewritten = 0;
		this.chainWrites = 0;
		this.farWrites = 0;
		this.rotations = 0;
		this.farCameraSections = 0;
		this.farCameraPasses = 0;
		this.farShadowSections = 0;
		this.farShadowPasses = 0;
		this.readyCalls = 0;
		this.readyPrepared = 0;
		this.readyDone = 0;
		this.frames = 0;
	}

	/**
	 * The window as lines of the log, every number an average over the frames in it. Empty for a
	 * window with no frame, which is nothing to average.
	 * <p>
	 * The shape is the pass table's: the figures first and the name last, so a column of them reads
	 * down, and a word in brackets where a good number is obvious.
	 *
	 * @param seconds how long the window was, which the header repeats
	 */
	List<String> lines(double seconds) {
		List<String> lines = new ArrayList<>();
		if (this.frames == 0) {
			return lines;
		}

		double frames = this.frames;
		lines.add(String.format(Locale.ROOT, "Frame census over %.1f s, %d frames, an average frame:",
				seconds, this.frames));
		lines.add(String.format(Locale.ROOT, "  %s pipeline binds, %s of them redundant (the pass "
						+ "already held that pipeline: 0 would be ideal), %s draws",
				per(sum(this.binds), frames), per(sum(this.redundant), frames),
				per(sum(this.draws), frames)));
		for (Family family : Family.values()) {
			int at = family.ordinal();
			if (this.binds[at] == 0 && this.draws[at] == 0) {
				continue;
			}

			// Terrain draws are Sodium's own commands, recorded straight into the command buffer and
			// never through the pass, so the pass has no count of them to give. A dash says that,
			// where a nought would say there were none.
			String drawn = family == Family.TERRAIN ? "       -" : column(this.draws[at], frames);
			lines.add(String.format(Locale.ROOT, "  %s binds %s redundant %s draws  %s",
					column(this.binds[at], frames), column(this.redundant[at], frames), drawn,
					family.label));
		}

		lines.add(String.format(Locale.ROOT, "  %s descriptor pushes, %s descriptors each, %s bound as "
						+ "an allocated set instead (a push a draw is the most it can be)",
				per(this.pushes, frames),
				this.pushes == 0 ? "0.0" : String.format(Locale.ROOT, "%.1f",
						this.descriptors / (double) this.pushes),
				per(this.allocatedSets, frames)));
		lines.add(String.format(Locale.ROOT, "  %s program binds, each one a uniform block and its "
						+ "samplers set on the pass, and %s more that found the program already "
						+ "standing in it and set only the images the draw brought",
				per(this.programBinds, frames), per(this.programKept, frames)));
		lines.add(String.format(Locale.ROOT, "  %s geometry block writes over %s programs, %s of them "
						+ "written more than once (0 would be ideal), %s chain block writes, %s far "
						+ "terrain block writes",
				per(this.geometryWrites, frames), per(this.geometryPrograms, frames),
				per(this.geometryRewritten, frames), per(this.chainWrites, frames),
				per(this.farWrites, frames)));
		lines.add(String.format(Locale.ROOT, "  %s ring rotations, each one a fence created",
				per(this.rotations, frames)));
		if (this.farCameraPasses + this.farShadowPasses > 0) {
			lines.add(String.format(Locale.ROOT, "  far terrain section uniforms, one descriptor push "
							+ "each: %s over %s camera passes, %s over %s shadow passes",
					per(this.farCameraSections, frames), per(this.farCameraPasses, frames),
					per(this.farShadowSections, frames), per(this.farShadowPasses, frames)));
		}

		lines.add(String.format(Locale.ROOT, "  %s PackChain.ready() calls, %s of them reaching the "
						+ "targets' prepare and %s handing a frame back (one full prepare would do)",
				per(this.readyCalls, frames), per(this.readyPrepared, frames),
				per(this.readyDone, frames)));

		return lines;
	}

	private static long sum(long[] counts) {
		long total = 0;
		for (long count : counts) {
			total += count;
		}

		return total;
	}

	private static String per(long count, double frames) {
		return String.format(Locale.ROOT, "%.1f", count / frames);
	}

	private static String column(long count, double frames) {
		return String.format(Locale.ROOT, "%8.1f", count / frames);
	}
}
