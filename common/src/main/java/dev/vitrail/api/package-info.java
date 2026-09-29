/**
 * What another mod may hold of Vitrail: the one package that promises to stay put between
 * releases.
 * <p>
 * An add-on is a mod that feeds the pack something Vitrail does not make itself: defines, images
 * it renders elsewhere, extra or patched pack files, work recorded at a fixed point of the frame,
 * far terrain, a copy of the terrain meshes as Sodium builds them, or a notice that the game is
 * about to destroy its Vulkan device. It implements {@link dev.vitrail.api.VitrailAddon} and
 * declares it where its loader looks: under the Fabric entrypoint {@code "vitrail"}, or as a
 * {@code META-INF/services} provider on NeoForge. Vitrail calls it once, and everything it
 * registers is called back later, on the render thread unless the piece's own javadoc names
 * another.
 * <p>
 * Nothing in here names a Minecraft class, so the same add-on source compiles against both
 * games. A Vulkan object crosses as its raw handle, a {@code long}, and a pack file as its path
 * relative to the pack's root, with forward slashes.
 * <p>
 * An add-on that throws is cut off rather than allowed to take the pack down: the piece that threw
 * is logged once with the add-on's id and skipped for the rest of the session, and the frame goes
 * on without it.
 */
package dev.vitrail.api;
