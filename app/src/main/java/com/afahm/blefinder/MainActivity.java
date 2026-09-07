package com.afahm.blefinder;

import android.Manifest;
import android.app.*;
import android.bluetooth.BluetoothAdapter;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.location.LocationManager;
import android.media.*;
import android.os.*;
import android.provider.Settings;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Choose a BLE device, then follow its live signal. */
public final class MainActivity extends Activity implements BleFinderClient.Listener {
  private static final int BG = 0xff0b1619, PANEL = 0xff152529, LINE = 0xff2b4044;
  private static final int INK = 0xfff0f4eb,
      MUTED = 0xff9db2b4,
      MINT = 0xffbafa73,
      AMBER = 0xffffca80;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final LinkedHashMap<String, BleFinderClient.Device> devices = new LinkedHashMap<>();
  private final LinkedHashMap<String, DeviceRow> rows = new LinkedHashMap<>();
  private final ArrayDeque<Integer> window = new ArrayDeque<>();
  private final ArrayDeque<SignalMath.TimedSignal> history = new ArrayDeque<>();
  private BleFinderClient client;
  private SharedPreferences prefs;
  private ToneGenerator tone;
  private Vibrator vibrator;
  private LinearLayout root, list;
  private TextView status, count, empty, number, strength, trend, lastSeen, bestText;
  private Button scanButton, soundButton, hapticButton;
  private SignalDial dial;
  private HistoryView chart;
  private EditText search;
  private BleFinderClient.Device target, recent;
  private boolean resumed, paused, sound = true, haptics, askingPermission;
  private long lastAt, sessionAt, lastClick;
  private double smoothed = Double.NaN, best = Double.NEGATIVE_INFINITY;
  private String source = "", statusMessage = "Looking for nearby devices…";
  private final BroadcastReceiver bluetoothState =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
          if (!resumed || paused) return;
          int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
          if (state == BluetoothAdapter.STATE_OFF) {
            client.stop();
            onStatus("Bluetooth is off. Turn it on to continue.");
          } else if (state == BluetoothAdapter.STATE_ON) begin(false);
        }
      };

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    prefs = getSharedPreferences("finder_preferences", MODE_PRIVATE);
    sound = prefs.getBoolean("sound", true);
    haptics = prefs.getBoolean("haptics", false);
    String address = prefs.getString("target_address", "");
    if (BluetoothAdapter.checkBluetoothAddress(address)) {
      recent =
          new BleFinderClient.Device(
              address,
              prefs.getString("target_name", "Last device"),
              prefs.getBoolean("target_connectable", true));
      recent.known = true;
    }
    client = new BleFinderClient(this, this);
    try {
      tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 70);
    } catch (RuntimeException ignored) {
    }
    setVolumeControlStream(AudioManager.STREAM_MUSIC);
    vibrator = getSystemService(Vibrator.class);
    if (Build.VERSION.SDK_INT >= 33)
      registerReceiver(
          bluetoothState,
          new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
          RECEIVER_EXPORTED);
    else registerReceiver(bluetoothState, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
    if (saved != null && saved.containsKey("address")) {
      target =
          new BleFinderClient.Device(
              saved.getString("address"), saved.getString("name"), saved.getBoolean("connectable"));
      paused = saved.getBoolean("paused");
    }
    buildScreen();
  }

  @Override
  protected void onResume() {
    super.onResume();
    resumed = true;
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    main.post(ticker);
    if (!paused) begin(false);
  }

  @Override
  protected void onPause() {
    super.onPause();
    resumed = false;
    main.removeCallbacks(ticker);
    client.stop();
    if (tone != null) tone.stopTone();
    if (vibrator != null) vibrator.cancel();
    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  }

  @Override
  protected void onDestroy() {
    client.stop();
    main.removeCallbacksAndMessages(null);
    unregisterReceiver(bluetoothState);
    if (tone != null) tone.release();
    super.onDestroy();
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    if (target != null) {
      state.putString("address", target.address);
      state.putString("name", target.name);
      state.putBoolean("connectable", target.connectable);
      state.putBoolean("paused", paused);
    }
    super.onSaveInstanceState(state);
  }

  @Override
  public void onBackPressed() {
    if (target != null) showPicker();
    else super.onBackPressed();
  }

  private void buildScreen() {
    root = column();
    root.setBackgroundColor(BG);
    root.setPadding(dp(24), dp(12), dp(24), dp(16));
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          root.setPadding(
              dp(24) + insets.getSystemWindowInsetLeft(),
              dp(12) + insets.getSystemWindowInsetTop(),
              dp(24) + insets.getSystemWindowInsetRight(),
              dp(16) + insets.getSystemWindowInsetBottom());
          return insets;
        });
    setContentView(root);
    root.requestApplyInsets();
    if (target == null) buildPicker();
    else buildLocator();
  }

  private void buildPicker() {
    LinearLayout header = row();
    TextView brand = label("BLE FINDER", 14, MINT);
    brand.setLetterSpacing(.12f);
    bold(brand);
    header.addView(brand, new LinearLayout.LayoutParams(0, dp(48), 1));
    Button help = button("?", false);
    help.setContentDescription("How to use BLE Finder");
    help.setOnClickListener(v -> showHelp());
    header.addView(help, lp(dp(48), dp(48)));
    root.addView(header);
    TextView title = label("Find your\nmissing thing.", 38, INK);
    bold(title);
    title.setLineSpacing(0, 1.02f);
    root.addView(title, margins(-1, -2, 0, 16, 0, 8));
    root.addView(label("Pick a device. Follow the clicks.", 17, MUTED));
    search = new EditText(this);
    search.setSingleLine(true);
    search.setTextSize(16);
    search.setTextColor(INK);
    search.setHintTextColor(MUTED);
    search.setHint("Search name or address");
    search.setPadding(dp(18), 0, dp(16), 0);
    search.setBackground(shape(PANEL, 18, LINE));
    root.addView(search, margins(-1, dp(56), 0, 24, 0, 18));
    search.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int a, int c, int f) {}

          public void onTextChanged(CharSequence s, int a, int b, int c) {
            renderDevices();
          }

          public void afterTextChanged(Editable e) {}
        });
    LinearLayout scanRow = row();
    count = label("NEARBY DEVICES", 12, MUTED);
    count.setLetterSpacing(.08f);
    bold(count);
    scanRow.addView(count, new LinearLayout.LayoutParams(0, dp(48), 1));
    scanButton = button("Refresh", false);
    scanButton.setOnClickListener(v -> begin(true));
    scanRow.addView(scanButton, lp(dp(105), dp(48)));
    root.addView(scanRow);
    status = label(statusMessage, 13, MUTED);
    root.addView(status, margins(-1, -2, 0, 0, 0, 10));
    ScrollView scroll = new ScrollView(this);
    scroll.setClipToPadding(false);
    list = column();
    scroll.addView(list);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    TextView footer = label("Bluetooth LE devices only · no account needed", 12, MUTED);
    footer.setGravity(Gravity.CENTER);
    root.addView(footer, margins(-1, -2, 0, 12, 0, 0));
    rows.clear();
    if (recent != null && !devices.containsKey(recent.address)) devices.put(recent.address, recent);
    renderDevices();
    root.setFocusableInTouchMode(true);
    root.requestFocus();
  }

  private void buildLocator() {
    LinearLayout header = row();
    Button back = button("‹", false);
    back.setTextSize(32);
    back.setContentDescription("Choose another device");
    back.setOnClickListener(v -> showPicker());
    header.addView(back, lp(dp(48), dp(48)));
    LinearLayout identity = column();
    TextView name = label(target.title(), 19, INK);
    bold(name);
    name.setSingleLine();
    name.setEllipsize(TextUtils.TruncateAt.END);
    identity.addView(name);
    identity.addView(label(target.address, 11, MUTED));
    LinearLayout.LayoutParams idParams = new LinearLayout.LayoutParams(0, -2, 1);
    idParams.setMargins(dp(12), 0, dp(8), 0);
    header.addView(identity, idParams);
    Button info = button("?", false);
    info.setContentDescription("Signal finding tips");
    info.setOnClickListener(v -> showHelp());
    header.addView(info, lp(dp(48), dp(48)));
    root.addView(header);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    LinearLayout content = column();
    content.setGravity(Gravity.CENTER_HORIZONTAL);
    scroll.addView(content);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    status = label("Listening for signal…", 13, MINT);
    status.setGravity(Gravity.CENTER);
    content.addView(status, margins(-1, -2, 0, 20, 0, 6));
    TextView heading = label("Follow the signal", 29, INK);
    bold(heading);
    heading.setGravity(Gravity.CENTER);
    content.addView(heading);
    FrameLayout meter = new FrameLayout(this);
    dial = new SignalDial();
    meter.addView(dial, new FrameLayout.LayoutParams(-1, -1));
    LinearLayout center = column();
    center.setGravity(Gravity.CENTER);
    TextView caption = label("SIGNAL STRENGTH", 11, MUTED);
    caption.setLetterSpacing(.1f);
    center.addView(caption);
    number = label("—", 76, INK);
    number.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
    number.setGravity(Gravity.CENTER);
    number.setFontFeatureSettings("tnum");
    center.addView(number, lp(-1, -2));
    center.addView(label("dBm", 14, MUTED));
    strength = label("Waiting for a reading", 16, MINT);
    bold(strength);
    center.addView(strength, margins(-2, -2, 0, 12, 0, 0));
    center.setPadding(0, 0, 0, dp(10));
    meter.addView(center, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
    content.addView(meter, margins(-1, dp(275), 0, 8, 0, 0));
    trend = label("Walk slowly. Pause to compare.", 20, INK);
    bold(trend);
    trend.setGravity(Gravity.CENTER);
    content.addView(trend, lp(-1, -2));
    lastSeen = label("Faster clicks mean a stronger signal.", 13, MUTED);
    lastSeen.setGravity(Gravity.CENTER);
    content.addView(lastSeen, margins(-1, -2, 0, 7, 0, 12));
    LinearLayout trace = column();
    trace.setPadding(dp(16), dp(12), dp(16), dp(10));
    trace.setBackground(shape(PANEL, 18, 0));
    LinearLayout traceHeader = row();
    TextView traceCaption = label("LAST 30 SECONDS", 10, MUTED);
    traceCaption.setLetterSpacing(.07f);
    traceHeader.addView(traceCaption, new LinearLayout.LayoutParams(0, -2, 1));
    bestText = label("Best  —", 12, MINT);
    traceHeader.addView(bestText);
    trace.addView(traceHeader);
    chart = new HistoryView();
    trace.addView(chart, margins(-1, dp(42), 0, 6, 0, 0));
    content.addView(trace, margins(-1, -2, 0, 4, 0, 16));
    LinearLayout controls = row();
    soundButton = button("", false);
    hapticButton = button("", false);
    soundButton.setOnClickListener(
        v -> {
          sound = !sound;
          prefs.edit().putBoolean("sound", sound).apply();
          updateToggles();
        });
    hapticButton.setOnClickListener(
        v -> {
          haptics = !haptics;
          prefs.edit().putBoolean("haptics", haptics).apply();
          updateToggles();
        });
    controls.addView(soundButton, new LinearLayout.LayoutParams(0, dp(54), 1));
    controls.addView(new Space(this), lp(dp(10), 1));
    controls.addView(hapticButton, new LinearLayout.LayoutParams(0, dp(54), 1));
    content.addView(controls, margins(-1, -2, 0, 0, 0, 12));
    updateToggles();
    Button found = button("Found it", true);
    found.setOnClickListener(
        v -> {
          client.stop();
          paused = true;
          if (tone != null) tone.stopTone();
          new AlertDialog.Builder(this)
              .setTitle("There it is.")
              .setMessage("Search stopped. Your device is saved for next time.")
              .setPositiveButton("Done", (d, w) -> showPicker())
              .setOnCancelListener(d -> showPicker())
              .show();
        });
    content.addView(found, margins(-1, dp(58), 0, 0, 0, 6));
    TextView stop = label("Stop searching", 14, MUTED);
    stop.setGravity(Gravity.CENTER);
    stop.setOnClickListener(v -> showPicker());
    content.addView(stop, lp(-1, dp(48)));
    if (paused) showPaused();
    else updateSignalUi();
  }

  private void updateToggles() {
    soundButton.setText(sound ? "Sound on" : "Sound off");
    hapticButton.setText(haptics ? "Vibration on" : "Vibration off");
    soundButton.setTextColor(sound ? MINT : MUTED);
    hapticButton.setTextColor(haptics ? MINT : MUTED);
    soundButton.setBackground(shape(PANEL, 16, sound ? 0xff608348 : LINE));
    hapticButton.setBackground(shape(PANEL, 16, haptics ? 0xff608348 : LINE));
    if (!sound && tone != null) tone.stopTone();
    if (!haptics && vibrator != null) vibrator.cancel();
  }

  private void renderDevices() {
    if (target != null || list == null) return;
    String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
    int visible = 0;
    for (BleFinderClient.Device d : devices.values()) {
      DeviceRow r = rows.get(d.address);
      if (r == null) {
        r = new DeviceRow(d);
        rows.put(d.address, r);
        list.addView(r, margins(-1, -2, 0, 0, 0, 10));
      }
      r.update(d);
      boolean match = (d.title() + " " + d.address).toLowerCase(Locale.ROOT).contains(query);
      r.setVisibility(match ? View.VISIBLE : View.GONE);
      if (match) visible++;
    }
    if (empty == null || empty.getParent() != list) {
      empty = label("", 17, MUTED);
      empty.setGravity(Gravity.CENTER);
      empty.setPadding(dp(12), dp(30), dp(12), dp(30));
      list.addView(empty);
    }
    empty.setText(
        query.isEmpty()
            ? "Listening for nearby devices…\n\nKeep Bluetooth and Location on."
            : "No matching devices");
    empty.setVisibility(visible == 0 ? View.VISIBLE : View.GONE);
    count.setText("DEVICES  ·  " + visible);
  }

  private final class DeviceRow extends LinearLayout {
    final TextView name, detail, signal;
    BleFinderClient.Device item;

    DeviceRow(BleFinderClient.Device d) {
      super(MainActivity.this);
      setOrientation(HORIZONTAL);
      setGravity(Gravity.CENTER_VERTICAL);
      setPadding(dp(16), dp(17), dp(16), dp(17));
      setMinimumHeight(dp(92));
      setBackground(shape(PANEL, 20, LINE));
      LinearLayout labels = column();
      name = label("", 18, INK);
      bold(name);
      name.setMaxLines(2);
      name.setEllipsize(TextUtils.TruncateAt.END);
      detail = label("", 11, MUTED);
      labels.addView(name);
      labels.addView(detail, margins(-1, -2, 0, 6, 0, 0));
      addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
      signal = label("", 16, MINT);
      bold(signal);
      signal.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
      addView(signal, margins(dp(68), -2, 10, 0, 0, 0));
      setFocusable(true);
      setOnClickListener(v -> select(item));
      update(d);
    }

    void update(BleFinderClient.Device d) {
      item = d;
      name.setText(d.title());
      boolean fresh = d.seen > 0 && SystemClock.elapsedRealtime() - d.seen < 10000;
      boolean saved = recent != null && recent.address.equals(d.address);
      detail.setText((saved ? "RECENT  ·  " : "") + d.address);
      signal.setText(fresh ? d.rssi + "\ndBm" : "Find  ›");
      signal.setTextColor(fresh ? MINT : MUTED);
      setContentDescription(
          d.title()
              + ", "
              + d.address
              + (fresh ? ", " + d.rssi + " dBm" : ", not seen recently")
              + ", tap to find");
    }
  }

  private void select(BleFinderClient.Device item) {
    if (item == null) return;
    android.view.inputmethod.InputMethodManager keyboard =
        getSystemService(android.view.inputmethod.InputMethodManager.class);
    if (search != null) keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
    client.stop();
    target = item;
    recent = item;
    paused = false;
    resetSignal();
    prefs
        .edit()
        .putString("target_address", item.address)
        .putString("target_name", item.title())
        .putBoolean("target_connectable", item.connectable)
        .apply();
    buildScreen();
    begin(true);
  }

  private void showPicker() {
    client.stop();
    target = null;
    paused = false;
    devices.clear();
    resetSignal();
    buildScreen();
    begin(false);
  }

  private void resetSignal() {
    window.clear();
    history.clear();
    smoothed = Double.NaN;
    best = Double.NEGATIVE_INFINITY;
    lastAt = 0;
    source = "";
    sessionAt = SystemClock.elapsedRealtime();
  }

  private void begin(boolean interactive) {
    if (!resumed || paused) return;
    if (!BleFinderClient.hasPermissions(this)) {
      onStatus("Allow Bluetooth and location to find nearby devices.");
      if (!askingPermission && (interactive || !prefs.getBoolean("asked_permissions", false))) {
        askingPermission = true;
        prefs.edit().putBoolean("asked_permissions", true).apply();
        ArrayList<String> p = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
          p.add(Manifest.permission.BLUETOOTH_SCAN);
          p.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        p.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        requestPermissions(p.toArray(new String[0]), 701);
      }
      return;
    }
    BluetoothAdapter adapter = client.adapter();
    if (adapter == null) {
      onStatus("This phone does not support Bluetooth LE.");
      return;
    }
    if (!adapter.isEnabled()) {
      onStatus("Bluetooth is off. Tap Refresh to turn it on.");
      if (interactive)
        try {
          startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
        } catch (SecurityException e) {
          onStatus("Allow Nearby Devices permission in Android settings.");
        }
      return;
    }
    LocationManager location = getSystemService(LocationManager.class);
    boolean locationOn =
        Build.VERSION.SDK_INT >= 28
            ? location.isLocationEnabled()
            : location.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || location.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    if (!locationOn) {
      onStatus("Turn on Location so Android can scan for devices.");
      if (interactive) startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
      return;
    }
    if (target == null) client.discover();
    else {
      sessionAt = SystemClock.elapsedRealtime();
      client.locate(target);
    }
  }

  @Override
  public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
    super.onRequestPermissionsResult(code, permissions, grants);
    askingPermission = false;
    if (code != 701) return;
    if (BleFinderClient.hasPermissions(this)) {
      if (resumed) begin(false);
    } else {
      onStatus("Permission needed. Tap here to open app settings.");
      status.setOnClickListener(
          v ->
              startActivity(
                  new Intent(
                      Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                      android.net.Uri.parse("package:" + getPackageName()))));
    }
  }

  @Override
  public void onDevices(List<BleFinderClient.Device> found) {
    Collections.sort(found, (a, b) -> Integer.compare(b.rssi, a.rssi));
    for (BleFinderClient.Device d : found) devices.put(d.address, d);
  }

  @Override
  public void onStatus(String text) {
    statusMessage = text;
    if (status != null) status.setText(text);
  }

  @Override
  public void onScanState(boolean value) {
    if (target == null && scanButton != null) scanButton.setText(value ? "Scanning…" : "Refresh");
  }

  @Override
  public void onSignal(int rssi, String kind, long time) {
    if (target == null
        || paused
        || !SignalMath.validRssi(rssi)
        || time <= lastAt
        || SystemClock.elapsedRealtime() - time > 3000) return;
    if (!source.equals(kind) || (lastAt > 0 && time - lastAt > 5000)) {
      window.clear();
      history.clear();
      smoothed = Double.NaN;
    }
    source = kind;
    lastAt = time;
    window.add(rssi);
    while (window.size() > 3) window.removeFirst();
    smoothed = SignalMath.exponential(smoothed, SignalMath.median(new ArrayList<>(window)), .45);
    best = Math.max(best, smoothed);
    history.add(new SignalMath.TimedSignal(time, smoothed));
    while (!history.isEmpty() && time - history.peekFirst().timeMs > 30000) history.removeFirst();
    updateSignalUi();
  }

  private void updateSignalUi() {
    if (target == null || number == null || paused) return;
    long age = lastAt == 0 ? Long.MAX_VALUE : SystemClock.elapsedRealtime() - lastAt;
    boolean live = age < 3000;
    number.setText(live ? Integer.toString((int) Math.round(smoothed)) : "—");
    number.setTextColor(live ? INK : MUTED);
    strength.setText(
        live ? signalLabel(smoothed) : age < 10000 ? "Waiting for signal" : "No signal yet");
    strength.setTextColor(live ? MINT : AMBER);
    dial.value = live ? SignalMath.gaugePercent(smoothed) / 100f : 0;
    dial.live = live;
    dial.invalidate();
    chart.invalidate();
    bestText.setText(
        best == Double.NEGATIVE_INFINITY ? "Best  —" : "Best  " + Math.round(best) + " dBm");
    if (live) {
      status.setText("●  Live signal");
      SignalMath.Trend direction =
          SignalMath.trend(new ArrayList<>(history), SystemClock.elapsedRealtime());
      trend.setText(
          direction == SignalMath.Trend.CLOSER
              ? "↗  Getting warmer"
              : direction == SignalMath.Trend.FARTHER
                  ? "↘  Getting colder"
                  : direction == SignalMath.Trend.STEADY
                      ? "Holding steady"
                      : "Walk slowly. Pause to compare.");
      trend.setTextColor(direction == SignalMath.Trend.FARTHER ? AMBER : INK);
      lastSeen.setText("Faster clicks mean a stronger signal.");
    } else {
      status.setText(statusMessage);
      trend.setText(
          lastAt == 0 ? "Try moving to another spot" : "Move back toward the last signal");
      trend.setTextColor(MUTED);
      lastSeen.setText(
          lastAt == 0
              ? "Listening for " + target.title()
              : "Last heard " + age / 1000 + " seconds ago · clicks paused");
    }
  }

  private String signalLabel(double rssi) {
    if (rssi >= -50) return "Very strong signal";
    if (rssi >= -65) return "Strong signal";
    if (rssi >= -78) return "Moderate signal";
    if (rssi >= -90) return "Weak signal";
    return "Very weak signal";
  }

  private final Runnable ticker =
      new Runnable() {
        long rendered;

        @Override
        public void run() {
          if (!resumed) return;
          long now = SystemClock.elapsedRealtime();
          if (target == null) {
            if (now - rendered > 1000) {
              renderDevices();
              rendered = now;
            }
          } else if (!paused) {
            updateSignalUi();
            if (now - sessionAt > 20 * 60 * 1000) {
              paused = true;
              client.stop();
              showPaused();
            } else if (lastAt > 0
                && now - lastAt < 3000
                && now - lastClick >= SignalMath.beepIntervalMs(smoothed)) {
              lastClick = now;
              if (sound && tone != null) tone.startTone(ToneGenerator.TONE_PROP_BEEP, 22);
              if (haptics && vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= 26)
                  vibrator.vibrate(VibrationEffect.createOneShot(20, 80));
                else vibrator.vibrate(20);
              }
            }
          }
          main.postDelayed(this, 100);
        }
      };

  private void showPaused() {
    status.setText("Search paused after 20 minutes · tap to resume");
    status.setOnClickListener(
        v -> {
          paused = false;
          resetSignal();
          begin(true);
          status.setOnClickListener(null);
        });
    number.setText("—");
    strength.setText("Search paused");
    dial.live = false;
    dial.invalidate();
  }

  private void showHelp() {
    new AlertDialog.Builder(this)
        .setTitle("Follow the clicks")
        .setMessage(
            "1. Choose a device. Identify unnamed devices by their Bluetooth address.\n\n"
                + "2. Walk slowly, holding the phone the same way. Pause a few seconds at each"
                + " spot.\n\n"
                + "3. Less negative is stronger: −50 beats −80. Clicks speed up as the signal"
                + " improves. Adjust media volume with the phone’s buttons.\n\n"
                + "Signal strength is a clue, not an exact distance or direction. Walls, your body"
                + " and furniture affect it.\n\n"
                + "Devices must broadcast Bluetooth LE or accept a BLE connection. Sleeping devices"
                + " and changing private addresses may not be trackable. Classic-only Bluetooth"
                + " devices aren’t supported.\n\n"
                + "Bluetooth and Location permissions let Android find the signals. No GPS position"
                + " is read or saved. Searching stops when you leave this app.")
        .setPositiveButton("Got it", null)
        .show();
  }

  private final class SignalDial extends View {
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    float value;
    boolean live;

    SignalDial() {
      super(MainActivity.this);
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onDraw(Canvas c) {
      float radius = Math.min(getWidth() * .46f, getHeight() * .47f),
          x = getWidth() / 2f,
          y = getHeight() / 2f;
      RectF bounds = new RectF(x - radius, y - radius, x + radius, y + radius);
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(dp(3));
      p.setStrokeCap(Paint.Cap.ROUND);
      for (int i = 0; i < 55; i++) {
        p.setColor(live && i < Math.round(value * 55) ? MINT : LINE);
        c.drawArc(bounds, 135 + i * 5, 2.3f, false, p);
      }
      p.setStrokeWidth(dp(1));
      p.setColor(LINE);
      RectF inner = new RectF(bounds);
      inner.inset(dp(12), dp(12));
      c.drawArc(inner, 135, 270, false, p);
      if (live) {
        double a = Math.toRadians(135 + 270 * value);
        p.setStyle(Paint.Style.FILL);
        p.setColor(MINT);
        c.drawCircle(x + (float) Math.cos(a) * radius, y + (float) Math.sin(a) * radius, dp(5), p);
      }
    }
  }

  private final class HistoryView extends View {
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    HistoryView() {
      super(MainActivity.this);
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onDraw(Canvas c) {
      long now = SystemClock.elapsedRealtime();
      p.setStrokeWidth(dp(1));
      p.setColor(LINE);
      c.drawLine(0, getHeight() - 2, getWidth(), getHeight() - 2, p);
      if (history.size() < 2) return;
      Path path = new Path();
      boolean first = true;
      long previous = 0;
      for (SignalMath.TimedSignal point : history) {
        float x = getWidth() * (1 - (now - point.timeMs) / 30000f),
            y = dp(4) + (getHeight() - dp(8)) * (1 - SignalMath.gaugePercent(point.rssi) / 100f);
        if (x < 0) continue;
        if (first || point.timeMs - previous > 3000) path.moveTo(x, y);
        else path.lineTo(x, y);
        first = false;
        previous = point.timeMs;
      }
      p.setColor(MINT);
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(dp(2));
      p.setStrokeJoin(Paint.Join.ROUND);
      c.drawPath(path, p);
      p.setStyle(Paint.Style.FILL);
    }
  }

  private LinearLayout column() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  private LinearLayout row() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.HORIZONTAL);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  private TextView label(String s, float size, int color) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setGravity(Gravity.CENTER_VERTICAL);
    t.setIncludeFontPadding(false);
    return t;
  }

  private void bold(TextView t) {
    t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
  }

  private Button button(String s, boolean primary) {
    Button b = new Button(this);
    b.setText(s);
    b.setAllCaps(false);
    b.setTextSize(16);
    bold(b);
    b.setTextColor(primary ? BG : INK);
    b.setBackground(shape(primary ? MINT : PANEL, 18, primary ? 0 : LINE));
    b.setPadding(dp(8), 0, dp(8), 0);
    b.setMinWidth(0);
    b.setMinimumWidth(0);
    b.setMinHeight(dp(48));
    b.setStateListAnimator(null);
    return b;
  }

  private GradientDrawable shape(int fill, int radius, int stroke) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(fill);
    d.setCornerRadius(dp(radius));
    if (stroke != 0) d.setStroke(dp(1), stroke);
    return d;
  }

  private LinearLayout.LayoutParams lp(int w, int h) {
    return new LinearLayout.LayoutParams(w, h);
  }

  private LinearLayout.LayoutParams margins(int w, int h, int l, int t, int r, int b) {
    LinearLayout.LayoutParams p = lp(w, h);
    p.setMargins(dp(l), dp(t), dp(r), dp(b));
    return p;
  }

  private int dp(float n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }
}
