package dev.vitrail.mixin.game;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.vitrail.render.GraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import org.jspecify.annotations.Nullable;

/**
 * Where 26.3 empties its pipelines at a resource reload, and the two caches it looks pipelines up
 * in.
 * <p>
 * 26.2 emptied the device's one cache in {@code clearPipelineCache}, and the engine's pipelines,
 * which lived in that same cache, went with it. 26.3 builds a new cache at every resource load and
 * swaps it in here, closing the old one; the engine's pipelines live in {@code GraphicsApi}'s own
 * map instead, so this is where that map is emptied too, with the live pack carried over as on
 * 26.2. The two caches themselves are reached through {@link RenderSystemAccessor}.
 */
@Mixin(RenderSystem.class)
public abstract class RenderSystemMixin {

	@Inject(method = "setCurrentPipelineCache", at = @At("HEAD"), require = 1)
	private static void vitrail$purge(PipelineCache cache,
			CallbackInfoReturnable<@Nullable PipelineCache> callback) {
		GraphicsApi.purge();
	}
}
