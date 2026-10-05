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
package org.thunderdog.challegram.loader;

import android.graphics.Bitmap;
import android.os.SystemClock;

import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.snapshotter.MapSnapshotter;
import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.component.location.OpenStreetMapStyles;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.unsorted.Settings;

import java.util.ArrayDeque;

/** An OSM snapshot loaded and cached through the ordinary message image pipeline. */
public final class ImageFileMap extends ImageFileLocal {
  private final double latitude;
  private final double longitude;
  private final int zoom;
  private final int width;
  private final int height;

  public ImageFileMap (double latitude, double longitude, int zoom, int width, int height) {
    super("osm_map_" + latitude + "_" + longitude + "_" + zoom + "_" + width + "_" + height);
    this.latitude = latitude;
    this.longitude = longitude;
    this.zoom = zoom;
    this.width = Math.max(32, width);
    this.height = Math.max(32, height);
    setNoBlur();
  }

  public ImageFileMap (ImageFileMap copy) {
    super(copy);
    latitude = copy.latitude;
    longitude = copy.longitude;
    zoom = copy.zoom;
    width = copy.width;
    height = copy.height;
  }

  // Bound native renderer use while scrolling through many location messages.
  // The queue and snapshotters are owned by the UI thread, as required by MapLibre.
  private static final ArrayDeque<RenderTask> pending = new ArrayDeque<>();
  private static int active;

  public void read (ImageActor actor, ImageReader.Listener listener) {
    UI.post(() -> {
      pending.add(new RenderTask(this, actor, listener));
      drain();
    });
  }

  private static void drain () {
    while (active < 2 && !pending.isEmpty()) {
      RenderTask task = pending.removeFirst();
      if (!task.actor.isCancelled()) {
        active++;
        task.start();
      }
    }
  }

  private static final class RenderTask implements Runnable {
    private final ImageFileMap file;
    private final ImageActor actor;
    private final ImageReader.Listener listener;
    private MapSnapshotter snapshotter;
    private long started;
    private boolean finished;

    private RenderTask (ImageFileMap file, ImageActor actor, ImageReader.Listener listener) {
      this.file = file;
      this.actor = actor;
      this.listener = listener;
    }

    private void start () {
      started = SystemClock.uptimeMillis();
      try {
        OpenStreetMapStyles.initialize(UI.getAppContext());
        float scale = Math.min(2f, Screen.density());
        float resize = Math.min(1f, 1024f / Math.max(file.width, file.height));
        int width = Math.max(32, Math.round(file.width * resize / scale));
        int height = Math.max(32, Math.round(file.height * resize / scale));
        snapshotter = new MapSnapshotter(UI.getAppContext(), new MapSnapshotter.Options(width, height)
          .withPixelRatio(scale)
          .withStyleBuilder(OpenStreetMapStyles.style(Settings.MAP_TYPE_DEFAULT))
          .withCameraPosition(new CameraPosition.Builder()
            .target(new LatLng(file.latitude, file.longitude)).zoom(file.zoom).build())
          .withLogo(false));
        snapshotter.start(snapshot -> complete(snapshot.getBitmap()), error -> complete(null));
        UI.post(this, 1000L);
      } catch (Throwable t) {
        Log.w("Cannot render OpenStreetMap preview", t);
        complete(null);
      }
    }

    @Override
    public void run () {
      if (finished) return;
      if (actor.isCancelled() || SystemClock.uptimeMillis() - started >= 30000L) {
        complete(null);
      } else {
        UI.post(this, 1000L);
      }
    }

    private void complete (Bitmap bitmap) {
      if (finished) return;
      finished = true;
      if (snapshotter != null) {
        snapshotter.cancel();
        snapshotter = null;
      }
      if (!actor.isCancelled()) {
        listener.onImageLoaded(bitmap != null, bitmap);
      } else if (bitmap != null) {
        bitmap.recycle();
      }
      active--;
      UI.post(ImageFileMap::drain);
    }
  }
}
