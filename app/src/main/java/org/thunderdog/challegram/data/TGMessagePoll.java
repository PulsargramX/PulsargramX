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
 * File created on 26/02/2019
 */
package org.thunderdog.challegram.data;

import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.U;
import org.thunderdog.challegram.component.attach.PollMediaPicker;
import org.thunderdog.challegram.component.chat.MessageView;
import org.thunderdog.challegram.component.chat.MessagesManager;
import org.thunderdog.challegram.config.Config;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.loader.AvatarReceiver;
import org.thunderdog.challegram.loader.ComplexReceiver;
import org.thunderdog.challegram.loader.Receiver;
import org.thunderdog.challegram.mediaview.MediaViewController;
import org.thunderdog.challegram.mediaview.data.PollMediaGallery;
import org.thunderdog.challegram.navigation.TooltipOverlayView;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibUi;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Strings;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.ui.PollResultsController;
import org.thunderdog.challegram.util.text.Text;
import org.thunderdog.challegram.util.text.TextWrapper;
import org.thunderdog.challegram.widget.ProgressComponent;
import org.thunderdog.challegram.widget.SimplestCheckBox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.android.animator.FactorAnimator;
import me.vkryl.android.animator.ListAnimator;
import me.vkryl.android.animator.ReplaceAnimator;
import me.vkryl.android.util.ClickHelper;
import me.vkryl.core.ArrayUtils;
import me.vkryl.core.ColorUtils;
import me.vkryl.core.MathUtils;
import me.vkryl.core.collection.IntList;
import me.vkryl.core.lambda.Destroyable;
import tgx.td.Td;

public class TGMessagePoll extends TGMessage implements ClickHelper.Delegate, ComplexReceiver.KeyFilter, TooltipOverlayView.VisibilityListener {
  private static int ftoi (float f) { // Utility method to change conversion in all places, if needed
    return (int) f;
  }

  private static int fromTo (int from, int to, float factor) {
    return from + ftoi((float) (to - from) * factor);
  }

  private static float fromTo (float from, float to, float factor) {
    return from + (to - from) * factor;
  }

  private static class PollState {
    private final TdApi.Poll poll;
    private final PollOption[] options;
    private final float resultsVisibility, timerVisibility, hintVisibility;
    private final boolean resultsVisible, timerVisible, hintVisible;
    private final int isAnsweredCorrectly;

    public PollState (Tdlib tdlib, TdApi.Poll poll, boolean resultsVisible) {
      this.poll = poll;
      this.resultsVisible = resultsVisible;
      this.resultsVisibility = resultsVisible ? 1f : 0f;
      this.timerVisible = !poll.isClosed && (poll.openPeriod != 0 || poll.closeDate != 0);
      this.timerVisibility = timerVisible ? 1f : 0f;
      this.hintVisible = poll.type.getConstructor() == TdApi.PollTypeQuiz.CONSTRUCTOR &&
        (!Td.isEmpty(((TdApi.PollTypeQuiz) poll.type).explanation) ||
          ((TdApi.PollTypeQuiz) poll.type).explanationMedia != null);
      this.hintVisibility = hintVisible ? 1f : 0f;
      this.options = new PollOption[poll.options.length];
      for (int i = 0; i < poll.options.length; i++) {
        this.options[i] = new PollOption(poll.options[i], voteRatio(i), poll.options[i].isBeingChosen ? 1f : 0f);
      }
      this.isAnsweredCorrectly = TD.isAnsweredCorrectly(poll);
    }

    public PollState (Tdlib tdlib, PollState fromState, PollState toState, float factor) {
      if (fromState.options.length != toState.options.length)
        throw new AssertionError(fromState.options.length + " != " + toState.options.length);
      this.resultsVisibility = fromTo(fromState.resultsVisibility, toState.resultsVisibility, factor);
      this.resultsVisible = resultsVisibility > 0f;
      this.timerVisibility = fromTo(fromState.timerVisibility, toState.timerVisibility, factor);
      this.timerVisible = timerVisibility > 0f;
      this.hintVisibility = fromTo(fromState.hintVisibility, toState.hintVisibility, factor);
      this.hintVisible = hintVisibility > 0f;
      this.isAnsweredCorrectly = TD.isAnsweredCorrectly(toState.poll);
      this.options = new PollOption[toState.options.length];
      TdApi.PollOption[] options = new TdApi.PollOption[toState.options.length];
      for (int i = 0; i < options.length; i++) {
        TdApi.PollOption fromOption = fromState.poll.options[i];
        TdApi.PollOption toOption = toState.poll.options[i];
        int voterCount = fromTo(fromOption.voterCount, toOption.voterCount, factor);
        int votePercentage = fromTo(fromOption.votePercentage, toOption.votePercentage, factor);
        TdApi.PollOption option = new TdApi.PollOption(
          toOption.id,
          toOption.text,
          toOption.media,
          voterCount,
          votePercentage,
          toOption.recentVoterIds,
          toOption.isChosen,
          toOption.isBeingChosen,
          toOption.author,
          toOption.additionDate
        );
        options[i] = option;
        this.options[i] = new PollOption(
          option,
          fromTo(fromState.options[i].ratio, toState.options[i].ratio, factor),
          fromTo(fromState.options[i].progress, toState.options[i].progress, factor)
        );
      }
      this.poll = new TdApi.Poll(
        toState.poll.id,
        toState.poll.question,
        options,
        toState.poll.totalVoterCount,
        toState.poll.recentVoterIds,
        toState.poll.canGetVoters,
        toState.poll.canSeeResults,
        toState.poll.isAnonymous,
        toState.poll.allowsMultipleAnswers,
        toState.poll.allowsRevoting,
        toState.poll.membersOnly,
        toState.poll.countryCodes,
        toState.poll.optionOrder,
        toState.poll.type,
        toState.poll.openPeriod,
        toState.poll.closeDate,
        toState.poll.isClosed,
        toState.poll.voteRestrictionReason
      );
    }

    public int size () {
      return poll.options.length;
    }

    public float voteRatio (int optionId) {
      return poll.options[optionId].votePercentage / 100f;
    }

    public int votePercentage (int optionId) {
      return poll.options[optionId].votePercentage;
    }

    public int voterCount (int optionId) {
      return poll.options[optionId].voterCount;
    }
  }

  private static class PollOption {
    public TdApi.PollOption option;
    public final float ratio;
    public final float progress;

    public PollOption (TdApi.PollOption option, float ratio, float progress) {
      this.option = option;
      this.ratio = ratio;
      this.progress = progress;
    }
  }

  // State

  @NonNull
  private PollState state;
  @Nullable
  private PollState futureState;

  // Texts

  public static class OptionEntry implements Destroyable {
    private int percentage = -1;
    private String percentageStr;
    private int percentageStrWidth;
    private String voterCountStr;
    private int voterCountStrWidth;
    private float selectionFactor;
    private SimplestCheckBox checkBox;
    private TdApi.FormattedText textSource;
    @Nullable
    private TextWrapper text;
    private ProgressComponent progress;
    private BoolAnimator isSelected;

    public float getSelectionFactor () {
      return Math.max(selectionFactor, isSelected != null ? isSelected.getFloatValue() : 0f);
    }

    public boolean isSelected () {
      return isSelected != null && isSelected.getValue();
    }

    public float getMoveFactor (float resultsVisibility) {
      if (isSelected != null) {
        return (1f - this.selectionFactor * resultsVisibility);
      } else {
        return 1f - resultsVisibility;
      }
    }

    @Override
    public void performDestroy () {
      if (progress != null) {
        progress.performDestroy();
        progress = null;
      }
      if (checkBox != null) {
        checkBox.destroy();
        checkBox = null;
      }
    }
  }

  @Override
  protected void onMessageContainerDestroyed() {
    highlightGeneration++;
    if (explanationPopup != null) {
      explanationPopup.removeListener(this);
      explanationPopup.hideNow();
      explanationPopup = null;
    }
    if (timerProgress != null) {
      timerProgress.performDestroy();
    }
    if (commonProgress != null) {
      commonProgress.performDestroy();
    }
    if (questionText != null) {
      questionText.performDestroy();
    }
    if (descriptionText != null) {
      descriptionText.performDestroy();
    }
    if (explanationText != null) {
      explanationText.performDestroy();
    }
    if (pollMedia != null) {
      pollMedia.destroy();
    }
    if (explanationMedia != null) {
      explanationMedia.destroy();
    }
    destroyAttachments(optionMedia);
    if (options != null) {
      for (OptionEntry entry : options) {
        if (entry.text != null) {
          entry.text.performDestroy();
        }
        entry.performDestroy();
      }
    }
  }

  private TdApi.MessagePoll messagePoll;
  private TextWrapper descriptionText;
  private TdApi.FormattedText descriptionSource;
  private TextWrapper explanationText;
  private PollMediaAttachment pollMedia, explanationMedia;
  private PollMediaAttachment[] optionMedia;
  private boolean previewResults, explanationExpanded;

  boolean openMedia (PollMediaAttachment attachment) {
    int index = -1;
    if (attachment == pollMedia) {
      index = PollMediaGallery.DESCRIPTION;
    } else if (attachment == explanationMedia) {
      index = PollMediaGallery.EXPLANATION;
    } else {
      for (int i = 0; i < optionMedia.length; i++) {
        if (attachment == optionMedia[i]) {
          index = i;
          break;
        }
      }
    }
    return index != -1 && MediaViewController.openFromPoll(controller(), msg, this, index);
  }
  private boolean attachmentsAttached;
  private SenderEntry[][] optionVoters;

  private void setMessagePoll (TdApi.MessagePoll content) {
    TdApi.MessagePoll previous = messagePoll;
    if (!PollResultPolicy.canPreview(content.poll.canSeeResults,
        TD.hasAnswer(content.poll), content.poll.isClosed)) {
      previewResults = false;
    }
    boolean changedIds = previous != null && !sameOptionIds(previous.poll, content.poll);
    if (changedIds) {
      translatedTexts = null;
      highlightGeneration++;
      highlightOptionId = HIGHLIGHT_NONE;
    }
    if (changedIds && options != null) {
      for (OptionEntry entry : options) {
        if (entry.text != null) {
          entry.text.performDestroy();
        }
        entry.performDestroy();
      }
      options = null;
    }
    pollMedia = replaceMedia(pollMedia, content.media);
    PollMediaAttachment[] previousMedia = optionMedia;
    optionMedia = new PollMediaAttachment[content.poll.options.length];
    for (int i = 0; i < optionMedia.length; i++) {
      int previousIndex = -1;
      if (previous != null) {
        for (int j = 0; j < previous.poll.options.length; j++) {
          if (content.poll.options[i].id.equals(previous.poll.options[j].id)) {
            previousIndex = j;
            break;
          }
        }
      }
      PollMediaAttachment attachment = previousIndex >= 0 && previousMedia != null ?
        previousMedia[previousIndex] : null;
      if (previousIndex >= 0 && previousMedia != null) {
        previousMedia[previousIndex] = null;
      }
      optionMedia[i] = replaceMedia(attachment, content.poll.options[i].media);
    }
    destroyAttachments(previousMedia);
    messagePoll = content;
    optionVoters = new SenderEntry[content.poll.options.length][];
    for (int i = 0; i < optionVoters.length; i++) {
      TdApi.MessageSender[] senders = content.poll.options[i].recentVoterIds;
      if (PollResultPolicy.canGetVoters(content.poll.isAnonymous, content.poll.canSeeResults,
          content.poll.canGetVoters) && senders != null) {
        optionVoters[i] = new SenderEntry[Math.min(3, senders.length)];
        for (int j = 0; j < optionVoters[i].length; j++) {
          optionVoters[i][j] = new SenderEntry(tdlib, senders[j]);
        }
      }
    }
    TdApi.PollTypeQuiz quiz = content.poll.type instanceof TdApi.PollTypeQuiz ?
      (TdApi.PollTypeQuiz) content.poll.type : null;
    explanationMedia = replaceMedia(explanationMedia, quiz != null ? quiz.explanationMedia : null);
    if (explanationText != null) {
      explanationText.performDestroy();
    }
    explanationText = quiz != null && !Td.isEmpty(quiz.explanation) ?
      new TextWrapper(tdlib, quiz.explanation, getTextStyleProvider(), getTextColorSet(),
        openParameters(), (wrapper, text, media) -> invalidateTextMediaReceiver(text, media))
        .setViewProvider(currentViews) : null;
    updateDescription();
  }

