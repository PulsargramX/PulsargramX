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
 * File created on 28/02/2019
 */
package org.thunderdog.challegram.ui;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.content.res.Resources;
import androidx.collection.SparseArrayCompat;
import org.thunderdog.challegram.component.chat.MediaPreview;
import org.thunderdog.challegram.loader.ComplexReceiver;
import org.thunderdog.challegram.util.DrawableProvider;
import android.text.InputFilter;
import android.text.Spanned;
import android.text.style.CharacterStyle;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.attach.CustomItemAnimator;
import org.thunderdog.challegram.component.attach.PollMediaPicker;
import org.thunderdog.challegram.component.sticker.TGStickerObj;
import org.thunderdog.challegram.emoji.EmojiSpan;
import org.thunderdog.challegram.widget.MaterialEditText;
import org.thunderdog.challegram.widget.EmojiLayout;
import org.thunderdog.challegram.widget.PopupLayout;
import org.thunderdog.challegram.util.text.Text;
import tgx.td.JSON;
import org.thunderdog.challegram.component.base.SettingView;
import org.thunderdog.challegram.component.chat.MessagesManager;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.emoji.Emoji;
import org.thunderdog.challegram.navigation.NavigationController;
import org.thunderdog.challegram.navigation.NavigationStack;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibUi;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.tool.Keyboard;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Strings;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.util.CharacterStyleFilter;
import org.thunderdog.challegram.util.HapticMenuHelper;
import org.thunderdog.challegram.v.CustomRecyclerView;
import org.thunderdog.challegram.widget.CheckBoxView;
import org.thunderdog.challegram.widget.FillingDecoration;
import org.thunderdog.challegram.widget.MaterialEditTextGroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.text.CodePointCountFilter;
import me.vkryl.android.text.RestrictFilter;
import me.vkryl.core.StringUtils;
import me.vkryl.core.collection.IntList;
import me.vkryl.core.lambda.RunnableData;
import tgx.td.Td;
import tgx.td.TdConstants;

public class CreatePollController extends RecyclerViewController<CreatePollController.Args> implements View.OnClickListener, SettingsAdapter.TextChangeListener {
  public static class Args {
    public final long chatId;
    public final TdApi.MessageTopic messageTopicId;
    public final TdApi.InputSuggestedPostInfo inputSuggestedPostInfo;
    public final Callback callback;
    public final boolean forceRegular, forceQuiz;

    public Args (long chatId, @Nullable TdApi.MessageTopic messageTopicId, TdApi.InputSuggestedPostInfo inputSuggestedPostInfo, Callback callback) {
      this(chatId, messageTopicId, inputSuggestedPostInfo, callback, false, false);
    }

    public Args (long chatId, @Nullable TdApi.MessageTopic messageTopicId, TdApi.InputSuggestedPostInfo inputSuggestedPostInfo, Callback callback, boolean forceQuiz, boolean forceRegular) {
      if (callback == null)
        throw new IllegalArgumentException();
      this.chatId = chatId;
      this.messageTopicId = messageTopicId;
      this.inputSuggestedPostInfo = inputSuggestedPostInfo;
      this.callback = callback;
      this.forceRegular = forceRegular;
      this.forceQuiz = forceQuiz;
    }
  }

  public interface Callback {
    boolean onSendPoll (CreatePollController context, long chatId, @Nullable TdApi.MessageTopic topicId, TdApi.InputMessagePoll poll, TdApi.MessageSendOptions sendOptions, RunnableData<TdApi.Message> after);
    boolean areScheduledOnly (CreatePollController context);
    TdApi.ChatList provideChatList (CreatePollController context);
  }

