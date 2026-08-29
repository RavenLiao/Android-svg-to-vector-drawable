package io.github.ravenliao.svg2vd.upstream

import java.nio.file.Files
import java.nio.file.Path

object BuildInputDigestMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size >= 4 && args[0] == "summarize") {
            "usage: summarize <workspace-root> <runtime-artifacts-file> <output-file> [module-root...]"
        }
        val artifacts = Files.readAllLines(Path.of(args[2])).map { line ->
            val parts = line.split('\t', limit = 2)
            require(parts.size == 2 && parts.all(String::isNotBlank)) { "runtime artifact input is invalid" }
            RuntimeArtifact(parts[0], Path.of(parts[1]))
        }
        val output = Path.of(args[3])
        Files.createDirectories(output.parent)
        Files.writeString(output, "${runtimeClosureSha256(artifacts)}\n${distributionLockSha256(Path.of(args[1]), args.drop(4).toSet())}\n")
    }
}
