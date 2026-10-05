/*
 * This file is a part of Pulsargram X
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.thunderdog.challegram.component.location;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;

import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdate;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.location.LocationComponent;
import org.maplibre.android.location.LocationComponentActivationOptions;
import org.maplibre.android.location.modes.RenderMode;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.Point;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Screen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.maplibre.android.style.expressions.Expression.get;
import static org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap;
import static org.maplibre.android.style.layers.PropertyFactory.iconAnchor;
import static org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement;
import static org.maplibre.android.style.layers.PropertyFactory.iconImage;
import static org.maplibre.android.style.layers.PropertyFactory.iconOffset;
import static org.maplibre.android.style.layers.PropertyFactory.iconOpacity;
import static org.maplibre.android.style.layers.PropertyFactory.symbolSortKey;

/** Rendering adapter; chat, live location, and picker state stay in their existing controllers. */
public final class OpenStreetMap implements android.location.LocationListener {
  public interface LocationListener {
    void onMyLocationChange (Location location);
  }

  public interface MarkerClickListener {
    boolean onMarkerClick (Marker marker);
  }

  private static final String MARKERS = "client-markers";
  private final Context context;
  private final MapLibreMap map;
  private final LocationManager locationManager;
  private final Map<Integer, Marker> markers = new LinkedHashMap<>();
  private int nextMarkerId;
  private int styleGeneration;
  private Style style;
  private GeoJsonSource markerSource;
  private boolean resumed;
  private boolean destroyed;
  private boolean locationEnabled;
  private boolean requestingLocation;
  private Location lastLocation;
  private LocationListener locationListener;
  private MarkerClickListener markerClickListener;

  public OpenStreetMap (Context context, MapLibreMap map) {
    this.context = context;
    this.map = map;
    this.locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    map.setMinZoomPreference(2);
    map.setMaxZoomPreference(21);
    map.getUiSettings().setCompassEnabled(false);
    map.getUiSettings().setLogoEnabled(false);
    map.addOnMapClickListener(coordinates -> {
      if (markerSource == null || markerClickListener == null) return false;
      android.graphics.PointF pixel = map.getProjection().toScreenLocation(coordinates);
      float radius = Screen.dp(8);
      List<Feature> features = map.queryRenderedFeatures(
        new RectF(pixel.x - radius, pixel.y - radius, pixel.x + radius, pixel.y + radius), MARKERS);
      Marker top = null;
      for (Feature feature : features) {
        Marker marker = markers.get(feature.getNumberProperty("markerId").intValue());
        if (marker != null && (top == null || marker.zIndex > top.zIndex)) top = marker;
      }
      return top != null && markerClickListener.onMarkerClick(top);
    });
  }

  public void setMapType (int type) {
    int generation = ++styleGeneration;
    style = null;
    markerSource = null;
    map.setStyle(OpenStreetMapStyles.style(type), loadedStyle -> {
      if (destroyed || generation != styleGeneration) return;
      style = loadedStyle;
      OpenStreetMapStyles.finishStyle(style, type);
      markerSource = new GeoJsonSource(MARKERS);
      style.addSource(markerSource);
      style.addLayer(new SymbolLayer(MARKERS, MARKERS).withProperties(
        iconImage(get("image")), iconAnchor("bottom"), iconOffset(get("offset")),
        iconOpacity(get("opacity")), symbolSortKey(get("order")),
        iconAllowOverlap(true), iconIgnorePlacement(true)));
      for (Marker marker : markers.values()) style.addImage(marker.imageId(), marker.icon);
      updateMarkers();
      enableLocationLayer();
    });
  }

  public CameraPosition getCameraPosition () { return map.getCameraPosition(); }
  public float getMinZoomLevel () { return (float) map.getMinZoomLevel(); }
  public float getMaxZoomLevel () { return (float) map.getMaxZoomLevel(); }
  public void moveCamera (CameraUpdate update) { map.moveCamera(update); }
  public void animateCamera (CameraUpdate update) { map.animateCamera(update); }

  public void setOnCameraMoveStartedListener (MapLibreMap.OnCameraMoveStartedListener listener) {
    map.addOnCameraMoveStartedListener(listener);
  }

  public void setOnCameraMoveListener (MapLibreMap.OnCameraMoveListener listener) {
    map.addOnCameraMoveListener(listener);
  }

  public void setOnCameraIdleListener (MapLibreMap.OnCameraIdleListener listener) {
    map.addOnCameraIdleListener(listener);
  }

  public void setOnCameraMoveCanceledListener (MapLibreMap.OnCameraMoveCanceledListener listener) {
    map.addOnCameraMoveCancelListener(listener);
  }

  public void setOnMarkerClickListener (MarkerClickListener listener) {
    markerClickListener = listener;
  }

  public void setOnMyLocationChangeListener (LocationListener listener) {
    locationListener = listener;
    if (lastLocation != null) listener.onMyLocationChange(new Location(lastLocation));
  }

  public void setMyLocationEnabled (boolean enabled) {
    locationEnabled = enabled;
    enableLocationLayer();
    updateLocationSubscription();
  }

  void setResumed (boolean resumed) {
    this.resumed = resumed;
    updateLocationSubscription();
  }

  private boolean hasLocationPermission () {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
      context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
      context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
  }

