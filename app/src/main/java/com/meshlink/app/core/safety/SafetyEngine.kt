package com.meshlink.app.core.safety

import com.meshlink.app.core.protocol.MeshPacket
import com.meshlink.app.core.protocol.PacketType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class SafetyDecision { PASS, HOLD, BLOCK }

data class SafetyResult(
    val decision: SafetyDecision,
    val reason: String,
    val riskScore: Float = 0f
)

/**
 * Small deterministic, offline-only classifier.
 * It detects suspicious patterns; it does not determine whether a real-world claim is true or false.
 */
class LocalSafetyClassifier {
    private data class Rule(val label: String, val weight: Float, val hit: Boolean)

    fun analyze(text: String?): Pair<Float, List<String>> {
        val x = text?.trim()?.lowercase().orEmpty()
        if (x.isBlank()) return 0f to emptyList()

        val rules = listOf(
            Rule("Suspicious financial request", 0.42f,
                listOf("send money", "transfer money", "pay now", "upi", "bank account", "account number", "refund fee").any(x::contains)),
            Rule("Credential / OTP request", 0.55f,
                listOf("verify otp", "share otp", "give otp", "password", "passcode", "pin", "cvv").any(x::contains)),
            Rule("Suspicious link", 0.30f, Regex("https?://|www\\.").containsMatchIn(x)),
            Rule("Urgency / pressure language", 0.12f,
                listOf("urgent", "immediately", "act now", "last chance", "forward to everyone").any(x::contains)),
            Rule("Excessive punctuation", 0.10f, x.count { it == '!' } >= 5 || x.count { it == '?' } >= 5),
            Rule("Very long message", 0.20f, x.length > 1000),
            Rule("Forward-chain language", 0.18f,
                listOf("forward this", "share with everyone", "send to 10 people", "viral", "must forward").any(x::contains))
        )
        val hits = rules.filter { it.hit }.map { it.label }
        val score = rules.filter { it.hit }.sumOf { it.weight.toDouble() }.toFloat().coerceIn(0f, 1f)
        return score to hits
    }

    fun score(text: String?): Float = analyze(text).first
}

/**
 * Local policy engine used before local delivery and before mesh forwarding.
 * Emergency packets are never blocked by content classification; malformed packets and abuse-rate limits
 * can still be rejected/held so one node cannot flood the mesh.
 */
class SafetyEngine {
    private val classifier = LocalSafetyClassifier()
    private val history = ConcurrentHashMap<UUID, ArrayDeque<Long>>()
    private val sosHistory = ConcurrentHashMap<UUID, ArrayDeque<Long>>()
    private val fingerprints = ConcurrentHashMap<UUID, ArrayDeque<String>>()

    fun evaluate(packet: MeshPacket, text: String?): SafetyResult {
        if (packet.ttl !in 0..12) {
            return SafetyResult(SafetyDecision.BLOCK, "Invalid TTL", 1f)
        }
        if (packet.payload.size > 60_000) {
            return SafetyResult(SafetyDecision.BLOCK, "Payload exceeds safety limit", 1f)
        }
        if (packet.createdAt <= 0L) {
            return SafetyResult(SafetyDecision.BLOCK, "Invalid message timestamp", 1f)
        }

        val now = System.currentTimeMillis()
        val times = history.getOrPut(packet.originNodeId) { ArrayDeque() }
        val countInWindow = synchronized(times) {
            while (times.isNotEmpty() && now - times.first() > 60_000L) times.removeFirst()
            times.add(now)
            times.size
        }

        // Emergency traffic gets priority. Only a dedicated SOS rate policy may hold it;
        // content rules never block an SOS.
        if (packet.type == PacketType.SOS) {
            val sosTimes = sosHistory.getOrPut(packet.originNodeId) { ArrayDeque() }
            val sosCount = synchronized(sosTimes) {
                while (sosTimes.isNotEmpty() && now - sosTimes.first() > 60_000L) sosTimes.removeFirst()
                sosTimes.add(now)
                sosTimes.size
            }
            return if (sosCount > 3) {
                SafetyResult(SafetyDecision.HOLD, "SOS rate limit: more than 3 SOS packets/minute", 0.70f)
            } else {
                SafetyResult(SafetyDecision.PASS, "Emergency traffic prioritised", 0f)
            }
        }

        if (packet.type != PacketType.TEXT && packet.type != PacketType.ALERT) {
            return SafetyResult(SafetyDecision.PASS, "Policy passed", 0f)
        }

        val clean = text?.trim()?.lowercase().orEmpty()
        val (risk, hits) = classifier.analyze(clean)

        val fingerprintsForPeer = fingerprints.getOrPut(packet.originNodeId) { ArrayDeque() }
        val repeated = synchronized(fingerprintsForPeer) {
            while (fingerprintsForPeer.size > 20) fingerprintsForPeer.removeFirst()
            val seen = clean.isNotBlank() && fingerprintsForPeer.count { it == clean } >= 2
            if (clean.isNotBlank()) fingerprintsForPeer.add(clean)
            seen
        }

        val reasons = hits.toMutableList()
        var finalRisk = risk
        if (repeated) {
            reasons += "Repeated identical message"
            finalRisk += 0.25f
        }
        if (countInWindow > 20) {
            reasons += "Message flood from same origin"
            finalRisk += 0.35f
        }
        finalRisk = finalRisk.coerceIn(0f, 1f)

        return when {
            finalRisk >= 0.90f -> SafetyResult(
                SafetyDecision.BLOCK,
                "High-risk message: ${reasons.distinct().take(3).joinToString(", ")}",
                finalRisk
            )
            finalRisk >= 0.45f -> SafetyResult(
                SafetyDecision.HOLD,
                "Review required: ${reasons.distinct().take(3).joinToString(", ")}",
                finalRisk
            )
            else -> SafetyResult(SafetyDecision.PASS, "Policy passed", finalRisk)
        }
    }
}
