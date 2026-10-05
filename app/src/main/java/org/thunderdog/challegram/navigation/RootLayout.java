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
 * File created on 17/08/2015 at 23:11
 */
package org.thunderdog.challegram.navigation;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Build;
import android.view.RoundedCorner;
import android.view.View;
import android.view.WindowInsets;

import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Screen;

import me.vkryl.android.widget.FrameLayoutFix;

public class RootLayout extends FrameLayoutFix {
  public RootLayout (Context context) {
    super(context);
  }

  private View backPreview;
  private NavigationLayout backPreviewParent;
  private final RectF backClosingRect = new RectF();
  private final RectF backEnteringRect = new RectF();
  private final float[] backCornerRadii = new float[8];
  private final float[] backScaledCornerRadii = new float[8];
  private float backForegroundAlpha = 1f, backDimFactor;
  private int backHeaderColor;
  private HeaderView backHeader;
  private FloatingButton backFloatingButton;
  private BackSnapshot backHeaderSnapshot, backFloatingButtonSnapshot;
  private final Path backClip = new Path();
  private final Paint backPaint = new Paint();

  private static final class BackSnapshot {
    final Bitmap bitmap;
    final Matrix matrix;
    final int left, top;
    final Paint paint;

    BackSnapshot (View view) {
      bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
      matrix = new Matrix(view.getMatrix());
      left = view.getLeft();
      top = view.getTop();
      paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
      paint.setAlpha(Math.round(255f * view.getAlpha()));
      try {
        view.draw(new Canvas(bitmap));
      } catch (RuntimeException | OutOfMemoryError e) {
        bitmap.recycle();
        throw e;
      }
    }

    void draw (Canvas canvas) {
      int saveCount = canvas.save();
      canvas.translate(left, top);
      canvas.concat(matrix);
      canvas.drawBitmap(bitmap, 0f, 0f, paint);
      canvas.restoreToCount(saveCount);
    }
  }

  boolean prepareBackHeader (HeaderView header, FloatingButton floatingButton) {
    clearBackHeader();
    if (header.getWidth() <= 0 || header.getHeight() <= 0) {
      return false;
    }
    prepareBackCorners();
    try {
      // Preserve only the outgoing chrome. The existing shared header can then show the live
      // destination without detaching its children or changing either controller's ownership.
      if (header.getVisibility() == VISIBLE) {
        backHeaderSnapshot = new BackSnapshot(header);
      }
      if (floatingButton.getVisibility() == VISIBLE && floatingButton.getAlpha() > 0f &&
          floatingButton.getWidth() > 0 && floatingButton.getHeight() > 0) {
        backFloatingButtonSnapshot = new BackSnapshot(floatingButton);
      }
      backHeader = header;
      backFloatingButton = floatingButton;
      invalidate();
      return true;
    } catch (RuntimeException | OutOfMemoryError e) {
      clearBackHeader();
      Log.w("Cannot capture predictive back header", e);
      return false;
    }
  }

