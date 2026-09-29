package dev.vitrail.fabric;

import dev.vitrail.api.VitrailAddon;
import dev.vitrail.platform.VitrailPlatform;
import dev.vitrail.Vitrail;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class FabricPlatform implements VitrailPlatform {

	@Override
	public String loaderName() {
		return "Fabric";
	}

	@Override
	public String loaderVersion() {
		return versionOf("fabricloader");
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
		return FabricLoader.getInstance().getGameDir();
	}

	@Override
	public boolean isModLoaded(String modId) {
		return FabricLoader.getInstance().isModLoaded(modId);
	}

	/**
	 * Each declaration of the {@code "vitrail"} entrypoint, created one by one: a container is only
	 * a promise until {@code getEntrypoint} runs the constructor, so one add-on whose class fails
	 * to load costs that add-on and not the list.
	 */
	@Override
	public List<VitrailAddon> addons() {
		List<VitrailAddon> addons = new ArrayList<>();
		for (EntrypointContainer<VitrailAddon> container
				: FabricLoader.getInstance().getEntrypointContainers("vitrail", VitrailAddon.class)) {
			try {
				addons.add(container.getEntrypoint());
			} catch (RuntimeException | LinkageError failure) {
				Vitrail.logger().error("The Vitrail add-on of {} could not be created and is skipped",
						container.getProvider().getMetadata().getId(), failure);
			}
		}
		return addons;
	}

	private static String versionOf(String modId) {
		return FabricLoader.getInstance()
				.getModContainer(modId)
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("unknown");
	}
}
