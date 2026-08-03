package cn.termux.ubuntumanager.data

import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class RootPasswordStoreTest {
    @Test
    fun passwordCipherRoundTripsWithoutPlaintextStorage() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val associatedData = "instance:ubuntu".toByteArray()

        val encrypted = PasswordCipher.encrypt(key, associatedData, "root1234")

        assertFalse(encrypted.contains("root1234"))
        assertEquals(
            "root1234",
            PasswordCipher.decrypt(key, associatedData, encrypted),
        )
    }

    @Test
    fun passwordCipherRejectsMovingRecordToAnotherInstance() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val encrypted = PasswordCipher.encrypt(
            key,
            "instance:ubuntu".toByteArray(),
            "root1234",
        )

        try {
            PasswordCipher.decrypt(key, "instance:ubuntu-copy".toByteArray(), encrypted)
            fail("使用其他实例名称时必须拒绝解密")
        } catch (_: Exception) {
            // AES-GCM authentication failure is expected.
        }
    }
}
