package com.apps.naviai.scene

import com.apps.naviai.audio.AnnouncementLanguage
import com.apps.naviai.detection.risk.RiskLevel
import com.apps.naviai.detection.tracking.MovementDirection

/**
 * Builds the text prompt sent alongside the camera frame to the vision LLM
 * for the Scene Understanding feature (acceptance criteria: recognize
 * common objects/structures, give room/area context when possible, never
 * invent objects that aren't there -- hedge with "mungkin"/"possibly" when
 * unsure, and answer in 1-2 sentences). Kept as a pure function (no
 * network, no Android framework types beyond what's already in
 * [SceneContext]) so the exact wording can be unit tested without a live
 * LLM endpoint.
 */
object ScenePromptBuilder {
    fun build(context: SceneContext, language: AnnouncementLanguage): String {
        val objectLines = if (context.objects.isEmpty()) {
            noObjectsLine(language)
        } else {
            context.objects.joinToString("\n") { describeObject(it, language) }
        }

        return when (language) {
            AnnouncementLanguage.INDONESIAN -> """
                Kamu adalah NAVI, asisten navigasi untuk pengguna tunanetra/low-vision. Pengguna baru saja bertanya: "${context.userCommandText}".

                Gambar terlampir adalah pandangan kamera pengguna saat ini. Sensor jarak lokal juga mendeteksi objek berikut (data ini SUDAH TERUKUR, bukan tebakan):
                $objectLines

                Tingkat risiko tertinggi saat ini: ${riskLabel(context.highestRisk, language)}.

                Tugasmu: berikan ringkasan singkat (1-2 kalimat, Bahasa Indonesia) tentang lingkungan pengguna. Sebutkan jenis ruangan/area jika bisa disimpulkan dari gambar (mis. "ruang tamu kecil", "koridor kantor"). Jika membantu, sebutkan juga posisi relatif objek-objek utama (mis. "di depan Anda", "di sisi kiri", "di sebelah kanan") supaya deskripsinya lebih berguna untuk navigasi -- contoh gaya jawaban: "Di depan Anda ada meja kayu dengan dua kursi. Di sisi kiri terdapat lemari, dan di sebelah kanan ada pintu menuju ruang lain." Gunakan HANYA objek yang benar-benar terlihat di gambar atau ada di daftar sensor di atas -- JANGAN mengarang objek yang tidak ada. Jika tidak yakin tentang sesuatu, gunakan kata "mungkin" atau nyatakan ketidakpastiannya secara eksplisit. Jangan sebutkan angka jarak/koordinat teknis mentah, cukup deskripsi natural.
            """.trimIndent()

            AnnouncementLanguage.ENGLISH -> """
                You are NAVI, a navigation assistant for a blind/low-vision user. The user just asked: "${context.userCommandText}".

                The attached image is the user's current camera view. A local distance sensor also detected the following objects (this data is MEASURED, not guessed):
                $objectLines

                Current highest risk level: ${riskLabel(context.highestRisk, language)}.

                Your task: give a short summary (1-2 sentences, English) of the user's surroundings. Mention the type of room/area if you can infer it from the image (e.g. "small living room", "office corridor"). When it helps, mention the relative position of key objects too (e.g. "in front of you", "on the left", "on the right") to make the description more useful for navigation -- example answer style: "There's a wooden table with two chairs in front of you. On the left is a cabinet, and on the right there's a door to another room." Use ONLY objects actually visible in the image or listed above -- DO NOT invent objects that aren't there. If unsure about something, say "possibly" or explicitly state the uncertainty. Don't mention raw technical distance numbers/coordinates, keep it natural.
            """.trimIndent()
        }
    }

    private fun describeObject(obj: DetectedObjectSummary, language: AnnouncementLanguage): String {
        val distance = obj.distanceMeters?.let { "%.1f".format(it) }
        val confidencePct = (obj.confidence * 100).toInt()
        return when (language) {
            AnnouncementLanguage.INDONESIAN -> buildString {
                append("- ${obj.label}")
                if (distance != null) append(", ~${distance}m")
                append(", ${movementLabel(obj.movement, language)}")
                append(", keyakinan deteksi ${confidencePct}%")
                append(", risiko ${riskLabel(obj.riskLevel, language)}")
            }
            AnnouncementLanguage.ENGLISH -> buildString {
                append("- ${obj.label}")
                if (distance != null) append(", ~${distance}m away")
                append(", ${movementLabel(obj.movement, language)}")
                append(", detection confidence ${confidencePct}%")
                append(", risk ${riskLabel(obj.riskLevel, language)}")
            }
        }
    }

    private fun movementLabel(movement: MovementDirection, language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> when (movement) {
            MovementDirection.APPROACHING -> "mendekat"
            MovementDirection.RECEDING -> "menjauh"
            MovementDirection.STATIONARY -> "diam"
            MovementDirection.MOVING_LEFT_TO_RIGHT -> "bergerak ke kanan"
            MovementDirection.MOVING_RIGHT_TO_LEFT -> "bergerak ke kiri"
            MovementDirection.UNKNOWN -> "gerakan tidak diketahui"
        }
        AnnouncementLanguage.ENGLISH -> when (movement) {
            MovementDirection.APPROACHING -> "approaching"
            MovementDirection.RECEDING -> "moving away"
            MovementDirection.STATIONARY -> "stationary"
            MovementDirection.MOVING_LEFT_TO_RIGHT -> "moving right"
            MovementDirection.MOVING_RIGHT_TO_LEFT -> "moving left"
            MovementDirection.UNKNOWN -> "unknown movement"
        }
    }

    private fun riskLabel(risk: RiskLevel, language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> when (risk) {
            RiskLevel.SAFE -> "aman"
            RiskLevel.LOW -> "rendah"
            RiskLevel.MEDIUM -> "sedang"
            RiskLevel.HIGH -> "tinggi"
            RiskLevel.CRITICAL -> "kritis"
        }
        AnnouncementLanguage.ENGLISH -> when (risk) {
            RiskLevel.SAFE -> "safe"
            RiskLevel.LOW -> "low"
            RiskLevel.MEDIUM -> "medium"
            RiskLevel.HIGH -> "high"
            RiskLevel.CRITICAL -> "critical"
        }
    }

    private fun noObjectsLine(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "(Tidak ada objek yang terdeteksi sensor saat ini.)"
        AnnouncementLanguage.ENGLISH -> "(No objects currently detected by the sensor.)"
    }
}
