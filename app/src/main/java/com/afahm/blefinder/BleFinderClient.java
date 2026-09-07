package com.afahm.blefinder;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.*;
import java.util.*;

/** Foreground-only generic BLE scanning and read-only connected RSSI. No pairing or writes. */
@SuppressLint("MissingPermission")
public final class BleFinderClient {
  public static final class Device {
    public final String address;
    public String name;
    public int rssi = -127;
    public long seen;
    public boolean connectable, known;

    public Device(String address, String name, boolean connectable) {
      this.address = address;
      this.name = name;
      this.connectable = connectable;
    }

    public String title() {
      return name == null || name.trim().isEmpty() ? "Unnamed device" : name;
    }
  }

  public interface Listener {
    void onDevices(List<Device> devices);

    void onStatus(String message);

    void onSignal(int rssi, String source, long time);

    void onScanState(boolean scanning);
  }

  private final Context context;
  private final Listener listener;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Map<String, Device> devices = new LinkedHashMap<>();
  private BluetoothLeScanner scanner;
  private ScanCallback scan;
  private BluetoothGatt gatt;
  private Device target;
  private boolean active, linked;
  private int generation;
  private long lastScanStart = -10000, pendingRssiAt, lastGoodRead;

  public BleFinderClient(Context context, Listener listener) {
    this.context = context.getApplicationContext();
    this.listener = listener;
  }

  public static boolean hasPermissions(Context c) {
    boolean location =
        c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED;
    return location
        && (Build.VERSION.SDK_INT < 31
            || (c.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED
                && c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED));
  }

  public BluetoothAdapter adapter() {
    BluetoothManager m = context.getSystemService(BluetoothManager.class);
    return m == null ? null : m.getAdapter();
  }

  public void discover() {
    stop();
    active = true;
    devices.clear();
    if (!ready()) return;
    try {
      BluetoothManager m = context.getSystemService(BluetoothManager.class);
      for (BluetoothDevice d : adapter().getBondedDevices()) addKnown(d);
      for (BluetoothDevice d : m.getConnectedDevices(BluetoothProfile.GATT)) addKnown(d);
    } catch (RuntimeException ignored) {
    }
    listener.onDevices(new ArrayList<>(devices.values()));
    scheduleScan();
  }

  private void addKnown(BluetoothDevice d) {
    if (d.getType() == BluetoothDevice.DEVICE_TYPE_CLASSIC) return;
    Device item = new Device(d.getAddress(), d.getName(), true);
    item.known = true;
    devices.put(item.address, item);
  }

  public void locate(Device device) {
    stop();
    target = device;
    active = true;
    if (!ready()) return;
    listener.onStatus("Listening for signal…");
    scheduleScan();
    if (target.connectable || target.known) connect();
  }

  private boolean ready() {
    if (!hasPermissions(context)) {
      listener.onStatus("Allow Bluetooth and location to find nearby devices.");
      return false;
    }
    if (adapter() == null || !adapter().isEnabled()) {
      listener.onStatus("Turn on Bluetooth to start looking.");
      return false;
    }
    return true;
  }

  private void scheduleScan() {
    final int token = generation;
    long delay = Math.max(0, 6500 - (SystemClock.elapsedRealtime() - lastScanStart));
    main.postDelayed(
        () -> {
          if (active && generation == token && !linked) startScan(token);
        },
        delay);
  }

  private void startScan(int token) {
    if (scan != null || !ready()) return;
    scanner = adapter().getBluetoothLeScanner();
    if (scanner == null) {
      listener.onStatus("Bluetooth scanner is unavailable. Try again.");
      return;
    }
    ScanCallback callback =
        new ScanCallback() {
          @Override
          public void onScanResult(int type, ScanResult result) {
            main.post(
                () -> {
                  if (token == generation && scan == this) accept(result);
                });
          }

          @Override
          public void onBatchScanResults(List<ScanResult> results) {
            main.post(
                () -> {
                  if (token == generation && scan == this) for (ScanResult r : results) accept(r);
                });
          }

          @Override
          public void onScanFailed(int error) {
            main.post(
                () -> {
                  if (token != generation || scan != this) return;
                  scan = null;
                  listener.onScanState(false);
                  listener.onStatus(
                      "Scan unavailable (" + error + "). Wait a moment, then try again.");
                });
          }
        };
    scan = callback;
    List<ScanFilter> filters =
        target == null
            ? Collections.emptyList()
            : Collections.singletonList(
                new ScanFilter.Builder().setDeviceAddress(target.address).build());
    try {
      scanner.startScan(
          filters,
          new ScanSettings.Builder()
              .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
              .setReportDelay(0)
              .build(),
          callback);
      lastScanStart = SystemClock.elapsedRealtime();
      listener.onScanState(true);
      if (target == null) {
        listener.onStatus("Looking for nearby devices…");
        main.postDelayed(
            () -> {
              if (token != generation || target != null) return;
              stopScan();
              listener.onStatus("Scan complete. Tap refresh to look again.");
            },
            60000);
      }
    } catch (RuntimeException e) {
      scan = null;
      listener.onScanState(false);
      listener.onStatus("Couldn’t start scanning. Check Bluetooth and permissions.");
    }
  }

