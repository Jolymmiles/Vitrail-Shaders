package dev.vitrail.render;

import net.minecraft.client.Minecraft;

/**
 * The calls into the game's own renderer that Minecraft 26.2 and 26.3 spell differently, one method
 * each, so the code making them is shared and the difference lives in one file per game.
 * {@link GraphicsApi} is the same thing one level down, for the graphics API the renderer is built
 * on.
 * <p>
 * <strong>This is the 26.3 half.</strong> Each method answers the question its 26.2 twin answers,
 * in the terms this game renders in, and says where the two part company.
 */
public final class GameRender {

	private GameRender() {
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
