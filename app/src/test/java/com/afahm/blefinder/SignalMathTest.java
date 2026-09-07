package com.afahm.blefinder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class SignalMathTest {
  @Test
  public void unavailableRssiMustNotSoundLikeANearbyDevice() {
    assertFalse(SignalMath.validRssi(127));
    assertFalse(SignalMath.validRssi(0));
    assertFalse(SignalMath.validRssi(-127));
    assertTrue(SignalMath.validRssi(-95));
    assertTrue(SignalMath.validRssi(-30));
  }

  @Test
  public void medianHandlesOddAndEvenWindows() {
    assertEquals(-60.0, SignalMath.median(Arrays.asList(-90, -60, -40, -62, -59)), 0.001);
    assertEquals(-60.5, SignalMath.median(Arrays.asList(-80, -61, -60, -40)), 0.001);
  }

  @Test
  public void exponentialUsesRequestedAlpha() {
    assertEquals(-67.5, SignalMath.exponential(-70, -60, 0.25), 0.001);
    assertEquals(-60.0, SignalMath.exponential(Double.NaN, -60, 0.25), 0.001);
  }

  @Test
  public void proximityBandsMatchFinderLanguage() {
    assertEquals("EXTREMELY CLOSE", SignalMath.proximity(-45));
    assertEquals("VERY CLOSE", SignalMath.proximity(-46));
    assertEquals("CLOSE", SignalMath.proximity(-56));
    assertEquals("NEARBY", SignalMath.proximity(-66));
    assertEquals("FAR", SignalMath.proximity(-76));
    assertEquals("VERY WEAK", SignalMath.proximity(-86));
  }

  @Test
  public void trendRequiresSustainedChangeAndDeadZone() {
    List<SignalMath.TimedSignal> closer =
        Arrays.asList(
            new SignalMath.TimedSignal(0, -70),
            new SignalMath.TimedSignal(2_000, -66),
            new SignalMath.TimedSignal(4_000, -62));
    assertEquals(SignalMath.Trend.CLOSER, SignalMath.trend(closer, 4_000));

    List<SignalMath.TimedSignal> steady =
        Arrays.asList(new SignalMath.TimedSignal(0, -55), new SignalMath.TimedSignal(4_000, -53));
    assertEquals(SignalMath.Trend.STEADY, SignalMath.trend(steady, 4_000));
  }

  @Test
  public void sessionBestIsTheLeastNegativeReading() {
    int best = Integer.MIN_VALUE;
    int worst = Integer.MAX_VALUE;
    for (int reading : new int[] {-72, -65, -81, -48}) {
      best = Math.max(best, reading);
      worst = Math.min(worst, reading);
    }
    assertEquals(-48, best);
    assertEquals(-81, worst);
  }

  @Test
  public void lostSignalStatesHaveGracePeriods() {
    assertEquals(SignalMath.Freshness.LIVE, SignalMath.freshness(2_999));
    assertEquals(SignalMath.Freshness.WAITING, SignalMath.freshness(3_000));
    assertEquals(SignalMath.Freshness.LOST, SignalMath.freshness(10_000));
  }

  @Test
  public void beepIntervalsAccelerateAsSignalStrengthens() {
    assertEquals(2_000, SignalMath.beepIntervalMs(-90));
    assertEquals(1_200, SignalMath.beepIntervalMs(-70));
    assertEquals(700, SignalMath.beepIntervalMs(-60));
    assertEquals(400, SignalMath.beepIntervalMs(-50));
    assertEquals(200, SignalMath.beepIntervalMs(-40));
  }
}