  private static void destroyAttachments (PollMediaAttachment[] attachments) {
    if (attachments != null) {
      for (PollMediaAttachment attachment : attachments) {
        if (attachment != null) {
          attachment.destroy();
        }
      }
    }
  }

  private PollMediaAttachment replaceMedia (PollMediaAttachment old, TdApi.PollMedia media) {
    if (old != null && PollMediaAttachment.sameMedia(old.media, media)) {
      return old;
    }
    if (old != null) {
      old.destroy();
    }
    return media != null ? new PollMediaAttachment(this, media) : null;
  }

  private static boolean sameOptionIds (TdApi.Poll first, TdApi.Poll second) {
    if (first.options.length != second.options.length ||
        !Arrays.equals(first.optionOrder, second.optionOrder)) {
      return false;
    }
    for (int i = 0; i < first.options.length; i++) {
      if (!first.options[i].id.equals(second.options[i].id)) {
        return false;
      }
    }
    return true;
  }

  private void updateDescription () {
    TdApi.FormattedText text = translatedTexts != null &&
      translatedTexts.length > state.options.length + 1 ?
      Td.trim(translatedTexts[translatedTexts.length - 1]) : messagePoll.description;
    if (descriptionText != null && Td.equalsTo(descriptionSource, text)) {
      return;
    }
    if (descriptionText != null) {
      descriptionText.performDestroy();
    }
    descriptionSource = text;
    descriptionText = !Td.isEmpty(text) ?
      new TextWrapper(tdlib, text, getTextStyleProvider(), getTextColorSet(), openParameters(),
        (wrapper, value, media) -> invalidateTextMediaReceiver(value, media))
        .setViewProvider(currentViews) : null;
  }

  private int attachmentsHeight () {
    int height = 0;
    if (explanationExpanded) {
      height += explanationText != null ? explanationText.getHeight() + Screen.dp(8f) : 0;
      height += explanationMedia != null ? explanationMedia.height() : 0;
    }
    height += canPreviewResults() ? Screen.dp(36f) : 0;
    height += canAddOption() ? Screen.dp(36f) : 0;
    return height;
  }

  @Nullable
  public RectF getOptionBounds (String id) {
    if (options == null) {
      return null;
    }
    int y = getContentY() + getQuestionTitleHeight() + Screen.dp(18f);
    for (int i = 0; i < options.length; i++) {
      int index = findOptionId(i, displayOrder);
      int height = getOptionHeight(options[index].text);
      if (getPoll().options[index].id.equals(id)) {
        return new RectF(getContentX(), y, getContentX() + getContentWidth(), y + height);
      }
      y += height;
    }
    return null;
  }

  private boolean canAddOption () {
    return messagePoll.canAddOption && !getPoll().isClosed && !isNotSent() && !isScheduled();
  }

  private boolean canPreviewResults () {
    return PollResultPolicy.canPreview(
      getPoll().canSeeResults, TD.hasAnswer(getPoll()), getPoll().isClosed
    );
  }

  private int highlightGeneration;

  public void highlightOption (String optionId) {
    for (int i = 0; i < getPoll().options.length; i++) {
      if (getPoll().options[i].id.equals(optionId)) {
        highlightOptionId = i;
        final int generation = ++highlightGeneration;
        invalidate();
        runOnUiThread(() -> {
          if (!isDestroyed() && highlightGeneration == generation) {
            highlightOptionId = HIGHLIGHT_NONE;
            invalidate();
          }
        }, 1800);
        return;
      }
    }
  }

  @Override
  protected void onMessageAttachedToView (@NonNull MessageView view, boolean attached) {
    attachmentsAttached = attached || currentViews.hasAnyTargetToInvalidate();
    if (pollMedia != null) {
      pollMedia.attach(attachmentsAttached);
    }
    if (explanationMedia != null) {
      explanationMedia.attach(attachmentsAttached);
    }
    if (optionMedia != null) {
      for (PollMediaAttachment attachment : optionMedia) {
        if (attachment != null) {
          attachment.attach(attachmentsAttached);
        }
      }
    }
  }

  private OptionEntry[] options;
  private int[] displayOrder;
  private ProgressComponent commonProgress;
  private ProgressComponent timerProgress;

  private static final int POLL_STATUS_ANONYMOUS = 1;
  private static final int POLL_STATUS_CLOSED = 2;
  private int pollStatus;
  private Text pollStatusText;

  private int totalVoterCount = -1;
  private String totalVoterCountStr;
  private int totalVoterCountStrWidth;
  private final BoolAnimator isButtonActive;
  private final ReplaceAnimator<Button> button;

  private TdApi.FormattedText questionTextSource;
  private @Nullable TextWrapper questionText;

  // Animation

  private static final int ANIMATOR_CHANGE = 0;
  private static final int ANIMATOR_SELECT = 1;
  private static final int ANIMATOR_BUTTON = 2;

  private FactorAnimator animator;
  private float changeFactor;

  // Touch

  private final ClickHelper clickHelper;

  private static final float VOTER_RADIUS = 9f;
  private static final float VOTER_OUTLINE = 1f;
  private static final float VOTER_SPACING = 4f;

  private static class SenderEntry {
    private final TdApi.MessageSender senderId;

    public SenderEntry (Tdlib tdlib, TdApi.MessageSender senderId) {
      this.senderId = senderId;
    }

    @Override
    public boolean equals (@Nullable Object obj) {
      return obj instanceof SenderEntry && Td.equalsTo(((SenderEntry) obj).senderId, this.senderId);
    }

    @Override
    public int hashCode() {
      long senderId = Td.getSenderId(this.senderId);
      return (int) (senderId ^ (senderId >>> 32));
    }

    public void draw (Canvas c, TGMessage context, ComplexReceiver complexReceiver, float cx, float cy, final float alpha) {
      if (alpha == 0f)
        return;

      int replaceColor = context.getContentReplaceColor();
      int radius = Screen.dp(VOTER_RADIUS);

      AvatarReceiver receiver = complexReceiver.getAvatarReceiver(Td.getSenderId(senderId));
      if (alpha != 1f)
        receiver.setPaintAlpha(receiver.getPaintAlpha() * alpha);
      receiver.setBounds((int) (cx - radius), (int) (cy - radius), (int) (cx + radius), (int) (cy + radius));

      boolean needRestore = alpha != 1f;
      int restoreToCount;
      if (needRestore) {
        float scale = .5f + .5f * alpha;
        restoreToCount = Views.save(c);
        c.scale(scale, scale, cx, cy);
      } else {
        restoreToCount = -1;
      }

      float displayRadius = receiver.getDisplayRadius();
      receiver.drawPlaceholderRounded(c, displayRadius, Screen.dp(VOTER_OUTLINE) * alpha * .5f, Paints.getProgressPaint(replaceColor, Screen.dp(VOTER_OUTLINE) * alpha));
      if (receiver.needPlaceholder())
        receiver.drawPlaceholder(c);
      receiver.draw(c);
      if (alpha != 1f)
        receiver.restorePaintAlpha();

      if (needRestore) {
        Views.restore(c, restoreToCount);
      }
    }
  }

  private ListAnimator<SenderEntry> recentVoters;

  // Impl

  private void prepareOptions (TdApi.PollOption[] options, int[] displayOrder) {
    this.displayOrder = displayOrder;
    int count = options.length;
    if (this.options == null) {
      this.options = new OptionEntry[count];
      for (int i = 0; i < count; i++) {
        this.options[i] = new OptionEntry();
        if (options[i].isBeingChosen && isMultiChoicePoll()) {
          this.options[i].isSelected = new BoolAnimator(ANIMATOR_SELECT, this, AnimatorUtils.DECELERATE_INTERPOLATOR, 165l, true);
        }
      }
    } else if (count != this.options.length) {
      int oldLength = this.options.length;
      this.options = ArrayUtils.resize(this.options, count);
      for (int i = oldLength; i < count; i++) {
        this.options[i] = new OptionEntry();
        if (options[i].isBeingChosen && isMultiChoicePoll()) {
          this.options[i].isSelected = new BoolAnimator(ANIMATOR_SELECT, this, AnimatorUtils.DECELERATE_INTERPOLATOR, 165l, true);
        }
      }
    }
  }

  public TGMessagePoll (MessagesManager manager, TdApi.Message msg, TdApi.Poll poll) {
    super(manager, msg);

    /*if (BuildConfig.DEBUG) {
      msg.date = (int) (tdlib().currentTimeMillis() / 1000l);
      poll.closeDate = msg.date + 15;
      poll.openPeriod = 30;
      poll.isClosed = false;
      if (poll.type.getConstructor() == TdApi.PollTypeQuiz.CONSTRUCTOR) {
        ((TdApi.PollTypeQuiz) poll.type).explanation = null;
      }
    }*/

    this.clickHelper = new ClickHelper(this);
    this.state = new PollState(tdlib, poll, needShowResults(poll));
    setMessagePoll((TdApi.MessagePoll) msg.content);
    if (!poll.isAnonymous || isMultiChoicePoll()) {
      this.isButtonActive = new BoolAnimator(ANIMATOR_BUTTON, this, AnimatorUtils.DECELERATE_INTERPOLATOR, 120l);
      this.button = new ReplaceAnimator<>(animator -> this.invalidate());
    } else {
      this.isButtonActive = null;
      this.button = null;
    }
  }

  private void setQuestion (@NonNull TdApi.FormattedText question) {
    TdApi.FormattedText questionToSet = (translatedTexts != null ? Td.trim(translatedTexts[0]) : question);
    if (questionToSet == null)
      throw new IllegalStateException();
    if (!Td.equalsTo(this.questionTextSource, questionToSet)) {
      if (this.questionText != null) {
        this.questionText.performDestroy();
      }
      this.questionTextSource = questionToSet;
      this.questionText = new TextWrapper(tdlib, questionToSet, getBiggerTextStyleProvider(), getTextColorSet(), openParameters(), (wrapper, text, specificMedia) -> TGMessagePoll.this.invalidateTextMediaReceiver(text, specificMedia))
        .addTextFlags(Text.FLAG_ALL_BOLD)
        .setViewProvider(currentViews);
    }
  }

  private void setOptions (TdApi.PollOption[] options, int[] order) {
    prepareOptions(options, order);
    int optionId = 0;
    for (TdApi.PollOption option : options) {
      TdApi.FormattedText optionToSet = (translatedTexts != null ? Td.trim(translatedTexts[optionId + 1]) : option.text);
      if (optionToSet == null) {
        optionToSet = Td.emptyFormattedText();
      }
      if (!Td.equalsTo(this.options[optionId].textSource, optionToSet)) {
        if (this.options[optionId].text != null) {
          this.options[optionId].text.performDestroy();
        }
        this.options[optionId].textSource = optionToSet;
        this.options[optionId].text = new TextWrapper(tdlib, optionToSet, getTextStyleProvider(), getTextColorSet(), openParameters(), (wrapper, text, specificMedia) -> TGMessagePoll.this.invalidateTextMediaReceiver(text, specificMedia))
          .setViewProvider(currentViews);
      }
      optionId++;
    }
  }

  @Override
  public void requestTextMedia (ComplexReceiver textMediaReceiver) {
    if (options == null) {
      textMediaReceiver.clear();
      return;
    }
    int idOffset = Integer.MAX_VALUE / (options.length + 1);
    if (descriptionText != null) {
      descriptionText.requestMedia(textMediaReceiver, Integer.MAX_VALUE + 1L, 1000000L);
    } else {
      textMediaReceiver.clearReceiversRange(Integer.MAX_VALUE + 1L, Integer.MAX_VALUE + 1000001L);
    }
    if (explanationExpanded && explanationText != null) {
      explanationText.requestMedia(textMediaReceiver, Integer.MAX_VALUE + 1000001L, 1000000L);
    } else {
      textMediaReceiver.clearReceiversRange(Integer.MAX_VALUE + 1000001L,
        Integer.MAX_VALUE + 2000001L);
    }
    if (questionText != null) {
      questionText.requestMedia(textMediaReceiver, 0, idOffset);
    } else {
      textMediaReceiver.clearReceiversRange(0, idOffset);
    }
    int key = 0;
    for (OptionEntry entry : options) {
      key += idOffset;
      if (entry.text != null) {
        entry.text.requestMedia(textMediaReceiver, key, idOffset);
      } else {
        textMediaReceiver.clearReceiversRange(key, key + idOffset);
      }
    }
  }

