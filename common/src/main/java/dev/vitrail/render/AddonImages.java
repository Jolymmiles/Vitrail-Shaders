package dev.vitrail.render;

import dev.vitrail.Vitrail;
import dev.vitrail.addon.AddonImageNames;
import dev.vitrail.addon.AddonRegistry;
import dev.vitrail.api.AddonImage;
import dev.vitrail.api.ImageSource;
import dev.vitrail.render.storage.StorageImages;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

/**
 * What a descriptor push binds under a name an add-on's {@link ImageSource} serves, for the
 * passes the game records and for the pack's own compute dispatches alike.
 * <p>
 * Asked where the push resolves the entry's name, next to the pack's own storage images, and
 * answered in the same shape so that the mixins that swap the view, drop the sampler and type the
 * descriptor as a storage image need no second road. The source is asked for every push of the
 * name, not once a frame: a push is the one moment that is known to be inside the pass that will
 * read the image.
 * <p>
 * <strong>Every served name gets an answer, and that is the rule this class exists to keep.</strong>
 * A source that has nothing this frame, throws, or hands over an image the shader cannot use is
 * answered with a stand-in the size of the screen, and never with the one texel the pass bound as
 * its placeholder: packs read these names with {@code texelFetch} at any pixel of the screen, and
 * a texel that does not exist at that coordinate reads whatever the driver likes. A name the
 * shader declares as a storage image gets a storage stand-in, because a descriptor of the wrong
 * type for its layout is not a black picture but a lost device. {@link AddonStandIns} allocates
 * both.
 */
public final class AddonImages {

	/** The {@code VkImageView} of the stand-in a sampler reads, or 0 while none is allocated. */
	private static volatile long black;

	/** The same for a storage image the pack writes: its own, so that no read sees the writes. */
	private static volatile long scratch;

	/** The names whose answer was refused, said once each so that a frame does not repeat it. */
	private static final Set<String> SAID = ConcurrentHashMap.newKeySet();

	private AddonImages() {
	}

	/** Published by {@link AddonStandIns} as it allocates and releases the images. */
	static void standIns(long blackView, long scratchView) {
		black = blackView;
		scratch = scratchView;
	}

	/**
	 * The view to bind for a name, or null when no add-on serves it. Never null for a served
	 * name unless the stand-in it needs is not allocated, which is the frames before the first
	 * {@link ColorTargets#ensure} and the device that cannot store into the format.
	 * <p>
	 * {@code storage} in the answer is what the shader declares, not what the source offers: the
	 * layout of the bind group was made from the declaration and the descriptor must agree with it.
	 */
	public static StorageImages.@Nullable Bound bound(String name) {
		if (!AddonImageNames.active()) {
			return null;
		}

		AddonRegistry.Entry<ImageSource> owner = AddonImageNames.owner(name);
		if (owner == null) {
			return null;
		}

		boolean storage = AddonImageNames.storageBinding(name);
		AddonImage[] answer = new AddonImage[1];
		AddonRegistry.call(owner, "serve an image", source -> answer[0] = source.image(name));
		AddonImage image = answer[0];
		if (image != null) {
			String refusal = refusal(image, storage);
			if (refusal != null && SAID.add(name)) {
				Vitrail.logger().warn("Add-on {} answered {} with an image that {}, so the "
						+ "screen-sized stand-in is bound instead", owner.addon(), name, refusal);
			}
		}

		return choose(image, storage, black, scratch);
	}

	/**
	 * What is bound for an answer: the image where it can be bound as the shader declares it, and
	 * else the stand-in of the declared kind, which is null only while that one is not allocated.
	 */
	static StorageImages.@Nullable Bound choose(@Nullable AddonImage image, boolean storage,
			long blackView, long scratchView) {
		if (image != null && refusal(image, storage) == null) {
			return new StorageImages.Bound(image.view(), storage, false);
		}

		long standIn = storage ? scratchView : blackView;

		return standIn == 0L ? null : new StorageImages.Bound(standIn, storage, false);
	}

	/** Why the image cannot be bound as declared, in words that follow "an image that", or null. */
	static @Nullable String refusal(AddonImage image, boolean storage) {
		if (image.view() == 0L) {
			return "has no view";
		}

		if (image.width() <= 0 || image.height() <= 0) {
			return "has no extent";
		}

		if (storage && !image.storage()) {
			return "cannot be stored into, and the pack declares the name as a storage image";
		}

		return null;
	}

	/** Forgets what was said, for a pack that is loaded again. */
	static void forget() {
		SAID.clear();
	}
}
