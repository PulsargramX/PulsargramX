package org.thunderdog.challegram.ui;

import android.content.Context;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;

import androidx.recyclerview.widget.RecyclerView;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.emoji.Emoji;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.util.CharacterStyleFilter;
import org.thunderdog.challegram.widget.ChatMemberTagPreview;
import org.thunderdog.challegram.widget.MaterialEditTextGroup;

import java.util.ArrayList;
import java.util.List;

import me.vkryl.android.widget.FrameLayoutFix;
import me.vkryl.core.StringUtils;
import tgx.td.Td;
import tgx.td.TdConstants;

public final class EditMemberTagController extends EditBaseController<EditMemberTagController.Args>
    implements View.OnClickListener {
  public static final class Args {
    public final long chatId;
    public final TdApi.ChatMember member;

    public Args (long chatId, TdApi.ChatMember member) {
      this.chatId = chatId;
      this.member = new TdApi.ChatMember(member.memberId, member.tag, member.inviterUserId,
        member.joinedChatDate, Td.copyOf(member.status));
    }
  }

  private SettingsAdapter adapter;
  private String tag;
  private ChatMemberTagPreview preview;

  public EditMemberTagController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public void setArguments (Args args) {
    super.setArguments(args);
    tag = args.member.tag != null ? args.member.tag : "";
  }

  @Override
  public int getId () {
    return R.id.controller_memberTag;
  }

  @Override
  public CharSequence getName () {
    TdApi.ChatMember member = getArgumentsStrict().member;
    return Lang.getString(TD.isCreator(member.status) ? R.string.EditOwnerTag :
      TD.isAdmin(member.status) ? R.string.EditAdminTag :
      StringUtils.isEmpty(member.tag) ? R.string.AddMemberTag : R.string.EditMemberTag);
  }

  @Override
  protected void onCreateView (Context context, FrameLayoutFix contentView, RecyclerView recyclerView) {
    adapter = new SettingsAdapter(this) {
      @Override
      protected SettingHolder initCustom (ViewGroup parent) {
        preview = new ChatMemberTagPreview(context, tdlib);
        addThemeInvalidateListener(preview);
        return new SettingHolder(preview);
      }

      @Override
      protected void setCustom (ListItem item, SettingHolder holder, int position) {
        ((ChatMemberTagPreview) holder.itemView).setMember(getArgumentsStrict().member, tag.trim());
      }

      @Override
      protected void modifyEditText (ListItem item, ViewGroup parent, MaterialEditTextGroup editText) {
        editText.getEditText().setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        Views.setSingleLine(editText.getEditText(), true);
        editText.setMaxLength(TdConstants.MAX_CUSTOM_TITLE_LENGTH);
      }
    };
    List<ListItem> items = new ArrayList<>();
    items.add(new ListItem(ListItem.TYPE_CUSTOM_SINGLE));
    items.add(new ListItem(ListItem.TYPE_EDITTEXT_COUNTERED, R.id.input_customTitle, 0, R.string.MemberTagHint)
      .setStringValue(tag).setInputFilters(new InputFilter[] {new CharacterStyleFilter()})
      .setOnEditorActionListener(new SimpleEditorActionListener(EditorInfo.IME_ACTION_DONE, this)));
    Args args = getArgumentsStrict();
    boolean self = tdlib.isSelfUserId(Td.getSenderUserId(args.member.memberId));
    items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, self ?
      Lang.getString(R.string.MemberTagSelfInfo) :
      Lang.getStringBold(R.string.MemberTagTheirInfo, tdlib.senderName(args.member.memberId))));
    items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_clearMemberTag, R.drawable.baseline_delete_24,
      TD.isAdmin(args.member.status) ? R.string.MemberTagReset : R.string.MemberTagRemove)
      .setTextColorId(ColorId.textNegative));
    adapter.setTextChangeListener((id, item, view) -> {
      tag = view.getText().toString();
      if (preview != null) {
        preview.setMember(args.member, tag.trim());
      }
      setDoneVisible(!StringUtils.equalsOrBothEmpty(tag.trim(), args.member.tag));
    });
    adapter.setLockFocusOn(this, true);
    adapter.setItems(items, false);
    recyclerView.setAdapter(adapter);
    recyclerView.setOverScrollMode(View.OVER_SCROLL_NEVER);
    setDoneVisible(false);
  }

  @Override
  public void onClick (View view) {
    if (view.getId() == R.id.btn_clearMemberTag && !isInProgress()) {
      tag = "";
      int index = adapter.indexOfViewById(R.id.input_customTitle);
      adapter.getItems().get(index).setStringValue(tag);
      adapter.notifyItemChanged(index);
      if (preview != null) {
        preview.setMember(getArgumentsStrict().member, tag);
      }
      setDoneVisible(!StringUtils.isEmpty(getArgumentsStrict().member.tag));
    }
  }

  @Override
  protected void onProgressStateChanged (boolean inProgress) {
    adapter.updateLockEditTextById(R.id.input_customTitle, inProgress ? tag : null);
  }

  @Override
  protected boolean onDoneClick () {
    if (isInProgress()) {
      return true;
    }
    Args args = getArgumentsStrict();
    String newTag = tag.trim();
    int error = !tdlib.canEditChatMemberTag(args.chatId, args.member) ? R.string.MemberTagCantEdit :
      newTag.codePointCount(0, newTag.length()) > TdConstants.MAX_CUSTOM_TITLE_LENGTH ? R.string.CustomTitleTooBig :
      Emoji.instance().hasEmoji(newTag) ? R.string.CustomTitleEmoji : 0;
    if (error != 0) {
      context().tooltipManager().builder(getDoneButton()).show(tdlib, error).hideDelayed();
      return true;
    }
    if (StringUtils.equalsOrBothEmpty(newTag, args.member.tag)) {
      onSaveCompleted();
      return true;
    }
    setInProgress(true);
    setStackLocked(true);
    tdlib.setChatMemberTag(args.chatId, Td.getSenderUserId(args.member.memberId), newTag,
      setError -> runOnUiThreadOptional(() -> {
        setStackLocked(false);
        setInProgress(false);
        if (setError != null) {
          context().tooltipManager().builder(getDoneButton())
            .show(this, tdlib, R.drawable.baseline_error_24, TD.toErrorString(setError));
        } else {
          onSaveCompleted();
        }
      }));
    return true;
  }

  @Override
  public boolean performOnBackPressed (boolean fromTop, boolean commit) {
    if (isInProgress()) {
      return true;
    }
    if (!StringUtils.equalsOrBothEmpty(tag.trim(), getArgumentsStrict().member.tag)) {
      if (commit) {
        showUnsavedChangesPromptBeforeLeaving(null);
      }
      return true;
    }
    return super.performOnBackPressed(fromTop, commit);
  }
}
