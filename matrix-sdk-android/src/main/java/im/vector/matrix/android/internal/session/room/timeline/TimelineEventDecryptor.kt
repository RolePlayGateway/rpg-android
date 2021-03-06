/*
 * Copyright 2019 New Vector Ltd
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
package im.vector.matrix.android.internal.session.room.timeline

import im.vector.matrix.android.api.session.crypto.CryptoService
import im.vector.matrix.android.api.session.crypto.MXCryptoError
import im.vector.matrix.android.api.session.events.model.toModel
import im.vector.matrix.android.internal.crypto.NewSessionListener
import im.vector.matrix.android.internal.crypto.model.event.EncryptedEventContent
import im.vector.matrix.android.internal.database.mapper.asDomain
import im.vector.matrix.android.internal.database.model.EventEntity
import im.vector.matrix.android.internal.database.query.where
import im.vector.matrix.android.internal.di.SessionDatabase
import im.vector.matrix.android.internal.session.SessionScope
import io.realm.Realm
import io.realm.RealmConfiguration
import timber.log.Timber
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject

@SessionScope
internal class TimelineEventDecryptor @Inject constructor(
        @SessionDatabase
        private val realmConfiguration: RealmConfiguration,
        private val cryptoService: CryptoService
) {

    private val newSessionListener = object : NewSessionListener {
        override fun onNewSession(roomId: String?, senderKey: String, sessionId: String) {
            synchronized(unknownSessionsFailure) {
                unknownSessionsFailure[sessionId]
                        ?.toList()
                        .orEmpty()
                        .also {
                            unknownSessionsFailure[sessionId]?.clear()
                        }
            }.forEach {
                requestDecryption(it)
            }
        }
    }

    private var executor: ExecutorService? = null

    // Set of eventIds which are currently decrypting
    private val existingRequests = mutableSetOf<DecryptionRequest>()
    // sessionId -> list of eventIds
    private val unknownSessionsFailure = mutableMapOf<String, MutableSet<DecryptionRequest>>()

    fun start() {
        executor = Executors.newSingleThreadExecutor()
        cryptoService.addNewSessionListener(newSessionListener)
    }

    fun destroy() {
        cryptoService.removeSessionListener(newSessionListener)
        executor?.shutdownNow()
        executor = null
        synchronized(unknownSessionsFailure) {
            unknownSessionsFailure.clear()
        }
        synchronized(existingRequests) {
            existingRequests.clear()
        }
    }

    fun requestDecryption(request: DecryptionRequest) {
        synchronized(unknownSessionsFailure) {
            for (requests in unknownSessionsFailure.values) {
                if (request in requests) {
                    Timber.d("Skip Decryption request for event ${request.eventId}, unknown session")
                    return
                }
            }
        }
        synchronized(existingRequests) {
            if (!existingRequests.add(request)) {
                Timber.d("Skip Decryption request for event ${request.eventId}, already requested")
                return
            }
        }
        executor?.execute {
            Realm.getInstance(realmConfiguration).use { realm ->
                processDecryptRequest(request, realm)
            }
        }
    }

    private fun processDecryptRequest(request: DecryptionRequest, realm: Realm) = realm.executeTransaction {
        val eventId = request.eventId
        val timelineId = request.timelineId
        Timber.v("Decryption request for event $eventId")
        val eventEntity = EventEntity.where(realm, eventId = eventId).findFirst()
                ?: return@executeTransaction Unit.also {
                    Timber.d("Decryption request for unknown message")
                }
        val event = eventEntity.asDomain()
        try {
            val result = cryptoService.decryptEvent(event, timelineId)
            Timber.v("Successfully decrypted event $eventId")
            eventEntity.setDecryptionResult(result)
        } catch (e: MXCryptoError) {
            Timber.v(e, "Failed to decrypt event $eventId")
            if (e is MXCryptoError.Base && e.errorType == MXCryptoError.ErrorType.UNKNOWN_INBOUND_SESSION_ID) {
                // Keep track of unknown sessions to automatically try to decrypt on new session
                eventEntity.decryptionErrorCode = e.errorType.name
                event.content?.toModel<EncryptedEventContent>()?.let { content ->
                    content.sessionId?.let { sessionId ->
                        synchronized(unknownSessionsFailure) {
                            val list = unknownSessionsFailure.getOrPut(sessionId) { mutableSetOf() }
                            list.add(request)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Timber.e(t, "Failed to decrypt event $eventId")
        } finally {
            synchronized(existingRequests) {
                existingRequests.remove(request)
            }
        }
    }

    data class DecryptionRequest(
            val eventId: String,
            val timelineId: String
    )
}
