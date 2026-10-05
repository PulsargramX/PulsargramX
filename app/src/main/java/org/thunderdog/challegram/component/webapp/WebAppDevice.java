/*
 * This file is a part of Pulsargram X, based on Telegram X.
 * Copyright © 2026 Pulsargram X contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License as published by the Free Software Foundation, version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See <https://www.gnu.org/licenses/> for the GNU General Public License.
 */
package org.thunderdog.challegram.component.webapp;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.SystemClock;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;
import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibAccount;
import org.thunderdog.challegram.telegram.TdlibManager;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Sensors and per-account, per-bot local storage. Secure values never leave Android Keystore. */
public final class WebAppDevice implements SensorEventListener {
  private static final ExecutorService STORAGE = Executors.newSingleThreadExecutor();
  private final WebAppActions.Host host;
  private final SensorManager sensors;
  private final Map<Integer, SensorState> active = new HashMap<>();
  private final String namespace;
  private boolean paused;
  private volatile boolean destroyed;
  private AlertDialog restoreDialog;
  private boolean restoring;

  private static final class SensorState {
    final String name;
    final int interval;
    long last;
    final float[] accumulated = new float[3];
    SensorState (String name, int interval) { this.name = name; this.interval = interval; }
  }

  public WebAppDevice (WebAppActions.Host host) {
    this.host = host;
    sensors = (SensorManager) host.context().getSystemService(Context.SENSOR_SERVICE);
    namespace = namespace(host.tdlib(), host.session().request.botUserId);
  }

  public static String namespace (org.thunderdog.challegram.telegram.Tdlib tdlib, long botId) {
    return "webapp_" + tdlib.id() + "_" + (tdlib.isProduction() ? "prod" : "test") + "_" +
      tdlib.myUserId() + "_" + botId;
  }

