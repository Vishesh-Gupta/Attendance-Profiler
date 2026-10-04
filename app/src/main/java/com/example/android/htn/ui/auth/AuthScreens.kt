package com.example.android.htn.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.android.htn.data.AuthUser
import com.example.android.htn.ui.rememberApp
import com.example.android.htn.ui.userMessage
import kotlinx.coroutines.launch

private const val MIN_PASSWORD_LENGTH = 6

@Composable
fun AuthScreen() {
    val auth = rememberApp().authRepository
    val scope = rememberCoroutineScope()
    var signUp by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var organization by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun run(action: suspend () -> Unit) {
        busy = true
        message = null
        scope.launch {
            try { action() } catch (e: Exception) { message = e.userMessage() } finally { busy = false }
        }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Attendance Profiler", style = MaterialTheme.typography.headlineMedium)
        Text("Organizers, participants, judges and volunteers all sign in here.")
        TabRow(selectedTabIndex = if (signUp) 1 else 0) {
            Tab(selected = !signUp, onClick = { signUp = false; message = null }, text = { Text("Sign in") })
            Tab(selected = signUp, onClick = { signUp = true; message = null }, text = { Text("Create account") })
        }
        if (signUp) {
            OutlinedTextField(name, { name = it }, label = { Text("Full name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(organization, { organization = it }, label = { Text("School / company (optional)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        OutlinedTextField(
            email, { email = it }, label = { Text("Email") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            password, { password = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            supportingText = if (signUp) {{ Text("At least $MIN_PASSWORD_LENGTH characters") }} else null,
            modifier = Modifier.fillMaxWidth(),
        )
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (signUp) {
            Button(
                enabled = !busy && name.isNotBlank() && email.isNotBlank() && password.length >= MIN_PASSWORD_LENGTH,
                onClick = { run { auth.signUp(name, email, password, organization) } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Create account") }
            Text(
                "Everyone starts as a participant. An organizer can make you a judge, volunteer or organizer.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Button(
                enabled = !busy && email.isNotBlank() && password.isNotEmpty(),
                onClick = { run { auth.signIn(email, password) } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Sign in") }
            TextButton(
                enabled = !busy && email.isNotBlank(),
                onClick = { run { auth.sendPasswordReset(email); message = "Password reset email sent." } },
            ) { Text("Forgot password?") }
        }
    }
}

@Composable
fun ProfileSetupScreen(user: AuthUser) {
    val auth = rememberApp().authRepository
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var organization by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Finish setting up", style = MaterialTheme.typography.headlineMedium)
        Text("Signed in as ${user.email}")
        OutlinedTextField(name, { name = it }, label = { Text("Full name") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(organization, { organization = it }, label = { Text("School / company (optional)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            enabled = !busy && name.isNotBlank(),
            onClick = {
                busy = true
                scope.launch {
                    try { auth.createProfile(user.uid, user.email, name, organization) }
                    catch (e: Exception) { error = e.userMessage() }
                    finally { busy = false }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Continue") }
        TextButton(onClick = { auth.signOut() }) { Text("Sign out") }
    }
}

@Composable
fun FirebaseSetupRequiredScreen() {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Backend not configured", style = MaterialTheme.typography.headlineMedium)
        Text(
            "This build has no Firebase configuration. Create a Firebase project, enable Email/Password " +
                "sign-in and Cloud Firestore, download google-services.json into the app/ folder, and rebuild. " +
                "See README.md for the full steps."
        )
    }
}
