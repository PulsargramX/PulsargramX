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

import org.maplibre.android.MapLibre;
import org.maplibre.android.maps.Style;
import org.maplibre.android.module.http.HttpRequestUtil;
import org.maplibre.android.style.layers.HillshadeLayer;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.sources.RasterDemSource;
import org.maplibre.android.style.sources.TileSet;
import org.thunderdog.challegram.BuildConfig;
import org.thunderdog.challegram.unsorted.Settings;

import okhttp3.OkHttpClient;

/** Shared tile sources for interactive maps and message previews. No API credentials are needed. */
public final class OpenStreetMapStyles {
  private OpenStreetMapStyles () { }

  private static boolean initialized;

  public static synchronized void initialize (Context context) {
    if (initialized) return;
    MapLibre.getInstance(context.getApplicationContext());
    HttpRequestUtil.setOkHttpClient(new OkHttpClient.Builder()
      .addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
        .header("User-Agent", BuildConfig.PROJECT_NAME.replace(" ", "-") + "/" + BuildConfig.VERSION_NAME +
          " (+" + BuildConfig.SOURCES_URL + ")")
        .build()))
      .build());
    HttpRequestUtil.setLogEnabled(false);
    initialized = true;
  }

  public static Style.Builder style (int type) {
    switch (type) {
      case Settings.MAP_TYPE_DARK:
        return new Style.Builder().fromUri("https://tiles.openfreemap.org/styles/dark");
      case Settings.MAP_TYPE_TERRAIN:
        return new Style.Builder().fromUri("https://tiles.openfreemap.org/styles/bright");
      case Settings.MAP_TYPE_SATELLITE:
      case Settings.MAP_TYPE_HYBRID:
        return new Style.Builder().fromJson(SATELLITE_STYLE);
      default:
        return new Style.Builder().fromUri("https://tiles.openfreemap.org/styles/liberty");
    }
  }

  public static void finishStyle (Style style, int type) {
    if (type != Settings.MAP_TYPE_TERRAIN) return;
    TileSet tiles = new TileSet("2.2.0",
      "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png");
    tiles.setMaxZoom(15f);
    tiles.setEncoding("terrarium");
    tiles.setAttribution("<a href=\"https://www.mapzen.com/rights/\">© Mapzen</a> and " +
      "<a href=\"https://www.mapzen.com/rights/#services-and-data-sources\">others</a>");
    style.addSource(new RasterDemSource("client-terrain", tiles, 256));
    HillshadeLayer hillshade = new HillshadeLayer("client-hillshade", "client-terrain")
      .withProperties(PropertyFactory.hillshadeExaggeration(.5f));
    // Put relief below roads, labels, and water, while retaining the OSM street map.
    if (style.getLayer("water") != null) {
      style.addLayerBelow(hillshade, "water");
    } else {
      style.addLayerAt(hillshade, Math.min(2, style.getLayers().size()));
    }
  }

  // The 2016 Sentinel-2 mosaic is licensed under CC BY 4.0. Later EOX mosaics have
  // different licensing. Keep OSM labels on top, as with the previous satellite mode.
  private static final String SATELLITE_STYLE = "{\"version\":8," +
    "\"glyphs\":\"https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf\"," +
    "\"sources\":{\"satellite\":{\"type\":\"raster\",\"tileSize\":256," +
    "\"maxzoom\":14,\"tiles\":[\"https://tiles.maps.eox.at/wmts/1.0.0/" +
    "s2cloudless_3857/default/g/{z}/{y}/{x}.jpg\"],\"attribution\":" +
    "\"<a href='https://cloudless.eox.at'>EOxCloudless</a> by " +
    "<a href='https://eox.at'>EOX IT Services GmbH</a> " +
    "(Contains modified Copernicus Sentinel data 2016), " +
    "<a href='https://creativecommons.org/licenses/by/4.0/'>CC BY 4.0</a>\"}," +
    "\"openmaptiles\":{\"type\":\"vector\",\"url\":\"https://tiles.openfreemap.org/planet\"}}," +
    "\"layers\":[{\"id\":\"satellite\",\"type\":\"raster\",\"source\":\"satellite\"}," +
    "{\"id\":\"roads\",\"type\":\"line\",\"source\":\"openmaptiles\"," +
    "\"source-layer\":\"transportation\",\"minzoom\":10,\"paint\":{" +
    "\"line-color\":\"#ffffff\",\"line-opacity\":0.6,\"line-width\":1}}," +
    "{\"id\":\"places\",\"type\":\"symbol\",\"source\":\"openmaptiles\"," +
    "\"source-layer\":\"place\",\"layout\":{\"text-field\":[\"coalesce\"," +
    "[\"get\",\"name:latin\"],[\"get\",\"name\"]],\"text-font\":[\"Noto Sans Regular\"]," +
    "\"text-size\":14},\"paint\":{\"text-color\":\"#ffffff\"," +
    "\"text-halo-color\":\"#242424\",\"text-halo-width\":1.5}}," +
    "{\"id\":\"street-names\",\"type\":\"symbol\",\"source\":\"openmaptiles\"," +
    "\"source-layer\":\"transportation_name\",\"minzoom\":13,\"layout\":{" +
    "\"symbol-placement\":\"line\",\"text-field\":[\"coalesce\",[\"get\",\"name:latin\"]," +
    "[\"get\",\"name\"]],\"text-font\":[\"Noto Sans Regular\"],\"text-size\":12}," +
    "\"paint\":{\"text-color\":\"#ffffff\",\"text-halo-color\":\"#242424\"," +
    "\"text-halo-width\":1.5}}]}";
}
