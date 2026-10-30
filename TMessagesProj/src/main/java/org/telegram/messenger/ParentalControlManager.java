package org.telegram.messenger;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputFilter;
import android.text.InputType;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.tgnet.TLRPC;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Self-contained Parent/Child Mode and Channel/Chat Whitelist controller for Telegram Android.
 *
 * Features:
 * - PIN-protected mode switching between Parent Mode (unrestricted + whitelist configuration)
 *   and Child Mode (strictly displays and receives messages only from whitelisted channels/chats).
 * - Uses programmatic Android AlertDialogs so zero XML layout resource changes are required.
 * - Filters DialogsActivity dialog lists, incoming message updates, search results, and notifications.
 */
public final class ParentalControlManager {

    public static final int MENU_ITEM_PARENTAL_CONTROL = 9991;

    private static final String PREFS_NAME = "tg_parental_control_prefs";
    private static final String KEY_CHILD_MODE = "is_child_mode_active";
    private static final String KEY_PIN_HASH = "parent_pin_sha256";
    private static final String KEY_WHITELIST_IDS = "whitelisted_dialog_ids";
    private static final String KEY_ALLOW_SAVED_MESSAGES = "allow_saved_messages";
    private static final String PIN_SALT = "TG_PARENTAL_SALT_v1:";
    private static final String DEFAULT_PIN = "1234";

    private static volatile ParentalControlManager instance;

    private final SharedPreferences prefs;
    private boolean childModeActive;
    private final Set<Long> whitelistedDialogIds = new HashSet<>();
    private boolean allowSavedMessages;

    public interface ModeChangeListener {
        void onParentalModeChanged(boolean isChildMode);
    }

    public static ParentalControlManager getInstance() {
        ParentalControlManager local = instance;
        if (local == null) {
            synchronized (ParentalControlManager.class) {
                local = instance;
                if (local == null) {
                    local = new ParentalControlManager(ApplicationLoader.applicationContext);
                    instance = local;
                }
            }
        }
        return local;
    }

    private ParentalControlManager(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        loadState();
    }