  @SuppressLint("MissingPermission")
  private void enableLocationLayer () {
    if (style == null || destroyed || !hasLocationPermission()) return;
    LocationComponent component = map.getLocationComponent();
    if (!component.isLocationComponentActivated()) {
      component.activateLocationComponent(LocationComponentActivationOptions.builder(context, style)
        .useDefaultLocationEngine(false).build());
      component.setRenderMode(RenderMode.NORMAL);
    }
    component.setLocationComponentEnabled(locationEnabled);
    if (lastLocation != null) component.forceLocationUpdate(lastLocation);
  }

  @SuppressLint("MissingPermission")
  private void updateLocationSubscription () {
    if (locationManager == null) return;
    boolean needLocation = resumed && locationEnabled && !destroyed && hasLocationPermission();
    if (!needLocation) {
      if (requestingLocation) {
        try { locationManager.removeUpdates(this); } catch (SecurityException ignored) { }
        requestingLocation = false;
      }
      return;
    }
    if (requestingLocation) return;
    Location cached = null;
    List<String> providers = locationManager.getAllProviders();
    for (String provider : new String[] {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
      try {
        if (!providers.contains(provider)) continue;
        locationManager.requestLocationUpdates(provider, 1000L, 0f, this, Looper.getMainLooper());
        requestingLocation = true;
        if (!locationManager.isProviderEnabled(provider)) continue;
        Location location = locationManager.getLastKnownLocation(provider);
        if (location != null && (cached == null || location.getTime() > cached.getTime())) cached = location;
      } catch (IllegalArgumentException | SecurityException ignored) { }
    }
    if (cached != null) onLocationChanged(cached);
  }

  @Override
  public void onLocationChanged (Location location) {
    if (destroyed || !resumed || !locationEnabled) return;
    if (lastLocation != null) {
      long ageDifference = location.getTime() - lastLocation.getTime();
      // Avoid replacing a fresh GPS fix with a less accurate network fix.
      if (ageDifference < -120000L || (ageDifference <= 120000L &&
          location.getAccuracy() > lastLocation.getAccuracy() &&
          (ageDifference <= 0 || location.getAccuracy() - lastLocation.getAccuracy() > 200f ||
            !Objects.equals(location.getProvider(), lastLocation.getProvider())))) return;
    }
    lastLocation = new Location(location);
    if (style != null && map.getLocationComponent().isLocationComponentActivated()) {
      map.getLocationComponent().forceLocationUpdate(lastLocation);
    }
    if (locationListener != null) locationListener.onMyLocationChange(new Location(lastLocation));
  }

  @Override public void onProviderEnabled (String provider) { updateLocationSubscription(); }
  @Override public void onProviderDisabled (String provider) { }
  @Override public void onStatusChanged (String provider, int status, Bundle extras) { }

  void destroy () {
    destroyed = true;
    updateLocationSubscription();
    locationListener = null;
    markerClickListener = null;
    markerSource = null;
    style = null;
    markers.clear();
  }

  public Marker addMarker (LatLng position, @Nullable Bitmap icon) {
    Marker marker = new Marker(nextMarkerId++, position, icon != null ? icon : defaultPin());
    markers.put(marker.id, marker);
    if (style != null) style.addImage(marker.imageId(), marker.icon);
    updateMarkers();
    return marker;
  }

  private Bitmap defaultPin () {
    Bitmap bitmap = Bitmap.createBitmap(Screen.dp(44), Screen.dp(44), Bitmap.Config.ARGB_8888);
    Drawables.draw(new Canvas(bitmap), Drawables.get(context.getResources(), R.drawable.ic_map_pin_44),
      0, 0, null);
    return bitmap;
  }

  private void updateMarkers () {
    if (markerSource == null) return;
    List<Feature> features = new ArrayList<>(markers.size());
    for (Marker marker : markers.values()) {
      Feature feature = Feature.fromGeometry(Point.fromLngLat(
        marker.position.getLongitude(), marker.position.getLatitude()));
      feature.addNumberProperty("markerId", marker.id);
      feature.addStringProperty("image", marker.imageId());
      feature.addNumberProperty("opacity", marker.alpha);
      feature.addNumberProperty("order", marker.zIndex);
      JsonArray offset = new JsonArray();
      offset.add(0);
      offset.add(marker.anchorOffset);
      feature.addProperty("offset", offset);
      features.add(feature);
    }
    markerSource.setGeoJson(FeatureCollection.fromFeatures(features));
  }

  public final class Marker {
    private final int id;
    private LatLng position;
    private Bitmap icon;
    private Object tag;
    private float alpha = 1f;
    private float zIndex;
    private float anchorOffset;
    private boolean removed;

    private Marker (int id, LatLng position, Bitmap icon) {
      this.id = id;
      this.position = position;
      this.icon = icon;
    }

    private String imageId () { return MARKERS + "-" + id; }
    public void setTag (Object tag) { this.tag = tag; }
    public Object getTag () { return tag; }
    public void setPosition (LatLng position) { this.position = position; updateMarkers(); }
    public void setAlpha (float alpha) { this.alpha = alpha; updateMarkers(); }
    public void setZIndex (float zIndex) { this.zIndex = zIndex; updateMarkers(); }
    public void setAnchor (float x, float y) {
      anchorOffset = icon.getHeight() / Screen.density() * (1f - y);
      updateMarkers();
    }
    public void setIcon (Bitmap icon) {
      if (removed || destroyed) return;
      this.icon = icon;
      if (style != null) style.addImage(imageId(), icon);
    }
    public void remove () {
      removed = true;
      markers.remove(id);
      updateMarkers();
      if (style != null) style.removeImage(imageId());
    }
  }
}
