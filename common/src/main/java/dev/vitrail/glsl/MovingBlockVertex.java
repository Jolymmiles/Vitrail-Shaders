package dev.vitrail.glsl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * What a moving block's mesh carries, and how the names a pack reads are made out of it.
 * <p>
 * Falling blocks and blocks carried by pistons are drawn through the game's three moving-block
 * pipelines, which declare {@code DefaultVertexFormat.BLOCK}: position, colour, the block atlas
 * coordinate and the real light map in {@code UV2}. Iris widens that format for them to its terrain
 * vertex ({@code mixin/vertices/MixinBufferBuilder.iris$extendFormat}, {@code IrisVertexFormats.TERRAIN})
 * and fills what it added, and so does this engine: {@code EntityMesh} builds the wider format and
 * binds it for those three pipelines, and {@code BufferBuilderMixin} fills it polygon by polygon.
 * <p>
 * <strong>What each added name is, read off Iris.</strong> The normal is the quad's own face normal
 * written over the one the game gave its nominal face, and the tangent is measured against it
 * ({@code MixinBufferBuilder.fillExtendedData}); {@code mc_midTexCoord} is the mean of the corners'
 * texture coordinates; {@code at_midBlock} is Iris's word for a vertex nothing opened a block
 * around ({@code iris$fillPerVertexData} with the fields it declares, {@code BufferBuilderMixin}
 * saying which). {@code mc_Entity} is not an element here: nothing opens a block around a moving
 * one, so Iris writes the minus one its field is declared with into both lanes of every vertex, and
 * a constant is answered as a constant.
 * <p>
 * The appended elements go after the game's four, and every one is declared whether or not a pack
 * reads it, for the reason {@link EntityVertex} gives: the pairing is by name, and a stage that
 * skipped one would move every name after it.
 */
public final class MovingBlockVertex {

	/**
	 * The game's own normal, appended here where the entity format carries it inline. Spelled as the
	 * game spells it, so that {@code BufferBuilder.setNormal} writes it.
	 */
	public static final String NORMAL = "Normal";

	/** The offset from a corner to the middle of its block, and the block's light emission. */
	public static final String MID_BLOCK = "MidBlock";

	/** The four this engine appends to the game's block format, in the order it appends them. */
	public static final List<String> APPENDED =
			List.of(NORMAL, EntityVertex.MID_TEX_COORD, EntityVertex.TANGENT, MID_BLOCK);

	/** The elements of a moving block's mesh, in the format's own order. */
	public static final List<String> ATTRIBUTES = Stream.concat(
			Stream.of("Position", "Color", "UV0", "UV2"), APPENDED.stream()).toList();

	/**
	 * The ones of {@link VertexPrologue#SYNTHESIZED} this mesh answers out of an element, which is
	 * what {@code EntityProgram} names in the log. {@code mc_Entity} is answered too, but with the
	 * constant Iris writes, so it is not in here.
	 */
	public static final Set<String> ANSWERED = Set.of("mc_midTexCoord", "at_tangent", "at_midBlock");

	private MovingBlockVertex() {
	}

	/**
	 * The head of a moving-block vertex stage.
	 *
	 * @param used        every fixed-function or synthesized name the rewritten body mentions
	 * @param synthesized vertex inputs the pack declared for itself and the translator lifted
	 */
	public static List<String> prologue(Set<String> used, Map<String, String> synthesized) {
		List<String> lines = new ArrayList<>();

		for (String attribute : ATTRIBUTES) {
			lines.add("in " + VertexPrologue.elementType(attribute) + " " + attribute + ";");
		}

		lines.add("#define of_Vertex vec4(Position, 1.0)");
		lines.add("#define of_Color Color");
		lines.add("#define of_MultiTexCoord0 vec4(UV0, 0.0, 1.0)");
		// Not full light: Iris keys this draw on the light map (ShaderKey.java:50), and the element
		// carries the block's own.
		lines.add("#define of_MultiTexCoord1 vec4(UV2, 0.0, 1.0)");
		lines.add("#define of_MultiTexCoord2 vec4(UV2, 0.0, 1.0)");
		lines.addAll(VertexPrologue.blankTexCoords());
		lines.add("#define of_Normal " + NORMAL + ".xyz");

		VertexPrologue.globals(used, synthesized, Map.of()).forEach((name, type) -> lines.add(
				switch (name) {
					case "mc_midTexCoord" -> "#define " + name + " " + EntityVertex.midTexCoord(type);
					case "at_tangent" -> "#define " + name + " " + EntityVertex.tangent(type);
					case "at_midBlock" -> "#define " + name + " " + midBlock(type);
					case "mc_Entity" -> type + " " + name + " = " + noBlock(type) + ";";
					default -> VertexPrologue.declaration(name, type);
				}));

		return List.copyOf(lines);
	}

	/**
	 * {@code at_midBlock} out of the element, in the shape the pack declared it under. The element is
	 * four normalised bytes, as Iris's is, and Iris hands the pack that value times 127
	 * ({@code CommonTransformer.replaceMidBlock}), so the same factor is applied here.
	 */
	private static String midBlock(String type) {
		return switch (type) {
			case "float" -> "(" + MID_BLOCK + ".x * 127.0)";
			case "vec2" -> "(" + MID_BLOCK + ".xy * 127.0)";
			case "vec3" -> "(" + MID_BLOCK + ".xyz * 127.0)";
			case "vec4" -> "(" + MID_BLOCK + " * 127.0)";
			default -> VertexPrologue.zero(type);
		};
	}

	/**
	 * The minus one Iris writes into both lanes of {@code mc_Entity} on a moving block, in the shape
	 * the pack declared it under, the lanes a two-lane element does not fill taking what a driver
	 * hands them.
	 */
	private static String noBlock(String type) {
		return switch (type) {
			case "float" -> "-1.0";
			case "int" -> "-1";
			case "vec2" -> "vec2(-1.0)";
			case "vec3" -> "vec3(-1.0, -1.0, 0.0)";
			case "vec4" -> "vec4(-1.0, -1.0, 0.0, 1.0)";
			case "ivec2" -> "ivec2(-1)";
			case "ivec3" -> "ivec3(-1, -1, 0)";
			case "ivec4" -> "ivec4(-1, -1, 0, 1)";
			default -> VertexPrologue.zero(type);
		};
	}
}
