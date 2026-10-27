package com.smartcess.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.smartcess.app.features.appointments.AppointmentDetailScreen
import com.smartcess.app.features.appointments.AppointmentsScreen
import com.smartcess.app.features.bills.BillResultScreen
import com.smartcess.app.features.bills.BillsScreen
import com.smartcess.app.features.bills.ComparisonScreen
import com.smartcess.app.features.briefing.BriefingScreen
import com.smartcess.app.features.camera.CameraScreen
import com.smartcess.app.features.documents.DocumentResultScreen
import com.smartcess.app.features.documents.DocumentsScreen
import com.smartcess.app.features.email.EmailScreen
import com.smartcess.app.features.home.HomeScreen
import com.smartcess.app.features.more.MoreScreen
import com.smartcess.app.features.onboarding.OnboardingScreen
import com.smartcess.app.features.onboarding.isOnboardingDone
import com.smartcess.app.features.reminders.ReminderScreen
import com.smartcess.app.features.routines.RoutineDetailScreen
import com.smartcess.app.features.routines.RoutinesScreen
import com.smartcess.app.features.settings.SettingsScreen
import com.smartcess.app.features.voice.VoiceScreen

/** Root navigation graph: onboarding → Home (progressive disclosure) → modules. */
@Composable
fun SmartCessNavGraph() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val start = remember { if (isOnboardingDone(context)) Routes.HOME else Routes.ONBOARDING }
    NavHost(navController = navController, startDestination = start) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onDone = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
                onScanBill = {
                    navController.navigate(Routes.CAMERA) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                onTalk = { navController.navigate(Routes.VOICE) },
                onToday = { navController.navigate(Routes.BRIEFING) },
                onScanBill = { navController.navigate(Routes.CAMERA) },
                onBills = { navController.navigate(Routes.BILLS) },
                onDocuments = { navController.navigate(Routes.DOCUMENTS) },
                onAppointments = { navController.navigate(Routes.APPOINTMENTS) },
                onRoutines = { navController.navigate(Routes.ROUTINES) },
                onEmail = { navController.navigate(Routes.EMAIL) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.MORE) {
            MoreScreen(
                onBills = { navController.navigate(Routes.BILLS) },
                onDocuments = { navController.navigate(Routes.DOCUMENTS) },
                onAppointments = { navController.navigate(Routes.APPOINTMENTS) },
                onRoutines = { navController.navigate(Routes.ROUTINES) },
                onEmail = { navController.navigate(Routes.EMAIL) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.VOICE) {
            VoiceScreen(
                onBack = { navController.popBackStack() },
                onNavigate = { route -> navController.navigate(route) },
            )
        }
        composable(Routes.CAMERA) {
            CameraScreen(
                onBack = { navController.popBackStack() },
                onCaptured = { billId ->
                    navController.navigate(Routes.billResult(billId)) {
                        popUpTo(Routes.CAMERA) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.BILLS) {
            BillsScreen(
                onBill = { billId -> navController.navigate(Routes.billResult(billId)) },
                onScan = { navController.navigate(Routes.CAMERA) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.BILL_RESULT,
            arguments = listOf(navArgument(Routes.ARG_BILL_ID) { type = NavType.LongType }),
        ) { backStackEntry ->
            BillResultScreen(
                billId = backStackEntry.arguments?.getLong(Routes.ARG_BILL_ID) ?: -1L,
                onCompare = { billId -> navController.navigate(Routes.comparison(billId)) },
                onRescan = { navController.navigate(Routes.CAMERA) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.COMPARISON,
            arguments = listOf(navArgument(Routes.ARG_BILL_ID) { type = NavType.LongType }),
        ) { backStackEntry ->
            ComparisonScreen(
                billId = backStackEntry.arguments?.getLong(Routes.ARG_BILL_ID) ?: -1L,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.REMINDER,
            arguments = listOf(navArgument(Routes.ARG_REMINDER_ID) { type = NavType.LongType }),
        ) { backStackEntry ->
            ReminderScreen(
                reminderId = backStackEntry.arguments?.getLong(Routes.ARG_REMINDER_ID) ?: -1L,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.BRIEFING) {
            BriefingScreen(
                onBack = { navController.popBackStack() },
                onBill = { billId -> navController.navigate(Routes.billResult(billId)) },
                onAppointment = { id -> navController.navigate(Routes.appointment(id)) },
                onEmail = { navController.navigate(Routes.EMAIL) },
                onDocument = { id -> navController.navigate(Routes.docResult(id)) },
                onRoutine = { id -> navController.navigate(Routes.routine(id)) },
            )
        }
        composable(Routes.EMAIL) {
            EmailScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.DOCUMENTS) {
            DocumentsScreen(
                onDocument = { id -> navController.navigate(Routes.docResult(id)) },
                onScan = { navController.navigate(Routes.CAMERA) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.DOC_RESULT,
            arguments = listOf(navArgument(Routes.ARG_DOC_ID) { type = NavType.LongType }),
        ) { backStackEntry ->
            DocumentResultScreen(
                docId = backStackEntry.arguments?.getLong(Routes.ARG_DOC_ID) ?: -1L,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.APPOINTMENTS) {
            AppointmentsScreen(
                onAppointment = { id -> navController.navigate(Routes.appointment(id)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.APPOINTMENT,
            arguments = listOf(navArgument(Routes.ARG_APPOINTMENT_ID) { type = NavType.LongType }),
        ) { backStackEntry ->
            AppointmentDetailScreen(
                appointmentId = backStackEntry.arguments?.getLong(Routes.ARG_APPOINTMENT_ID) ?: -1L,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.ROUTINES) {
            RoutinesScreen(
                onRoutine = { id -> navController.navigate(Routes.routine(id)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.ROUTINE,
            arguments = listOf(navArgument(Routes.ARG_ROUTINE_ID) { type = NavType.LongType }),
        ) { backStackEntry ->
            RoutineDetailScreen(
                routineId = backStackEntry.arguments?.getLong(Routes.ARG_ROUTINE_ID) ?: -1L,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
