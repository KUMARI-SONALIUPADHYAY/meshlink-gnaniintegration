package com.meshlink.app.ui.cloud

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meshlink.app.core.cloud.CloudDashboardState
import com.meshlink.app.core.cloud.CloudIncident
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CloudDashboard(
    state: CloudDashboardState,
    onlineMode: Boolean,
    internetAvailable: Boolean,
    configured: Boolean,
    onAuthenticate: (String, String, String, Boolean) -> Unit,
    onLogout: () -> Unit,
    onRefresh: (Boolean) -> Unit,
    onSubmitReport: (String, Int, String, Int?, String, Boolean, Uri?) -> Unit,
    onPickImage: () -> Unit,
    selectedImageName: String,
    selectedImageUri: Uri?,
    onStatus: (CloudIncident, String) -> Unit,
    onVerification: (CloudIncident, String) -> Unit,
    onDispute: (CloudIncident, String) -> Unit,
    onSync: () -> Unit,
    onShareSosConsent: (Boolean) -> Unit,
    onOpenMap: (Double, Double) -> Unit,
    onViewMedia: (String) -> Unit
) {
    var register by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("OTHER") }
    var severity by remember { mutableFloatStateOf(2f) }
    var description by remember { mutableStateOf("") }
    var affected by remember { mutableStateOf("") }
    var assistance by remember { mutableStateOf("") }
    var locationConsent by remember { mutableStateOf(false) }
    var shareConsent by remember { mutableStateOf(false) }
    var nearby by remember(state.nearbyOnly) { mutableStateOf(state.nearbyOnly) }
    var nearbyLocationConsent by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (internetAvailable && configured) Icons.Default.Cloud else Icons.Default.CloudOff, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Emergency coordination", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(when { !onlineMode -> "Offline Mode · cloud operations are queued"; !internetAvailable -> "Internet unavailable · local queue retained"; !configured -> "Cloud backend not configured"; else -> "Online incident dashboard" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { onRefresh(nearby) }) { Icon(Icons.Default.Refresh, "Refresh incidents") }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Synchronization", fontWeight = FontWeight.SemiBold)
                    Text("${state.pendingSync} events waiting to synchronize", style = MaterialTheme.typography.bodyMedium)
                    if (state.pendingSync > 0) Button(onClick = onSync, enabled = onlineMode && internetAvailable && configured && state.user != null && !state.busy) { Text("Sync pending events") }
                    Text("BLE messaging and SOS work independently of cloud connectivity.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = state.shareSos, onCheckedChange = onShareSosConsent)
                        Text("Allow future SOS summaries to sync to my cloud account", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        state.coordinationSummary?.let { summary ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("SahayakNet impact & resource overview", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("${summary.records} structured reports · ${summary.incidentReports} incident reports", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SummaryTile("People reported", summary.peopleReported.toString(), Modifier.weight(1f))
                            SummaryTile("Injured", summary.injured.toString(), Modifier.weight(1f))
                            SummaryTile("Volunteers", summary.volunteersAvailable.toString(), Modifier.weight(1f))
                        }
                        Text("Children ${summary.children} · Elderly ${summary.elderly} · Disability support ${summary.disabilitySupport} · Pregnant ${summary.pregnant}", style = MaterialTheme.typography.bodySmall)
                        Text("Reported demand: food ${summary.foodKits} kits · water ${summary.waterLitres} L · first aid ${summary.firstAidKits} kits · blankets ${summary.blankets} · shelter ${summary.shelterSpaces}", style = MaterialTheme.typography.bodySmall)
                        Text(summary.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (!configured) item { Text("Set cloud.api.base.url to your deployed HTTPS backend in local.properties. See backend/README.md.", color = MaterialTheme.colorScheme.error) }
        if (state.user == null) {
            item {
                Card {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (register) "Create community account" else "Sign in", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(email, { email = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        if (register) OutlinedTextField(name, { name = it }, label = { Text("Display name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(password, { password = it }, label = { Text("Password (12+ characters)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Button(onClick = { onAuthenticate(email, password, name, register); password = "" }, enabled = configured && internetAvailable && onlineMode && !state.busy && email.isNotBlank() && password.isNotBlank() && (!register || name.isNotBlank()), modifier = Modifier.fillMaxWidth()) { Text(if (register) "Create account" else "Sign in") }
                        TextButton(onClick = { register = !register }) { Text(if (register) "Already registered? Sign in" else "New community member? Create account") }
                        Text("New accounts receive the Community role. Responder privileges must be provisioned by the backend administrator.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            item {
                Card {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(state.user.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("${state.user.email} · ${state.user.role}", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onLogout) { Text("Sign out") }
                    }
                }
            }
        }
        item {
            Card {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.ReportProblem, null, tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(8.dp)); Text("Submit emergency report", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                    Text("Reports are stored locally first. They upload only after you consent and sign in; a queued report is not a dispatch request.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    var categoryExpanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { categoryExpanded = true }, modifier = Modifier.fillMaxWidth()) { Text("Emergency category: ${category.replace('_',' ')}") }
                        DropdownMenu(expanded = categoryExpanded, onDismissRequest = { categoryExpanded = false }) {
                            listOf("MEDICAL","FIRE","FLOOD","ROAD_HAZARD","SECURITY","EARTHQUAKE","OTHER").forEach { item -> DropdownMenuItem(text = { Text(item.replace('_',' ')) }, onClick = { category = item; categoryExpanded = false }) }
                        }
                    }
                    Text("Severity: ${severity.toInt()} / 5", style = MaterialTheme.typography.labelLarge)
                    Slider(value = severity, onValueChange = { severity = it }, valueRange = 1f..5f, steps = 3)
                    OutlinedTextField(description, { description = it }, label = { Text("What happened?") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                    OutlinedTextField(affected, { affected = it.filter(Char::isDigit).take(6) }, label = { Text("People affected (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(assistance, { assistance = it }, label = { Text("Assistance needed (optional)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = onPickImage, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Image, null); Spacer(Modifier.width(8.dp)); Text(if (selectedImageName.isBlank()) "Attach photo (optional, max 5 MB)" else selectedImageName) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(locationConsent, { locationConsent = it }); Text("Share my current GPS location (optional)", style = MaterialTheme.typography.bodySmall) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(shareConsent, { shareConsent = it }); Text("I consent to upload this report and selected photo when signed in and online.", style = MaterialTheme.typography.bodySmall) }
                    Button(onClick = { onSubmitReport(category, severity.toInt(), description, affected.toIntOrNull(), assistance, locationConsent, selectedImageUri); shareConsent = false }, enabled = description.trim().length >= 5 && shareConsent && !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Save report and queue upload") }
                    Text("Photo selection is handled by the device photo picker. GPS is included only when explicitly enabled and permission is granted.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Reported incidents", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                FilterChip(selected = nearby, onClick = { if (!nearbyLocationConsent) { nearby = false; onRefresh(false) } else { nearby = !nearby; onRefresh(!nearby) } }, label = { Text("Nearby") })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(nearbyLocationConsent, { nearbyLocationConsent = it; if (!it && nearby) { nearby = false; onRefresh(false) } })
                Text("Allow a one-time location query to filter reports within 30 km", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (state.incidents.isEmpty()) item { Text("No online incidents loaded. Refresh when connected, or continue using the local mesh.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(state.incidents, key = { it.id }) { incident ->
            Card {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(incident.category.replace('_',' '), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        AssistChip(onClick = {}, label = { Text(incident.status.replace('_',' ')) })
                    }
                    Text("Severity ${incident.severity}/5 · ${incident.verification}", style = MaterialTheme.typography.labelMedium)
                    Text(incident.description, style = MaterialTheme.typography.bodyMedium)
                    Text("Reported ${SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(incident.reportedAt))} · ${incident.reporterName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (incident.latitude != null && incident.longitude != null) {
                        Text("Location: ${"%.5f".format(Locale.US,incident.latitude)}, ${"%.5f".format(Locale.US,incident.longitude)}", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { onOpenMap(incident.latitude, incident.longitude) }) { Text("Open incident location in map") }
                    }
                    Text("People affected: ${incident.affectedCount?.toString() ?: "Unknown"} · Assistance: ${incident.assistance ?: "Not specified"}", style = MaterialTheme.typography.bodySmall)
                    incident.mediaId?.let { mediaId ->
                        if (state.user?.id == incident.reporterId || state.user?.role == "RESPONDER" || state.user?.role == "ADMIN") {
                            OutlinedButton(onClick = { onViewMedia(mediaId) }) { Icon(Icons.Default.Image, null); Spacer(Modifier.width(6.dp)); Text("View attached evidence photo") }
                        } else Text("Private evidence photo attached", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (incident.verification != "VERIFIED") Text("Unverified community report. AI assessments are advisory, not confirmed facts.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    if (state.user?.role == "RESPONDER" || state.user?.role == "ADMIN") {
                        var expanded by remember(incident.id) { mutableStateOf(false) }
                        var nextStatus by remember(incident.id) { mutableStateOf(incident.status) }
                        Box {
                            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("Status: ${nextStatus.replace('_',' ')}") }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                listOf("NEW","ACKNOWLEDGED","IN_PROGRESS","RESOLVED").forEach { status -> DropdownMenuItem(text = { Text(status.replace('_',' ')) }, onClick = { nextStatus = status; expanded = false }) }
                            }
                        }
                        Button(onClick = { onStatus(incident,nextStatus) }, enabled = nextStatus != incident.status) { Text("Save status") }
                        OutlinedButton(onClick = { onVerification(incident,"VERIFIED") }, enabled = incident.verification != "VERIFIED") { Text("Verify report") }
                    } else {
                        var reason by remember(incident.id) { mutableStateOf("") }
                        OutlinedTextField(reason, { reason = it }, label = { Text("Dispute reason") }, modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = { if (reason.trim().length >= 5) onDispute(incident,reason) }, enabled = reason.trim().length >= 5) { Text("Dispute report") }
                    }
                }
            }
        }
        if (state.message.isNotBlank()) item { Text(state.message, color = if (state.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
        if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun SummaryTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(8.dp)) { Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(label, style = MaterialTheme.typography.labelSmall) }
    }
}
