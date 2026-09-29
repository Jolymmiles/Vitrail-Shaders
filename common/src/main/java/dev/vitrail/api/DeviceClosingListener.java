package dev.vitrail.api;

/**
 * Told that the game is about to destroy its Vulkan device, so that an add-on frees the objects it
 * created on it.
 * <p>
 * <strong>A pack reload is not a device close.</strong> Changing the shader pack, reloading the
 * resources with F3+T and the pack's own reload leave the device, its handles and everything the
 * add-on created on it standing, and announce nothing: images an add-on serves or records into
 * stay valid across all of them and are used by the next pack, so an add-on destroys none of them
 * because of a reload. What it derived from the pack it reads again from the calls it is asked
 * next, which come with every load: {@link SourcePatcher#appliesTo}, {@link DefineSource#write}
 * and {@link StageListener#programs}.
 * <p>
 * <strong>A device close is announced once and is final.</strong> The add-on destroys everything
 * it created on the device, in the order its own objects need, and does not call Vulkan with the
 * handles again. Nothing of the add-on is called afterwards: not a stage, not an image request.
 * <p>
 * It is not called where the process ends without the game closing its device, a crash or a
 * kill, in which the driver takes everything back.
 */
@FunctionalInterface
public interface DeviceClosingListener {

	/**
	 * Called once, on the render thread, from inside the game's own close of its device, before the
	 * game destroys its allocator and the device. Vitrail has waited for the device to be idle, so
	 * no frame reads what the add-on destroys and it needs no wait of its own.
	 * <p>
	 * What was allocated through {@link VulkanHandles#allocator()} has to be freed through it
	 * before this returns, as the allocator is destroyed right after, and every object created on
	 * {@link VulkanHandles#device()} has to be destroyed before the device is, which is also right
	 * after. Validation layers report each one left as a leak of the device.
	 *
	 * @param vulkan the handles of the device that is closing, the ones every
	 *               {@link FrameContext#vulkan()} answered
	 */
	void deviceClosing(VulkanHandles vulkan);
}
