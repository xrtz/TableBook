package com.example.tablebook.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONException
import org.json.JSONObject

internal interface SessionStorage {
    fun read(): SessionTokens?
    fun write(tokens: SessionTokens)
    fun clear()
}

internal class KeystoreSessionStorage(context: Context) : SessionStorage {
    private val file = AtomicFile(File(context.noBackupFilesDir, "session"))

    override fun read(): SessionTokens? = try {
        val encrypted = file.readFully()
        if (encrypted.size <= ivSize) throw GeneralSecurityException()
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encrypted, 0, ivSize))
        val json = JSONObject(cipher.doFinal(encrypted, ivSize, encrypted.size - ivSize).toString(Charsets.UTF_8))
        val tokens = SessionTokens(json.getString("accessToken"), json.getString("refreshToken"))
        val scope = json.optString("scope")
        if (scope.isEmpty()) {
            write(tokens)
            tokens
        } else tokens.copy(scope = scope)
    } catch (_: FileNotFoundException) {
        null
    } catch (_: GeneralSecurityException) {
        clear()
        null
    } catch (_: JSONException) {
        clear()
        null
    }

    override fun write(tokens: SessionTokens) {
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val json = JSONObject()
            .put("accessToken", tokens.accessToken)
            .put("refreshToken", tokens.refreshToken)
            .put("scope", tokens.scope)
        val encrypted = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try {
            stream.write(encrypted)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    override fun clear() {
        file.delete()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ivSize = 12
        const val keyAlias = "tablebook-session"
        const val transformation = "AES/GCM/NoPadding"
    }
}
