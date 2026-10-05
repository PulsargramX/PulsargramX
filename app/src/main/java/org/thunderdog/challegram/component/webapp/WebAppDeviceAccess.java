/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 */
package org.thunderdog.challegram.component.webapp;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.biometrics.BiometricPrompt;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;
import org.thunderdog.challegram.R;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.UUID;
import java.util.function.Consumer;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Per-account, per-bot permission consent and encrypted biometric tokens. */
final class WebAppDeviceAccess {
  private static final int LOCATION_PERMISSION = 7821;
  private final WebAppActions actions;
  private final SharedPreferences preferences;
  private final String prefix;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private CancellationSignal authentication;
  private LocationListener locationListener;
  private boolean awaitingLocation;
  private int locationGeneration;
  private boolean biometricAccessPending;
  private final Runnable locationTimeout = () -> finishLocation(null);

  WebAppDeviceAccess (WebAppActions actions) {
    this.actions = actions;
    preferences = actions.host.context().getSharedPreferences("miniapp_native_permissions", Context.MODE_PRIVATE);
    prefix = actions.host.tdlib().id() + ":" + (actions.host.tdlib().isProduction() ? "prod" : "test") + ":" + actions.host.tdlib().myUserId() + ":" + actions.botId() + ":";
  }
  static void clearBotData (Context context, org.thunderdog.challegram.telegram.Tdlib tdlib, long botId) {
    SharedPreferences preferences = context.getSharedPreferences("miniapp_native_permissions", Context.MODE_PRIVATE);
    String accountPrefix = tdlib.id() + ":" + (tdlib.isProduction() ? "prod" : "test") + ":";
    String botPrefix = accountPrefix + tdlib.myUserId() + ":" + botId + ":";
    SharedPreferences.Editor editor = preferences.edit();
    for (String key : preferences.getAll().keySet()) {
      if (key.startsWith(botId == 0 ? accountPrefix : botPrefix)) editor.remove(key);
    }
    editor.apply();
    try {
      KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
      String keyPrefix = "miniapp.biometric." + (botId == 0 ? accountPrefix : botPrefix);
      java.util.Enumeration<String> keys = store.aliases();
      while (keys.hasMoreElements()) {
        String key = keys.nextElement(); if (key.startsWith(keyPrefix)) store.deleteEntry(key);
      }
    } catch (Exception ignored) { }
  }

  boolean handle (String event, JSONObject data) {
    switch (event) {
      case "web_app_check_location": locationInfo(); return true;
      case "web_app_request_location": requestLocation(); return true;
      case "web_app_open_location_settings": if (actions.host.hasRecentUserGesture()) settings(false); return true;
      case "web_app_biometry_get_info": biometryInfo(); return true;
      case "web_app_biometry_request_access": requestBiometry(data.optString("reason")); return true;
      case "web_app_biometry_request_auth": authenticateToken(data.optString("reason")); return true;
      case "web_app_biometry_update_token": updateToken(data.optString("token"), data.optString("reason")); return true;
      case "web_app_biometry_open_settings": if (actions.host.hasRecentUserGesture()) settings(true); return true;
      default: return false;
    }
  }
  private boolean granted (String type) { return preferences.getBoolean(prefix + type + "_granted", false); }
  private boolean requested (String type) { return preferences.getBoolean(prefix + type + "_requested", false); }
  private void permission (String type, boolean allowed) {
    if (actions.alive()) preferences.edit().putBoolean(prefix + type + "_requested", true).putBoolean(prefix + type + "_granted", allowed).apply();
  }
  private void settings (boolean biometric) {
    String type = biometric ? "biometry" : "location";
    boolean checked = granted(type);
    actions.show(new AlertDialog.Builder(actions.host.activity()).setTitle(biometric ? R.string.WebAppBiometryTitle : R.string.WebAppLocationTitle)
      .setMessage(actions.host.context().getString(R.string.WebAppPermissionSettingsText, actions.botName()))
      .setPositiveButton(checked ? R.string.WebAppRevoke : R.string.WebAppAllow, (d, w) -> {
        if (!actions.alive()) return;
        if (biometric && !checked) {
          permission(type, false);
          authenticate(null, "", success -> { permission(type, success != null); biometryInfo(); });
        } else {
          permission(type, !checked);
          if (biometric) {
            if (checked) invalidateToken();
            biometryInfo();
          } else locationInfo();
        }
      }).setNegativeButton(android.R.string.cancel, null), null);
  }

