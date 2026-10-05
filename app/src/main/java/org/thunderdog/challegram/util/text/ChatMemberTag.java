package org.thunderdog.challegram.util.text;

import android.graphics.Canvas;
import android.graphics.RectF;

import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;

import me.vkryl.core.ColorUtils;

public final class ChatMemberTag {
  private ChatMemberTag () { }

  public static int width (Text text, boolean isAdministrator) {
    return text.getWidth() + (isAdministrator ? Screen.dp(12f) : 0);
  }

  public static int height (Text text, boolean isAdministrator) {
    return text.getHeight() + (isAdministrator ? Screen.dp(2f) : 0);
  }

  public static void draw (Canvas c, Text text, boolean isAdministrator, float x, float y,
                          TextColorSet colors) {
    if (isAdministrator) {
      RectF rect = Paints.getRectF();
      rect.set(x, y, x + width(text, true), y + height(text, true));
      c.drawRoundRect(rect, Screen.dp(4f), Screen.dp(4f),
        Paints.fillingPaint(ColorUtils.alphaColor(.15f, colors.defaultTextColor())));
      x += Screen.dp(6f);
      y += Screen.dp(1f);
    }
    text.draw(c, (int) x, (int) y, colors);
  }
}
