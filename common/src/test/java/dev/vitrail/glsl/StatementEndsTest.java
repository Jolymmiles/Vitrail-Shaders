package dev.vitrail.glsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.glsl.GlslTranslatorCases.Pair;
import dev.vitrail.glsl.GlslTranslatorCases.Single;

import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Holds {@link StatementEnds} to what {@link TokenStream#statementEnd} answers, which is the walk it
 * stands in for and is kept here, as it is written there, as the reading it must agree with.
 * <p>
 * The agreement is asked at every index of every text: the stages of the translator's own corpus and
 * its recombinations, texts of random tokens that are nothing a compiler would take (a closer with
 * no opener, a brace inside a parameter list, a preprocessor line full of parentheses), and texts
 * long enough that the four thousand token window decides the answer.
 */
class StatementEndsTest {

	private static void assertAgrees(String label, String text) {
		TokenStream tokens = new TokenStream(GlslLexer.lex(text));
		StatementEnds ends = new StatementEnds(tokens);

		// One past the last index as well, where the walk has nothing to look at.
		for (int index = 0; index <= tokens.size(); index++) {
			assertEquals(tokens.statementEnd(index), ends.after(index), label + " at token " + index);
		}
	}

	@Test
	void agreesOnEveryStageOfTheCorpus() {
		for (Single one : GlslTranslatorCases.everySingle()) {
			assertAgrees(one.name(), one.source());
		}

		for (Pair pair : GlslTranslatorCases.everyPair()) {
			assertAgrees(pair.name() + " vertex", pair.vertex());
			assertAgrees(pair.name() + " fragment", pair.fragment());
		}
	}

	@Test
	void agreesOnTheRecombinations() {
		for (int index = 0; index < 100; index++) {
			assertAgrees("recombination " + index, GlslTranslatorCases.recombination(index).source());
		}
	}

	@Test
	void agreesOnTextsNoCompilerWouldTake() {
		String[] words = {"a", "b", "float", "vec3", "in", "out", "main", "0", "1.0"};
		String[] operators = {"(", ")", "(", ")", "[", "]", "{", "}", ";", ";", ",", "=", "+"};
		String[] directives = {"#define F(a, b) (a + b);", "#if defined(X) && (Y)", "#define G )))", "#endif"};
		Random random = new Random(0x57A7E);
		for (int text = 0; text < 300; text++) {
			StringBuilder source = new StringBuilder();
			int length = 20 + random.nextInt(400);
			for (int at = 0; at < length; at++) {
				int pick = random.nextInt(100);
				if (pick < 40) {
					source.append(words[random.nextInt(words.length)]).append(' ');
				} else if (pick < 92) {
					source.append(operators[random.nextInt(operators.length)]);
				} else if (pick < 96) {
					source.append('\n');
				} else {
					source.append('\n').append(directives[random.nextInt(directives.length)]).append('\n');
				}
			}

			assertAgrees("soup " + text, source.toString());
		}
	}

	@Test
	void agreesWhereTheWindowDecidesTheAnswer() {
		// A name, then a run of words, then the semicolon: the semicolon is found when it stands within
		// four thousand tokens of the name and is not when it stands past them. Both sides of the
		// edge are walked, and the test says that it did.
		int found = 0;
		int lost = 0;
		for (int words = 2040; words <= 2056; words++) {
			String text = "x" + " y".repeat(words) + ";";
			TokenStream tokens = new TokenStream(GlslLexer.lex(text));
			StatementEnds ends = new StatementEnds(tokens);
			assertEquals(tokens.statementEnd(0), ends.after(0), words + " words");
			if (ends.after(0) >= 0) {
				found++;
			} else {
				lost++;
			}
		}

		assertTrue(found > 0 && lost > 0, "the edge of the window was crossed: found " + found + ", lost " + lost);
	}

	@Test
	void aParameterListHasNoEndAndADeclarationDoes() {
		String text = """
				float f(float a, vec3 b) {
					vec3 c = b * 2.0;
					return a;
				}
				""";
		TokenStream tokens = new TokenStream(GlslLexer.lex(text));
		StatementEnds ends = new StatementEnds(tokens);
		int a = indexOf(tokens, "a");
		int c = indexOf(tokens, "c");

		assertEquals(-1, ends.after(a));
		assertTrue(ends.after(c) > c, "the declaration ends at its semicolon");
		assertEquals(tokens.statementEnd(c), ends.after(c));
	}

	private static int indexOf(TokenStream tokens, String identifier) {
		for (int index = 0; index < tokens.size(); index++) {
			if (tokens.get(index).identifier(identifier)) {
				return index;
			}
		}

		throw new AssertionError(identifier);
	}
}
