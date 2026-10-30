#!/usr/bin/env python3
import os
import re
import sys

def patch_file(path, transform_fn):
    if not os.path.exists(path):
        print(f"[WARN] File not found: {path}", file=sys.stderr)
        return False
    with open(path, "r", encoding="utf-8") as f:
        original = f.read()
    updated = transform_fn(original)
    if updated == original:
        print(f"[WARN] Pattern not modified in {path}", file=sys.stderr)
        return False
    with open(path, "w", encoding="utf-8") as f:
        f.write(updated)
    print(f"[OK] Patched {path}")
    return True

def patch_tdlib_ui(content):
    import_stmt = "import org.thunderdog.challegram.guard.ChannelGuardManager;\n"
    if import_stmt not in content:
        content = content.replace("package org.thunderdog.challegram.telegram;\n",
                                  "package org.thunderdog.challegram.telegram;\n\n" + import_stmt, 1)
    pattern = re.compile(
        r"(public\s+void\s+openChat\s*\([^)]*long\s+chatId[^)]*\)\s*\{)"
    )
    guard_check = (
        r"\1\n"
        r"    if (ChannelGuardManager.getInstance().isChannelBlocked(tdlib, chatId)) {\n"
        r"      ChannelGuardManager.getInstance().notifyBlockedAttempt();\n"
        r"      return;\n"
        r"    }"
    )
    return pattern.sub(guard_check, content)

def patch_messages_controller(content):
    import_stmt = "import org.thunderdog.challegram.guard.ChannelGuardManager;\n"
    if import_stmt not in content:
        content = content.replace("package org.thunderdog.challegram.ui;\n",
                                  "package org.thunderdog.challegram.ui;\n\n" + import_stmt, 1)
    pattern = re.compile(
        r"(private\s+void\s+setChat\s*\(\s*TdApi\.Chat\s+chat\b[^)]*\)\s*\{)"
    )
    guard_check = (
        r"\1\n"
        r"    if (chat != null && ChannelGuardManager.getInstance().isChannelBlocked(chat)) {\n"
        r"      ChannelGuardManager.getInstance().notifyBlockedAttempt();\n"
        r"      navigateBack();\n"
        r"      return;\n"
        r"    }"
    )
    if pattern.search(content):
        content = pattern.sub(guard_check, content)
    return content

def patch_notification_manager(content):
    import_stmt = "import org.thunderdog.challegram.guard.ChannelGuardManager;\n"
    if import_stmt not in content:
        content = content.replace("package org.thunderdog.challegram.telegram;\n",
                                  "package org.thunderdog.challegram.telegram;\n\n" + import_stmt, 1)
    pattern = re.compile(
        r"(public\s+void\s+onUpdateNotificationGroup\s*\(\s*TdApi\.UpdateNotificationGroup\s+update\s*\)\s*\{)"
    )
    guard_check = (
        r"\1\n"
        r"    if (update != null && ChannelGuardManager.getInstance().isChannelBlocked(tdlib, update.chatId)) {\n"
        r"      return;\n"
        r"    }"
    )
    return pattern.sub(guard_check, content)

def patch_settings_controller(content):
    imports = (
        "import org.thunderdog.challegram.guard.ChannelGuardController;\n"
        "import org.thunderdog.challegram.guard.ChannelGuardManager;\n"
    )
    if "ChannelGuardController" not in content:
        content = content.replace("package org.thunderdog.challegram.ui;\n",
                                  "package org.thunderdog.challegram.ui;\n\n" + imports, 1)
    pattern = re.compile(r"(case\s+R\.id\.btn_privacySecurity\s*:)बाड़ी") # wait, remove typo
    pattern = re.compile(r"(case\s+R\.id\.btn_privacySecurity\s*:)".encode('utf-8').decode('utf-8'))
    replacement = (
        r"\1\n"
        r"        if (v != null && v.getTag() instanceof String && \"CHANNEL_GUARD\".equals(v.getTag())) {\n"
        r"          navigateTo(new ChannelGuardController(context, tdlib));\n"
        r"          return;\n"
        r"        }"
    )
    content = pattern.sub(replacement, content, count=1)

    item_pattern = re.compile(r"(new\s+ListItem\s*\(\s*ListItem\.TYPE_VALUED_SETTING[^\n]+R\.id\.btn_privacySecurity[^\n]+\))")
    item_injection = (
        r"new ListItem(ListItem.TYPE_SETTING, R.id.btn_privacySecurity, R.drawable.baseline_lock_24, \"Channel Guard (PIN Allowlist)\", false).setTag(\"CHANNEL_GUARD\"),\n      \1"
    )
    if item_pattern.search(content):
        content = item_pattern.sub(item_injection, content, count=1)
    else:
        alt_pattern = re.compile(r"(new\s+ListItem\s*\(\s*ListItem\.TYPE_SETTING[^\n]+R\.id\.btn_privacySecurity[^\n]+\))")
        content = alt_pattern.sub(item_injection, content, count=1)

    return content

def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    java_root = os.path.join(root, "app", "src", "main", "java", "org", "thunderdog", "challegram")

    patch_file(os.path.join(java_root, "telegram", "TdlibUi.java"), patch_tdlib_ui)
    patch_file(os.path.join(java_root, "ui", "MessagesController.java"), patch_messages_controller)
    patch_file(os.path.join(java_root, "telegram", "TdlibNotificationManager.java"), patch_notification_manager)
    patch_file(os.path.join(java_root, "ui", "SettingsController.java"), patch_settings_controller)

if __name__ == "__main__":
    main()
