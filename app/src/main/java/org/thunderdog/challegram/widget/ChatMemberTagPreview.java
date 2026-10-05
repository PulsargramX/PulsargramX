package org.thunderdog.challegram.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.ViewGroup;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.loader.AvatarReceiver;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.util.text.ChatMemberTag;
import org.thunderdog.challegram.util.text.Text;
import org.thunderdog.challegram.util.text.TextColorSet;
import org.thunderdog.challegram.util.text.TextColorSets;

import me.vkryl.core.ColorUtils;
import me.vkryl.core.StringUtils;
import me.vkryl.core.lambda.Destroyable;

public final class ChatMemberTagPreview extends BaseView implements AttachDelegate, Destroyable {
  private final AvatarReceiver avatar;
  private TdApi.ChatMember member;
  private String tag;

  public ChatMemberTagPreview (Context context, Tdlib tdlib) {
    super(context, tdlib);
    avatar = new AvatarReceiver(this);
    setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Screen.dp(112f)));
  }

  public void setMember (TdApi.ChatMember member, String tag) {
    this.member = member;
    this.tag = tag;
    avatar.requestMessageSender(tdlib, member.memberId, AvatarReceiver.Options.NONE);
    invalidate();
  }

  @Override
  protected void onDraw (Canvas c) {
    if (member == null) {
      return;
    }
    c.drawColor(Theme.getColor(ColorId.chatBackground));
    boolean rtl = Lang.rtl();
    int left = rtl ? Screen.dp(16f) : Screen.dp(64f);
    int right = getWidth() - (rtl ? Screen.dp(64f) : Screen.dp(16f));
    int avatarLeft = rtl ? getWidth() - Screen.dp(54f) : Screen.dp(12f);
    avatar.setBounds(avatarLeft, Screen.dp(58f), avatarLeft + Screen.dp(42f), Screen.dp(100f));
    if (avatar.needPlaceholder()) {
      avatar.drawPlaceholder(c);
    }
    avatar.draw(c);
    RectF rect = Paints.getRectF();
    rect.set(left, Screen.dp(12f), right, Screen.dp(100f));
    c.drawRoundRect(rect, Screen.dp(12f), Screen.dp(12f),
      Paints.fillingPaint(Theme.getColor(ColorId.bubbleIn_background)));

    int contentLeft = left + Screen.dp(12f);
    int contentRight = right - Screen.dp(12f);
    int maxWidth = Math.max(1, contentRight - contentLeft);
    boolean admin = TD.isAdmin(member.status);
    String displayTag = !StringUtils.isEmpty(tag) ? tag : admin ?
      Lang.getString(TD.isCreator(member.status) ? R.string.message_ownerSign : R.string.message_adminSignPlain) : null;
    TextColorSet nameColors = () -> tdlib.senderAccentColor(member.memberId).getNameColor();
    if (displayTag != null) {
      Text label = new Text.Builder(displayTag, Math.max(1, maxWidth / 2 - Screen.dp(12f)),
        Paints.robotoStyleProvider(11f), TextColorSets.BubbleIn.LIGHT).allBold(admin).singleLine().build();
      int tagWidth = ChatMemberTag.width(label, admin);
      ChatMemberTag.draw(c, label, admin, contentRight - tagWidth, Screen.dp(22f),
        admin ? nameColors : TextColorSets.BubbleIn.LIGHT);
      maxWidth -= tagWidth + Screen.dp(8f);
    }
    Text name = new Text.Builder(tdlib.senderName(member.memberId), Math.max(1, maxWidth),
      Paints.robotoStyleProvider(15f), nameColors).allBold().singleLine().build();
    name.draw(c, contentLeft, Screen.dp(20f));
    int color = ColorUtils.alphaColor(.12f, Theme.getColor(ColorId.bubbleIn_text));
    rect.set(contentLeft, Screen.dp(52f), contentLeft + (contentRight - contentLeft) * .8f, Screen.dp(59f));
    c.drawRoundRect(rect, Screen.dp(3f), Screen.dp(3f), Paints.fillingPaint(color));
    rect.set(contentLeft, Screen.dp(72f), contentLeft + (contentRight - contentLeft) * .6f, Screen.dp(79f));
    c.drawRoundRect(rect, Screen.dp(3f), Screen.dp(3f), Paints.fillingPaint(color));
  }

  @Override
  public void attach () {
    avatar.attach();
  }

  @Override
  public void detach () {
    avatar.detach();
  }

  @Override
  public void performDestroy () {
    avatar.destroy();
  }
}
