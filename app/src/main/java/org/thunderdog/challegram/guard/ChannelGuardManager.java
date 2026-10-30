/*
 * This file is part of Telegram Channel Guard (adapted from Telegram X).
 * Copyright (C) 2026 Telegram Channel Guard Contributors
 *
 * Licensed under the GNU General Public License v3.0 (GPL-3.0).
 * See LICENSE file in the project root for full license information.
 */
package org.thunderdog.challegram.guard;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.widget.Toast;

import org.drinkless.tdlib.TdApi;
import org.json.JSONArray;
import org.json.JSONObject;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.UI;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;

public final class ChannelGuardManager {
  private static final String PREFS_NAME = "channel_guard_secure_prefs";
  private static final String KEYSTORE_ALIAS = "ChannelGuardMasterKey_v1";
  private static final String KEY_PIN_BLOB = "pin_encrypted_blob";
  private static final String KEY_ALLOWLIST_BLOB = "allowlist_encrypted_blob";
  private static final String KEY_FAILED_ATTEMPTS = "pin_failed_attempts";
  private static final String KEY_LOCKOUT_UNTIL = "pin_lockout_until_ms";

  private static final int PBKDF2_ITERATIONS = 65536;
  private static final int SALT_BYTES = 16;
  private static final int HASH_BITS = 256;
  private static final int GCM_TAG_BITS = 128;

  public static final class ApprovedChannel {
    public final long chatId;
    public final String title;
    public final String username;
    public final long addedAtMs;

    public ApprovedChannel(long chatId, String title, String username, long addedAtMs) {
      this.chatId = chatId;
      this.title = title != null ? title : ("Channel " + chatId);
      this.username = username != null ? username : "";
      this.addedAtMs = addedAtMs;
    }
  }

  public interface AllowlistChangeListener {
    void onAllowlistChanged();
  }

  private static volatile ChannelGuardManager instance;

  private final SharedPreferences prefs;
  private final Map<Long, ApprovedChannel> approvedChannels = new ConcurrentHashMap<>();
  private final List<AllowlistChangeListener> listeners = new CopyOnWriteArrayList<>();

  public static ChannelGuardManager getInstance() {
    if (instance == null) {
      synchronized (ChannelGuardManager.class) {
        if (instance == null) {
          instance = new ChannelGuardManager(UI.getAppContext());
        }
      }
    }
    return instance;
  }

  private ChannelGuardManager(Context context) {
    this.prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    ensureKeystoreKey();
    loadAllowlistFromDisk();
  }

  private void ensureKeystoreKey() {
    try {
      KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
      ks.load(null);
      if (!ks.containsAlias(KEYSTORE_ALIAS)) {
        KeyGenerator keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build();
        keyGenerator.init(spec);
        keyGenerator.generateKey();
      }
    } catch (Exception e) {
      throw new RuntimeException("Failed to initialize Android Keystore for Channel Guard", e);
    }
  }

  private SecretKey getMasterKey() throws Exception {
    KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
    ks.load(null);
    return ((KeyStore.SecretKeyEntry) ks.getEntry(KEYSTORE_ALIAS, null)).getSecretKey();
  }

