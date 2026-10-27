package com.smartcess.app.features.appointments

import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartcess.app.R
import com.smartcess.app.core.ai.SparkClient
import com.smartcess.app.core.work.ReminderScheduler
import com.smartcess.app.data.local.entity.AppointmentEntity
import com.smartcess.app.data.repository.AppointmentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class AppointmentFormState(
    val title: String = "",
    val provider: String = "",
    val location: String = "",
    val doctor: String = "",
    val startText: String = "",
    val endText: String = "",
    val prepNotes: String = "",
    val extractSource: String = "",
    val fieldErrorRes: Int? = null,
    val messageRes: Int? = null,
    val errorRes: Int? = null,
)

@HiltViewModel
class AppointmentsViewModel @Inject constructor(
    private val appointments: AppointmentRepository,
    private val spark: SparkClient,
) : ViewModel() {

    val items: StateFlow<List<AppointmentEntity>> = appointments.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _form = MutableStateFlow(AppointmentFormState())
    val form: StateFlow<AppointmentFormState> = _form.asStateFlow()

    fun onForm(form: AppointmentFormState) {
        _form.value = form.copy(fieldErrorRes = null, messageRes = null, errorRes = null)
    }

    /** Fills the form from pasted text via Spark; offline leaves the form untouched. */
    fun fillFromText() {
        val source = _form.value.extractSource
        if (source.isBlank()) return
        viewModelScope.launch {
            val extracted = runCatching { spark.extractAppointment(source) }.getOrNull()
            if (extracted == null) {
                _form.value = _form.value.copy(errorRes = R.string.banner_offline)
                return@launch
            }
            _form.value = _form.value.copy(
                title = extracted.title,
                provider = extracted.provider ?: "",
                location = extracted.location ?: "",
                doctor = extracted.doctor ?: "",
                startText = extracted.start_iso,
                endText = extracted.end_iso ?: "",
                prepNotes = extracted.prep_notes ?: "",
            )
        }
    }

    /** Saves only after the caller shows the confirmation dialog. */
    fun confirmSave(onSaved: (Long) -> Unit) {
        val form = _form.value
        val start = parseDateTime(form.startText)
        if (form.title.isBlank() || start == null) {
            _form.value = form.copy(fieldErrorRes = R.string.error_generic)
            return
        }
        viewModelScope.launch {
            val outcome = appointments.save(
                AppointmentEntity(
                    title = form.title.trim(),
                    provider = form.provider.trim().ifBlank { null },
                    location = form.location.trim().ifBlank { null },
                    doctorName = form.doctor.trim().ifBlank { null },
                    startMillis = start,
                    endMillis = parseDateTime(form.endText),
                    prepNotes = form.prepNotes.trim().ifBlank { null },
                ),
            )
            if (outcome.validation.hasErrors || outcome.savedId == null) {
                _form.value = form.copy(errorRes = R.string.error_generic)
            } else {
                _form.value = AppointmentFormState(messageRes = R.string.appt_saved)
                onSaved(outcome.savedId)
            }
        }
    }

    companion object {
        private val formats = listOf(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"),
            DateTimeFormatter.ofPattern("d/M/yyyy HH:mm"),
            DateTimeFormatter.ofPattern("d-M-yyyy HH:mm"),
        )

        fun parseDateTime(text: String): Long? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            runCatching { return Instant.parse(trimmed).toEpochMilli() }
            for (format in formats) {
                runCatching {
                    return LocalDateTime.parse(trimmed, format)
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }
            }
            return null
        }
    }
}

data class AppointmentDetailUiState(
    val loading: Boolean = true,
    val appointment: AppointmentEntity? = null,
    val notFound: Boolean = false,
    val messageRes: Int? = null,
)