  public CreatePollController (@NonNull Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public int getId () {
    return R.id.controller_createPoll;
  }

  @Override
  public CharSequence getName () {
    return Lang.getString(isQuiz ? R.string.CreateQuiz : R.string.CreatePoll);
  }

  @Override
  public boolean passNameToHeader() {
    return true;
  }

  private int descriptionMaxLength = 300;
  private int selectedOpenPeriod;
  private int selectedCloseDate;
  private final Map<ListItem, TdApi.InputPollMedia> attachedMedia = new LinkedHashMap<>();
  private final Map<ListItem, CharSequence> mediaLabels = new LinkedHashMap<>();
  private final Map<ListItem, MediaPreview> mediaPreviews = new LinkedHashMap<>();
  private ItemTouchHelper reorderHelper;
  private SettingsAdapter adapter;
  private FillingDecoration decoration;
  private ListItem addItem, itemsListHint;
  private ListItem questionItem, descriptionItem, descriptionHintItem, explanationItem, explanationHintItem;

  @Override
  protected void onCreateView (Context context, CustomRecyclerView recyclerView) {
    String cachedConfig = tdlib.settings().getApplicationConfig();
    if (!StringUtils.isEmpty(cachedConfig)) readDescriptionLimit(JSON.parse(cachedConfig));
    tdlib.send(new TdApi.GetApplicationConfig(), (result, error) -> runOnUiThreadOptional(() -> {
      if (error == null && readDescriptionLimit(result) && adapter != null) {
        int index = adapter.indexOfView(descriptionItem);
        if (index != -1) adapter.notifyItemChanged(index);
        checkSend();
      }
    }));
    adapter = new SettingsAdapter(this) {
      @Override
      protected void modifyEditText (ListItem item, ViewGroup parent, MaterialEditTextGroup editText) {
        final int itemId = item.getId();
        if (itemId == R.id.text_subtitle || itemId == R.id.text_explanation) {
          boolean isExplanation = itemId == R.id.text_explanation;
          editText.addLengthCounter(false);
          editText.setEmptyHint(isExplanation ? R.string.QuizExplanationEmpty : R.string.PollDescriptionEmpty);
          editText.setHint(isExplanation ? R.string.QuizExplanation : R.string.PollDescription);
          editText.getEditText().setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
          Views.setSingleLine(editText.getEditText(), false);
          editText.setMaxLength(isExplanation ? TdConstants.MAX_QUIZ_EXPLANATION_LENGTH : descriptionMaxLength);
          editText.setAlwaysActive(true);
          editText.getEditText().setLineDisabled(true);
        } else if (itemId == R.id.title) {
          editText.setEmptyHint(R.string.PollQuestionEmpty);
          editText.getEditText().setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
          Views.setSingleLine(editText.getEditText(), false);
          editText.addLengthCounter(false);
          editText.setMaxLength(TdConstants.MAX_POLL_QUESTION_LENGTH);
          editText.setAlwaysActive(true);
          editText.addCheckbox().setEnabled(true);
          editText.getEditText().setLineDisabled(true);
        } else if (itemId == R.id.optionAdd) {
          editText.setCheckboxVisible(isQuiz, !isMultiAnswers(),false);
          editText.addCheckbox().setEnabled(false);
          editText.getEditText().setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
          Views.setSingleLine(editText.getEditText(), false);
          editText.getEditText().setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_NEXT);
          editText.setNeedNextButton(v -> true);
        } else if (itemId == R.id.option) {
          editText.setCheckboxVisible(isQuiz,  !isMultiAnswers(),false);
          editText.setCheckboxSelected(correctOptionItems.contains(item), false);
          editText.getEditText().setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
          Views.setSingleLine(editText.getEditText(), false);
          editText.setAlwaysActive(true);
          editText.getEditText().setLineDisabled(true);
          editText.getEditText().setBackspaceListener((v, text, selectionStart, selectionEnd) -> {
            if (options.size() > 1 && (text.length() == 0 || text.toString().trim().isEmpty())) {
              removeOption((ListItem) ((ViewGroup) v.getParent().getParent()).getTag());
              return true;
            }
            return false;
          });
          editText.setNeedNextButton((v) -> {
            ListItem listItem = (ListItem) ((ViewGroup) v.getParent()).getTag();
            if (listItem != null && listItem.getId() == R.id.option) {
              int i = adapter.indexOfView(listItem);
              if (i != -1 && i + 2 < adapter.getItems().size()) {
                int id = adapter.getItems().get(i + 2).getId();
                return id == R.id.option || id == R.id.optionAdd;
              }
            }
            return false;
          });
          editText.getEditText().setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_NEXT);
        }
        if (itemId == R.id.title || itemId == R.id.option || itemId == R.id.text_subtitle || itemId == R.id.text_explanation) {
          addEditorControls(item, editText);
        }
      }

      @Override
      protected void onEditTextCheckBoxClick (ListItem item, ViewGroup parent, MaterialEditTextGroup editText, CheckBoxView checkBoxVIew) {
        if (!getDoneButton().isInProgress() && item.getId() == R.id.option) {
          toggleCorrectOptionEnabled(item);
        }
      }

      @Override
      public void onFocusChanged (MaterialEditTextGroup v, boolean isFocused) {
        super.onFocusChanged(v, isFocused);
        if (isFocused && ((ViewGroup) v.getParent()).getId() == R.id.optionAdd) {
          addOption();
        }
      }

      @Override
      protected void setValuedSetting(ListItem item, SettingView view, boolean isUpdate) {
        int itemId = item.getId();
        if (
          itemId == R.id.btn_pollSetting_duration ||
          itemId == R.id.btn_pollSetting_hideResults ||
          itemId == R.id.btn_pollSetting_multiAnswers ||
          itemId == R.id.btn_pollSetting_revoting ||
          itemId == R.id.btn_pollSetting_shuffle ||
          itemId == R.id.btn_pollSetting_members ||
          itemId == R.id.btn_pollSetting_countries ||
          itemId == R.id.btn_pollSetting_showVoters ||
          itemId == R.id.btn_pollSetting_quiz
        ) {
          view.getToggler().setRadioEnabled(item.getBoolValue(), isUpdate);
        } else if (
          itemId == R.id.btn_pollSetting_additions
        ) {
          view.getToggler().setRadioEnabled(!isQuiz && item.getBoolValue(), isUpdate);
        }

        if (itemId == R.id.btn_pollSetting_duration) {
          view.setData(selectedCloseDate != 0 ? Lang.getDate(selectedCloseDate, TimeUnit.SECONDS) : selectedOpenPeriod != 0 ? Strings.buildDuration(selectedOpenPeriod) : Lang.getString(R.string.PollDurationUnlimited));
        } else if (itemId == R.id.btn_pollSetting_showVoters) {
          view.setData(item.getBoolValue() ? R.string.PollSettingShowVotersOn : R.string.PollSettingShowVotersOff);
        } else if (itemId == R.id.btn_pollSetting_multiAnswers) {
          view.setData(item.getBoolValue() ? R.string.PollSettingMultipleOn : R.string.PollSettingMultipleOff);
        } else if (itemId == R.id.btn_pollSetting_revoting) {
          view.setData(item.getBoolValue() ? R.string.PollSettingRevotingOn : R.string.PollSettingRevotingOff);
        } else if (itemId == R.id.btn_pollSetting_shuffle) {
          view.setData(item.getBoolValue() ? R.string.PollSettingShuffleOn : R.string.PollSettingShuffleOff);
        } else if (itemId == R.id.btn_pollSetting_members) {
          view.setData(item.getBoolValue() ? R.string.PollSettingMembersOn : R.string.PollSettingMembersOff);
        } else if (itemId == R.id.btn_pollSetting_countries) {
          if (item.getBoolValue()) {
            view.setData(Lang.getString(R.string.PollSettingCountriesOnSpecific, Strings.join(Lang.getConcatSeparator(), selectedCountries.values(), country -> Emoji.getEmojiFlagFromCountry(country.countryCode) + " " + country.countryCode)));
          } else {
            view.setData(item.getBoolValue() ? R.string.PollSettingCountriesOn : R.string.PollSettingCountriesOff);
          }
        } else if (itemId == R.id.btn_pollSetting_hideResults) {
          view.setData(item.getBoolValue() ? R.string.PollSettingHideResultsOn : R.string.PollSettingHideResultsOff);
        } else if (itemId == R.id.btn_pollSetting_quiz) {
          view.setEnabledAnimated(canChangePollType(), isUpdate);
          view.setData(item.getBoolValue() ? R.string.PollSettingQuizOn : R.string.PollSettingQuizOff);
        } else if (itemId == R.id.btn_pollSetting_additions) {
          view.setEnabledAnimated(!isQuiz, isUpdate);
          view.setData(!isQuiz && item.getBoolValue() ? R.string.PollSettingAddingOn : R.string.PollSettingAddingOff);
        }
      }
    };
    adapter.setTextChangeListener(this);

    List<ListItem> items = new ArrayList<>();

    decoration = new FillingDecoration(recyclerView, this) {
      @Override
      public void getItemOffsets (@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        int i = parent.getChildAdapterPosition(view);
        outRect.top = view.getId() == R.id.text_subtitle || view.getId() == R.id.text_explanation ? Screen.dp(10f) : 0;
        outRect.bottom = i != -1 && i == adapter.getItemCount() - 1 ? Screen.dp(56f) : 0;
      }
    };
    decoration.addId(R.id.text_subtitle);
    decoration.addId(R.id.text_explanation);

