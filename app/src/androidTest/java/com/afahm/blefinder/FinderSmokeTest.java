package com.afahm.blefinder;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import java.util.*;

/** Exercises the actual UI and signal handling, even on an emulator without BLE hardware. */
public final class FinderSmokeTest extends Instrumentation {
  private MainActivity activity;

  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    start();
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    SharedPreferences prefs = getTargetContext().getSharedPreferences("finder_preferences", 0);
    Map<String, ?> original = prefs.getAll();
    try {
      prefs
          .edit()
          .clear()
          .putBoolean("asked_permissions", true)
          .putBoolean("sound", false)
          .commit();
      Intent intent =
          new Intent(getTargetContext(), MainActivity.class)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      activity = (MainActivity) startActivitySync(intent);
      waitForIdleSync();
      assertText("Pick a device. Follow the clicks.");
      BleFinderClient.Device beacon =
          new BleFinderClient.Device("02:00:00:00:00:01", "Test beacon", false);
      beacon.rssi = -75;
      beacon.seen = SystemClock.elapsedRealtime();
      BleFinderClient.Device unnamed = new BleFinderClient.Device("02:00:00:00:00:02", null, false);
      runOnMainSync(() -> activity.onDevices(new ArrayList<>(Arrays.asList(beacon, unnamed))));
      SystemClock.sleep(1200);
      waitForIdleSync();
      assertText("Test beacon");
      assertText("Unnamed device");
      screenshot("picker");
      runOnMainSync(() -> ((EditText) findType(root(), EditText.class)).setText("00:00:02"));
      runOnMainSync(
          () -> {
            View named = findText(root(), "Test beacon");
            if (named != null && named.isShown())
              throw new AssertionError("Search did not filter named device");
          });
      assertText("Unnamed device");
      runOnMainSync(() -> ((EditText) findType(root(), EditText.class)).setText(""));
      runOnMainSync(
          () -> {
            View row = findText(root(), "Test beacon");
            while (row != null && !row.isClickable()) row = (View) row.getParent();
            if (row == null || !row.performClick())
              throw new AssertionError("Device row is not selectable");
          });
      assertText("Follow the signal");
      assertText("Sound off");
      runOnMainSync(() -> activity.onSignal(-70, "Broadcast", SystemClock.elapsedRealtime()));
      assertText("-70");
      assertText("●  Live signal");
      runOnMainSync(() -> activity.onSignal(127, "Broadcast", SystemClock.elapsedRealtime() + 1));
      assertText("-70");
      SystemClock.sleep(30);
      runOnMainSync(() -> activity.onSignal(-45, "Connected", SystemClock.elapsedRealtime()));
      assertText("-45"); // Switching sources resets smoothing rather than mixing unlike signals.
      screenshot("locator");
      SystemClock.sleep(3200);
      waitForIdleSync();
      assertText("—");
      assertText("Waiting for signal");
      runOnMainSync(
          () -> activity.onSignal(-80, "Connected", SystemClock.elapsedRealtime() - 10000));
      assertText("—"); // Delayed scan batches cannot revive a stale signal.
      runOnMainSync(() -> activity.onBackPressed());
      assertText("Pick a device. Follow the clicks.");
      result.putString(
          "stream",
          "\n"
              + "BLE_FINDER_SMOKE_PASS: picker, address search, unnamed devices, selection, RSSI,"
              + " invalid readings, source switching, staleness, back navigation\n");
    } catch (Throwable error) {
      result.putString(
          "stream", "\nBLE_FINDER_SMOKE_FAIL: " + android.util.Log.getStackTraceString(error));
    } finally {
      if (activity != null) runOnMainSync(() -> activity.finish());
      SharedPreferences.Editor editor = prefs.edit().clear();
      for (Map.Entry<String, ?> entry : original.entrySet()) {
        Object v = entry.getValue();
        String k = entry.getKey();
        if (v instanceof String) editor.putString(k, (String) v);
        else if (v instanceof Boolean) editor.putBoolean(k, (Boolean) v);
        else if (v instanceof Integer) editor.putInt(k, (Integer) v);
        else if (v instanceof Long) editor.putLong(k, (Long) v);
        else if (v instanceof Float) editor.putFloat(k, (Float) v);
      }
      editor.commit();
    }
    finish(
        result.getString("stream", "").contains("SMOKE_PASS")
            ? Activity.RESULT_OK
            : Activity.RESULT_CANCELED,
        result);
  }

  private View root() {
    return activity.getWindow().getDecorView();
  }

  private void screenshot(String name) {
    waitForIdleSync();
    android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot();
    if (bitmap == null) return;
    java.io.File directory = getTargetContext().getExternalFilesDir(null);
    try (java.io.FileOutputStream output =
        new java.io.FileOutputStream(new java.io.File(directory, name + ".png"))) {
      bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
    } catch (java.io.IOException error) {
      throw new AssertionError(error);
    }
    bitmap.recycle();
  }

  private void assertText(String text) {
    runOnMainSync(
        () -> {
          View v = findText(root(), text);
          if (v == null || !v.isShown()) throw new AssertionError("Missing UI text: " + text);
        });
  }

  private View findText(View v, String text) {
    if (v instanceof TextView && ((TextView) v).getText().toString().equals(text)) return v;
    if (v instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
        View found = findText(((ViewGroup) v).getChildAt(i), text);
        if (found != null) return found;
      }
    return null;
  }

  private View findType(View v, Class<?> type) {
    if (type.isInstance(v)) return v;
    if (v instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
        View found = findType(((ViewGroup) v).getChildAt(i), type);
        if (found != null) return found;
      }
    return null;
  }
}
