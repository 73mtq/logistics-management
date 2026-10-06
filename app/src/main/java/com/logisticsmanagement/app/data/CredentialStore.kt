package com.logisticsmanagement.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences("encrypted_credentials", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val alias = "logistics_credentials_v1"
    private fun key(): SecretKey {
        if (!keyStore.containsAlias(alias)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
                generateKey()
            }
        }
        return keyStore.getKey(alias, null) as SecretKey
    }
    @Synchronized fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val buffer = ByteBuffer.allocate(4 + cipher.iv.size + encrypted.size)
        buffer.putInt(cipher.iv.size).put(cipher.iv).put(encrypted)
        return Base64.encodeToString(buffer.array(), Base64.NO_WRAP)
    }
    @Synchronized fun decrypt(value: String): String {
        val buffer = ByteBuffer.wrap(Base64.decode(value, Base64.NO_WRAP))
        val size = buffer.int
        require(size == 12 && buffer.remaining() > size) { "凭据数据格式错误" }
        val iv = ByteArray(size).also(buffer::get)
        val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }
    fun getSecret(name: String): String? = preferences.getString(name, null)?.let(::decrypt)
    fun putSecret(name: String, value: String) { check(preferences.edit().putString(name, encrypt(value)).commit()) }
    fun deleteSecret(name: String) { check(preferences.edit().remove(name).commit()) }
    fun deleteAccount(accountId: String) {
        val edit = preferences.edit()
        preferences.all.keys.filter { it.startsWith("account.$accountId.") }.forEach(edit::remove)
        check(edit.commit())
    }
}
