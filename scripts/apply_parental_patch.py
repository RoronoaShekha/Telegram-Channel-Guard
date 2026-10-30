#!/usr/bin/env python3
"""
Automated Source Patcher for DrKLO/Telegram (Android).

Injects:
1. Repository API credentials (APP_ID, APP_HASH) into BuildVars.java
2. ParentalControlManager dialog filtering, ActionBar lock button & PIN prompt trigger into DialogsActivity.java
3. Incoming message & update filtering in Child Mode into MessagesController.java
4. Notification suppression for non-whitelisted channels in Child Mode into NotificationsController.java
"""

import os
import re
import sys


def patch_build_vars(repo_root: str) -> None:
    app_id = (
        os.environ.get("TELEGRAM_APP_ID")
        or os.environ.get("APP_ID")
        or os.environ.get("API_ID")
        or os.environ.get("TG_APP_ID")
        or ""
    ).strip()
    app_hash = (
        os.environ.get("TELEGRAM_APP_HASH")
        or os.environ.get("APP_HASH")
        or os.environ.get("API_HASH")
        or os.environ.get("TG_APP_HASH")
        or ""
    ).strip()

    matched_files = []
    for root, _, files in os.walk(repo_root):
        for f in files:
            if f == "BuildVars.java":
                matched_files.append(os.path.join(root, f))

    if not matched_files:
        print("[WARN] No BuildVars.java found in repository.")
        return

    for build_vars_path in matched_files:
        with open(build_vars_path, "r", encoding="utf-8") as f:
            content = f.read()

        if app_id and app_id.isdigit():
            content = re.sub(
                r"(public\s+static\s+int\s+APP_ID\s*=\s*)[^;]+;",
                rf"\g<1>{app_id};",
                content,
            )
            print(f"[INFO] Injected APP_ID into {build_vars_path}")
        else:
            print(f"[INFO] Keeping default BuildVars.APP_ID in {build_vars_path}")

        if app_hash:
            content = re.sub(
                r'(public\s+static\s+String\s+APP_HASH\s*=\s*)"[^"]*";',
                rf'\g<1>"{app_hash}";',
                content,
            )
            print(f"[INFO] Injected APP_HASH into {build_vars_path}")
        else:
            print(f"[INFO] Keeping default BuildVars.APP_HASH in {build_vars_path}")

        with open(build_vars_path, "w", encoding="utf-8") as f:
            f.write(content)


def patch_default_pin(repo_root: str) -> None:
    pcm_path = os.path.join(
        repo_root,
        "TMessagesProj/src/main/java/org/telegram/messenger/ParentalControlManager.java",
    )
    default_pin = (
        os.environ.get("DEFAULT_PARENT_PIN")
        or os.environ.get("PARENT_PIN")
        or ""
    ).strip()
    if not default_pin or not os.path.exists(pcm_path):
        return
    with open(pcm_path, "r", encoding="utf-8") as f:
        content = f.read()
    content = content.replace(
        'private static final String DEFAULT_PIN = "1234";',
        f'private static final String DEFAULT_PIN = "{default_pin}";',
    )
    with open(pcm_path, "w", encoding="utf-8") as f:
        f.write(content)
    print("[INFO] Configured custom DEFAULT_PARENT_PIN in ParentalControlManager.java")


def patch_dialogs_activity(repo_root: str) -> None:
    path = os.path.join(
        repo_root, "TMessagesProj/src/main/java/org/telegram/ui/DialogsActivity.java"
    )
    if not os.path.exists(path):
        print(f"[WARN] DialogsActivity.java not found at {path}")
        return

    with open(path, "r", encoding="utf-8") as f:
        content = f.read()

    if "ParentalControlManager.getInstance().filterDialogsList" in content:
        print("[INFO] DialogsActivity.java already patched.")
        return

    # 1. Patch getDialogsArray(...) whether static or instance method
    pattern = re.compile(
        r"(public\s+(static\s+)?ArrayList<TLRPC\.Dialog>\s+getDialogsArray\s*\(([^)]*)\)\s*\{)"
    )
    match = pattern.search(content)
    if match:
        original_sig = match.group(1)
        static_kw = match.group(2) or ""
        params_str = match.group(3) or ""
        arg_names = []
        for param in params_str.split(","):
            tokens = param.strip().split()
            if tokens:
                arg_names.append(tokens[-1])
        first_arg = arg_names[0] if arg_names else "currentAccount"
        call_args = ", ".join(arg_names)

        wrapper_code = (
            f"{original_sig}\n"
            f"        ArrayList<TLRPC.Dialog> rawDialogs = getDialogsArrayUnfiltered({call_args});\n"
            f"        return org.telegram.messenger.ParentalControlManager.getInstance().filterDialogsList(rawDialogs, {first_arg});\n"
            f"    }}\n\n"
            f"    private {static_kw}ArrayList<TLRPC.Dialog> getDialogsArrayUnfiltered({params_str}) {{"
        )
        content = content[: match.start()] + wrapper_code + content[match.end() :]
        print("[INFO] Patched DialogsActivity.getDialogsArray(...) with Child Mode whitelist filter.")
    else:
        print("[WARN] Could not locate getDialogsArray signature in DialogsActivity.java")

    # 2. Add ActionBar long-press trigger in createView(...) using getParentActivity()
    action_bar_hook = """
        if (actionBar != null) {
            actionBar.setOnLongClickListener(v -> {
                org.telegram.messenger.ParentalControlManager.getInstance().showPinPrompt(
                    getParentActivity(),
                    currentAccount,
                    isChild -> {
                        if (dialogsAdapter != null) {
                            dialogsAdapter.notifyDataSetChanged();
                        }
                    }
                );
                return true;
            });
            if (actionBar.getTitleTextView() != null) {
                actionBar.getTitleTextView().setOnLongClickListener(v -> {
                    org.telegram.messenger.ParentalControlManager.getInstance().showPinPrompt(
                        getParentActivity(),
                        currentAccount,
                        isChild -> {
                            if (dialogsAdapter != null) {
                                dialogsAdapter.notifyDataSetChanged();
                            }
                        }
                    );
                    return true;
                });
            }
        }
"""
    menu_click_marker = "actionBar.setActionBarMenuOnItemClick("
    idx = content.find(menu_click_marker)
    if idx != -1:
        line_start = content.rfind("\n", 0, idx)
        content = content[:line_start] + "\n" + action_bar_hook + content[line_start:]
        print("[INFO] Patched DialogsActivity ActionBar & title long-press trigger.")

    with open(path, "w", encoding="utf-8") as f:
        f.write(content)


