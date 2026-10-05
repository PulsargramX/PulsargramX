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
 * File created on 08/03/2018
 */
package org.thunderdog.challegram.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdate;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapLibreMap;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.U;
import org.thunderdog.challegram.component.dialogs.ChatView;
import org.thunderdog.challegram.component.location.OpenStreetMap;
import org.thunderdog.challegram.component.location.OpenStreetMap.Marker;
import org.thunderdog.challegram.component.location.OpenStreetMapView;
import org.thunderdog.challegram.loader.ImageCache;
import org.thunderdog.challegram.loader.ImageFile;
import org.thunderdog.challegram.loader.ImageLoader;
import org.thunderdog.challegram.loader.Watcher;
import org.thunderdog.challegram.loader.WatcherReference;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibAccentColor;
import org.thunderdog.challegram.telegram.TdlibUi;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.unsorted.Settings;
import org.thunderdog.challegram.util.text.Letters;

import java.util.List;

import me.vkryl.core.lambda.Destroyable;
import tgx.td.MessageId;

final class MapOpenStreetMapController extends MapController<OpenStreetMapView, MapOpenStreetMapController.MarkerData> implements OpenStreetMapView.ReadyCallback, OpenStreetMap.LocationListener, MapLibreMap.OnCameraMoveStartedListener, OpenStreetMap.MarkerClickListener {
  private static final float DEFAULT_ZOOM_LEVEL = 16.0f;
  private static final float CLICK_ZOOM_LEVEL = 17.0f;

  public MapOpenStreetMapController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  protected OpenStreetMapView createMapView (Context context, int marginBottom) {
    OpenStreetMapView mapView = new OpenStreetMapView(context);
    mapView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    mapView.setBottomInset(marginBottom);
    return mapView;
  }

  private static final float FINISHED_BROADCAST_ALPHA = .6f;

  public static class MarkerData implements Watcher, Destroyable {
    public final Tdlib tdlib;
    public Marker marker;

    private final WatcherReference reference = new WatcherReference(this);

    public Canvas canvas;
    public Bitmap bitmap;

    public MarkerData (Tdlib tdlib, OpenStreetMap map, LocationPoint<MarkerData> point) {
      this.tdlib = tdlib;
      marker = newPoint(map, point);
      marker.setTag(point);
    }

    private Marker newPoint (OpenStreetMap map, LocationPoint<MarkerData> point) {
      LatLng latLng = new LatLng(point.latitude, point.longitude);
      Bitmap bitmap = null;
      if (point.isSelfLocation) {
        TdApi.User user = tdlib.myUser();
        TdlibAccentColor accentColor = tdlib.cache().userAccentColor(user);
        Letters letters = tdlib.cache().userLetters(user);
        TdApi.File avatar = user != null && user.profilePhoto != null ? user.profilePhoto.small : null;
        bitmap = newBitmap(this, accentColor, letters, avatar);
      } else if (point.isLiveLocation && point.message != null) {
        this.isActive = ((TdApi.MessageLiveLocation) point.message.content).expiresIn > 0;
        bitmap = newBitmap(this, point.message);
      }
      Marker marker = map.addMarker(latLng, bitmap);
      if (point.isLiveLocation && point.message != null) {
        marker.setZIndex(1f);
        marker.setAlpha(isActive ? 1f : FINISHED_BROADCAST_ALPHA);
      }
      if (bitmap != null) marker.setAnchor(0.5f, 0.907f);
      return marker;
    }

    private @Nullable Bitmap newBitmap (MarkerData data, TdApi.Message message) {
      TdlibAccentColor accentColor;
      Letters letters;
      TdApi.File avatar;
      switch (message.senderId.getConstructor()) {
        case TdApi.MessageSenderChat.CONSTRUCTOR: {
          TdApi.Chat chat = tdlib.chat(((TdApi.MessageSenderChat) message.senderId).chatId);
          accentColor = tdlib.chatAccentColor(chat);
          letters = tdlib.chatLetters(chat);
          avatar = chat != null && chat.photo != null ? chat.photo.small : null;
          break;
        }
        case TdApi.MessageSenderUser.CONSTRUCTOR: {
          TdApi.User user = tdlib.cache().user(((TdApi.MessageSenderUser) message.senderId).userId);
          accentColor = tdlib.cache().userAccentColor(user);
          letters = tdlib.cache().userLetters(user);
          avatar = user != null && user.profilePhoto != null ? user.profilePhoto.small : null;
          break;
        }
        default:
          throw new IllegalArgumentException(message.senderId.toString());
      }
      return newBitmap(data, accentColor, letters, avatar);
    }

