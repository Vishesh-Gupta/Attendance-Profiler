package com.example.android.htn.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.android.htn.profiling.AttendanceTier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = { actions() },
    )
}

@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun TierBadge(tier: AttendanceTier) {
    val color = when (tier) {
        AttendanceTier.NONE -> MaterialTheme.colorScheme.surfaceVariant
        AttendanceTier.FIRST_TIMER -> MaterialTheme.colorScheme.secondaryContainer
        AttendanceTier.RETURNING -> MaterialTheme.colorScheme.primaryContainer
        AttendanceTier.VETERAN -> MaterialTheme.colorScheme.tertiaryContainer
    }
    AssistChip(
        onClick = {},
        label = { Text(tier.label) },
        colors = AssistChipDefaults.assistChipColors(containerColor = color),
    )
}
