package com.meshlink.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.util.UUID

/** Structured citizen/responder reporting workspace. Submission is explicit; no fake delivery state is shown. */
@Composable
internal fun DisasterResponseWorkspace(
    connectedPeers: Int,
    online: Boolean,
    onSubmit: (String, String, Boolean) -> Unit,
    onOpenReports: () -> Unit,
    onOpenVoiceTools: () -> Unit
) {
    var kind by rememberSaveable { mutableStateOf("INCIDENT") }
    var category by rememberSaveable { mutableStateOf("FLOOD") }
    var severity by rememberSaveable { mutableIntStateOf(3) }
    var description by rememberSaveable { mutableStateOf("") }
    var people by rememberSaveable { mutableStateOf("") }
    var children by rememberSaveable { mutableStateOf("") }
    var elderly by rememberSaveable { mutableStateOf("") }
    var injured by rememberSaveable { mutableStateOf("") }
    var disability by rememberSaveable { mutableStateOf("") }
    var pregnant by rememberSaveable { mutableStateOf("") }
    var food by rememberSaveable { mutableStateOf("") }
    var water by rememberSaveable { mutableStateOf("") }
    var medicine by rememberSaveable { mutableStateOf("") }
    var blankets by rememberSaveable { mutableStateOf("") }
    var shelter by rememberSaveable { mutableStateOf("") }
    var volunteers by rememberSaveable { mutableStateOf("") }
    var skills by rememberSaveable { mutableStateOf("") }
    var safeZoneName by rememberSaveable { mutableStateOf("") }
    var safeCapacity by rememberSaveable { mutableStateOf("") }
    var safeAccess by rememberSaveable { mutableStateOf("") }
    var safeOpen by rememberSaveable { mutableStateOf(true) }
    var cloudConsent by rememberSaveable { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Disaster Response", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Create a structured field report for nearby MeshLink peers and, with your consent, the SahayakNet coordination backend.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Card(shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Bluetooth, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp)); Text("Connectivity", fontWeight = FontWeight.SemiBold) }
                    Text("Authenticated nearby peers: $connectedPeers", style = MaterialTheme.typography.titleMedium)
                    Text(if (connectedPeers > 0) "Reports can be sent to the currently connected mesh. Delivery still requires a recipient acknowledgement." else "No authenticated mesh peers right now. Your report can still be saved locally and queued for cloud sync if you opt in.", style = MaterialTheme.typography.bodySmall)
                    Text("Internet: ${if (online) "available" else "unavailable"} · BLE peer count is independent of internet.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Text("What are you reporting?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("INCIDENT" to "Incident", "PEOPLE" to "People").forEach { (v, label) ->
                        FilterChip(selected = kind == v, onClick = { kind = v }, label = { Text(label) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("RESOURCES" to "Supplies", "VOLUNTEER" to "Volunteer").forEach { (v, label) ->
                        FilterChip(selected = kind == v, onClick = { kind = v }, label = { Text(label) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = kind == "SAFE_ZONE", onClick = { kind = "SAFE_ZONE" }, label = { Text("Safe zone") })
                }
            }
        }
        if (kind == "INCIDENT") item {
            Card { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Incident details", fontWeight = FontWeight.SemiBold)
                var expanded by remember { mutableStateOf(false) }
                Box { OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("Category: ${category.replace('_',' ')}") }; DropdownMenu(expanded, { expanded = false }) { listOf("MEDICAL","FIRE","FLOOD","ROAD_HAZARD","SECURITY","EARTHQUAKE","OTHER").forEach { DropdownMenuItem(text = { Text(it.replace('_',' ')) }, onClick = { category = it; expanded = false }) } } }
                Text("Urgency: $severity / 5"); Slider(severity.toFloat(), { severity = it.toInt().coerceIn(1,5) }, valueRange = 1f..5f, steps = 3)
                OutlinedTextField(description, { description = it.take(1500) }, label = { Text("What happened? Include a landmark if possible") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            } }
        }
        if (kind == "PEOPLE" || kind == "INCIDENT") item {
            Card { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("People and vulnerability assessment", fontWeight = FontWeight.SemiBold)
                Text("Enter known counts only. Leave unknown values blank; do not estimate missing people from phone inactivity.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                NumberField("Total people affected", people, { people = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { NumberField("Children", children, { children = it }, Modifier.weight(1f)); NumberField("Elderly", elderly, { elderly = it }, Modifier.weight(1f)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { NumberField("Injured", injured, { injured = it }, Modifier.weight(1f)); NumberField("Disability support", disability, { disability = it }, Modifier.weight(1f)) }
                NumberField("Pregnant people", pregnant, { pregnant = it })
            } }
        }
        if (kind == "RESOURCES" || kind == "INCIDENT") item {
            Card { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Resource requirements", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { NumberField("Food kits", food, { food = it }, Modifier.weight(1f)); NumberField("Water litres", water, { water = it }, Modifier.weight(1f)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { NumberField("First-aid kits", medicine, { medicine = it }, Modifier.weight(1f)); NumberField("Blankets", blankets, { blankets = it }, Modifier.weight(1f)) }
                NumberField("Shelter spaces", shelter, { shelter = it })
            } }
        }
        if (kind == "VOLUNTEER") item {
            Card { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Volunteer availability", fontWeight = FontWeight.SemiBold)
                NumberField("Volunteers available", volunteers, { volunteers = it })
                OutlinedTextField(skills, { skills = it.take(500) }, label = { Text("Skills, vehicle and supplies available") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            } }
        }
        if (kind == "SAFE_ZONE") item {
            Card { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Safe zone / shelter information", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(safeZoneName, { safeZoneName = it.take(120) }, label = { Text("Place name and landmark") }, modifier = Modifier.fillMaxWidth())
                NumberField("Available capacity", safeCapacity, { safeCapacity = it })
                OutlinedTextField(safeAccess, { safeAccess = it.take(500) }, label = { Text("Accessibility, hazards and facilities") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(safeOpen, { safeOpen = it }); Spacer(Modifier.width(8.dp)); Text(if (safeOpen) "Reported open" else "Reported closed") }
                Text("Only publish a shelter as verified after an authorized person checks it.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
        }
        if (kind != "INCIDENT") item {
            Card { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Additional details", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(description, { description = it.take(1500) }, label = { Text("Location landmark / context / notes") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            } }
        }
        item {
            Card { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(cloudConsent, { cloudConsent = it }); Text("I consent to sync this structured report to the SahayakNet backend when signed in and online.", style = MaterialTheme.typography.bodySmall) }
                Button(onClick = {
                    val payload = JSONObject().apply {
                        put("schema_version", 1); put("record_type", kind); put("category", category); put("severity", severity)
                        put("description", description.trim()); put("people_affected", people.toIntOrNull())
                        put("children_count", children.toIntOrNull()); put("elderly_count", elderly.toIntOrNull())
                        put("injured_count", injured.toIntOrNull()); put("disability_support_count", disability.toIntOrNull())
                        put("pregnant_count", pregnant.toIntOrNull()); put("food_kits", food.toIntOrNull())
                        put("water_litres", water.toIntOrNull()); put("first_aid_kits", medicine.toIntOrNull())
                        put("blankets", blankets.toIntOrNull()); put("shelter_spaces", shelter.toIntOrNull())
                        put("volunteers_available", volunteers.toIntOrNull()); put("volunteer_skills", skills.trim())
                        put("safe_zone_name", safeZoneName.trim()); put("safe_zone_capacity", safeCapacity.toIntOrNull())
                        put("safe_zone_accessibility", safeAccess.trim()); put("safe_zone_open", safeOpen)
                        put("reported_at", System.currentTimeMillis())
                    }
                    onSubmit(kind, payload.toString(), cloudConsent); submitted = true
                }, enabled = description.trim().length >= 5 || kind == "PEOPLE" && people.toIntOrNull() != null || kind == "RESOURCES" && listOf(food,water,medicine,blankets,shelter).any { it.toIntOrNull() != null } || kind == "VOLUNTEER" && volunteers.toIntOrNull() != null || kind == "SAFE_ZONE" && safeZoneName.trim().length >= 3, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Send, null); Spacer(Modifier.width(8.dp)); Text("Submit ${kind.lowercase().replaceFirstChar { it.uppercase() }} report")
                }
                if (submitted) Text("Submission handed to MeshLink. Check the message queue and sync status for actual delivery/acknowledgement.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            } }
        }
        item { OutlinedButton(onClick = onOpenReports, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Assignment, null); Spacer(Modifier.width(8.dp)); Text("Open incident reports and command data") } }
        item { TextButton(onClick = onOpenVoiceTools, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Mic, null); Spacer(Modifier.width(8.dp)); Text("Open Gnani voice processing tools") } }
        item { Text("AI-generated classifications and resource estimates must be reviewed. Dispatch decisions remain with authorized responders.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun NumberField(label: String, value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(value, { raw -> onValue(raw.filter(Char::isDigit).take(6)) }, label = { Text(label) }, singleLine = true, modifier = modifier)
}
