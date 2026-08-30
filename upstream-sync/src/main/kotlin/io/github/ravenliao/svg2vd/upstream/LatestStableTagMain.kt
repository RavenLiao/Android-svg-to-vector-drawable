package io.github.ravenliao.svg2vd.upstream

import java.nio.file.Files
import java.nio.file.Path

/** Reads Gitiles refs and emits the newest accepted stable tag after the lock anchor. */
object LatestStableTagMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "usage: <corpus-lock> <output-file>" }
        val lock = readCorpusLock(Path.of(args[0]).toAbsolutePath().normalize())
        val anchor = classifyTag(lock.tag.name, GitilesRef(lock.tag.tagObject, lock.tag.peeledCommit))
            as? TagClassification.AcceptedStable
            ?: error("corpus lock tag is not an accepted stable tag: ${lock.tag.name}")
        val latest = discoverStableTags(GitilesClient().fetchRefs(), anchor.version)
            .let { result ->
                require(result is DiscoveryResult.Success)
                result.candidates.lastOrNull()
            }
        val output = Path.of(args[1]).toAbsolutePath().normalize()
        Files.createDirectories(output.parent)
        Files.writeString(output, (latest?.name ?: "NO_UPDATE") + "\n")
        println(latest?.name ?: "NO_UPDATE")
    }
}
