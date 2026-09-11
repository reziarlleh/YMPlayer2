package dev.petrov.ymplayer2.yandex

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Per-profile encrypted files excluded from backup. No plaintext or software-key fallback. */
class KeystoreAccountStore(context: Context) : AccountStore {
    private val directory = File(context.applicationContext.noBackupFilesDir, "yandex-accounts")
    private fun file(profileId: String): AtomicFile {
        require(profileId.matches(Regex("[a-zA-Z0-9_-]{1,64}")))
        return AtomicFile(File(directory, "$profileId.bin"))
    }
    private fun key(profileId: String, create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "ymplayer2.yandex.$profileId"
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    override suspend fun read(profileId: String): AccountSession? = withContext(Dispatchers.IO) {
        try {
            val file = file(profileId)
            if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@withContext null
            check(file.baseFile.length() <= 65536 && File(file.baseFile.path + ".bak").length() <= 65536)
            val bytes = file.readFully()
            check(bytes.size in 30..65536 && bytes[0] == 1.toByte())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(profileId, false), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
            cipher.updateAAD(profileId.toByteArray(Charsets.UTF_8))
            val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(13, bytes.size)), Charsets.UTF_8))
            check(json.getInt("schema") == 1)
            AccountSession(YandexAccount(json.getString("id"), json.getString("name")),
                OAuthCredentials(json.getString("access"), json.optString("refresh").takeIf { it.isNotEmpty() },
                    if (json.has("expires")) json.getLong("expires") else null))
        } catch (_: Exception) { throw AuthException(AuthFailure.STORAGE) }
    }
    override suspend fun write(profileId: String, session: AccountSession?) = withContext(Dispatchers.IO) {
        try {
            val file = file(profileId)
            if (session == null) {
                // AtomicFile deletion can fail silently on some filesystems: verify the result.
                if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@withContext
                file.delete()
                check(!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists())
                return@withContext
            }
            check(directory.isDirectory || directory.mkdirs())
            val json = JSONObject().put("schema", 1).put("id", session.account.id).put("name", session.account.name)
                .put("access", session.credentials.accessToken).put("refresh", session.credentials.refreshToken)
                .put("expires", session.credentials.expiresAtMillis)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(profileId, true))
            cipher.updateAAD(profileId.toByteArray(Charsets.UTF_8))
            val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
            check(cipher.iv.size == 12 && bytes.size <= 65536)
            val output = file.startWrite()
            try { output.write(bytes); file.finishWrite(output) }
            catch (e: Exception) { file.failWrite(output); throw e }
        } catch (_: Exception) { throw AuthException(AuthFailure.STORAGE) }
    }
}
