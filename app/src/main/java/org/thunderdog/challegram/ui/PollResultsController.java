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
package org.thunderdog.challegram.ui;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.attach.CustomItemAnimator;
import org.thunderdog.challegram.component.user.UserView;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.data.PollResultPolicy;
import org.thunderdog.challegram.data.TGUser;
import org.thunderdog.challegram.navigation.DoubleHeaderView;
import org.thunderdog.challegram.navigation.NavigationController;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.support.ViewSupport;
import org.thunderdog.challegram.telegram.ListManager;
import org.thunderdog.challegram.telegram.PollListener;
import org.thunderdog.challegram.telegram.PollVoterListManager;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibUi;
import org.thunderdog.challegram.telegram.UserListManager;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.v.CustomRecyclerView;
import org.thunderdog.challegram.widget.CustomTextView;
import org.thunderdog.challegram.widget.ListInfoView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.core.ArrayUtils;
import tgx.td.Td;

public class PollResultsController extends RecyclerViewController<PollResultsController.Args> implements PollListener, UserListManager.ChangeListener, View.OnClickListener {
  public static class Args {
    public TdApi.Poll poll;
    public final long chatId, messageId;
    public String optionId;

    public Args (TdApi.Poll poll, long chatId, long messageId, String optionId) {
      this(poll, chatId, messageId);
      this.optionId = optionId;
    }

    public Args(TdApi.Poll poll, long chatId, long messageId) {
      this.poll = poll;
      this.chatId = chatId;
      this.messageId = messageId;
    }
  }

