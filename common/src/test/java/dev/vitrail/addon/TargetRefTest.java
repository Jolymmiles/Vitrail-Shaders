package dev.vitrail.addon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Holds which names {@code FrameContext.target} answers to: nineteen, spelled one way each. */
class TargetRefTest {

	@Test
	void everyColourTargetAnAddonMayAskForIsRecognised() {
		for (int index = 0; index < TargetRef.COLOUR_COUNT; index++) {
			assertEquals(new TargetRef(false, index), TargetRef.parse("colortex" + index), "colortex" + index);
		}
	}

	@Test
	void everyDepthAnAddonMayAskForIsRecognised() {
		for (int index = 0; index < TargetRef.DEPTH_COUNT; index++) {
			assertEquals(new TargetRef(true, index), TargetRef.parse("depthtex" + index), "depthtex" + index);
		}
	}

	@Test
	void anythingElseIsNoTarget() {
		for (String name : List.of("", "colortex", "depthtex", "colortex16", "colortex-1", "colortex+1",
				"colortex01", "colortex007", "colortex100", "colortex1a", "colortex 1", "depthtex3",
				"depthtex10", "depthtex01", "gcolor", "gdepth", "gaux1", "gdepthtex", "colorimg0",
				"shadowtex0", "noisetex", "Colortex0", "COLORTEX0", "colortex9999999999")) {
			assertNull(TargetRef.parse(name), "'" + name + "'");
		}
	}
}
