/*
 * This file is a part of Telegram X
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.thunderdog.challegram.data;

import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.chat.MessageView;
import org.thunderdog.challegram.component.chat.MessagesManager;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.emoji.Emoji;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.util.text.Text;
import org.thunderdog.challegram.util.text.TextColorSet;
import org.thunderdog.challegram.util.text.TextWrapper;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import me.vkryl.android.util.ClickHelper;

/** The account introduction card shown above the first incoming message, like Telegram. */
public final class TGMessageAccountInfo extends TGMessage {
  private TdApi.User user;
  private TdApi.AccountInfo accountInfo;
  private TextWrapper title, subtitle, footer;
  private final ArrayList<TextWrapper> labels = new ArrayList<>();
  private final ArrayList<TextWrapper> values = new ArrayList<>();
  private final RectF bounds = new RectF();
  private int cardWidth, cardHeight, labelsWidth, valuesWidth;
  private final Drawable infoIcon;
  private final TextColorSet colors = () -> useBubbles() ?
    getBubbleDateTextColor() : Theme.textAccentColor();

  private final ClickHelper clickHelper = new ClickHelper(new ClickHelper.Delegate() {
    @Override
    public boolean needClickAt (View view, float x, float y) {
      return bounds.contains(x, y);
    }

    @Override
    public void onClickAt (View view, float x, float y) {
      tdlib.ui().openChatProfile(messagesController(), getChatId(), null, null);
    }
  });

  public TGMessageAccountInfo (MessagesManager manager, long chatId, TdApi.User user,
                              TdApi.AccountInfo accountInfo) {
    super(manager, TD.newFakeMessage(chatId, new TdApi.MessageSenderUser(user.id),
      new TdApi.MessageText(new TdApi.FormattedText("", null), null, null)));
    this.user = user;
    this.accountInfo = accountInfo;
    this.infoIcon = Drawables.get(manager.controller().context().getResources(),
      R.drawable.baseline_info_16);
  }

  public void update (TdApi.User user, TdApi.AccountInfo accountInfo) {
    this.user = user;
    this.accountInfo = accountInfo;
    rebuildAndUpdateContent();
  }

  private TextWrapper text (String value, boolean bold, boolean centered) {
    TextWrapper wrapper = new TextWrapper(value, getTextStyleProvider(), colors);
    wrapper.setViewProvider(currentViews);
    if (bold) {
      wrapper.addTextFlags(Text.FLAG_ALL_BOLD);
    }
    if (centered) {
      wrapper.addTextFlags(Text.FLAG_ALIGN_CENTER);
    }
    return wrapper;
  }

  private void clearText () {
    if (title != null) title.performDestroy();
    if (subtitle != null) subtitle.performDestroy();
    if (footer != null) footer.performDestroy();
    title = subtitle = null;
    for (TextWrapper label : labels) label.performDestroy();
    for (TextWrapper value : values) value.performDestroy();
    labels.clear();
    values.clear();
    footer = null;
  }

  private void addRow (int labelRes, String value) {
    TextWrapper label = text(Lang.getString(labelRes), false, false);
    TextWrapper content = text(value, true, false);
    (Lang.rtl() ? content : label).addTextFlags(Text.FLAG_ALIGN_RIGHT);
    labels.add(label);
    values.add(content);
  }

  @Override
  protected void buildContent (int maxWidth) {
    clearText();
    title = text(TD.getUserName(user), true, true);
    subtitle = text(Lang.getString(user.isContact ?
      R.string.ContactInfoIsContact : R.string.ContactInfoIsNotContact), false, true);
    String countryCode = ChatAccountInfo.phoneCountry(accountInfo);
    if (!countryCode.isEmpty()) {
      String country;
      if ("FT".equals(countryCode)) {
        country = Lang.getString(R.string.ContactInfoPhoneFragment);
      } else {
        String flag = Emoji.getEmojiFlagFromCountry(countryCode);
        country = (flag != null ? flag + " " : "") +
          new Locale("", countryCode).getDisplayCountry(Lang.locale());
      }
      addRow(R.string.ContactInfoPhone, country);
    }
    if (ChatAccountInfo.hasRegistrationYear(accountInfo)) {
      String registration;
      if (ChatAccountInfo.hasRegistrationMonth(accountInfo)) {
        Calendar date = Calendar.getInstance();
        date.clear();
        date.set(accountInfo.registrationYear, accountInfo.registrationMonth - 1, 1);
        registration = Lang.monthYearFull(date.getTimeInMillis(), TimeUnit.MILLISECONDS);
      } else {
        registration = Integer.toString(accountInfo.registrationYear);
      }
      addRow(R.string.ContactInfoRegistration, registration);
    }
    if (ChatAccountInfo.showUnverifiedNotice(user)) {
      footer = text(Lang.getString(R.string.ContactInfoNotVerified), false, true);
    }

    int padding = Screen.dp(16);
    int gap = Screen.dp(8);
    int available = Math.max(1, width - Screen.dp(24) - padding * 2);
    title.prepare(available);
    subtitle.prepare(available);
    labelsWidth = valuesWidth = 0;
    for (int i = 0; i < labels.size(); i++) {
      labels.get(i).prepare(Math.max(1, (available - gap) / 2));
      values.get(i).prepare(Math.max(1, (available - gap) / 2));
      labelsWidth = Math.max(labelsWidth, labels.get(i).getWidth());
      valuesWidth = Math.max(valuesWidth, values.get(i).getWidth());
    }
    cardWidth = Math.max(title.getWidth(), subtitle.getWidth());
    cardWidth = Math.max(cardWidth, labelsWidth + gap + valuesWidth);
    if (footer != null) {
      footer.prepare(Math.max(1, available - Screen.dp(22)));
      cardWidth = Math.max(cardWidth, footer.getWidth() + Screen.dp(22));
    }
    cardWidth += padding * 2;
    cardHeight = padding * 2 + title.getHeight() + Screen.dp(3) + subtitle.getHeight();
    cardHeight += Screen.dp(14);
    for (int i = 0; i < labels.size(); i++) {
      if (i > 0) cardHeight += Screen.dp(5);
      cardHeight += Math.max(labels.get(i).getHeight(), values.get(i).getHeight());
    }
    if (footer != null) cardHeight += Screen.dp(16) + footer.getHeight();
  }

