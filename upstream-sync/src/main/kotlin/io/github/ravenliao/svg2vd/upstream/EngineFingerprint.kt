package io.github.ravenliao.svg2vd.upstream

import java.security.MessageDigest

data class ScopeEntry(val path: String, val objectId: String)
data class EngineFingerprint(val sha256: String, val entries: List<ScopeEntry>)

fun fingerprint(scopeSha256: String, entries: List<ScopeEntry>, engineRuntimeClosureSha256: String): EngineFingerprint {
    val sortedEntries = entries.sortedWith(compareBy<ScopeEntry> { it.path }.thenBy { it.objectId })
    val canonical = buildList {
        add(scopeSha256)
        sortedEntries.forEach {
            add(it.path)
            add(it.objectId)
        }
        add(engineRuntimeClosureSha256)
    }.joinToString("\u0000").toByteArray(Charsets.UTF_8)
    return EngineFingerprint(sha256Hex(canonical), sortedEntries)
}

internal fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
