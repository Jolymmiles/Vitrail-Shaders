package dev.vitrail.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.vitrail.Vitrail;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/**
 * The calls into the game's own renderer that Minecraft 26.2 and 26.3 spell differently, one method
 * each, so the code making them is shared and the difference lives in one file per game.
 * {@link GraphicsApi} is the same thing one level down, for the graphics API the renderer is built
 * on.
 * <p>
 * <strong>This is the 26.3 half.</strong> Each method answers the question its 26.2 twin answers,
 * in the terms this game renders in, and says where the two part company.
 * <p>
 * <strong>What changed underneath the drawing half of them is who opens the render pass.</strong>
 * On 26.2 an immediate draw opened a pass of its own, on the target its render type named, and
 * {@code RenderSystem} carried an output override for colour and one for depth that every such
 * draw read. 26.3 removed both: a render type names no target, a draw goes into the pass its caller
 * hands it, and the level renderer opens ONE pass on the main target for its whole main pass, the
 * opaque terrain, the solid features, the translucent features, the translucent terrain, the clouds
 * and the weather in turn.
 */
public final class GameRender {

	/** Whether the refusal below has been said, so that it is said once a session. */
	private static boolean featuresRefusalSaid;

	private GameRender() {
	}

	/**
	 * Whether the game's translucent features can be sent into an image of the engine's choosing,
	 * which on this game they cannot yet, and the first refusal says so in the log.
	 * <p>
	 * <strong>What the redirect needs here is a pass of its own.</strong> The 26.3 way to send a
	 * feature phase somewhere is to open the pass it is drawn into on that image and hand the pass
	 * to the phase. The game draws its translucent features inside the one pass it keeps open for
	 * the whole main pass, so that phase would have to be cut out of it: the main pass closed after
	 * the solid features, a pass opened on the layer and the world's depth for the translucent ones,
	 * and the main pass opened again for the translucent terrain after them. That cut is the same
	 * work as running the engine's own stages at those two moments at all, which open passes and
	 * copy images where NeoForge and the game now hand them the main pass still recording, and it is
	 * not done yet. Until it is, the layer is never opened and the game's features stay on its own
	 * target.
	 */
	public static boolean redirectsFeatures() {
		if (!featuresRefusalSaid) {
			featuresRefusalSaid = true;
			Vitrail.logger().warn("Vitrail does not hand the game's translucent features to the pack's "
					+ "image on Minecraft 26.3, so the player's own body in third person and every "
					+ "translucent feature no program of the pack serves stay on the game's target, "
					+ "which the pack's final draws over. This game draws them inside the one render "
					+ "pass it keeps open for its whole main pass and has no output override to send "
					+ "them elsewhere; redirecting them means cutting that pass, which is not done "
					+ "yet");
		}

		return false;
	}

	/**
	 * Never reached, {@link #redirectsFeatures()} answering no on this game. Kept so that the shared
	 * caller is one caller, and a no-op rather than a throw so that a caller that did not ask first
	 * costs a picture and not the frame.
	 */
	public static void redirectFeatures(GpuTextureView colour, GpuTextureView depth) {
		// Nothing to redirect through: see redirectsFeatures.
	}

	/** Nothing to put back, nothing having been redirected. */
	public static void endFeatureRedirect() {
		// See redirectsFeatures.
	}

	/**
	 * Whether a draw of this render type lands on the game's main target.
	 * <p>
	 * 26.3 names no target on a render type, and what separates the draws that do not land there is
	 * the game's improved transparency: under it, a render type carrying an order independent
	 * pipeline set is drawn in the translucent phase through {@code drawFromBufferOit}, into targets
	 * that technique composes onto the main one afterwards, which is the position 26.2's targets of
	 * that option were in. Everything else is drawn into a pass its caller opened on the main target.
	 */
	public static boolean drawsOnMainTarget(PreparedRenderType prepared) {
		Minecraft minecraft = Minecraft.getInstance();

		return minecraft != null && (!minecraft.gameRenderer.useImprovedTransparency()
				|| prepared.oitPipelineSet() == null);
	}

	/** Where a draw of this render type is sent when {@link #drawsOnMainTarget} says elsewhere. */
	public static String drawTargetName(PreparedRenderType prepared) {
		return "the order independent transparency targets";
	}

	/**
	 * The colour image a draw of this render type lands in: the main target's, which is where the
	 * level renderer opens the pass its features are drawn in. No override exists on this game to
	 * move it.
	 */
	public static GpuTextureView drawColour(PreparedRenderType prepared) {
		return Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTextureView();
	}

