package com.example.android.htn.ui.events

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.android.htn.data.Attendee
import com.example.android.htn.data.AttendanceRepository
import com.example.android.htn.data.AttendeeRole
import com.example.android.htn.data.CheckInResult
import com.example.android.htn.data.CheckedInAttendee
import com.example.android.htn.data.Event
import com.example.android.htn.profiling.AttendanceProfiler
import com.example.android.htn.profiling.EventSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class EventDetailState(
    val event: Event? = null,
    val checkedIn: List<CheckedInAttendee> = emptyList(),
    val summary: EventSummary? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class EventDetailViewModel(
    private val eventId: Long,
    private val repository: AttendanceRepository,
) : ViewModel() {

    val query = MutableStateFlow("")

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    val state: StateFlow<EventDetailState> = combine(
        repository.event(eventId),
        repository.checkedInAttendees(eventId),
        repository.events,
        repository.checkIns,
    ) { event, checkedIn, events, checkIns ->
        EventDetailState(
            event = event,
            checkedIn = checkedIn,
            summary = event?.let {
                AttendanceProfiler.summarizeEvent(it, events, checkedIn.map { c -> c.attendee }, checkIns)
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EventDetailState())

    val searchResults: StateFlow<List<Attendee>> = query
        .flatMapLatest { q -> if (q.isBlank()) flowOf(emptyList()) else repository.searchAttendees(q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun checkIn(attendee: Attendee) = viewModelScope.launch {
        report(repository.checkIn(eventId, attendee))
        query.value = ""
    }

    fun registerAndCheckIn(name: String, email: String, organization: String, role: AttendeeRole) =
        viewModelScope.launch {
            report(repository.registerAndCheckIn(eventId, name, email, organization, role))
            query.value = ""
        }

    fun undoCheckIn(attendeeId: Long) = viewModelScope.launch {
        repository.undoCheckIn(eventId, attendeeId)
    }

    fun deleteEvent(onDeleted: () -> Unit) = viewModelScope.launch {
        repository.deleteEvent(eventId)
        onDeleted()
    }

    private fun report(result: CheckInResult) {
        messageChannel.trySend(
            when (result) {
                is CheckInResult.CheckedIn -> "${result.attendee.name} checked in"
                is CheckInResult.AlreadyCheckedIn -> "${result.attendee.name} is already checked in"
            }
        )
    }
}
