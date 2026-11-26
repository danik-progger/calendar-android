package dev.calendar.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class CalendarAccount(private val context: Context, private val scope: CoroutineScope) {
    private val credentials = CredentialManager.create(context)
    private val tokens = SessionToken(context)
    private var api: CalendarApi? = null
    var store by mutableStateOf<EventStore?>(null)
        private set
    var email by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    suspend fun restore() {
        busy = true
        error = null
        try {
            val token = withContext(Dispatchers.IO) { tokens.read() }
            if (token == null) {
                CalendarWidgetSnapshot.clear(context)
                return
            }
            connect(CalendarApi(token))
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: SessionExpired) {
            expire()
        } catch (_: Exception) {
            error = "Couldn’t check your session. Try again."
        } finally {
            busy = false
        }
    }

    suspend fun signIn() {
        if (busy) return
        busy = true
        error = null
        try {
            val nonce = UUID.randomUUID().toString()
            val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).setNonce(nonce).build()
            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
            val response = credentials.getCredential(context, request).credential
            check(response is CustomCredential && response.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
            val idToken = GoogleIdTokenCredential.createFrom(response.data).idToken
            val api = CalendarApi.signIn(idToken, nonce)
            try {
                connect(api, persist = true)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                try { api.signOut() } catch (_: Exception) { }
                throw exception
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: GetCredentialCancellationException) {
            return
        } catch (_: Exception) {
            error = "Couldn’t sign in with Google. Try again."
        } finally {
            busy = false
        }
    }

    suspend fun signOut() {
        val api = api ?: return
        val current = store ?: return
        if (busy || current.busy) return
        busy = true
        current.busy = true
        try {
            try { api.signOut() } catch (_: SessionExpired) { }
            expire(message = null)
            try { credentials.clearCredentialState(ClearCredentialStateRequest()) }
            catch (exception: CancellationException) { throw exception }
            catch (_: Exception) { }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            current.error = "Couldn’t sign out. Try again."
        } finally {
            busy = false
            current.busy = false
        }
    }

    private suspend fun connect(api: CalendarApi, persist: Boolean = false) {
        val email = api.email()
        if (persist) {
            withContext(Dispatchers.IO) { tokens.write(api.token) }
            CalendarWidgetSnapshot.clear(context)
        }
        this.api = api
        this.email = email
        store = EventStore(
            scope, api,
            onExpired = { if (this.api?.token == api.token) expire() },
            onConfirmedEvents = { events ->
                if (this.api?.token == api.token) CalendarWidgetSnapshot.publish(context, events)
            },
        )
    }

    private suspend fun expire(message: String? = "Session expired. Sign in again.") {
        withContext(Dispatchers.IO) { tokens.clear() }
        api = null
        store = null
        email = null
        CalendarWidgetSnapshot.clear(context)
        error = message
    }
}

private class SessionToken(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "calendar-session"))
    private val alias = "calendar-shared-session"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(alias, null)
        if (existing is SecretKey) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }

    fun read(): String? {
        val data = try { file.readFully() } catch (_: FileNotFoundException) { return null }
        require(data.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        return cipher.doFinal(data, 12, data.size - 12).decodeToString()
    }

    fun write(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        check(cipher.iv.size == 12)
        val data = cipher.iv + cipher.doFinal(token.encodeToByteArray())
        val stream = file.startWrite()
        try {
            stream.write(data)
            file.finishWrite(stream)
        } catch (exception: Exception) {
            file.failWrite(stream)
            throw exception
        }
    }

    fun clear() { file.delete() }
}
