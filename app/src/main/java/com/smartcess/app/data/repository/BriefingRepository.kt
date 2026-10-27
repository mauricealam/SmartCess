package com.smartcess.app.data.repository

import com.smartcess.app.data.local.entity.AppointmentEntity
import com.smartcess.app.data.local.entity.BillEntity
import com.smartcess.app.data.local.entity.DocumentEntity
import com.smartcess.app.data.local.entity.EmailItemEntity
import com.smartcess.app.data.local.entity.RoutineEntity
import javax.inject.Inject
import javax.inject.Singleton

/** Aggregated snapshot feeding Daily Briefing and the get_today_data tool call. */
data class TodayData(
    val overdueBills: List<BillEntity>,
    val billsDueSoon: List<BillEntity>,
    val appointments: List<AppointmentEntity>,
    val urgentEmails: List<EmailItemEntity>,
    val documentsWithDeadlines: List<DocumentEntity>,
    val routinesDue: List<RoutineEntity>,
) {
    val isEmpty: Boolean get() =
        overdueBills.isEmpty() && billsDueSoon.isEmpty() && appointments.isEmpty() &&
            urgentEmails.isEmpty() && documentsWithDeadlines.isEmpty() && routinesDue.isEmpty()
}

@Singleton
class BriefingRepository @Inject constructor(
    private val bills: BillRepository,
    private val appointments: AppointmentRepository,
    private val emails: EmailRepository,
    private val documents: DocumentRepository,
    private val routines: RoutineRepository,
) {
    /** Overdue bills, due < 7d, appts today/tomorrow, urgent email, doc deadlines, routines due. */
    suspend fun getTodayData(): TodayData = TodayData(
        overdueBills = bills.overdue(),
        billsDueSoon = bills.dueWithinDays(7),
        appointments = appointments.todayAndTomorrow(),
        urgentEmails = emails.unreadUrgent(),
        documentsWithDeadlines = documents.withDeadlines(),
        routinesDue = routines.dueToday(),
    )
}
