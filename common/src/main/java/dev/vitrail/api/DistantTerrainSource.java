package dev.vitrail.api;

/**
 * Far terrain an add-on keeps and the pack draws with its {@code dh_terrain}, {@code dh_water}
 * and {@code dh_shadow} programs, in the place Distant Horizons takes when it is installed.
 * <p>
 * At most one source draws in a session. Distant Horizons, when present, is the source; an add-on
 * source is used only without it.
 */
public interface DistantTerrainSource {

	/**
	 * Whether this source has far terrain in this session at all. While true, the pack sees
	 * {@code DISTANT_HORIZONS} defined and its {@code dh_*} programs are compiled.
	 */
	boolean present();

	/** Whether it can draw in this frame; a present source may be briefly unable to. */
	boolean usable();

	/** How far its terrain reaches, in blocks, which is what {@code dhRenderDistance} reads. */
	int renderDistanceBlocks();
}
