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

import android.content.Context;
import android.view.Gravity;

import org.maplibre.android.maps.MapLibreMapOptions;
import org.maplibre.android.maps.MapView;

/** MapLibre view with the same lifecycle used by the existing location screens. */
public class OpenStreetMapView extends MapView {
  public interface ReadyCallback {
    void onMapReady (OpenStreetMap map);
  }

  public OpenStreetMapView (Context context) {
    super(context, options(context));
  }

  private static MapLibreMapOptions options (Context context) {
    OpenStreetMapStyles.initialize(context);
    return new MapLibreMapOptions()
      .textureMode(true)
      .logoEnabled(false)
      .compassEnabled(false)
      .attributionEnabled(true)
      .attributionGravity(Gravity.BOTTOM | Gravity.LEFT);
  }

  private OpenStreetMap map;
  private int bottomInset;
  private boolean resumed;
  private boolean destroyed;

  public void getOpenStreetMapAsync (ReadyCallback callback, int mapType) {
    getMapAsync(nativeMap -> {
      if (destroyed) return;
      nativeMap.setPadding(0, 0, 0, bottomInset);
      map = new OpenStreetMap(getContext(), nativeMap);
      map.setResumed(resumed);
      map.setMapType(mapType);
      // Map interaction and location selection must also work while tiles are offline.
      callback.onMapReady(map);
    });
  }

  public void setBottomInset (int bottomInset) {
    this.bottomInset = bottomInset;
  }

  @Override
  public void onResume () {
    super.onResume();
    resumed = true;
    if (map != null) map.setResumed(true);
  }

  @Override
  public void onPause () {
    resumed = false;
    if (map != null) map.setResumed(false);
    super.onPause();
  }

  @Override
  public void onDestroy () {
    destroyed = true;
    if (map != null) map.destroy();
    map = null;
    super.onDestroy();
  }
}
