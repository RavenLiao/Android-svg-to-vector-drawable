package io.github.ravenliao.svg2vd.upstream

import java.nio.file.Path

object LockedCandidateMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 5) { "usage: <workspace-root> <corpus-lock> <candidate-output-directory> <runtime-sha:lock-sha> <scope-file>" }
        val workspace = Path.of(args[0]).toAbsolutePath().normalize()
        val lock = Path.of(args[1]).toAbsolutePath().normalize()
        val output = Path.of(args[2]).toAbsolutePath().normalize()
        require(!output.startsWith(workspace)) { "candidate output directory must be outside the workspace: $output" }
        val identities = args[3].split(':', limit = 2)
        require(identities.size == 2 && identities.all(String::isNotBlank)) { "runtime and lock identities are required" }
        val scope = loadScope(Path.of(args[4]))
        println(LockedCandidateDiscoverer(scope, GitilesContentClient(), identities[0], identities[1]).discoverLockedCandidate(lock, output))
    }
}
