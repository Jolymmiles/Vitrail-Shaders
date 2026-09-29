package dev.vitrail.addon;

/**
 * Lets a test outside this package put the registry and the far terrain sources built out of it
 * back as they were before anything was registered, which the two of them keep package private
 * because nothing but a test ever wants it.
 */
public final class AddonTestSupport {

	private AddonTestSupport() {
	}

	/** Forgets every registered add-on and every source built from them. */
	public static void forget() {
		AddonRegistry.clear();
		FarSources.reset();
	}
}
