package dev.vitrail.addon;

import org.jspecify.annotations.Nullable;

/**
 * One of the pack's targets as an add-on names it through {@code FrameContext.target}: a colour
 * target {@code colortex0} to {@code colortex15} or a depth {@code depthtex0} to {@code depthtex2}.
 * <p>
 * Only those spellings, exactly. The legacy names a pack may sample a target under
 * ({@code gcolor}, {@code gaux1}) are the pack's business, and an add-on that asks for one gets
 * nothing rather than a second answer to a question the contract phrases one way.
 *
 * @param depth whether this is a depth image rather than a colour target
 * @param index the number in the name
 */
public record TargetRef(boolean depth, int index) {

	/** The colour targets an add-on may ask for. */
	public static final int COLOUR_COUNT = 16;

	/** {@code depthtex0}, {@code depthtex1} and {@code depthtex2}. */
	public static final int DEPTH_COUNT = 3;

	private static final String COLOUR = "colortex";
	private static final String DEPTH = "depthtex";

	/** What the name refers to, or null for every name that is not one of the nineteen. */
	public static @Nullable TargetRef parse(String name) {
		if (name.startsWith(COLOUR)) {
			int index = number(name, COLOUR.length());
			return index >= 0 && index < COLOUR_COUNT ? new TargetRef(false, index) : null;
		}

		if (name.startsWith(DEPTH)) {
			int index = number(name, DEPTH.length());
			return index >= 0 && index < DEPTH_COUNT ? new TargetRef(true, index) : null;
		}

		return null;
	}

	/** The decimal number from {@code from} to the end with no sign and no leading zero, else -1. */
	private static int number(String name, int from) {
		int length = name.length() - from;
		if (length < 1 || length > 2 || (length == 2 && name.charAt(from) == '0')) {
			return -1;
		}

		int value = 0;
		for (int i = from; i < name.length(); i++) {
			char digit = name.charAt(i);
			if (digit < '0' || digit > '9') {
				return -1;
			}

			value = value * 10 + (digit - '0');
		}

		return value;
	}
}
