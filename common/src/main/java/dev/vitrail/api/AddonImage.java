package dev.vitrail.api;

/**
 * A Vulkan image as it crosses between Vitrail and an add-on: the view to bind and what a
 * descriptor needs to know about it.
 * <p>
 * The owner keeps the image alive while the frame that bound it is in flight; the image layout
 * both sides agree on is the one the stage that hands it over documents.
 *
 * @param view the {@code VkImageView}
 * @param image the {@code VkImage} behind the view, for barriers
 * @param format the {@code VkFormat}
 * @param width width in pixels of the view's first level
 * @param height height in pixels of the view's first level
 * @param storage whether the view may be bound as a storage image as well as sampled
 */
public record AddonImage(long view, long image, int format, int width, int height, boolean storage) {
}
