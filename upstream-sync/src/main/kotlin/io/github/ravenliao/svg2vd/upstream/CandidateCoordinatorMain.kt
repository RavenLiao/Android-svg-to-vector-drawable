package io.github.ravenliao.svg2vd.upstream

import java.nio.file.Path

object CandidateCoordinatorMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 5 || args.size == 6) {
            "usage: <discover|sync> <workspace-root> <candidate-or-tag> <candidate-output-or-scope> <runtime-or-engine-build>"
        }
        when (args[0]) {
            "discover" -> discover(args)
            "sync" -> sync(args)
            else -> error("unknown candidate coordinator operation: ${args[0]}")
        }
    }

    private fun discover(args: Array<String>) {
        val workspace = Path.of(args[1]).toAbsolutePath().normalize()
        val tag = args[2]
        val candidates = externalPath(args[3], workspace, "candidate output directory")
        val runtimeAndLock = args[4].split(':', limit = 2)
        require(runtimeAndLock.size == 2 && runtimeAndLock.all(String::isNotBlank)) { "discover requires runtime-sha:distribution-lock-sha" }
        val scope = loadScope(workspace.resolve("upstream-scope.yaml"))
        val candidate = CandidateManifestCoordinator(GitilesClient()::fetchRefs, GitilesContentClient()).discover(
            tag,
            scope,
            runtimeAndLock[0],
            runtimeAndLock[1],
            candidates,
        )
        println(candidate)
    }

    private fun sync(args: Array<String>) {
        require(args.size == 6) { "sync requires runtime-sha:distribution-lock-sha" }
        val workspace = Path.of(args[1]).toAbsolutePath().normalize()
        val manifest = externalPath(args[2], workspace, "candidate manifest")
        val scopePath = Path.of(args[3]).toAbsolutePath().normalize()
        val engineBuild = Path.of(args[4]).toAbsolutePath().normalize()
        val runtimeAndLock = args[5].split(':', limit = 2)
        require(runtimeAndLock.size == 2 && runtimeAndLock.all(String::isNotBlank)) { "sync requires runtime-sha:distribution-lock-sha" }
        val scope = loadScope(scopePath)
        val materialStore = CandidateMaterialStore(manifest, scope)
        println(SyncCandidateCoordinator(workspace, scope, engineBuild, materialStore::materialFor, runtimeAndLock[0], runtimeAndLock[1]).sync(manifest))
    }

    private fun externalPath(value: String, workspace: Path, label: String): Path {
        val path = Path.of(value).toAbsolutePath().normalize()
        require(!path.startsWith(workspace)) { "$label must be outside the workspace: $path" }
        return path
    }
}
