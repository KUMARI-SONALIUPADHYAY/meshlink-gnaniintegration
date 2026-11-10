package com.meshlink.app.core.security
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
class ReplayProtection {
    private val seen=ConcurrentHashMap.newKeySet<UUID>()
    fun accept(id:UUID)=seen.add(id)
    fun contains(id:UUID)=seen.contains(id)
    fun trim(max:Int=5000){ if(seen.size>max) seen.clear() }
}
