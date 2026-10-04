package com.example.android.htn.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.android.htn.ui.attendees.AttendeeProfileScreen
import com.example.android.htn.ui.attendees.AttendeesScreen
import com.example.android.htn.ui.events.EventDetailScreen
import com.example.android.htn.ui.events.EventsScreen

private enum class TopLevel(val route: String, val label: String, val icon: ImageVector) {
    EVENTS("events", "Events", Icons.Filled.DateRange),
    ATTENDEES("attendees", "Attendees", Icons.Filled.Person),
}

@Composable
fun AttendanceNavHost() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            if (TopLevel.entries.any { it.route == currentRoute }) {
                NavigationBar {
                    TopLevel.entries.forEach { item ->
                        NavigationBarItem(
                            selected = currentRoute == item.route,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        val openAttendee = { id: Long -> navController.navigate("attendees/$id") }
        NavHost(
            navController = navController,
            startDestination = TopLevel.EVENTS.route,
            modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
        ) {
            composable(TopLevel.EVENTS.route) {
                EventsScreen(onOpenEvent = { navController.navigate("events/$it") })
            }
            composable(
                "events/{eventId}",
                arguments = listOf(navArgument("eventId") { type = NavType.LongType }),
            ) { entry ->
                EventDetailScreen(
                    eventId = entry.arguments!!.getLong("eventId"),
                    onBack = { navController.popBackStack() },
                    onOpenAttendee = openAttendee,
                )
            }
            composable(TopLevel.ATTENDEES.route) {
                AttendeesScreen(onOpenAttendee = openAttendee)
            }
            composable(
                "attendees/{attendeeId}",
                arguments = listOf(navArgument("attendeeId") { type = NavType.LongType }),
            ) { entry ->
                AttendeeProfileScreen(
                    attendeeId = entry.arguments!!.getLong("attendeeId"),
                    onBack = { navController.popBackStack() },
                    onOpenEvent = { navController.navigate("events/$it") },
                )
            }
        }
    }
}
