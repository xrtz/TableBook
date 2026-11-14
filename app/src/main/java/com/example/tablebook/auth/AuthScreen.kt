package com.example.tablebook.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.tablebook.R

@Composable
internal fun AuthScreen(
    session: SessionState,
    operation: AuthOperation,
    onSignIn: (String, String, Boolean) -> Unit,
    onLogout: () -> Unit,
    onRestore: () -> Unit,
    onClearError: () -> Unit,
) {
    Scaffold { insets ->
        Column(
            modifier = Modifier.fillMaxSize().padding(insets).imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 42.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
            when (session) {
                SessionState.LOADING -> {
                    if (operation.error != null) {
                        Button(onClick = onRestore) { Text(stringResource(R.string.auth_retry)) }
                    }
                }
                SessionState.SIGNED_IN -> {
                    Text(stringResource(R.string.auth_signed_in))
                    Button(onClick = onLogout, enabled = !operation.busy) {
                        Text(stringResource(R.string.auth_logout))
                    }
                }
                SessionState.SIGNED_OUT -> AuthForm(operation.busy, onSignIn, onClearError)
            }
            if (operation.busy) CircularProgressIndicator()
            operation.error?.let { failure ->
                Text(stringResource(failure.message()), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun AuthForm(
    busy: Boolean,
    onSignIn: (String, String, Boolean) -> Unit,
    onClearError: () -> Unit,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var register by rememberSaveable { mutableStateOf(false) }
    val action = if (register) R.string.auth_register else R.string.auth_login
    Column(Modifier.widthIn(max = 380.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(action), style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = email, onValueChange = { email = it; onClearError() },
            label = { Text(stringResource(R.string.auth_email)) }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it; onClearError() },
            label = { Text(stringResource(R.string.auth_password)) }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, enabled = !busy, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            supportingText = if (register) {{ Text(stringResource(R.string.auth_password_hint)) }} else null,
        )
        Button(
            onClick = { onSignIn(email, password, register) },
            enabled = !busy && email.isNotBlank() && password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(action)) }
        TextButton(
            onClick = { register = !register; password = ""; onClearError() },
            enabled = !busy,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text(stringResource(if (register) R.string.auth_switch_to_login else R.string.auth_switch_to_register))
        }
    }
}

private fun AuthFailure.message(): Int = when (this) {
    AuthFailure.INVALID_CREDENTIALS -> R.string.auth_invalid_credentials
    AuthFailure.INVALID_REGISTRATION -> R.string.auth_invalid_registration
    AuthFailure.EMAIL_TAKEN -> R.string.auth_email_taken
    AuthFailure.EXPIRED -> R.string.auth_expired
    AuthFailure.CONNECTION -> R.string.auth_connection_error
}
