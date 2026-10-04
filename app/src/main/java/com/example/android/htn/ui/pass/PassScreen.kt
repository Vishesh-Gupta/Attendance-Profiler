package com.example.android.htn.ui.pass

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.android.htn.data.QrPass
import com.example.android.htn.data.UserProfile
import com.example.android.htn.profiling.AttendanceProfiler
import com.example.android.htn.ui.components.ProfileStats
import com.example.android.htn.ui.components.QrCode
import com.example.android.htn.ui.components.RoleBadge
import com.example.android.htn.ui.components.TierBadge
import com.example.android.htn.ui.components.TimelineRow
import com.example.android.htn.ui.rememberApp
import com.example.android.htn.ui.todayEpochDay
import kotlinx.coroutines.flow.combine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassScreen(me: UserProfile, onOpenEvent: (String) -> Unit) {
    val app = rememberApp()
    val repository = app.repository
    val profileFlow = remember(me.uid) {
        combine(repository.events(), repository.checkInsOf(me.uid), repository.registrationsOf(me.uid)) { events, checkIns, regs ->
            AttendanceProfiler.profile(me.uid, events, checkIns, regs, todayEpochDay())
        }
    }
    val profile by profileFlow.collectAsStateWithLifecycle(initialValue = null)
    MaxBrightness()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My pass") },
                actions = {
                    IconButton(onClick = { app.authRepository.signOut() }) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Sign out")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Card(Modifier.widthIn(max = 320.dp).fillMaxWidth()) {
                        QrCode(
                            content = QrPass.encode(me.uid),
                            contentDescription = "Check-in QR code for ${me.name}",
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(16.dp),
                        )
                    }
                    Text(me.name, style = MaterialTheme.typography.headlineSmall)
                    if (me.organization.isNotBlank()) Text(me.organization)
                    RoleBadge(me.role)
                    Text(
                        "Show this code to a volunteer or organizer to check in.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            profile?.let { p ->
                item {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("My attendance", style = MaterialTheme.typography.titleMedium)
                        TierBadge(p.tier)
                        ProfileStats(p)
                    }
                }
                items(p.timeline, key = { it.event.id }) { TimelineRow(it) { onOpenEvent(it.event.id) } }
            }
        }
    }
}

/** Scanners read a bright screen much more reliably. */
@Composable
private fun MaxBrightness() {
    val window = (LocalContext.current as? Activity)?.window ?: return
    DisposableEffect(window) {
        val previous = window.attributes.screenBrightness
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        }
        onDispose { window.attributes = window.attributes.apply { screenBrightness = previous } }
    }
}
