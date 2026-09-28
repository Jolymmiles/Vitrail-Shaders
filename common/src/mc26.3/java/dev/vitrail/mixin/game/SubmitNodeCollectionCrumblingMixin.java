package dev.vitrail.mixin.game;

import dev.vitrail.render.PackChain;

import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.feature.phase.SimpleFeatureRenderPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Submits the cracks over a block being mined into the breaking overlay phase while a pack draws,
 * which is where 26.2 submitted all of them.
 * <p>
 * 26.3 sends the cracks over an opaque block to the solid phase instead, and keeps the breaking
 * overlay phase for a translucent one ({@code SubmitNodeCollection.submitBreakingBlockModel}, and
 * {@code submitCrumblingOverlay} for a block entity). The row that draws them with the pack's
 * {@code gbuffers_damagedblock} is bound to the translucent features, because the pipeline blends,
 * and it is there that the game executes the breaking overlay phase
 * ({@code FeatureRenderDispatcher.executeTranslucent}, after the translucent blocks and items). Met
 * in the solid features, the draw went back to the game, onto its own picture, which the pack's
 * image covers: every crack over stone, wood or dirt was gone, and the cracks over glass stayed.
 * <p>
 * The move comes with 26.3's order independent transparency, under which the breaking overlay
 * phase IS the order independent one ({@code SubmitNodeCollection}'s constructor), and 26.3 sends
 * only a translucent block's cracks through it, with a crumbling pipeline of its own for that pass
 * ({@code RenderPipelines.OIT_CRUMBLING}). While a pack draws that transparency is off
 * ({@code GameRendererTransparencyMixin}), so the phase this hands back is the plain one, executed
 * exactly where 26.2 executed it. Without a pack nothing changes.
 */
@Mixin(SubmitNodeCollection.class)
public abstract class SubmitNodeCollectionCrumblingMixin {

	@ModifyVariable(method = "submitBreakingBlockModel", at = @At("STORE"), require = 1)
	private SimpleFeatureRenderPhase vitrail$breakingBlock(SimpleFeatureRenderPhase chosen) {
		return vitrail$overlay(chosen);
	}

	@ModifyVariable(method = "submitCrumblingOverlay", at = @At("STORE"), require = 1)
	private SimpleFeatureRenderPhase vitrail$crumblingOverlay(SimpleFeatureRenderPhase chosen) {
		return vitrail$overlay(chosen);
	}

	private SimpleFeatureRenderPhase vitrail$overlay(SimpleFeatureRenderPhase chosen) {
		return PackChain.drawingPack()
				? ((SubmitNodeCollection) (Object) this).breakingOverlay
				: chosen;
	}
}
