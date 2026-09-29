package dev.vitrail.api;

/**
 * What a far terrain source is told about the frame it is asked for sections in.
 *
 * @param cameraX the game's camera position, x, in blocks. It is the position every section origin
 *     is measured from when the frame is drawn, the light's halves included
 * @param cameraY the camera's y
 * @param cameraZ the camera's z
 * @param shadows whether the pack draws its far terrain into the shadow map this frame. When false
 *     the lists {@link DistantSections#shadowOpaque()} and {@link DistantSections#shadowWater()}
 *     are not read, so a source that builds them apart from the picture's lists may skip the work
 */
public record DistantFrame(double cameraX, double cameraY, double cameraZ, boolean shadows) {
}
