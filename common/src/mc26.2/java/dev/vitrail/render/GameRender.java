package dev.vitrail.render;

import net.minecraft.client.Minecraft;

/**
 * The calls into the game's own renderer that Minecraft 26.2 and 26.3 spell differently, one method
 * each, so the code making them is shared and the difference lives in one file per game.
 * {@link GraphicsApi} is the same thing one level down, for the graphics API the renderer is built
 * on.
 * <p>
 * <strong>This is the 26.2 half, and every method is the call the engine made before there were
 * two games,</strong> unchanged: a 26.2 jar runs exactly what it ran before this class existed. The
 * 26.3 half beside it under {@code src/mc26.3/} is where a method has to do more than rename, and
 * its javadoc says what and why.
 */
public final class GameRender {

	private GameRender() {
	}

	/**
	 * Whether the game is drawing its translucent particles into a target of its own that it
	 * composes onto the main one afterwards, which is what its improved transparency does. Asked
	 * inside the frame: the target is allocated per frame and only while the chain runs.
	 */
	public static boolean particlesApart() {
		return Minecraft.getInstance().levelRenderer.particlesTarget() != null;
	}

	/** The same question about the weather, which the same chain gives a target of its own. */
	public static boolean weatherApart() {
		return Minecraft.getInstance().levelRenderer.weatherTarget() != null;
	}
}
