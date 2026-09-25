package dev.vitrail.mixin.game;

import dev.vitrail.Vitrail;
import dev.vitrail.render.SkyDraw;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.level.MoonPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The sky on Minecraft 26.3: the tilt of the sun's path, and a line in the log for the pack's sky
 * programs, which this game does not draw with yet.
 * <p>
 * <strong>What the 26.2 twin of the same name does cannot be carried over by renaming.</strong> On
 * 26.2 the sky renderer opens a pass per piece, labels it, sets a pipeline description and binds its
 * images by name, and that twin recognises a piece by the label of its pass, compiles the pack's
 * program before the pass opens, and replaces the pass with one on the pack's own colour targets.
 * 26.3 records the whole sky in ONE pass opened at the head of {@code render}, names its pieces
 * with debug groups inside it, sets compiled pipelines and binds images as uniforms. So by the time
 * a piece is known the pass is already open on the game's target, a program cannot be compiled or a
 * target cleared inside it, and one pass cannot hold the pack's attachments for the pieces it serves
 * and the game's for the ones it does not. Serving the sky here means preparing every piece before
 * that pass and opening it on the pack's targets whole, which is a design of its own and is not done
 * yet. Until it is, the game draws its own sky with its own shaders, which is what a pack with
 * {@code sky=off} already gets on 26.2, and the first frame that would have served one says so.
 * <p>
 * <strong>Two things are not carried over because of that.</strong> The refusal of the sun and the
 * moon is tied to the pack drawing the sky, since a pack that writes {@code sun=false} has drawn its
 * own sun inside {@code gbuffers_skybasic}: with that program not run, taking the game's sun away
 * would leave none. And the horizon cone rides in the disc's pass, which is the pack's or nothing.
 * <p>
 * <strong>The tilt is carried over, unconditionally, as on 26.2.</strong> It is a property of the
 * pack's light rather than of its sky programs: the shadow matrices turn by it whether a sky
 * program runs or not, and a sun left where the game put it lights the world from one place and
 * shows itself in another.
 */
@Mixin(SkyRenderer.class)
public abstract class SkyRendererMixin {

	/** Whether the line below has been said, so that it is said once a session. */
	@Unique
	private static boolean vitrail$said;

	/**
	 * Tilts the path the sun, the moon and the stars travel by what the pack asked for, at the
	 * place the 26.2 twin does and with the same rotation: right after the game has turned the
	 * celestial space and before it turns for the hour, so that it tilts the whole path rather than
	 * the body of one moment. 26.3 turns that space with {@code rotateDegrees} where 26.2 multiplied
	 * a quaternion in, and it is the first such call either way.
	 */
	@Inject(method = "renderSunMoonAndStars",
			at = @At(value = "INVOKE", ordinal = 0, shift = At.Shift.AFTER,
					target = "Lcom/mojang/blaze3d/vertex/PoseStack;rotateDegrees(Lcom/mojang/math/Axis;F)V"))
	private void vitrail$tilt(RenderPass pass, PoseStack poseStack, float sunAngle, float moonAngle,
			float starAngle, MoonPhase moonPhase, float rainBrightness, float starBrightness,
			CallbackInfo callback) {
		float tilt = SkyDraw.sunPathRotation();
		if (tilt != 0.0F) {
			poseStack.rotate(Axis.ZP.rotationDegrees(tilt));
		}
	}

	/**
	 * Says once that the pack's sky programs are not drawn with on this game, on the first frame a
	 * pack asked for them. Nothing is changed about the sky itself.
	 */
	@Inject(method = "render", at = @At("HEAD"))
	private void vitrail$notServed(GpuBufferSlice skyFog, SkyRenderState state,
			CallbackInfo callback) {
		if (!vitrail$said && SkyDraw.serves()) {
			vitrail$said = true;
			Vitrail.logger().warn("Vitrail does not draw the sky with the pack's programs on "
					+ "Minecraft 26.3: that game records the whole sky in one render pass on its own "
					+ "target, and serving it needs every piece prepared before that pass opens. The "
					+ "game draws its own sky instead, as it does under sky=off, and the pack's sun "
					+ "and moon directives are not applied since no sky program of the pack runs to "
					+ "stand in for them. The tilt of the sun's path is applied");
		}
	}
}