  private void prepareProgress (TdApi.PollOption[] options, int[] order) {
    prepareOptions(options, order);
    if (isMultiChoicePoll()) {
      if (commonProgress != null)
        return;
      for (TdApi.PollOption option : options) {
        if (option.isBeingChosen) {
          getCommonProgressView();
          break;
        }
      }
    } else {
      int optionId = 0;
      for (TdApi.PollOption option : options) {
        if (option.isBeingChosen && this.options[optionId].progress == null) {
          getResultProgressView(optionId);
        }
        optionId++;
      }
    }
  }

  @Override
  protected void buildContent (int maxWidth) {
    if (questionText == null) {
      setRecentVoters(state.poll.recentVoterIds, false);
      setQuestion(state.poll.question);
      setOptions(state.poll.options, state.poll.optionOrder);
      prepareProgress(state.poll.options, state.poll.optionOrder);
      setTexts();
      setButton(false);
    }
    updateDescription();
    if (descriptionText != null) {
      descriptionText.prepare(maxWidth);
    }
    if (explanationText != null) {
      explanationText.prepare(maxWidth);
    }
    if (pollMedia != null) {
      pollMedia.build(maxWidth);
    }
    if (explanationMedia != null) {
      explanationMedia.build(maxWidth);
    }
    for (PollMediaAttachment attachment : optionMedia) {
      if (attachment != null) {
        attachment.buildThumbnail(Screen.dp(36f));
      }
    }
    if (questionText != null) {
      questionText.prepare(maxWidth);
    }
    int optionWidth = getOptionTextRight(maxWidth) - Screen.dp(34f);
    for (int i = 0; i < this.options.length; i++) {
      OptionEntry option = this.options[i];
      TdApi.PollOption pollOption = getPoll().options[i];
      option.voterCountStr = pollOption.voterCount > 0 ? Lang.badgeCounter(pollOption.voterCount) : null;
      option.voterCountStrWidth = option.voterCountStr != null ?
        (int) U.measureText(option.voterCountStr, Paints.getRegularTextPaint(11f)) : 0;
      int counterWidth = getPoll().canSeeResults ?
        option.voterCountStrWidth + getOptionVotersWidth(i) : 0;
      if (option.text != null) {
        option.text.prepare(Math.max(Screen.dp(24f),
          optionWidth - (counterWidth > 0 ? counterWidth + Screen.dp(8f) : 0)));
      }
    }
  }

  @Override
  protected int getContentHeight () {
    int height = getQuestionTitleHeight();
    height += Screen.dp(18f); // poll status
    if (options != null) {
      for (OptionEntry option : options) {
        height += getOptionHeight(option.text);
      }
    } else {
      height += (Screen.dp(46f) + Screen.separatorSize()) * getPoll().options.length;
    }
    height += Screen.dp(10f) + Screen.dp(14f);
    height += Screen.dp(12f);
    if (useBubbles()) {
      height += Screen.dp(8f);
    }
    return height + attachmentsHeight();
  }

  @Override
  public boolean filterKey (int receiverType, Receiver receiver, long key) {
    if (canGetVoters() && recentVoters != null) {
      for (ListAnimator.Entry<SenderEntry> recentVoter : recentVoters) {
        if (Td.getSenderId(recentVoter.item.senderId) == key) {
          return true;
        }
      }
    }
    if (canGetVoters()) {
      for (TdApi.PollOption option : getPoll().options) {
        if (option.recentVoterIds != null) {
          for (TdApi.MessageSender sender : option.recentVoterIds) {
            if (Td.getSenderId(sender) == key) {
              return true;
            }
          }
        }
      }
    }
    return false;
  }

  @Override
  public boolean needComplexReceiver () {
    return true;
  }

  @Override
  public void requestMediaContent (ComplexReceiver complexReceiver, boolean invalidate, int invalidateArg) {
    if (canGetVoters() && recentVoters != null) {
      for (ListAnimator.Entry<SenderEntry> entry : recentVoters) {
        long senderId = Td.getSenderId(entry.item.senderId);
        AvatarReceiver receiver = complexReceiver.getAvatarReceiver(senderId);
        receiver.requestMessageSender(tdlib, entry.item.senderId, AvatarReceiver.Options.NONE);
      }
    }
    if (canGetVoters()) {
      for (TdApi.PollOption option : getPoll().options) {
        if (option.recentVoterIds != null) {
          for (TdApi.MessageSender sender : option.recentVoterIds) {
            complexReceiver.getAvatarReceiver(Td.getSenderId(sender))
              .requestMessageSender(tdlib, sender, AvatarReceiver.Options.NONE);
          }
        }
      }
    }
    complexReceiver.clearReceivers(this);
  }

  @Override
  protected void drawContent (MessageView view, Canvas c, int startX, int startY, int maxWidth, ComplexReceiver receiver) {
    drawContent(view, c, startX, startY, maxWidth);
    drawAttachments(view, c, startX, maxWidth);

    // First, draw question
    startY += getQuestionTitleHeight();
    // Second, draw status
    startY += Screen.dp(18f);

    startY -= Screen.dp(10f);

    if (canGetVoters() && recentVoters != null) {
      int cx = startX + pollStatusText.getWidth() + Screen.dp(VOTER_RADIUS) + Screen.dp(6f);
      int spacing = Screen.dp(VOTER_RADIUS) * 2 - Screen.dp(VOTER_SPACING);
      for (int index = recentVoters.size() - 1; index >= 0; index--) {
        ListAnimator.Entry<SenderEntry> item = recentVoters.getEntry(index);
        int x = cx + item.getIndex() * spacing;
        if (x + Screen.dp(VOTER_RADIUS) + Screen.dp(2f) <= startX + maxWidth) {
          item.item.draw(c, this, receiver, cx + item.getPosition() * spacing, startY, item.getVisibility());
        }
      }
    }
    int optionY = getContentY() + getQuestionTitleHeight() + Screen.dp(18f);
    for (int i = 0; i < options.length; i++) {
      int index = findOptionId(i, displayOrder);
      OptionEntry entry = options[index];
      if (showOptionVoters(index)) {
        int avatarY = getOptionTextY(optionY, entry.text) +
          (entry.text != null ? entry.text.getHeight() - entry.text.getLineHeight() / 2 :
            Screen.dp(9f));
        int avatarX = startX + getOptionTextRight(maxWidth) -
          getOptionVotersWidth(index) + Screen.dp(VOTER_RADIUS);
        SenderEntry[] voters = optionVoters[index];
        if (voters != null) {
          for (int j = voters.length - 1; j >= 0; j--) {
            voters[j].draw(c, this, receiver,
              avatarX + j * Screen.dp(VOTER_RADIUS * 2 - VOTER_SPACING),
              avatarY, getResultsVisibility());
          }
        }
      }
      optionY += getOptionHeight(entry.text);
    }
  }

  private boolean showOptionVoters (int index) {
    TdApi.PollOption option = getPoll().options[index];
    return canGetVoters() && option.recentVoterIds != null && option.recentVoterIds.length > 0;
  }

  private int getOptionVotersWidth (int index) {
    if (!showOptionVoters(index) || optionVoters[index] == null) return 0;
    return Screen.dp(VOTER_RADIUS * 2 +
      (optionVoters[index].length - 1) * (VOTER_RADIUS * 2 - VOTER_SPACING) + 4f);
  }

  private int getOptionTextRight (int maxWidth) {
    for (PollMediaAttachment attachment : optionMedia) {
      if (attachment != null) return maxWidth - Screen.dp(48f);
    }
    return maxWidth;
  }

  private int getOptionTextY (int startY, @Nullable TextWrapper text) {
    return startY + Math.max(Screen.dp(8f),
      Screen.dp(46f) / 2 - (text != null ? text.getLineHeight() / 2 : 0));
  }

  public boolean isAnonymous () {
    return state.poll.isAnonymous;
  }

  private int getConfettiCenterX (int optionId) {
    return getContentX() + Screen.dp(12f);
  }

  private int getQuestionTitleHeight () {
    return (questionText != null ? questionText.getHeight() + Screen.dp(5f) : 0) +
      (descriptionText != null ? descriptionText.getHeight() + Screen.dp(8f) : 0) +
      (pollMedia != null ? pollMedia.height() : 0);
  }

  private int getConfettiCenterY (int optionId) {
    int startY = getContentY();
    startY += getQuestionTitleHeight();
    startY += Screen.dp(18f);
    int currentOptionId = 0;
    for (OptionEntry option : options) {
      if (currentOptionId == optionId) {
        return startY + Screen.dp(22f);
      }
      int optionHeight = getOptionHeight(option.text);
      startY += optionHeight;
      currentOptionId++;
    }
    return getContentY() + getContentHeight() / 2;
  }

  private Drawable explanationDrawable, explanationDrawableActive;
  private float explanationActive;

  private void setExplanationActive (float factor) {
    if (this.explanationActive != factor) {
      this.explanationActive = factor;
      if (getHintVisibility() > 0f) {
        invalidate();
      }
    }
  }

  private Drawable getExplanationDrawable (boolean isActive) {
    if (isActive) {
      if (explanationDrawableActive == null) {
        explanationDrawableActive = Drawables.get(R.drawable.deproko_baseline_lamp_filled_22);
      }
      return explanationDrawableActive;
    } else {
      if (explanationDrawable == null) {
        explanationDrawable = Drawables.get(R.drawable.deproko_baseline_lamp_22);
      }
      return explanationDrawable;
    }
  }

  private int[] getCorrectOptionIds () {
    if (state.poll.type.getConstructor() == TdApi.PollTypeQuiz.CONSTRUCTOR) {
      int[] correctOptionIds = ((TdApi.PollTypeQuiz) state.poll.type).correctOptionIds;
      if (correctOptionIds.length == 0 && futureState != null && futureState.poll.type.getConstructor() == TdApi.PollTypeQuiz.CONSTRUCTOR) {
        correctOptionIds = ((TdApi.PollTypeQuiz) futureState.poll.type).correctOptionIds;
      }
      if (correctOptionIds != null && correctOptionIds.length > 0) {
        return correctOptionIds;
      }
    }
    return null;
  }

  private int timerTime = -1;
  private String timerStr;
  private float timerWidth;

