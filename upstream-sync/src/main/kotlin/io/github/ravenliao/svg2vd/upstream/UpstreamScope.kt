package io.github.ravenliao.svg2vd.upstream

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.nio.file.Files
import java.nio.file.Path

data class UpstreamScope(
    val version: Int,
    val paths: List<String>,
    val historyAllowlist: Set<String>,
    val blobPaths: Set<String> = emptySet(),
) {
    val sha256: String
        get() = sha256Hex(canonicalScopeBytes(this))
}

fun loadScope(path: Path): UpstreamScope {
    val root = Files.newInputStream(path).use { input ->
        Load(LoadSettings.builder().setAllowDuplicateKeys(false).setMaxAliasesForCollections(0).build()).loadFromInputStream(input)
    } as? Map<*, *> ?: throw IllegalArgumentException("scope must be a YAML mapping")
    val version = root["version"]?.toString()?.toIntOrNull() ?: throw IllegalArgumentException("scope version must be an integer")
    val paths = stringList(root["paths"], "paths")
    val blobPaths = root["blob_paths"]?.let { stringList(it, "blob_paths").toSet() } ?: emptySet()
    val allowlist = stringList(root["history_allowlist"], "history_allowlist").toSet()
    require(paths.isNotEmpty() && paths.all { it.isNotBlank() } && paths.size == paths.toSet().size) { "scope paths must be unique and non-empty" }
    require(blobPaths.all(paths::contains)) { "scope blob_paths must be declared in paths" }
    require(allowlist == KNOWN_LEGACY_TAGS) { "scope history_allowlist must exactly equal the immutable historical tag allowlist" }
    return UpstreamScope(version, paths, allowlist, blobPaths)
}

private fun stringList(value: Any?, name: String): List<String> =
    (value as? List<*>)?.map { it as? String ?: throw IllegalArgumentException("$name must contain strings") }
        ?: throw IllegalArgumentException("$name must be a YAML list")

private fun canonicalScopeBytes(scope: UpstreamScope): ByteArray = buildString {
    append(scope.version).append('\u0000')
    scope.paths.sorted().forEach { append(it).append('\u0000') }
    scope.blobPaths.sorted().forEach { append("blob:").append(it).append('\u0000') }
    scope.historyAllowlist.sorted().forEach { append(it).append('\u0000') }
}.toByteArray(Charsets.UTF_8)
