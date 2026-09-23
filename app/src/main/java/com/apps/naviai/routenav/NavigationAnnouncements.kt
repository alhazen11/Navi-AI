package com.apps.naviai.routenav

import com.apps.naviai.audio.AnnouncementLanguage
import kotlin.math.roundToInt

/** Localized (ID/EN) spoken phrases for route recording/navigation events. Pure string building, no TTS calls -- see [com.apps.naviai.audio.AnnouncementManager] for the equivalent pattern used elsewhere in this app. */
object NavigationAnnouncements {
    fun forTurn(turn: TurnInstruction, language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> when (turn) {
            TurnInstruction.STRAIGHT -> "Berjalan lurus."
            TurnInstruction.SLIGHT_RIGHT -> "Belok sedikit ke kanan."
            TurnInstruction.RIGHT -> "Belok ke kanan."
            TurnInstruction.SHARP_RIGHT -> "Putar badan ke kanan."
            TurnInstruction.SLIGHT_LEFT -> "Belok sedikit ke kiri."
            TurnInstruction.LEFT -> "Belok ke kiri."
            TurnInstruction.SHARP_LEFT -> "Putar badan ke kiri."
        }
        AnnouncementLanguage.ENGLISH -> when (turn) {
            TurnInstruction.STRAIGHT -> "Walk straight ahead."
            TurnInstruction.SLIGHT_RIGHT -> "Turn slightly right."
            TurnInstruction.RIGHT -> "Turn right."
            TurnInstruction.SHARP_RIGHT -> "Turn around to the right."
            TurnInstruction.SLIGHT_LEFT -> "Turn slightly left."
            TurnInstruction.LEFT -> "Turn left."
            TurnInstruction.SHARP_LEFT -> "Turn around to the left."
        }
    }

    fun deviated(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Anda menyimpang dari rute."
        AnnouncementLanguage.ENGLISH -> "You've deviated from the route."
    }

    fun backOnRoute(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Anda kembali ke jalur rute."
        AnnouncementLanguage.ENGLISH -> "You're back on the route."
    }

    fun approachingDestination(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Tujuan sudah dekat."
        AnnouncementLanguage.ENGLISH -> "You're approaching your destination."
    }

    fun arrived(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Anda telah sampai di tujuan."
        AnnouncementLanguage.ENGLISH -> "You have arrived at your destination."
    }

    fun poorGpsAccuracy(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "GPS kurang akurat, harap berhati-hati."
        AnnouncementLanguage.ENGLISH -> "GPS accuracy is low, please be careful."
    }

    fun distanceToNextPoint(meters: Double, language: AnnouncementLanguage): String {
        val rounded = meters.roundToInt()
        return when (language) {
            AnnouncementLanguage.INDONESIAN -> "Jarak ke titik berikutnya $rounded meter."
            AnnouncementLanguage.ENGLISH -> "Distance to next point: $rounded meters."
        }
    }

    fun routeStarted(routeName: String, language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Rute $routeName dimulai."
        AnnouncementLanguage.ENGLISH -> "Route $routeName started."
    }

    fun navigationFinished(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Navigasi selesai."
        AnnouncementLanguage.ENGLISH -> "Navigation finished."
    }

    fun navigationStopped(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Navigasi dihentikan."
        AnnouncementLanguage.ENGLISH -> "Navigation stopped."
    }

    fun routeNotFound(routeName: String, language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Rute $routeName tidak ditemukan."
        AnnouncementLanguage.ENGLISH -> "Route $routeName was not found."
    }

    fun gpsUnavailable(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Sinyal GPS tidak tersedia."
        AnnouncementLanguage.ENGLISH -> "GPS signal is unavailable."
    }

    fun locationPermissionRequired(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Izin lokasi diperlukan untuk navigasi. Silakan aktifkan di pengaturan aplikasi."
        AnnouncementLanguage.ENGLISH -> "Location permission is required for navigation. Please grant it in app settings."
    }
}
