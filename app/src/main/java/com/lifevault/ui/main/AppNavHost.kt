package com.lifevault.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.lifevault.domain.model.EntryType
import com.lifevault.ui.attendance.AttendanceScreen
import com.lifevault.ui.attendance.HolidaysScreen
import com.lifevault.ui.attendance.TimetableEditScreen
import com.lifevault.ui.attendance.TimetableImportScreen
import com.lifevault.ui.attendance.TimetableScreen
import com.lifevault.ui.events.EventEditScreen
import com.lifevault.ui.events.EventsScreen
import com.lifevault.ui.home.HomeScreen
import com.lifevault.ui.media.CameraScreen
import com.lifevault.ui.media.MediaGalleryScreen
import com.lifevault.ui.media.MediaViewerScreen
import com.lifevault.ui.media.RecorderScreen
import com.lifevault.ui.mood.MoodCheckInScreen
import com.lifevault.ui.mood.MoodReportScreen
import com.lifevault.ui.more.MoreScreen
import com.lifevault.ui.rules.JobLogScreen
import com.lifevault.ui.rules.RuleSetEditScreen
import com.lifevault.ui.rules.RuleSetsScreen
import com.lifevault.ui.rules.StartJobScreen
import com.lifevault.ui.settings.BackupScreen
import com.lifevault.ui.settings.ChangePassphraseScreen
import com.lifevault.ui.settings.ReliabilityScreen
import com.lifevault.ui.settings.RotateRecoveryScreen
import com.lifevault.ui.settings.SecurityInfoScreen
import com.lifevault.ui.settings.SettingsScreen
import com.lifevault.ui.study.StudyPageScreen
import com.lifevault.ui.study.StudyScreen
import com.lifevault.ui.trackers.EntryEditScreen
import com.lifevault.ui.trackers.TrackScreen

object Routes {
    const val HOME = "home"
    const val ATTENDANCE = "attendance"
    const val TRACK = "track"
    const val MEDIA = "media"
    const val MORE = "more"

    const val TIMETABLE = "timetable"
    const val TIMETABLE_IMPORT = "timetable/import"
    const val HOLIDAYS = "holidays"
    const val EVENTS = "events"
    const val STUDY = "study"
    const val MOOD_REPORT = "mood/report"
    const val RULES = "rules"
    const val JOB_LOG = "joblog"
    const val CAMERA = "camera"
    const val RECORDER = "recorder"
    const val SETTINGS = "settings"
    const val PASSPHRASE = "settings/passphrase"
    const val RECOVERY = "settings/recovery"
    const val BACKUP = "backup"
    const val RELIABILITY = "reliability"
    const val SECURITY = "security"

    const val NEW = "new"

    /** Result key set on the previous back-stack entry by the camera/recorder with the new media id. */
    const val RESULT_MEDIA_ID = "new_media_id"

    fun timetableEdit(id: String) = "timetable/edit/$id"
    fun event(id: String) = "event/$id"
    fun studyPage(id: String) = "study/$id"
    fun entry(type: EntryType, id: String = NEW) = "entry/${type.name}/$id"
    fun moodCheckIn(date: String? = null, id: String? = null) =
        "mood/checkin?date=${date.orEmpty()}&id=${id.orEmpty()}"
    fun ruleSet(id: String) = "rules/$id"
    fun startJob(setId: String? = null, jobName: String? = null) =
        "startjob?set=${setId.orEmpty()}&job=${android.net.Uri.encode(jobName.orEmpty())}"
    fun mediaViewer(id: String) = "media/$id"
}

private fun String.orNullIfBlank() = takeIf { it.isNotBlank() }

@Composable
fun AppNavHost(nav: NavHostController, modifier: Modifier = Modifier) {
    NavHost(nav, startDestination = Routes.HOME, modifier = modifier) {
        composable(Routes.HOME) { HomeScreen(nav) }
        composable(Routes.ATTENDANCE) { AttendanceScreen(nav) }
        composable(Routes.TRACK) { TrackScreen(nav) }
        composable(Routes.MEDIA) { MediaGalleryScreen(nav) }
        composable(Routes.MORE) { MoreScreen(nav) }

        composable(Routes.TIMETABLE) { TimetableScreen(nav) }
        composable(Routes.TIMETABLE_IMPORT) { TimetableImportScreen(nav) }
        composable("timetable/edit/{id}") { TimetableEditScreen(nav, it.arguments?.getString("id") ?: Routes.NEW) }
        composable(Routes.HOLIDAYS) { HolidaysScreen(nav) }

        composable(Routes.EVENTS) { EventsScreen(nav) }
        composable("event/{id}") { EventEditScreen(nav, it.arguments?.getString("id") ?: Routes.NEW) }

        composable(Routes.STUDY) { StudyScreen(nav) }
        composable("study/{id}") { StudyPageScreen(nav, it.arguments?.getString("id")!!) }

        composable("entry/{type}/{id}") {
            val type = runCatching { EntryType.valueOf(it.arguments?.getString("type")!!) }.getOrDefault(EntryType.MONEY)
            EntryEditScreen(nav, type, it.arguments?.getString("id") ?: Routes.NEW)
        }

        composable(
            "mood/checkin?date={date}&id={id}",
            arguments = listOf(
                navArgument("date") { type = NavType.StringType; defaultValue = "" },
                navArgument("id") { type = NavType.StringType; defaultValue = "" },
            ),
        ) {
            MoodCheckInScreen(nav, it.arguments?.getString("date")?.orNullIfBlank(), it.arguments?.getString("id")?.orNullIfBlank())
        }
        composable(Routes.MOOD_REPORT) { MoodReportScreen(nav) }

        composable(Routes.RULES) { RuleSetsScreen(nav) }
        composable("rules/{id}") { RuleSetEditScreen(nav, it.arguments?.getString("id") ?: Routes.NEW) }
        composable(
            "startjob?set={set}&job={job}",
            arguments = listOf(
                navArgument("set") { type = NavType.StringType; defaultValue = "" },
                navArgument("job") { type = NavType.StringType; defaultValue = "" },
            ),
        ) {
            StartJobScreen(nav, it.arguments?.getString("set")?.orNullIfBlank(), it.arguments?.getString("job")?.orNullIfBlank())
        }
        composable(Routes.JOB_LOG) { JobLogScreen(nav) }

        composable("media/{id}") { MediaViewerScreen(nav, it.arguments?.getString("id")!!) }
        composable(Routes.CAMERA) { CameraScreen(nav) }
        composable(Routes.RECORDER) { RecorderScreen(nav) }

        composable(Routes.SETTINGS) { SettingsScreen(nav) }
        composable(Routes.PASSPHRASE) { ChangePassphraseScreen(nav) }
        composable(Routes.RECOVERY) { RotateRecoveryScreen(nav) }
        composable(Routes.BACKUP) { BackupScreen(nav) }
        composable(Routes.RELIABILITY) { ReliabilityScreen(nav) }
        composable(Routes.SECURITY) { SecurityInfoScreen(nav) }
    }
}