  private LocationManager locations () { return (LocationManager) actions.host.context().getSystemService(Context.LOCATION_SERVICE); }
  private boolean hasLocationPermission () {
    return Build.VERSION.SDK_INT < 23 || actions.host.context().checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
      actions.host.context().checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
  }
  private boolean locationAvailable () {
    LocationManager manager = locations();
    return manager != null && (!manager.getAllProviders().isEmpty());
  }
  private void locationInfo () {
    actions.emit("location_checked", WebAppActions.object("available", locationAvailable(), "access_requested", requested("location"),
      "access_granted", granted("location") && hasLocationPermission()));
  }
  private void requestLocation () {
    if (awaitingLocation || !locationAvailable()) {
      actions.emit("location_requested", WebAppActions.object("available", false)); return;
    }
    awaitingLocation = true;
    if (granted("location")) obtainLocation();
    else if (requested("location")) finishLocation(null);
    else actions.confirm(R.string.WebAppLocationTitle,
      actions.host.context().getString(R.string.WebAppLocationText, actions.botName()), R.string.WebAppAllow,
      () -> { permission("location", true); obtainLocation(); },
      () -> { permission("location", false); finishLocation(null); });
  }
  private void obtainLocation () {
    if (!actions.alive()) return;
    if (!hasLocationPermission() && Build.VERSION.SDK_INT >= 23) {
      int token = ++locationGeneration;
      // The activity's dedicated location callback does not replace a WebView camera/microphone callback.
      ((org.thunderdog.challegram.BaseActivity) actions.host.activity()).requestLocationPermission(false, true,
        (code, permissions, results, grantedCount) -> {
          if (!actions.alive() || !awaitingLocation || token != locationGeneration) return;
          if (hasLocationPermission()) obtainLocation(); else finishLocation(null);
        });
      return;
    }
    LocationManager manager = locations();
    if (manager == null) { finishLocation(null); return; }
    locationListener = new LocationListener() {
      @Override public void onLocationChanged (Location location) { finishLocation(location); }
      @Override public void onStatusChanged (String provider, int status, Bundle extras) { }
      @Override public void onProviderEnabled (String provider) { }
      @Override public void onProviderDisabled (String provider) { }
    };
    try {
      boolean fine = Build.VERSION.SDK_INT < 23 || actions.host.context().checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
      boolean listening = false;
      for (String provider : manager.getProviders(true)) {
        if (!LocationManager.GPS_PROVIDER.equals(provider) && !LocationManager.NETWORK_PROVIDER.equals(provider)) continue;
        if (!fine && LocationManager.GPS_PROVIDER.equals(provider)) continue;
        manager.requestSingleUpdate(provider, locationListener, Looper.getMainLooper()); listening = true;
      }
      if (!listening) { finishLocation(null); return; }
      handler.postDelayed(locationTimeout, 30000);
    } catch (SecurityException | IllegalArgumentException ignored) { finishLocation(null); }
  }
  private void finishLocation (Location location) {
    if (!awaitingLocation) return;
    awaitingLocation = false;
    removeLocationListener();
    JSONObject result = WebAppActions.object("available", location != null);
    if (location != null) {
      result = WebAppActions.object("available", true, "latitude", location.getLatitude(), "longitude", location.getLongitude(),
        "altitude", location.hasAltitude() ? location.getAltitude() : JSONObject.NULL,
        "course", location.hasBearing() ? location.getBearing() : JSONObject.NULL,
        "speed", location.hasSpeed() ? location.getSpeed() : JSONObject.NULL,
        "horizontal_accuracy", location.hasAccuracy() ? location.getAccuracy() : JSONObject.NULL,
        "vertical_accuracy", Build.VERSION.SDK_INT >= 26 && location.hasVerticalAccuracy() ? location.getVerticalAccuracyMeters() : JSONObject.NULL,
        "course_accuracy", Build.VERSION.SDK_INT >= 26 && location.hasBearingAccuracy() ? location.getBearingAccuracyDegrees() : JSONObject.NULL,
        "speed_accuracy", Build.VERSION.SDK_INT >= 26 && location.hasSpeedAccuracy() ? location.getSpeedAccuracyMetersPerSecond() : JSONObject.NULL);
    }
    actions.emit("location_requested", result);
  }
  private void removeLocationListener () {
    handler.removeCallbacks(locationTimeout);
    LocationManager manager = locations();
    if (manager != null && locationListener != null) {
      try { manager.removeUpdates(locationListener); } catch (SecurityException ignored) { }
    }
    locationListener = null;
  }

