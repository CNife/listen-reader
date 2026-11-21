package com.cnife.listenreader.data.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface KeyEncryptor {
    fun encrypt(plain: String): String?
    fun decrypt(token: String): String?
}

class EncryptedKeyStore(private val encryptor: KeyEncryptor = KeystoreKeyEncryptor()) {

    fun encrypt(plain: String): String? =
        if (plain.isBlank()) null else encryptor.encrypt(plain)

    fun decrypt(token: String?): String? {
        if (token.isNullOrBlank()) return null
        return encryptor.decrypt(token)?.takeIf { it.isNotBlank() }
    }
}

class KeystoreKeyEncryptor(private val alias: String = DEFAULT_ALIAS) : KeyEncryptor {

    override fun encrypt(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val payload = ByteArray(2 + iv.size + ciphertext.size)
        payload[0] = VERSION
        payload[1] = iv.size.toByte()
        iv.copyInto(payload, destinationOffset = 2)
        ciphertext.copyInto(payload, destinationOffset = 2 + iv.size)
        Base64.encodeToString(payload, Base64.NO_WRAP)
    }.getOrNull()

    override fun decrypt(token: String): String? = runCatching {
        val payload = Base64.decode(token, Base64.NO_WRAP)
        if (payload.size < 3 || payload[0] != VERSION) return@runCatching null
        val ivSize = payload[1].toInt() and 0xFF
        if (ivSize <= 0 || payload.size <= 2 + ivSize) return@runCatching null
        val iv = payload.copyOfRange(2, 2 + ivSize)
        val ciphertext = payload.copyOfRange(2 + ivSize, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }.getOrNull()

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val DEFAULT_ALIAS = "listen_reader_key"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val VERSION: Byte = 1
    }
}