  @Override
  protected void drawContent (MessageView view, Canvas c, int startX, int startY, int maxWidth) {
    int left = (width - cardWidth) / 2;
    int top = getContentY();
    bounds.set(left, top, left + cardWidth, top + cardHeight);
    c.drawRoundRect(bounds, Screen.dp(16), Screen.dp(16), Paints.fillingPaint(useBubbles() ?
      getBubbleDateBackgroundColor() : Theme.fillingColor()));
    int y = top + Screen.dp(16);
    drawCentered(c, title, y, 1f);
    y += title.getHeight() + Screen.dp(3);
    drawCentered(c, subtitle, y, .7f);
    y += subtitle.getHeight() + Screen.dp(14);
    int rowsLeft = (width - labelsWidth - Screen.dp(8) - valuesWidth) / 2;
    for (int i = 0; i < labels.size(); i++) {
      TextWrapper label = labels.get(i), value = values.get(i);
      int labelX = Lang.rtl() ? rowsLeft + valuesWidth + Screen.dp(8) :
        rowsLeft + labelsWidth - label.getWidth();
      int valueX = Lang.rtl() ? rowsLeft + valuesWidth - value.getWidth() :
        rowsLeft + labelsWidth + Screen.dp(8);
      label.draw(c, labelX, labelX + label.getWidth(), 0, y, colors, .7f);
      value.draw(c, valueX, valueX + value.getWidth(), 0, y, colors, 1f);
      y += Math.max(label.getHeight(), value.getHeight()) + Screen.dp(5);
    }
    if (footer != null) {
      y += Screen.dp(11);
      int footerLeft = (width - footer.getWidth() - Screen.dp(22)) / 2;
      int iconX = Lang.rtl() ? footerLeft + footer.getWidth() + Screen.dp(6) : footerLeft;
      int footerX = Lang.rtl() ? footerLeft : footerLeft + Screen.dp(22);
      Drawables.draw(c, infoIcon, iconX, y + Screen.dp(2),
        Paints.getPorterDuffPaint(colors.defaultTextColor()));
      footer.draw(c, footerX -
        (footer.getCurrent().getMaxWidth() - footer.getWidth()) / 2, y, colors, .7f);
    }
  }

  private void drawCentered (Canvas c, TextWrapper text, int y, float alpha) {
    text.draw(c, (width - text.getCurrent().getMaxWidth()) / 2, y, colors, alpha);
  }

  @Override
  public boolean onTouchEvent (MessageView view, MotionEvent event) {
    return clickHelper.onTouchEvent(view, event);
  }

  @Override
  public boolean performLongPress (View view, float x, float y) {
    clickHelper.cancel(view, x, y);
    return false;
  }

  @Override
  protected void onMessageContainerDestroyed () {
    clearText();
  }

  @Override
  protected int getContentWidth () { return cardWidth; }

  @Override
  protected int getContentHeight () { return cardHeight; }

  @Override
  protected boolean headerDisabled () { return true; }

  @Override
  protected boolean disableBubble () { return true; }

  @Override
  public boolean isFakeMessage () { return true; }

  @Override
  public boolean canSwipe () { return false; }

  @Override
  public boolean canBeSelected () { return false; }

  @Override
  public boolean canMarkAsViewed () { return false; }

  @Override
  public boolean needRefreshViewCount () { return false; }
}
