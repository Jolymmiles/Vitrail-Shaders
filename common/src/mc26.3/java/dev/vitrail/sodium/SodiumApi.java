package dev.vitrail.sodium;

import dev.vitrail.Vitrail;
import dev.vitrail.pack.program.TerrainPass;
import dev.vitrail.render.GeometryHold;
import dev.vitrail.render.TerrainDraw;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuSampler;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.world.phys.Vec3;

/**
 * The calls into Sodium that its builds for Minecraft 26.2 and 26.3 spell differently, one method
 * each, so the code making them is shared and the difference lives in one file per game.
 * <p>
 * <strong>This is the 26.3 half</strong>, against Sodium 0.9.2 for that game, which follows the
 * game in handing the render pass to its caller rather than opening it itself.
 */
public final class SodiumApi {

	/** Whether the refusal below has been said, so that it is said once a session. */
	private static boolean refusalSaid;

	private SodiumApi() {
	}

	/**
	 * Draws one group of chunk layers for the light's walk, inside {@code TerrainDraw.shadowPass}.
	 * <p>
	 * <strong>The pass is ours to open here.</strong> On 26.2 Sodium opened one per terrain pass of
	 * the group and the chunk renderer mixin handed back, while that flag stood, the descriptor
	 * {@link TerrainDraw#descriptor} names for it, which is the shadow map. 26.3's
	 * {@code drawChunkLayer} takes the pass, so this opens that same descriptor itself, through
	 * {@link GeometryHold} as the mixin did, and draws the group into it with no order independent
	 * stage, which is the classic path the game's own chunk sections take.
	 * <p>
	 * <strong>One pass for the whole group, where 26.2 had one per terrain pass.</strong> The opaque
	 * group is the solid and the cutout passes, and the descriptor is asked for the first of them.
	 * The two shadow programs draw into the one map and their passes were already joined into one by
	 * {@link GeometryHold} wherever they named the same attachments; a pack that gave them different
	 * draw buffers would have the second refused at {@code setPipeline}, and that is a question for
	 * the port of the chunk renderer mixin, which is also what puts the pack's shadow programs on
	 * this pass at all.
	 * <p>
	 * <strong>No descriptor, no draw.</strong> 26.2 fell back on Sodium's own pass on the game's
	 * target there, which the shadow stage guards against before it opens; drawn here, that would be
	 * the world seen from the light painted over the picture, so this draws nothing and says so once.
	 */
	public static void drawShadowLayer(SodiumWorldRenderer renderer, ChunkSectionLayerGroup group,
			ChunkRenderMatrices matrices, Vec3 camera, GpuSampler sampler) {
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		TerrainPass first = group == ChunkSectionLayerGroup.TRANSLUCENT
				? TerrainPass.TRANSLUCENT
				: TerrainPass.SOLID;
		RenderPassDescriptor descriptor = TerrainDraw.descriptor(first, main.getColorTextureView(),
				main.getDepthTextureView());
		if (descriptor == null) {
			if (!refusalSaid) {
				refusalSaid = true;
				Vitrail.logger().warn("Vitrail drew no {} terrain into the shadow map because the pack "
						+ "names no pass for it, and on Minecraft 26.3 the only other pass is the game's "
						+ "own picture", group);
			}

			return;
		}

		try (RenderPass pass = GeometryHold.open(RenderSystem.getDevice().createCommandEncoder(),
				descriptor)) {
			renderer.drawChunkLayer(pass, group, matrices, camera.x, camera.y, camera.z, sampler,
					null);
		}
	}
}
