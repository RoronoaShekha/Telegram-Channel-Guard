# Legal Notice & GPL-3.0 Compliance

**Telegram Channel Guard** is a modified derivative work of the open-source **Telegram X for Android** project:
- Upstream Repository: https://github.com/TGX-Android/Telegram-X
- License: GNU General Public License v3.0 (GPL-3.0)

## Summary of Modifications (October 2026)
1. Added `org.thunderdog.challegram.guard.ChannelGuardManager`: Hardware-backed (Android Keystore AES-256-GCM + PBKDF2-HMAC-SHA256) PIN authentication and persistent channel allowlist keyed by 64-bit TDLib chat IDs.
2. Added `org.thunderdog.challegram.guard.ChannelGuardController`: PIN-protected allowlist management interface supporting public (`@username`), private (`t.me/+invite`), and joined channels.
3. Modified `TdlibUi.java`, `MessagesController.java`, `TdlibNotificationManager.java`, and `SettingsController.java` to block unapproved broadcast channels across navigation, deep links, previews, and notifications while preserving private 1-on-1 chats and normal groups.
