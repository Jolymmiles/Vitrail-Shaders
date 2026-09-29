package dev.vitrail.addon;

import dev.vitrail.api.StageListener;
import dev.vitrail.pack.model.ProgramNames;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The pack programs the stage listeners want to be called after, settled once for a load of the
 * pack and looked up by every pass of every frame.
 * <p>
 * Settled per load and not asked of the add-ons per frame, because the question a frame has for
 * every pass is "does anyone want this program", and the answer is a set of names. It is the
 * listeners' own {@link StageListener#programs()} joined to the programs the pack really runs,
 * which is why it is made where the pack's passes are built: a name the pack has no pass or
 * compute for is logged there and never enters the set, so a frame only ever finds names it can
 * act on. With no listener naming a program the set is empty and {@link #wants} answers false
 * before it looks at a name.
 */
public final class StagePrograms {

	private static final Logger LOGGER = LoggerFactory.getLogger("Vitrail");

	/** Nobody wants any program, which is what every player without such an add-on gets. */
	public static final StagePrograms NONE = new StagePrograms(Map.of());

	private final Map<String, List<AddonRegistry.Entry<StageListener>>> listeners;

	private StagePrograms(Map<String, List<AddonRegistry.Entry<StageListener>>> listeners) {
		this.listeners = listeners;
	}

	/**
	 * The name the frame knows a program a listener named by, or empty for a name that is no
	 * program the frame runs as a full screen pass. One name for every spelling of one program:
	 * the number-less and slot-nought forms agree ({@code deferred0} is {@code deferred}), and a
	 * compute file names the program it hangs off ({@code composite3_a} is {@code composite3}).
	 * The families are the ones {@code PackChain.drawRange} runs, {@code begin}, {@code prepare},
	 * {@code deferred}, {@code composite} and {@code final}; geometry, shadow and setup programs
	 * are not among them.
	 */
	public static Optional<String> nameOf(String named) {
		return ProgramNames.computeBase(named);
	}

	/**
	 * Asks every stage listener which programs it wants and keeps the ones the pack runs.
	 * <p>
	 * A listener that throws while answering is cut off like one that throws anywhere else. A name
	 * that is no program of the frame, and one the pack does not run, is logged with the add-on's
	 * id and dropped, once for this call.
	 *
	 * @param stages  the registered stage listeners, in registry order
	 * @param running the programs the pack's chain runs, by the bare names the pass and the
	 *                standalone computes carry
	 * @param pack    what a log line calls the pack by
	 */
	public static StagePrograms settle(List<AddonRegistry.Entry<StageListener>> stages,
			Collection<String> running, String pack) {
		Map<String, List<AddonRegistry.Entry<StageListener>>> settled = new LinkedHashMap<>();
		for (AddonRegistry.Entry<StageListener> entry : stages) {
			List<String> asked = new ArrayList<>();
			AddonRegistry.call(entry, "name the programs it is called after", listener -> {
				List<String> answer = new ArrayList<>();
				for (String name : Objects.requireNonNull(listener.programs(), "programs")) {
					answer.add(Objects.requireNonNull(name, "program name"));
				}

				asked.addAll(answer);
			});

			Set<String> wanted = new LinkedHashSet<>();
			for (String name : asked) {
				Optional<String> program = nameOf(name);
				if (program.isEmpty()) {
					LOGGER.warn("Add-on {} wants to be called after {}, which is no program of the "
							+ "pack's frame (begin, prepare, deferred, composite or final, numbered "
							+ "as the pack does), so it is never called for it", entry.addon(), name);
				} else if (!running.contains(program.get())) {
					LOGGER.info("Add-on {} wants to be called after {}, which {} does not run, so it "
							+ "is not called for it there", entry.addon(), program.get(), pack);
				} else {
					wanted.add(program.get());
				}
			}

			for (String program : wanted) {
				settled.computeIfAbsent(program, _ -> new ArrayList<>()).add(entry);
			}
		}

		if (settled.isEmpty()) {
			return NONE;
		}

		Map<String, List<AddonRegistry.Entry<StageListener>>> frozen = new LinkedHashMap<>();
		settled.forEach((program, entries) -> frozen.put(program, List.copyOf(entries)));

		return new StagePrograms(Collections.unmodifiableMap(frozen));
	}

	/**
	 * Whether a listener that has not been cut off wants this program: the one question a pass
	 * asks each frame. False at once when no program is wanted at all.
	 */
	public boolean wants(String program) {
		if (this.listeners.isEmpty()) {
			return false;
		}

		List<AddonRegistry.Entry<StageListener>> entries = this.listeners.get(program);
		if (entries == null) {
			return false;
		}

		for (AddonRegistry.Entry<StageListener> entry : entries) {
			if (!entry.cutOff()) {
				return true;
			}
		}

		return false;
	}

	/** The listeners to call after this program, in the order the registry holds them. */
	public List<AddonRegistry.Entry<StageListener>> listeners(String program) {
		List<AddonRegistry.Entry<StageListener>> entries = this.listeners.get(program);

		return entries == null ? List.of() : entries;
	}

	/** The programs some listener wants, for a log line and for tests. */
	public Set<String> programs() {
		return this.listeners.keySet();
	}
}