  public PollResultsController (@NonNull Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public int getId() {
    return R.id.controller_pollResults;
  }

  private DoubleHeaderView headerCell;

  @Override
  public View getCustomHeaderCell () {
    return headerCell;
  }

  private TdApi.Poll getPoll () {
    return getArgumentsStrict().poll;
  }

  private boolean isQuiz () {
    return getPoll().type.getConstructor() == TdApi.PollTypeQuiz.CONSTRUCTOR;
  }

  private SettingsAdapter adapter;

  private void updateHeader (boolean isUpdate) {
    switch (getPoll().type.getConstructor()) {
      case TdApi.PollTypeRegular.CONSTRUCTOR: {
        if (!isUpdate)
          headerCell.setTitle(R.string.PollResultsTitle);
        headerCell.setSubtitle(Lang.plural(R.string.xVotes, getPoll().totalVoterCount));
        break;
      }
      case TdApi.PollTypeQuiz.CONSTRUCTOR: {
        if (!isUpdate)
          headerCell.setTitle(R.string.QuizResultsTitle);
        headerCell.setSubtitle(Lang.plural(R.string.xAnswers, getPoll().totalVoterCount));
        break;
      }
    }
  }

  private static class ListCache implements PollVoterListManager.ChangeListener {
    private final Tdlib tdlib;
    private final PollVoterListManager voters;
    private final SettingsAdapter adapter;

    public ListCache (ViewController<?> context, long chatId, long messageId, int optionId) {
      this.tdlib = context.tdlib();
      this.adapter = new SettingsAdapter(context) {
        @Override
        protected void setUser (ListItem item, int position, UserView view, boolean isUpdate) {
          TdApi.PollVoter voter = (TdApi.PollVoter) item.getData();
          TGUser user;
          if (voter.voterId instanceof TdApi.MessageSenderUser) {
            long userId = ((TdApi.MessageSenderUser) voter.voterId).userId;
            user = new TGUser(tdlib, tdlib.cache().user(userId));
          } else {
            long chatId = ((TdApi.MessageSenderChat) voter.voterId).chatId;
            user = new TGUser(tdlib, tdlib.chat(chatId));
          }
          user.setCustomStatus(voter.date != 0 ? Lang.getTimestamp(voter.date, TimeUnit.SECONDS) : "");
          view.setLayoutParams(new RecyclerView.LayoutParams(
            Math.min(Screen.dp(260f), Screen.currentWidth()), Screen.dp(95f)));
          view.setHeight(Screen.dp(95f));
          view.setUser(user);
        }
      };
      this.adapter.setNoEmptyProgress();
      this.voters = new PollVoterListManager(tdlib, 50, 50, this) {
        @Override
        protected TdApi.Function<TdApi.PollVoters> nextLoadFunction (boolean reverse, int itemCount, int loadCount) {
          return new TdApi.GetPollVoters(chatId, messageId, optionId, itemCount, loadCount);
        }
      };
      this.voters.loadInitialChunk(null);
    }

    private ListItem newItem (TdApi.PollVoter voter) {
      return new ListItem(ListItem.TYPE_USER, R.id.sender)
        .setData(voter)
        .setLongId(Td.getSenderId(voter.voterId));
    }

    @Override
    public void onItemsAdded (ListManager<TdApi.PollVoter> list, List<TdApi.PollVoter> items, int startIndex, boolean isInitialChunk) {
      List<ListItem> itemsToAdd = new ArrayList<>(items.size());
      for (TdApi.PollVoter voter : items) {
        itemsToAdd.add(newItem(voter));
      }
      adapter.getItems().addAll(startIndex, itemsToAdd);
      adapter.notifyItemRangeInserted(startIndex, itemsToAdd.size());
    }

    @Override
    public void onItemChanged (ListManager<TdApi.PollVoter> list, TdApi.PollVoter item,
                               int index, int cause) {
      adapter.getItems().set(index, newItem(item));
      adapter.notifyItemChanged(index);
    }

    @Override
    public void onItemRemoved (ListManager<TdApi.PollVoter> list, TdApi.PollVoter item, int index) {
      adapter.removeItem(index);
    }

    @Override
    public void onItemAdded (ListManager<TdApi.PollVoter> list, TdApi.PollVoter item, int toIndex) {
      adapter.addItem(toIndex, newItem(item));
    }
  }

  @Override
  public void onClick (View v) {
    if (v.getId() == R.id.sender) {
      ListItem item = (ListItem) v.getTag();
      TdApi.MessageSender sender = tdlib.sender(item.getLongId());
      tdlib.ui().openSenderProfile(this, sender, new TdlibUi.UrlOpenParameters().tooltip(context().tooltipManager().builder(v)));
    }
  }

  @Override
  public boolean canSlideBackFrom(NavigationController navigationController, float originalX, float originalY) {
    float x = originalX - (Views.getLocationInWindow(getRecyclerView())[0] - Views.getLocationInWindow(navigationController.getValue())[0]);
    float y = originalY - (Views.getLocationInWindow(getRecyclerView())[1] - Views.getLocationInWindow(navigationController.getValue())[1]);
    if (x >= 0 && y >= 0 && x < getRecyclerView().getMeasuredWidth() && y < getRecyclerView().getMeasuredHeight()) {
      View view = getRecyclerView().findChildViewUnder(x, y);
      if (view instanceof RecyclerView) {
        LinearLayoutManager manager = (LinearLayoutManager) ((RecyclerView) view).getLayoutManager();
        if (manager.findFirstCompletelyVisibleItemPosition() != 0) {
          return false;
        }
      }
    }
    return super.canSlideBackFrom(navigationController, originalX, originalY);
  }

  @Override
  protected void onCreateView (Context context, CustomRecyclerView recyclerView) {
    headerCell = new DoubleHeaderView(context);
    headerCell.setThemedTextColor(this);
    headerCell.initWithMargin(Screen.dp(49f), true);
    updateHeader(false);

    // FillingDecoration decoration = new FillingDecoration(recyclerView, this);
    if (!PollResultPolicy.canGetVoters(getPoll().isAnonymous, getPoll().canSeeResults,
        getPoll().canGetVoters)) {
      adapter = new SettingsAdapter(this);
      adapter.setItems(new ListItem[] {
        new ListItem(ListItem.TYPE_TEXT_VIEW, R.id.text_title, 0,
          Lang.getString(R.string.PollVoteRestricted), false)
      }, false);
      recyclerView.setAdapter(adapter);
      return;
    }
    List<ListItem> items = buildItems();

    adapter = new SettingsAdapter(this) {
      @Override
      protected void setInfo(ListItem item, int position, ListInfoView infoView) {
        int[] correctOptionIds = isQuiz() ? ((TdApi.PollTypeQuiz) getPoll().type).correctOptionIds : null;
        TdApi.PollOption option = getPoll().options[item.getIntValue()];
        infoView.showInfo(Lang.formatString("%s — %d%%", null, Lang.pluralBold(isQuiz() ? (correctOptionIds != null && ArrayUtils.contains(correctOptionIds, item.getIntValue()) ? R.string.xCorrectAnswers : R.string.xAnswers) : R.string.xVotes, option.voterCount), option.votePercentage));
      }

      @Override
      protected void setText(ListItem item, CustomTextView view, boolean isUpdate) {
        super.setText(item, view, isUpdate);

        final int viewId = view.getId();
        if (viewId == R.id.text_title) {
          view.setTextSize(17f);
          view.setPadding(Screen.dp(16f), Screen.dp(13f), Screen.dp(16f), Screen.dp(13f));
          view.setTextColorId(ColorId.text);
          ViewSupport.setThemedBackground(view, ColorId.filling, PollResultsController.this);
        } else if (viewId == R.id.text_subtitle) {
          view.setTextSize(15f);
          view.setPadding(Screen.dp(16f), Screen.dp(6f), Screen.dp(16f), Screen.dp(6f));
          view.setTextColorId(ColorId.background_text);
          ViewSupport.setThemedBackground(view, ColorId.NONE, PollResultsController.this);
        }
      }

      @Override
      protected void setRecyclerViewData(ListItem item, RecyclerView recyclerView, boolean isInitialization) {
        ListCache listCache = (ListCache) item.getData();
        if (isInitialization) {
          recyclerView.setItemAnimator(new CustomItemAnimator(AnimatorUtils.DECELERATE_INTERPOLATOR, 180l));
          recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
              PollVoterListManager listManager = ((ListCache) ((ListItem) recyclerView.getTag()).getData()).voters;
              int lastVisiblePosition = ((LinearLayoutManager) recyclerView.getLayoutManager()).findLastVisibleItemPosition();
              if (lastVisiblePosition + 5 >= listManager.getCount()) {
                listManager.loadItems(false, null);
              }
            }
          });
        }
        if (recyclerView.getAdapter() != listCache.adapter) {
          recyclerView.setAdapter(listCache.adapter);
        }
      }
    };
    adapter.setItems(items, false);
    recyclerView.setAdapter(adapter);
    if (getArgumentsStrict().optionId != null) {
      for (int i = 0; i < getPoll().options.length; i++) {
        if (getArgumentsStrict().optionId.equals(getPoll().options[i].id)) {
          ((LinearLayoutManager) recyclerView.getLayoutManager())
            .scrollToPositionWithOffset(findOption(i), 0);
          break;
        }
      }
    }
    // recyclerView.addItemDecoration(decoration);

    tdlib.listeners().addPollListener(getPoll().id, this);
  }

  private List<ListItem> buildItems () {
    List<ListItem> items = new ArrayList<>();

    items.add(new ListItem(ListItem.TYPE_TEXT_VIEW, R.id.text_title, 0, TD.toCharSequence(getPoll().question), false));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));

    int optionId = 0;
    for (TdApi.PollOption option : getPoll().options) {
      items.add(new ListItem(ListItem.TYPE_TEXT_VIEW, R.id.text_subtitle, 0,
        TD.toCharSequence(option.text), false).setIntValue(optionId));
      items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
      if (option.voterCount > 0) {
        items.add(newRecyclerItem(optionId));
      }
      items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
      items.add(new ListItem(ListItem.TYPE_LIST_INFO_VIEW).setIntValue(optionId));
      optionId++;
    }
    return items;
  }

  private void destroyVoterLists () {
    if (adapter != null) {
      for (ListItem item : adapter.getItems()) {
        if (item.getData() instanceof ListCache) {
          ((ListCache) item.getData()).voters.performDestroy();
        }
      }
    }
  }

  private int findOption (int optionId) {
    List<ListItem> items = adapter.getItems();
    int index = 0;
    for (ListItem item : items) {
      if (item.getId() == R.id.text_subtitle && item.getIntValue() == optionId) {
        return index;
      }
      index++;
    }
    return -1;
  }

  private ListItem newRecyclerItem (int optionId) {
    return new ListItem(ListItem.TYPE_RECYCLER_HORIZONTAL).setIntValue(optionId).setData(new ListCache(this, getArgumentsStrict().chatId, getArgumentsStrict().messageId, optionId));
  }

  @Override
  public void destroy() {
    super.destroy();
    tdlib.listeners().removePollListener(getPoll().id, this);
    destroyVoterLists();
  }

  @Override
  public void onUpdatePoll (TdApi.Poll updatedPoll) {
    runOnUiThread(() -> {
      if (!isDestroyed() && getPoll().id == updatedPoll.id) {
        boolean changedOptions = getPoll().options.length != updatedPoll.options.length;
        if (!changedOptions) {
          for (int i = 0; i < updatedPoll.options.length; i++) {
            if (!getPoll().options[i].id.equals(updatedPoll.options[i].id) ||
                getPoll().options[i].voterCount != updatedPoll.options[i].voterCount) {
              changedOptions = true;
              break;
            }
          }
        }
        getArgumentsStrict().poll = updatedPoll;

        if (!PollResultPolicy.canGetVoters(updatedPoll.isAnonymous, updatedPoll.canSeeResults,
            updatedPoll.canGetVoters)) {
          navigateBack();
          return;
        }

        updateHeader(true);

        if (changedOptions) {
          destroyVoterLists();
          adapter.setItems(buildItems(), false);
          return;
        }
        List<ListItem> items = adapter.getItems();
        for (int i = 0; i < updatedPoll.options.length; i++) {
          int position = findOption(i);
          if (position >= 0) {
            items.get(position).setString(TD.toCharSequence(updatedPoll.options[i].text));
            adapter.notifyItemChanged(position);
          }
        }

        int index = 0;
        for (ListItem item : items) {
          if (item.getViewType() == ListItem.TYPE_LIST_INFO_VIEW) {
            adapter.updateValuedSettingByPosition(index);
          }
          index++;
        }

      }
    });
  }
}