	/** The same for depth, the main target's own. */
	public static @Nullable GpuTextureView drawDepth(PreparedRenderType prepared) {
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();

		return GraphicsApi.hasDepth(main) ? main.getDepthTextureView() : null;
	}

	/**
	 * Draws everything a storage of the engine's own holds, on a dispatcher of the engine's own.
	 * <p>
	 * 26.2 opened a pass per draw on the target each render type named, and without an override
	 * every one of those resolved to the main target and its depth for the storages this is called
	 * with. 26.3 wants the pass from the caller, so this opens one on those same two images, the way
	 * the game draws its own hand ({@code GameRenderer.renderItemInHand}): the frame prepared first,
	 * since preparing uploads and an upload is refused inside a pass, and the default uniforms bound
	 * before the phases run. The phases are the ones 26.2's call ran, the see-through one included,
	 * which 26.3 split out of the translucent one.
	 */
	public static void renderAllFeatures(FeatureRenderDispatcher dispatcher,
			SubmitNodeStorage submits, Supplier<String> label) {
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();

		try (FeatureRenderDispatcher.PreparedFrame frame = dispatcher.prepareFrame(submits);
				RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
						label, main.getColorTextureView(), Optional.empty(),
						main.getDepthTextureView(), OptionalDouble.empty())) {
			RenderSystem.bindDefaultUniforms(pass);
			FeatureRenderDispatcher.renderAllFeatures(pass, frame);
		}
	}

	/**
	 * Submits the player's own hands and whatever they hold, as the game's own late call submits
	 * them.
	 * <p>
	 * 26.3 draws the hands from a render state the frame extracted for the player, and the class
	 * that submits them is {@code FirstPersonHandsAndItemsRenderer}. The player and the light are
	 * not read: the state carries the light the frame extracted for the player, and the game
	 * submits nothing where it extracted no player, which is kept here too. The partial
	 * tick the caller hands in is the camera entity's, which is the one the game extracts for this
	 * very call.
	 */
	public static void submitHands(GameRenderer gameRenderer, float partialTicks, PoseStack pose,
			SubmitNodeCollector into, LocalPlayer player, int light) {
		PlayerRenderState state = gameRenderer.gameRenderState().levelRenderState.playerRenderState;
		if (state.hasPlayer) {
			gameRenderer.firstPersonHandsAndItemsRenderer.submitHandsWithItems(partialTicks, pose,
					into, state, state.firstPersonHandsAndItems);
		}
	}

	/**
	 * Fills a camera render state from the camera, as the game's own extraction does. 26.3 takes
	 * the delta tracker and works the camera entity's partial tick out of it itself, which is the
	 * value the 26.2 twin hands in, and keeps it on the state.
	 */
	public static void extractCamera(Camera camera, CameraRenderState into, DeltaTracker delta) {
		camera.extractRenderState(into, delta);
	}

	/**
	 * The game's own frustum test for one entity, asked of the renderer that draws it. 26.3 builds
	 * the culling box at the partial tick it is handed, where 26.2 built it where the entity stood;
	 * the game hands it the entity's own tick, frozen or not, and so does the caller here.
	 */
	public static boolean shouldRender(EntityRenderDispatcher entities, Entity entity,
			Frustum frustum, Vec3 at, float partialTicks) {
		return entities.shouldRender(entity, frustum, at.x, at.y, at.z, partialTicks);
	}

	/**
	 * Whether the section a block stands in has a mesh and has faded in far enough to be drawn,
	 * which is the game's own second test on an entity it is about to extract. 26.3 takes the length
	 * of the fade as an argument rather than reading it off the section, and the game hands it the
	 * option's value, as this does.
	 */
	public static boolean sectionShown(LevelRenderer level, BlockPos block) {
		long fade = Util.toMillis(Minecraft.getInstance().options.chunkSectionFadeInTime().get());

		return level.isSectionCompiledAndVisible(block, fade);
	}

	/**
	 * Whether the game is drawing its translucent particles somewhere it composes onto the main
	 * target afterwards, which is what its improved transparency does.
	 * <p>
	 * 26.3 has no particles target to ask about. Its improved transparency is order independent:
	 * the translucent particles go through the frame's order independent passes into targets of
	 * that technique, and a composite puts the result on the main target. So the question is asked
	 * of the switch the level renderer itself reads, which is the same answer.
	 */
	public static boolean particlesApart() {
		return Minecraft.getInstance().gameRenderer.useImprovedTransparency();
	}

	/**
	 * The same question about the weather, and the same answer: under this game's improved
	 * transparency the weather is drawn through the order independent passes as well.
	 */
	public static boolean weatherApart() {
		return Minecraft.getInstance().gameRenderer.useImprovedTransparency();
	}
}
