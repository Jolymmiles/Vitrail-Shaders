package dev.vitrail.mixin.sodium;

import dev.vitrail.sodium.TerrainMeshEvents;

import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

/**
 * The instant at which add-ons are told what Sodium built.
 * <p>
 * The head of the public {@code uploadResults}, and not the private one it hands each region's
 * share to: this sees the whole batch that {@code processChunkBuilds} decided to upload, before any
 * region touches it, and the buffers in it are freed by {@code BuilderTaskOutput.destroy} straight
 * after that method returns. The two overloads share a name, so the descriptor is spelled out.
 * {@code TerrainMeshEvents} says why this point and not the end of the build.
 */
@Mixin(value = RenderRegionManager.class, remap = false)
public abstract class RenderRegionManagerMixin {

	@Inject(method = "uploadResults(Ljava/util/Collection;"
			+ "Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V",
			at = @At("HEAD"))
	private void vitrail$built(Collection<BuilderTaskOutput> results, UniformBufferManager uniforms,
			CallbackInfo callback) {
		TerrainMeshEvents.built(results);
	}
}