  @Override
  protected void drawContent (MessageView view, Canvas c, final int startX, int startY, int maxWidth) {
    float alpha = getTranslationLoadingAlphaValue();

    int textColor = getTextColor();
    int decentColor = getDecentColor();
    int textOffset = Screen.dp(12f);

    // First, draw question
    if (questionText != null) {
      questionText.draw(c, startX, startX + maxWidth, 0, startY, null, alpha, view.getTextMediaReceiver());
      startY += questionText.getHeight() + Screen.dp(5f);
    }
    if (descriptionText != null) {
      descriptionText.draw(c, startX, startX + maxWidth, 0, startY, null, alpha, view.getTextMediaReceiver());
      startY += descriptionText.getHeight() + Screen.dp(8f);
    }
    if (pollMedia != null) {
      pollMedia.draw(view, c, startX, startY);
      startY += pollMedia.height();
    }

    // Second, draw status
    pollStatusText.draw(c, startX, startY);

    float hintVisibility = getHintVisibility();
    if (hintVisibility > 0f) {
      Drawable explanationDrawable = getExplanationDrawable(false);
      float cx = startX + maxWidth - explanationDrawable.getMinimumWidth() / 2f - Screen.dp(2f);
      float cy = startY + pollStatusText.getHeight() / 2f;
      if (explanationActive < 1f) {
        Drawables.draw(c, explanationDrawable, cx - explanationDrawable.getMinimumWidth() / 2f, cy - explanationDrawable.getMinimumHeight() / 2f, hintVisibility == 1f ? getDecentIconPaint() : Paints.getPorterDuffPaint(ColorUtils.alphaColor(hintVisibility, decentColor)));
      }
      if (explanationActive > 0f) {
        Drawable explanationDrawableActive = getExplanationDrawable(true);
        int progressColor = getProgressColor();
        int size = Screen.dp(2f);
        Drawables.draw(c, explanationDrawableActive, cx - explanationDrawable.getMinimumWidth() / 2f, cy - explanationDrawable.getMinimumHeight() / 2f, Paints.getPorterDuffPaint(ColorUtils.alphaColor(hintVisibility * explanationActive, progressColor)));

        cy -= Screen.dp(2.5f);

        int lineColor = ColorUtils.alphaColor(hintVisibility * explanationActive, progressColor);
        int lineLength = Screen.dp(2.5f);
        float dy1 = Screen.dp(6f) + Screen.dp(3f);
        float dy2 = lineLength * explanationActive;

        for (float degrees = 0; degrees < 360f; degrees += 45f) {
          if (degrees == 180 || degrees == 180 - 45 || degrees == 180 + 45) {
            continue;
          }
          double rad = Math.toRadians(degrees);
          float sin = (float) Math.sin(rad);
          float cos = (float) Math.cos(rad);
          c.drawLine(cx - dy1 * sin, cy - dy1 * cos, cx - (dy1 + dy2) * sin, cy - (dy1 + dy2) * cos, Paints.getProgressPaint(lineColor, size));
        }
      }
    }

    float timerVisibility = getTimerVisibility();
    if (timerVisibility > 0f || timerProgress != null) {
      if (timerProgress == null) {
        timerProgress = new ProgressComponent(context(), Screen.dp(5f));
        timerProgress.setViewProvider(currentViews);
        timerProgress.setTimer(tdlib.toSystemTimeMillis(msg.date, TimeUnit.SECONDS), tdlib.toSystemTimeMillis(getPoll().closeDate, TimeUnit.SECONDS));
        timerProgress.setUseLargerPaint(Screen.dp(1.5f));
      }
      int cx = startX + maxWidth - Screen.dp(12f) - Screen.dp(1f);
      int cy = startY + pollStatusText.getHeight() / 2;
      int size = Screen.dp(12f);

      long closeDate = getPoll().closeDate != 0 ? getPoll().closeDate : (long) msg.date + getPoll().openPeriod;
      timerProgress.setTimer(tdlib.toSystemTimeMillis(msg.date, TimeUnit.SECONDS),
        tdlib.toSystemTimeMillis(closeDate, TimeUnit.SECONDS));
      long remainingTime = Math.max(0, timerProgress.getTimerRemainingTimeMillis());
      int remainingSeconds = (int) TimeUnit.MILLISECONDS.toSeconds(remainingTime);
      int color = remainingTime >= 10000 ? getDecentColor() : remainingTime <= 5000 ? getNegativeLineColor() : ColorUtils.fromToArgb(getNegativeLineColor(), getDecentColor(), (remainingTime - 5000) / (float) (10000 - 5000));

      timerProgress.forceColor(color);
      timerProgress.setAlpha(timerVisibility);
      timerProgress.setBounds(cx - size, cy - size, cx + size, cy + size);
      timerProgress.draw(c);

      TextPaint paint = Paints.getRegularTextPaint(12f, ColorUtils.alphaColor(timerVisibility, color));
      if (this.timerTime != remainingSeconds || this.timerStr == null) {
        this.timerStr = Lang.getString(R.string.EndsInDuration, Strings.buildDurationFull(remainingTime, TimeUnit.MILLISECONDS));
        this.timerTime = remainingSeconds;
        this.timerWidth = U.measureText(timerStr, paint);
      }

      cx -= Screen.dp(5f) + Screen.dp(6f) + timerWidth;
      c.drawText(timerStr, cx, cy + Screen.dp(4f), paint);
    }

    startY += Screen.dp(18f);

    // Then, draw options

    final boolean isQuiz = state.poll.type.getConstructor() == TdApi.PollTypeQuiz.CONSTRUCTOR;
    final int[] correctOptionIds = getCorrectOptionIds();

    float visibility = getResultsVisibility();
    for (int i = 0; i < options.length; i++) {
      final int optionId = findOptionId(i, displayOrder);
      OptionEntry option = options[optionId];

      int optionHeight = getOptionHeight(option.text);
      int rightX = startX + maxWidth + (useBubbles() ? getBubblePaddingRight() : 0);
      int optionTextRight = startX + getOptionTextRight(maxWidth);

      if (visibility < 1f) {
        // separator
        int lineY = startY + optionHeight - Screen.separatorSize();
        c.drawLine(startX + Screen.dp(34f), lineY, optionTextRight, lineY, Paints.getProgressPaint(ColorUtils.alphaColor(1f - visibility, getSeparatorColor()),  Screen.separatorSize()));
      }

      if (highlightOptionId == optionId) {
        c.drawRect(startX - (useBubbles() ? getBubbleContentPadding() : 0), startY, rightX, startY + optionHeight, Paints.fillingPaint(Theme.getColor(getPressColorId())));
      }

      int optionTextY = getOptionTextY(startY, option.text);
      int counterWidth = getPoll().canSeeResults ?
        option.voterCountStrWidth + getOptionVotersWidth(optionId) : 0;
      if (option.text != null) {
        option.text.draw(c, startX + Screen.dp(34f),
          optionTextRight - (counterWidth > 0 ? counterWidth + Screen.dp(8f) : 0),
          0, optionTextY, null, alpha, view.getTextMediaReceiver());
      }

      if (optionMedia[optionId] != null) {
        optionMedia[optionId].draw(view, c, startX + maxWidth - Screen.dp(36f),
          startY + optionHeight - Screen.dp(40f));
      }
      float progress = getResultProgress(optionId);
      float stateVisibility = visibility >= .5f ? 0f : 1f - visibility / .5f;
      int progressRadius = Screen.dp(9f);
      int progressCx = startX + Screen.dp(12f);
      int progressCy = startY + Screen.dp(22f);
      if (stateVisibility > 0f) {
        int circleColor = ColorUtils.alphaColor(stateVisibility * (isMultiChoicePoll() ? 1f - option.getSelectionFactor() : 1f - progress), decentColor);
        if (isMultiChoicePoll()) {
          RectF rectF = Paints.getRectF();
          progressRadius -= Screen.dp(1f);
          rectF.set(progressCx - progressRadius, progressCy - progressRadius, progressCx + progressRadius, progressCy + progressRadius);
          int squareRadius = Screen.dp(3f);
          c.drawRoundRect(rectF, squareRadius, squareRadius, Paints.getProgressPaint(circleColor, Screen.dp(1f)));
        } else {
          c.drawCircle(progressCx, progressCy, progressRadius, Paints.getProgressPaint(circleColor, Screen.dp(1f)));
        }
        if (progress > 0f && option.progress != null && !isMultiChoicePoll()) {
          ProgressComponent progressComponent = getResultProgressView(optionId);
          progressComponent.forceColor(ColorUtils.alphaColor(stateVisibility * progress, Theme.getColor(getProgressColorId())));
          progressComponent.setBounds(progressCx - progressRadius, progressCy - progressRadius, progressCx + progressRadius, progressCy + progressRadius);
          progressComponent.draw(c);
        }
      }

      int lineColor = getVerticalLineColor();
      int contentColor = getVerticalLineContentColor();
      float selectionFactor = option.getSelectionFactor();

      boolean isCorrectOption = ArrayUtils.contains(correctOptionIds, optionId);
      boolean isChosen = futureState != null ?
        futureState.poll.options[optionId].isChosen :
        state.poll.options[optionId].isChosen;

      boolean hasAnswer = futureState != null ?
        futureState.resultsVisible :
        state.resultsVisible;

      if (correctOptionIds != null) {
        int secondaryColor = isCorrectOption ? getCorrectLineColor(isChosen) : getNegativeLineColor();
        int secondaryContentColor = isCorrectOption ? getCorrectLineContentColor(isChosen) : getNegativeLineContentColor();
        lineColor = ColorUtils.fromToArgb(lineColor, secondaryColor, selectionFactor * visibility);
        contentColor = ColorUtils.fromToArgb(contentColor, secondaryContentColor, selectionFactor * visibility);
      }

      int lineY = startY + optionHeight - Screen.dp(4.5f);
      int fromX = startX + Screen.dp(34f);
      int toX = optionTextRight;
      if (visibility > 0f) {
        float radius = Screen.dp(2.5f);
        c.drawRoundRect(fromX, lineY - radius, toX, lineY + radius, radius, radius,
          Paints.fillingPaint(ColorUtils.alphaColor(visibility * .06f, textColor)));
        float resultWidth = Math.max(Screen.dp(5f), (toX - fromX) * getResultRatio(optionId));
        c.drawRoundRect(fromX, lineY - radius, fromX + Math.min(toX - fromX, resultWidth),
          lineY + radius, radius, radius,
          Paints.fillingPaint(ColorUtils.alphaColor(visibility, lineColor)));
        c.drawText(option.percentageStr, fromX - option.percentageStrWidth - Screen.dp(8f), optionTextY + textOffset, Paints.getMediumTextPaint(13f, ColorUtils.alphaColor(visibility, textColor), false));
        if (option.voterCountStr != null) {
          c.drawText(option.voterCountStr,
            optionTextRight - getOptionVotersWidth(optionId) - option.voterCountStrWidth,
            optionTextY + (option.text != null ?
              option.text.getHeight() - option.text.getLineHeight() : 0) + textOffset,
            Paints.getRegularTextPaint(11f,
              ColorUtils.alphaColor(visibility, textColor)));
        }
      }

      if (selectionFactor > 0f) {
        float moveFactor = option.getMoveFactor(visibility);
        float squareFactor = state.poll.allowsMultipleAnswers ? 1f : 0f;
        int checkBoxMode = isQuiz && hasAnswer && correctOptionIds != null ? (!isCorrectOption ? SimplestCheckBox.MODE_NEGATIVE : !isChosen ? SimplestCheckBox.MODE_MISSING : SimplestCheckBox.MODE_NORMAL) : SimplestCheckBox.MODE_NORMAL;
        if (option.checkBox == null) {
          option.checkBox = SimplestCheckBox.newInstance(selectionFactor, null, lineColor, contentColor, checkBoxMode, moveFactor);
        }
        float scale = .75f;
        int cx = fromX - (int) (SimplestCheckBox.size() * scale) / 2 - Screen.dp(8f) + (int) (Screen.dp(2f) * scale);
        int cy = lineY - (int) (Screen.dp(2f) * scale);
        if (moveFactor > 0f) {
          cx = (int) (cx + (progressCx - cx) * moveFactor);
          cy = (int) (cy + (progressCy - cy) * moveFactor);
          scale = scale + (1f - scale) * moveFactor;
        }
        final boolean needScale = scale != 1f;
        final int restoreToCount;
        if (needScale) {
          restoreToCount = Views.save(c);
          c.scale(scale, scale, cx, lineY);
        } else {
          restoreToCount = -1;
        }
        SimplestCheckBox.draw(c, cx, cy, selectionFactor, null, option.checkBox, lineColor, contentColor, checkBoxMode, squareFactor);
        if (needScale) {
          Views.restore(c, restoreToCount);
        }
      }

      startY += optionHeight;
    }

    if (highlightOptionId == HIGHLIGHT_BUTTON) {
      if (useBubble() && !useForward()) {
        final int restoreToCount = Views.save(c);
        c.clipRect(getActualLeftContentEdge(), startY, getActualRightContentEdge(), getBottomContentEdge());
        c.drawPath(getBubblePath(), Paints.fillingPaint(Theme.getColor(getPressColorId())));
        Views.restore(c, restoreToCount);
      } else {
        int rightX = startX + maxWidth + (useBubbles() ? getBubblePaddingRight() : 0);
        c.drawRect(startX - (useBubbles() ? getBubbleContentPadding() : 0), startY, rightX, startY + Screen.dp(46f), Paints.fillingPaint(Theme.getColor(getPressColorId())));
      }
    }

    startY += Screen.dp(10f);
    float maxVisibility = 0f;
    if (button != null) {
      for (ListAnimator.Entry<Button> entry : button) {
        maxVisibility = Math.max(entry.getVisibility(), maxVisibility);
        int x = startX + maxWidth / 2 - entry.item.text.getWidth() / 2;
        int y = startY + Screen.dp(useBubbles() ? 6f : 4f);
        /*float textVisibility = entry.getVisibility();
        if (textVisibility != 1f) {
          float scale = .9f + .1f * textVisibility;
          c.save();
          c.scale(scale, scale, startX + maxWidth / 2, y + entry.item.text.getHeight() / 2);
        }*/
        // int dy = (int) (!isMultiChoicePoll() && !isAnonymous() ? Screen.dp(7f) * getResultsVisibility() : 0);
        entry.item.text.draw(c, x, y, null, (1f - .4f * (1f - isButtonActive.getFloatValue())) * entry.getVisibility());
        if (commonProgress != null && entry.item.id == R.id.btn_vote) {
          final float stateVisibility = visibility >= .5f ? 0f : 1f - visibility / .5f;
          commonProgress.forceColor(ColorUtils.alphaColor(stateVisibility * getCommonProgress() * entry.getVisibility(), Theme.getColor(getProgressColorId())));
          int radius = Screen.dp(3);
          x += entry.item.text.getWidth() + radius + Screen.dp(7f);
          y += entry.item.text.getHeight() / 2;
          commonProgress.setBounds(x - radius, y - radius, x + radius, y + radius);
          commonProgress.draw(c);
        }
        /*if (textVisibility != 1f) {
          c.restore();
        }*/
      }
    }
    startY += Screen.dp(12f);

    /*if (button != null && !isAnonymous() && !isMultiChoicePoll()) {
      decentColor = U.alphaColor(1f - maxVisibility, decentColor);
    }*/
    int textCx = startX + maxWidth / 2 - totalVoterCountStrWidth / 2;
    int textCy = startY + textOffset - Screen.dp(useBubbles() ? 5f : 7f);
    if (!isAnonymous() || isMultiChoicePoll()) {
      decentColor = ColorUtils.alphaColor(1f - maxVisibility, decentColor);
      c.drawText(totalVoterCountStr, textCx, textCy, Paints.getRegularTextPaint(12f, decentColor));
    } else {
      // c.drawText(totalVoterCountStr, startX, startY + textOffset, Paints.getRegularTextPaint(12f, decentColor));
      c.drawText(totalVoterCountStr, textCx, textCy, Paints.getRegularTextPaint(12f, decentColor));
    }
  }

