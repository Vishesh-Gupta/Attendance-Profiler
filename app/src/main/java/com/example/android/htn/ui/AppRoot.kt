package com.example.android.htn.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.android.htn.data.UserProfile
import com.example.android.htn.ui.auth.AuthScreen
import com.example.android.htn.ui.auth.FirebaseSetupRequiredScreen
import com.example.android.htn.ui.auth.ProfileSetupScreen
import com.example.android.htn.ui.components.LoadingBox
import com.example.android.htn.ui.events.EventDetailScreen
import com.example.android.htn.ui.events.EventsScreen
import com.example.android.htn.ui.pass.PassScreen
import com.example.android.htn.ui.people.PeopleScreen
import com.example.android.htn.ui.people.PersonScreen

@Composable
fun AppRoot() {
    val app = rememberApp()
    if (!app.firebaseConfigured) {
        FirebaseSetupRequiredScreen()
        return
    }
    val sessionFlow = remember { sessionState(app.authRepository, app.repository) }
    val session by sessionFlow.collectAsStateWithLifecycle(initialValue = SessionState.Loading)

    when (val s = session) {
        SessionState.Loading -> LoadingBox()
        SessionState.SignedOut -> AuthScreen()
        is SessionState.NeedsProfile -> ProfileSetupScreen(s.user)
        // Rebuild navigation on account or role change, since the available screens depend on both.
        is SessionState.SignedIn -> androidx.compose.runtime.key(s.profile.uid, s.profile.role) { SignedInApp(s.profile) }
    }
}

private enum class Tab(val route: String, val label: String, val icon: ImageVector, val staffOnly: Boolean) {
    PASS("pass", "My pass", Icons.Filled.AccountBox, staffOnly = false),
    EVENTS("events", "Events", Icons.Filled.DateRange, staffOnly = false),
    PEOPLE("people", "People", Icons.Filled.Person, staffOnly = true),
}

@Composable
private fun SignedInApp(me: UserProfile) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val tabs = Tab.entries.filter { !it.staffOnly || me.role.isStaff }

    Scaffold(
        bottomBar = {
            if (tabs.any { it.route == currentRoute }) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        val openEvent = { id: String -> navController.navigate("events/$id") }
        val openPerson = { uid: String -> navController.navigate("people/$uid") }
        NavHost(
            navController = navController,
            startDestination = Tab.PASS.route,
            modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
        ) {
            composable(Tab.PASS.route) { PassScreen(me, onOpenEvent = openEvent) }
            composable(Tab.EVENTS.route) { EventsScreen(me, onOpenEvent = openEvent) }
            composable("events/{eventId}") { entry ->
                EventDetailScreen(
                    eventId = entry.arguments?.getString("eventId").orEmpty(),
                    me = me,
                    onBack = { navController.popBackStack() },
                    onOpenPerson = { if (me.role.isStaff) openPerson(it) },
                )
            }
            if (me.role.isStaff) {
                composable(Tab.PEOPLE.route) { PeopleScreen(onOpenPerson = openPerson) }
                composable("people/{uid}") { entry ->
                    PersonScreen(
                        uid = entry.arguments?.getString("uid").orEmpty(),
                        me = me,
                        onBack = { navController.popBackStack() },
                        onOpenEvent = openEvent,
                    )
                }
            }
        }
    }
}
