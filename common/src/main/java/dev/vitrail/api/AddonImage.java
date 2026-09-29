package dev.vitrail.api;

/**
 * A Vulkan image as it crosses between Vitrail and an add-on: the view to bind and what a
 * descriptor needs to know about it.
 * <p>
 * The owner keeps the image alive while the frame that bound it is in flight, and the image is in
 * {@code VK_IMAGE_LAYOUT_GENERAL} whenever it changes hands, in either direction: the layout of
 * one an add-on serves is set out in {@link ImageSource}, and the ones a {@link FrameContext}
 * hands the add-on are in it and are to be left in it.
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
