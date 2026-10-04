package com.example.android.htn.ui.events

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.android.htn.data.AttendanceRepository
import com.example.android.htn.data.CheckIn
import com.example.android.htn.data.CheckInMethod
import com.example.android.htn.data.CheckInResult
import com.example.android.htn.data.Event
import com.example.android.htn.data.QrPass
import com.example.android.htn.data.UserProfile
import com.example.android.htn.profiling.AttendanceProfiler
import com.example.android.htn.profiling.EventSummary
import com.example.android.htn.ui.userMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class Arrival(val user: UserProfile?, val checkIn: CheckIn)

data class EventDetailState(
    val loading: Boolean = true,
    val event: Event? = null,
    val registered: Boolean = false,
    val checkedIn: Boolean = false,
    /** Staff only. */
    val summary: EventSummary? = null,
    /** Staff only, most recent first. */
    val arrivals: List<Arrival> = emptyList(),
    /** Staff only, for manual check-in. */
    val people: List<UserProfile> = emptyList(),
)

sealed interface ScanOutcome {
    data class Done(val result: CheckInResult, val method: CheckInMethod) : ScanOutcome
    data object NotAPass : ScanOutcome
    data class Failed(val message: String) : ScanOutcome
}

class EventDetailViewModel(
    private val eventId: String,
    private val me: UserProfile,
    private val repository: AttendanceRepository,
) : ViewModel() {

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    private val _lastScan = MutableStateFlow<ScanOutcome?>(null)
    /** Result of the latest check-in attempt, shown prominently at the check-in desk. */
    val lastScan: StateFlow<ScanOutcome?> = _lastScan.asStateFlow()

    val state: StateFlow<EventDetailState> = combine(
        repository.event(eventId),
        repository.events(),
        if (me.role.isStaff) repository.allCheckIns() else repository.checkInsOf(me.uid),
        if (me.role.isStaff) repository.allRegistrations() else repository.registrationsOf(me.uid),
        if (me.role.isStaff) repository.users() else flowOf(listOf(me)),
    ) { event, events, checkIns, registrations, users ->
        val usersById = users.associateBy { it.uid }
        EventDetailState(
            loading = false,
            event = event,
            registered = registrations.any { it.eventId == eventId && it.userId == me.uid },
            checkedIn = checkIns.any { it.eventId == eventId && it.userId == me.uid },
            summary = if (me.role.isStaff && event != null) {
                AttendanceProfiler.summarizeEvent(event, events, usersById, checkIns, registrations)
            } else null,
            arrivals = if (me.role.isStaff) {
                checkIns.filter { it.eventId == eventId }
                    .sortedByDescending { it.checkedInAt }
                    .map { Arrival(usersById[it.userId], it) }
            } else emptyList(),
            people = if (me.role.canCheckIn) users else emptyList(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EventDetailState())

    fun setRegistered(register: Boolean) = launchReporting {
        if (register) repository.register(eventId, me.uid) else repository.unregister(eventId, me.uid)
    }

    fun onScanned(raw: String) {
        val uid = QrPass.decode(raw)
        if (uid == null) _lastScan.value = ScanOutcome.NotAPass else checkIn(uid, CheckInMethod.QR)
    }

    fun onScanFailed(e: Exception) {
        _lastScan.value = ScanOutcome.Failed(e.userMessage())
    }

    fun checkIn(userId: String, method: CheckInMethod) = viewModelScope.launch {
        _lastScan.value = try {
            ScanOutcome.Done(repository.checkIn(eventId, userId, me.uid, method), method)
        } catch (e: Exception) {
            ScanOutcome.Failed(e.userMessage())
        }
    }

    fun dismissScan() {
        _lastScan.value = null
    }

    fun undoCheckIn(userId: String) = launchReporting { repository.undoCheckIn(eventId, userId) }

    fun deleteEvent(onDeleted: () -> Unit) = launchReporting {
        repository.deleteEvent(eventId)
        onDeleted()
    }

    private fun launchReporting(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (e: Exception) {
            messageChannel.trySend(e.userMessage())
        }
    }
}
