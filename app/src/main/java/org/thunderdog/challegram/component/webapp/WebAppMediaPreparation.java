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

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.effect.Presentation;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;

import org.thunderdog.challegram.U;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.function.Consumer;

/** Shared media normalization for stories and owner-managed Mini App previews. */
public final class WebAppMediaPreparation {
  private WebAppMediaPreparation () { }

  /** Decode off the UI thread, orient and crop to TDLib's required photo dimensions. */
  public static Bitmap decodePhoto (File source) {
    BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
    BitmapFactory.decodeFile(source.getAbsolutePath(), bounds);
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
    BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1;
    while (bounds.outWidth / options.inSampleSize > 2160 || bounds.outHeight / options.inSampleSize > 3840) options.inSampleSize *= 2;
    Bitmap decoded = BitmapFactory.decodeFile(source.getAbsolutePath(), options);
    if (decoded == null) return null;
    try {
      int orientation = U.getExifOrientation(source.getAbsolutePath());
      Matrix matrix = U.exifMatrix(decoded.getWidth(), decoded.getHeight(), orientation);
      if (matrix != null && !matrix.isIdentity()) {
        Bitmap oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), matrix, true);
        if (oriented != decoded) decoded.recycle(); decoded = oriented;
      }
      Bitmap normalized = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888);
      Canvas canvas = new Canvas(normalized);
      canvas.drawColor(android.graphics.Color.BLACK);
      float scale = Math.max(1080f / decoded.getWidth(), 1920f / decoded.getHeight());
      int width = Math.round(decoded.getWidth() * scale), height = Math.round(decoded.getHeight() * scale);
      canvas.drawBitmap(decoded, null, new Rect((1080 - width) / 2, (1920 - height) / 2,
        (1080 + width) / 2, (1920 + height) / 2), new Paint(Paint.FILTER_BITMAP_FLAG));
      return normalized;
    } finally { decoded.recycle(); }
  }

  public static void writePhoto (Bitmap photo, File destination) throws IOException {
    try (FileOutputStream stream = new FileOutputStream(destination)) {
      if (!photo.compress(Bitmap.CompressFormat.JPEG, 90, stream)) throw new IOException("Cannot encode photo");
    }
  }

  /** Call and cancel on the UI thread. No codec fallback can change the required HEVC format. */
  public static Transformer prepareVideo (Context context, File source, File destination, double duration,
                                          Consumer<Boolean> completed) {
    MediaItem item = new MediaItem.Builder().setUri(Uri.fromFile(source))
      .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder().setEndPositionMs((long) (duration * 1000)).build()).build();
    EditedMediaItem edited = new EditedMediaItem.Builder(item).setEffects(new Effects(Collections.emptyList(),
      Collections.singletonList(Presentation.createForWidthAndHeight(720, 1280, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)))).build();
    Transformer transformer = new Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H265).setAudioMimeType(MimeTypes.AUDIO_AAC)
      .setEncoderFactory(new DefaultEncoderFactory.Builder(context).setEnableFallback(false).setRequestedVideoEncoderSettings(
        new VideoEncoderSettings.Builder().setBitrate(2500000).setiFrameIntervalSeconds(1).build()).build())
      .addListener(new Transformer.Listener() {
        @Override public void onCompleted (Composition composition, ExportResult result) { completed.accept(true); }
        @Override public void onError (Composition composition, ExportResult result, ExportException exception) { completed.accept(false); }
      }).build();
    transformer.start(edited, destination.getAbsolutePath());
    return transformer;
  }
}
