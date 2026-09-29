package dev.vitrail.platform;

import dev.vitrail.api.VitrailAddon;

import java.nio.file.Path;
import java.util.List;

/**
 * The whole surface the loader modules have to implement. Kept deliberately small: if a
 * new method shows up here it should be because the common code genuinely cannot answer
 * the question on its own.
 */
public interface VitrailPlatform {

	String loaderName();

	String loaderVersion();

	String modVersion();

	String minecraftVersion();

	/** Root of the game instance, where the user-editable shader sources live. */
	Path gameDirectory();

	boolean isModLoaded(String modId);

	/**
	 * The add-ons other mods declare where this loader looks for them, in the loader's order. One
	 * that cannot even be created is logged and left out rather than failing the list.
	 */
	List<VitrailAddon> addons();
}
