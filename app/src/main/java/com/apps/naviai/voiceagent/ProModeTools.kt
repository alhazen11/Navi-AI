package com.apps.naviai.voiceagent

import com.apps.naviai.audio.AnnouncementLanguage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the `tools` array sent in the Voice Agent API's `session.update`
 * message (see [VoiceAgentClient]'s class doc for the exact wire shape,
 * verified against AssemblyAI's Voice Agent API docs as of this writing).
 *
 * This is Pro Mode's entire "tool-calling dispatch" layer: instead of
 * NAVI AI's regular keyword/regex matchers (`VoiceCommandParser` +
 * `RouteRecordingMatcher`/`NavigationCommandMatcher`/`ObjectSearchMatcher`/
 * `MemoryCommandMatcher`/...), the Voice Agent's own LLM reads each tool's
 * `description` and decides which one (if any) matches the user's free-form
 * sentence -- see `ProModeViewModel` for what each tool actually does when
 * invoked.
 *
 * **Natural confirmation**: [clearAllMemoriesTool] and [deleteRouteTool]
 * (destructive, irreversible actions) take an explicit `confirmed` boolean
 * parameter rather than relying purely on the system prompt's wording. The
 * agent's own LLM still does the actual natural-language understanding --
 * "iya boleh", "jangan deh", "lanjutkan saja", not just a literal "ya"/"yes"
 * (something the old keyword-based `MemoryManager.isAffirmative()` could
 * never do) -- but it must resolve that understanding into an explicit
 * `true`/`false` before the tool executes, and
 * [com.apps.naviai.ui.viewmodel.ProModeViewModel]'s handlers refuse to
 * delete anything when `confirmed` isn't literally `true`. This is a code-
 * level safety net on top of the prompt instruction, not a replacement for
 * it -- see [com.apps.naviai.ui.viewmodel.ProModeViewModel.bilingualSystemPrompt]
 * for the corresponding system-prompt wording that tells the agent to ask
 * for confirmation before ever calling either tool.
 */
object ProModeTools {

    fun build(language: AnnouncementLanguage): JSONArray {
        val idOrEn = language == AnnouncementLanguage.INDONESIAN
        return JSONArray().apply {
            put(tool("describe_surroundings", if (idOrEn) "Jelaskan apa yang ada di sekitar pengguna saat ini berdasarkan kamera. Panggil ini saat pengguna bertanya tentang lingkungannya secara umum, mis. \"apa yang ada di depan saya?\", \"jelaskan ruangan ini\"." else "Describe what is currently around the user based on the camera. Call this when the user asks about their surroundings generally, e.g. \"what's in front of me?\", \"describe this room\"."))
            put(tool("read_text", if (idOrEn) "Membacakan tulisan yang terlihat di kamera saat ini, mis. label, papan nama, kartu nama." else "Read aloud any text currently visible in the camera, e.g. a label, sign, or business card."))
            put(
                tool(
                    "search_object",
                    if (idOrEn) "Mencari apakah suatu benda/objek terlihat di kamera saat ini dan di posisi mana." else "Search whether a specific object is currently visible in the camera and where.",
                    stringParam("query", if (idOrEn) "Nama benda yang dicari, mis. \"kursi\", \"pintu\"." else "The name of the object to search for, e.g. \"chair\", \"door\".")
                )
            )
            put(
                tool(
                    "save_memory",
                    if (idOrEn) "Menyimpan satu informasi yang diminta pengguna untuk diingat, mis. \"rumah saya di Depok\"." else "Save one piece of information the user asked to be remembered, e.g. \"my house is in Depok\".",
                    stringParam("content", if (idOrEn) "Isi informasi yang akan disimpan, dalam kata-kata pengguna." else "The content to remember, in the user's own words.")
                )
            )
            put(
                tool(
                    "recall_memory",
                    if (idOrEn) "Mencari sesuatu yang pernah diminta pengguna untuk diingat sebelumnya, mis. \"di mana rumah saya?\"." else "Recall something the user previously asked to be remembered, e.g. \"where do I live?\".",
                    stringParam("query", if (idOrEn) "Apa yang ingin diingat kembali oleh pengguna." else "What the user wants recalled.")
                )
            )
            put(
                tool(
                    "forget_memory",
                    if (idOrEn) "Menghapus satu informasi tersimpan yang diminta pengguna untuk dilupakan." else "Delete one saved memory the user asked to forget.",
                    stringParam("query", if (idOrEn) "Informasi mana yang akan dilupakan." else "Which information to forget.")
                )
            )
            put(clearAllMemoriesTool(idOrEn))
            put(tool("start_route_recording", if (idOrEn) "Mulai merekam rute jalan kaki yang sedang ditempuh pengguna." else "Start recording the walking route the user is currently taking."))
            put(
                tool(
                    "stop_route_recording",
                    if (idOrEn) "Berhenti merekam rute yang sedang direkam dan menyimpannya dengan nama tertentu. Tanyakan nama rutenya dulu ke pengguna sebelum memanggil ini jika belum disebutkan." else "Stop recording the currently active route and save it under a name. Ask the user for a route name first if they haven't given one.",
                    stringParam("name", if (idOrEn) "Nama untuk menyimpan rute ini." else "The name to save this route under.")
                )
            )
            put(
                tool(
                    "start_navigation",
                    if (idOrEn) "Mulai navigasi suara langkah demi langkah menyusuri rute yang sudah pernah disimpan sebelumnya." else "Start turn-by-turn voice navigation along a previously saved route.",
                    stringParam("route_name", if (idOrEn) "Nama rute tersimpan yang ingin dinavigasi." else "The name of the saved route to navigate.")
                )
            )
            put(tool("stop_navigation", if (idOrEn) "Menghentikan navigasi rute yang sedang berjalan." else "Stop the currently active route navigation."))
            put(tool("list_saved_routes", if (idOrEn) "Menyebutkan daftar semua rute yang sudah pernah disimpan pengguna." else "List all routes the user has previously saved."))
            put(
                tool(
                    "rename_route",
                    if (idOrEn) "Mengganti nama rute tersimpan yang sudah ada." else "Rename an existing saved route.",
                    stringParam("old_name", if (idOrEn) "Nama rute saat ini." else "The route's current name."),
                    stringParam("new_name", if (idOrEn) "Nama baru untuk rute tersebut." else "The new name for that route.")
                )
            )
            put(deleteRouteTool(idOrEn))
            put(tool("list_all_memories", if (idOrEn) "Menyebutkan semua informasi yang pernah diminta pengguna untuk diingat." else "List everything the user has previously asked to be remembered."))
        }
    }

