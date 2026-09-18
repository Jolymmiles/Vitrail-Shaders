package dev.vitrail.glsl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What Minecraft's moving-block mesh carries, and how the fixed-function names a shader pack reads
 * are made out of it.
 * <p>
 * Falling blocks and blocks carried by pistons are submitted through the moving-block render
 * pipelines. Those pipelines bind {@code DefaultVertexFormat.BLOCK}: position, colour, the block
 * atlas coordinate and the real light-map coordinate in {@code UV2}. Unlike the breaking overlay,
 * this geometry is not full-bright, so both fixed-function light-map units are answered from
 * {@code UV2}.
 * <p>
 * <strong>This is intentionally narrower than Iris's terrain vertex format.</strong> Iris widens the
 * same block format and computes a normal, tangent, middle texture coordinate, block id and middle
 * block offset. Vitrail does not yet widen this mesh, so those names are synthesized by
 * {@link VertexPrologue} instead. The cost is that packs depending on those extended terrain
 * attributes can shade a moving block differently from ordinary chunk terrain; keeping the real
 * light map here still avoids handing the draw back to the game's already-lit path.
 */
public final class MovingBlockVertex {

	/** The elements of {@code DefaultVertexFormat.BLOCK}, in the format's own order. */
	public static final List<String> ATTRIBUTES = List.of("Position", "Color", "UV0", "UV2");

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
		lines.add("#define of_MultiTexCoord1 vec4(UV2, 0.0, 1.0)");
		lines.add("#define of_MultiTexCoord2 vec4(UV2, 0.0, 1.0)");
		lines.addAll(VertexPrologue.blankTexCoords());

		// DefaultVertexFormat.BLOCK carries no normal. Match the stand-in used for other meshes that
		// do not carry one rather than normalising a zero vector into NaNs.
		lines.add("#define of_Normal vec3(0.0, 0.0, 1.0)");
		lines.addAll(VertexPrologue.tail(used, synthesized));

		return List.copyOf(lines);
	}
}
