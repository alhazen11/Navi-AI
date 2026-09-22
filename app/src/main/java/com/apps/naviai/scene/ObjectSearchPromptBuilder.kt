package com.apps.naviai.scene

import com.apps.naviai.audio.AnnouncementLanguage

/**
 * Builds the text prompt sent alongside the camera frame to the vision LLM
 * for the Object/Landmark Search feature (acceptance criteria: state
 * presence + relative left/center/right location when found; state clearly
 * when not found; refuse to answer and ask for a clearer/steadier shot
 * instead of guessing when the image is too blurry). Pure function, no
 * network -- see [ScenePromptBuilder] for the same rationale.
 */
object ObjectSearchPromptBuilder {
    fun build(query: String, objects: List<DetectedObjectSummary>, language: AnnouncementLanguage): String {
        val objectLines = if (objects.isEmpty()) noObjectsLine(language) else objects.joinToString("\n") { describeObject(it, language) }

        return when (language) {
            AnnouncementLanguage.INDONESIAN -> """
                Kamu adalah NAVI, asisten navigasi untuk pengguna tunanetra/low-vision. Pengguna sedang mencari objek berikut: "$query".

                Gambar terlampir adalah pandangan kamera pengguna saat ini. Sensor jarak lokal juga mendeteksi objek berikut di frame ini (data ini SUDAH TERUKUR, bukan tebakan, termasuk posisi horizontalnya):
                $objectLines

                Tugasmu, dalam urutan ini:
                1. Jika gambar terlalu buram, gelap, atau goyang untuk dipastikan isinya -- JANGAN menjawab ada/tidaknya objek. Katakan gambarnya kurang jelas dan minta pengguna mengambil gambar ulang yang lebih stabil dan jelas.
                2. Jika gambar cukup jelas: periksa apakah "$query" benar-benar terlihat di gambar ATAU ada di daftar sensor di atas (termasuk label yang mirip/sinonim).
                   - Jika DITEMUKAN: sebutkan dengan jelas bahwa objeknya ada, sebutkan lokasi relatifnya (kiri/tengah/kanan), dalam satu kalimat singkat.
                   - Jika TIDAK ditemukan: katakan dengan jelas dan singkat, mis. "Saya tidak melihat $query di frame ini."
                3. JANGAN mengarang objek yang sebenarnya tidak terlihat di gambar atau di daftar sensor. Jawab singkat (1 kalimat) dalam Bahasa Indonesia.
            """.trimIndent()

            AnnouncementLanguage.ENGLISH -> """
                You are NAVI, a navigation assistant for a blind/low-vision user. The user is searching for the following object: "$query".

                The attached image is the user's current camera view. A local distance sensor also detected the following objects in this frame (this data is MEASURED, not guessed, including horizontal position):
                $objectLines

                Your task, in this order:
                1. If the image is too blurry, dark, or shaky to be sure of its contents -- DO NOT answer whether the object is present or not. Say the image isn't clear enough and ask the user to take a steadier, clearer picture.
                2. If the image is clear enough: check whether "$query" is actually visible in the image OR listed above (including close synonyms).
                   - If FOUND: clearly state it's present, and state its relative location (left/center/right), in one short sentence.
                   - If NOT found: clearly and briefly say so, e.g. "I don't see $query in this frame."
                3. DO NOT invent objects that aren't actually visible in the image or listed above. Answer briefly (1 sentence) in English.
            """.trimIndent()
        }
    }

    private fun describeObject(obj: DetectedObjectSummary, language: AnnouncementLanguage): String {
        val distance = obj.distanceMeters?.let { "%.1f".format(it) }
        val confidencePct = (obj.confidence * 100).toInt()
        val position = obj.horizontalPosition?.let { positionLabel(it, language) }
        return when (language) {
            AnnouncementLanguage.INDONESIAN -> buildString {
                append("- ${obj.label}")
                if (position != null) append(", posisi: $position")
                if (distance != null) append(", ~${distance}m")
                append(", keyakinan deteksi ${confidencePct}%")
            }
            AnnouncementLanguage.ENGLISH -> buildString {
                append("- ${obj.label}")
                if (position != null) append(", position: $position")
                if (distance != null) append(", ~${distance}m away")
                append(", detection confidence ${confidencePct}%")
            }
        }
    }

    private fun positionLabel(position: HorizontalPosition, language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> when (position) {
            HorizontalPosition.LEFT -> "kiri"
            HorizontalPosition.CENTER -> "tengah"
            HorizontalPosition.RIGHT -> "kanan"
        }
        AnnouncementLanguage.ENGLISH -> when (position) {
            HorizontalPosition.LEFT -> "left"
            HorizontalPosition.CENTER -> "center"
            HorizontalPosition.RIGHT -> "right"
        }
    }

    private fun noObjectsLine(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "(Tidak ada objek yang terdeteksi sensor saat ini.)"
        AnnouncementLanguage.ENGLISH -> "(No objects currently detected by the sensor.)"
    }
}
