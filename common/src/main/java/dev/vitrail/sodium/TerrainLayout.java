package dev.vitrail.sodium;

import dev.vitrail.api.TerrainAttribute;
import dev.vitrail.api.TerrainVertexLayout;
import dev.vitrail.glsl.SodiumVertex;

import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * Where the elements this engine appends to a chunk vertex sit, and how the ones an add-on may ask
 * for are named and unioned into what the mesh carries.
 * <p>
 * <strong>The one place the order is counted.</strong> {@link TerrainMesh} lays its format and its
 * encoder out from {@link #appendedOffset}, and the layout an add-on is handed comes from
 * {@link #of}, so a mesh's own bytes and the description of them cannot part company. The order is
 * {@link SodiumVertex#ATTRIBUTES}' from the block id on, which is also the order the pack's programs
 * declare their inputs in and the one {@code TerrainProgram.carries} compares the bound format
 * against.
 * <p>
 * Nothing here names a Minecraft or a Sodium class, so every combination of elements is checked
 * off the game.
 */
public final class TerrainLayout {

	/** What a name that is not carried answers to {@link #appendedOffset}. */
	public static final int ABSENT = -1;

	/** Bytes one appended element takes. Every one of them is a single word. */
	public static final int WORD = Integer.BYTES;

	/**
	 * The elements this engine appends after Sodium's own bytes, in the order they are laid out:
	 * everything of {@link SodiumVertex#ATTRIBUTES} from the block id on.
	 */
	static final List<String> APPENDED = SodiumVertex.ATTRIBUTES.subList(
			SodiumVertex.ATTRIBUTES.indexOf(SodiumVertex.BLOCK_ID), SodiumVertex.ATTRIBUTES.size());

	private TerrainLayout() {
	}

	/**
	 * The name of the element an attribute is carried in. A switch with no default so that an
	 * attribute added to the API without an element of its own does not compile.
	 */
	public static String element(TerrainAttribute attribute) {
		return switch (attribute) {
			case BLOCK_ID -> SodiumVertex.BLOCK_ID;
			case MID_TEX_COORD -> SodiumVertex.MID_TEX_COORD;
			case MID_BLOCK -> SodiumVertex.MID_BLOCK;
			case TANGENT_FRAME -> SodiumVertex.TANGENT_FRAME;
		};
	}

	/**
	 * The attribute an element carries, or null for one that answers none: Sodium's own four, and
	 * the separated colour, which is a second shape for a word the mesh already has.
	 */
	public static @Nullable TerrainAttribute attribute(String element) {
		for (TerrainAttribute attribute : TerrainAttribute.values()) {
			if (element(attribute).equals(element)) {
				return attribute;
			}
		}

		return null;
	}

	/** The names of the elements a set of attributes is carried in. */
	public static Set<String> elements(Set<TerrainAttribute> attributes) {
		Set<String> elements = new LinkedHashSet<>();
		for (TerrainAttribute attribute : attributes) {
			elements.add(element(attribute));
		}

		return elements;
	}

	/**
	 * What the mesh has to carry once the attributes add-ons asked for are counted in: what was
	 * asked for by the pack, plus theirs, as Sodium's own four and then ours in layout order.
	 * <p>
	 * Handed back as it came where nothing was forced, so that a pack that asked for nothing stays
	 * an empty answer and not Sodium's own four, which {@code TerrainMesh.settle} reads differently.
	 *
	 * @param asked  the mesh the pack asked for, Sodium's own names first, or empty for none
	 * @param forced what add-ons need in every vertex whether the pack reads it or not
	 */
	public static List<String> withForced(List<String> asked, Set<TerrainAttribute> forced) {
		if (forced.isEmpty()) {
			return asked;
		}

		Set<String> reads = new LinkedHashSet<>(asked);
		reads.addAll(elements(forced));

		return SodiumVertex.carried(reads);
	}

	/**
	 * Where one appended element starts, counted from the end of Sodium's own bytes, or
	 * {@link #ABSENT} for one the mesh does not carry. Each carried element takes the next word in
	 * layout order, and one left out closes the gap rather than leaving a hole.
	 *
	 * @param element the name of an element this engine appends
	 * @param carried the whole format, as a list of names in which Sodium's own may or may not appear
	 */
	public static int appendedOffset(String element, Collection<String> carried) {
		int offset = 0;
		for (String appended : APPENDED) {
			if (appended.equals(element)) {
				return carried.contains(element) ? offset : ABSENT;
			}

			if (carried.contains(appended)) {
				offset += WORD;
			}
		}

		return ABSENT;
	}

	/**
	 * The layout of a vertex whose format is Sodium's own bytes and then the appended elements in
	 * {@code carried}.
	 *
	 * @param sodiumStride the bytes Sodium's own four elements take
	 * @param carried      the whole format, by names, in which only the appended ones matter
	 */
	public static TerrainVertexLayout of(int sodiumStride, Collection<String> carried) {
		Map<TerrainAttribute, Integer> offsets = new EnumMap<>(TerrainAttribute.class);
		int stride = sodiumStride;
		for (String element : APPENDED) {
			if (!carried.contains(element)) {
				continue;
			}

			TerrainAttribute attribute = attribute(element);
			if (attribute != null) {
				offsets.put(attribute, stride);
			}

			stride += WORD;
		}

		return new TerrainVertexLayout(stride, offsets);
	}
}
