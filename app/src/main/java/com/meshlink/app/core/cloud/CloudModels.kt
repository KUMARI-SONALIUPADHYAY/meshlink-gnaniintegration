package com.meshlink.app.core.cloud

data class CloudUser(val id: String, val email: String, val displayName: String, val role: String)
data class CloudIncident(
    val id: String,
    val messageId: String,
    val category: String,
    val severity: Int,
    val description: String,
    val latitude: Double?,
    val longitude: Double?,
    val affectedCount: Int?,
    val assistance: String?,
    val status: String,
    val verification: String,
    val version: Int,
    val reportedAt: Long,
    val updatedAt: Long,
    val reporterId: String,
    val reporterName: String,
    val reporterRole: String,
    val mediaId: String?
)
data class CoordinationSummary(
    val records: Int = 0,
    val incidentReports: Int = 0,
    val peopleReported: Int = 0,
    val children: Int = 0,
    val elderly: Int = 0,
    val injured: Int = 0,
    val disabilitySupport: Int = 0,
    val pregnant: Int = 0,
    val volunteersAvailable: Int = 0,
    val foodKits: Int = 0,
    val waterLitres: Int = 0,
    val firstAidKits: Int = 0,
    val blankets: Int = 0,
    val shelterSpaces: Int = 0,
    val note: String = ""
)
data class CloudDashboardState(
    val user: CloudUser? = null,
    val incidents: List<CloudIncident> = emptyList(),
    val pendingSync: Int = 0,
    val busy: Boolean = false,
    val message: String = "",
    val error: Boolean = false,
    val nearbyOnly: Boolean = false,
    val shareSos: Boolean = false,
    val coordinationSummary: CoordinationSummary? = null
)
