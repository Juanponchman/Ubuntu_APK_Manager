package cn.termux.ubuntumanager.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SavedRootPassword(
    val password: String,
    val updatedAtEpochMillis: Long,
)

/**
 * Stores only passwords that Ubuntu Manager has successfully written itself.
 *
 * The values in SharedPreferences are AES-GCM ciphertext. The non-exportable key lives in the
 * Android Keystore, and the instance name is authenticated as associated data so records cannot
 * be silently moved between instances.
 */
class RootPasswordStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val mutex = Mutex()

    suspend fun get(name: String): SavedRootPassword? = onStorageThread {
        readRecord(name)
    }

    suspend fun getAll(names: Set<String>): Map<String, SavedRootPassword> = onStorageThread {
        names.mapNotNull { name -> readRecord(name)?.let { name to it } }.toMap()
    }

    suspend fun save(name: String, password: String): SavedRootPassword = onStorageThread {
        val record = SavedRootPassword(password, System.currentTimeMillis())
        writeRecord(name, record)
        record
    }

    suspend fun remove(name: String) = onStorageThread {
        check(
            preferences.edit()
                .remove(passwordKey(name))
                .remove(updatedAtKey(name))
                .commit(),
        ) { "无法删除本地密码记录" }
    }

    suspend fun rename(oldName: String, newName: String) = onStorageThread {
        val record = readRecord(oldName) ?: return@onStorageThread
        writeRecord(newName, record)
        check(
            preferences.edit()
                .remove(passwordKey(oldName))
                .remove(updatedAtKey(oldName))
                .commit(),
        ) { "实例已重命名，但旧密码记录清理失败" }
    }

    suspend fun copy(sourceName: String, targetName: String): SavedRootPassword? =
        onStorageThread {
            val source = readRecord(sourceName) ?: return@onStorageThread null
            val copied = source.copy(updatedAtEpochMillis = System.currentTimeMillis())
            writeRecord(targetName, copied)
            copied
        }

    private suspend fun <T> onStorageThread(block: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block() }
    }

    private fun readRecord(name: String): SavedRootPassword? {
        val encrypted = preferences.getString(passwordKey(name), null) ?: return null
        return try {
            SavedRootPassword(
                password = PasswordCipher.decrypt(
                    key = getOrCreateKey(),
                    associatedData = associatedData(name),
                    encodedEnvelope = encrypted,
                ),
                updatedAtEpochMillis = preferences.getLong(updatedAtKey(name), 0L),
            )
        } catch (_: Exception) {
            // A restored app-data file cannot use a Keystore key from another installation.
            preferences.edit()
                .remove(passwordKey(name))
                .remove(updatedAtKey(name))
                .commit()
            null
        }
    }

    private fun writeRecord(name: String, record: SavedRootPassword) {
        val encrypted = PasswordCipher.encrypt(
            key = getOrCreateKey(),
            associatedData = associatedData(name),
            plaintext = record.password,
        )
        check(
            preferences.edit()
                .putString(passwordKey(name), encrypted)
                .putLong(updatedAtKey(name), record.updatedAtEpochMillis)
                .commit(),
        ) { "无法保存本地密码记录" }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
            }
            .generateKey()
    }

    private fun associatedData(name: String): ByteArray =
        "$ASSOCIATED_DATA_PREFIX$name".toByteArray(StandardCharsets.UTF_8)

    private fun passwordKey(name: String) = "password.$name"
    private fun updatedAtKey(name: String) = "updated_at.$name"

    companion object {
        private const val PREFERENCES_NAME = "encrypted_root_passwords"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "ubuntu_manager_root_password_v1"
        private const val ASSOCIATED_DATA_PREFIX = "cn.termux.ubuntumanager/root/"
    }
}

internal object PasswordCipher {
    private const val VERSION: Byte = 1
    private const val IV_SIZE_BYTES = 12
    private const val GCM_TAG_SIZE_BITS = 128

    fun encrypt(key: SecretKey, associatedData: ByteArray, plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        require(iv.size == IV_SIZE_BYTES) { "Keystore 生成了不支持的 GCM IV 长度" }
        cipher.updateAAD(associatedData)
        val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        val envelope = byteArrayOf(VERSION) + iv + ciphertext
        return Base64.getEncoder().encodeToString(envelope)
    }

    fun decrypt(key: SecretKey, associatedData: ByteArray, encodedEnvelope: String): String {
        val envelope = Base64.getDecoder().decode(encodedEnvelope)
        require(envelope.size > 1 + IV_SIZE_BYTES) { "密码记录长度不正确" }
        require(envelope[0] == VERSION) { "不支持的密码记录版本" }
        val iv = envelope.copyOfRange(1, 1 + IV_SIZE_BYTES)
        val ciphertext = envelope.copyOfRange(1 + IV_SIZE_BYTES, envelope.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_SIZE_BITS, iv))
        cipher.updateAAD(associatedData)
        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }
}