  private void drawAttachments (MessageView view, Canvas c, int startX, int maxWidth) {
    int y = getContentY() + getContentHeight() - attachmentsHeight();
    if (canPreviewResults()) {
      drawAction(c, startX, y, maxWidth,
        previewResults ? R.string.PollReturnToVoting : R.string.PollPreviewResults);
      y += Screen.dp(36f);
    }
    if (canAddOption()) {
      drawAction(c, startX, y, maxWidth, R.string.PollAddOption);
      y += Screen.dp(36f);
    }
    if (explanationExpanded) {
      if (explanationText != null) {
        explanationText.draw(c, startX, startX + maxWidth, 0, y, null, 1f,
          view.getTextMediaReceiver());
        y += explanationText.getHeight() + Screen.dp(8f);
      }
      if (explanationMedia != null) {
        explanationMedia.draw(view, c, startX, y);
      }
    }
  }

  private void drawAction (Canvas c, int x, int y, int width, int stringId) {
    String label = Lang.getString(stringId);
    TextPaint paint = Paints.getMediumTextPaint(14f, getTextLinkColor(), false);
    c.drawText(label, x + (width - U.measureText(label, paint)) / 2f, y + Screen.dp(23f), paint);
  }

  @Override
  protected int getBottomLineContentWidth () {
    return totalVoterCountStrWidth;
  }

  private float getResultsVisibility () {
    if (futureState == null || changeFactor == 0f)
      return state.resultsVisibility;
    if (changeFactor == 1f)
      return futureState.resultsVisibility;
    return fromTo(state.resultsVisibility, futureState.resultsVisibility, changeFactor);
  }

  private float getHintVisibility () {
    if (futureState == null || changeFactor == 0f)
      return state.hintVisibility;
    if (changeFactor == 1f)
      return futureState.hintVisibility;
    return fromTo(state.hintVisibility, futureState.hintVisibility, changeFactor);
  }

  private float getTimerVisibility () {
    if (futureState == null || changeFactor == 0f)
      return state.timerVisibility;
    if (changeFactor == 1f)
      return futureState.timerVisibility;
    return fromTo(state.timerVisibility, futureState.timerVisibility, changeFactor);
  }

  private float getResultRatio (int optionId) {
    if (futureState == null || changeFactor == 0f)
      return state.resultsVisible ? state.options[optionId].ratio : 0f;
    if (changeFactor == 1f)
      return futureState.options[optionId].ratio;
    return fromTo(state.options[optionId].ratio, futureState.options[optionId].ratio, changeFactor);
  }

  private float getResultProgress (int optionId) {
    if (futureState == null || changeFactor == 0f)
      return state.options[optionId].progress;
    if (changeFactor == 1f)
      return futureState.options[optionId].progress;
    return fromTo(state.options[optionId].progress, futureState.options[optionId].progress, changeFactor);
  }

  private float getCommonProgress () {
    float maxProgress = 0f;
    for (int optionId = 0; optionId < state.options.length; optionId++) {
      maxProgress = Math.max(maxProgress, getResultProgress(optionId));
    }
    return maxProgress;
  }

  private TdApi.Poll getPoll () {
    return futureState != null ? futureState.poll : state.poll;
  }

  private boolean isMultiChoicePoll () {
    return getPoll().allowsMultipleAnswers;
  }

  private boolean isQuiz () {
    return getPoll().type.getConstructor() == TdApi.PollTypeQuiz.CONSTRUCTOR;
  }

  private boolean hasAnswer () {
    return TD.hasAnswer(getPoll());
  }

  private ProgressComponent newProgress (boolean isCommon) {
    ProgressComponent progress = new ProgressComponent(context(), Screen.dp(isCommon ? 3f : 9f));
    progress.setNoStartDelay(true);
    progress.setViewProvider(currentViews);
    progress.forceColor(0);
    return progress;
  }

  private ProgressComponent getCommonProgressView () {
    if (!isMultiChoicePoll())
      return null;
    if (commonProgress == null)
      commonProgress = newProgress(true);
    return commonProgress;
  }

  private ProgressComponent getResultProgressView (int optionId) {
    if (isMultiChoicePoll())
      return null;
    ProgressComponent progress = options[optionId].progress;
    if (progress == null) {
      progress = newProgress(false);
      options[optionId].progress = progress;
    }
    return progress;
  }

  private boolean canVote (boolean checkSelected) {
    return canVote(getPoll()) && (!checkSelected || (!isMultiChoicePoll() || !(!hasAnswer() && TD.hasSelectedOption(getPoll()))));
  }

  private boolean canVote (TdApi.Poll poll) {
    if (poll.isClosed || poll.voteRestrictionReason != null || previewResults) return false;
    for (TdApi.PollOption option : poll.options) {
      if (option.isChosen) {
        return false;
      }
    }
    return !needShowResults(poll);
  }

  private boolean needShowResults (TdApi.Poll poll) {
    if (!poll.canSeeResults) return false;
    if (previewResults) return true;
    if (poll.isClosed)
      return true;
    boolean haveVoters = false;
    boolean haveChosenOption = false;
    for (TdApi.PollOption option : poll.options) {
      if (option.isChosen)
        haveChosenOption = true;
      if (option.voterCount > 0) {
        haveVoters = true;
      }
    }
    if (haveChosenOption) {
      return haveVoters;
    }
    // show results for anonymous admin
    // FIXME TDLib/server: poll information never returned to anonymous admin
    return haveVoters && tdlib.isAnonymousAdminNonCreator(msg.chatId);
  }

  @Override
  protected boolean onLocaleChange () {
    totalVoterCount = -1;
    pollStatus = 0;
    setTexts();
    return true;
  }

  private void setRecentVoters (TdApi.MessageSender[] recentVoterIds, boolean animated) {
    if (canGetVoters() && recentVoterIds != null && recentVoterIds.length > 0) {
      List<SenderEntry> entries = new ArrayList<>(recentVoterIds.length);
      for (TdApi.MessageSender senderId : recentVoterIds) {
        entries.add(new SenderEntry(tdlib, senderId));
      }
      if (this.recentVoters == null)
        this.recentVoters = new ListAnimator<>(currentViews);
      recentVoters.reset(entries, animated);
    } else if (recentVoters != null) {
      recentVoters.clear(animated);
    }
  }

  @Override
  protected boolean isSupportedMessageContent(TdApi.Message message, TdApi.MessageContent messageContent) {
    if (super.isSupportedMessageContent(message, messageContent)) {
      TdApi.Poll oldPoll = getPoll();
      TdApi.Poll updatedPoll = ((TdApi.MessagePoll) messageContent).poll;
      return oldPoll.options.length == updatedPoll.options.length &&
        oldPoll.type.getConstructor() == updatedPoll.type.getConstructor() &&
        oldPoll.allowsMultipleAnswers == updatedPoll.allowsMultipleAnswers;
    }
    return false;
  }

  private void applyPoll (TdApi.Poll updatedPoll, boolean force) {
    TdApi.Poll oldPoll = getPoll();
    boolean changed = !sameOptionIds(oldPoll, updatedPoll) || !Td.equalsTo(oldPoll, updatedPoll, true) || force;
    if (!changed && Td.equalsTo(oldPoll, updatedPoll, false)) {
      return;
    }
    boolean animated = !changed && needAnimateChanges();
    if (animated) {
      resetPollAnimation(true);
      futureState = new PollState(tdlib, updatedPoll, needShowResults(updatedPoll));
      setRecentVoters(updatedPoll.recentVoterIds, true);
      setButton(true);
      if (recentVoters != null) {
        invalidateContentReceiver();
      }
      if (isQuiz() || Config.TEST_CONFETTI) {
        TdApi.PollTypeQuiz quiz;
        if (updatedPoll.type.getConstructor() == TdApi.PollTypeQuiz.CONSTRUCTOR) {
          quiz = (TdApi.PollTypeQuiz) updatedPoll.type;
        } else {
          quiz = null;
        }

        boolean isShaken = false;
        int optionId = 0;
        for (TdApi.PollOption option : updatedPoll.options) {
          TdApi.PollOption oldOption = oldPoll.options[optionId];
          if (oldOption.isBeingChosen && option.isChosen && quiz != null) {
            boolean isCorrect = TD.isAnsweredCorrectly(updatedPoll) == TD.ANSWER_CORRECT;
            if (isCorrect) {
              performConfettiAnimation(getConfettiCenterX(optionId), getConfettiCenterY(optionId));
            }
            if (!isShaken) {
              isShaken = true;
              performShakeAnimation(isCorrect);
            }
            if (!isCorrect && (!Td.isEmpty(quiz.explanation) || quiz.explanationMedia != null)) {
              showExplanation(null);
            }
          }
          optionId++;
        }
        if (!isShaken && !oldPoll.isClosed && updatedPoll.isClosed && oldPoll.openPeriod > 0 && oldPoll.closeDate != 0 && tdlib.currentTime(TimeUnit.SECONDS) + 5 >= oldPoll.closeDate) {
          performShakeAnimation(false);
        }
      }
      if (isMultiChoicePoll() && TD.hasAnswer(updatedPoll)) {
        int optionId = 0;
        for (OptionEntry entry : options) {
          if (entry.isSelected != null && !(updatedPoll.options[optionId].isChosen || updatedPoll.options[optionId].isBeingChosen)) {
            entry.isSelected.setValue(false, false);
            entry.isSelected = null;
          }
          optionId++;
        }
      }
      if (animator == null) {
        animator = new FactorAnimator(ANIMATOR_CHANGE, this, AnimatorUtils.DECELERATE_INTERPOLATOR, 280l);
      }
      animator.animateTo(1f);
    } else {
      resetPollAnimation(false);
      this.state = new PollState(tdlib, updatedPoll, needShowResults(updatedPoll));
      setRecentVoters(updatedPoll.recentVoterIds, false);
      if (recentVoters != null) {
        invalidateContentReceiver();
      }
      setButton(false);
      setTexts();
      if (changed) {
        setQuestion(updatedPoll.question);
        setOptions(updatedPoll.options, updatedPoll.optionOrder);
        prepareProgress(updatedPoll.options, updatedPoll.optionOrder);
        rebuildAndUpdateContent();
      } else {
        invalidate();
      }
    }
  }