    private synchronized void loadState() {
        childModeActive = prefs.getBoolean(KEY_CHILD_MODE, false);
        allowSavedMessages = prefs.getBoolean(KEY_ALLOW_SAVED_MESSAGES, false);
        whitelistedDialogIds.clear();
        Set<String> stored = prefs.getStringSet(KEY_WHITELIST_IDS, Collections.emptySet());
        if (stored != null) {
            for (String s : stored) {
                try {
                    whitelistedDialogIds.add(Long.parseLong(s.trim()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (!prefs.contains(KEY_PIN_HASH)) {
            prefs.edit().putString(KEY_PIN_HASH, hashPin(DEFAULT_PIN)).apply();
        }
    }

    private synchronized void saveWhitelist() {
        Set<String> serialized = new HashSet<>(whitelistedDialogIds.size());
        for (Long id : whitelistedDialogIds) {
            serialized.add(String.valueOf(id));
        }
        prefs.edit().putStringSet(KEY_WHITELIST_IDS, serialized).apply();
    }

    private static String hashPin(String rawPin) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((PIN_SALT + rawPin.trim()).getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(bytes, Base64.NO_WRAP);
        } catch (Exception e) {
            return String.valueOf((PIN_SALT + rawPin).hashCode());
        }
    }

    public synchronized boolean verifyPin(String candidatePin) {
        if (candidatePin == null) {
            return false;
        }
        String storedHash = prefs.getString(KEY_PIN_HASH, hashPin(DEFAULT_PIN));
        return storedHash.equals(hashPin(candidatePin));
    }

    public synchronized boolean updatePin(String currentPin, String newPin) {
        if (!verifyPin(currentPin)) {
            return false;
        }
        if (newPin == null || newPin.trim().length() < 4) {
            return false;
        }
        prefs.edit().putString(KEY_PIN_HASH, hashPin(newPin.trim())).apply();
        return true;
    }

    public synchronized boolean isChildMode() {
        return childModeActive;
    }

    public synchronized boolean isParentMode() {
        return !childModeActive;
    }

    public synchronized void setChildMode(boolean enabled) {
        childModeActive = enabled;
        prefs.edit().putBoolean(KEY_CHILD_MODE, enabled).apply();
        notifyDialogsReload();
    }

    public synchronized boolean isDialogWhitelisted(long dialogId) {
        return whitelistedDialogIds.contains(dialogId);
    }

    public synchronized void setDialogWhitelisted(long dialogId, boolean whitelisted) {
        if (whitelisted) {
            whitelistedDialogIds.add(dialogId);
        } else {
            whitelistedDialogIds.remove(dialogId);
        }
        saveWhitelist();
    }

    public synchronized void toggleDialogWhitelisted(long dialogId) {
        setDialogWhitelisted(dialogId, !whitelistedDialogIds.contains(dialogId));
    }

    public synchronized Set<Long> getWhitelistedDialogIds() {
        return new HashSet<>(whitelistedDialogIds);
    }

    /**
     * Checks whether a given dialog ID is permitted to be shown or receive messages.
     * In Parent Mode, all dialogs are permitted.
     * In Child Mode, only whitelisted dialog IDs are permitted.
     */
    public synchronized boolean isDialogAllowed(long dialogId, int currentAccount) {
        if (!childModeActive) {
            return true;
        }
        if (allowSavedMessages) {
            try {
                long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
                if (dialogId == selfId) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return whitelistedDialogIds.contains(dialogId);
    }

    /**
     * Filters any list of TLRPC.Dialog when Child Mode is active.
     * Hooked directly into DialogsActivity.getDialogsArray(...).
     */
    public ArrayList<TLRPC.Dialog> filterDialogsList(ArrayList<TLRPC.Dialog> sourceDialogs, int currentAccount) {
        if (sourceDialogs == null || !isChildMode()) {
            return sourceDialogs;
        }
        ArrayList<TLRPC.Dialog> filtered = new ArrayList<>();
        for (int i = 0; i < sourceDialogs.size(); i++) {
            TLRPC.Dialog dialog = sourceDialogs.get(i);
            if (dialog != null && isDialogAllowed(dialog.id, currentAccount)) {
                filtered.add(dialog);
            }
        }
        return filtered;
    }

    /**
     * Returns true if a message should be blocked/hidden in Child Mode.
     */
    public boolean shouldBlockMessage(MessageObject messageObject, int currentAccount) {
        if (!isChildMode() || messageObject == null) {
            return false;
        }
        long dialogId = messageObject.getDialogId();
        return !isDialogAllowed(dialogId, currentAccount);
    }

    /**
     * Returns true if a raw TLRPC.Message should be blocked in Child Mode.
     */
    public boolean shouldBlockRawMessage(TLRPC.Message message, int currentAccount) {
        if (!isChildMode() || message == null) {
            return false;
        }
        long dialogId = MessageObject.getDialogId(message);
        return !isDialogAllowed(dialogId, currentAccount);
    }

    public static int resolveLockIconResId(Context context) {
        if (context != null) {
            String[] candidates = new String[]{"ic_lock_header", "msg_secret", "list_pin"};
            for (String name : candidates) {
                int resId = context.getResources().getIdentifier(name, "drawable", context.getPackageName());
                if (resId != 0) {
                    return resId;
                }
            }
        }
        return android.R.drawable.ic_lock_idle_lock;
    }

    private void notifyDialogsReload() {
        AndroidUtilities.runOnUIThread(() -> {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                try {
                    NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.dialogsNeedReload, true);
                } catch (Exception ignored) {
                }
            }
        });
    }

    // =========================================================================================
    // Minimal Programmatic UI (No XML Resources Required)
    // =========================================================================================

    /**
     * Entry point triggered from the main chat list (tapping the lock icon on the ActionBar
     * or long-pressing the ActionBar title).
     * Always prompts for the Parent PIN first before opening the Parent Control Panel or exiting Child Mode.
     */
    public void showPinPrompt(Context context, int currentAccount, ModeChangeListener listener) {
        if (context == null) {
            return;
        }
        LinearLayout container = createPaddedLayout(context);

        TextView subtitle = new TextView(context);
        subtitle.setText(isChildMode()
                ? "Child Mode is ACTIVE (Only whitelisted channels appear).\nEnter Parent PIN to switch mode or edit whitelist:"
                : "Parent Mode is ACTIVE.\nEnter Parent PIN to manage whitelist or enable Child Mode:");
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        container.addView(subtitle);

        final EditText pinInput = new EditText(context);
        pinInput.setHint("PIN (Default: 1234)");
        pinInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(12)});
        container.addView(pinInput);

        new AlertDialog.Builder(context)
                .setTitle("Parental Controls")
                .setView(container)
                .setPositiveButton("Unlock", (dialog, which) -> {
                    String entered = pinInput.getText() != null ? pinInput.getText().toString() : "";
                    if (verifyPin(entered)) {
                        showParentControlDashboard(context, currentAccount, listener);
                    } else {
                        Toast.makeText(context, "Incorrect Parent PIN", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Main Parent Control Dashboard:
     * 1. Switch between Parent Mode and Child Mode
     * 2. Manage Whitelisted Channels / Chats
     * 3. Add Channel/Chat by ID Manually
     * 4. Change Parent PIN
     */
    public void showParentControlDashboard(Context context, int currentAccount, ModeChangeListener listener) {
        String modeLabel = isChildMode()
                ? "Switch to Parent Mode (Currently: CHILD MODE)"
                : "Switch to Child Mode (Currently: PARENT MODE)";
        String whitelistLabel = "Manage Whitelisted Channels (" + getWhitelistedDialogIds().size() + " allowed)";
        String manualIdLabel = "Add Channel/Chat ID Manually";
        String changePinLabel = "Change Parent PIN";

        CharSequence[] items = new CharSequence[]{
                modeLabel,
                whitelistLabel,
                manualIdLabel,
                changePinLabel
        };

        new AlertDialog.Builder(context)
                .setTitle("Parent Mode Control Panel")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        boolean nextChildMode = !isChildMode();
                        setChildMode(nextChildMode);
                        Toast.makeText(context,
                                nextChildMode ? "Child Mode Enabled: Only whitelisted channels visible"
                                              : "Parent Mode Enabled: All channels visible",
                                Toast.LENGTH_SHORT).show();
                        if (listener != null) {
                            listener.onParentalModeChanged(nextChildMode);
                        }
                    } else if (which == 1) {
                        showWhitelistSelectorDialog(context, currentAccount, listener);
                    } else if (which == 2) {
                        showManualDialogIdAdder(context, currentAccount, listener);
                    } else if (which == 3) {
                        showChangePinDialog(context);
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    /**
     * Displays all currently loaded channels, megagroups, and chats from MessagesController
     * with checkboxes so the parent can whitelist/unwhitelist them in one place.
     */
    public void showWhitelistSelectorDialog(Context context, int currentAccount, ModeChangeListener listener) {
        MessagesController mc = MessagesController.getInstance(currentAccount);
        ArrayList<TLRPC.Dialog> allDialogs = new ArrayList<>(mc.allDialogs);

        ScrollView scrollView = new ScrollView(context);
        LinearLayout listLayout = createPaddedLayout(context);
        scrollView.addView(listLayout);

        final List<CheckBox> checkBoxes = new ArrayList<>();
        final List<Long> dialogIds = new ArrayList<>();

        if (allDialogs.isEmpty()) {
            TextView emptyView = new TextView(context);
            emptyView.setText("No channels or chats loaded yet. Join or load channels in Parent Mode first.");
            listLayout.addView(emptyView);
        } else {
            for (int i = 0; i < allDialogs.size(); i++) {
                TLRPC.Dialog d = allDialogs.get(i);
                if (d == null) continue;
                long did = d.id;
                String title = resolveDialogTitle(mc, did);

                CheckBox cb = new CheckBox(context);
                cb.setText(title + "  [" + did + "]");
                cb.setChecked(isDialogWhitelisted(did));
                listLayout.addView(cb);

                checkBoxes.add(cb);
                dialogIds.add(did);
            }
        }

        new AlertDialog.Builder(context)
                .setTitle("Select Whitelisted Channels & Chats")
                .setView(scrollView)
                .setPositiveButton("Save Whitelist", (d, w) -> {
                    synchronized (this) {
                        for (int i = 0; i < dialogIds.size(); i++) {
                            long id = dialogIds.get(i);
                            if (checkBoxes.get(i).isChecked()) {
                                whitelistedDialogIds.add(id);
                            } else {
                                whitelistedDialogIds.remove(id);
                            }
                        }
                        saveWhitelist();
                    }
                    notifyDialogsReload();
                    Toast.makeText(context,
                            "Whitelist updated (" + getWhitelistedDialogIds().size() + " channels/chats)",
                            Toast.LENGTH_SHORT).show();
                    if (listener != null) {
                        listener.onParentalModeChanged(isChildMode());
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showManualDialogIdAdder(Context context, int currentAccount, ModeChangeListener listener) {
        LinearLayout layout = createPaddedLayout(context);
        final EditText input = new EditText(context);
        input.setHint("Dialog ID (e.g. -1001234567890)");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        layout.addView(input);

        new AlertDialog.Builder(context)
                .setTitle("Add Whitelisted Dialog ID")
                .setView(layout)
                .setPositiveButton("Add", (d, w) -> {
                    String raw = input.getText() != null ? input.getText().toString().trim() : "";
                    try {
                        long did = Long.parseLong(raw);
                        setDialogWhitelisted(did, true);
                        notifyDialogsReload();
                        Toast.makeText(context, "Added Dialog ID: " + did, Toast.LENGTH_SHORT).show();
                        if (listener != null) {
                            listener.onParentalModeChanged(isChildMode());
                        }
                    } catch (NumberFormatException e) {
                        Toast.makeText(context, "Invalid numeric Dialog ID", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showChangePinDialog(Context context) {
        LinearLayout layout = createPaddedLayout(context);

        final EditText oldPinInput = new EditText(context);
        oldPinInput.setHint("Current PIN");
        oldPinInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        layout.addView(oldPinInput);

        final EditText newPinInput = new EditText(context);
        newPinInput.setHint("New PIN (min 4 digits)");
        newPinInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        layout.addView(newPinInput);

        new AlertDialog.Builder(context)
                .setTitle("Change Parent PIN")
                .setView(layout)
                .setPositiveButton("Update PIN", (d, w) -> {
                    String oldPin = oldPinInput.getText() != null ? oldPinInput.getText().toString() : "";
                    String newPin = newPinInput.getText() != null ? newPinInput.getText().toString() : "";
                    if (updatePin(oldPin, newPin)) {
                        Toast.makeText(context, "Parent PIN updated", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(context, "Failed: verify current PIN and ensure new PIN is >= 4 digits", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static String resolveDialogTitle(MessagesController mc, long dialogId) {
        if (DialogObject.isChatDialog(dialogId)) {
            TLRPC.Chat chat = mc.getChat(-dialogId);
            if (chat != null && chat.title != null) {
                String prefix = ChatObject.isChannel(chat) && !chat.megagroup ? "[Channel] " : "[Group] ";
                return prefix + chat.title;
            }
            return "[Channel/Chat] ID " + dialogId;
        } else if (DialogObject.isUserDialog(dialogId)) {
            TLRPC.User user = mc.getUser(dialogId);
            if (user != null) {
                return "[User] " + ContactsController.formatName(user.first_name, user.last_name);
            }
            return "[User] ID " + dialogId;
        }
        return "[Dialog] ID " + dialogId;
    }

    private static LinearLayout createPaddedLayout(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = AndroidUtilities.dp(20);
        layout.setPadding(pad, pad / 2, pad, pad / 2);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }
}
