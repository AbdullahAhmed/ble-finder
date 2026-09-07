package com.afahm.blefinder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Deterministic RSSI calculations kept separate from Android APIs for testing. */
public final class SignalMath {
  public enum Trend {
    CLOSER,
    FARTHER,
    STEADY,
    UNKNOWN
  }

  public enum Freshness {
    LIVE,
    WAITING,
    LOST
  }

  public static final class TimedSignal {
    public final long timeMs;
    public final double rssi;

    public TimedSignal(long timeMs, double rssi) {
      this.timeMs = timeMs;
      this.rssi = rssi;
    }
  }

  private SignalMath() {}

  public static boolean validRssi(int rssi) {
    return rssi >= -126 && rssi <= -1;
  }

  public static double median(List<Integer> readings) {
    if (readings == null || readings.isEmpty()) {
      throw new IllegalArgumentException("At least one RSSI reading is required.");
    }
    List<Integer> sorted = new ArrayList<>(readings);
    Collections.sort(sorted);
    int middle = sorted.size() / 2;
    if ((sorted.size() & 1) == 1) {
      return sorted.get(middle);
    }
    return (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
  }

  public static double exponential(double previous, double current, double alpha) {
    if (Double.isNaN(previous)) {
      return current;
    }
    if (alpha < 0.0 || alpha > 1.0) {
      throw new IllegalArgumentException("Alpha must be between zero and one.");
    }
    return alpha * current + (1.0 - alpha) * previous;
  }

  public static String proximity(double rssi) {
    if (rssi >= -45) return "EXTREMELY CLOSE";
    if (rssi >= -55) return "VERY CLOSE";
    if (rssi >= -65) return "CLOSE";
    if (rssi >= -75) return "NEARBY";
    if (rssi >= -85) return "FAR";
    return "VERY WEAK";
  }

  public static Trend trend(List<TimedSignal> history, long nowMs) {
    if (history == null || history.size() < 2) {
      return Trend.UNKNOWN;
    }
    TimedSignal current = history.get(history.size() - 1);
    TimedSignal reference = null;
    for (int index = history.size() - 2; index >= 0; index--) {
      TimedSignal candidate = history.get(index);
      long age = nowMs - candidate.timeMs;
      if (age >= 3_000L) {
        reference = candidate;
        if (age >= 5_000L) break;
      }
    }
    if (reference == null) return Trend.UNKNOWN;
    double delta = current.rssi - reference.rssi;
    if (delta >= 3.0) return Trend.CLOSER;
    if (delta <= -3.0) return Trend.FARTHER;
    return Trend.STEADY;
  }

  public static Freshness freshness(long ageMs) {
    if (ageMs < 3_000L) return Freshness.LIVE;
    if (ageMs < 10_000L) return Freshness.WAITING;
    return Freshness.LOST;
  }

  public static int gaugePercent(double rssi) {
    double bounded = Math.max(-95.0, Math.min(-35.0, rssi));
    return (int) Math.round((bounded + 95.0) * 100.0 / 60.0);
  }

  public static long beepIntervalMs(double rssi) {
    if (rssi <= -80) return 2_000L;
    if (rssi <= -70) return interpolate(rssi, -80, 2_000, -70, 1_200);
    if (rssi <= -60) return interpolate(rssi, -70, 1_200, -60, 700);
    if (rssi <= -50) return interpolate(rssi, -60, 700, -50, 400);
    if (rssi <= -45) return interpolate(rssi, -50, 400, -45, 200);
    return 200L;
  }

  private static long interpolate(double value, double x0, long y0, double x1, long y1) {
    double fraction = (value - x0) / (x1 - x0);
    return Math.round(y0 + fraction * (y1 - y0));
  }
}
