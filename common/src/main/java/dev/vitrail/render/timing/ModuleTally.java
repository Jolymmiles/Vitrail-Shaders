package dev.vitrail.render.timing;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * How many of the programs a family built are the same text over again, and the words that say so.
 * The sums behind {@link ModuleCensus}, held apart for the reason {@link FrameTally} is.
 * <p>
 * A program is identified by what it hands the compiler: the text of its vertex stage, the text of
 * its fragment stage, and the layout of the mesh it reads. Two programs with all three equal are one
 * compile made twice, and two with only a stage equal are a module made twice. Texts are held as a
 * 64 bit hash and never as the text, so a tally over a pack of a few hundred programs costs a few
 * hundred longs instead of the megabytes the stages come to.
 * <p>
 * <strong>Synchronized, unlike the frame tally</strong>: the programs are built on the pack-load
 * workers, more than one at a time, and the report is read from the render thread and from the
 * worker that closes the warm-up.
 */
final class ModuleTally {

	/** The three things a program hands the compiler that decide whether it is a new one. */
	private record Triple(long vertex, long fragment, Object format) {
	}

	/** One family's programs, in the order they arrived. */
	private static final class Family {

		int programs;

		/** How many programs the last line printed for this family knew of. */
		int reported;

		final Set<Triple> triples = new HashSet<>();
		final Set<Long> vertices = new HashSet<>();
		final Set<Long> fragments = new HashSet<>();
	}

	private final Map<String, Family> families = new LinkedHashMap<>();

	/**
	 * A 64 bit FNV-1a over the characters of a text: no allocation, one pass, and wide enough that two
	 * different stages of one pack meeting in it is not a thing to plan for.
	 */
	static long hash(String text) {
		long hash = 0xcbf29ce484222325L;
		for (int at = 0; at < text.length(); at++) {
			hash ^= text.charAt(at);
			hash *= 0x100000001b3L;
		}

		return hash;
	}

	/**
	 * One program built.
	 *
	 * @param family   what the log calls the family, {@code entity} or {@code chunk}
	 * @param vertex   {@link #hash} of the vertex stage the compiler is handed
	 * @param fragment {@link #hash} of the fragment stage
	 * @param format   the mesh layout, compared by {@code equals}, or null for a family with none
	 */
	synchronized void built(String family, long vertex, long fragment, Object format) {
		Family tally = this.families.computeIfAbsent(family, name -> new Family());
		tally.programs++;
		tally.triples.add(new Triple(vertex, fragment, format));
		tally.vertices.add(vertex);
		tally.fragments.add(fragment);
	}

	/**
	 * A line for every family that has built programs since it was last said, in the order the
	 * families first appeared. A family whose count has not moved says nothing again, and one that
	 * gained a program says its whole tally over, the terrain being the one that does: its programs are
	 * made when the renderer first asks for its shader and not with the other six.
	 */
	synchronized List<String> lines() {
		List<String> lines = new ArrayList<>();
		for (Map.Entry<String, Family> entry : this.families.entrySet()) {
			Family family = entry.getValue();
			if (family.programs == family.reported) {
				continue;
			}

			family.reported = family.programs;
			lines.add(String.format(Locale.ROOT, "Module census, %s: programs built %d, distinct "
							+ "(vertex text, fragment text, vertex format) triples %d, so repeats %d; "
							+ "modules made %d, distinct vertex texts %d, distinct fragment texts %d",
					entry.getKey(), family.programs, family.triples.size(),
					family.programs - family.triples.size(), 2 * family.programs,
					family.vertices.size(), family.fragments.size()));
		}

		return lines;
	}

	/** How many distinct triples a family has built, for the tests. */
	synchronized int triples(String family) {
		Family tally = this.families.get(family);

		return tally == null ? 0 : tally.triples.size();
	}

	synchronized void clear() {
		this.families.clear();
	}
}
