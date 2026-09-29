package dev.vitrail.addon;

import dev.vitrail.api.VitrailAddon;

import java.util.List;

/**
 * Lets a test outside this package pose registered pieces without an add-on to find them.
 * <p>
 * An {@link AddonRegistry.Entry} can only be made here, and the session's own registry can only be
 * emptied here, which is deliberate for the code under test and a nuisance for the tests of what
 * reads from it.
 */
public final class RegisteredPieces {

	private RegisteredPieces() {
	}

	/** One registered piece as an add-on called {@code addon} would have registered it. */
	public static <T> AddonRegistry.Entry<T> of(String addon, T piece) {
		return new AddonRegistry.Entry<>(addon, piece);
	}

	/** Registers add-ons into the session's own registry, as the loader does at startup. */
	public static void load(VitrailAddon... addons) {
		AddonRegistry.load(List.of(addons));
	}

	/** Empties the session's registry again; a test that loaded into it must call this. */
	public static void forget() {
		AddonRegistry.clear();
	}
}
