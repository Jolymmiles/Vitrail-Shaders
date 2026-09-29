package dev.vitrail.render.timing;

import dev.vitrail.Vitrail;

import org.jspecify.annotations.Nullable;

/**
 * How much of the module compilation a pack load pays for is the same text compiled again, printed
 * once a family at the load under {@code -Dvitrail.passTimings=N}.
 * <p>
 * A program's modules are named after its row, so two rows of a pack that translate to the same
 * stage still make two modules, and nothing says how often that happens. This says: per family, how
 * many programs were built and how many of them are distinct by what they hand the compiler, the
 * vertex text, the fragment text, the geometry text of a device that binds one and the mesh layout.
 * The gap between the two numbers is the most that a memo keyed on the text could take off the
 * compile, before the pipeline's own build, which it does not touch.
 * <p>
 * The text is the translated one {@code GeometryProgram} hands the compiler, hashed where it is
 * built and never kept. That costs a pass over every stage of the pack on the worker that builds it,
 * a fraction of a second at load, and is why nothing here runs with the switch off.
 * <p>
 * <strong>Said when the warm-up closes, and again wherever a family has grown since.</strong> Six of
 * the families are built by the pack-load worker before that, and the terrain is built when the
 * renderer first asks for its shader, which may be after: its line comes with the next pass timing
 * report, once, and so does any other family's that was still adding programs.
 */
public final class ModuleCensus {

	private static final boolean ENABLED = PassTimings.enabled();

	private static final ModuleTally TALLY = new ModuleTally();

	private ModuleCensus() {
	}

	/**
	 * One geometry program built, with what it hands the compiler. Called from the pack-load workers
	 * and from the render thread, whichever builds the program.
	 *
	 * @param family   what the log calls the family, as {@code GeometryProgram.Pass} has it
	 * @param vertex   the vertex stage's text as the compiler receives it
	 * @param fragment the fragment stage's text, or null for a program that has none to give, which
	 *                 hands the compiler nothing and is left out
	 * @param geometry the text of the geometry stage the device binds as a module of its own, or
	 *                 null where the program has none or the stage is folded into the fragment text
	 * @param format   the layout of the mesh it reads, or null for a family with no mesh
	 */
	public static void built(String family, String vertex, String fragment,
			@Nullable String geometry, Object format) {
		if (ENABLED && vertex != null && fragment != null) {
			TALLY.built(family, ModuleTally.hash(vertex), ModuleTally.hash(fragment),
					geometry == null ? 0L : ModuleTally.hash(geometry), format);
		}
	}

	/** Prints a line for every family that has built programs since it was last said. */
	public static void report() {
		if (!ENABLED) {
			return;
		}

		for (String line : TALLY.lines()) {
			Vitrail.logger().info("{}", line);
		}
	}

	/** A new pack is being read: the programs of the last one are not this one's. */
	static void reset() {
		if (ENABLED) {
			TALLY.clear();
		}
	}

	/** The census, for the tests that read it back. */
	static ModuleTally tally() {
		return TALLY;
	}
}