  @Override
  protected boolean onMessageContentChanged (TdApi.Message message, TdApi.MessageContent oldContent, TdApi.MessageContent newContent, boolean isBottomMessage) {
    if (Td.isPoll(newContent)) {
      setMessagePoll((TdApi.MessagePoll) newContent);
      TdApi.Poll updatedPoll = ((TdApi.MessagePoll) newContent).poll;
      applyPoll(updatedPoll, false);
      rebuildAndUpdateContent();
      return true;
    }
    return false;
  }

  @Override
  protected boolean updateMessageContent (TdApi.Message message, TdApi.MessageContent newContent, boolean isBottomMessage) {
    setMessagePoll((TdApi.MessagePoll) newContent);
    TdApi.Poll updatedPoll = ((TdApi.MessagePoll) newContent).poll;
    applyPoll(updatedPoll, false);
    rebuildAndUpdateContent();
    return true;
  }

  // Texts

  private CharSequence getCounter (TdApi.Poll poll, int count) {
    if (!poll.canSeeResults) {
      return Lang.getString(poll.type instanceof TdApi.PollTypeQuiz ?
        R.string.WaitingResultsQuiz : R.string.WaitingResultsPoll);
    }
    switch (getPoll().type.getConstructor()) {
      case TdApi.PollTypeRegular.CONSTRUCTOR:
        return count > 0 ? Lang.pluralBold(R.string.xVotes, count) : Lang.getString(TD.areResultsHidden(poll) ? R.string.WaitingResultsPoll : poll.isClosed ? R.string.NoVotesResult : R.string.NoVotes);
      case TdApi.PollTypeQuiz.CONSTRUCTOR:
        return count > 0 ? Lang.pluralBold(R.string.xAnswers, count) : Lang.getString(TD.areResultsHidden(poll) ? R.string.WaitingResultsQuiz : poll.isClosed ? R.string.NoAnswersResult : R.string.NoAnswers);
      default:
        throw new IllegalArgumentException(getPoll().type.toString());
    }
  }

  private void setTotalVoterCount (TdApi.Poll poll, boolean force) {
    int count = poll.canSeeResults ? poll.totalVoterCount : 0;
    if (poll.canSeeResults && !poll.isAnonymous) {
      if (TD.hasAnswer(poll))
        count--;
    } else if (poll.canSeeResults && isMultiChoicePoll() && canVote(false)) {
      if (!TD.hasAnswer(poll))
        count++;
    }
    if (this.totalVoterCount != count || force) {
      this.totalVoterCount = count;
      this.totalVoterCountStr = getCounter(poll, count).toString();
      this.totalVoterCountStrWidth = (int) U.measureText(totalVoterCountStr, Paints.getRegularTextPaint(12f));
    }
  }

  private void setPollStatus (int status) {
    if (this.pollStatus != status) {
      this.pollStatus = status;
      int stringRes;
      switch (getPoll().type.getConstructor()) {
        case TdApi.PollTypeRegular.CONSTRUCTOR:
          stringRes = status == POLL_STATUS_CLOSED ? R.string.PollResults : getPoll().isAnonymous ? R.string.PollAnonymous : R.string.PollPublic;
          break;
        case TdApi.PollTypeQuiz.CONSTRUCTOR:
          stringRes = status == POLL_STATUS_CLOSED ? R.string.QuizResults : getPoll().isAnonymous ? R.string.QuizAnonymous : R.string.QuizPublic;
          break;
        default:
          throw new IllegalArgumentException(getPoll().type.toString());
      }
      this.pollStatusText = new Text.Builder(Lang.getString(stringRes), getContentMaxWidth(), Paints.robotoStyleProvider(12f), getDecentColorSet()).singleLine().build();
    }
  }

  private static String makeVotePercentageStr (int votePercentage) {
    return votePercentage + "%";
  }

  private void setPercentage (int optionId, int percentage) {
    if (options != null && options[optionId].percentage != percentage) {
      options[optionId].percentage = percentage;
      options[optionId].percentageStr = makeVotePercentageStr(percentage);
      options[optionId].percentageStrWidth = (int) U.measureText(options[optionId].percentageStr, Paints.getMediumTextPaint(13f, false));
    }
  }

  private void setPercentages (boolean visible, TdApi.PollOption[] options, int[] order) {
    prepareOptions(options, order);
    for (int i = 0; i < options.length; i++) {
      setPercentage(i, visible ? options[i].votePercentage : 0);
    }
  }

  private void setTexts () {
    if (futureState == null) {
      boolean showResults = needShowResults(state.poll);
      setTotalVoterCount(state.poll, true);
      setPollStatus(state.poll.isClosed ? POLL_STATUS_CLOSED : POLL_STATUS_ANONYMOUS);
      setPercentages(showResults, state.poll.options, state.poll.optionOrder);
      int[] correctOptionIds = state.poll.type.getConstructor() == TdApi.PollTypeQuiz.CONSTRUCTOR ? ((TdApi.PollTypeQuiz) state.poll.type).correctOptionIds : null;
      for (int optionId = 0; optionId < state.poll.options.length; optionId++) {
        boolean isCorrect = correctOptionIds != null && ArrayUtils.contains(correctOptionIds, optionId);
        options[optionId].selectionFactor = (showResults && isCorrect) || state.poll.options[optionId].isChosen ? 1f : 0f;
      }
    } else {
      setTotalVoterCount(futureState.poll, state.poll.isClosed != futureState.poll.isClosed || TD.areResultsHidden(state.poll) != TD.areResultsHidden(futureState.poll));
      if (state.poll.isClosed != futureState.poll.isClosed) {
        setPollStatus(futureState.poll.isClosed ? POLL_STATUS_CLOSED : POLL_STATUS_ANONYMOUS);
      }
      boolean fromShowResults = needShowResults(state.poll);
      int[] fromCorrectOptionIds = state.poll.type.getConstructor() == TdApi.PollTypeQuiz.CONSTRUCTOR ? ((TdApi.PollTypeQuiz) state.poll.type).correctOptionIds : null;
      boolean toShowResults = needShowResults(futureState.poll);
      int[] toCorrectOptionIds = futureState.poll.type.getConstructor() == TdApi.PollTypeQuiz.CONSTRUCTOR ? ((TdApi.PollTypeQuiz) futureState.poll.type).correctOptionIds : null;
      for (int optionId = 0; optionId < state.poll.options.length; optionId++) {
        int fromPercentage = state.resultsVisible ? state.votePercentage(optionId) : 0;
        int toPercentage = futureState.resultsVisible ? futureState.votePercentage(optionId) : 0;
        if (fromPercentage != toPercentage) {
          setPercentage(optionId, fromTo(fromPercentage, toPercentage, changeFactor));
        }

        options[optionId].selectionFactor = fromTo(
          (fromShowResults && ArrayUtils.contains(fromCorrectOptionIds, optionId)) || state.poll.options[optionId].isChosen ? 1f : 0f,
          (toShowResults && ArrayUtils.contains(toCorrectOptionIds, optionId)) || futureState.poll.options[optionId].isChosen ? 1f : 0f,
          changeFactor
        );
      }
    }
  }

  private void updateProgress () {
    if (options == null)
      return;
    final float visibility = getResultsVisibility();
    final float stateVisibility = visibility >= .5f ? 0f : 1f - visibility / .5f;
    final int progressColor = Theme.getColor(getProgressColorId());
    if (isMultiChoicePoll()) {
      float maxProgress = 0f;
      int optionId = 0;
      for (OptionEntry ignored : options) {
        maxProgress = Math.max(maxProgress, getResultProgress(optionId));
        optionId++;
      }
      if (commonProgress != null || maxProgress > 0f) {
        getCommonProgressView().forceColor(ColorUtils.alphaColor(stateVisibility * maxProgress, progressColor));
      }
    } else {
      int optionId = 0;
      for (OptionEntry option : options) {
        float progress = getResultProgress(optionId);
        if (option.progress != null || (progress > 0f && stateVisibility > 0f)) {
          ProgressComponent progressComponent = getResultProgressView(optionId);
          if (progressComponent != null) {
            progressComponent.forceColor(ColorUtils.alphaColor(stateVisibility * progress, progressColor));
          }
        }
        optionId++;
      }
    }
  }

  private static int findOptionId (int optionIndex, int[] order) {
    if (order != null && order.length > 0) {
      return order[optionIndex];
    } else {
      return optionIndex;
    }
  }

  private static class Button {
    private final int id;
    private final Text text;
    private final boolean isInactive;

    public Button (int id, Text text, boolean isInactive) {
      this.id = id;
      this.text = text;
      this.isInactive = isInactive;
    }

    public boolean isInactive () {
      return isInactive;
    }

    @Override
    public boolean equals (@Nullable Object obj) {
      return obj instanceof Button && ((Button) obj).id == this.id && ((Button) obj).text.getText().equals(this.text.getText());
    }
  }

  private void setButton (boolean animated) {
    if (this.button == null)
      return;
    int id;
    String str;
    boolean isInactive = false;
    if (isMultiChoicePoll() && !hasAnswer() && canVote(false)) {
      id = R.id.btn_vote;
      str = Lang.getString(R.string.Vote);
    } else if (canGetVoters() && (hasAnswer() || !canVote(false))) {
      id = R.id.btn_viewResults;
      int voterCount = getPoll().totalVoterCount;
      if (canVote(false) && !TD.hasAnswer(getPoll())) {
        voterCount++;
      }
      if (voterCount == 0 && getPoll().isClosed) {
        str = Lang.getString(isQuiz() ? R.string.NoAnswersResult : R.string.NoVotesResult);
        isInactive = true;
      } else if (voterCount > 1) {
        str = Lang.plural(isQuiz() ? R.string.ViewXQuizResults : R.string.ViewXPollResults, voterCount);
      } else {
        str = Lang.getString(isQuiz() ? R.string.ViewQuizResults : R.string.ViewPollResults);
      }
    } else {
      id = 0; str = null;
    }
    if (str != null) {
      Text text = new Text.Builder(str, getContentMaxWidth(), isInactive ? Paints.robotoStyleProvider(12f) : getNameStyleProvider(), isInactive ? getDecentColorSet() : getLinkColorSet()).singleLine().allBold(!isInactive).build();
      this.button.replace(new Button(id, text, isInactive), animated);
    } else {
      this.button.clear(animated);
    }
    updateButtonState(animated);
  }

  private void updateButtonState (boolean animated) {
    if (isButtonActive == null || button == null)
      return;

    boolean isActive = false;
    if (isMultiChoicePoll() && !hasAnswer() && canVote(false)) {
      if (options != null) {
        for (OptionEntry entry : options) {
          if (entry.isSelected()) {
            isActive = true;
            break;
          }
        }
      }
    } else if (canGetVoters() && (hasAnswer() || !canVote(false))) {
      isActive = true;
    } else {
      isActive = false;
    }

    isButtonActive.setValue(isActive, animated);
  }

  // Animation

  private void resetPollAnimation (boolean applyFuture) {
    if (animator != null) {
      animator.cancel();
      animator.forceFactor(0f);
    }
    if (futureState != null) {
      if (applyFuture) {
        state = new PollState(tdlib, state, futureState, changeFactor);
      }
      futureState = null;
    }
    if (recentVoters != null) {
      recentVoters.stopAnimation(applyFuture);
    }
    if (button != null) {
      button.stopAnimation(applyFuture);
    }
    changeFactor = 0f;
  }

  private void setChangeFactor (float factor) {
    if (this.changeFactor != factor) {
      this.changeFactor = factor;
      // Looks better when synchronized with everything else
      // float animationFactor = factor > (180f / 280f) ? 1f : factor / (180f / 280f);
      if (recentVoters != null) {
        recentVoters.applyAnimation(factor);
      }
      if (button != null) {
        button.applyAnimation(factor);
      }
      setTexts();
      updateProgress();
      if (timerProgress != null) {
        timerProgress.setAlpha(getTimerVisibility());
      }
      invalidate();
    }
  }

