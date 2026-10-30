/*
 * This file is part of Telegram Channel Guard (adapted from Telegram X).
 * Copyright (C) 2026 Telegram Channel Guard Contributors
 *
 * Licensed under the GNU General Public License v3.0 (GPL-3.0).
 */
package org.thunderdog.challegram.guard;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;

import java.util.List;

public class ChannelGuardController extends ViewController<Void>
    implements ChannelGuardManager.AllowlistChangeListener {

  private boolean isUnlocked = false;
  private LinearLayout rootContainer;
  private LinearLayout listContainer;

  public ChannelGuardController(Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public int getId() {
    return View.generateViewId();
  }

  @Override
  public CharSequence getName() {
    return "Channel Guard";
  }

  @Override
  protected View onCreateView(Context context) {
    ScrollView scrollView = new ScrollView(context);
    scrollView.setFillViewport(true);
    scrollView.setBackgroundColor(Color.parseColor("#121212"));

    rootContainer = new LinearLayout(context);
    rootContainer.setOrientation(LinearLayout.VERTICAL);
    int pad = Screen.dp(16f);
    rootContainer.setPadding(pad, pad, pad, pad);
    scrollView.addView(rootContainer, new ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    ChannelGuardManager.getInstance().addListener(this);
    renderCurrentState();
    return scrollView;
  }

  @Override
  public void destroy() {
    ChannelGuardManager.getInstance().removeListener(this);
    super.destroy();
  }

  @Override
  public void onAllowlistChanged() {
    if (isUnlocked) {
      refreshApprovedChannelsList();
    }
  }

  private void renderCurrentState() {
    rootContainer.removeAllViews();
    if (!isUnlocked) {
      buildPinGateView();
    } else {
      buildAllowlistManagementView();
    }
  }

  private void buildPinGateView() {
    ChannelGuardManager guard = ChannelGuardManager.getInstance();
    boolean pinExists = guard.isPinConfigured();

    TextView title = new TextView(context);
    title.setText(pinExists ? "Enter Channel Guard PIN" : "Create Channel Guard PIN");
    title.setTextColor(Color.WHITE);
    title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
    rootContainer.addView(title);

    TextView subtitle = new TextView(context);
    subtitle.setText(pinExists
        ? "Enter your PIN to view or modify the channel allowlist."
        : "Set a 4 to 16 digit PIN to protect the channel allowlist.\n\n"
          + "IMPORTANT: PINs are hashed with PBKDF2 + Android Keystore with no backdoor. "
          + "If you forget your PIN, you must clear app storage in Android Settings and log in again.");
    subtitle.setTextColor(Color.parseColor("#B0B0B0"));
    subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
    subtitle.setPadding(0, Screen.dp(8f), 0, Screen.dp(16f));
    rootContainer.addView(subtitle);

    final EditText pinInput = new EditText(context);
    pinInput.setHint(pinExists ? "PIN" : "New PIN (4-16 digits)");
    pinInput.setHintTextColor(Color.GRAY);
    pinInput.setTextColor(Color.WHITE);
    pinInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
    rootContainer.addView(pinInput);

    final EditText confirmPinInput;
    if (!pinExists) {
      confirmPinInput = new EditText(context);
      confirmPinInput.setHint("Confirm New PIN");
      confirmPinInput.setHintTextColor(Color.GRAY);
      confirmPinInput.setTextColor(Color.WHITE);
      confirmPinInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
      rootContainer.addView(confirmPinInput);
    } else {
      confirmPinInput = null;
    }

    Button submitBtn = new Button(context);
    submitBtn.setText(pinExists ? "Unlock Allowlist" : "Save PIN & Continue");
    submitBtn.setOnClickListener(v -> {
      long lockout = guard.getRemainingLockoutSeconds();
      if (lockout > 0) {
        Toast.makeText(context, "Too many failed attempts. Try again in " + lockout + "s.", Toast.LENGTH_SHORT).show();
        return;
      }
      String pin = pinInput.getText().toString().trim();
      if (!pinExists) {
        String confirm = confirmPinInput != null ? confirmPinInput.getText().toString().trim() : "";
        if (pin.length() < 4 || pin.length() > 16) {
          Toast.makeText(context, "PIN must be between 4 and 16 digits.", Toast.LENGTH_SHORT).show();
          return;
        }
        if (!pin.equals(confirm)) {
          Toast.makeText(context, "PINs do not match.", Toast.LENGTH_SHORT).show();
          return;
        }
        if (guard.setOrUpdatePin(null, pin)) {
          isUnlocked = true;
          renderCurrentState();
        } else {
          Toast.makeText(context, "Failed to save PIN.", Toast.LENGTH_SHORT).show();
        }
      } else {
        if (guard.verifyPin(pin)) {
          isUnlocked = true;
          renderCurrentState();
        } else {
          long newLockout = guard.getRemainingLockoutSeconds();
          String msg = newLockout > 0
              ? "Incorrect PIN. Locked for " + newLockout + "s."
              : "Incorrect PIN.";
          Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
          pinInput.setText("");
        }
      }
    });
    rootContainer.addView(submitBtn);
  }

  private void buildAllowlistManagementView() {
    TextView header = new TextView(context);
    header.setText("Channel Guard Allowlist");
    header.setTextColor(Color.WHITE);
    header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
    rootContainer.addView(header);

    TextView info = new TextView(context);
    info.setText("Only channels listed below can be viewed or opened in this app. "
        + "Private 1-on-1 chats and normal groups remain accessible.");
    info.setTextColor(Color.parseColor("#B0B0B0"));
    info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
    info.setPadding(0, Screen.dp(4f), 0, Screen.dp(12f));
    rootContainer.addView(info);

    Button addByIdentifierBtn = new Button(context);
    addByIdentifierBtn.setText("+ Add Channel by @Username, Invite Link, or ID");
    addByIdentifierBtn.setOnClickListener(v -> showAddChannelDialog());
    rootContainer.addView(addByIdentifierBtn);

    Button addFromJoinedBtn = new Button(context);
    addFromJoinedBtn.setText("+ Approve from Joined Channels (Public & Private)");
    addFromJoinedBtn.setOnClickListener(v -> showPickJoinedChannelDialog());
    rootContainer.addView(addFromJoinedBtn);

    Button changePinBtn = new Button(context);
    changePinBtn.setText("Change Security PIN");
    changePinBtn.setOnClickListener(v -> showChangePinDialog());
    rootContainer.addView(changePinBtn);

    TextView listTitle = new TextView(context);
    listTitle.setText("Approved Channels:");
    listTitle.setTextColor(Color.WHITE);
    listTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
    listTitle.setPadding(0, Screen.dp(16f), 0, Screen.dp(8f));
    rootContainer.addView(listTitle);

    listContainer = new LinearLayout(context);
    listContainer.setOrientation(LinearLayout.VERTICAL);
    rootContainer.addView(listContainer, new ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    refreshApprovedChannelsList();
  }

  private void refreshApprovedChannelsList() {
    if (listContainer == null) return;
    listContainer.removeAllViews();

    List<ChannelGuardManager.ApprovedChannel> channels =
        ChannelGuardManager.getInstance().getApprovedChannels();

    if (channels.isEmpty()) {
      TextView emptyView = new TextView(context);
      emptyView.setText("No channels approved yet. All Telegram channels are currently blocked.");
      emptyView.setTextColor(Color.parseColor("#888888"));
      emptyView.setPadding(0, Screen.dp(8f), 0, Screen.dp(8f));
      listContainer.addView(emptyView);
      return;
    }

    for (ChannelGuardManager.ApprovedChannel ch : channels) {
      LinearLayout row = new LinearLayout(context);
      row.setOrientation(LinearLayout.HORIZONTAL);
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setPadding(Screen.dp(8f), Screen.dp(10f), Screen.dp(8f), Screen.dp(10f));
      row.setBackgroundColor(Color.parseColor("#1E1E1E"));

      LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
      rowLp.bottomMargin = Screen.dp(6f);
      row.setLayoutParams(rowLp);

      LinearLayout textCol = new LinearLayout(context);
      textCol.setOrientation(LinearLayout.VERTICAL);
      LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
          0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
      textCol.setLayoutParams(textLp);

      TextView titleView = new TextView(context);
      titleView.setText(ch.title);
      titleView.setTextColor(Color.WHITE);
      titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
      textCol.addView(titleView);

      String sub = "ID: " + ch.chatId + (TextUtils.isEmpty(ch.username) ? " (Private)" : " • @" + ch.username);
      TextView subView = new TextView(context);
      subView.setText(sub);
      subView.setTextColor(Color.parseColor("#9E9E9E"));
      subView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
      textCol.addView(subView);

      Button removeBtn = new Button(context);
      removeBtn.setText("Remove");
      removeBtn.setOnClickListener(v -> {
        ChannelGuardManager.getInstance().removeChannel(ch.chatId);
        Toast.makeText(context, "Removed: " + ch.title, Toast.LENGTH_SHORT).show();
      });

      row.addView(textCol);
      row.addView(removeBtn);
      listContainer.addView(row);
    }
  }

  private void showAddChannelDialog() {
    final EditText input = new EditText(context);
    input.setHint("@username, https://t.me/+invite, or -100123456789");
    new AlertDialog.Builder(context)
        .setTitle("Add Approved Channel")
        .setMessage("Enter a public @username, a private t.me/+ invite link, or a numeric TDLib Chat ID (-100...):")
        .setView(input)
        .setPositiveButton("Resolve & Add", (d, which) -> {
          String raw = input.getText().toString().trim();
          if (!raw.isEmpty()) {
            resolveAndApproveChannel(raw);
          }
        })
        .setNegativeButton("Cancel", null)
        .show();
  }

  private void resolveAndApproveChannel(String rawInput) {
    if (rawInput.matches("^-?\d+$")) {
      try {
        long chatId = Long.parseLong(rawInput);
        if (chatId > 0) {
          chatId = -1000000000000L - chatId;
        }
        final long targetChatId = chatId;
        tdlib.client().send(new TdApi.GetChat(targetChatId), object -> {
          if (object.getConstructor() == TdApi.Chat.CONSTRUCTOR) {
            approveResolvedChat((TdApi.Chat) object);
          } else {
            ChannelGuardManager.getInstance().addChannel(targetChatId, "Channel " + targetChatId, "");
            UI.post(() -> Toast.makeText(context, "Added Channel ID: " + targetChatId, Toast.LENGTH_SHORT).show());
          }
        });
        return;
      } catch (NumberFormatException ignored) {
      }
    }

    if (rawInput.contains("t.me/+") || rawInput.contains("joinchat/")) {
      tdlib.client().send(new TdApi.CheckChatInviteLink(rawInput), object -> {
        if (object.getConstructor() == TdApi.ChatInviteLinkInfo.CONSTRUCTOR) {
          TdApi.ChatInviteLinkInfo info = (TdApi.ChatInviteLinkInfo) object;
          if (info.chatId != 0) {
            ChannelGuardManager.getInstance().addChannel(info.chatId, info.title, "");
            UI.post(() -> Toast.makeText(context, "Approved private channel: " + info.title, Toast.LENGTH_SHORT).show());
          } else {
            tdlib.client().send(new TdApi.JoinChatByInviteLink(rawInput), joinObj -> {
              if (joinObj.getConstructor() == TdApi.Chat.CONSTRUCTOR) {
                approveResolvedChat((TdApi.Chat) joinObj);
              } else {
                UI.post(() -> Toast.makeText(context, "Could not join private invite link.", Toast.LENGTH_SHORT).show());
              }
            });
          }
        } else {
          UI.post(() -> Toast.makeText(context, "Invalid or expired invite link.", Toast.LENGTH_SHORT).show());
        }
      });
      return;
    }

    String username = rawInput
        .replace("https://t.me/", "")
        .replace("http://t.me/", "")
        .replace("t.me/", "")
        .replace("@", "")
        .split("/")[0]
        .split("\?")[0]
        .trim();

    tdlib.client().send(new TdApi.SearchPublicChat(username), object -> {
      if (object.getConstructor() == TdApi.Chat.CONSTRUCTOR) {
        approveResolvedChat((TdApi.Chat) object);
      } else {
        UI.post(() -> Toast.makeText(context, "Could not find public channel: @" + username, Toast.LENGTH_SHORT).show());
      }
    });
  }

  private void approveResolvedChat(TdApi.Chat chat) {
    if (chat.type.getConstructor() == TdApi.ChatTypeSupergroup.CONSTRUCTOR) {
      TdApi.ChatTypeSupergroup sg = (TdApi.ChatTypeSupergroup) chat.type;
      if (sg.isChannel) {
        String uname = tdlib.chatUsername(chat.id);
        ChannelGuardManager.getInstance().addChannel(chat.id, chat.title, uname != null ? uname : "");
        UI.post(() -> Toast.makeText(context, "Approved channel: " + chat.title, Toast.LENGTH_SHORT).show());
        return;
      }
    }
    UI.post(() -> Toast.makeText(context, "That chat is not a broadcast channel (groups/private chats are already allowed).", Toast.LENGTH_LONG).show());
  }

  private void showPickJoinedChannelDialog() {
    tdlib.client().send(new TdApi.GetChats(new TdApi.ChatListMain(), 200), object -> {
      if (object.getConstructor() != TdApi.Chats.CONSTRUCTOR) return;
      long[] chatIds = ((TdApi.Chats) object).chatIds;
      final List<TdApi.Chat> unapprovedChannels = new java.util.ArrayList<>();
      for (long id : chatIds) {
        TdApi.Chat chat = tdlib.chat(id);
        if (chat != null && chat.type.getConstructor() == TdApi.ChatTypeSupergroup.CONSTRUCTOR) {
          if (((TdApi.ChatTypeSupergroup) chat.type).isChannel
              && !ChannelGuardManager.getInstance().isChannelAllowed(chat.id)) {
            unapprovedChannels.add(chat);
          }
        }
      }
      UI.post(() -> {
        if (unapprovedChannels.isEmpty()) {
          Toast.makeText(context, "No unapproved joined channels found in your main chat list.", Toast.LENGTH_SHORT).show();
          return;
        }
        CharSequence[] items = new CharSequence[unapprovedChannels.size()];
        for (int i = 0; i < unapprovedChannels.size(); i++) {
          TdApi.Chat c = unapprovedChannels.get(i);
          items[i] = c.title + " (" + c.id + ")";
        }
        new AlertDialog.Builder(context)
            .setTitle("Approve Joined Channel")
            .setItems(items, (dialog, which) -> approveResolvedChat(unapprovedChannels.get(which)))
            .setNegativeButton("Cancel", null)
            .show();
      });
    });
  }

  private void showChangePinDialog() {
    LinearLayout layout = new LinearLayout(context);
    layout.setOrientation(LinearLayout.VERTICAL);
    int pad = Screen.dp(16f);
    layout.setPadding(pad, pad, pad, pad);

    final EditText oldPin = new EditText(context);
    oldPin.setHint("Current PIN");
    oldPin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
    layout.addView(oldPin);

    final EditText newPin = new EditText(context);
    newPin.setHint("New PIN (4-16 digits)");
    newPin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
    layout.addView(newPin);

    new AlertDialog.Builder(context)
        .setTitle("Change Security PIN")
        .setView(layout)
        .setPositiveButton("Update PIN", (d, w) -> {
          boolean ok = ChannelGuardManager.getInstance().setOrUpdatePin(
              oldPin.getText().toString().trim(),
              newPin.getText().toString().trim()
          );
          Toast.makeText(context, ok ? "PIN updated." : "Failed: Verify current PIN and length (4-16 digits).", Toast.LENGTH_SHORT).show();
        })
        .setNegativeButton("Cancel", null)
        .show();
  }
}