    private Drawable liveBackground;

    private static void drawAvatar (Canvas c, Bitmap bitmap) {
      BitmapShader shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
      Matrix matrix = new Matrix();
      float scale = Screen.dp(52) / (float) bitmap.getWidth();
      matrix.postTranslate(Screen.dp(5), Screen.dp(5));
      matrix.postScale(scale, scale);
      Paint roundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
      roundPaint.setShader(shader);
      shader.setLocalMatrix(matrix);
      RectF rect = Paints.getRectF();
      rect.set(Screen.dp(5), Screen.dp(5), Screen.dp(52 + 5), Screen.dp(52 + 5));
      c.drawRoundRect(rect, Screen.dp(26), Screen.dp(26), roundPaint);
    }

    private void drawAvatar (final MarkerData data, Canvas c, TdlibAccentColor accentColor, Letters letters, TdApi.File avatar) {
      int cx = Screen.dp(62) / 2;
      int cy = Screen.dp(62f) / 2;
      int radius = Screen.dp(26f);

      final ImageFile imageFile;
      if (avatar != null) {
        imageFile = new ImageFile(tdlib, avatar);
        imageFile.setSwOnly(true);
        imageFile.setSize(ChatView.getDefaultAvatarCacheSize());
        synchronized (ImageCache.getReferenceCounters()) {
          Bitmap avatarBitmap = ImageCache.instance().getBitmap(imageFile);
          if (U.isValidBitmap(avatarBitmap)) {
            drawAvatar(c, avatarBitmap);
            return;
          }
        }
      } else {
        imageFile = null;
      }

      c.drawCircle(cx, cy, radius, Paints.fillingPaint(accentColor.getPrimaryColor()));
      Paint paint = Paints.getMediumTextPaint(19f, accentColor.getPrimaryContentColor(), letters.needFakeBold);
      float textWidth = Paints.measureLetters(letters, 19f);
      c.drawText(letters.text, cx - textWidth / 2, cy + Screen.dp(6.5f), paint);

      data.requestFile(imageFile);
    }

    private @Nullable Bitmap newBitmap (MarkerData data, TdlibAccentColor accentColor, Letters letters, TdApi.File avatar) {
      Bitmap result = null;
      boolean success = false;
      try {
        if (liveBackground == null) {
          liveBackground = UI.getResources().getDrawable(R.drawable.bg_livepin);
          liveBackground.setBounds(0, 0, Screen.dp(62f), Screen.dp(76f));
        }
        result = Bitmap.createBitmap(Screen.dp(62), Screen.dp(76), Bitmap.Config.ARGB_8888);
        result.eraseColor(0);
        Canvas c = new Canvas(result);
        liveBackground.draw(c);

        data.canvas = c;
        data.bitmap = result;

        drawAvatar(data, c, accentColor, letters, avatar);
        success = true;
      } catch (Throwable t) {
        Log.w(t);
      }
      if (!success && result != null) {
        try {
          result.recycle();
        } catch (Throwable ignored) { }
        result = null;
      }
      return result;
    }

    public void setPosition (LocationPoint<MarkerData> point) {
      marker.setPosition(new LatLng(point.latitude, point.longitude));
      setActive(point.message == null || ((TdApi.MessageLiveLocation) point.message.content).expiresIn > 0);
    }

    private boolean isActive = true;

    public void setActive (boolean isActive) {
      if (this.isActive != isActive) {
        this.isActive = isActive;
        marker.setAlpha(isActive ? 1f : FINISHED_BROADCAST_ALPHA);
      }
    }

