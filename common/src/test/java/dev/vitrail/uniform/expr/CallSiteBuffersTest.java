package dev.vitrail.uniform.expr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Holds two calls of one function in one expression apart, for the shapes
 * {@link ExprVectorsTest} does not reach: a function of three vectors, and the functions an
 * integer vector goes through, which keep their operands rather than their answer.
 * <p>
 * All of them used to be one object per name, shared by every call site. A function of three
 * vectors wrote its answer into the one vector it kept, as the others of its kind did. An integer
 * vector function parks each operand in a field before it walks the components, so the inner sum
 * of {@code a + (b + c)} parked {@code b} where the outer one had parked {@code a}, and the outer
 * one then added {@code b}.
 */
class CallSiteBuffersTest {

	private final ExprRig rig = new ExprRig();

	@Test
	void twoCallsOfAFunctionOfThreeVectorsKeepTheirOwnResults() {
		// (1, 0) + (0, 2); both answers in one vector gave (0, 2) twice.
		assertArrayEquals(new float[] {1, 2}, this.rig.vecOf(2,
				"clamp(vec2(5, -5), vec2(0, 0), vec2(1, 1)) + clamp(vec2(-5, 5), vec2(0, 0), vec2(2, 2))"));
	}

	@Test
	void anIntegerVectorSumKeepsItsOuterOperand() {
		// (1, 2) + (8, 10); with the operands parked in one place it was (3, 4) + (8, 10).
		assertTrue(this.rig.boolOf("equals(ivec2(1, 2) + (ivec2(3, 4) + ivec2(5, 6)), ivec2(9, 12))"));
	}

	@Test
	void anIntegerVectorComparisonKeepsItsFirstOperandAcrossAnInnerOne() {
		// The inner equals runs while the outer one is reading its second operand, and it parked
		// (3, 4) where the outer one had parked (1, 2).
		assertTrue(this.rig.boolOf(
				"equals(ivec2(1, 2), if(equals(ivec2(3, 4), ivec2(3, 4)), ivec2(1, 2), ivec2(0, 0)))"));
	}
}