  @Override
  protected void onChildFactorChanged (int id, float factor, float fraction) {
    switch (id) {
      case ANIMATOR_CHANGE: {
        setChangeFactor(factor);
        break;
      }
      case ANIMATOR_SELECT:
      case ANIMATOR_BUTTON: {
        invalidate();
        break;
      }
    }
  }

  @Override
  protected void onChildFactorChangeFinished (int id, float finalFactor, FactorAnimator callee) {
    switch (id) {
      case ANIMATOR_CHANGE: {
        if (finalFactor == 1f && this.futureState != null) {
          setChangeFactor(1f);
          if (isMultiChoicePoll() && hasAnswer()) {
            for (OptionEntry entry : options) {
              if (entry.isSelected != null) {
                entry.isSelected.setValue(false, false);
                entry.isSelected = null;
              }
            }
          }
          this.state = futureState;
          this.futureState = null;
          this.animator.forceFactor(0f);
          this.changeFactor = 0f;
        }
        break;
      }
    }
  }

  // Touch

  @Override
  public boolean onTouchEvent (MessageView view, MotionEvent e) {
    if (descriptionText != null && descriptionText.onTouchEvent(view, e)) {
      return true;
    }
    if (explanationExpanded && explanationText != null && explanationText.onTouchEvent(view, e)) {
      return true;
    }
    if (pollMedia != null && pollMedia.touch(view, e)) {
      return true;
    }
    if (explanationExpanded && explanationMedia != null && explanationMedia.touch(view, e)) {
      return true;
    }
    for (PollMediaAttachment attachment : optionMedia) {
      if (attachment != null && attachment.touch(view, e)) {
        return true;
      }
    }
    boolean res = super.onTouchEvent(view, e);
    return (!isEventLog() && clickHelper.onTouchEvent(view, e)) || res;
  }

  private int clickOptionId = HIGHLIGHT_NONE;

  @Override
  public boolean performLongPress (View view, float x, float y) {
    clickHelper.cancel(view, x, y);
    if (needClickAt(view, x, y) && clickOptionId >= 0) {
      showOptionMenu(view, clickOptionId);
      clickOptionId = HIGHLIGHT_NONE;
      return true;
    }
    return super.performLongPress(view, x, y);
  }

  @Override
  public boolean needClickAt (View view, final float originalX, final float originalY) {
    final float x = originalX - getContentX();
    final float y = originalY - getContentY();
    final int maxWidth = getContentMaxWidth();

    if (x < 0 || y < 0 || x > maxWidth || isNotSent()) {
      return false;
    }

    int extraY = getContentHeight() - attachmentsHeight();
    if (canPreviewResults() && y >= extraY && y < extraY + Screen.dp(36f)) {
      clickOptionId = HIGHLIGHT_PREVIEW;
      return true;
    }
    if (canPreviewResults()) {
      extraY += Screen.dp(36f);
    }
    if (canAddOption() && y >= extraY && y < extraY + Screen.dp(36f)) {
      clickOptionId = HIGHLIGHT_ADD;
      return true;
    }
    int startY = getQuestionTitleHeight();
    if (explanationDrawable != null && getHintVisibility() > 0f) {
      float cx = maxWidth - explanationDrawable.getMinimumWidth() / 2f - Screen.dp(2f);
      float cy = startY + pollStatusText.getHeight() / 2f;

      float padding = Screen.dp(6f);
      float boundX = explanationDrawable.getMinimumWidth() / 2f + padding;
      float boundY = explanationDrawable.getMinimumHeight() / 2f + padding;

      if (x >= cx - boundX && x <= cx + boundX && y >= cy - boundY && y <= cy + boundY) {
        clickOptionId = HIGHLIGHT_EXPLANATION;
        return true;
      }
    }
    startY += Screen.dp(18f);

    for (int i = 0; i < options.length; i++) {
      final int optionId = findOptionId(i, displayOrder);
      OptionEntry option = options[optionId];

      int optionHeight = getOptionHeight(option.text);
      if (y >= startY && y < startY + optionHeight) {
        clickOptionId = optionId;
        return true;
      }
      startY += optionHeight;
    }

    if (isButtonActive != null && isButtonActive.getValue() && button.singleton() != null &&
        !button.singleton().item.isInactive && y >= startY &&
        y < getContentHeight() - attachmentsHeight() +
          (useBubbles() ? getBubbleContentPadding() : 0)) {
      clickOptionId = HIGHLIGHT_BUTTON;
      return true;
    }

    return false;
  }

  @Override
  public void onClickTouchDown (View view, float x, float y) {
    if (clickOptionId <= HIGHLIGHT_NONE || canVote(true)) {
      setHighlightOption(clickOptionId, view, x, y);
    }
  }

  @Override
  public void onClickTouchUp (View view, float x, float y) {
    setHighlightOption(HIGHLIGHT_NONE, view, x, y);
  }

  private TooltipOverlayView.TooltipInfo explanationPopup;

  @Override
  public void onVisibilityChanged (TooltipOverlayView.TooltipInfo tooltipInfo, float visibilityFactor) {
    if (explanationPopup == tooltipInfo) {
      setExplanationActive(MathUtils.clamp(visibilityFactor));
    }
  }

  @Override
  public void onVisibilityChangeFinished (TooltipOverlayView.TooltipInfo tooltipInfo, boolean isVisible) {
    if (explanationPopup == tooltipInfo && !isVisible) {
      explanationPopup = null;
    }
  }

  private void showExplanation (View view) {
    if (explanationMedia != null) {
      explanationExpanded = !explanationExpanded;
      rebuildAndUpdateContent();
      invalidateTextMediaReceiver();
      return;
    }
    TdApi.FormattedText formattedText = TD.getExplanation(getPoll());
    if (!Td.isEmpty(formattedText)) {
      if (explanationPopup != null) {
        explanationPopup.removeListener(this);
      }
      explanationPopup = buildContentHint(view, (targetView, outRect) -> {
        if (questionText != null) {
          outRect.set(0, 0, questionText.getWidth(), questionText.getHeight());
        } else {
          outRect.setEmpty();
        }
      }, true).icon(R.drawable.baseline_info_24).needBlink(true).chatTextSize(-2f).interceptTouchEvents(true).handleBackPress(true).show(tdlib, formattedText).addListener(this);
    }
  }

  @Override
  public void onClickAt (View view, float x, float y) {
    if (clickOptionId != HIGHLIGHT_NONE) {
      if (clickOptionId == HIGHLIGHT_PREVIEW) {
        previewResults = !previewResults;
        invalidateTextMediaReceiver();
        applyPoll(getPoll(), true);
        rebuildAndUpdateContent();
      } else if (clickOptionId == HIGHLIGHT_ADD) {
        addOption(view);
      } else if (clickOptionId == HIGHLIGHT_EXPLANATION) {
        showExplanation(view);
      } else if (clickOptionId == HIGHLIGHT_BUTTON) {
        if (button.singleton() != null) {
          if (isScheduled()) {
            showContentHint(view, (targetView, outRect) -> {
              int startY = getQuestionTitleHeight();
              startY += Screen.dp(18f);
              for (OptionEntry options : options) {
                startY += Math.max(Screen.dp(46f), options.text != null ? options.text.getHeight() : 0) + Screen.separatorSize();
              }
              outRect.set(0, startY, getContentWidth(), getContentHeight());
            }, R.string.ErrorScheduled);
          } else {
            final int itemId = button.singleton().item.id;
            if (itemId == R.id.btn_vote) {
              IntList selectedOptions = new IntList(this.options.length);
              IntList currentOptions = new IntList(selectedOptions.size());
              int optionId = 0;
              for (OptionEntry entry : options) {
                if (entry.isSelected()) {
                  selectedOptions.append(optionId);
                }
                if (getPoll().options[optionId].isBeingChosen) {
                  currentOptions.append(optionId);
                }
                optionId++;
              }
              int[] selectedOptionIds = selectedOptions.get();
              int[] currentOptionIds = currentOptions.get();
              if (!Config.PROTECT_ANONYMOUS_VOTING || isAnonymous() || messagesController().callNonAnonymousProtection(msg.id + R.id.btn_vote, this, makeVoteButtonLocationProvider(true))) {
                Tdlib.ResultHandler<TdApi.Ok> handler = (ok, error) -> {
                  if (error != null) {
                    runOnUiThreadOptional(() -> {
                      showContentHint(view, makeVoteButtonLocationProvider(false), TD.toFormattedText(TD.toErrorString(error), false));
                    });
                  }
                };
                if (Arrays.equals(selectedOptionIds, currentOptionIds)) {
                  tdlib.send(new TdApi.SetPollAnswer(msg.chatId, msg.id, null), handler);
                } else {
                  tdlib.send(new TdApi.SetPollAnswer(msg.chatId, msg.id, selectedOptionIds), handler);
                }
              }
            } else if (itemId == R.id.btn_viewResults && canGetVoters()) {
              PollResultsController c = new PollResultsController(context(), tdlib());
              c.setArguments(new PollResultsController.Args(getPoll(), msg.chatId, msg.id));
              navigateTo(c);
            }
          }
        }
      } else if (isScheduled()) {
        final int selectedOptionId = clickOptionId;
        showContentHint(view, (targetView, outRect) -> {
          int startY = getQuestionTitleHeight();
          startY += Screen.dp(18f);
          for (int i = 0; i < options.length; i++) {
            final int optionId = findOptionId(i, displayOrder);
            OptionEntry option = options[optionId];
            int optionHeight = getOptionHeight(option.text);
            if (selectedOptionId == optionId) {
              int progressCx = Screen.dp(12f);
              int progressCy = startY + Screen.dp(22f);
              int progressRadius = Screen.dp(9f);
              outRect.set(progressCx - progressRadius, progressCy - progressRadius, progressCx + progressRadius, progressCy + progressRadius);
              return;
            }
            startY += optionHeight;
          }
          outRect.set(0, 0, 0, 0);
        }, R.string.ErrorScheduled);
      } else if (getPoll().voteRestrictionReason != null) {
        TdApi.PollVoteRestrictionReason reason = getPoll().voteRestrictionReason;
        int stringId;
        if (reason instanceof TdApi.PollVoteRestrictionReasonCountryRestricted) {
          stringId = R.string.PollVoteRestrictedCountry;
        } else if (reason instanceof TdApi.PollVoteRestrictionReasonMembershipRequired) {
          stringId = R.string.PollVoteRestrictedMembership;
        } else if (reason instanceof TdApi.PollVoteRestrictionReasonScheduled) {
          stringId = R.string.ErrorScheduled;
        } else {
          stringId = R.string.PollVoteRestricted;
        }
        String restriction = Lang.getString(stringId);
        if (reason instanceof TdApi.PollVoteRestrictionReasonCountryRestricted) {
          String country = ((TdApi.PollVoteRestrictionReasonCountryRestricted) reason).countryCode;
          restriction += "\n" + new java.util.Locale("", country).getDisplayCountry();
        } else if (reason instanceof TdApi.PollVoteRestrictionReasonMembershipRequired) {
          long chatId = ((TdApi.PollVoteRestrictionReasonMembershipRequired) reason).chatId;
          restriction += "\n" + tdlib.chatTitle(chatId);
        }
        showContentHint(view, makeButtonLocationProvider(clickOptionId, false), TD.toFormattedText(restriction, false));
      } else if (!canVote(true)) {
        final int selectedOptionId = clickOptionId;
        showContentHint(view, (targetView, outRect) -> {
          int startY = getQuestionTitleHeight();
          startY += Screen.dp(18f);
          for (int i = 0; i < options.length; i++) {
            final int optionId = findOptionId(i, displayOrder);
            OptionEntry option = options[optionId];
            int optionHeight = getOptionHeight(option.text);
            if (selectedOptionId == optionId) {
              startY += Screen.dp(15f);
              outRect.set(Screen.dp(34f), startY, Screen.dp(34f) + (option.text != null ? option.text.getLineWidth(0) : 0), startY + (option.text != null ? option.text.getLineHeight() : 0));
              return;
            }
            startY += optionHeight;
          }
          outRect.set(0, 0, 0, 0);
        }, TD.toFormattedText(getCounter(getPoll(), getPoll().options[selectedOptionId].voterCount), false));
      } else if (isMultiChoicePoll()) {
        selectUnselect(clickOptionId);
      } else {
        chooseOption(view, clickOptionId);
      }
      clickOptionId = HIGHLIGHT_NONE;
    }
  }

