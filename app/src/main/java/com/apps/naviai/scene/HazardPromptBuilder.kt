package com.apps.naviai.scene

import com.apps.naviai.audio.AnnouncementLanguage

/**
 * Builds the text prompt sent alongside the camera frame to the vision LLM
 * for the Hazard Awareness feature (acceptance criteria: focus on large
 * objects ahead like chairs/tables/stairs/vehicles, hedge with "mungkin"
 * when distance/identity isn't certain, neutral/calm tone, and no definite
 * walking instructions -- e.g. never "turn left now", only advisory
 * phrasing like "silakan pelan-pelan melewatinya"). Pure function, no
 * network -- see [ScenePromptBuilder] for the same rationale. Unlike
 * [ScenePromptBuilder], there's no user command text here -- this feature
 * is triggered automatically by [HazardTrigger], not by a voice command.
 */
object HazardPromptBuilder {
    fun build(objects: List<DetectedObjectSummary>, language: AnnouncementLanguage): String {
        val objectLines = objects.joinToString("\n") { describeObject(it, language) }

        return when (language) {
            AnnouncementLanguage.INDONESIAN -> """
                Kamu adalah NAVI, asisten navigasi untuk pengguna tunanetra/low-vision. Sensor jarak lokal baru saja mendeteksi kemungkinan penghalang besar di jalur jalan pengguna (data ini SUDAH TERUKUR, bukan tebakan):
                $objectLines

                Gambar terlampir adalah pandangan kamera pengguna saat ini -- gunakan untuk mengonfirmasi atau melengkapi jenis objeknya jika membantu.

                Tugasmu: berikan SATU kalimat peringatan singkat, bernada netral dan tenang (bukan panik atau berlebihan), tentang objek besar yang mungkin menghalangi jalan pengguna. Jika jarak objek DIKETAHUI (lihat data sensor di atas), SEBUTKAN jaraknya secara alami dalam kalimat (mis. "sekitar dua meter"), contoh: "Perhatian: ada kursi di depan Anda sekitar dua meter. Silakan pelan-pelan melewatinya." Jika jarak TIDAK bisa dipastikan, gunakan frasa seperti "ada objek di depan, mungkin kursi" tanpa menyebut angka jarak -- jangan memastikan jarak atau jenis objek yang sebenarnya tidak yakin. JANGAN memberi instruksi berjalan yang pasti (mis. "belok kiri sekarang", "mundur tiga langkah") -- cukup peringatan naratif. Jangan mengarang objek yang tidak ada di data atau gambar di atas.
            """.trimIndent()

            AnnouncementLanguage.ENGLISH -> """
                You are NAVI, a navigation assistant for a blind/low-vision user. A local distance sensor just detected a possibly large obstacle in the user's walking path (this data is MEASURED, not guessed):
                $objectLines

                The attached image is the user's current camera view -- use it to confirm or refine the object type if it helps.

                Your task: give ONE short warning sentence, neutral and calm in tone (not alarming or exaggerated), about the large object that may be blocking the user's path. If the object's distance IS known (see the sensor data above), INCLUDE it naturally in the sentence (e.g. "about two meters"), for example: "Caution: there's a chair about two meters ahead. Please walk slowly past it." If distance can't be pinned down, use phrasing like "there's an object ahead, possibly a chair" without stating a distance -- don't assert a distance or object type you aren't actually sure of. DO NOT give definite walking instructions (e.g. "turn left now", "step back three paces") -- just a narrative caution. Don't invent objects that aren't in the data or image above.
            """.trimIndent()
        }
    }

    private fun describeObject(obj: DetectedObjectSummary, language: AnnouncementLanguage): String {
        val distance = obj.distanceMeters?.let { "%.1f".format(it) }
        val confidencePct = (obj.confidence * 100).toInt()
        return when (language) {
            AnnouncementLanguage.INDONESIAN -> buildString {
                append("- ${obj.label}")
                if (distance != null) append(", ~${distance}m") else append(", jarak tidak pasti")
                append(", keyakinan deteksi ${confidencePct}%")
            }
            AnnouncementLanguage.ENGLISH -> buildString {
                append("- ${obj.label}")
                if (distance != null) append(", ~${distance}m away") else append(", distance uncertain")
                append(", detection confidence ${confidencePct}%")
            }
        }
    }
}
