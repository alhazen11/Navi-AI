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

                Gambar terlampir adalah pandangan kamera pengguna saat ini. Sensor jarak lokal juga mendeteksi objek berikut di frame ini (data ini SUDAH TERUKUR, bukan tebakan, termasuk posisi horizontalnya) -- tapi sensor ini TIDAK mendeteksi semua jenis benda (mis. kabel/kawat tipis biasanya tidak akan muncul di sini walau ada di gambar), jadi daftar kosong atau tidak menyebut "$query" BUKAN bukti bahwa objeknya tidak ada:
                $objectLines

                Tugasmu, dalam urutan ini:
                1. Nilai KUALITAS GAMBAR SECARA KESELURUHAN saja -- bukan berdasarkan mudah/tidaknya "$query" terlihat. Hanya jika gambar SECARA KESELURUHAN gelap total, sangat buram di semua bagian, atau rusak sehingga hampir tidak ada apa pun di gambar yang bisa dikenali -- JANGAN menjawab ada/tidaknya objek. Katakan gambarnya kurang jelas dan minta pengguna mengambil gambar ulang yang lebih stabil dan jelas.
                2. Jika gambar secara umum masih bisa dianalisis, PINDAI SELURUH gambar secara sistematis sebelum menyimpulkan apa pun -- jangan hanya sekilas melihat bagian tengah. Periksa berurutan: area dekat (lantai/bawah), area jauh, sisi kiri, tengah, dan sisi kanan, termasuk bagian yang terpotong di tepi frame. Pertimbangkan juga nama/istilah lain yang mengacu ke benda yang sama dengan "$query" (mis. jika mencari "kabel": kawat, kabel charger, kabel listrik, kabel USB, senar, tali kecil yang menyerupai kabel -- sesuaikan dengan jenis objeknya), dan tetap perhatikan benda berukuran kecil, tipis, sebagian tertutup benda lain, atau warnanya mirip latar belakang. Cek juga daftar sensor di atas (termasuk label yang mirip/sinonim), tapi jangan hanya mengandalkan sensor untuk benda tipis seperti itu.
                   - Jika DITEMUKAN (termasuk jika kamu cukup yakin walau tidak 100% pasti): sebutkan dengan jelas bahwa objeknya ada, sebutkan lokasi relatifnya (kiri/tengah/kanan). Jika yakin tapi tidak sepenuhnya pasti, boleh gunakan kata seperti "sepertinya" atau "kemungkinan" -- itu tetap jawaban DITEMUKAN, bukan "tidak jelas". Satu kalimat singkat.
                   - Jika setelah pemindaian menyeluruh objeknya benar-benar TIDAK terlihat sama sekali: katakan dengan jelas dan singkat, mis. "Saya tidak melihat $query di frame ini." Ini adalah jawaban yang valid dan berbeda dari poin 1 -- JANGAN jawab "gambar kurang jelas" hanya karena objek yang dicari kecil/tipis dan sulit ditemukan; itu bukan alasan yang sah untuk poin 1.
                3. JANGAN mengarang objek yang sebenarnya tidak terlihat di gambar atau di daftar sensor -- tapi condonglah untuk memberi jawaban DITEMUKAN dengan sedikit keraguan ("sepertinya ada") daripada langsung menyerah ke "tidak terlihat"/"kurang jelas" begitu ada indikasi objeknya mungkin ada. Jawab singkat (1 kalimat) dalam Bahasa Indonesia.
            """.trimIndent()

            AnnouncementLanguage.ENGLISH -> """
                You are NAVI, a navigation assistant for a blind/low-vision user. The user is searching for the following object: "$query".

                The attached image is the user's current camera view. A local distance sensor also detected the following objects in this frame (this data is MEASURED, not guessed, including horizontal position) -- but this sensor does NOT detect every kind of object (e.g. a thin cable/wire usually won't show up here even if it's in the image), so an empty or non-matching list is NOT proof the object isn't there:
                $objectLines

                Your task, in this order:
                1. Judge the image's OVERALL quality only -- not based on how easy "$query" specifically is to spot. Only if the image as a whole is completely dark, badly blurred throughout, or corrupted such that almost nothing in it can be made out at all -- DO NOT answer whether the object is present or not. Say the image isn't clear enough and ask the user to take a steadier, clearer picture.
                2. If the image is generally analyzable, SCAN THE WHOLE IMAGE systematically before concluding anything -- don't just glance at the center. Check in order: near/floor area, far area, left side, center, and right side, including anything cut off at the frame's edges. Also consider other names/terms that could refer to the same thing as "$query" (e.g. if searching for "cable": wire, cord, charger cable, power cable, USB cable, or a thin string-like object that resembles one -- adapt to the kind of object), and keep an eye out for anything small, thin, partially occluded, or similar in color to the background. Also check the sensor list above (including close synonyms), but don't rely on the sensor alone for a thin object like that.
                   - If FOUND (including if you're reasonably confident but not 100% certain): clearly state it's present, and state its relative location (left/center/right). If confident but not fully certain, it's fine to hedge with "it looks like" or "possibly" -- that still counts as FOUND, not "unclear". One short sentence.
                   - If, after a thorough scan, it's genuinely NOT visible anywhere: clearly and briefly say so, e.g. "I don't see $query in this frame." This is a valid answer, different from step 1 -- DO NOT answer "the image isn't clear enough" just because the searched-for object is small/thin and hard to spot; that isn't a valid reason for step 1.
                3. DO NOT invent objects that aren't actually visible in the image or listed above -- but lean toward a hedged FOUND ("it looks like there's...") over immediately giving up with "not visible"/"unclear" whenever there's some indication the object might be there. Answer briefly (1 sentence) in English.
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
