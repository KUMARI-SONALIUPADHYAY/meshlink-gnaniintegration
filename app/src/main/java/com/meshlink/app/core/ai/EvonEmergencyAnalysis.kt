package com.meshlink.app.core.ai

import org.json.JSONArray
import org.json.JSONObject

/** Strictly parsed Evon output. AI suggestions are advisory and must be reviewed by a person. */
data class EvonEmergencyAnalysis(
    val emergencyType: String,
    val severity: String,
    val locationMentioned: String?,
    val peopleAffected: Int?,
    val immediateAssistance: String,
    val instructions: List<String>,
    val warnings: List<String>,
    val suggestedResponse: String,
    val rawJson: String
) {
    companion object {
        private val allowedSeverity = setOf("unknown", "low", "moderate", "high", "critical")

        fun parse(raw: String): EvonEmergencyAnalysis {
            val normalized = raw.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            val start = normalized.indexOf('{')
            val end = normalized.lastIndexOf('}')
            require(start >= 0 && end > start) { "Evon did not return a JSON object." }
            val json = JSONObject(normalized.substring(start, end + 1))
            val emergencyType = json.optString("emergency_type").trim()
            val severity = json.optString("severity", "unknown").lowercase()
            val immediate = json.optString("immediate_assistance").trim()
            val response = json.optString("suggested_response").trim()
            require(emergencyType.isNotBlank() && emergencyType.length <= 120) { "Evon emergency type is missing or invalid." }
            require(severity in allowedSeverity) { "Evon returned an unsupported severity value." }
            require(immediate.length <= 1_000 && response.isNotBlank() && response.length <= 2_000) { "Evon suggested response is missing or exceeded the safe display limit." }
            val location = json.optString("location", "").trim().takeIf { it.isNotBlank() && it != "null" }?.take(240)
            val people = if (json.isNull("people_affected") || !json.has("people_affected")) null else json.optInt("people_affected", -1).takeIf { it in 0..100_000 }
            return EvonEmergencyAnalysis(
                emergencyType = emergencyType,
                severity = severity,
                locationMentioned = location,
                peopleAffected = people,
                immediateAssistance = immediate.ifBlank { "Not specified in the transcription." },
                instructions = readStringArray(json.optJSONArray("instructions"), 8, 500),
                warnings = readStringArray(json.optJSONArray("warnings"), 8, 500),
                suggestedResponse = response,
                rawJson = json.toString()
            )
        }

        private fun readStringArray(array: JSONArray?, maxItems: Int, maxLength: Int): List<String> {
            if (array == null) return emptyList()
            return (0 until minOf(array.length(), maxItems)).mapNotNull { index ->
                array.optString(index).trim().takeIf { it.isNotBlank() }?.take(maxLength)
            }
        }
    }
}
