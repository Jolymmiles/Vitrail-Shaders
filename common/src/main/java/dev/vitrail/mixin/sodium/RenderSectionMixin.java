package dev.vitrail.mixin.sodium;

import dev.vitrail.sodium.TerrainMeshEvents;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The instant at which add-ons are told a section is gone.
 * <p>
 * {@code delete} and not {@code RenderSectionManager.onSectionRemoved}, because it is the one road
 * every section leaves by: a chunk unloading goes through {@code onSectionRemoved}, and the
 * renderer being torn down, which is every dimension change and every rebuild of the world, goes
 * through {@code deleteAll} without it.
 * <p>
 * <strong>Once per section.</strong> A section removed while the storage is queueing stays in it
 * until the queue is flushed, and a teardown in between deletes it a second time; the disposed flag
 * that the first delete sets is what tells the second.
 */
@Mixin(value = RenderSection.class, remap = false)
public abstract class RenderSectionMixin {

	@Shadow
	public abstract boolean isDisposed();

	@Shadow
	public abstract int getChunkX();

	@Shadow
	public abstract int getChunkY();

	@Shadow
	public abstract int getChunkZ();

	@Inject(method = "delete", at = @At("HEAD"))
	private void vitrail$removed(CallbackInfo callback) {
		if (!this.isDisposed()) {
			TerrainMeshEvents.removed(this.getChunkX(), this.getChunkY(), this.getChunkZ());
		}
	}
}
