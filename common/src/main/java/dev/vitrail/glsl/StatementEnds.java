package dev.vitrail.glsl;

import dev.vitrail.glsl.GlslLexer.Kind;
import dev.vitrail.glsl.GlslLexer.Token;

import java.util.Arrays;

/**
 * Where every statement of a token list ends, worked out once for a pass that only reads, and
 * answering exactly what {@link TokenStream#statementEnd} answers for the list as it stands.
 * <p>
 * The walk {@code statementEnd} makes is short for a declaration and as long as its window for a
 * parameter: the parenthesis that closes the list takes the depth below the nought the search wants
 * a semicolon at, and nothing in the body of the function brings it back, so the search runs its
 * four thousand tokens and answers that there is no end. Every parameter of every function paid
 * that, and so did every {@code in} and {@code out} that qualifies one: on a stage of some
 * twenty-seven thousand lines about a fifth of the translation was that walk.
 * <p>
 * The depth is a running sum over the list, so the walk from a token ends at the first semicolon or
 * opening brace after it that stands at the depth the token stood at, and those are found by
 * bisection among the ones at that depth. Everything the walk does is kept: the operators of a
 * preprocessor line neither count nor end anything, the window is the same, a brace ends the search
 * with no end and only a semicolon is one.
 * <p>
 * The depth before a token is kept for every {@link #BLOCK}th token only and finished from there by
 * reading the few tokens between, which is what keeps the index from costing a word for every token
 * of a list that has hundreds of thousands.
 * <p>
 * <strong>It describes the list as it was when it was built.</strong> A token edited afterwards, a
 * bracket blanked or a word replaced, can change the depth of every token after it, so an index is
 * only for a pass that edits nothing between building it and its last question.
 */
final class StatementEnds {

	/** How far {@link TokenStream#statementEnd} looks for a semicolon, and so how far this does. */
	private static final int WINDOW = 4096;

	/** How many tokens share one recorded depth. */
	private static final int BLOCK = 64;

	private final TokenStream tokens;

	/** The depth before the first token of each block, an opener taking one and a closer giving it back. */
	private final int[] blockDepth;

	/**
	 * The places of the semicolons and opening braces of the list, each with the depth it stands at
	 * in the high half and its place in the low half, so that the sorted order is by depth and then
	 * by place. Only the first {@link #endingCount} are in use.
	 */
	private final long[] endings;
	private final int endingCount;

	StatementEnds(TokenStream tokens) {
		this.tokens = tokens;
		int size = tokens.size();
		this.blockDepth = new int[size / BLOCK + 1];

		long[] found = new long[size / 16 + 16];
		int count = 0;
		int depth = 0;
		for (int at = 0; at < size; at++) {
			if (at % BLOCK == 0) {
				this.blockDepth[at / BLOCK] = depth;
			}

			Token token = tokens.get(at);
			if (token.kind() != Kind.OPERATOR || token.directive() != null) {
				continue;
			}

			String text = token.text();
			if (text.equals("(") || text.equals("[")) {
				depth++;
			} else if (text.equals(")") || text.equals("]")) {
				depth--;
			} else if (text.equals("{") || text.equals(";")) {
				if (count == found.length) {
					found = Arrays.copyOf(found, count * 2);
				}

				found[count++] = pack(depth, at);
			}
		}

		this.endings = found;
		this.endingCount = count;
		Arrays.sort(this.endings, 0, count);
	}

	private static long pack(int depth, int at) {
		return ((long) depth << 32) | at;
	}

	/** The depth before the token at this index: what its block recorded, and what the tokens since it added. */
	private int depthBefore(int index) {
		int depth = this.blockDepth[index / BLOCK];
		for (int at = index - index % BLOCK; at < index; at++) {
			Token token = this.tokens.get(at);
			if (token.kind() != Kind.OPERATOR || token.directive() != null) {
				continue;
			}

			String text = token.text();
			if (text.equals("(") || text.equals("[")) {
				depth++;
			} else if (text.equals(")") || text.equals("]")) {
				depth--;
			}
		}

		return depth;
	}

	/**
	 * The semicolon that closes the statement the token at this index is in, or -1 where a brace
	 * opens first or none is within reach: {@link TokenStream#statementEnd}, for any index of the list.
	 */
	int after(int index) {
		int size = this.tokens.size();
		if (index < 0 || index >= size) {
			return -1;
		}

		int depth = depthBefore(index);
		int found = Arrays.binarySearch(this.endings, 0, this.endingCount, pack(depth, index));
		int first = found >= 0 ? found : -found - 1;
		if (first >= this.endingCount || (int) (this.endings[first] >> 32) != depth) {
			return -1;
		}

		int at = (int) this.endings[first];
		if (at >= Math.min(size, index + WINDOW)) {
			return -1;
		}

		return this.tokens.get(at).text().equals(";") ? at : -1;
	}
}
