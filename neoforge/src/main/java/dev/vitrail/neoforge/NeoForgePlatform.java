package dev.vitrail.neoforge;

import dev.vitrail.api.VitrailAddon;
import dev.vitrail.platform.VitrailPlatform;
import dev.vitrail.Vitrail;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

final class NeoForgePlatform implements VitrailPlatform {

	@Override
	public String loaderName() {
		return "NeoForge";
	}

	@Override
	public String loaderVersion() {
		return versionOf("neoforge");
	}

	@Override
	public String modVersion() {
		return versionOf(Vitrail.MOD_ID);
	}

	@Override
	public String minecraftVersion() {
		return versionOf("minecraft");
	}

	@Override
	public Path gameDirectory() {
		return FMLPaths.GAMEDIR.get();
	}

	@Override
	public boolean isModLoaded(String modId) {
		return ModList.get().isLoaded(modId);
	}

	/**
	 * Providers of {@code META-INF/services/dev.vitrail.api.VitrailAddon} visible to the loader of
	 * the API's own class, which on NeoForge is the loader every mod's classes come from. Each one
	 * is created on its own, so a provider that fails to load costs that provider and not the list.
	 */
	@Override
	public List<VitrailAddon> addons() {
		List<VitrailAddon> addons = new ArrayList<>();
		Iterator<VitrailAddon> providers = ServiceLoader.load(VitrailAddon.class, VitrailAddon.class.getClassLoader())
				.iterator();
		while (true) {
			try {
				if (!providers.hasNext()) {
					break;
				}
				addons.add(providers.next());
			} catch (ServiceConfigurationError | LinkageError failure) {
				Vitrail.logger().error("A Vitrail add-on could not be created and is skipped", failure);
			}
		}
		return addons;
	}

	private static String versionOf(String modId) {
		return ModList.get()
				.getModContainerById(modId)
				.map(container -> container.getModInfo().getVersion().toString())
				.orElse("unknown");
	}
}
