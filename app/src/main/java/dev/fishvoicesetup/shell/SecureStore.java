package dev.fishvoicesetup.shell;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SecureStore {
    private static final String PREFS = "fish_voice_setup";
    private static final String PREF_KEY = "api_key_cipher";
    private static final String KEY_ALIAS = "FishVoiceSetupKey";

    static void save(Context context, String value) throws Exception {
        SecretKey secret = getOrCreateKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secret);
        String packed = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" +
                Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PREF_KEY, packed).apply();
    }

    static String load(Context context) throws Exception {
        String packed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_KEY, null);
        if (packed == null) return null;
        String[] parts = packed.split(":", 2);
        if (parts.length != 2) return null;
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        SecretKey secret = (SecretKey) ks.getKey(KEY_ALIAS, null);
        if (secret == null) return null;
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, secret, new GCMParameterSpec(128, Base64.decode(parts[0], Base64.DEFAULT)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.DEFAULT)), StandardCharsets.UTF_8);
    }

    static void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(PREF_KEY).apply();
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        SecretKey existing = (SecretKey) ks.getKey(KEY_ALIAS, null);
        if (existing != null) return existing;
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return kg.generateKey();
    }

    private SecureStore() {}
}
