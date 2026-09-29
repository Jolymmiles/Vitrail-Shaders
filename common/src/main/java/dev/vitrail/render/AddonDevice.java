package dev.vitrail.render;

import dev.vitrail.addon.AddonRegistry;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import org.lwjgl.vulkan.VK10;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tells the add-ons that the game is about to destroy its Vulkan device, from the head of the
 * device's own {@code close}.
 * <p>
 * <strong>Why here and not at the end of the session.</strong> An add-on creates its own objects on
 * the game's device and its allocator, and both are destroyed inside that {@code close}: the
 * allocator right after the command encoder is, and the device after it. Anything an add-on holds
 * that is still alive then is a child outliving its parent, which the validation layers report as
 * a leak of the device. The head of {@code close} is the last moment the device and the allocator
 * are whole, on both games and both loaders, and the only one that is reached exactly once: the
 * session's own shutdown hooks stand earlier, before the game has finished with its renderer, and
 * differ between the loaders.
 * <p>
 * <strong>The device is waited idle first.</strong> The last frames are still in flight when the
 * game starts to close, and an add-on frees what they read. One wait for all of them, so that no
 * add-on needs one of its own and none can forget it.
 * <p>
 * A failure in here never stops the game closing: what the add-ons leave behind is a leak the
 * validation layers name, and what an exception here would leave behind is a device that is never
 * destroyed at all.
 */
public final class AddonDevice {

	private static final Logger LOGGER = LoggerFactory.getLogger("Vitrail");

	private AddonDevice() {
	}

	/**
	 * Waits for the device to go idle and tells the add-ons it is closing, once. Costs a player with
	 * no add-on a single read.
	 */
	public static void closing(VulkanDevice vulkan) {
		if (!AddonRegistry.any()) {
			return;
		}

		try {
			if (!AddonRegistry.closing().isEmpty()) {
				// The result is not read: a device that is already lost still has its children to
				// destroy, and the add-on is told either way.
				VK10.vkDeviceWaitIdle(vulkan.vkDevice());
			}

			AddonRegistry.closeDevice(AddonStages.handles(vulkan));
		} catch (RuntimeException failure) {
			LOGGER.error("The add-ons could not be told the device is closing", failure);
		}
	}
}
