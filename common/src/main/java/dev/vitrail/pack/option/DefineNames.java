package dev.vitrail.pack.option;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Which names another party may pose beside {@link EngineDefines}'s symbols, and what they may
 * be set to.
 * <p>
 * A pack reads {@code MC_VERSION}, {@code IRIS_VERSION} or {@code DISTANT_HORIZONS} as a fact about
 * the engine it runs on, so an extra symbol must never be able to say something else about them.
 * Refusing only what the table holds at this moment would leave the gap that matters: the symbols
 * the engine withholds where a capability is missing are exactly the ones that mean "not here" by
 * being absent, and an extra one posing them would turn the absence into a lie.
 */
public final class DefineNames {

	/**
	 * Families the machine chooses one member of. Which member is posed is a fact about this
	 * machine, so every member is the engine's, posed or not: {@code MC_OS_LINUX} on a Windows
	 * machine says the same thing a wrong {@code MC_VERSION} does.
	 */
	private static final List<String> FAMILIES = List.of(
			"MC_OS_", "MC_GL_VENDOR_", "MC_GL_RENDERER_", "MC_TEXTURE_FORMAT_");

	private DefineNames() {
	}

	/**
	 * Whether a name is one the preprocessor reads as an identifier: a letter or an underscore, then
	 * letters, digits and underscores, all ASCII. A name outside it would be written into the head
	 * of a program as a {@code #define} that no compiler accepts, and take that program with it.
	 */
	public static boolean valid(String name) {
		if (name == null || name.isEmpty()) {
			return false;
		}

		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			boolean letter = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_';
			if (!letter && !(i > 0 && c >= '0' && c <= '9')) {
				return false;
			}
		}

		return true;
	}

	/**
	 * Whether a value can stand after its name on one {@code #define} line. A line break in it
	 * would end the directive early and leave the rest as a line of the program, which is how a
	 * value would smuggle in code.
	 */
	public static boolean validValue(String value) {
		return value != null && value.indexOf('\n') < 0 && value.indexOf('\r') < 0;
	}

	/**
	 * The names that are the engine's on this machine: everything its table holds, everything it
	 * would hold with each capability it can withhold present, and every member of the families
	 * the machine chooses between.
	 */
	public static Predicate<String> reservedIn(EngineDefines.Environment environment) {
		EngineDefines.Environment plain = environment.withAddonDefines(Map.of());
		EngineDefines.Environment everything = new EngineDefines.Environment(plain.mcVersion(),
				plain.os(), plain.vendorName(), plain.rendererName(), plain.mipmapLevel(), true,
				plain.biomes(), plain.biomeCategories(), plain.textureFormat(), true);

		Set<String> posed = new HashSet<>(EngineDefines.table(plain).keySet());
		posed.addAll(EngineDefines.table(everything).keySet());

		return name -> posed.contains(name) || FAMILIES.stream().anyMatch(name::startsWith);
	}
}
