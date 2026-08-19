package az.spendly

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import az.spendly.store.AuthStatus
import az.spendly.store.AuthViewModel
import az.spendly.store.FinanceViewModel
import az.spendly.ui.AuthScreen
import az.spendly.ui.SpendlyApp
import az.spendly.ui.theme.SpendlyTheme
import az.spendly.ui.theme.spendlyColors
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            SpendlyTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(spendlyColors.background),
                ) {
                    Root()
                }
            }
        }
    }
}

/**
 * Supabase when it is configured, the device's own storage otherwise, and a
 * sign-in screen in between when there is an account to sign into.
 */
@Composable
private fun Root() {
    val application = LocalContext.current.applicationContext as Application
    val auth: AuthViewModel = viewModel(factory = AuthViewModel.factory(application))
    val authState by auth.state.collectAsState()

    when (authState.status) {
        AuthStatus.SIGNED_OUT -> AuthScreen(
            state = authState,
            onSignIn = auth::signIn,
            onSignUp = auth::signUp,
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
