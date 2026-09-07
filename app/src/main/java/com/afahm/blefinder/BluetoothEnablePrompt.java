package com.afahm.blefinder;

/** Avoid duplicate system dialogs and respect cancellation until an explicit retry. */
final class BluetoothEnablePrompt {
  boolean requested, pending;

  boolean request(boolean interactive) {
    if (pending || (requested && !interactive)) return false;
    requested = true;
    pending = true;
    return true;
  }

  void finished() {
    pending = false;
  }

  void enabled() {
    requested = false;
  }
}
