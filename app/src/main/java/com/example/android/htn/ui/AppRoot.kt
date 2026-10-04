package com.example.android.htn.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.android.htn.data.Role
import com.example.android.htn.data.UserProfile
import com.example.android.htn.ui.auth.AuthScreen
import com.example.android.htn.ui.auth.FirebaseSetupRequiredScreen
import com.example.android.htn.ui.auth.ProfileSetupScreen
import com.example.android.htn.ui.components.LoadingBox
import com.example.android.htn.ui.events.EventDetailScreen
import com.example.android.htn.ui.events.EventsScreen
import com.example.android.htn.ui.people.PeopleScreen
import com.example.android.htn.ui.people.PersonScreen
import com.example.android.htn.ui.profile.ProfileScreen
import com.example.android.htn.ui.projects.EventProjectsScreen
import com.example.android.htn.ui.projects.ProjectDetailScreen
import com.example.android.htn.ui.projects.ProjectEditScreen
import com.example.android.htn.ui.projects.ProjectsTab

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
        is SessionState.SignedIn -> key(s.profile.uid, s.profile.role) { SignedInApp(s.profile) }
    }
}

private enum class Tab(val route: String, val icon: ImageVector) {
    PROFILE("profile", Icons.Filled.AccountCircle),
    EVENTS("events", Icons.Filled.DateRange),
    PEOPLE("people", Icons.Filled.Person),
    PROJECTS("projects", Icons.Filled.Star),
}

/** Each role gets only the tabs it has data for. */
private fun tabsFor(role: Role): List<Pair<Tab, String>> = when (role) {
    Role.PARTICIPANT -> listOf(Tab.PROFILE to "Profile", Tab.EVENTS to "Events", Tab.PROJECTS to "My projects")
    Role.JUDGE -> listOf(Tab.PROFILE to "Profile", Tab.EVENTS to "Events", Tab.PROJECTS to "Judging")
    Role.VOLUNTEER -> listOf(Tab.PROFILE to "Profile", Tab.EVENTS to "Events")
    Role.ORGANIZER -> listOf(
        Tab.PROFILE to "Profile", Tab.EVENTS to "Events", Tab.PEOPLE to "People", Tab.PROJECTS to "Projects",
    )
}

private fun NavHostController.openTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
private fun SignedInApp(me: UserProfile) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val tabs = tabsFor(me.role)
    val tabRoutes = tabs.map { it.first.route }.toSet()

    Scaffold(
        bottomBar = {
            if (currentRoute in tabRoutes) {
                NavigationBar {
                    tabs.forEach { (tab, label) ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { navController.openTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        val openEvent = { id: String -> navController.navigate("events/$id") }
        val openPerson = { uid: String -> navController.navigate("people/$uid") }
        val openProject = { eventId: String, projectId: String -> navController.navigate("events/$eventId/projects/$projectId") }
        val back: () -> Unit = { navController.popBackStack() }
        NavHost(
            navController = navController,
            startDestination = Tab.PROFILE.route,
            modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
        ) {
            composable(Tab.PROFILE.route) {
                ProfileScreen(me, onOpenEvent = openEvent, onOpenTab = { if (it in tabRoutes) navController.openTab(it) })
            }
            composable(Tab.EVENTS.route) { EventsScreen(me, onOpenEvent = openEvent) }
            composable("events/{eventId}") { entry ->
                val eventId = entry.arguments?.getString("eventId").orEmpty()
                EventDetailScreen(
                    eventId = eventId,
                    me = me,
                    onBack = back,
                    onOpenPerson = { if (me.role.canSeeEveryone) openPerson(it) },
                    onOpenProjects = { navController.navigate("events/$eventId/projects") },
                    onEditProject = { navController.navigate("events/$eventId/submit") },
                )
            }
            if (me.role == Role.PARTICIPANT) {
                composable("events/{eventId}/submit") { entry ->
                    ProjectEditScreen(entry.arguments?.getString("eventId").orEmpty(), me, onBack = back)
                }
            }
            if (Tab.PROJECTS.route in tabRoutes) {
                composable(Tab.PROJECTS.route) {
                    ProjectsTab(
                        me,
                        onOpenEventProjects = { navController.navigate("events/$it/projects") },
                        onOpenOwnProject = { navController.navigate("events/$it/submit") },
                    )
                }
            }
            if (me.role.canSeeAllProjects) {
                composable("events/{eventId}/projects") { entry ->
                    val eventId = entry.arguments?.getString("eventId").orEmpty()
                    EventProjectsScreen(eventId, me, onBack = back, onOpenProject = { openProject(eventId, it) })
                }
                composable("events/{eventId}/projects/{projectId}") { entry ->
                    ProjectDetailScreen(
                        eventId = entry.arguments?.getString("eventId").orEmpty(),
                        projectId = entry.arguments?.getString("projectId").orEmpty(),
                        me = me,
                        onBack = back,
                    )
                }
            }
            if (me.role.canSeeEveryone) {
                composable(Tab.PEOPLE.route) { PeopleScreen(onOpenPerson = openPerson) }
                composable("people/{uid}") { entry ->
                    PersonScreen(
                        uid = entry.arguments?.getString("uid").orEmpty(),
                        me = me,
                        onBack = back,
                        onOpenEvent = openEvent,
                        onOpenProject = openProject,
                    )
                }
            }
        }
    }
}
