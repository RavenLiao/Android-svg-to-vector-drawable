package io.github.ravenliao.svg2vd.upstream

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

private val versionLine = Regex("(?m)^svg2vdVersion=([0-9]+)\\.([0-9]+)\\.([0-9]+)(\\r?)(?=\\n|$)")

data class ToolVersion(val major: Int, val minor: Int, val patch: Int) {
    init {
        require(major >= 0 && minor >= 0 && patch >= 0) { "tool version components must be non-negative" }
    }

    fun incrementPatch(): ToolVersion = copy(patch = Math.addExact(patch, 1))

    override fun toString(): String = "$major.$minor.$patch"
}

fun parseToolVersionFile(content: String): ToolVersion {
    val matches = versionLine.findAll(content).toList()
    require(matches.size == 1) { "gradle.properties must contain exactly one svg2vdVersion=X.Y.Z assignment" }
    val match = matches.single()
    require(match.range.first == content.indexOf("svg2vdVersion=")) { "svg2vdVersion must not be duplicated or hidden by another assignment" }
    return ToolVersion(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt())
}

fun bumpToolPatchVersionFile(path: Path, expected: ToolVersion): ToolVersion {
    val original = Files.readString(path, StandardCharsets.UTF_8)
    val actual = parseToolVersionFile(original)
    require(actual == expected) { "tool version changed concurrently: expected $expected, found $actual" }
    val next = actual.incrementPatch()
    val match = versionLine.find(original)!!
    val replacement = "svg2vdVersion=$next${match.groupValues[4]}"
    Files.writeString(path, original.replaceRange(match.range, replacement), StandardCharsets.UTF_8)
    return next
}