    public void remove () {
      marker.remove();
    }

    @Override
    public void performDestroy () {
      requestFile(null);
    }

    private ImageFile requestedFile;

    public void requestFile (ImageFile imageFile) {
      if (this.requestedFile == null && imageFile == null) {
        return;
      }
      if (this.requestedFile != null && imageFile != null && this.requestedFile.accountId() == imageFile.accountId() && this.requestedFile.getId() == imageFile.getId()) {
        return;
      }
      if (this.requestedFile != null) {
        ImageLoader.instance().removeWatcher(reference);
      }
      this.requestedFile = imageFile;
      if (imageFile != null) {
        ImageLoader.instance().requestFile(imageFile, reference);
      }
    }

    private boolean isRequested (ImageFile file) {
      return this.requestedFile != null && this.requestedFile.getId() == file.getId() && this.requestedFile.accountId() == file.accountId();
    }

    @Override
    public void imageLoaded (final ImageFile file, boolean successful, Bitmap bitmap) {
      if (successful && isRequested(file) && canvas != null && U.isValidBitmap(bitmap)) {
        drawAvatar(canvas, bitmap);
        UI.post(() -> {
          if (isRequested(file)) {
            marker.setIcon(this.bitmap);
          }
        });
      }
    }

    @Override
    public void imageProgress (ImageFile file, float progress) { }
  }

  @Override
  protected boolean needBackgroundMapInitialization (@NonNull OpenStreetMapView mapView) {
    return false;
  }

  private boolean mapCreated;
  private boolean mapResumed;

  @Override
  protected void initializeMap (@NonNull OpenStreetMapView mapView, boolean inBackground) {
    if (mapCreated || isDestroyed()) return;
    try {
      mapView.onCreate(null);
      mapCreated = true;
      resumeMap(mapView);
      mapView.getOpenStreetMapAsync(this, mapType());
    } catch (Throwable t) {
      Log.e("Unable to initialize OpenStreetMap", t);
    }
  }

  @Override
  protected void resumeMap (@NonNull OpenStreetMapView mapView) {
    if (!mapCreated || mapResumed || isPaused() || isDestroyed()) return;
    try {
      mapView.onStart();
      mapView.onResume();
      mapResumed = true;
    } catch (Throwable t) {
      Log.e("Unable to resume OpenStreetMap", t);
    }
  }

  @Override
  protected void pauseMap (@NonNull OpenStreetMapView mapView) {
    if (!mapResumed) return;
    try { mapView.onPause(); } catch (Throwable ignored) { }
    try { mapView.onStop(); } catch (Throwable ignored) { }
    mapResumed = false;
  }

  @Override
  protected void destroyMap (@NonNull OpenStreetMapView mapView) {
    if (!mapCreated) return;
    pauseMap(mapView);
    try { mapView.onDestroy(); } catch (Throwable ignored) { }
    mapCreated = false;
    map = null;
  }

  @Override
  protected boolean onBuildDirectionTo (@NonNull OpenStreetMapView mapView, double latitude, double longitude) {
    return false;
  }

  @SuppressWarnings("MissingPermission")
  @Override
  protected boolean displayMyLocation (@NonNull OpenStreetMapView mapView) {
    if (map != null) {
      try {
        map.setMyLocationEnabled(true);
        return true;
      } catch (Throwable ignored) { }
    }
    return false;
  }

  private OpenStreetMap map;

  @Override
  protected void onApplyMapType (int oldType, int newType) {
    if (map != null) map.setMapType(newType);
  }