  private String encryptWithKeystore(byte[] plainBytes) throws Exception {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, getMasterKey());
    byte[] iv = cipher.getIV();
    byte[] cipherBytes = cipher.doFinal(plainBytes);
    byte[] combined = new byte[1 + iv.length + cipherBytes.length];
    combined[0] = (byte) iv.length;
    System.arraycopy(iv, 0, combined, 1, iv.length);
    System.arraycopy(cipherBytes, 0, combined, 1 + iv.length, cipherBytes.length);
    return Base64.encodeToString(combined, Base64.NO_WRAP);
  }

  private byte[] decryptWithKeystore(String encodedBlob) throws Exception {
    byte[] combined = Base64.decode(encodedBlob, Base64.NO_WRAP);
    int ivLength = combined[0] & 0xFF;
    byte[] iv = new byte[ivLength];
    System.arraycopy(combined, 1, iv, 0, ivLength);
    int cipherLen = combined.length - 1 - ivLength;
    byte[] cipherBytes = new byte[cipherLen];
    System.arraycopy(combined, 1 + ivLength, cipherBytes, 0, cipherLen);

    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_BITS, iv);
    cipher.init(Cipher.DECRYPT_MODE, getMasterKey(), spec);
    return cipher.doFinal(cipherBytes);
  }

  private byte[] derivePbkdf2(String pin, byte[] salt) throws Exception {
    PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, HASH_BITS);
    try {
      SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
      return skf.generateSecret(spec).getEncoded();
    } finally {
      spec.clearPassword();
    }
  }

  public boolean isPinConfigured() {
    return prefs.contains(KEY_PIN_BLOB);
  }

  public long getRemainingLockoutSeconds() {
    long until = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L);
    long diff = until - System.currentTimeMillis();
    return diff > 0 ? (diff + 999) / 1000 : 0;
  }

  public synchronized boolean setOrUpdatePin(String currentPinOrNull, String newPin) {
    if (newPin == null || newPin.length() < 4 || newPin.length() > 16) {
      return false;
    }
    if (isPinConfigured()) {
      if (currentPinOrNull == null || !verifyPin(currentPinOrNull)) {
        return false;
      }
    }
    try {
      byte[] salt = new byte[SALT_BYTES];
      new SecureRandom().nextBytes(salt);
      byte[] hash = derivePbkdf2(newPin, salt);

      byte[] payload = new byte[SALT_BYTES + hash.length];
      System.arraycopy(salt, 0, payload, 0, SALT_BYTES);
      System.arraycopy(hash, 0, payload, SALT_BYTES, hash.length);

      String encryptedBlob = encryptWithKeystore(payload);
      prefs.edit()
          .putString(KEY_PIN_BLOB, encryptedBlob)
          .putInt(KEY_FAILED_ATTEMPTS, 0)
          .putLong(KEY_LOCKOUT_UNTIL, 0L)
          .commit();
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  public synchronized boolean verifyPin(String candidatePin) {
    if (candidatePin == null || !isPinConfigured()) {
      return false;
    }
    if (getRemainingLockoutSeconds() > 0) {
      return false;
    }
    try {
      String blob = prefs.getString(KEY_PIN_BLOB, null);
      if (blob == null) return false;
      byte[] payload = decryptWithKeystore(blob);
      if (payload.length <= SALT_BYTES) return false;

      byte[] salt = new byte[SALT_BYTES];
      byte[] expectedHash = new byte[payload.length - SALT_BYTES];
      System.arraycopy(payload, 0, salt, 0, SALT_BYTES);
      System.arraycopy(payload, SALT_BYTES, expectedHash, 0, expectedHash.length);

      byte[] actualHash = derivePbkdf2(candidatePin, salt);
      boolean matches = MessageDigest.isEqual(expectedHash, actualHash);
      if (matches) {
        prefs.edit()
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCKOUT_UNTIL, 0L)
            .commit();
        return true;
      } else {
        recordFailedAttempt();
        return false;
      }
    } catch (Exception e) {
      recordFailedAttempt();
      return false;
    }
  }

  private void recordFailedAttempt() {
    int attempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1;
    long lockoutMs = 0L;
    if (attempts >= 5) {
      int shift = Math.min(attempts - 5, 6);
      lockoutMs = System.currentTimeMillis() + (30_000L * (1L << shift));
    }
    prefs.edit()
        .putInt(KEY_FAILED_ATTEMPTS, attempts)
        .putLong(KEY_LOCKOUT_UNTIL, lockoutMs)
        .commit();
  }

  private synchronized void loadAllowlistFromDisk() {
    approvedChannels.clear();
    String blob = prefs.getString(KEY_ALLOWLIST_BLOB, null);
    if (blob == null || blob.isEmpty()) {
      return;
    }
    try {
      byte[] jsonBytes = decryptWithKeystore(blob);
      JSONArray array = new JSONArray(new String(jsonBytes, StandardCharsets.UTF_8));
      for (int i = 0; i < array.length(); i++) {
        JSONObject obj = array.getJSONObject(i);
        long chatId = obj.getLong("chatId");
        String title = obj.optString("title", "Channel " + chatId);
        String username = obj.optString("username", "");
        long addedAt = obj.optLong("addedAt", 0L);
        approvedChannels.put(chatId, new ApprovedChannel(chatId, title, username, addedAt));
      }
    } catch (Exception ignored) {
      approvedChannels.clear();
    }
  }

  private synchronized void saveAllowlistToDisk() {
    try {
      JSONArray array = new JSONArray();
      for (ApprovedChannel ch : approvedChannels.values()) {
        JSONObject obj = new JSONObject();
        obj.put("chatId", ch.chatId);
        obj.put("title", ch.title);
        obj.put("username", ch.username);
        obj.put("addedAt", ch.addedAtMs);
        array.put(obj);
      }
      byte[] raw = array.toString().getBytes(StandardCharsets.UTF_8);
      String encrypted = encryptWithKeystore(raw);
      prefs.edit().putString(KEY_ALLOWLIST_BLOB, encrypted).commit();
    } catch (Exception ignored) {
    }
  }

  public void addChannel(long chatId, String title, String username) {
    ApprovedChannel item = new ApprovedChannel(chatId, title, username, System.currentTimeMillis());
    approvedChannels.put(chatId, item);
    saveAllowlistToDisk();
    notifyAllowlistChanged();
  }

  public void removeChannel(long chatId) {
    if (approvedChannels.remove(chatId) != null) {
      saveAllowlistToDisk();
      notifyAllowlistChanged();
    }
  }

  public boolean isChannelAllowed(long chatId) {
    return approvedChannels.containsKey(chatId);
  }

  public List<ApprovedChannel> getApprovedChannels() {
    List<ApprovedChannel> list = new ArrayList<>(approvedChannels.values());
    Collections.sort(list, (a, b) -> a.title.compareToIgnoreCase(b.title));
    return list;
  }

  public boolean isChannelBlocked(Tdlib tdlib, long chatId) {
    if (tdlib == null || chatId == 0) {
      return false;
    }
    TdApi.Chat chat = tdlib.chat(chatId);
    if (chat != null) {
      return isChannelBlocked(chat);
    }
    if (tdlib.isChannel(chatId)) {
      return !isChannelAllowed(chatId);
    }
    return false;
  }

  public boolean isChannelBlocked(TdApi.Chat chat) {
    if (chat == null || chat.type == null) {
      return false;
    }
    if (chat.type.getConstructor() == TdApi.ChatTypeSupergroup.CONSTRUCTOR) {
      TdApi.ChatTypeSupergroup supergroupType = (TdApi.ChatTypeSupergroup) chat.type;
      if (supergroupType.isChannel) {
        return !isChannelAllowed(chat.id);
      }
    }
    return false;
  }

  public void notifyBlockedAttempt() {
    UI.post(() -> {
      if (UI.getAppContext() != null) {
        Toast.makeText(
            UI.getAppContext(),
            "Access blocked: This channel is not on your Channel Guard allowlist.",
            Toast.LENGTH_SHORT
        ).show();
      }
    });
  }

  public void addListener(AllowlistChangeListener listener) {
    if (!listeners.contains(listener)) {
      listeners.add(listener);
    }
  }

  public void removeListener(AllowlistChangeListener listener) {
    listeners.remove(listener);
  }

  private void notifyAllowlistChanged() {
    UI.post(() -> {
      for (AllowlistChangeListener listener : listeners) {
        listener.onAllowlistChanged();
      }
    });
  }
}
