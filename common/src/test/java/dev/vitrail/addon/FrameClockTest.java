package dev.vitrail.addon;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Holds the number a frame is told, which counts up by one and has nothing to do with the pack. */
class FrameClockTest {

	@Test
	void theFirstFrameIsNoughtAndEveryFrameAfterItIsOneMore() {
		FrameClock clock = new FrameClock();

		for (long expected = 0; expected < 1000; expected++) {
			assertEquals(expected, clock.tick());
		}
	}

	@Test
	void twoClocksCountApart() {
		FrameClock first = new FrameClock();
		FrameClock second = new FrameClock();

		first.tick();
		first.tick();

		assertEquals(0, second.tick());
		assertEquals(2, first.tick());
	}
}
