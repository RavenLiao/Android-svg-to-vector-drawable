package io.github.ravenliao.svg2vd.upstream

import java.nio.file.Path

object ToolVersionMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 3 && args[0] == "bump-patch") { "usage: bump-patch <gradle.properties> <expected-version>" }
        val expected = args[2].split('.').map(String::toInt).let { require(it.size == 3); ToolVersion(it[0], it[1], it[2]) }
        println(bumpToolPatchVersionFile(Path.of(args[1]).toAbsolutePath().normalize(), expected))
    }
}