  @Override
  public void onMapReady (OpenStreetMap map) {
    if (isDestroyed()) {
      return;
    }

    this.map = map;

    map.setOnMyLocationChangeListener(this);
    map.setOnMarkerClickListener(this);

    map.setOnCameraMoveStartedListener(this);

    if (context.permissions().canAccessLocation()) {
      try { map.setMyLocationEnabled(true); } catch (Throwable ignored) { }
    }

    List<LocationPoint<MarkerData>> points = pointsOfInterest();
    for (LocationPoint<MarkerData> point : points) {
      if (point.data != null) {
        point.data.setPosition(point);
      } else {
        point.data = new MarkerData(tdlib, map, point);
      }
    }
    boolean isSharing = isSharingLiveLocation();
    if (isSharing) {
      LocationPoint<MarkerData> point = myLocation(false);
      if (point != null) {
        if (point.data != null) {
          point.data.setPosition(point);
        } else {
          point.data = new MarkerData(tdlib, map, point);
        }
      }
    }
    map.moveCamera(buildCamera(mapView(), null, false, getArgumentsStrict().mode == MODE_DROPPED_PIN));

    resumeMap(mapView());

    executeScheduledAnimation();
  }

  @Override
  protected void onPointOfInterestFocusStateChanged (LocationPoint<MarkerData> point, boolean isFocused) {
    if (point.data != null) {
      point.data.marker.setZIndex(isFocused ? 10f : point.isLiveLocation && point.message != null ? 1f : 0f);
    }
  }

  @Override
  protected void onPointOfInterestAdded (LocationPoint<MarkerData> point, int toIndex) {
    if (map != null) {
      if (point.data != null) {
        point.data.setPosition(point);
      } else {
        point.data = new MarkerData(tdlib, map, point);
      }
    }
  }

  @Override
  protected void onPointOfInterestRemoved (LocationPoint<MarkerData> point, int fromIndex) {
    if (point.data != null) {
      point.data.remove();
      point.data = null;
    }
  }

  @Override
  protected void onPointOfInterestCoordinatesChanged (LocationPoint<MarkerData> point, int index) {
    if (point.data != null) {
      point.data.setPosition(point);
    }
  }

  @Override
  protected void onPointOfInterestActiveStateMightChanged (LocationPoint<MarkerData> point, boolean isActive) {
    if (point.data != null) {
      point.data.setActive(isActive);
    }
  }

  private CameraUpdate buildCamera (OpenStreetMapView mapView, @Nullable LocationPoint<MarkerData> specificPoint, boolean needBearing, boolean onlyFocus) {
    LocationPoint<MarkerData> singlePoint = specificPoint;
    List<LocationPoint<MarkerData>> pointOfInterests = pointsOfInterest();
    LocationPoint<MarkerData> myLocation = myLocation(true);

    if (singlePoint == null) {
      int totalCount = pointOfInterests.size();
      if (myLocation != null) {
        totalCount++;
      }
      if (totalCount == 1) {
        singlePoint = myLocation != null ? myLocation : pointOfInterests.get(0);
      } else if (totalCount == 0) {
        return CameraUpdateFactory.newLatLngZoom(new LatLng(getArgumentsStrict().latitude,
          getArgumentsStrict().longitude), DEFAULT_ZOOM_LEVEL);
      }
    }

    if (singlePoint != null) {
      CameraPosition.Builder b = new CameraPosition.Builder();
      b.target(new LatLng(singlePoint.latitude, singlePoint.longitude));
      float zoom = DEFAULT_ZOOM_LEVEL;
      if (specificPoint != null) {
        zoom = (float) Math.max(map.getCameraPosition().zoom, CLICK_ZOOM_LEVEL);
      }
      b.zoom(zoom);
      if (needBearing) {
        b.bearing(singlePoint.bearing);
        b.tilt(45f);
      }
      return CameraUpdateFactory.newCameraPosition(b.build());
    }

    LatLngBounds.Builder b = new LatLngBounds.Builder();
    if (myLocation != null) {
      b.include(new LatLng(myLocation.latitude, myLocation.longitude));
    }
    if (onlyFocus) {
      if (hasFocusPoint()) {
        LocationPoint<MarkerData> point = pointOfInterests.get(0);
        b.include(new LatLng(point.latitude, point.longitude));
      }
    } else {
      for (LocationPoint<MarkerData> point : pointOfInterests) {
        b.include(new LatLng(point.latitude, point.longitude));
      }
    }

    // MapLibre requires two positions, even when both refer to the same place.
    if (onlyFocus && !hasFocusPoint() && myLocation != null) {
      b.include(new LatLng(myLocation.latitude, myLocation.longitude));
    }
    LatLngBounds tmpBounds = b.build();
    LatLng center = tmpBounds.getCenter();
    int bound = 111;
    LatLng northEast = move(center, bound, bound);
    LatLng southWest = move(center, -bound, -bound);
    b.include(southWest);
    b.include(northEast);

    LatLngBounds bounds = b.build();

    return CameraUpdateFactory.newLatLngBounds(bounds, Screen.dp(82f));
  }

