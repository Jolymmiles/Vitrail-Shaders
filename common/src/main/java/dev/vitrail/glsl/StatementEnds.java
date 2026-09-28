package dev.vitrail.glsl;

import dev.vitrail.glsl.GlslLexer.Kind;
import dev.vitrail.glsl.GlslLexer.Token;

import java.util.Arrays;

/**
 * Where every statement of a token list ends, worked out once for a pass that only reads, and
 * answering exactly what {@link TokenStream#statementEnd} answers for the list as it stands.
 * <p>
 * The walk {@code statementEnd} makes is asked at every name that follows a type, which is every
 * parameter of every function, and at every {@code in} and {@code out} that qualifies one. A
 * parameter has no end to find: the parenthesis that closes its list takes the depth below the
 * nought the search wants a semicolon at, and the walk answers that there is none right there. It
 * has to stop there rather than read on, because the body of the function does bring the depth
 * back: the first {@code for} header in it opens a parenthesis, and its first semicolon would be
 * answered as the end of the parameter's statement. The corpus shows it: read on, the parameter
 * that starts at token 104 of {@code pin-composite} reaches the semicolon at 136, the first of the
 * header of the loop in its function.
 * <p>
 * The depth is a running sum over the list, so the walk from a token ends at the first semicolon or
 * opening brace after it that stands at the depth the token stood at, unless a bracket that closes
 * below that depth comes first. Both are found by bisection, the endings among those at the depth
 * the token stood at and the closers among those that leave the depth one below it, since the first
 * bracket to close below a depth is the first to leave the one under it. Everything the walk does
 * is kept: the operators of a preprocessor line neither count nor end anything, the window is the
 * same, a brace or a closer below the depth ends the search with no end and only a semicolon is one.
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

	/**
	 * The places of the closing parentheses and brackets, packed and sorted as {@link #endings} are,
	 * each with the depth it leaves behind rather than the one it closes. Only the first
	 * {@link #closerCount} are in use.
	 */
	private final long[] closers;
	private final int closerCount;

	StatementEnds(TokenStream tokens) {
		this.tokens = tokens;
		int size = tokens.size();
		this.blockDepth = new int[size / BLOCK + 1];

		long[] found = new long[size / 16 + 16];
		int count = 0;
		long[] closed = new long[size / 16 + 16];
		int closedCount = 0;
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
				if (closedCount == closed.length) {
					closed = Arrays.copyOf(closed, closedCount * 2);
				}

				closed[closedCount++] = pack(depth, at);
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
		this.closers = closed;
		this.closerCount = closedCount;
		Arrays.sort(this.closers, 0, closedCount);
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
	 * opens first, a bracket closes below the token's depth first, or none is within reach:
	 * {@link TokenStream#statementEnd}, for any index of the list.
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

		// The first bracket from here on to leave the depth one below the token's is the first to
		// close below it, and a closer at the token itself counts, as the walk reads that one too.
		int closing = Arrays.binarySearch(this.closers, 0, this.closerCount, pack(depth - 1, index));
		int firstClosing = closing >= 0 ? closing : -closing - 1;
		if (firstClosing < this.closerCount && (int) (this.closers[firstClosing] >> 32) == depth - 1
				&& (int) this.closers[firstClosing] < at) {
			return -1;
		}

		return this.tokens.get(at).text().equals(";") ? at : -1;
	}
}
