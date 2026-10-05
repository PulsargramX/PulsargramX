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
 */
package org.thunderdog.challegram.component.chat;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;

import java.util.ArrayList;
import java.util.Collections;

public final class MessageTextSelectionOverlay extends FrameLayout {
  private final MessageTextSelectionManager manager;
  private final SelectionHandleView startHandle;
  private final SelectionHandleView endHandle;
  private final LinearLayout legacyActions;
  private final TextView copyAction;
  private final TextView quoteAction;
  private final TextView selectAllAction;
  private final Rect viewport = new Rect();
  private final ArrayList<Rect> gestureExclusion = new ArrayList<>(2);
  private final Rect startGestureExclusion = new Rect();
  private final Rect endGestureExclusion = new Rect();

  public MessageTextSelectionOverlay (
    @NonNull Context context,
    @NonNull MessageTextSelectionManager manager
  ) {
    super(context);
    this.manager = manager;
    setWillNotDraw(false);
    setClipChildren(false);
    setClipToPadding(false);

    startHandle = newHandleView(R.string.StartSelectionHandle,
      MessageTextSelectionManager.HANDLE_START);
    endHandle = newHandleView(R.string.EndSelectionHandle,
      MessageTextSelectionManager.HANDLE_END);
    addView(startHandle, new LayoutParams(Screen.dp(48f), Screen.dp(48f)));
    addView(endHandle, new LayoutParams(Screen.dp(48f), Screen.dp(48f)));

    legacyActions = new LinearLayout(context);
    legacyActions.setOrientation(LinearLayout.HORIZONTAL);
    legacyActions.setBackgroundColor(Theme.getColor(ColorId.filling));
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      legacyActions.setElevation(Screen.dp(6f));
    }
    copyAction = newAction(R.string.Copy, MessageTextSelectionManager.ACTION_COPY);
    quoteAction = newAction(
      R.string.TextFormatQuote,
      MessageTextSelectionManager.ACTION_QUOTE
    );
    selectAllAction = newAction(
      R.string.SelectAll,
      MessageTextSelectionManager.ACTION_SELECT_ALL
    );
    legacyActions.addView(copyAction);
    legacyActions.addView(quoteAction);
    legacyActions.addView(selectAllAction);
    legacyActions.setVisibility(GONE);
    addView(legacyActions, new LayoutParams(
      LayoutParams.WRAP_CONTENT,
      Screen.dp(48f)
    ));
    setActive(false);
  }

  private SelectionHandleView newHandleView (int descriptionRes, int handle) {
    SelectionHandleView view = new SelectionHandleView(getContext(), handle);
    view.setContentDescription(Lang.getString(descriptionRes));
    view.setFocusable(true);
    view.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    view.setOnTouchListener((v, event) -> {
      boolean handled = manager.onHandleTouch(
        handle,
        event,
        v.getX() + event.getX(),
        v.getY() + event.getY()
      );
      if (handled && event.getActionMasked() == MotionEvent.ACTION_UP) {
        v.performClick();
      }
      return handled;
    });
    return view;
  }

  private final class SelectionHandleView extends View {
    private final int handle;
    private final Drawable leftDrawable;
    private final Drawable rightDrawable;
    private Drawable drawable;
    private boolean rtl;

    SelectionHandleView (@NonNull Context context, int handle) {
      super(context);
      this.handle = handle;
      TypedArray handles = context.obtainStyledAttributes(
        null,
        new int[] {
          android.R.attr.textSelectHandleLeft,
          android.R.attr.textSelectHandleRight
        },
        android.R.attr.editTextStyle,
        0
      );
      leftDrawable = handles.getDrawable(0);
      rightDrawable = handles.getDrawable(1);
      handles.recycle();
      if (leftDrawable != null) {
        leftDrawable.setCallback(this);
        leftDrawable.setState(getDrawableState());
      }
      if (rightDrawable != null) {
        rightDrawable.setCallback(this);
        rightDrawable.setState(getDrawableState());
      }
      updateDirection(false);
    }

    @Override
    protected boolean verifyDrawable (@NonNull Drawable who) {
      return who == leftDrawable || who == rightDrawable || super.verifyDrawable(who);
    }

    void updateDirection (boolean rtl) {
      if (this.rtl != rtl || drawable == null) {
        this.rtl = rtl;
        boolean start = handle == MessageTextSelectionManager.HANDLE_START;
        drawable = rtl ?
          (start ? rightDrawable : leftDrawable) :
          (start ? leftDrawable : rightDrawable);
        invalidate();
      }
    }

    @Override
    protected void onDraw (@NonNull Canvas canvas) {
      super.onDraw(canvas);
      if (drawable == null) {
        drawFallbackHandle(canvas);
        return;
      }
      int intrinsicWidth = Math.max(1, drawable.getIntrinsicWidth());
      int intrinsicHeight = Math.max(1, drawable.getIntrinsicHeight());
      float scale = Math.min(1f, Math.min(
        getWidth() / (float) intrinsicWidth,
        getHeight() / (float) intrinsicHeight
      ));
      int width = Math.max(1, Math.round(intrinsicWidth * scale));
      int height = Math.max(1, Math.round(intrinsicHeight * scale));
      int hotspotX = getHotspotX(width);
      int left = getWidth() / 2 - hotspotX;
      drawable.setBounds(left, 0, left + width, height);
      drawable.draw(canvas);
    }

    private int getHotspotX (int width) {
      boolean start = handle == MessageTextSelectionManager.HANDLE_START;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        return rtl == start ? width / 4 : width * 3 / 4;
      }
      return start ? (rtl ? 0 : width) : (rtl ? width : 0);
    }

    private void drawFallbackHandle (@NonNull Canvas canvas) {
      Paint paint = Paints.fillingPaint(manager.getHandleColor());
      float radius = Screen.dp(10f);
      float direction = rtl ? -1f : 1f;
      if (handle == MessageTextSelectionManager.HANDLE_END) {
        direction = -direction;
      }
      float x = getWidth() / 2f;
      canvas.drawRect(
        x - Screen.dp(1f),
        0,
        x + Screen.dp(1f),
        radius,
        paint
      );
      canvas.drawCircle(x + direction * radius * .3f, radius, radius, paint);
    }
  }

  private TextView newAction (int stringRes, int action) {
    TextView view = new TextView(getContext());
    view.setText(Lang.getString(stringRes));
    view.setTextColor(Theme.getColor(ColorId.text));
    view.setGravity(android.view.Gravity.CENTER);
    view.setMinWidth(Screen.dp(64f));
    view.setPadding(Screen.dp(12f), 0, Screen.dp(12f), 0);
    view.setOnClickListener(v -> manager.performAction(action));
    view.setContentDescription(Lang.getString(stringRes));
    return view;
  }

  public void setActive (boolean active) {
    setVisibility(active ? VISIBLE : GONE);
    startHandle.setVisibility(active ? VISIBLE : GONE);
    endHandle.setVisibility(active ? VISIBLE : GONE);
    if (!active) {
      legacyActions.setVisibility(GONE);
      clearGestureExclusion();
    }
    invalidate();
  }

  public void showLegacyActions (boolean show, boolean quote, boolean selectAll) {
    legacyActions.setVisibility(show ? VISIBLE : GONE);
    quoteAction.setVisibility(quote ? VISIBLE : GONE);
    selectAllAction.setVisibility(selectAll ? VISIBLE : GONE);
    requestLayout();
    invalidate();
  }

  @Override
  protected void onDraw (@NonNull Canvas canvas) {
    super.onDraw(canvas);
    if (!manager.prepareOverlayGeometry(this, viewport)) {
      startHandle.setVisibility(INVISIBLE);
      endHandle.setVisibility(INVISIBLE);
      clearGestureExclusion();
      return;
    }
    positionHandle(
      startHandle,
      manager.getStartHandleX(),
      manager.getStartHandleY(),
      manager.isStartHandleRtl()
    );
    positionHandle(
      endHandle,
      manager.getEndHandleX(),
      manager.getEndHandleY(),
      manager.isEndHandleRtl()
    );
    positionLegacyActions();
    updateGestureExclusion();
    postInvalidateOnAnimation();
  }

  private void positionHandle (SelectionHandleView handle, float x, float y, boolean rtl) {
    handle.updateDirection(rtl);
    float half = Screen.dp(24f);
    handle.setX(x - half);
    handle.setY(y);
    boolean visible = x >= viewport.left && x <= viewport.right &&
      y >= viewport.top && y <= viewport.bottom;
    handle.setVisibility(visible ? VISIBLE : INVISIBLE);
  }

  private void positionLegacyActions () {
    if (legacyActions.getVisibility() != VISIBLE) {
      return;
    }
    int width = legacyActions.getMeasuredWidth();
    int height = legacyActions.getMeasuredHeight();
    float centerX = manager.getActionAnchorX();
    float above = manager.getActionAnchorTop() - height - Screen.dp(8f);
    float y = above >= viewport.top ?
      above : manager.getActionAnchorBottom() + Screen.dp(8f);
    legacyActions.setX(Math.max(
      viewport.left,
      Math.min(viewport.right - width, centerX - width / 2f)
    ));
    legacyActions.setY(Math.max(
      viewport.top,
      Math.min(viewport.bottom - height, y)
    ));
  }

  private void updateGestureExclusion () {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
      return;
    }
    gestureExclusion.clear();
    addGestureRect(startHandle, startGestureExclusion);
    addGestureRect(endHandle, endGestureExclusion);
    setSystemGestureExclusionRects(gestureExclusion);
  }

  private void addGestureRect (View handle, Rect rect) {
    if (handle.getVisibility() != VISIBLE) {
      return;
    }
    rect.set(
      Math.round(handle.getX()),
      Math.round(handle.getY()),
      Math.round(handle.getX() + handle.getWidth()),
      Math.round(handle.getY() + handle.getHeight())
    );
    gestureExclusion.add(rect);
  }

  private void clearGestureExclusion () {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      setSystemGestureExclusionRects(Collections.emptyList());
    }
  }

  @Override
  protected void onLayout (boolean changed, int left, int top, int right, int bottom) {
    super.onLayout(changed, left, top, right, bottom);
    positionLegacyActions();
  }

  @Override
  public boolean onTouchEvent (MotionEvent event) {
    return manager.onOverlayTouch(event);
  }

  @Override
  public boolean performClick () {
    super.performClick();
    return true;
  }
}
