package io.github.ravenliao.svg2vd.upstream

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

val GITILES_REFS_URI: URI = URI.create("https://android.googlesource.com/platform/tools/base/+refs?format=JSON")

data class GitilesHttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, List<String>> = emptyMap(),
)
fun interface GitilesTransport {
    fun get(uri: URI, timeout: Duration): GitilesHttpResponse
}
class GitilesTransportException(message: String) : IllegalStateException(message)

class GitilesClient(
    private val transport: GitilesTransport = GitilesTransport { uri, timeout ->
        val http = HttpClient.newBuilder().connectTimeout(timeout).build()
        val request = HttpRequest.newBuilder(uri).timeout(timeout).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        GitilesHttpResponse(response.statusCode(), response.body(), response.headers().map())
    },
    private val timeout: Duration = Duration.ofSeconds(30),
    private val retryDelay: Duration = Duration.ofSeconds(1),
) {
    fun fetchRefs(): GitilesRefs {
        return retryGitiles("refs $GITILES_REFS_URI", retryDelay, {
            transport.get(GITILES_REFS_URI, timeout).let { GitilesResponse(it.statusCode, it.body, it.headers) }
        }) { message ->
            throw GitilesTransportException(message)
        }.let(GitilesRefs::fromJson)
    }
}