    items.add(questionItem = new ListItem(ListItem.TYPE_EDITTEXT, R.id.title, 0, R.string.PollQuestion));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));

    decoration.addId(R.id.title);

    items.add(new ListItem(ListItem.TYPE_HEADER, R.id.text_title, 0, isQuiz ? R.string.QuizOptions : R.string.PollOptions));
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));

    items.add(createNewOption());
    items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
    items.add(addItem = new ListItem(ListItem.TYPE_EDITTEXT_POLL_OPTION_ADD, R.id.optionAdd).setInputFilters(new InputFilter[] {new CodePointCountFilter(0)}));
    decoration.addRange(items.size() - 3, items.size());

    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    items.add(itemsListHint = new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, Lang.plural(R.string.PollOptionsLimit, tdlib.options().pollAnswerCountMax - options.size()), false));

    final boolean isChannel = tdlib.isChannel(getArgumentsStrict().chatId);

    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    int settingCount = 0;
    if (!isChannel) {
      items.add(settingShowVoters = new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT_WITH_TOGGLER, R.id.btn_pollSetting_showVoters, 0, R.string.PollSettingShowVoters).setBoolValue(true));
      settingCount++;
    }

    if (!getArgumentsStrict().forceQuiz) {
      if (settingCount > 0)
        items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
      items.add(settingMultiAnswers = new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT_WITH_TOGGLER, R.id.btn_pollSetting_multiAnswers, 0, R.string.PollSettingMultiple));
      settingCount++;
    }

    if (!getArgumentsStrict().forceQuiz && !isChannel) {
      if (settingCount > 0)
        items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
      items.add(settingAdditions = new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT_WITH_TOGGLER, R.id.btn_pollSetting_additions, 0, R.string.PollSettingAdding));
      settingCount++;
    }

    if (settingCount > 0)
      items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
    items.add(settingRevoting = new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT_WITH_TOGGLER, R.id.btn_pollSetting_revoting, 0, R.string.PollSettingRevoting).setBoolValue(!isQuiz));
    settingCount++;

    if (settingCount > 0)
      items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
    items.add(settingQuiz = new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT_WITH_TOGGLER, R.id.btn_pollSetting_quiz, 0, R.string.PollSettingQuiz).setBoolValue(isQuiz));
    settingCount++;

    if (settingCount > 0)
      items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
    items.add(settingShuffle = new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT_WITH_TOGGLER, R.id.btn_pollSetting_shuffle, 0, R.string.PollSettingShuffle).setBoolValue(true));
    settingCount++;

    {
      if (settingCount > 0)
        items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
      items.add(settingDuration = new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT_WITH_TOGGLER, R.id.btn_pollSetting_duration, 0, R.string.PollSettingDuration));
      settingCount++;
    }

    if (isChannel) {
      if (settingCount > 0)
        items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
      items.add(settingOnlyMembers = new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT_WITH_TOGGLER, R.id.btn_pollSetting_members, 0, R.string.PollSettingMembers));
      settingCount++;

      if (settingCount > 0)
        items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
      items.add(settingCountries = new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT_WITH_TOGGLER, R.id.btn_pollSetting_countries, 0, R.string.PollSettingCountries));
      settingCount++;
    }

    if (settingCount > 0)
      items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
    items.add(settingHideResults = new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT_WITH_TOGGLER, R.id.btn_pollSetting_hideResults, 0, R.string.PollSettingHideResults));
    settingCount++;

    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));

    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(descriptionItem = newDescriptionItem(false));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    items.add(descriptionHintItem = new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, isQuiz ? R.string.QuizDescriptionInfo : R.string.PollDescriptionInfo));

    if (isQuiz) {
      items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
      items.add(explanationItem = newDescriptionItem(true));
      items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
      items.add(explanationHintItem = new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.QuizExplanationInfo));
    }

    items.add(new ListItem(ListItem.TYPE_EMPTY_OFFSET_SMALL));

    adapter.setItems(items, false);
    adapter.setLockFocusOn(this, false);

    recyclerView.setAdapter(adapter);
    recyclerView.setItemAnimator(new CustomItemAnimator(AnimatorUtils.DECELERATE_INTERPOLATOR, 180l));
    recyclerView.addItemDecoration(decoration);
    reorderHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
      @Override
      public boolean isLongPressDragEnabled () { return false; }

      @Override
      public int getMovementFlags (@NonNull RecyclerView recycler, @NonNull RecyclerView.ViewHolder holder) {
        return makeMovementFlags(holder.itemView.getTag() instanceof ListItem && options.contains(holder.itemView.getTag()) ? ItemTouchHelper.UP | ItemTouchHelper.DOWN : 0, 0);
      }

      @Override
      public boolean onMove (@NonNull RecyclerView recycler, @NonNull RecyclerView.ViewHolder source, @NonNull RecyclerView.ViewHolder target) {
        if (getDoneButton().isInProgress()) return false;
        int from = options.indexOf(source.itemView.getTag());
        int to = options.indexOf(target.itemView.getTag());
        if (from < 0 || to < 0) return false;
        int sourcePosition = source.getBindingAdapterPosition();
        int targetPosition = target.getBindingAdapterPosition();
        if (sourcePosition < 0 || targetPosition < 0) return false;
        int direction = from < to ? 1 : -1;
        for (int index = from; index != to; index += direction) {
          ListItem moved = options.get(index);
          ListItem neighbor = options.get(index + direction);
          int first = adapter.indexOfView(moved);
          int second = adapter.indexOfView(neighbor);
          Collections.swap(options, index, index + direction);
          Collections.swap(adapter.getItems(), first, second);
          adapter.notifyItemMoved(first, second);
          adapter.notifyItemMoved(second - direction, first);
        }
        return true;
      }

      @Override
      public void onSwiped (@NonNull RecyclerView.ViewHolder holder, int direction) { }
    });
    reorderHelper.attachToRecyclerView(recyclerView);
  }

  private static class DraftMediaView extends View implements DrawableProvider {
    private final ComplexReceiver receiver = new ComplexReceiver(this);
    private final SparseArrayCompat<Drawable> drawables = new SparseArrayCompat<>();
    private final MediaPreview preview;
    private CharSequence label;

    DraftMediaView (Context context, @Nullable MediaPreview preview) {
      super(context);
      this.preview = preview;
      if (preview != null) preview.requestFiles(receiver, false);
    }

    @Override
    protected void onAttachedToWindow () {
      super.onAttachedToWindow();
      receiver.attach();
    }

    @Override
    protected void onDetachedFromWindow () {
      receiver.detach();
      super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw (Canvas canvas) {
      super.onDraw(canvas);
      if (preview != null) preview.draw(this, canvas, receiver, 0, 0, 1f);
      android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
      paint.setTextSize(Screen.dp(14));
      paint.setColor(org.thunderdog.challegram.theme.Theme.getColor(ColorId.text));
      if (label != null) {
        CharSequence text = android.text.TextUtils.ellipsize(label, new android.text.TextPaint(paint), Math.max(0, getWidth() - Screen.dp(60)), android.text.TextUtils.TruncateAt.END);
        canvas.drawText(text.toString(), Screen.dp(60), Screen.dp(28), paint);
      }
    }

    @Override
    public SparseArrayCompat<Drawable> getSparseDrawableHolder () { return drawables; }

    @Override
    public Resources getSparseDrawableResources () { return getResources(); }
  }

  private boolean readDescriptionLimit (@Nullable TdApi.Object object) {
    if (!(object instanceof TdApi.JsonValueObject)) return false;
    for (TdApi.JsonObjectMember member : ((TdApi.JsonValueObject) object).members) {
      if ("poll_caption_length_max".equals(member.key) && member.value instanceof TdApi.JsonValueNumber) {
        double value = ((TdApi.JsonValueNumber) member.value).value;
        if (value > 0 && value <= Integer.MAX_VALUE && descriptionMaxLength != (int) value) {
          descriptionMaxLength = (int) value;
          return true;
        }
      }
    }
    return false;
  }

  private void showDurationPicker () {
    IntList ids = new IntList(7);
    ArrayList<String> labels = new ArrayList<>();
    ids.append(R.id.btn_cancel);
    labels.add(Lang.getString(R.string.PollDurationUnlimited));
    for (int hours : new int[] {1, 3, 8, 24, 72}) {
      int seconds = hours * 3600;
      if (seconds <= tdlib.options().pollOpenPeriodMax) {
        ids.append(-seconds);
        labels.add(Strings.buildDuration(seconds));
      }
    }
    ids.append(R.id.btn_pollDurationCustom);
    labels.add(Lang.getString(R.string.PollDurationCustom));
    showOptions(Lang.getString(R.string.PollSettingDuration), ids.get(), labels.toArray(new String[0]), null, null, (view, id) -> {
      if (id == R.id.btn_pollDurationCustom) {
        showDateTimePicker(Lang.getString(R.string.PollDurationCustom), R.string.PollClosesTodayAt, R.string.PollClosesTomorrowAt, R.string.PollClosesDateAt, millis -> {
          long seconds = TimeUnit.MILLISECONDS.toSeconds(millis);
          long now = tdlib.currentTime(TimeUnit.SECONDS);
          if (seconds <= now || seconds - now > tdlib.options().pollOpenPeriodMax || seconds > Integer.MAX_VALUE) {
            showErrorTooltip(getDoneButton(), Lang.getString(R.string.PollDurationInvalid));
            return;
          }
          selectedOpenPeriod = 0;
          selectedCloseDate = (int) seconds;
          updateDuration();
        }, null);
      } else {
        selectedOpenPeriod = id < 0 ? -id : 0;
        selectedCloseDate = 0;
        updateDuration();
      }
      return true;
    });
  }

  private void updateDuration () {
    settingDuration.setBoolValue(selectedOpenPeriod != 0 || selectedCloseDate != 0);
    adapter.updateValuedSetting(settingDuration);
    checkSend();
  }

  private void setPollSending (boolean sending) {
    getDoneButton().setInProgress(sending);
    for (int i = 0; i < getRecyclerView().getChildCount(); i++) {
      View row = getRecyclerView().getChildAt(i);
      if (row instanceof ViewGroup && ((ViewGroup) row).getChildCount() > 0 && ((ViewGroup) row).getChildAt(0) instanceof MaterialEditTextGroup) {
        ((MaterialEditTextGroup) ((ViewGroup) row).getChildAt(0)).getEditText().setEnabled(!sending);
      }
    }
  }

  private void addEditorControls (ListItem item, MaterialEditTextGroup group) {
    boolean hasMedia = item != questionItem;
    MaterialEditText edit = group.getEditText();
    edit.setEnabled(!getDoneButton().isInProgress());
    for (int id : new int[] {R.id.btn_pollMedia, R.id.menu_btn_emoji, R.id.btn_pollReorder, R.id.btn_pollMediaLabel}) {
      View existing = group.findViewById(id);
      if (existing instanceof DraftMediaView) {
        ComplexReceiver receiver = ((DraftMediaView) existing).receiver;
        removeDestroyListener(receiver);
        receiver.performDestroy();
      }
      if (existing != null) group.removeView(existing);
    }
    int controlsWidth = Screen.dp(item.getId() == R.id.option ? 108 : hasMedia ? 72 : 36);
    FrameLayout.LayoutParams textParams = (FrameLayout.LayoutParams) edit.getLayoutParams();
    textParams.rightMargin = controlsWidth;
    edit.setLayoutParams(textParams);
    ImageView emoji = editorButton(group, R.id.menu_btn_emoji, R.drawable.baseline_emoticon_outline_24, 0);
    emoji.setContentDescription(Lang.getString(R.string.AnimatedEmoji));
    emoji.setOnClickListener(view -> showEmojiPicker(edit));
    if (hasMedia) {
      ImageView media = editorButton(group, R.id.btn_pollMedia, R.drawable.deproko_baseline_attach_26, 36);
      media.setAlpha(attachedMedia.containsKey(item) ? 1f : .55f);
      media.setContentDescription(mediaLabels.containsKey(item) ? mediaLabels.get(item) : Lang.getString(R.string.PollMediaAttach));
      media.setOnClickListener(view -> {
        if (getDoneButton().isInProgress()) return;
        if (attachedMedia.containsKey(item)) {
          showOptions(mediaLabels.get(item), new int[] {R.id.btn_edit, R.id.btn_delete},
            new String[] {Lang.getString(R.string.PollMediaReplace), Lang.getString(R.string.PollMediaRemove)}, null, null, (option, id) -> {
              if (id == R.id.btn_delete) setAttachedMedia(item, null, null);
              else pickMedia(item);
              return true;
            });
        } else {
          pickMedia(item);
        }
      });
    }
    if (item.getId() == R.id.option) {
      ImageView drag = editorButton(group, R.id.btn_pollReorder, R.drawable.dotvhs_baseline_folders_reorder_24, 72);
      drag.setContentDescription(Lang.getString(R.string.PollReorderOption));
      drag.setOnTouchListener((view, event) -> {
        if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN && reorderHelper != null && !getDoneButton().isInProgress()) {
          View row = (View) group.getParent();
          RecyclerView.ViewHolder holder = getRecyclerView().findContainingViewHolder(row);
          if (holder != null) reorderHelper.startDrag(holder);
          return true;
        }
        return false;
      });
    }
    group.setPadding(group.getPaddingLeft(), group.getPaddingTop(), group.getPaddingRight(), attachedMedia.containsKey(item) ? Screen.dp(56) : 0);
    if (attachedMedia.containsKey(item)) {
      DraftMediaView preview = new DraftMediaView(context, mediaPreviews.get(item));
      addDestroyListener(preview.receiver);
      preview.setId(R.id.btn_pollMediaLabel);
      preview.setContentDescription(mediaLabels.get(item));
      FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Screen.dp(48), Gravity.BOTTOM);
      params.bottomMargin = -Screen.dp(52);
      group.addView(preview, params);
      preview.setOnClickListener(view -> {
        View button = group.findViewById(R.id.btn_pollMedia);
        if (button != null) button.performClick();
      });
      preview.label = mediaLabels.get(item);
    }
    if (item == descriptionItem || item == explanationItem) installFormatting(edit, item);
  }

  private ImageView editorButton (MaterialEditTextGroup group, int id, int icon, int margin) {
    ImageView view = new ImageView(context);
    view.setId(id);
    view.setImageResource(icon);
    view.setColorFilter(org.thunderdog.challegram.theme.Theme.getColor(ColorId.icon));
    view.setScaleType(ImageView.ScaleType.CENTER);
    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(Screen.dp(36), Screen.dp(40), Gravity.RIGHT | Gravity.TOP);
    params.topMargin = Screen.dp(20);
    params.rightMargin = Screen.dp(margin);
    group.addView(view, params);
    return view;
  }

  private void pickMedia (ListItem item) {
    hideSoftwareKeyboard();
    PollMediaPicker.show(this, getArgumentsStrict().chatId, item.getId() == R.id.option, new PollMediaPicker.Callback() {
      @Override
      public void onMediaSelected (TdApi.InputPollMedia media, CharSequence label) {
        onMediaSelected(media, label, null);
      }

      @Override
      public void onMediaSelected (TdApi.InputPollMedia media, CharSequence label, @Nullable MediaPreview preview) {
        if (!isDestroyed() && !getDoneButton().isInProgress() && (options.contains(item) || item == descriptionItem || item == explanationItem)) {
          if (preview != null) mediaPreviews.put(item, preview);
          else mediaPreviews.remove(item);
          setAttachedMedia(item, media, label);
        }
      }
    });
  }

  private void setAttachedMedia (ListItem item, @Nullable TdApi.InputPollMedia media, @Nullable CharSequence label) {
    if (media == null) {
      attachedMedia.remove(item);
      mediaLabels.remove(item);
      mediaPreviews.remove(item);
    } else {
      attachedMedia.put(item, media);
      mediaLabels.put(item, label);
    }
    int index = adapter.indexOfView(item);
    if (index != -1) adapter.notifyItemChanged(index);
    checkSend();
  }

  private void showEmojiPicker (MaterialEditText edit) {
    if (getDoneButton().isInProgress()) return;
    hideSoftwareKeyboard();
    showPopup(Lang.getString(R.string.AnimatedEmoji), true, Text.LINE_COUNT_UNLIMITED, (popup, wrap) -> {
      EmojiLayout emoji = new EmojiLayout(context);
      emoji.initWithMediasEnabled(this, false, new EmojiLayout.Listener() {
        @Override
        public long getOutputChatId () { return getArgumentsStrict().chatId; }

        @Override
        public void onEnterEmoji (String value) {
          if (isDestroyed() || getDoneButton().isInProgress()) return;
          edit.paste(new TdApi.FormattedText(value, null), false);
        }

        @Override
        public void onEnterCustomEmoji (TGStickerObj sticker) {
          if (isDestroyed() || getDoneButton().isInProgress()) return;
          if (!tdlib.hasPremium()) {
            tdlib.ui().showPremiumAlert(CreatePollController.this, edit, TdlibUi.PremiumFeature.CUSTOM_EMOJI);
            return;
          }
          TdApi.Sticker data = sticker.getSticker();
          if (data != null && !StringUtils.isEmpty(data.emoji)) {
            edit.paste(new TdApi.FormattedText(data.emoji, new TdApi.TextEntity[] {
              new TdApi.TextEntity(0, data.emoji.length(), new TdApi.TextEntityTypeCustomEmoji(sticker.getCustomEmojiId()))
            }), false);
          }
        }

        @Override
        public boolean isEmojiInputEmpty () { return edit.length() == 0; }

        @Override
        public void onDeleteEmoji () {
          edit.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL));
        }
      }, this, false);
      emoji.setForceHeight(Screen.dp(320));
      wrap.addView(emoji);
      me.vkryl.core.lambda.Destroyable dismiss = () -> popup.hideWindow(false);
      addDestroyListener(dismiss);
      popup.setDismissListener(new PopupLayout.DismissListener() {
        @Override
        public void onPopupDismissPrepare (PopupLayout layout) { }
        @Override
        public void onPopupDismiss (PopupLayout layout) {
          if (!isDestroyed()) removeDestroyListener(dismiss);
          emoji.destroy();
        }
      });
      return Screen.dp(320);
    }, null);
  }

  private void installFormatting (MaterialEditText edit, ListItem item) {
    edit.setCustomSelectionActionModeCallback(new ActionMode.Callback() {
      @Override
      public boolean onCreateActionMode (ActionMode mode, Menu menu) {
        mode.getMenuInflater().inflate(R.menu.text, menu);
        return true;
      }

      @Override
      public boolean onPrepareActionMode (ActionMode mode, Menu menu) { return false; }

      @Override
      public boolean onActionItemClicked (ActionMode mode, MenuItem menuItem) {
        int id = menuItem.getItemId();
        TdApi.TextEntityType type;
        if (id == R.id.btn_bold) type = new TdApi.TextEntityTypeBold();
        else if (id == R.id.btn_italic) type = new TdApi.TextEntityTypeItalic();
        else if (id == R.id.btn_underline) type = new TdApi.TextEntityTypeUnderline();
        else if (id == R.id.btn_strikethrough) type = new TdApi.TextEntityTypeStrikethrough();
        else if (id == R.id.btn_spoiler) type = new TdApi.TextEntityTypeSpoiler();
        else if (id == R.id.btn_monospace) type = new TdApi.TextEntityTypeCode();
        else if (id == R.id.btn_quote) type = new TdApi.TextEntityTypeBlockQuote();
        else if (id == R.id.btn_plain) type = null;
        else if (id == R.id.btn_link) {
          int start = Math.min(edit.getSelectionStart(), edit.getSelectionEnd());
          int end = Math.max(edit.getSelectionStart(), edit.getSelectionEnd());
          android.widget.EditText input = new android.widget.EditText(context);
          input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
          new android.app.AlertDialog.Builder(context).setTitle(Lang.getString(R.string.TextFormatLink)).setView(input)
            .setPositiveButton(Lang.getString(R.string.Done), (dialog, which) -> {
              String url = input.getText().toString().trim();
              if (!url.isEmpty() && start >= 0 && end <= edit.length()) {
                applyFormatting(edit, item, start, end, new TdApi.TextEntityTypeTextUrl(url));
              }
            }).setNegativeButton(Lang.getString(R.string.Cancel), null).show();
          return true;
        } else return false;
        applyFormatting(edit, item, Math.min(edit.getSelectionStart(), edit.getSelectionEnd()), Math.max(edit.getSelectionStart(), edit.getSelectionEnd()), type);
        return true;
      }

      @Override
      public void onDestroyActionMode (ActionMode mode) { }
    });
  }

  private void applyFormatting (MaterialEditText edit, ListItem item, int start, int end, @Nullable TdApi.TextEntityType type) {
    if (isDestroyed() || start < 0 || end <= start || getDoneButton().isInProgress()) return;
    if (type == null) {
      for (CharacterStyle span : edit.getText().getSpans(start, end, CharacterStyle.class)) {
        if (!(span instanceof EmojiSpan)) edit.getText().removeSpan(span);
      }
    } else {
      Object span = TD.toSpan(type, TD.TextEntityOption.ALLOW_INTERNAL);
      if (span != null) edit.getText().setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    item.setStringValue(org.thunderdog.challegram.v.EditText.nonModifiableCopy(edit.getText()));
    edit.invalidate();
    checkSend();
  }

  @Nullable
  private CharSequence splitPastedOptions (ListItem item, CharSequence source, int start, int end, Spanned dest, int dstart, int dend) {
    String pasted = source.subSequence(start, end).toString();
    if (pasted.indexOf('\n') == -1 && pasted.indexOf('\r') == -1) return null;
    ArrayList<CharSequence> lines = new ArrayList<>();
    for (int[] range : PollDraftRules.splitLines(source, start, end)) {
      lines.add(source.subSequence(range[0], range[1]));
    }
    if (lines.isEmpty()) return "";
    if (lines.size() > tdlib.options().pollAnswerCountMax - options.size() + 1) {
      tdlib.ui().post(() -> showErrorTooltip(getDoneButton(), Lang.getString(R.string.PollOptionsMax)));
      return dest.subSequence(dstart, dend);
    }
    if (lines.size() > 1) {
      tdlib.ui().post(() -> {
        int optionIndex = options.indexOf(item);
        if (optionIndex == -1 || isDestroyed() || getDoneButton().isInProgress()) return;
        for (int i = 1; i < lines.size(); i++) {
          int addPosition = adapter.indexOfView(addItem);
          if (addPosition == -1) break;
          ListItem next = createNewOption().setStringValue(lines.get(i));
          options.remove(next);
          options.add(optionIndex + i, next);
          int insertPosition = adapter.indexOfView(options.get(optionIndex + i - 1)) + 1;
          if (options.size() == tdlib.options().pollAnswerCountMax) {
            adapter.removeRange(addPosition - 1, 2);
          } else {
            decoration.lastRange()[1] += 2;
          }
          adapter.addItems(insertPosition, new ListItem(ListItem.TYPE_SEPARATOR_FULL), next);
        }
        updateLimit();
        checkSend();
      });
    }
    return lines.get(0);
  }

  private HapticMenuHelper currentMenu;

  @Override
  public void onFocus () {
    super.onFocus();
    if (currentMenu == null) {
      currentMenu = tdlib.ui().createSimpleHapticMenu(this, getArgumentsStrict().chatId, this::canSendPoll, () -> {
        TdApi.FormattedText explanation = getExplanation(false);
        return explanation != null && explanation.text.trim().length() <= TdConstants.MAX_QUIZ_EXPLANATION_LENGTH && Td.parseMarkdown(explanation);
      }, null, null, this::send, null)
              .attachToView(getDoneButton());
    }
  }


  private boolean isQuiz;
  private ListItem settingQuiz, settingShowVoters, settingMultiAnswers, settingRevoting, settingShuffle, settingHideResults, settingAdditions, settingDuration, settingOnlyMembers, settingCountries;
  private Set<ListItem> correctOptionItems = new LinkedHashSet<>();

  private void toggleCorrectOptionEnabled (final ListItem option) {
    boolean wasEnabled = correctOptionItems.contains(option);
    boolean multiAnswers = isMultiAnswers();
    boolean nowEnabled = !multiAnswers || !wasEnabled;
    setCorrectOptionEnabled(option, nowEnabled);
  }

  private void setCorrectOptionEnabled (final ListItem option, boolean nowEnabled) {
    boolean wasEnabled = correctOptionItems.contains(option);
    if (wasEnabled == nowEnabled) {
      return;
    }
    boolean multiAnswers = isMultiAnswers();
    if (!multiAnswers && nowEnabled) {
      while (!correctOptionItems.isEmpty()) {
        ListItem item = correctOptionItems.iterator().next();
        correctOptionItems.remove(item);
        setCheckboxActive(item, false);
      }
    }
    if (nowEnabled) {
      correctOptionItems.add(option);
    } else {
      correctOptionItems.remove(option);
    }
    setCheckboxActive(option, nowEnabled);
    checkSend();
  }

  private void setCheckboxActive (ListItem item, boolean isActive) {
    int adapterPosition = adapter.indexOfView(item);
    if (adapterPosition == -1) {
      return;
    }
    View view = getRecyclerView().getLayoutManager().findViewByPosition(adapterPosition);
    if (view != null && view.getTag() == item) {
      ((MaterialEditTextGroup) ((ViewGroup) view).getChildAt(0)).setCheckboxSelected(isActive, true);
    } else {
      adapter.notifyItemChanged(adapterPosition);
    }
  }

  @Override
  public void setArguments(Args args) {
    super.setArguments(args);
    this.isQuiz = args.forceQuiz;
  }

  private boolean canChangePollType () {
    return !(getArgumentsStrict().forceQuiz || getArgumentsStrict().forceRegular);
  }

  @Override
  public boolean performOnBackPressed (boolean fromTop, boolean commit) {
    if (hasUnsavedPoll()) {
      if (commit) {
        showOptions(Lang.getString(isQuiz ? R.string.QuizDiscardPrompt : R.string.PollDiscardPrompt), new int[] {R.id.btn_done, R.id.btn_cancel}, new String[] {Lang.getString(isQuiz ? R.string.QuizDiscard : R.string.PollDiscard), Lang.getString(R.string.Cancel)}, new int[] {OptionColor.RED, OptionColor.NORMAL}, new int[] {R.drawable.baseline_delete_forever_24, R.drawable.baseline_cancel_24}, (itemView, id) -> {
          if (id == R.id.btn_done) {
            navigateBack();
          }
          return true;
        });
      }
      return true;
    }
    return super.performOnBackPressed(fromTop, commit);
  }

  private static ListItem newDescriptionItem (boolean isQuizExplanation) {
    return new ListItem(ListItem.TYPE_EDITTEXT_NO_PADDING_REUSABLE, isQuizExplanation ? R.id.text_explanation : R.id.text_subtitle, 0, R.string.PollDescription);
  }

  private final Map<String, TdApi.CountryInfo> selectedCountries = new LinkedHashMap<>();

  @Override
  public void onClick (View v) {
    if (getDoneButton().isInProgress()) return;
    int itemId = v.getId();
    ListItem item = (ListItem) v.getTag();

    if (itemId == R.id.btn_pollSetting_duration) {
      showDurationPicker();
      return;
    }
    if (itemId == R.id.optionAdd) {
      addOption();
    } else if (itemId == R.id.btn_pollSetting_countries) {
      // Open country picker
      CountryController c = new CountryController(context, tdlib);
      c.setArguments(new CountryController.Args(new CountryController.Callback() {
        @Override
        public void onCountryUnselected (CountryController context, View view, TdApi.CountryInfo country) {
          selectedCountries.remove(country.countryCode);
          settingCountries.setBoolValue(!selectedCountries.isEmpty());
          adapter.updateValuedSettingById(R.id.btn_pollSetting_countries);
        }

        @Override
        public boolean onCountrySelected (CountryController context, View view, TdApi.CountryInfo country) {
          if (selectedCountries.size() >= tdlib.options().pollCountryCountMax) {
            context.showErrorTooltip(view, Lang.getMarkdownPlural(CreatePollController.this, R.string.CountryLimit, tdlib.options().pollCountryCountMax));
            return false;
          }
          selectedCountries.put(country.countryCode, country);
          settingCountries.setBoolValue(true);
          adapter.updateValuedSettingById(R.id.btn_pollSetting_countries);
          return true;
        }
      }, selectedCountries.values().toArray(new TdApi.CountryInfo[0])));
      navigateTo(c);
    } else if (
      itemId == R.id.btn_pollSetting_duration ||
      itemId == R.id.btn_pollSetting_hideResults ||
      itemId == R.id.btn_pollSetting_members ||
      itemId == R.id.btn_pollSetting_multiAnswers ||
      itemId == R.id.btn_pollSetting_shuffle ||
      itemId == R.id.btn_pollSetting_revoting ||
      itemId == R.id.btn_pollSetting_showVoters ||
      (itemId == R.id.btn_pollSetting_quiz && canChangePollType()) ||
      (itemId == R.id.btn_pollSetting_additions && !isQuiz)
    ) {
      boolean value = adapter.toggleView(v);

      item.setBoolValue(value);
      adapter.updateValuedSetting(item);

      if (itemId == R.id.btn_pollSetting_multiAnswers) {
        if (!value) {
          while (correctOptionItems.size() > 1) {
            setCorrectOptionEnabled(correctOptionItems.iterator().next(), false);
          }
        }
        updateCheckBoxes();
      } else if (itemId == R.id.btn_pollSetting_quiz) {
        isQuiz = value;

        if (value) {
          // Add items
          int index = adapter.indexOfView(descriptionItem);
          if (index != -1) {
            index = Math.max(0, index - 1);
            adapter.addItems(index,
              new ListItem(ListItem.TYPE_SHADOW_TOP),
              explanationItem != null ? explanationItem : (explanationItem = newDescriptionItem(true)),
              new ListItem(ListItem.TYPE_SHADOW_BOTTOM),
              explanationHintItem != null ? explanationHintItem : (explanationHintItem = new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.QuizExplanationInfo))
            );
          }
        } else {
          // Remove items
          int index = adapter.indexOfView(explanationItem);
          if (index != -1) {
            adapter.removeRange(index - 1, 4);
          }
        }

        if (isQuiz) {
          // Show hint
          int i = ((LinearLayoutManager) getRecyclerView().getLayoutManager()).findFirstVisibleItemPosition();
          if (i != RecyclerView.NO_POSITION) {
            i = adapter.indexOfViewById(R.id.option, i);
            i = i != -1 ? options.indexOf(adapter.getItem(i)) : -1;
            if (i != -1) {
              int firstVisibleOptionId = i;
              while (i < options.size() && StringUtils.isEmpty(StringUtils.trim(options.get(i).getCharSequenceValue()))) {
                i++;
              }
              if (i != options.size()) {
                firstVisibleOptionId = i;
              }
              i = adapter.indexOfView(options.get(firstVisibleOptionId));
              if (i != -1) {
                View view = getRecyclerView().getLayoutManager().findViewByPosition(i);
                if (view instanceof ViewGroup && view.getTag() == options.get(firstVisibleOptionId)) {
                  MaterialEditTextGroup editTextGroup = ((MaterialEditTextGroup) ((ViewGroup) view).getChildAt(0));
                  editTextGroup.showCheckboxHint(this, tdlib, R.string.QuizOptionHint);
                }
              }
            }
          }
        }

        if (settingAdditions != null) {
          adapter.updateValuedSetting(settingAdditions);
        }

        if (settingRevoting != null) {
          settingRevoting.setBoolValue(!value);
          adapter.updateValuedSetting(settingRevoting);
        }

        int titleIndex = adapter.indexOfViewById(R.id.text_title);
        if (titleIndex != -1 && adapter.getItems().get(titleIndex).setStringIfChanged(isQuiz ? R.string.QuizOptions : R.string.PollOptions)) {
          adapter.notifyItemChanged(titleIndex);
        }

        int descHintIndex = adapter.indexOfView(descriptionHintItem);
        if (descHintIndex != -1 && descriptionHintItem.setStringIfChanged(isQuiz ? R.string.QuizDescriptionInfo : R.string.PollDescriptionInfo)) {
          adapter.notifyItemChanged(descHintIndex);
        }

        descHintIndex = adapter.indexOfView(descriptionItem);
        if (descHintIndex != -1 && descriptionItem.setStringIfChanged(R.string.PollDescription)) {
          adapter.updateValuedSetting(descriptionItem);
        }

        if (headerView != null)
          headerView.updateTextTitle(getId(), getName());

        checkSend();

        updateCheckBoxes();
      }
    }
  }

  private void updateCheckBoxes () {
    int position = 0;
    for (ListItem otherItem : adapter.getItems()) {
      int otherItemId = otherItem.getId();
      if (otherItemId == R.id.option || otherItemId == R.id.optionAdd) {
        View view = getRecyclerView().getLayoutManager().findViewByPosition(position);
        if (view != null && view.getTag() == otherItem) {
          MaterialEditTextGroup editTextGroup = (MaterialEditTextGroup) ((ViewGroup) view).getChildAt(0);
          editTextGroup.setCheckboxVisible(isQuiz, !isMultiAnswers(), true);
        } else {
          adapter.notifyItemChanged(position);
        }
      }
      position++;
    }
  }

  @Override
  public int getRootColorId () {
    return ColorId.background;
  }

  private boolean isAdding;

  private boolean addOption () {
    final int maxPollOptionCount = tdlib.options().pollAnswerCountMax;
    if (getDoneButton().isInProgress() || options.size() >= maxPollOptionCount || isAdding)
      return false;
    int i = adapter.indexOfViewById(R.id.optionAdd);
    isAdding = true;
    int targetIndex;
    if (options.size() + 1 == maxPollOptionCount) {
      adapter.setItem(targetIndex = i, createNewOption());
    } else {
      adapter.getItems().add(i - 1, createNewOption());
      adapter.getItems().add(i - 1, new ListItem(ListItem.TYPE_SEPARATOR_FULL));
      decoration.lastRange()[1] += 2;
      targetIndex = i;
      adapter.notifyItemRangeInserted(i, 2);
      // getRecyclerView().invalidateItemDecorations();
    }
    updateLimit();
    tdlib.ui().postDelayed(() -> {
      isAdding = false;
      View view = getRecyclerView().getLayoutManager().findViewByPosition(targetIndex);
      if (view instanceof ViewGroup) {
        Keyboard.show(((MaterialEditTextGroup) ((ViewGroup) view).getChildAt(0)).getEditText());
      }
    }, 180);
    return true;
  }

  private void removeOption (ListItem option) {
    if (getDoneButton().isInProgress()) return;
    final int i = options.indexOf(option);
    if (i == -1)
      return;
    setCorrectOptionEnabled(option, false);
    options.remove(i);
    attachedMedia.remove(option);
    mediaLabels.remove(option);
    mediaPreviews.remove(option);
    boolean hideKeyboard = true;
    if (!options.isEmpty()) {
      int nextIndex = adapter.indexOfView(options.get(i > 0 ? i - 1 : 0));
      View view = getRecyclerView().getLayoutManager().findViewByPosition(nextIndex);
      if (view instanceof ViewGroup) {
        Keyboard.show(((MaterialEditTextGroup) ((ViewGroup) view).getChildAt(0)).getEditText());
        hideKeyboard = false;
      }
    }
    int adapterPosition = adapter.indexOfView(option);
    if (adapterPosition == -1)
      throw new AssertionError();
    if (options.size() == tdlib.options().pollAnswerCountMax - 1) {
      if (i == options.size()) {
        adapter.setItem(adapterPosition, addItem);
      } else {
        adapter.removeRange(adapterPosition, 2);
        int lastAdapterPosition = adapter.indexOfView(options.get(options.size() - 1));
        adapter.getItems().add(lastAdapterPosition + 1, addItem);
        adapter.getItems().add(lastAdapterPosition + 1, new ListItem(ListItem.TYPE_SEPARATOR_FULL));
        adapter.notifyItemRangeInserted(lastAdapterPosition + 1, 2);
      }
    } else {
      decoration.lastRange()[1] -= 2;
      adapter.removeRange(adapterPosition, 2);
    }
    if (hideKeyboard) {
      hideSoftwareKeyboard();
    }
    updateLimit();
    checkSend();
  }

  @Override
  public void onTextChanged (int id, ListItem item, MaterialEditTextGroup v) {
    checkSend();
  }

  private void updateLimit () {
    final int maxPollOptionCount = tdlib.options().pollAnswerCountMax;
    if (itemsListHint.setStringIfChanged(maxPollOptionCount <= options.size() ? Lang.getString(R.string.PollOptionsMax) : Lang.plural(R.string.PollOptionsLimit, maxPollOptionCount - options.size()))) {
      adapter.updateValuedSetting(itemsListHint);
    }
  }

  private final List<ListItem> options = new ArrayList<>();

  private ListItem createNewOption () {
    ListItem option = new ListItem(ListItem.TYPE_EDITTEXT_POLL_OPTION, R.id.option);
    option.setInputFilters(new InputFilter[] {
      (source, start, end, dest, dstart, dend) -> splitPastedOptions(option, source, start, end, dest, dstart, dend),
      new CodePointCountFilter(TdConstants.MAX_POLL_OPTION_LENGTH),
      new CharacterStyleFilter(),
      new RestrictFilter(new char[] {'\n'})
    }).setOnEditorActionListener(new EditBaseController.SimpleEditorActionListener(EditorInfo.IME_ACTION_NEXT, v -> addOption()));
    this.options.add(option);
    return option;
  }

  private boolean canSendPoll () {
    if (isQuiz && correctOptionItems.isEmpty())
      return false;
    CharSequence title = StringUtils.trim(questionItem.getCharSequenceValue());
    if (StringUtils.isEmpty(title))
      return false;
    if (isQuiz) {
      TdApi.FormattedText formattedText = getExplanation(false);
      if (formattedText != null && Td.codePointCount(formattedText) > TdConstants.MAX_QUIZ_EXPLANATION_LENGTH)
        return false;
    }
    int count = 0;
    int correctOptionCount = 0;
    for (ListItem optionItem : options) {
      CharSequence option = StringUtils.trim(optionItem.getCharSequenceValue());
      if (!StringUtils.isEmpty(option)) {
        if (correctOptionItems.contains(optionItem))
          correctOptionCount++;
        count++;
      }
    }
    TdApi.FormattedText description = getDescription(false);
    if (description != null && Td.codePointCount(description) > descriptionMaxLength) return false;
    boolean multiAnswers = isMultiAnswers();
    return PollDraftRules.validAnswers(count, correctOptionCount, isQuiz, multiAnswers);
  }

  private boolean isMultiAnswers () {
    return settingMultiAnswers != null && settingMultiAnswers.getBoolValue();
  }

  private boolean hasUnsavedPoll () {
    CharSequence title = StringUtils.trim(questionItem.getCharSequenceValue());
    if (!StringUtils.isEmpty(title))
      return true;
    if (!attachedMedia.isEmpty() || selectedOpenPeriod != 0 || selectedCloseDate != 0 || !Td.isEmpty(getDescription(false)))
      return true;
    if (!Td.isEmpty(getFormattedText(explanationItem, false)))
      return true;
    for (ListItem optionItem : options) {
      if (!StringUtils.isEmptyOrBlank(optionItem.getCharSequenceValue())) {
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean canSlideBackFrom (NavigationController navigationController, float x, float y) {
    return !hasUnsavedPoll() && !getDoneButton().isInProgress();
  }

  private void checkSend () {
    boolean canSendPoll = canSendPoll();
    if (canSendPoll) {
      getDoneButton().setIcon(R.drawable.deproko_baseline_send_24, Screen.dp(1.5f));
    } else if (currentMenu != null) {
      currentMenu.hideMenu();
    }
    setDoneVisible(canSendPoll, true);
  }

  private void requestFocus (ListItem item) {
    int i = adapter.indexOfView(item);
    View view = getRecyclerView().getLayoutManager().findViewByPosition(i);
    if (view instanceof ViewGroup) {
      Keyboard.show(((MaterialEditTextGroup) ((ViewGroup) view).getChildAt(0)).getEditText());
    }
  }

  @Override
  protected void onDoneClick () {
    send(Td.newSendOptions(), false);
  }

  @Nullable
  private TdApi.FormattedText getExplanation (boolean parseMarkdown) {
    return isQuiz ? getFormattedText(explanationItem, parseMarkdown) : null;
  }

  @Nullable
  private TdApi.FormattedText getDescription (boolean parseMarkdown) {
    return getFormattedText(descriptionItem, parseMarkdown);
  }

  @Nullable
  private TdApi.FormattedText getFormattedText (ListItem item, boolean parseMarkdown) {
    CharSequence explanationText = item != null ? item.getCharSequenceValue() : null;
    if (!StringUtils.isEmpty(explanationText)) {
      TdApi.FormattedText explanation = TD.toFormattedText(explanationText, false);
      if (parseMarkdown)
        Td.parseMarkdown(explanation);
      return explanation;
    }
    return null;
  }

  private void send (TdApi.MessageSendOptions sendOptions, boolean disableMarkdown) {
    if (getDoneButton().isInProgress() || !canSendPoll())
      return;
    TdApi.FormattedText question = TD.toFormattedText(StringUtils.trim(questionItem.getCharSequenceValue()), false);
    if (Td.isEmpty(question) || Td.codePointCount(question) > TdConstants.MAX_POLL_QUESTION_LENGTH) {
      requestFocus(questionItem);
      return;
    }
    boolean hasCustomEmoji = TD.hasCustomEmoji(question);
    IntList correctOptionIds = new IntList(tdlib.options().pollAnswerCountMax);
    List<TdApi.InputPollOption> options = new ArrayList<>(tdlib.options().pollAnswerCountMax);
    for (ListItem optionItem : this.options) {
      CharSequence cs = StringUtils.trim(optionItem.getCharSequenceValue());
      if (StringUtils.isEmpty(cs))
        continue;
      TdApi.FormattedText option = TD.toFormattedText(cs, false);
      if (Td.isEmpty(option))
        continue;
      if (Td.codePointCount(option) > TdConstants.MAX_POLL_OPTION_LENGTH) {
        requestFocus(optionItem);
        return;
      }
      if (!hasCustomEmoji && TD.hasCustomEmoji(option)) {
        hasCustomEmoji = true;
      }
      if (correctOptionItems.contains(optionItem)) {
        int correctOptionId = options.size();
        correctOptionIds.append(correctOptionId);
      }
      options.add(new TdApi.InputPollOption(option, attachedMedia.get(optionItem)));
    }
    if (options.size() < 2)
      return;

    TdApi.FormattedText description = getDescription(!disableMarkdown);
    TdApi.FormattedText explanation = getExplanation(!disableMarkdown);
    if (description != null && Td.codePointCount(description) > descriptionMaxLength) {
      requestFocus(descriptionItem);
      return;
    }
    if (explanation != null && Td.codePointCount(explanation) > TdConstants.MAX_QUIZ_EXPLANATION_LENGTH) {
      requestFocus(explanationItem);
      return;
    }
    hasCustomEmoji |= description != null && TD.hasCustomEmoji(description) || explanation != null && TD.hasCustomEmoji(explanation);
    if (isQuiz && (correctOptionIds.size() == 0 || !isMultiAnswers() && correctOptionIds.size() != 1)) return;
    if (hasCustomEmoji && !tdlib.hasPremium()) {
      tdlib.ui().showPremiumAlert(this, getDoneButton(), TdlibUi.PremiumFeature.CUSTOM_EMOJI);
      return;
    }

    Args args = getArgumentsStrict();
    final long chatId = args.chatId;
    final TdApi.MessageTopic messageTopicId = args.messageTopicId;
    final TdApi.InputSuggestedPostInfo suggestedPostInfo = args.inputSuggestedPostInfo;
    if (sendOptions.schedulingState == null && args.callback.areScheduledOnly(this)) {
      tdlib.ui().showScheduleOptions(this, chatId, false, (modifiedSendOptions, disableMarkdown1) -> send(modifiedSendOptions, disableMarkdown), sendOptions, null);
      return;
    }

    final CharSequence slowModeRestrictionText = tdlib.getSlowModeRestrictionText(chatId, sendOptions.schedulingState);
    if (slowModeRestrictionText != null) {
      context().tooltipManager().builder(getDoneButton()).controller(this).show(tdlib, slowModeRestrictionText).hideDelayed();
      return;
    }

    int openPeriod = selectedOpenPeriod;
    int closeDate = selectedCloseDate;
    long now = tdlib.currentTime(TimeUnit.SECONDS);
    long sendDate = sendOptions.schedulingState instanceof TdApi.MessageSchedulingStateSendAtDate ?
      ((TdApi.MessageSchedulingStateSendAtDate) sendOptions.schedulingState).sendDate : now;
    if (!PollDraftRules.validDuration(openPeriod, closeDate, now, sendDate,
        sendOptions.schedulingState instanceof TdApi.MessageSchedulingStateSendWhenOnline,
        tdlib.options().pollOpenPeriodMax)) {
      showErrorTooltip(getDoneButton(), Lang.getString(R.string.PollDurationInvalid));
      return;
    }

    setPollSending(true);
    hideSoftwareKeyboard();

    TdApi.InputPollOption[] optionsArray = options.toArray(new TdApi.InputPollOption[0]);
    TdApi.InputPollType inputPollType = isQuiz ?
      new TdApi.InputPollTypeQuiz(correctOptionIds.get(), explanation, attachedMedia.get(explanationItem)) :
      new TdApi.InputPollTypeRegular(settingAdditions != null && settingAdditions.getBoolValue());
    TdApi.InputMessagePoll poll = new TdApi.InputMessagePoll(
      question,
      optionsArray,
      description,
      attachedMedia.get(descriptionItem),
      settingShowVoters == null || !settingShowVoters.getBoolValue(),
      isMultiAnswers(),
      settingRevoting != null && settingRevoting.getBoolValue(),
      settingOnlyMembers != null && settingOnlyMembers.getBoolValue(),
      settingCountries != null && settingCountries.getBoolValue() ? selectedCountries.keySet().toArray(new String[0]) : new String[0],
      settingShuffle != null && settingShuffle.getBoolValue(),
      settingHideResults != null && settingHideResults.getBoolValue(),
      inputPollType,
      openPeriod,
      closeDate,
      false
    );

    RunnableData<TdApi.Message> after = message -> runOnUiThreadOptional(() -> {
      setPollSending(false);
      if (message != null) {
        if (sendOptions.schedulingState != null && !args.callback.areScheduledOnly(this)) {
          NavigationStack stack = navigationStack();
          if (stack != null) {
            MessagesController c = new MessagesController(context, tdlib);
            c.setArguments(new MessagesController.Arguments(args.callback.provideChatList(this), tdlib.chatStrict(args.chatId), /* messageThread */ null, messageTopicId, null, MessagesManager.HIGHLIGHT_MODE_NONE, null).setScheduled(true));
            stack.insertBack(c);
          }
        }
        navigateBack();
      }
    });
    final TdApi.MessageSendOptions finalSendOptions = Td.newSendOptions(sendOptions, suggestedPostInfo, tdlib.chatDefaultDisableNotifications(chatId));
    if (!getArgumentsStrict().callback.onSendPoll(this, chatId, messageTopicId, poll, finalSendOptions, after)) {
      tdlib.sendMessage(chatId, messageTopicId, null, finalSendOptions, poll, after);
    }
  }
}
