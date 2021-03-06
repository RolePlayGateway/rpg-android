/*
 * Copyright (c) 2020 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.matrix.android.internal.crypto.secrets

import im.vector.matrix.android.api.MatrixCallback
import im.vector.matrix.android.api.extensions.orFalse
import im.vector.matrix.android.api.listeners.ProgressListener
import im.vector.matrix.android.api.session.accountdata.AccountDataService
import im.vector.matrix.android.api.session.events.model.toContent
import im.vector.matrix.android.api.session.securestorage.EncryptedSecretContent
import im.vector.matrix.android.api.session.securestorage.IntegrityResult
import im.vector.matrix.android.api.session.securestorage.KeyInfo
import im.vector.matrix.android.api.session.securestorage.KeyInfoResult
import im.vector.matrix.android.api.session.securestorage.KeySigner
import im.vector.matrix.android.api.session.securestorage.RawBytesKeySpec
import im.vector.matrix.android.api.session.securestorage.SecretStorageKeyContent
import im.vector.matrix.android.api.session.securestorage.SharedSecretStorageError
import im.vector.matrix.android.api.session.securestorage.SharedSecretStorageService
import im.vector.matrix.android.api.session.securestorage.SsssKeyCreationInfo
import im.vector.matrix.android.api.session.securestorage.SsssKeySpec
import im.vector.matrix.android.api.session.securestorage.SsssPassphrase
import im.vector.matrix.android.internal.crypto.OutgoingGossipingRequestManager
import im.vector.matrix.android.internal.crypto.SSSS_ALGORITHM_AES_HMAC_SHA2
import im.vector.matrix.android.internal.crypto.SSSS_ALGORITHM_CURVE25519_AES_SHA2
import im.vector.matrix.android.internal.crypto.crosssigning.fromBase64
import im.vector.matrix.android.internal.crypto.crosssigning.toBase64NoPadding
import im.vector.matrix.android.internal.crypto.keysbackup.generatePrivateKeyWithPassword
import im.vector.matrix.android.internal.crypto.keysbackup.util.computeRecoveryKey
import im.vector.matrix.android.internal.crypto.tools.HkdfSha256
import im.vector.matrix.android.internal.crypto.tools.withOlmDecryption
import im.vector.matrix.android.internal.di.UserId
import im.vector.matrix.android.internal.extensions.foldToCallback
import im.vector.matrix.android.internal.util.MatrixCoroutineDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.matrix.olm.OlmPkMessage
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import kotlin.experimental.and

internal class DefaultSharedSecretStorageService @Inject constructor(
        @UserId private val userId: String,
        private val accountDataService: AccountDataService,
        private val outgoingGossipingRequestManager: OutgoingGossipingRequestManager,
        private val coroutineDispatchers: MatrixCoroutineDispatchers,
        private val cryptoCoroutineScope: CoroutineScope
) : SharedSecretStorageService {

    override fun generateKey(keyId: String,
                             keyName: String,
                             keySigner: KeySigner?,
                             callback: MatrixCallback<SsssKeyCreationInfo>) {
        cryptoCoroutineScope.launch(coroutineDispatchers.main) {
            val key = try {
                ByteArray(32).also {
                    SecureRandom().nextBytes(it)
                }
            } catch (failure: Throwable) {
                callback.onFailure(failure)
                return@launch
            }

            val storageKeyContent = SecretStorageKeyContent(
                    name = keyName,
                    algorithm = SSSS_ALGORITHM_AES_HMAC_SHA2,
                    passphrase = null
            )

            val signedContent = keySigner?.sign(storageKeyContent.canonicalSignable())?.let {
                storageKeyContent.copy(
                        signatures = it
                )
            } ?: storageKeyContent

            accountDataService.updateAccountData(
                    "$KEY_ID_BASE.$keyId",
                    signedContent.toContent(),
                    object : MatrixCallback<Unit> {
                        override fun onFailure(failure: Throwable) {
                            callback.onFailure(failure)
                        }

                        override fun onSuccess(data: Unit) {
                            callback.onSuccess(SsssKeyCreationInfo(
                                    keyId = keyId,
                                    content = storageKeyContent,
                                    recoveryKey = computeRecoveryKey(key)
                            ))
                        }
                    }
            )
        }
    }

    override fun generateKeyWithPassphrase(keyId: String,
                                           keyName: String,
                                           passphrase: String,
                                           keySigner: KeySigner,
                                           progressListener: ProgressListener?,
                                           callback: MatrixCallback<SsssKeyCreationInfo>) {
        cryptoCoroutineScope.launch(coroutineDispatchers.main) {
            val privatePart = generatePrivateKeyWithPassword(passphrase, progressListener)

            val storageKeyContent = SecretStorageKeyContent(
                    algorithm = SSSS_ALGORITHM_AES_HMAC_SHA2,
                    passphrase = SsssPassphrase(algorithm = "m.pbkdf2", iterations = privatePart.iterations, salt = privatePart.salt)
            )

            val signedContent = keySigner.sign(storageKeyContent.canonicalSignable())?.let {
                storageKeyContent.copy(
                        signatures = it
                )
            } ?: storageKeyContent

            accountDataService.updateAccountData(
                    "$KEY_ID_BASE.$keyId",
                    signedContent.toContent(),
                    object : MatrixCallback<Unit> {
                        override fun onFailure(failure: Throwable) {
                            callback.onFailure(failure)
                        }

                        override fun onSuccess(data: Unit) {
                            callback.onSuccess(SsssKeyCreationInfo(
                                    keyId = keyId,
                                    content = storageKeyContent,
                                    recoveryKey = computeRecoveryKey(privatePart.privateKey)
                            ))
                        }
                    }
            )
        }
    }

    override fun hasKey(keyId: String): Boolean {
        return accountDataService.getAccountDataEvent("$KEY_ID_BASE.$keyId") != null
    }

    override fun getKey(keyId: String): KeyInfoResult {
        val accountData = accountDataService.getAccountDataEvent("$KEY_ID_BASE.$keyId")
                ?: return KeyInfoResult.Error(SharedSecretStorageError.UnknownKey(keyId))
        return SecretStorageKeyContent.fromJson(accountData.content)?.let {
            KeyInfoResult.Success(
                    KeyInfo(id = keyId, content = it)
            )
        } ?: KeyInfoResult.Error(SharedSecretStorageError.UnknownAlgorithm(keyId))
    }

    override fun setDefaultKey(keyId: String, callback: MatrixCallback<Unit>) {
        val existingKey = getKey(keyId)
        if (existingKey is KeyInfoResult.Success) {
            accountDataService.updateAccountData(DEFAULT_KEY_ID,
                    mapOf("key" to keyId),
                    callback
            )
        } else {
            callback.onFailure(SharedSecretStorageError.UnknownKey(keyId))
        }
    }

    override fun getDefaultKey(): KeyInfoResult {
        val accountData = accountDataService.getAccountDataEvent(DEFAULT_KEY_ID)
                ?: return KeyInfoResult.Error(SharedSecretStorageError.UnknownKey(DEFAULT_KEY_ID))
        val keyId = accountData.content["key"] as? String
                ?: return KeyInfoResult.Error(SharedSecretStorageError.UnknownKey(DEFAULT_KEY_ID))
        return getKey(keyId)
    }

    override fun storeSecret(name: String, secretBase64: String, keys: List<SharedSecretStorageService.KeyRef>, callback: MatrixCallback<Unit>) {
        cryptoCoroutineScope.launch(coroutineDispatchers.main) {
            val encryptedContents = HashMap<String, EncryptedSecretContent>()
            try {
                keys.forEach {
                    val keyId = it.keyId
                    // encrypt the content
                    when (val key = keyId?.let { getKey(keyId) } ?: getDefaultKey()) {
                        is KeyInfoResult.Success -> {
                            if (key.keyInfo.content.algorithm == SSSS_ALGORITHM_AES_HMAC_SHA2) {
                                encryptAesHmacSha2(it.keySpec!!, name, secretBase64).let {
                                    encryptedContents[key.keyInfo.id] = it
                                }
                            } else {
                                // Unknown algorithm
                                callback.onFailure(SharedSecretStorageError.UnknownAlgorithm(key.keyInfo.content.algorithm ?: ""))
                                return@launch
                            }
                        }
                        is KeyInfoResult.Error   -> {
                            callback.onFailure(key.error)
                            return@launch
                        }
                    }
                }

                accountDataService.updateAccountData(
                        type = name,
                        content = mapOf(
                                "encrypted" to encryptedContents
                        ),
                        callback = callback
                )
            } catch (failure: Throwable) {
                callback.onFailure(failure)
            }
        }
    }

    /**
     * Encryption algorithm m.secret_storage.v1.aes-hmac-sha2
     * Secrets are encrypted using AES-CTR-256 and MACed using HMAC-SHA-256. The data is encrypted and MACed as follows:
     *
     * Given the secret storage key, generate 64 bytes by performing an HKDF with SHA-256 as the hash, a salt of 32 bytes
     * of 0, and with the secret name as the info.
     *
     * The first 32 bytes are used as the AES key, and the next 32 bytes are used as the MAC key
     *
     * Generate 16 random bytes, set bit 63 to 0 (in order to work around differences in AES-CTR implementations), and use
     * this as the AES initialization vector.
     * This becomes the iv property, encoded using base64.
     *
     * Encrypt the data using AES-CTR-256 using the AES key generated above.
     *
     * This encrypted data, encoded using base64, becomes the ciphertext property.
     *
     * Pass the raw encrypted data (prior to base64 encoding) through HMAC-SHA-256 using the MAC key generated above.
     * The resulting MAC is base64-encoded and becomes the mac property.
     * (We use AES-CTR to match file encryption and key exports.)
     */
    @Throws
    private fun encryptAesHmacSha2(secretKey: SsssKeySpec, secretName: String, clearDataBase64: String): EncryptedSecretContent {
        secretKey as RawBytesKeySpec
        val pseudoRandomKey = HkdfSha256.deriveSecret(
                secretKey.privateKey,
                ByteArray(32) { 0.toByte() },
                secretName.toByteArray(),
                64)

        // The first 32 bytes are used as the AES key, and the next 32 bytes are used as the MAC key
        val aesKey = pseudoRandomKey.copyOfRange(0, 32)
        val macKey = pseudoRandomKey.copyOfRange(32, 64)

        val secureRandom = SecureRandom()
        val iv = ByteArray(16)
        secureRandom.nextBytes(iv)

        // clear bit 63 of the salt to stop us hitting the 64-bit counter boundary
        // (which would mean we wouldn't be able to decrypt on Android). The loss
        // of a single bit of salt is a price we have to pay.
        iv[9] = iv[9] and 0x7f

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")

        val secretKeySpec = SecretKeySpec(aesKey, "AES")
        val ivParameterSpec = IvParameterSpec(iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivParameterSpec)
        // secret are not that big, just do Final
        val cipherBytes = cipher.doFinal(clearDataBase64.fromBase64())
        require(cipherBytes.isNotEmpty())

        val macKeySpec = SecretKeySpec(macKey, "HmacSHA256")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(macKeySpec)
        val digest = mac.doFinal(cipherBytes)

        return EncryptedSecretContent(
                ciphertext = cipherBytes.toBase64NoPadding(),
                initializationVector = iv.toBase64NoPadding(),
                mac = digest.toBase64NoPadding()
        )
    }

    private fun decryptAesHmacSha2(secretKey: SsssKeySpec, secretName: String, cipherContent: EncryptedSecretContent): String {
        secretKey as RawBytesKeySpec
        val pseudoRandomKey = HkdfSha256.deriveSecret(
                secretKey.privateKey,
                ByteArray(32) { 0.toByte() },
                secretName.toByteArray(),
                64)

        // The first 32 bytes are used as the AES key, and the next 32 bytes are used as the MAC key
        val aesKey = pseudoRandomKey.copyOfRange(0, 32)
        val macKey = pseudoRandomKey.copyOfRange(32, 64)

        val iv = cipherContent.initializationVector?.fromBase64() ?: ByteArray(16)

        val cipherRawBytes = cipherContent.ciphertext?.fromBase64() ?: throw SharedSecretStorageError.BadCipherText

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")

        val secretKeySpec = SecretKeySpec(aesKey, "AES")
        val ivParameterSpec = IvParameterSpec(iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec)
        // secret are not that big, just do Final
        val decryptedSecret = cipher.doFinal(cipherRawBytes)

        require(decryptedSecret.isNotEmpty())

        // Check Signature
        val macKeySpec = SecretKeySpec(macKey, "HmacSHA256")
        val mac = Mac.getInstance("HmacSHA256").apply { init(macKeySpec) }
        val digest = mac.doFinal(cipherRawBytes)

        if (!cipherContent.mac?.fromBase64()?.contentEquals(digest).orFalse()) {
            throw SharedSecretStorageError.BadMac
        } else {
            // we are good
            return decryptedSecret.toBase64NoPadding()
        }
    }

    override fun getAlgorithmsForSecret(name: String): List<KeyInfoResult> {
        val accountData = accountDataService.getAccountDataEvent(name)
                ?: return listOf(KeyInfoResult.Error(SharedSecretStorageError.UnknownSecret(name)))
        val encryptedContent = accountData.content[ENCRYPTED] as? Map<*, *>
                ?: return listOf(KeyInfoResult.Error(SharedSecretStorageError.SecretNotEncrypted(name)))

        val results = ArrayList<KeyInfoResult>()
        encryptedContent.keys.forEach {
            (it as? String)?.let { keyId ->
                results.add(getKey(keyId))
            }
        }
        return results
    }

    override fun getSecret(name: String, keyId: String?, secretKey: SsssKeySpec, callback: MatrixCallback<String>) {
        val accountData = accountDataService.getAccountDataEvent(name) ?: return Unit.also {
            callback.onFailure(SharedSecretStorageError.UnknownSecret(name))
        }
        val encryptedContent = accountData.content[ENCRYPTED] as? Map<*, *> ?: return Unit.also {
            callback.onFailure(SharedSecretStorageError.SecretNotEncrypted(name))
        }
        val key = keyId?.let { getKey(it) } as? KeyInfoResult.Success ?: getDefaultKey() as? KeyInfoResult.Success ?: return Unit.also {
            callback.onFailure(SharedSecretStorageError.UnknownKey(name))
        }

        val encryptedForKey = encryptedContent[key.keyInfo.id] ?: return Unit.also {
            callback.onFailure(SharedSecretStorageError.SecretNotEncryptedWithKey(name, key.keyInfo.id))
        }

        val secretContent = EncryptedSecretContent.fromJson(encryptedForKey)
                ?: return Unit.also {
                    callback.onFailure(SharedSecretStorageError.ParsingError)
                }

        val algorithm = key.keyInfo.content
        if (SSSS_ALGORITHM_CURVE25519_AES_SHA2 == algorithm.algorithm) {
            val keySpec = secretKey as? RawBytesKeySpec ?: return Unit.also {
                callback.onFailure(SharedSecretStorageError.BadKeyFormat)
            }
            cryptoCoroutineScope.launch(coroutineDispatchers.main) {
                kotlin.runCatching {
                    // decrypt from recovery key
                    withOlmDecryption { olmPkDecryption ->
                        olmPkDecryption.setPrivateKey(keySpec.privateKey)
                        olmPkDecryption.decrypt(OlmPkMessage()
                                .apply {
                                    mCipherText = secretContent.ciphertext
                                    mEphemeralKey = secretContent.ephemeral
                                    mMac = secretContent.mac
                                }
                        )
                    }
                }.foldToCallback(callback)
            }
        } else if (SSSS_ALGORITHM_AES_HMAC_SHA2 == algorithm.algorithm) {
            val keySpec = secretKey as? RawBytesKeySpec ?: return Unit.also {
                callback.onFailure(SharedSecretStorageError.BadKeyFormat)
            }
            cryptoCoroutineScope.launch(coroutineDispatchers.main) {
                kotlin.runCatching {
                    decryptAesHmacSha2(keySpec, name, secretContent)
                }.foldToCallback(callback)
            }
        } else {
            callback.onFailure(SharedSecretStorageError.UnsupportedAlgorithm(algorithm.algorithm ?: ""))
        }
    }

    companion object {
        const val KEY_ID_BASE = "m.secret_storage.key"
        const val ENCRYPTED = "encrypted"
        const val DEFAULT_KEY_ID = "m.secret_storage.default_key"
    }

    override fun checkShouldBeAbleToAccessSecrets(secretNames: List<String>, keyId: String?): IntegrityResult {
        if (secretNames.isEmpty()) {
            return IntegrityResult.Error(SharedSecretStorageError.UnknownSecret("none"))
        }

        val keyInfoResult = if (keyId == null) {
            getDefaultKey()
        } else {
            getKey(keyId)
        }

        val keyInfo = (keyInfoResult as? KeyInfoResult.Success)?.keyInfo
                ?: return IntegrityResult.Error(SharedSecretStorageError.UnknownKey(keyId ?: ""))

        if (keyInfo.content.algorithm != SSSS_ALGORITHM_AES_HMAC_SHA2
                || keyInfo.content.algorithm != SSSS_ALGORITHM_CURVE25519_AES_SHA2) {
            // Unsupported algorithm
            return IntegrityResult.Error(
                    SharedSecretStorageError.UnsupportedAlgorithm(keyInfo.content.algorithm ?: "")
            )
        }

        secretNames.forEach { secretName ->
            val secretEvent = accountDataService.getAccountDataEvent(secretName)
                    ?: return IntegrityResult.Error(SharedSecretStorageError.UnknownSecret(secretName))
            if ((secretEvent.content["encrypted"] as? Map<*, *>)?.get(keyInfo.id) == null) {
                return IntegrityResult.Error(SharedSecretStorageError.SecretNotEncryptedWithKey(secretName, keyInfo.id))
            }
        }

        return IntegrityResult.Success(keyInfo.content.passphrase != null)
    }

    override fun requestSecret(name: String, myOtherDeviceId: String) {
        outgoingGossipingRequestManager.sendSecretShareRequest(
                name,
                mapOf(userId to listOf(myOtherDeviceId))
        )
    }
}
