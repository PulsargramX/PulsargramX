package org.thunderdog.challegram.ui;

import android.content.Context;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.charts.Chart;
import org.thunderdog.challegram.charts.data.ChartDataUtil;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.v.CustomRecyclerView;

/** Uses the same interactive chart, zoom and asynchronous graph loading as chat statistics. */
public final class PollStatisticsController extends RecyclerViewController<PollStatisticsController.Args> {
  public static final class Args {
    public final long chatId, messageId;

    public Args (long chatId, long messageId) {
      this.chatId = chatId;
      this.messageId = messageId;
    }
  }

  private SettingsAdapter adapter;

  public PollStatisticsController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public int getId () {
    return R.id.controller_pollStatistics;
  }

  @Override
  public CharSequence getName () {
    return Lang.getString(R.string.PollVoteStatistics);
  }

  @Override
  protected void onCreateView (Context context, CustomRecyclerView recyclerView) {
    adapter = new SettingsAdapter(this);
    recyclerView.setAdapter(adapter);
    Args args = getArgumentsStrict();
    tdlib.send(new TdApi.GetMessageProperties(args.chatId, args.messageId), (properties, error) -> {
      if (error != null || !properties.canGetPollVoteStatistics) {
        runOnUiThread(() -> {
          if (!isDestroyed()) {
            showError(error != null ? TD.toErrorString(error) :
              Lang.getString(R.string.PollVoteRestricted));
          }
        });
        return;
      }
      tdlib.send(new TdApi.GetPollVoteStatistics(args.chatId, args.messageId, Theme.isDark()),
        (statistics, failure) -> runOnUiThread(() -> {
          if (isDestroyed()) {
            return;
          }
          if (failure != null) {
            showError(TD.toErrorString(failure));
            return;
          }
          Chart chart = new Chart(R.id.btn_pollStatistics, tdlib, args.chatId,
            R.string.PollVoteStatistics, ChartDataUtil.TYPE_LINEAR, statistics.voteGraph, 0);
          if (chart.isAsync()) {
            chart.load(loaded -> {
              if (!isDestroyed()) {
                showChart(chart);
              }
            });
          } else {
            showChart(chart);
          }
        }));
    });
  }

  private void showChart (Chart chart) {
    adapter.setItems(new ListItem[] {
      new ListItem(ListItem.TYPE_CHART_HEADER).setData(chart),
      new ListItem(ListItem.TYPE_SHADOW_TOP),
      new ListItem(chart.getViewType(), chart.getId()).setData(chart),
      new ListItem(ListItem.TYPE_SHADOW_BOTTOM)
    }, false);
  }

  private void showError (String error) {
    adapter.setItems(new ListItem[] {
      new ListItem(ListItem.TYPE_TEXT_VIEW, R.id.text_title, 0, error, false)
    }, false);
  }
}
