# Telegram Android Fork — Parent / Child Channel Whitelist Mode

This repository packages a lightweight, automated source overlay and GitHub Actions build pipeline for the official [DrKLO/Telegram](https://github.com/DrKLO/Telegram) Android client. It adds **PIN-protected Parent Mode & Child Mode** with a strict **Channel/Chat Whitelist**.

## Core Architecture & Implementation

1. **`ParentalControlManager.java` (`org.telegram.messenger.ParentalControlManager`)**:
   - **PIN Authentication**: Stores a salted SHA-256 hash of the Parent PIN in `SharedPreferences` (`tg_parental_control_prefs`). Default PIN is `1234` (customizable in-app or via the `DEFAULT_PARENT_PIN` GitHub Secret).
   - **Parent Mode**: Full access to all Telegram channels, groups, chats, and search. Allows selecting which subscribed channels/chats are added to the Child Mode whitelist.
   - **Child Mode**: Strictly filters the dialog list, incoming live message updates, and push/local notifications so **only whitelisted channels/chats and their messages appear**.
   - **Zero-XML Programmatic UI**: Uses native Android `AlertDialog` views triggered by **tapping the Lock icon on the top ActionBar** (or **long-pressing the top ActionBar**) on the main chat list screen (`DialogsActivity`), avoiding fragile layout XML conflicts across upstream Telegram updates.

2. **Automated Upstream Hooks (`scripts/apply_parental_patch.py`)**:
   - **`DialogsActivity.getDialogsArray(...)`**: Wraps the dialog list provider so non-whitelisted channels and chats are stripped out whenever Child Mode is active.
   - **`MessagesController.updateInterfaceWithMessages(...)`**: Drops real-time incoming messages from non-whitelisted dialogs while in Child Mode.
   - **`NotificationsController.processNewMessages(...)`**: Suppresses notifications for non-whitelisted channels/chats in Child Mode.
   - **`BuildVars.java`**: Injects your Telegram API credentials from GitHub Repository Secrets during the workflow run.

---

## GitHub Repository Secrets Setup

The GitHub Actions workflow (`.github/workflows/build-apk.yml`) automatically reads any of the following secret names from **Settings → Secrets and variables → Actions**:

| Secret Name (Supported Aliases) | Required | Description |
| :--- | :--- | :--- |
| `APP_ID` *(or `TELEGRAM_APP_ID` / `API_ID` / `TG_APP_ID`)* | **Yes** | Your numeric Telegram `api_id` from [my.telegram.org](https://my.telegram.org) |
| `APP_HASH` *(or `TELEGRAM_APP_HASH` / `API_HASH` / `TG_APP_HASH`)* | **Yes** | Your 32-character Telegram `api_hash` from [my.telegram.org](https://my.telegram.org) |
| `DEFAULT_PARENT_PIN` *(or `PARENT_PIN`)* | Optional | Initial Parent Mode PIN compiled into the APK (defaults to `1234`) |
| `GOOGLE_SERVICES_JSON` | Optional | Raw contents of `google-services.json` (if omitted, the build script generates a valid fallback automatically) |
| `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` | Optional | Custom signing keystore (if omitted, builds with an auto-generated Android keystore) |

---

## Quick Start: Push to GitHub & Build APK

1. Extract this `.zip` archive and push it to your GitHub repository:
   ```bash
   unzip telegram-parental-fork-repo.zip -d telegram-parental-fork
   cd telegram-parental-fork
   git init
   git branch -M main
   git add .
   git commit -m "Initial commit: Telegram Parent/Child channel whitelist fork"
   git remote add origin https://github.com/<your-username>/<your-repo>.git
   git push -u origin main
   ```
2. Open the **Actions** tab in your GitHub repository and select **Build Telegram Parental Control APK** (runs automatically on push to `main`/`master` or manually via **Run workflow**).
3. Once the workflow finishes, download the `telegram-parental-control-apk` artifact containing your compiled `.apk`.

---

## How to Use Parent & Child Mode in the App

1. **Open the Parent Control Panel**:
   - On the main Telegram chat list screen, **tap the Lock icon in the top ActionBar** (or **long-press the top ActionBar**).
   - Enter the Parent PIN (default: `1234`) and tap **Unlock**.
2. **Configure Whitelisted Channels (Parent Mode)**:
   - Tap **Manage Whitelisted Channels** to check/uncheck any joined channels or chats, or tap **Add Channel/Chat ID Manually** to enter a numeric Dialog ID (e.g., `-1001234567890`).
   - Tap **Save Whitelist**.
3. **Activate Child Mode**:
   - Re-open the Parent Control Panel and tap **Switch to Child Mode**.
   - Immediately, only the whitelisted channels and their messages remain visible.
4. **Return to Parent Mode**:
   - Tap the Lock icon (or long-press the top ActionBar), enter the Parent PIN, and tap **Switch to Parent Mode**.
