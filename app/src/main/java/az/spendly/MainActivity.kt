package az.spendly

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import az.spendly.store.AuthStatus
import az.spendly.store.AuthViewModel
import az.spendly.store.FinanceViewModel
import az.spendly.ui.AuthScreen
import az.spendly.ui.RecoveryScreen
import az.spendly.ui.SpendlyApp
import az.spendly.ui.theme.SpendlyTheme
import az.spendly.ui.theme.spendlyColors
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {

    /**
     * A reset link that has just opened the app.
     *
     * Supabase verifies the link and redirects to the deep link with the
     * session in the URI fragment — which is not a query string, so it has to
     * be read off the raw URI rather than through getQueryParameter.
     */
    private val recovery = mutableStateOf<Pair<String, String?>?>(null)

    /**
     * Whether the link this activity was started with has already been used.
     *
     * Saved and restored, because the intent is not: a rotation recreates the
     * activity with the original VIEW intent still attached, and reading it
     * again adopts a one-time link a second time. The link is spent by then,
     * so what the user got for turning their phone was an error on a password
     * they were halfway through setting.
     */
    private var recoveryHandled = false

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        recoveryHandled = false
        readRecoveryLink(intent)
    }

    /** The tokens a reset link carried, or nothing when it was not one. */
    private fun readRecoveryLink(intent: Intent?) {
        val fragment = intent?.data?.fragment ?: return
        val parts = fragment.split("&")
            .mapNotNull { entry ->
                val (key, value) = entry.split("=", limit = 2).let {
                    if (it.size == 2) it[0] to it[1] else return@mapNotNull null
                }
                key to value
            }
            .toMap()

        val access = parts["access_token"] ?: return
        if (parts["type"] != null && parts["type"] != "recovery") return
        recovery.value = access to parts["refresh_token"]
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_RECOVERY_HANDLED, recoveryHandled)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        recoveryHandled = savedInstanceState?.getBoolean(KEY_RECOVERY_HANDLED) == true
        if (!recoveryHandled) readRecoveryLink(intent)

        setContent {
            SpendlyTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(spendlyColors.background),
                ) {
                    Root(
                        recovery = recovery.value,
                        onRecoveryHandled = {
                            recoveryHandled = true
                            recovery.value = null
                        },
                    )
                }
            }
        }
    }

    private companion object {
        const val KEY_RECOVERY_HANDLED = "recovery_handled"
    }
}

/**
 * Supabase when it is configured, the device's own storage otherwise, and a
 * sign-in screen in between when there is an account to sign into.
 */
@Composable
private fun Root(
    recovery: Pair<String, String?>? = null,
    onRecoveryHandled: () -> Unit = {},
) {
    val application = LocalContext.current.applicationContext as Application
    val auth: AuthViewModel = viewModel(factory = AuthViewModel.factory(application))
    val authState by auth.state.collectAsState()

    LaunchedEffect(recovery) {
        val tokens = recovery ?: return@LaunchedEffect
        auth.startRecovery(tokens.first, tokens.second)
        onRecoveryHandled()
    }

    when (authState.status) {
        AuthStatus.SIGNED_OUT -> AuthScreen(
            state = authState,
            onSignIn = auth::signIn,
            onSignUp = auth::signUp,
            onSendPasswordReset = auth::sendPasswordReset,
            onClearMessages = auth::clearMessages,
        )

        // A reset link signed the app in; the password it was opened to set is
        // still unset, so this comes before the app itself.
        AuthStatus.RECOVERING -> RecoveryScreen(
            state = authState,
            onSubmit = auth::completePasswordReset,
            onCancel = auth::signOut,
        )

        AuthStatus.SIGNED_IN, AuthStatus.NOT_REQUIRED -> {
            /*
             * Keyed by user, so signing into a different account builds a new
             * store rather than leaving the previous account's figures on
             * screen while the new ones load.
             */
            val finance: FinanceViewModel = viewModel(
                key = authState.userId ?: "local",
                factory = FinanceViewModel.factory(application),
            )
            val state by finance.state.collectAsState()

            SpendlyApp(
                state = state,
                viewModel = finance,
                user = authState.user,
                onChangePassword = if (authState.status == AuthStatus.SIGNED_IN) {
                    auth::changePassword
                } else {
                    null
                },
                passwordNotice = authState.notice,
                passwordFailure = authState.failure,
                passwordBusy = authState.busy,
                // Present only when there is an account to leave; in local
                // storage mode there is nobody signed in.
                onSignOut = if (authState.status == AuthStatus.SIGNED_IN) {
                    auth::signOut
                } else {
                    null
                },
            )
        }
    }
}
