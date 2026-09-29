package dev.vitrail.api;

/**
 * A mod that feeds the pack through Vitrail.
 * <p>
 * Found through the loader rather than looked up by name, so that Vitrail knows no add-on and an
 * add-on needs no class of Vitrail's beyond this package: the Fabric entrypoint {@code "vitrail"},
 * or a {@code META-INF/services/dev.vitrail.api.VitrailAddon} provider on NeoForge. A mod that
 * only suggests Vitrail can declare one safely, because nothing loads the class unless Vitrail is
 * there to ask for it.
 */
public interface VitrailAddon {

	/** The add-on's mod id, which is what every log line about it names. */
	String id();

	/**
	 * Hands Vitrail the pieces this add-on provides.
	 * <p>
	 * Called once, from the loader's client entry point, which on Fabric is inside the game's
	 * constructor: the graphics device is not up yet and no option has been read, so this only
	 * registers and must not look at the game. What it registers is called later, on the render
	 * thread. If it throws, nothing it registered is kept.
	 */
	void register(AddonRegistrar registrar);
}
