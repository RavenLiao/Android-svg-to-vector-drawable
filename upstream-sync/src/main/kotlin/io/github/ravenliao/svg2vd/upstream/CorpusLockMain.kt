package io.github.ravenliao.svg2vd.upstream

import java.nio.file.Path

object CorpusLockMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "usage: <materialized-corpus-manifest> <corpus-lock-output>" }
        println(writeCorpusLockFromManifest(Path.of(args[0]), Path.of(args[1])))
    }
}
