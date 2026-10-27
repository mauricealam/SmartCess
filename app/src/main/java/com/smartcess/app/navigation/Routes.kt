package com.smartcess.app.navigation

/** All navigation destinations (spec screen list). Module agents implement each screen. */
object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    /** Kept for back-compat; Home now opens More as a bottom sheet instead. */
    const val MORE = "more"
    const val DOCUMENTS = "documents"
    const val APPOINTMENTS = "appointments"
    const val ROUTINES = "routines"
    const val VOICE = "voice"
    const val CAMERA = "camera"
    const val BILLS = "bills"
    const val BILL_RESULT = "bill_result/{billId}"
    const val COMPARISON = "comparison/{billId}"
    const val REMINDER = "reminder/{reminderId}"
    const val BRIEFING = "briefing"
    const val EMAIL = "email"
    const val DOC_RESULT = "doc_result/{docId}"
    const val APPOINTMENT = "appointment/{appointmentId}"
    const val ROUTINE = "routine/{routineId}"
    const val SETTINGS = "settings"

    const val ARG_BILL_ID = "billId"
    const val ARG_REMINDER_ID = "reminderId"
    const val ARG_DOC_ID = "docId"
    const val ARG_APPOINTMENT_ID = "appointmentId"
    const val ARG_ROUTINE_ID = "routineId"

    fun billResult(billId: Long) = "bill_result/$billId"
    fun comparison(billId: Long) = "comparison/$billId"
    fun reminder(reminderId: Long) = "reminder/$reminderId"
    fun docResult(docId: Long) = "doc_result/$docId"
    fun appointment(appointmentId: Long) = "appointment/$appointmentId"
    fun routine(routineId: Long) = "routine/$routineId"
}