def patch_messages_controller(repo_root: str) -> None:
    path = os.path.join(
        repo_root,
        "TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java",
    )
    if not os.path.exists(path):
        print(f"[WARN] MessagesController.java not found at {path}")
        return

    with open(path, "r", encoding="utf-8") as f:
        content = f.read()

    if "ParentalControlManager.getInstance().shouldBlockMessage" in content:
        print("[INFO] MessagesController.java already patched.")
        return

    pattern = re.compile(
        r"(public\s+void\s+updateInterfaceWithMessages\s*\(\s*long\s+(\w+)\s*,\s*ArrayList<MessageObject>\s+(\w+)[^)]*\)\s*\{)"
    )
    match = pattern.search(content)
    if match:
        uid_var = match.group(2)
        msgs_var = match.group(3)
        guard = (
            f"\n        if (org.telegram.messenger.ParentalControlManager.getInstance().isChildMode() "
            f"&& !org.telegram.messenger.ParentalControlManager.getInstance().isDialogAllowed({uid_var}, currentAccount)) {{\n"
            f"            return;\n"
            f"        }}\n"
            f"        if ({msgs_var} != null && org.telegram.messenger.ParentalControlManager.getInstance().isChildMode()) {{\n"
            f"            for (int _i = {msgs_var}.size() - 1; _i >= 0; _i--) {{\n"
            f"                if (org.telegram.messenger.ParentalControlManager.getInstance().shouldBlockMessage({msgs_var}.get(_i), currentAccount)) {{\n"
            f"                    {msgs_var}.remove(_i);\n"
            f"                }}\n"
            f"            }}\n"
            f"            if ({msgs_var}.isEmpty()) return;\n"
            f"        }}\n"
        )
        content = content[: match.end()] + guard + content[match.end() :]
        print("[INFO] Patched MessagesController.updateInterfaceWithMessages(...) for Child Mode filtering.")

    with open(path, "w", encoding="utf-8") as f:
        f.write(content)


def patch_notifications_controller(repo_root: str) -> None:
    path = os.path.join(
        repo_root,
        "TMessagesProj/src/main/java/org/telegram/messenger/NotificationsController.java",
    )
    if not os.path.exists(path):
        print(f"[WARN] NotificationsController.java not found at {path}")
        return

    with open(path, "r", encoding="utf-8") as f:
        content = f.read()

    if "ParentalControlManager.getInstance().shouldBlockMessage" in content:
        print("[INFO] NotificationsController.java already patched.")
        return

    pattern = re.compile(
        r"(public\s+void\s+processNewMessages\s*\(\s*ArrayList<MessageObject>\s+(\w+)[^)]*\)\s*\{)"
    )
    match = pattern.search(content)
    if match:
        msgs_var = match.group(2)
        guard = (
            f"\n        if ({msgs_var} != null && org.telegram.messenger.ParentalControlManager.getInstance().isChildMode()) {{\n"
            f"            ArrayList<MessageObject> _allowed = new ArrayList<>();\n"
            f"            for (int _i = 0; _i < {msgs_var}.size(); _i++) {{\n"
            f"                MessageObject _m = {msgs_var}.get(_i);\n"
            f"                if (!org.telegram.messenger.ParentalControlManager.getInstance().shouldBlockMessage(_m, currentAccount)) {{\n"
            f"                    _allowed.add(_m);\n"
            f"                }}\n"
            f"            }}\n"
            f"            {msgs_var} = _allowed;\n"
            f"            if ({msgs_var}.isEmpty()) return;\n"
            f"        }}\n"
        )
        content = content[: match.end()] + guard + content[match.end() :]
        print("[INFO] Patched NotificationsController.processNewMessages(...) to silence non-whitelisted channels in Child Mode.")

    with open(path, "w", encoding="utf-8") as f:
        f.write(content)


def main() -> None:
    repo_root = sys.argv[1] if len(sys.argv) > 1 else os.getcwd()
    print(f"[INFO] Applying Telegram Parental Control patches to: {repo_root}")
    patch_default_pin(repo_root)
    patch_build_vars(repo_root)
    patch_dialogs_activity(repo_root)
    patch_messages_controller(repo_root)
    patch_notifications_controller(repo_root)
    print("[INFO] All patches applied successfully.")


if __name__ == "__main__":
    main()
