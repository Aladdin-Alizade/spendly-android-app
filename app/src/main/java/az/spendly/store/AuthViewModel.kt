/**
 * Who is signed in.
 *
 * When Supabase is not configured the app runs on the device's own storage,
 * where there is nobody to sign in as — the status is [AuthStatus.NOT_REQUIRED]
 * and no sign-in screen is ever shown.
 */
package az.spendly.store

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import az.spendly.data.AccountUser
import az.spendly.data.SupabaseConfig
import az.spendly.data.SupabaseSession
import az.spendly.domain.authErrorMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * There is no loading state: the stored session is read from local storage,
 * so who is signed in is known before the first frame.
 */
enum class AuthStatus {
    SIGNED_OUT,
    SIGNED_IN,

    /**
     * A reset link opened the app and signed it in. It is a session like any
     * other, so nothing distinguishes it except this — and without it somebody
     * who followed a link out of their mailbox lands on the dashboard with the
     * password they came to set still unset.
     */
    RECOVERING,
    NOT_REQUIRED,
}

data class AuthState(
    val status: AuthStatus,
    /** Who is signed in, for the profile. Null in local-storage mode. */
    val user: AccountUser? = null,
    /** Set after a sign-up that needs the address confirmed before signing in. */
    val notice: String? = null,
    /** Set when the last attempt failed, in the user's own language. */
    val failure: String? = null,
    val busy: Boolean = false,
) {
    /** The id the store is keyed by, so a different account builds a new one. */
    val userId: String? get() = user?.id
}

class AuthViewModel(private val session: SupabaseSession?) : ViewModel() {

    private val _state = MutableStateFlow(
        if (session == null) {
            AuthState(AuthStatus.NOT_REQUIRED)
        } else if (session.isSignedIn) {
            AuthState(AuthStatus.SIGNED_IN, user = session.account)
        } else {
            AuthState(AuthStatus.SIGNED_OUT)
        },
    )
    val state: StateFlow<AuthState> = _state.asStateFlow()

    fun signIn(email: String, password: String) = attempt {
        session!!.signIn(email.trim(), password)
        _state.value = AuthState(AuthStatus.SIGNED_IN, user = session.account)
    }

    fun signUp(email: String, password: String) = attempt {
        // With email confirmation on, Supabase creates the user but no
        // session; the app has to say so rather than dropping the user on an
        // empty screen.
        val signedIn = session!!.signUp(email.trim(), password)
        _state.value = if (signedIn) {
            AuthState(AuthStatus.SIGNED_IN, user = session.account)
        } else {
            AuthState(
                AuthStatus.SIGNED_OUT,
                notice = "Hesab yaradıldı. Daxil olmadan əvvəl e-poçtunuza gələn " +
                    "təsdiq linkini açın.",
            )
        }
    }

    /**
     * Change the password. Reports through [AuthState.notice] on success and
     * [AuthState.failure] on refusal, so the screen needs no state of its own.
     */
    fun changePassword(currentPassword: String, nextPassword: String) = attempt {
        session!!.changePassword(currentPassword, nextPassword)
        _state.value = AuthState(
            AuthStatus.SIGNED_IN,
            user = session.account,
            notice = "Şifrə dəyişdirildi.",
        )
    }

    /** Forget what the last attempt said. One screen's message must not read
     *  as another screen's answer. */
    fun clearMessages() {
        _state.value = _state.value.copy(notice = null, failure = null)
    }

    /**
     * Email a reset link. Reported as sent whether or not the address has an
     * account, because saying which addresses exist is telling.
     */
    fun sendPasswordReset(email: String) = attempt {
        session!!.sendPasswordReset(email)
        _state.value = AuthState(
            AuthStatus.SIGNED_OUT,
            notice = "Əgər bu ünvanla hesab varsa, link göndərildi. Poçtunuzu yoxlayın.",
        )
    }

    /** A reset link came back to the app; adopt the session it carried. */
    fun startRecovery(accessToken: String, refreshToken: String?) {
        val current = session ?: return
        _state.value = _state.value.copy(busy = true, failure = null, notice = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { current.adoptRecovery(accessToken, refreshToken) }
                _state.value = AuthState(AuthStatus.RECOVERING, user = current.account)
            } catch (cause: Exception) {
                _state.value = AuthState(
                    AuthStatus.SIGNED_OUT,
                    failure = authErrorMessage(cause.message ?: "Naməlum xəta"),
                )
            }
        }
    }

    /** Set the password the reset link was opened to set. */
    fun completePasswordReset(nextPassword: String) = attempt {
        session!!.setPassword(nextPassword)
        _state.value = AuthState(AuthStatus.SIGNED_IN, user = session.account)
    }

    fun signOut() {
        val current = session ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { current.signOut() }
            _state.value = AuthState(AuthStatus.SIGNED_OUT)
        }
    }

    fun dismissFailure() {
        _state.value = _state.value.copy(failure = null)
    }

    private fun attempt(work: suspend () -> Unit) {
        _state.value = _state.value.copy(busy = true, failure = null, notice = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { work() }
            } catch (cause: Exception) {
                _state.value = _state.value.copy(
                    busy = false,
                    failure = authErrorMessage(cause.message ?: "Naməlum xəta"),
                )
            }
        }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val session = if (SupabaseConfig.isConfigured) {
                        SupabaseSession(application)
                    } else {
                        null
                    }
                    return AuthViewModel(session) as T
                }
            }
    }
}
