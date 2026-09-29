package au.com.roningroup.evieassistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SecretStore {
    private const val PREFS = "evie_secure_values"
    private const val KEY_ALIAS = "evie_assistant_aes_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun put(
        context: Context,
        name: String,
        value: String
    ): Boolean {
        return runCatching {
            val key = secretKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)

            val encrypted = cipher.doFinal(
                value.toByteArray(Charsets.UTF_8)
            )

            val payload =
                Base64.encodeToString(
                    cipher.iv,
                    Base64.NO_WRAP
                ) +
                    ":" +
                    Base64.encodeToString(
                        encrypted,
                        Base64.NO_WRAP
                    )

            context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )
                .edit()
                .putString(name, payload)
                .apply()

            true
        }.getOrDefault(false)
    }

    fun get(
        context: Context,
        name: String
    ): String {
        val payload =
            context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )
                .getString(name, null)
                ?: return ""

        return runCatching {
            val separator = payload.indexOf(':')
            require(separator > 0)

            val iv = Base64.decode(
                payload.substring(0, separator),
                Base64.NO_WRAP
            )

            val encrypted = Base64.decode(
                payload.substring(separator + 1),
                Base64.NO_WRAP
            )

            val cipher = Cipher.getInstance(
                TRANSFORMATION
            )

            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, iv)
            )

            String(
                cipher.doFinal(encrypted),
                Charsets.UTF_8
            )
        }.getOrDefault("")
    }

    fun remove(
        context: Context,
        name: String
    ) {
        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )
            .edit()
            .remove(name)
            .apply()
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(
            "AndroidKeyStore"
        ).apply {
            load(null)
        }

        val existing =
            store.getKey(
                KEY_ALIAS,
                null
            ) as? SecretKey

        if (existing != null) {
            return existing
        }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )

        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or
                    KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(
                    KeyProperties.BLOCK_MODE_GCM
                )
                .setEncryptionPaddings(
                    KeyProperties.ENCRYPTION_PADDING_NONE
                )
                .setKeySize(256)
                .build()
        )

        return generator.generateKey()
    }
}
