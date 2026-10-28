package com.smartcess.app.navigation

/**
 * Notification deep links (C2/M4/M10). Pure and JVM-testable; MainActivity
 * reads the Intent extras and passes the resulting route to the nav graph.
 * Invalid ids (null, zero, negative) yield null: normal cold start.
 */
fun deepLinkRoute(reminderId: Long?, appointmentId: Long?): String? = when {
    reminderId != null && reminderId > 0 -> Routes.reminder(reminderId)
    appointmentId != null && appointmentId > 0 -> Routes.appointment(appointmentId)
    else -> null
}