  private void accept(ScanResult result) {
    if (!SignalMath.validRssi(result.getRssi())) return;
    String address = result.getDevice().getAddress();
    String name = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
    if (name == null) name = result.getDevice().getName();
    long time = result.getTimestampNanos() / 1000000;
    if (target != null) {
      if (target.address.equals(address) && !linked)
        listener.onSignal(result.getRssi(), "Broadcast", time);
      return;
    }
    Device d = devices.get(address);
    boolean connectable = Build.VERSION.SDK_INT < 26 || result.isConnectable();
    if (d == null) {
      d = new Device(address, name, connectable);
      devices.put(address, d);
    }
    if (time <= d.seen) return;
    if (name != null && !name.isEmpty()) d.name = name;
    d.rssi = result.getRssi();
    d.seen = time;
    d.connectable = connectable;
    listener.onDevices(new ArrayList<>(devices.values()));
  }

  private void connect() {
    if (!active || target == null || gatt != null || !ready()) return;
    final int token = generation;
    try {
      BluetoothDevice device = adapter().getRemoteDevice(target.address);
      gatt =
          device.connectGatt(
              context,
              false,
              new BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
                  main.post(
                      () -> {
                        if (token != generation || g != gatt) {
                          g.close();
                          return;
                        }
                        if (status == BluetoothGatt.GATT_SUCCESS
                            && state == BluetoothProfile.STATE_CONNECTED) {
                          linked = true;
                          lastGoodRead = SystemClock.elapsedRealtime();
                          stopScan();
                          pendingRssiAt = 0;
                          listener.onStatus("Connected · listening for signal");
                          main.removeCallbacks(pollRssi);
                          main.post(pollRssi);
                        } else if (status != BluetoothGatt.GATT_SUCCESS
                            || state == BluetoothProfile.STATE_DISCONNECTED) {
                          fallback(token);
                        }
                      });
                }

                @Override
                public void onReadRemoteRssi(BluetoothGatt g, int rssi, int status) {
                  main.post(
                      () -> {
                        if (token != generation || g != gatt) return;
                        pendingRssiAt = 0;
                        if (status == BluetoothGatt.GATT_SUCCESS && SignalMath.validRssi(rssi)) {
                          lastGoodRead = SystemClock.elapsedRealtime();
                          listener.onSignal(rssi, "Connected", SystemClock.elapsedRealtime());
                        }
                      });
                }
              },
              BluetoothDevice.TRANSPORT_LE);
      if (gatt == null) {
        fallback(token);
        return;
      }
      main.postDelayed(
          () -> {
            if (generation == token && !linked && gatt != null) fallback(token);
          },
          18000);
    } catch (RuntimeException e) {
      fallback(token);
    }
  }

  private void fallback(int token) {
    closeGatt();
    if (!active || generation != token) return;
    listener.onStatus("Listening for broadcast signal…");
    scheduleScan();
    // Broadcast-only devices remain useful even if they reject a GATT connection.
    main.postDelayed(
        () -> {
          if (active && generation == token && !linked) connect();
        },
        30000);
  }

  private final Runnable pollRssi =
      new Runnable() {
        @Override
        public void run() {
          if (!active || !linked || gatt == null) return;
          long now = SystemClock.elapsedRealtime();
          if ((pendingRssiAt != 0 && now - pendingRssiAt > 5000) || now - lastGoodRead > 10000) {
            fallback(generation);
            return;
          }
          if (pendingRssiAt == 0) {
            try {
              if (gatt.readRemoteRssi()) pendingRssiAt = now;
            } catch (RuntimeException e) {
              fallback(generation);
              return;
            }
          }
          main.postDelayed(this, 700);
        }
      };

  private void stopScan() {
    ScanCallback old = scan;
    scan = null;
    if (old != null && scanner != null)
      try {
        scanner.stopScan(old);
      } catch (RuntimeException ignored) {
      }
    listener.onScanState(false);
  }

  private void closeGatt() {
    main.removeCallbacks(pollRssi);
    linked = false;
    pendingRssiAt = 0;
    BluetoothGatt old = gatt;
    gatt = null;
    if (old != null) {
      try {
        old.disconnect();
      } catch (RuntimeException ignored) {
      }
      old.close();
    }
  }

  public void stop() {
    active = false;
    generation++;
    main.removeCallbacksAndMessages(null);
    stopScan();
    closeGatt();
    target = null;
  }
}