    /**
     * Wording every destructive tool's confirmation instruction shares --
     * kept in one place so `clear_all_memories` and `delete_route` (and any
     * future destructive tool) ask for/interpret confirmation the same way.
     */
    private fun confirmedParamDescription(language: AnnouncementLanguage) = when (language) {
        AnnouncementLanguage.INDONESIAN -> "true HANYA jika pengguna baru saja mengonfirmasi dengan jelas setelah kamu bertanya \"apakah Anda yakin?\" (mis. \"iya boleh\", \"lanjutkan saja\", \"ya\") -- false jika pengguna menolak (mis. \"jangan deh\", \"tidak usah\", \"batal\") atau jawabannya tidak jelas. Jangan pernah mengirim true tanpa benar-benar bertanya dan mendapat konfirmasi terlebih dahulu."
        AnnouncementLanguage.ENGLISH -> "true ONLY if the user just clearly confirmed after you asked \"are you sure?\" (e.g. \"yeah go ahead\", \"sure\", \"yes\") -- false if they declined (e.g. \"nah\", \"never mind\", \"cancel\") or the answer was unclear. Never send true without actually asking and getting confirmation first."
    }

    private fun clearAllMemoriesTool(idOrEn: Boolean): JSONObject {
        val language = if (idOrEn) AnnouncementLanguage.INDONESIAN else AnnouncementLanguage.ENGLISH
        return tool(
            "clear_all_memories",
            if (idOrEn) "Menghapus SEMUA ingatan tersimpan -- aksi permanen. Selalu tanya \"apakah Anda yakin?\" dulu sebelum memanggil ini." else "Delete ALL saved memories -- a permanent action. Always ask \"are you sure?\" before calling this.",
            boolParam("confirmed", confirmedParamDescription(language))
        )
    }

    private fun deleteRouteTool(idOrEn: Boolean): JSONObject {
        val language = if (idOrEn) AnnouncementLanguage.INDONESIAN else AnnouncementLanguage.ENGLISH
        return tool(
            "delete_route",
            if (idOrEn) "Menghapus satu rute tersimpan secara permanen. Selalu tanya \"apakah Anda yakin?\" dulu sebelum memanggil ini." else "Permanently delete one saved route. Always ask \"are you sure?\" before calling this.",
            stringParam("name", if (idOrEn) "Nama rute yang akan dihapus." else "The name of the route to delete."),
            boolParam("confirmed", confirmedParamDescription(language))
        )
    }

    private data class ToolParam(val name: String, val jsonType: String, val description: String)

    private fun stringParam(name: String, description: String) = ToolParam(name, "string", description)
    private fun boolParam(name: String, description: String) = ToolParam(name, "boolean", description)

    private fun tool(name: String, description: String, vararg params: ToolParam): JSONObject {
        val properties = JSONObject()
        val required = JSONArray()
        for (param in params) {
            properties.put(param.name, JSONObject().put("type", param.jsonType).put("description", param.description))
            required.put(param.name)
        }
        return JSONObject().apply {
            put("type", "function")
            put("name", name)
            put("description", description)
            put(
                "parameters",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", properties)
                    put("required", required)
                }
            )
            // "interactive" (vs "hold") is what makes the agent automatically
            // continue and speak a reply once it receives this tool's
            // tool.result -- without it, a tool call could leave the agent
            // waiting for an explicit resume signal this app never sends,
            // which looks exactly like "the agent never replies".
            put("execution_mode", "interactive")
        }
    }
}