@HiltViewModel
class AppointmentDetailViewModel @Inject constructor(
    private val appointments: AppointmentRepository,
    private val scheduler: ReminderScheduler,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(AppointmentDetailUiState())
    val state: StateFlow<AppointmentDetailUiState> = _state.asStateFlow()

    fun load(appointmentId: Long) {
        viewModelScope.launch {
            val appt = appointments.getById(appointmentId)
            _state.value = if (appt == null) {
                AppointmentDetailUiState(loading = false, notFound = true)
            } else {
                AppointmentDetailUiState(loading = false, appointment = appt)
            }
        }
    }

    /**
     * Calendar write only after the caller confirms (and grants WRITE_CALENDAR).
     * Spec fallback: CALENDAR_FAILED -> SAVE_LOCALLY_AND_WARN.
     */
    fun confirmCalendar() {
        val appt = _state.value.appointment ?: return
        if (appt.calendarEventId != null) {
            _state.value = _state.value.copy(messageRes = R.string.appt_calendar_saved)
            return
        }
        viewModelScope.launch {
            val eventId = runCatching { insertCalendarEvent(appt) }.getOrNull()
            if (eventId == null) {
                _state.value = _state.value.copy(messageRes = R.string.appt_calendar_local_warn)
            } else {
                appointments.setCalendarEvent(appt.id, eventId)
                _state.value = _state.value.copy(messageRes = R.string.appt_calendar_saved)
            }
            load(appt.id)
        }
    }

    private suspend fun insertCalendarEvent(appt: AppointmentEntity): Long? =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val calendarId = runCatching {
                resolver.query(
                    CalendarContract.Calendars.CONTENT_URI,
                    arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY),
                    "${CalendarContract.Calendars.VISIBLE}=1",
                    null, null,
                )?.use { cursor ->
                    var first: Long? = null
                    var primary: Long? = null
                    val idCol = cursor.getColumnIndex(CalendarContract.Calendars._ID)
                    val primaryCol = cursor.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        if (first == null) first = id
                        if (primaryCol >= 0 && cursor.getInt(primaryCol) == 1) primary = id
                    }
                    primary ?: first
                }
            }.getOrNull() ?: return@withContext null
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, appt.title)
                put(CalendarContract.Events.DTSTART, appt.startMillis)
                put(CalendarContract.Events.DTEND, appt.endMillis ?: (appt.startMillis + DEFAULT_EVENT_MILLIS))
                put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
                appt.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
                val notes = listOfNotNull(appt.provider, appt.doctorName, appt.prepNotes).joinToString("\n")
                if (notes.isNotBlank()) put(CalendarContract.Events.DESCRIPTION, notes)
            }
            runCatching {
                resolver.insert(CalendarContract.Events.CONTENT_URI, values)?.lastPathSegment?.toLongOrNull()
            }.getOrNull()
        }

    /** Shown when calendar permission is denied: the appointment stays local. */
    fun onCalendarDenied() {
        _state.value = _state.value.copy(messageRes = R.string.appt_calendar_denied)
    }

    /** Travel reminder only after the caller confirms the offset. */
    fun confirmTravelReminder(minutesBefore: Int) {
        val appt = _state.value.appointment ?: return
        val triggerAt = appt.startMillis - minutesBefore * 60L * 1000L
        if (triggerAt <= System.currentTimeMillis()) {
            _state.value = _state.value.copy(messageRes = R.string.error_generic)
            return
        }
        viewModelScope.launch {
            scheduler.scheduleTravelNudge(appt.id, triggerAt)
            appointments.save(appt.copy(travelReminderMinutes = minutesBefore))
            _state.value = _state.value.copy(messageRes = R.string.appt_travel_saved)
            load(appt.id)
        }
    }

    fun delete(onDone: () -> Unit) {
        val appt = _state.value.appointment ?: return
        viewModelScope.launch {
            scheduler.cancelTravelNudge(appt.id)
            appointments.delete(appt.id)
            onDone()
        }
    }

    companion object {
        /** Default event length when the letter gives no end time. */
        private const val DEFAULT_EVENT_MILLIS = 60L * 60L * 1000L
    }
}
