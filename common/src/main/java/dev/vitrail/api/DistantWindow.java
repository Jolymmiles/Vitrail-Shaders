package dev.vitrail.api;

/**
 * The clip planes a far terrain is drawn between, in blocks along the view direction.
 * <p>
 * The pack reads them as {@code dhNearPlane} and {@code dhFarPlane}, and the {@code dhProjection}
 * it is served is the frame's own projection with these two planes in place of the game's: the far
 * terrain is rasterised in that volume and the image it leaves, {@code dhDepthTex}, is converted
 * out of it. So the planes decide two things at once: which geometry exists at all, since what
 * lies nearer than {@code near} or farther than {@code far} is clipped away, and how the depth
 * precision is spread between the two.
 * <p>
 * A window is usable when both planes are finite and {@code 0 < near < far}. Vitrail treats
 * anything else as no window, and the frame then goes out as one without far terrain.
 *
 * @param near distance of the near plane, in blocks
 * @param far distance of the far plane, in blocks
 */
public record DistantWindow(float near, float far) {
}