  private static final double EARTH_RADIUS = 6366198;
  /**
   * Create a new LatLng which lies toNorth meters north and toEast meters
   * east of startLL
   */
  private static LatLng move(LatLng startLL, double toNorth, double toEast) {
    double lonDiff = meterToLongitude(toEast, startLL.getLatitude());
    double latDiff = meterToLatitude(toNorth);
    return new LatLng(startLL.getLatitude() + latDiff, startLL.getLongitude()
      + lonDiff);
  }

  private static double meterToLongitude(double meterToEast, double latitude) {
    double latArc = Math.toRadians(latitude);
    double radius = Math.cos(latArc) * EARTH_RADIUS;
    double rad = meterToEast / radius;
    return Math.toDegrees(rad);
  }


  private static double meterToLatitude(double meterToNorth) {
    double rad = meterToNorth / EARTH_RADIUS;
    return Math.toDegrees(rad);
  }

  @Override
  protected boolean onPositionRequested (@NonNull OpenStreetMapView mapView, @Nullable LocationPoint<MarkerData> point, boolean animated, boolean needBearing, boolean onlyFocus) {
    if (map != null) {
      CameraUpdate cameraUpdate = buildCamera(mapView, point, needBearing, onlyFocus);
      if (animated) {
        map.animateCamera(cameraUpdate);
        return true;
      } else {
        map.moveCamera(cameraUpdate);
      }
    }
    return false;
  }

  @Override
  public void onMyLocationChange (Location location) {
    Settings.instance().saveLastKnownLocation(location.getLatitude(), location.getLongitude(), location.getAccuracy());
    setMyLocation(location);
  }

  @Override
  @SuppressWarnings("unchecked")
  public boolean onMarkerClick (Marker marker) {
    LocationPoint<MarkerData> point = (LocationPoint<MarkerData>) marker.getTag();
    if (point != null) {
      long chatId = 0;
      long messageId = 0;
      if (point.message != null) {
        chatId = point.message.chatId;
        messageId = point.message.id;
      } else if (point.isSelfLocation) {
        chatId = getArgumentsStrict().chatId;
        TdApi.Message outputMessage = tdlib.cache().findOutputLiveLocationMessage(chatId);
        if (outputMessage != null) {
          messageId = outputMessage.id;
        }
      }
      if (chatId != 0 && messageId != 0) {
        tdlib.ui().openChat(this, chatId, new TdlibUi.ChatOpenParameters().highlightMessage(new MessageId(chatId, messageId)).ensureHighlightAvailable());
      }
    }
    return true;
  }

  @Override
  public void onCameraMoveStarted (int reason) {
    if (reason == REASON_API_GESTURE) {
      onUserMovedCamera();
    }
  }

  @Override
  protected boolean wouldRememberMapType (int newMapType) {
    switch (newMapType) {
      case Settings.MAP_TYPE_HYBRID:
      case Settings.MAP_TYPE_SATELLITE:
        return false;
    }
    return true;
  }

  @Override
  protected int[] getAvailableMapTypes () {
    return new int[] {
      Settings.MAP_TYPE_DEFAULT,
      Settings.MAP_TYPE_DARK,
      Settings.MAP_TYPE_SATELLITE,
      Settings.MAP_TYPE_TERRAIN
    };
  }

  @Override
  protected boolean onStartPeriodicBearingUpdates (@NonNull OpenStreetMapView mapView) {
    return false;
  }

  @Override
  protected void onFinishPeriodicBearingUpdates (@NonNull OpenStreetMapView mapView) { }
}