  /** Close this bot's live sessions before calling, so outstanding writes cannot repopulate data. */
  public static void forget (Context context, org.thunderdog.challegram.telegram.Tdlib tdlib,
                            long botId, Runnable onDone) {
    String name = namespace(tdlib, botId);
    WebAppBridge.forgetProfile(name);
    WebAppBridge.forgetProfiles(name + "_");
    STORAGE.execute(() -> {
      context.getSharedPreferences(name + "_device", Context.MODE_PRIVATE).edit().clear().commit();
      context.getSharedPreferences(name + "_secure", Context.MODE_PRIVATE).edit().clear().commit();
      if (Build.VERSION.SDK_INT >= 23) {
        try {
          KeyStore store = KeyStore.getInstance("AndroidKeyStore");
          store.load(null);
          if (store.containsAlias(name)) store.deleteEntry(name);
        } catch (Exception ignored) { }
      }
      if (onDone != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(onDone);
    });
  }

  public static void forgetAccount (Context context, org.thunderdog.challegram.telegram.Tdlib tdlib,
                                   Runnable onDone) {
    String prefix = "webapp_" + tdlib.id() + "_";
    WebAppBridge.forgetProfiles(prefix);
    STORAGE.execute(() -> {
      java.io.File directory = new java.io.File(context.getApplicationInfo().dataDir, "shared_prefs");
      String[] files = directory.list();
      if (files != null) for (String file : files) {
        if (file.startsWith(prefix) && file.endsWith(".xml")) {
          context.getSharedPreferences(file.substring(0, file.length() - 4), Context.MODE_PRIVATE)
            .edit().clear().commit();
        }
      }
      if (Build.VERSION.SDK_INT >= 23) {
        try {
          KeyStore store = KeyStore.getInstance("AndroidKeyStore");
          store.load(null);
          java.util.Enumeration<String> aliases = store.aliases();
          java.util.List<String> remove = new java.util.ArrayList<>();
          while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            if (alias.startsWith(prefix)) remove.add(alias);
          }
          for (String alias : remove) store.deleteEntry(alias);
        } catch (Exception ignored) { }
      }
      if (onDone != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(onDone);
    });
  }

  public static JSONObject object (Object... values) {
    JSONObject result = new JSONObject();
    try {
      for (int i = 0; i + 1 < values.length; i += 2) {
        result.put((String) values[i], values[i + 1] == null ? JSONObject.NULL : values[i + 1]);
      }
    } catch (JSONException ignored) { }
    return result;
  }

  public boolean handle (String event, JSONObject data) {
    if (event.startsWith("web_app_device_storage_") || event.startsWith("web_app_secure_storage_")) {
      boolean secure = event.startsWith("web_app_secure_");
      String prefix = secure ? "secure_storage_" : "device_storage_";
      String operation = event.substring(("web_app_" + prefix).length());
      if (!secure && operation.equals("restore_key")) return false;
      if (!data.has("req_id") || data.optString("req_id").length() > 256) return true;
      List<StorageOwner> owners = secure && (operation.equals("get_key") || operation.equals("restore_key")) ?
        storageOwners() : new ArrayList<>();
      if (operation.equals("restore_key")) {
        if (restoring) {
          host.emit("secure_storage_failed", object("req_id", data.optString("req_id"), "error", "RESTORE_CANCELLED"));
          return true;
        }
        restoring = true;
      }
      STORAGE.execute(() -> storage(prefix, operation, data, secure, owners));
      return true;
    }
    boolean start = event.startsWith("web_app_start_");
    boolean stop = event.startsWith("web_app_stop_");
    if (!start && !stop) return false;
    String name = event.substring(start ? 14 : 13);
    int type;
    switch (name) {
      case "accelerometer": type = Sensor.TYPE_ACCELEROMETER; break;
      case "gyroscope": type = Sensor.TYPE_GYROSCOPE; break;
      case "device_orientation":
        type = data.optBoolean("need_absolute", false) || Build.VERSION.SDK_INT < 18 ?
          Sensor.TYPE_ROTATION_VECTOR : Sensor.TYPE_GAME_ROTATION_VECTOR;
        break;
      default: return false;
    }
    if (stop) {
      for (Integer key : new java.util.ArrayList<>(active.keySet())) {
        if (active.get(key).name.equals(name)) {
          if (sensors != null) sensors.unregisterListener(this, sensors.getDefaultSensor(key));
          active.remove(key);
        }
      }
      host.emit(name + "_stopped", null);
    } else {
      Sensor sensor = sensors == null ? null : sensors.getDefaultSensor(type);
      int interval = Math.max(20, Math.min(1000, data.optInt("refresh_rate", 1000)));
      // A restarted orientation stream must not leave its alternate sensor registered.
      for (Integer key : new java.util.ArrayList<>(active.keySet())) {
        if (active.get(key).name.equals(name)) {
          sensors.unregisterListener(this, sensors.getDefaultSensor(key));
          active.remove(key);
        }
      }
      if (sensor == null || !sensors.registerListener(this, sensor, interval * 1000)) {
        host.emit(name + "_failed", object("error", "UNSUPPORTED"));
      } else {
        active.put(type, new SensorState(name, interval));
        if (paused) sensors.unregisterListener(this, sensor);
        host.emit(name + "_started", null);
      }
    }
    return true;
  }

  private void storage (String prefix, String operation, JSONObject data, boolean secure, List<StorageOwner> owners) {
    if (destroyed || !host.isAlive()) return;
    String request = data.optString("req_id");
    String key = data.optString("key", "");
    if (!operation.equals("clear") && (key.isEmpty() || key.length() > 256)) {
      JSONObject failure = object("req_id", request, "error", "KEY_INVALID");
      if (operation.equals("restore_key")) restoreResult(prefix + "failed", failure);
      else result(prefix + "failed", failure);
      return;
    }
    if (secure && Build.VERSION.SDK_INT < 23) {
      JSONObject failure = object("req_id", request, "error", "UNSUPPORTED");
      if (operation.equals("restore_key")) restoreResult(prefix + "failed", failure);
      else result(prefix + "failed", failure);
      return;
    }
    try {
      SharedPreferences preferences = host.context().getSharedPreferences(namespace +
        (secure ? "_secure" : "_device"), Context.MODE_PRIVATE);
      switch (operation) {
        case "save_key": {
          if (!data.has("value")) throw new IllegalArgumentException("VALUE_INVALID");
          boolean remove = data.isNull("value");
          if (!remove && !(data.opt("value") instanceof String)) {
            throw new IllegalArgumentException("VALUE_INVALID");
          }
          String value = remove ? null : data.getString("value");
          if (value != null && value.getBytes(StandardCharsets.UTF_8).length > (secure ? 4096 : 5242880)) {
            throw new IllegalArgumentException("VALUE_TOO_LONG");
          }
          Map<String, ?> entries = preferences.getAll();
          long size = 0;
          for (Map.Entry<String, ?> entry : entries.entrySet()) {
            if (!entry.getKey().equals(key)) size += entry.getKey().getBytes(StandardCharsets.UTF_8).length + entry.getValue().toString().getBytes(StandardCharsets.UTF_8).length;
          }
          if (value != null && (entries.size() >= (secure ? 10 : 1024) && !entries.containsKey(key) ||
              size + value.getBytes(StandardCharsets.UTF_8).length > (secure ? 65536 : 5242880))) {
            throw new IllegalArgumentException("QUOTA_EXCEEDED");
          }
          String stored = value == null ? null : secure ? encrypt(value) : value;
          if (destroyed || !host.isAlive()) return;
          if (!preferences.edit().putString(key, stored).commit()) throw new Exception();
          result(prefix + "key_saved", object("req_id", request));
          break;
        }
        case "restore_key": {
          String stored = preferences.getString(key, null);
          if (stored != null) {
            String value = decrypt(stored);
            restoreResult("secure_storage_key_restored", object("req_id", request, "value", value));
          } else {
            List<StorageOwner> candidates = restoreCandidates(owners, key);
            if (candidates.isEmpty()) restoreResult("secure_storage_failed", object("req_id", request, "error", "RESTORE_UNAVAILABLE"));
            else host.runOnUiThread(() -> chooseRestore(request, key, candidates));
          }
          break;
        }
        case "get_key": {
          String value = preferences.getString(key, null);
          if (secure && value != null) value = decrypt(value);
          boolean canRestore = secure && value == null && !restoreCandidates(owners, key).isEmpty();
          result(prefix + "key_received",
            secure ? object("req_id", request, "value", value, "can_restore", canRestore) :
              object("req_id", request, "value", value));
          break;
        }
        case "clear":
          if (destroyed || !host.isAlive()) return;
          if (!preferences.edit().clear().commit()) throw new Exception();
          result(prefix + "cleared", object("req_id", request));
          break;
        default: result(prefix + "failed", object("req_id", request, "error", "UNSUPPORTED"));
      }
    } catch (Exception error) {
      String reason = storageError(error, "UNKNOWN_ERROR");
      if (operation.equals("restore_key")) restoreResult(prefix + "failed", object("req_id", request, "error", reason));
      else result(prefix + "failed", object("req_id", request, "error", reason));
    }
  }

  private static final class StorageOwner {
    final TdlibAccount account;
    final long userId;
    final boolean production;
    final String namespace;
    final String label;
    StorageOwner (TdlibAccount account, boolean production, long botId, String label) {
      this.account = account;
      this.userId = account.getKnownUserId();
      this.production = production;
      this.namespace = "webapp_" + account.id + "_" + (production ? "prod" : "test") + "_" + userId + "_" + botId;
      this.label = label;
    }
    boolean valid () {
      TdlibManager manager = TdlibManager.instance();
      if (!manager.hasAccount(account.id) || manager.account(account.id) != account || account.isUnauthorized() ||
          account.getKnownUserId() != userId || account.tdlibInstanceMode() != (production ? Tdlib.Mode.NORMAL : Tdlib.Mode.DEBUG)) return false;
      if (account.hasTdlib(false) && account.tdlibNoWakeup().authorizationState() instanceof TdApi.AuthorizationStateLoggingOut) return false;
      return true;
    }
  }

  private List<StorageOwner> storageOwners () {
    List<StorageOwner> owners = new ArrayList<>();
    boolean production = host.tdlib().isProduction();
    for (TdlibAccount account : TdlibManager.instance().accountsQueue()) {
      if (account.id == host.tdlib().id() || account.isUnauthorized() || account.getKnownUserId() == 0 ||
          account.tdlibInstanceMode() != (production ? Tdlib.Mode.NORMAL : Tdlib.Mode.DEBUG)) continue;
      String label = account.hasUserInfo() ? account.getName() :
        host.context().getString(R.string.WebAppRuntimeRestoreAccount, account.id + 1);
      StorageOwner owner = new StorageOwner(account, production, host.session().request.botUserId, label);
      if (owner.valid()) owners.add(owner);
    }
    return owners;
  }

  private List<StorageOwner> restoreCandidates (List<StorageOwner> owners, String key) {
    List<StorageOwner> candidates = new ArrayList<>();
    for (StorageOwner owner : owners) {
      if (destroyed || !host.isAlive()) break;
      if (!owner.valid()) continue;
      try {
        String value = host.context().getSharedPreferences(owner.namespace + "_secure", Context.MODE_PRIVATE).getString(key, null);
        // Only test decryptability here. No source value crosses to UI or JavaScript before consent.
        if (value != null && decrypt(value, owner.namespace).getBytes(StandardCharsets.UTF_8).length <= 4096) candidates.add(owner);
      } catch (Exception ignored) { }
    }
    return candidates;
  }

  private void chooseRestore (String request, String key, List<StorageOwner> candidates) {
    if (destroyed || !host.isAlive()) return;
    if (!host.controller().isFocused() || ((BaseActivity) host.activity()).isPasscodeShowing() || restoreDialog != null) {
      restoreResult("secure_storage_failed", object("req_id", request, "error", "RESTORE_CANCELLED"));
      return;
    }
    List<StorageOwner> available = new ArrayList<>();
    for (StorageOwner owner : candidates) if (owner.valid()) available.add(owner);
    if (available.isEmpty()) {
      restoreResult("secure_storage_failed", object("req_id", request, "error", "RESTORE_UNAVAILABLE"));
      return;
    }
    String[] labels = new String[available.size()];
    for (int i = 0; i < labels.length; i++) labels[i] = available.get(i).label;
    final int[] selected = {-1};
    restoreDialog = new AlertDialog.Builder(host.context()).setTitle(R.string.WebAppRuntimeRestoreTitle)
      .setSingleChoiceItems(labels, -1, (dialog, index) -> {
        selected[0] = index;
        ((AlertDialog) dialog).getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
      })
      .setPositiveButton(R.string.WebAppRuntimeRestoreConfirm, (dialog, which) -> {
        if (destroyed || !host.isAlive() || selected[0] < 0) return;
        StorageOwner source = available.get(selected[0]);
        STORAGE.execute(() -> restoreSelected(source, request, key));
      })
      .setNegativeButton(R.string.WebAppRuntimeCancel, (dialog, which) ->
        restoreResult("secure_storage_failed", object("req_id", request, "error", "RESTORE_CANCELLED")))
      .create();
    restoreDialog.setOnCancelListener(dialog -> restoreResult("secure_storage_failed",
      object("req_id", request, "error", "RESTORE_CANCELLED")));
    restoreDialog.setOnDismissListener(dialog -> restoreDialog = null);
    restoreDialog.show();
    restoreDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
  }

  private void restoreSelected (StorageOwner owner, String request, String key) {
    if (destroyed || !host.isAlive()) return;
    try {
      if (!owner.valid()) throw new IllegalArgumentException("RESTORE_UNAVAILABLE");
      String stored = host.context().getSharedPreferences(owner.namespace + "_secure", Context.MODE_PRIVATE).getString(key, null);
      if (stored == null) throw new IllegalArgumentException("RESTORE_UNAVAILABLE");
      String value = decrypt(stored, owner.namespace);
      if (value.getBytes(StandardCharsets.UTF_8).length > 4096) throw new IllegalArgumentException("VALUE_TOO_LONG");
      SharedPreferences target = host.context().getSharedPreferences(namespace + "_secure", Context.MODE_PRIVATE);
      Map<String, ?> entries = target.getAll();
      long size = 0;
      for (Map.Entry<String, ?> entry : entries.entrySet()) {
        if (!entry.getKey().equals(key)) size += entry.getKey().getBytes(StandardCharsets.UTF_8).length +
          entry.getValue().toString().getBytes(StandardCharsets.UTF_8).length;
      }
      String encrypted = encrypt(value);
      if ((!entries.containsKey(key) && entries.size() >= 10) || size + encrypted.length() > 65536) {
        throw new IllegalArgumentException("QUOTA_EXCEEDED");
      }
      if (destroyed || !host.isAlive()) return;
      if (!owner.valid()) throw new IllegalArgumentException("RESTORE_UNAVAILABLE");
      if (!target.edit().putString(key, encrypted).commit()) throw new Exception();
      restoreResult("secure_storage_key_restored", object("req_id", request, "value", value));
    } catch (Exception error) {
      restoreResult("secure_storage_failed", object("req_id", request, "error",
        storageError(error, "RESTORE_UNAVAILABLE")));
    }
  }

  private static String storageError (Exception error, String fallback) {
    String reason = error.getMessage();
    return "KEY_INVALID".equals(reason) || "VALUE_INVALID".equals(reason) || "VALUE_TOO_LONG".equals(reason) ||
      "QUOTA_EXCEEDED".equals(reason) || "RESTORE_UNAVAILABLE".equals(reason) ? reason : fallback;
  }

  private void restoreResult (String event, JSONObject data) {
    host.runOnUiThread(() -> {
      restoring = false;
      if (!destroyed && host.isAlive()) host.emit(event, data);
    });
  }

  private SecretKey key () throws Exception {
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    if (!store.containsAlias(namespace)) {
      KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
      generator.init(new KeyGenParameterSpec.Builder(namespace,
        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
      return generator.generateKey();
    }
    return (SecretKey) store.getKey(namespace, null);
  }

  private String encrypt (String value) throws Exception {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key());
    return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" +
      Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
  }

  private String decrypt (String value) throws Exception {
    return decrypt(value, namespace);
  }

  private String decrypt (String value, String sourceNamespace) throws Exception {
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    if (!store.containsAlias(sourceNamespace)) throw new IllegalArgumentException("RESTORE_UNAVAILABLE");
    SecretKey sourceKey = (SecretKey) store.getKey(sourceNamespace, null);
    String[] parts = value.split(":", 2);
    if (parts.length != 2) throw new IllegalArgumentException();
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, sourceKey, new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
    return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
  }

  private void result (String event, JSONObject data) {
    host.runOnUiThread(() -> { if (!destroyed && host.isAlive()) host.emit(event, data); });
  }

  public void setPaused (boolean paused) {
    this.paused = paused;
    if (sensors == null) return;
    sensors.unregisterListener(this);
    if (!paused) {
      for (Map.Entry<Integer, SensorState> entry : active.entrySet()) {
        Sensor sensor = sensors.getDefaultSensor(entry.getKey());
        if (sensor != null) sensors.registerListener(this, sensor, entry.getValue().interval * 1000);
      }
    }
  }

  @Override
  public void onSensorChanged (SensorEvent event) {
    if (paused || destroyed || !host.isAlive()) return;
    SensorState state = active.get(event.sensor.getType());
    long now = SystemClock.elapsedRealtime();
    if (state == null) return;
    if (state.name.equals("gyroscope")) {
      for (int axis = 0; axis < 3; axis++) state.accumulated[axis] += event.values[axis];
    }
    if (now - state.last < state.interval) return;
    state.last = now;
    if (state.name.equals("device_orientation")) {
      float[] matrix = new float[9];
      float[] angles = new float[3];
      // Some Samsung implementations reject the optional fifth rotation-vector element.
      float[] rotation = event.values.length > 4 ? java.util.Arrays.copyOf(event.values, 4) : event.values;
      try {
        SensorManager.getRotationMatrixFromVector(matrix, rotation);
        SensorManager.getOrientation(matrix, angles);
      } catch (IllegalArgumentException ignored) { return; }
      host.emit("device_orientation_changed", object("absolute",
        event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR,
        "alpha", -angles[0], "beta", -angles[1], "gamma", angles[2]));
    } else if (state.name.equals("accelerometer")) {
      host.emit("accelerometer_changed", object("x", -event.values[0],
        "y", -event.values[1], "z", -event.values[2]));
    } else {
      host.emit("gyroscope_changed", object("x", state.accumulated[0],
        "y", state.accumulated[1], "z", state.accumulated[2]));
      java.util.Arrays.fill(state.accumulated, 0f);
    }
  }

  @Override public void onAccuracyChanged (Sensor sensor, int accuracy) { }

  public void destroy () {
    destroyed = true;
    restoring = false;
    if (restoreDialog != null) { restoreDialog.dismiss(); restoreDialog = null; }
    if (sensors != null) sensors.unregisterListener(this);
    active.clear();
  }
}
