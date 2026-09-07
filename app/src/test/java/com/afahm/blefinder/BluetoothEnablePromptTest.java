package com.afahm.blefinder;

import static org.junit.Assert.*;

import org.junit.Test;

public class BluetoothEnablePromptTest {
  @Test
  public void automaticallyRequestsOnceWithoutDuplicateDialogs() {
    BluetoothEnablePrompt prompt = new BluetoothEnablePrompt();
    assertTrue(prompt.request(false));
    assertFalse(prompt.request(false));
    assertFalse(prompt.request(true));
  }

  @Test
  public void cancellationDoesNotRepromptOnResumeButAllowsTapToRetry() {
    BluetoothEnablePrompt prompt = new BluetoothEnablePrompt();
    prompt.request(false);
    prompt.finished();
    assertFalse(prompt.request(false));
    assertTrue(prompt.request(true));
  }

  @Test
  public void enablingRearmsPromptForNextBluetoothOffEvent() {
    BluetoothEnablePrompt prompt = new BluetoothEnablePrompt();
    prompt.request(false);
    prompt.finished();
    prompt.enabled();
    assertTrue(prompt.request(false));
  }

  @Test
  public void enabledBroadcastCannotDuplicatePendingDialog() {
    BluetoothEnablePrompt prompt = new BluetoothEnablePrompt();
    prompt.request(false);
    prompt.enabled();
    assertFalse(prompt.request(true));
    prompt.finished();
    assertTrue(prompt.request(false));
  }

  @Test
  public void recreatedActivityRetainsCancellationAndPendingState() {
    BluetoothEnablePrompt restored = new BluetoothEnablePrompt();
    restored.requested = true;
    restored.pending = true;
    assertFalse(restored.request(false));
    restored.finished();
    assertFalse(restored.request(false));
    assertTrue(restored.request(true));
  }
}
