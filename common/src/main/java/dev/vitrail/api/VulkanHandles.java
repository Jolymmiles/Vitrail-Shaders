package dev.vitrail.api;

/**
 * The game's Vulkan device as raw handles, for an add-on that creates its own objects on it or
 * hands them to another API.
 *
 * @param instance the {@code VkInstance}
 * @param physicalDevice the {@code VkPhysicalDevice}
 * @param device the {@code VkDevice}
 * @param allocator the game's {@code VmaAllocator}
 * @param graphicsQueue the {@code VkQueue} the frame is submitted on
 * @param graphicsQueueFamily that queue's family index
 */
public record VulkanHandles(
		long instance,
		long physicalDevice,
		long device,
		long allocator,
		long graphicsQueue,
		int graphicsQueueFamily) {
}