  private boolean biometricAvailable () {
    if (Build.VERSION.SDK_INT < 23) return false;
    if (Build.VERSION.SDK_INT >= 29) {
      android.hardware.biometrics.BiometricManager manager = actions.host.context().getSystemService(android.hardware.biometrics.BiometricManager.class);
      return manager != null && manager.canAuthenticate() == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS;
    }
    androidx.core.hardware.fingerprint.FingerprintManagerCompat manager = androidx.core.hardware.fingerprint.FingerprintManagerCompat.from(actions.host.context());
    return manager != null && manager.isHardwareDetected() && manager.hasEnrolledFingerprints();
  }
  private void biometryInfo () {
    String deviceId = preferences.getString(prefix + "device_id", null);
    if (deviceId == null) { deviceId = UUID.randomUUID().toString(); preferences.edit().putString(prefix + "device_id", deviceId).apply(); }
    String type = actions.host.context().getPackageManager().hasSystemFeature("android.hardware.biometrics.face") ? "face" : "finger";
    actions.emit("biometry_info_received", WebAppActions.object("available", biometricAvailable(), "type", type,
      "access_requested", requested("biometry"), "access_granted", granted("biometry"),
      "token_saved", preferences.contains(prefix + "token"), "device_id", deviceId));
  }
  private void requestBiometry (String reason) {
    if (biometricAccessPending) return;
    if (requested("biometry") || !biometricAvailable()) { biometryInfo(); return; }
    biometricAccessPending = true;
    actions.confirm(R.string.WebAppBiometryTitle, actions.host.context().getString(R.string.WebAppBiometryText, actions.botName()) +
      (reason.isEmpty() ? "" : "\n\n" + reason.substring(0, Math.min(128, reason.length()))), R.string.WebAppAllow,
      () -> authenticate(null, reason, success -> { biometricAccessPending = false; permission("biometry", success != null); biometryInfo(); }),
      () -> { biometricAccessPending = false; permission("biometry", false); biometryInfo(); });
  }
  private Cipher cipher (boolean encrypt) throws Exception {
    String alias = "miniapp.biometric." + prefix;
    KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
    if (!store.containsAlias(alias)) {
      if (!encrypt) throw new java.security.KeyStoreException();
      KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
      generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setUserAuthenticationRequired(true).setUserAuthenticationValidityDurationSeconds(-1).build());
      generator.generateKey();
    }
    SecretKey key = (SecretKey) store.getKey(alias, null);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    if (encrypt) cipher.init(Cipher.ENCRYPT_MODE, key);
    else cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.decode(preferences.getString(prefix + "iv", ""), Base64.NO_WRAP)));
    return cipher;
  }
  private void authenticateToken (String reason) {
    if (!granted("biometry") || !biometricAvailable()) { authResult(null); return; }
    try {
      boolean hasToken = preferences.contains(prefix + "token");
      Cipher crypto = hasToken ? cipher(false) : null;
      authenticate(crypto, reason, authenticated -> {
        if (authenticated == null) { authResult(null); return; }
        try {
          String token = hasToken ? new String(authenticated.cipher.doFinal(
            Base64.decode(preferences.getString(prefix + "token", ""), Base64.NO_WRAP)), StandardCharsets.UTF_8) : "";
          authResult(token);
        } catch (Exception ignored) { invalidateToken(); authResult(null); }
      });
    } catch (Exception ignored) { invalidateToken(); authResult(null); }
  }
  private void authResult (String token) {
    actions.emit("biometry_auth_requested", token == null ? WebAppActions.object("status", "failed") : WebAppActions.object("status", "authorized", "token", token));
  }
  private void invalidateToken () {
    preferences.edit().remove(prefix + "token").remove(prefix + "iv").apply();
    try {
      KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
      store.deleteEntry("miniapp.biometric." + prefix);
    } catch (Exception ignored) { }
  }
  private void updateToken (String token, String reason) {
    if (!granted("biometry") || !biometricAvailable() || token.length() > 1024) { tokenResult("failed"); return; }
    try {
      Cipher crypto = token.isEmpty() ? null : cipher(true);
      authenticate(crypto, reason, authenticated -> {
        if (authenticated == null) { tokenResult("failed"); return; }
        if (token.isEmpty()) { invalidateToken(); tokenResult("removed"); return; }
        try {
          Cipher cipher = authenticated.cipher;
          byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
          preferences.edit().putString(prefix + "token", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(prefix + "iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)).apply();
          tokenResult("updated");
        } catch (Exception ignored) { tokenResult("failed"); }
      });
    } catch (Exception ignored) { invalidateToken(); tokenResult("failed"); }
  }
  private void tokenResult (String status) { actions.emit("biometry_token_updated", WebAppActions.object("status", status)); }
  private static final class Authentication {
    final Cipher cipher;
    Authentication (Cipher cipher) { this.cipher = cipher; }
  }
  private void authenticate (Cipher cipher, String reason, Consumer<Authentication> callback) {
    if (!actions.alive() || authentication != null || !biometricAvailable() || Build.VERSION.SDK_INT < 23) { callback.accept(null); return; }
    authentication = new CancellationSignal();
    final boolean[] completed = {false};
    final AlertDialog[] fingerprintDialog = {null};
    Consumer<Authentication> finish = result -> {
      if (completed[0]) return; completed[0] = true; authentication = null;
      if (fingerprintDialog[0] != null) fingerprintDialog[0].dismiss();
      if (actions.alive()) callback.accept(result);
    };
    if (Build.VERSION.SDK_INT < 28) {
      CancellationSignal signal = authentication;
      fingerprintDialog[0] = actions.show(new AlertDialog.Builder(actions.host.activity())
        .setTitle(R.string.WebAppBiometryTitle).setMessage(R.string.WebAppTouchFingerprint)
        .setNegativeButton(android.R.string.cancel, (d, w) -> { signal.cancel(); finish.accept(null); }),
        () -> { signal.cancel(); finish.accept(null); });
      androidx.core.hardware.fingerprint.FingerprintManagerCompat manager = androidx.core.hardware.fingerprint.FingerprintManagerCompat.from(actions.host.context());
      try {
        manager.authenticate(cipher == null ? null : new androidx.core.hardware.fingerprint.FingerprintManagerCompat.CryptoObject(cipher),
          0, signal, new androidx.core.hardware.fingerprint.FingerprintManagerCompat.AuthenticationCallback() {
            @Override public void onAuthenticationSucceeded (androidx.core.hardware.fingerprint.FingerprintManagerCompat.AuthenticationResult result) {
              finish.accept(new Authentication(result.getCryptoObject() == null ? null : result.getCryptoObject().getCipher()));
            }
            @Override public void onAuthenticationError (int error, CharSequence message) { finish.accept(null); }
            @Override public void onAuthenticationHelp (int help, CharSequence message) {
              if (fingerprintDialog[0] != null) fingerprintDialog[0].setMessage(message);
            }
          }, handler);
      } catch (Exception ignored) { finish.accept(null); }
      return;
    }
    BiometricPrompt.Builder builder = new BiometricPrompt.Builder(actions.host.activity())
      .setTitle(actions.host.context().getString(R.string.WebAppBiometryTitle)).setSubtitle(actions.botName())
      .setNegativeButton(actions.host.context().getString(android.R.string.cancel), actions.host.activity().getMainExecutor(), (d, w) -> finish.accept(null));
    if (!reason.isEmpty()) builder.setDescription(reason.substring(0, Math.min(128, reason.length())));
    BiometricPrompt.AuthenticationCallback listener = new BiometricPrompt.AuthenticationCallback() {
      @Override public void onAuthenticationSucceeded (BiometricPrompt.AuthenticationResult result) { finish.accept(new Authentication(result.getCryptoObject() == null ? null : result.getCryptoObject().getCipher())); }
      @Override public void onAuthenticationError (int error, CharSequence message) { finish.accept(null); }
    };
    try {
      if (cipher == null) builder.build().authenticate(authentication, actions.host.activity().getMainExecutor(), listener);
      else builder.build().authenticate(new BiometricPrompt.CryptoObject(cipher), authentication, actions.host.activity().getMainExecutor(), listener);
    } catch (Exception ignored) { finish.accept(null); }
  }
  void onRequestPermissionsResult (int requestCode, int[] results) {
    if (requestCode != LOCATION_PERMISSION || !awaitingLocation || !actions.alive()) return;
    if (hasLocationPermission()) obtainLocation(); else finishLocation(null);
  }
  void onActivityResult (int requestCode, int resultCode, Intent data) { }
  void destroy () {
    if (authentication != null) { authentication.cancel(); authentication = null; }
    awaitingLocation = false; locationGeneration++; biometricAccessPending = false;
    removeLocationListener(); handler.removeCallbacksAndMessages(null);
  }
}
