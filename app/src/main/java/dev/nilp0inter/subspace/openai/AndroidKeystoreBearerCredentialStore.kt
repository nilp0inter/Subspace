package dev.nilp0inter.subspace.openai

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.nilp0inter.subspace.model.OpenAiCredentialReference
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

sealed interface OpenAiCredentialStoreError {
    val message: String

    data object Missing : OpenAiCredentialStoreError {
        override val message = "Bearer credential is unavailable"
    }

    data object InvalidCredential : OpenAiCredentialStoreError {
        override val message = "Bearer credential is invalid"
    }

    data object ProtectedStorageUnavailable : OpenAiCredentialStoreError {
        override val message = "Protected credential storage is unavailable"
    }

    data object CorruptCredential : OpenAiCredentialStoreError {
        override val message = "Stored bearer credential is invalid"
    }
}

sealed interface OpenAiCredentialStoreResult<out T> {
    data class Success<T>(val value: T) : OpenAiCredentialStoreResult<T>
    data class Failure(val error: OpenAiCredentialStoreError) : OpenAiCredentialStoreResult<Nothing>
}

/**
 * A deliberately non-projectable secret boundary. Callers can use a credential only inside a
 * host-owned operation; no profile, channel configuration, or UI projection receives it.
 */
interface OpenAiBearerCredentialStore {
    fun replace(reference: OpenAiCredentialReference, bearerToken: CharSequence): OpenAiCredentialStoreResult<Unit>
    fun delete(reference: OpenAiCredentialReference): OpenAiCredentialStoreResult<Unit>
    fun contains(reference: OpenAiCredentialReference): Boolean
    fun <T> use(reference: OpenAiCredentialReference, block: (CharSequence) -> T): OpenAiCredentialStoreResult<T>
}

/**
 * Android Keystore owns the AES key. SharedPreferences stores only AES-GCM ciphertext and IV,
 * keyed by a SHA-256-derived opaque reference. Bearer tokens are never serialized in metadata.
 */
class AndroidKeystoreBearerCredentialStore(
    context: Context,
    private val preferences: SharedPreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
) : OpenAiBearerCredentialStore {
    override fun replace(
        reference: OpenAiCredentialReference,
        bearerToken: CharSequence,
    ): OpenAiCredentialStoreResult<Unit> {
        if (bearerToken.isBlank()) return OpenAiCredentialStoreResult.Failure(OpenAiCredentialStoreError.InvalidCredential)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(bearerToken.toString().toByteArray(StandardCharsets.UTF_8))
            val record = encode(cipher.iv, encrypted)
            if (!preferences.edit().putString(referenceKey(reference), record).commit()) {
                OpenAiCredentialStoreResult.Failure(OpenAiCredentialStoreError.ProtectedStorageUnavailable)
            } else {
                OpenAiCredentialStoreResult.Success(Unit)
            }
        } catch (_: Exception) {
            OpenAiCredentialStoreResult.Failure(OpenAiCredentialStoreError.ProtectedStorageUnavailable)
        }
    }

    override fun delete(reference: OpenAiCredentialReference): OpenAiCredentialStoreResult<Unit> = try {
        if (!preferences.edit().remove(referenceKey(reference)).commit()) {
            OpenAiCredentialStoreResult.Failure(OpenAiCredentialStoreError.ProtectedStorageUnavailable)
        } else {
            OpenAiCredentialStoreResult.Success(Unit)
        }
    } catch (_: Exception) {
        OpenAiCredentialStoreResult.Failure(OpenAiCredentialStoreError.ProtectedStorageUnavailable)
    }

    override fun contains(reference: OpenAiCredentialReference): Boolean = preferences.contains(referenceKey(reference))

    override fun <T> use(
        reference: OpenAiCredentialReference,
        block: (CharSequence) -> T,
    ): OpenAiCredentialStoreResult<T> {
        val stored = preferences.getString(referenceKey(reference), null)
            ?: return OpenAiCredentialStoreResult.Failure(OpenAiCredentialStoreError.Missing)
        return try {
            val (iv, ciphertext) = decode(stored)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
            val credential = cipher.doFinal(ciphertext).toString(StandardCharsets.UTF_8)
            try {
                OpenAiCredentialStoreResult.Success(block(credential))
            } finally {
                // The platform/String API cannot guarantee zeroization, but this value is not retained here.
            }
        } catch (_: IllegalArgumentException) {
            OpenAiCredentialStoreResult.Failure(OpenAiCredentialStoreError.CorruptCredential)
        } catch (_: Exception) {
            OpenAiCredentialStoreResult.Failure(OpenAiCredentialStoreError.ProtectedStorageUnavailable)
        }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()
    }

    private fun referenceKey(reference: OpenAiCredentialReference): String =
        "credential.${MessageDigest.getInstance("SHA-256").digest(reference.value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }}"

    private fun encode(iv: ByteArray, ciphertext: ByteArray): String =
        android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP) + "." +
            android.util.Base64.encodeToString(ciphertext, android.util.Base64.NO_WRAP)

    private fun decode(record: String): Pair<ByteArray, ByteArray> {
        val components = record.split('.', limit = 2)
        require(components.size == 2)
        return android.util.Base64.decode(components[0], android.util.Base64.NO_WRAP) to
            android.util.Base64.decode(components[1], android.util.Base64.NO_WRAP)
    }

    private companion object {
        const val PREFERENCES_NAME = "openai-bearer-credentials"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "subspace.openai.bearer.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
