package io.github.ravenliao.svg2vd.upstream

import java.nio.file.Path

object CorpusManifestMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 4 && args[0] == "sync") { "usage: sync <workspace-root> <external-candidate-manifest> <corpus-output-directory>" }
        val workspace = Path.of(args[1]).toAbsolutePath().normalize()
        val candidate = Path.of(args[2]).toAbsolutePath().normalize()
        val output = Path.of(args[3]).toAbsolutePath().normalize()
        require(!candidate.startsWith(workspace)) { "candidate manifest must be outside the workspace: $candidate" }
        println(synchronizeCorpus(candidate, output))
    }
}