  private void prepareBackCorners () {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      WindowInsets insets = getRootWindowInsets();
      if (insets != null) {
        int[] positions = {
          RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT,
          RoundedCorner.POSITION_BOTTOM_RIGHT, RoundedCorner.POSITION_BOTTOM_LEFT
        };
        for (int i = 0; i < positions.length; i++) {
          RoundedCorner corner = insets.getRoundedCorner(positions[i]);
          backCornerRadii[i * 2] = backCornerRadii[i * 2 + 1] =
            corner != null ? corner.getRadius() : 0f;
        }
        return;
      }
    }
    for (int i = 0; i < backCornerRadii.length; i++) {
      backCornerRadii[i] = Screen.dp(28f);
    }
  }

  private void clearBackHeader () {
    // Hardware display lists may still reference these bitmaps until the next frame. Release
    // our ownership and let their native references drain instead of recycling rendered pixels.
    backHeaderSnapshot = null;
    backFloatingButtonSnapshot = null;
    backHeader = null;
    backFloatingButton = null;
  }

  void setBackPreview (View preview, RectF closingRect, RectF enteringRect,
                       int headerColor, float foregroundAlpha, float dimFactor) {
    if (backPreview != preview) {
      if (backPreview != null) {
        clearBackPreview();
      }
      backPreview = preview;
      backPreviewParent = (NavigationLayout) preview.getParent();
    }
    backClosingRect.set(closingRect);
    backEnteringRect.set(enteringRect);
    backHeaderColor = headerColor;
    backForegroundAlpha = Math.max(0f, Math.min(1f, foregroundAlpha));
    backDimFactor = Math.max(0f, Math.min(1f, dimFactor));
    invalidate();
  }

  void clearBackPreview () {
    backPreviewParent = null;
    backPreview = null;
    clearBackHeader();
    backForegroundAlpha = 1f;
    backDimFactor = 0f;
    backClosingRect.setEmpty();
    backEnteringRect.setEmpty();
    backClip.rewind();
    invalidate();
  }

  @Override
  protected void onDetachedFromWindow () {
    clearBackPreview();
    super.onDetachedFromWindow();
  }

  @Override
  protected void dispatchDraw (Canvas canvas) {
    if (backPreview == null || backPreviewParent == null || getWidth() == 0 || getHeight() == 0) {
      super.dispatchDraw(canvas);
      return;
    }

    // The app background stays still while the two controller surfaces move independently.
    canvas.drawColor(Theme.backgroundColor());
    long drawingTime = getDrawingTime();
    if (!backEnteringRect.isEmpty()) {
      int saveCount = saveBackSurface(canvas, backEnteringRect, 1f);
      canvas.drawColor(Theme.backgroundColor());
      backPaint.setColor(backHeaderColor);
      float top = backPreviewParent.getTop() + backPreview.getTop();
      canvas.drawRect(0, 0, getWidth(), top, backPaint);
      drawBackContent(canvas, false, drawingTime);
      if (backHeader != null && backHeader.getVisibility() == VISIBLE) {
        super.drawChild(canvas, backHeader, drawingTime);
      }
      if (backFloatingButton != null && backFloatingButton.getVisibility() == VISIBLE) {
        super.drawChild(canvas, backFloatingButton, drawingTime);
      }
      canvas.restoreToCount(saveCount);
    }

    // Match the system cross-activity scrim, using the app's theme. Keep it above the complete
    // destination surface and independent of the closing surface's much shorter commit fade.
    float maxDimAlpha = Theme.isDark() ? .8f : .2f;
    backPaint.setColor(Color.argb(Math.round(255f * maxDimAlpha * backDimFactor), 0, 0, 0));
    canvas.drawRect(0, 0, getWidth(), getHeight(), backPaint);

    if (backForegroundAlpha == 0f || backClosingRect.isEmpty()) {
      return;
    }

    int saveCount = saveBackSurface(canvas, backClosingRect, backForegroundAlpha);
    canvas.drawColor(Theme.backgroundColor());
    super.dispatchDraw(canvas);
    if (backHeaderSnapshot != null) {
      backHeaderSnapshot.draw(canvas);
    }
    if (backFloatingButtonSnapshot != null) {
      backFloatingButtonSnapshot.draw(canvas);
    }
    canvas.restoreToCount(saveCount);
  }

  @Override
  protected boolean drawChild (Canvas canvas, View child, long drawingTime) {
    if (backPreview != null) {
      if (child == backHeader || child == backFloatingButton) {
        return false;
      }
      if (child == backPreviewParent) {
        drawBackContent(canvas, true, drawingTime);
        return false;
      }
    }
    return super.drawChild(canvas, child, drawingTime);
  }

  @SuppressWarnings("deprecation")
  private int saveBackSurface (Canvas canvas, RectF bounds, float alpha) {
    // Separate layers keep hardware-backed descendants in their own surface. In particular,
    // neither elevated chrome nor a cached controller can draw over the destination scrim.
    int saveCount = canvas.saveLayerAlpha(0, 0, getWidth(), getHeight(),
      Math.round(255f * alpha), Canvas.ALL_SAVE_FLAG);
    // SurfaceControl rounds in surface coordinates before applying the scale.
    // Clip in screen coordinates with the same scaled device radii here.
    float scale = bounds.width() / getWidth();
    for (int i = 0; i < backCornerRadii.length; i++) {
      backScaledCornerRadii[i] = backCornerRadii[i] * scale;
    }
    backClip.rewind();
    backClip.addRoundRect(bounds, backScaledCornerRadii, Path.Direction.CW);
    canvas.clipPath(backClip);
    canvas.translate(bounds.left, bounds.top);
    canvas.scale(bounds.width() / getWidth(), bounds.height() / getHeight());
    return saveCount;
  }

  private void drawBackContent (Canvas canvas, boolean foreground, long drawingTime) {
    int saveCount = canvas.save();
    canvas.translate(backPreviewParent.getLeft() - getScrollX(),
      backPreviewParent.getTop() - getScrollY());
    canvas.concat(backPreviewParent.getMatrix());
    backPreviewParent.drawBackContent(canvas, backPreview, foreground, drawingTime);
    canvas.restoreToCount(saveCount);
  }

  /* optimization */

  private boolean preventLayout;
  private boolean layoutRequested;

  public void preventLayout () {
    preventLayout = true;
  }

  public void layoutIfRequested () {
    preventLayout = false;
    if (layoutRequested) {
      layoutRequested = false;
      requestLayout();
    }
  }

  public void cancelLayout () {
    preventLayout = false;
    layoutRequested = false;
  }

  public boolean isLayoutRequested () {
    return layoutRequested;
  }

  @Override
  public void requestLayout () {
    if (!preventLayout) {
      if (layoutLimit == -1) {
        super.requestLayout();
      } else if (layoutComplete < layoutLimit) {
        layoutComplete++;
        super.requestLayout();
      }
    } else {
      layoutRequested = true;
    }
  }
  
  private int layoutLimit = -1;
  private int layoutComplete;

  public void preventNextLayouts (int limit) {
    layoutLimit = limit;
    layoutComplete = 0;
  }

  public void completeNextLayout () {
    layoutLimit = -1;
    layoutComplete = 0;
  }

  /* optimization end */
}
