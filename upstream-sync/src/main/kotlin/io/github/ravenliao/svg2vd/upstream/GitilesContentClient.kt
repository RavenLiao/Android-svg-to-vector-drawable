package io.github.ravenliao.svg2vd.upstream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

private const val GITILES_BASE_URL = "https://android.googlesource.com/platform/tools/base"
private const val GITILES_XSSI_PREFIX = ")]}'\n"
private val objectIdPattern = Regex("^[0-9a-f]{40}$")

data class GitilesBytesResponse(
    val statusCode: Int,
    val body: ByteArray,
    val headers: Map<String, List<String>> = emptyMap(),
)
fun interface GitilesBytesTransport {
    fun get(uri: URI, timeout: Duration): GitilesBytesResponse
}

internal object DefaultGitilesBytesTransport : GitilesBytesTransport {
    private val http = HttpClient.newBuilder().build()

    override fun get(uri: URI, timeout: Duration): GitilesBytesResponse {
        val request = HttpRequest.newBuilder(uri).timeout(timeout).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
        return GitilesBytesResponse(response.statusCode(), response.body(), response.headers().map())
    }
}

interface CandidateSource {
    fun objectIdAt(commit: String, path: String): String
    fun archive(commit: String, path: String): ByteArray
    fun blob(commit: String, path: String): ByteArray
}

class GitilesContentClient(
    private val transport: GitilesBytesTransport = DefaultGitilesBytesTransport,
    private val timeout: Duration = Duration.ofSeconds(30),
    private val retryDelay: Duration = Duration.ofSeconds(1),
) : CandidateSource {
    override fun objectIdAt(commit: String, path: String): String = json(gitilesObjectUri(commit, path)).getValue("id").jsonPrimitive.content.also(::requireObjectId)

    override fun archive(commit: String, path: String): ByteArray = get(gitilesArchiveUri(commit, path))

    override fun blob(commit: String, path: String): ByteArray = try {
        Base64.getMimeDecoder().decode(get(gitilesBlobUri(commit, path)))
    } catch (error: IllegalArgumentException) {
        throw GitilesTransportException("Gitiles blob $commit/$path is not base64 text: ${error.message}")
    }

    private fun json(uri: URI) = try {
        val payload = get(uri).toString(Charsets.UTF_8)
        require(payload.startsWith(GITILES_XSSI_PREFIX)) { "Gitiles JSON response is missing the required XSSI prefix" }
        Json.parseToJsonElement(payload.removePrefix(GITILES_XSSI_PREFIX)).jsonObject
    } catch (error: GitilesTransportException) {
        throw error
    } catch (error: Exception) {
        throw IllegalArgumentException("Gitiles JSON response is invalid: ${error.message}")
    }

    private fun get(uri: URI): ByteArray {
        return retryGitiles("content $uri", retryDelay, { transport.get(uri, timeout).asRetryResponse() }) { message ->
            throw GitilesTransportException(message)
        }
    }
}

private fun GitilesBytesResponse.asRetryResponse() = GitilesResponse(statusCode, body, headers)

fun gitilesObjectUri(commit: String, path: String): URI {
    requireObjectId(commit)
    requireSafeScopePath(path)
    return URI.create("$GITILES_BASE_URL/+/$commit/$path?format=JSON")
}

fun gitilesArchiveUri(commit: String, path: String): URI {
    requireObjectId(commit)
    requireSafeScopePath(path)
    return URI.create("$GITILES_BASE_URL/+archive/$commit/$path.tar.gz")
}

fun gitilesBlobUri(commit: String, path: String): URI {
    requireObjectId(commit)
    requireSafeScopePath(path)
    return URI.create("$GITILES_BASE_URL/+/$commit/$path?format=TEXT")
}

private fun requireObjectId(objectId: String) {
    require(objectIdPattern.matches(objectId)) { "Gitiles object id must be a 40-character lowercase SHA-1" }
}

private fun requireSafeScopePath(path: String) {
    require(path.isNotBlank() && !path.startsWith('/') && !path.split('/').any { it in setOf("", ".", "..") }) {
        "Gitiles scope path is unsafe: $path"
    }
}