  private int getOptionHeight (@Nullable TextWrapper text) {
    if (text == null) {
      return Screen.dp(46f);
    }
    return Math.max(
      Screen.dp(46f),
      Math.max(
        Screen.dp(8f),
        (Screen.dp(46f) / 2 - text.getLineHeight() / 2)
      ) + text.getHeight() + Screen.dp(12f)
    ) + Screen.separatorSize();
  }

  private void chooseOption (final View view, final int optionId) {
    if (!Config.PROTECT_ANONYMOUS_VOTING || isAnonymous() || messagesController().callNonAnonymousProtection(msg.id + optionId, this, makeButtonLocationProvider(optionId, true))) {
      Tdlib.ResultHandler<TdApi.Ok> handler = (ok, error) -> {
        if (error != null) {
          runOnUiThreadOptional(() -> {
            showContentHint(view, makeButtonLocationProvider(optionId, false), TD.toFormattedText(TD.toErrorString(error), false));
          });
        }
      };
      if (getPoll().options[optionId].isBeingChosen) {
        tdlib.send(new TdApi.SetPollAnswer(msg.chatId, msg.id, null), handler);
      } else {
        tdlib.send(new TdApi.SetPollAnswer(msg.chatId, msg.id, new int[] {optionId}), handler);
      }
    }
  }

  private void selectUnselect (final int optionId) {
    if (options[optionId].isSelected == null) {
      options[optionId].isSelected = new BoolAnimator(ANIMATOR_SELECT, this, AnimatorUtils.DECELERATE_INTERPOLATOR, 165l);
    }
    options[optionId].isSelected.toggleValue(needAnimateChanges());
    updateButtonState(needAnimateChanges());
  }

  private void addOption (View view) {
    if (!canAddOption()) {
      return;
    }
    final TdApi.InputPollMedia[] selectedMedia = {null};
    controller().openInputAlert(Lang.getString(R.string.PollAddOption),
      Lang.getString(R.string.PollOption), R.string.Add, R.string.Cancel, null, null,
      (input, result) -> {
        String option = result.trim();
        if (option.isEmpty() || option.codePointCount(0, option.length()) > 100) {
          input.setInErrorState(true);
          return false;
        }
        if (!canAddOption()) {
          return false;
        }
        TdApi.InputPollOption newOption = new TdApi.InputPollOption(
          new TdApi.FormattedText(option, null), selectedMedia[0]
        );
        tdlib.send(new TdApi.AddPollOption(msg.chatId, msg.id, newOption), (ok, error) -> {
          if (error != null) {
            runOnUiThreadOptional(() -> showContentHint(view,
              makeVoteButtonLocationProvider(false),
              TD.toFormattedText(TD.toErrorString(error), false)));
          }
        });
        return true;
      }, true, layout -> {
        android.widget.Button attach = new android.widget.Button(context());
        attach.setText(Lang.getString(R.string.PollMediaAttach));
        attach.setOnClickListener(v -> PollMediaPicker.show(controller(), msg.chatId, true,
          (media, label) -> {
            selectedMedia[0] = media;
            attach.setText(label);
          }));
        layout.addView(attach);
      }, null);
  }

  private int findOptionIndex (String stableId) {
    String[] ids = new String[getPoll().options.length];
    for (int i = 0; i < ids.length; i++) {
      ids[i] = getPoll().options[i].id;
    }
    return PollOptionSelection.find(ids, stableId);
  }

  private boolean canGetVoters () {
    TdApi.Poll poll = getPoll();
    return PollResultPolicy.canGetVoters(poll.isAnonymous, poll.canSeeResults, poll.canGetVoters);
  }

  private void showOptionError (View view, String stableId, TdApi.Error error) {
    runOnUiThreadOptional(() -> {
      int index = findOptionIndex(stableId);
      if (index >= 0 && !isDestroyed()) {
        showContentHint(view, makeButtonLocationProvider(index, false),
          TD.toFormattedText(TD.toErrorString(error), false));
      }
    });
  }

  private void showOptionMenu (View view, int originalIndex) {
    String stableId = getPoll().options[originalIndex].id;
    tdlib.send(new TdApi.GetPollOptionProperties(msg.chatId, msg.id, stableId),
      (properties, error) -> runOnUiThreadOptional(() -> {
        int index = findOptionIndex(stableId);
        if (index < 0 || isDestroyed()) {
          return;
        }
        if (error != null) {
          showOptionError(view, stableId, error);
          return;
        }
        TdApi.PollOption option = getPoll().options[index];
        IntList ids = new IntList(8);
        List<String> titles = new ArrayList<>();
        if (properties.canBeReplied) {
          ids.append(1);
          titles.add(Lang.getString(R.string.Reply));
        }
        if (canBeSaved() && !Td.isEmpty(option.text)) {
          ids.append(2);
          titles.add(Lang.getString(R.string.Copy));
        }
        if (properties.canGetLink) {
          ids.append(3);
          titles.add(Lang.getString(R.string.CopyLink));
        }
        if ((getPoll().allowsRevoting || !TD.hasAnswer(getPoll())) &&
            !getPoll().isClosed && getPoll().voteRestrictionReason == null) {
          ids.append(4);
          titles.add(Lang.getString(option.isChosen ? R.string.RetractVote : R.string.Vote));
        }
        if (properties.canBeDeleted) {
          ids.append(5);
          titles.add(Lang.getString(R.string.PollDeleteOption));
        }
        if (option.author != null) {
          ids.append(6);
          titles.add(Lang.getString(R.string.PollOptionAddedBy, tdlib.senderName(option.author),
            Lang.getTimestamp(option.additionDate, TimeUnit.SECONDS)));
        }
        if (canGetVoters()) {
          ids.append(7);
          titles.add(Lang.getString(R.string.PollResultsTitle));
        }
        controller().showOptions(TD.toCharSequence(option.text), ids.get(),
          titles.toArray(new String[0]), (v, id) -> performOptionAction(view, stableId, id));
      }));
  }

  private boolean performOptionAction (View view, String stableId, int action) {
    int index = findOptionIndex(stableId);
    if (index < 0 || isDestroyed()) {
      return true;
    }
    TdApi.PollOption option = getPoll().options[index];
    switch (action) {
      case 1:
        getMessageWithProperties(message ->
          messagesController().showReply(message, null, 0, stableId, true, true));
        break;
      case 2:
        if (canBeSaved()) {
          UI.copyText(option.text.text, R.string.CopiedText);
        }
        break;
      case 3:
        tdlib.send(new TdApi.GetMessageLink(msg.chatId, msg.id, 0, 0, stableId, false, false),
          (link, error) -> runOnUiThreadOptional(() -> {
            if (error == null) {
              UI.copyText(link.link, R.string.CopiedLink);
            } else {
              showOptionError(view, stableId, error);
            }
          }));
        break;
      case 4:
        voteOption(view, stableId, index);
        break;
      case 5:
        tdlib.send(new TdApi.DeletePollOption(msg.chatId, msg.id, stableId), (ok, error) -> {
          if (error != null) {
            showOptionError(view, stableId, error);
          }
        });
        break;
      case 6:
        tdlib.ui().openSenderProfile(controller(), option.author, new TdlibUi.UrlOpenParameters());
        break;
      case 7:
        if (canGetVoters()) {
          PollResultsController results = new PollResultsController(context(), tdlib);
          results.setArguments(new PollResultsController.Args(getPoll(), msg.chatId, msg.id, stableId));
          navigateTo(results);
        }
        break;
    }
    return true;
  }

  private void voteOption (View view, String stableId, int index) {
    TdApi.Poll poll = getPoll();
    if ((!poll.allowsRevoting && TD.hasAnswer(poll)) ||
        poll.isClosed || poll.voteRestrictionReason != null) {
      return;
    }
    boolean[] chosen = new boolean[poll.options.length];
    String[] stableIds = new String[chosen.length];
    for (int i = 0; i < chosen.length; i++) {
      chosen[i] = poll.options[i].isChosen;
      stableIds[i] = poll.options[i].id;
    }
    int[] selected = PollOptionSelection.toggle(stableIds, chosen, stableId,
      poll.allowsMultipleAnswers);
    if (selected == null) {
      return;
    }
    if (!Config.PROTECT_ANONYMOUS_VOTING || isAnonymous() ||
        messagesController().callNonAnonymousProtection(msg.id + index, this,
          makeButtonLocationProvider(index, true))) {
      tdlib.send(new TdApi.SetPollAnswer(msg.chatId, msg.id, selected), (ok, error) -> {
        if (error != null) {
          showOptionError(view, stableId, error);
        }
      });
    }
  }

  private static final int HIGHLIGHT_PREVIEW = -4, HIGHLIGHT_ADD = -5;
  private static final int HIGHLIGHT_NONE = -1;
  private static final int HIGHLIGHT_BUTTON = -2;
  private static final int HIGHLIGHT_EXPLANATION = -3;
  private int highlightOptionId = HIGHLIGHT_NONE;

  private void setHighlightOption (int optionId, View view, float x, float y) {
    highlightGeneration++;
    if (highlightOptionId != optionId) {
      this.highlightOptionId = optionId;
      invalidate();
    }
  }

  private TooltipOverlayView.LocationProvider makeVoteButtonLocationProvider (boolean needOffset) {
    return (targetView, outRect) -> {
      int startY = getQuestionTitleHeight() + Screen.dp(23f);
      for (OptionEntry option : options) {
        int optionHeight = getOptionHeight(option.text);
        startY += optionHeight;
      }
      outRect.set(0, startY, getContentMaxWidth(), startY + Screen.dp(50));
      if (needOffset) {
        outRect.offset(getContentX(), getContentY());
      }
    };
  }

  private TooltipOverlayView.LocationProvider makeButtonLocationProvider (int selectedOptionId, boolean needOffset) {
    return (targetView, outRect) -> {
      int startY = getQuestionTitleHeight();
      for (int i = 0; i < options.length; i++) {
        final int optionId = findOptionId(i, displayOrder);
        OptionEntry option = options[optionId];
        int optionHeight = getOptionHeight(option.text);
        if (selectedOptionId == optionId) {
          startY += Screen.dp(15f + 12f);
          outRect.set(Screen.dp(0f), startY, Screen.dp(24f), startY + option.text.getLineHeight());
          if (needOffset) {
            outRect.offset(getContentX(), getContentY());
          }
          return;
        }
        startY += optionHeight;
      }
      outRect.set(0, 0, 0, 0);
    };
  }

  private TdApi.FormattedText[] translatedTexts;

  @Nullable
  @Override
  public TdApi.FormattedText getTextToTranslateImpl () {
    if (state == null || state.poll == null) {
      return null;
    }
    // FIXME: proper workaround for "•"
    TdApi.FormattedText concatenatedText = Td.isEmpty(state.poll.question) ? state.poll.question : new TdApi.FormattedText(
      state.poll.question.text.replaceAll("•", " "),
      state.poll.question.entities
    );
    for (TdApi.PollOption option : state.poll.options) {
      concatenatedText = Td.concat(
        concatenatedText,
        new TdApi.FormattedText("\n\n• ", new TdApi.TextEntity[0]),
        Td.isEmpty(option.text) ? option.text : new TdApi.FormattedText(
          option.text.text.replaceAll("•", " "),
          option.text.entities
        )
      );
    }
    if (messagePoll != null && !Td.isEmpty(messagePoll.description)) {
      concatenatedText = Td.concat(concatenatedText, new TdApi.FormattedText("\n\n• ", null),
        new TdApi.FormattedText(messagePoll.description.text.replace("•", " "),
          messagePoll.description.entities));
    }
    return concatenatedText;
  }

  @Override
  protected void setTranslationResult (@Nullable TdApi.FormattedText text) {
    if (text != null) {
      translatedTexts = Td.split(text, "•");
      int expectedParts = state.options.length + 1 +
        (!Td.isEmpty(messagePoll.description) ? 1 : 0);
      if (translatedTexts.length != expectedParts) {
        translatedTexts = null;
      }
    } else {
      translatedTexts = null;
    }
    applyPoll(getPoll(), true);
    rebuildAndUpdateContent();
    invalidateTextMediaReceiver();
    super.setTranslationResult(text);
  }
}
