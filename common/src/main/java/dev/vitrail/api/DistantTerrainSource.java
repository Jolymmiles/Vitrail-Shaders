package dev.vitrail.api;

import org.jspecify.annotations.Nullable;

/**
 * Far terrain an add-on keeps and the pack draws with its {@code dh_terrain}, {@code dh_water}
 * and {@code dh_shadow} programs, in the place Distant Horizons takes when it is installed.
 * <p>
 * At most one source draws in a session. Distant Horizons, when present, is the source; an add-on
 * source is used only without it, and of several the first that is {@link #present} is the one, in
 * the order the add-ons were found. A source that is not the chosen one is asked nothing beyond
 * {@code attach} and, until one is found, {@code present}.
 * <p>
 * <strong>The add-on owns the terrain and Vitrail owns the GPU.</strong> The source decides which
 * tiles exist, what they look like and which of them a frame draws. It hands the geometry over
 * once, through the {@link DistantMeshes} it is given in {@link #attach}, and each frame it lists
 * what to draw as {@link DistantSections} of handles. Vitrail draws the lists with the pack's own
 * programs, in the frame's own order: the opaque half just before the game's opaque terrain, the
 * water half just before its translucent terrain and the light's halves into the shadow map at the
 * tail of the frame. Nothing here needs a hook into the game.
 * <p>
 * <strong>What the pack is told comes from here as well.</strong> {@code DISTANT_HORIZONS} is
 * defined for as long as the source is present, the pack's {@code dh_*} programs are compiled ahead,
 * {@code dhRenderDistance} is {@link #renderDistanceBlocks}, and {@code dhNearPlane},
 * {@code dhFarPlane} and {@code dhProjection} are made out of {@link #window}. The depth the far
 * terrain leaves is what the pack reads as {@code dhDepthTex0} and {@code dhDepthTex1}.
 * <p>
 * All of it is called on the render thread. The exceptions are the two objects {@code attach}
 * hands over, which any thread may use.
 */
public interface DistantTerrainSource {

	/**
	 * Whether this source has far terrain in this session at all. While true, the pack sees
	 * {@code DISTANT_HORIZONS} defined and its {@code dh_*} programs are compiled. Read every frame,
	 * and a change from one answer to the other reads the pack again, so it should follow what the
	 * player can switch on and off and nothing quicker.
	 */
	boolean present();

	/**
	 * Whether it can draw in this frame; a present source may be briefly unable to, before its
	 * first tiles exist for one. {@link #frame} is not called while this is false, and the pack
	 * still sees everything that {@code present} gives it.
	 */
	boolean usable();

	/**
	 * How far its terrain reaches, in blocks, which is what {@code dhRenderDistance} reads. Zero or
	 * less means there is no answer, and the pack is given the game's own render distance instead.
	 */
	int renderDistanceBlocks();

	/**
	 * Hands the source the place it puts its geometry. Called once, on the render thread, before
	 * the first {@link #present}; the object is the source's own, is never replaced and may be
	 * kept and used from any thread.
	 * <p>
	 * When a source is cut off after throwing, everything it uploaded is released.
	 */
	void attach(DistantMeshes meshes);

	/**
	 * The clip planes of the far terrain's volume, read every frame while the source is present,
	 * even while it is not usable. Null while there are none to give, and the pack then sees the
	 * frame without far terrain: the planes it publishes fall back and no far depth is served. A
	 * {@linkplain DistantWindow window} that cannot be used counts as null.
	 */
	@Nullable DistantWindow window();

	/**
	 * The far terrain for one frame, asked once at the head of every level frame in which the pack
	 * draws its far terrain, while the source is present and usable, and before any of that frame's
	 * far terrain is drawn.
	 * <p>
	 * Uploads and releases made since the last frame have taken effect by now. Null and
	 * {@link DistantSections#NONE} both mean nothing is drawn: the frame goes on and the pack sees
	 * an empty far terrain.
	 */
	@Nullable DistantSections frame(DistantFrame frame);
}
