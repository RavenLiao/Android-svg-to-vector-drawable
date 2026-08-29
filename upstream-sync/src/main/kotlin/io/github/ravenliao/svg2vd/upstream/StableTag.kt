package io.github.ravenliao.svg2vd.upstream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val ACCEPTED_STABLE_SYNTAX = "^studio-(?<first>[0-9]+)\\.(?<second>[0-9]+)\\.(?<third>[0-9]+)(?:-patch(?<revision>0*[1-9][0-9]*))?$"
private const val GITILES_XSSI_PREFIX = ")]}'\n"

private val acceptedStable = Regex(ACCEPTED_STABLE_SYNTAX)
private val knownPreview = Regex("^studio-[0-9]+\\.[0-9]+\\.[0-9]+-(alpha|beta|canary|rc)[0-9]*$")
private val masterDevelopment = Regex("^studio-master-dev_before_[0-9]+$")
private val unknownCore = Regex("^studio-([0-9]+)\\.([0-9]+)\\.([0-9]+)(?:[.-].*)?$")
val KNOWN_LEGACY_TAGS = setOf(
    "studio-1.4", "studio-1.5", "studio-2.0", "studio-2.0-rc1",
    "studio-2.2", "studio-2.2-preview3", "studio-2.3", "studio-3.0",
)

data class GitilesRef(val value: String, val peeled: String? = null)

data class GitilesRefs(val refs: Map<String, GitilesRef>) {
    companion object {
        fun fromJson(payload: String): GitilesRefs {
            require(payload.startsWith(GITILES_XSSI_PREFIX)) { "Gitiles refs response is missing the required XSSI prefix" }
            val objectRefs = Json.parseToJsonElement(payload.removePrefix(GITILES_XSSI_PREFIX)).jsonObject
            return GitilesRefs(objectRefs.mapValues { (_, entry) ->
                val ref = entry.jsonObject
                GitilesRef(ref.getValue("value").jsonPrimitive.content, ref["peeled"]?.jsonPrimitive?.content)
            })
        }
    }
}

data class UpstreamTag(val name: String, val tagObject: String, val peeledCommit: String)

data class StableCore(val first: Int, val second: Int, val third: Int) : Comparable<StableCore> {
    override fun compareTo(other: StableCore): Int = compareValuesBy(this, other, StableCore::first, StableCore::second, StableCore::third)
}

data class StableVersion(val core: StableCore, val revision: Int, val rawTag: String) : Comparable<StableVersion> {
    override fun compareTo(other: StableVersion): Int = compareValuesBy(this, other, StableVersion::core, StableVersion::revision)
}

sealed interface TagClassification {
    data class AcceptedStable(val tag: UpstreamTag, val version: StableVersion) : TagClassification
    data object KnownNonStableOrLegacy : TagClassification
    data class UnknownVersionLike(val rawTag: String, val core: StableCore?, val reason: String) : TagClassification
}

sealed interface DiscoveryResult {
    data class Success(
        val candidates: List<UpstreamTag>,
        val nonblockingHistoricalUnknowns: List<TagClassification.UnknownVersionLike>,
    ) : DiscoveryResult
}

class DiscoveryBlockedException(message: String) : IllegalStateException(message)

fun classifyTag(rawTag: String, ref: GitilesRef): TagClassification {
    val tag = UpstreamTag(rawTag, ref.value, ref.peeled ?: ref.value)
    val stable = acceptedStable.matchEntire(rawTag)
    if (stable != null) {
        val revision = stable.groups["revision"]?.value?.toInt() ?: 0
        return TagClassification.AcceptedStable(
            tag,
            StableVersion(
                StableCore(stable.groups["first"]!!.value.toInt(), stable.groups["second"]!!.value.toInt(), stable.groups["third"]!!.value.toInt()),
                revision,
                rawTag,
            ),
        )
    }
    if (rawTag in KNOWN_LEGACY_TAGS || knownPreview.matches(rawTag) || masterDevelopment.matches(rawTag)) {
        return TagClassification.KnownNonStableOrLegacy
    }
    val core = unknownCore.matchEntire(rawTag)?.let { match ->
        StableCore(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt())
    }
    return TagClassification.UnknownVersionLike(
        rawTag,
        core,
        if (core == null) "unsupported_studio_tag_format" else "unsupported_studio_tag_variant",
    )
}

fun selectStableTagsAfter(refs: GitilesRefs, after: StableVersion?): List<UpstreamTag> =
    if (after == null) discoverStableTags(refs, null).let { (it as DiscoveryResult.Success).candidates }
    else discover(refs, after).stableAfterAnchor

fun discoverStableTags(refs: GitilesRefs, after: StableVersion?): DiscoveryResult {
    val discovered = discover(refs, after)
    return DiscoveryResult.Success(
        candidates = if (after == null) listOfNotNull(discovered.accepted.lastOrNull()?.tag) else discovered.stableAfterAnchor,
        nonblockingHistoricalUnknowns = discovered.historicalUnknowns,
    )
}

private data class ClassifiedRefs(
    val accepted: List<TagClassification.AcceptedStable>,
    val stableAfterAnchor: List<UpstreamTag>,
    val historicalUnknowns: List<TagClassification.UnknownVersionLike>,
)

private fun discover(refs: GitilesRefs, after: StableVersion?): ClassifiedRefs {
    val classifications = refs.refs.entries
        .asSequence()
        .filter { it.key.startsWith("refs/tags/studio-") }
        .map { (refName, ref) -> classifyTag(refName.removePrefix("refs/tags/"), ref) }
        .toList()
    val accepted = classifications.filterIsInstance<TagClassification.AcceptedStable>().sortedBy(TagClassification.AcceptedStable::version)
    rejectAmbiguousRevisions(accepted)
    val comparisonCore = after?.core ?: accepted.maxByOrNull(TagClassification.AcceptedStable::version)?.version?.core
    val historicalUnknowns = mutableListOf<TagClassification.UnknownVersionLike>()
    classifications.filterIsInstance<TagClassification.UnknownVersionLike>().forEach { unknown ->
        if (unknown.core == null || comparisonCore == null || unknown.core >= comparisonCore) {
            throw blocked(refs, unknown.rawTag, "${unknown.reason}; comparison core is ${comparisonCore ?: "unavailable"}")
        }
        historicalUnknowns += unknown
    }
    return ClassifiedRefs(
        accepted,
        accepted.filter { after == null || it.version > after }.map(TagClassification.AcceptedStable::tag),
        historicalUnknowns.sortedBy(TagClassification.UnknownVersionLike::rawTag),
    )
}

private fun rejectAmbiguousRevisions(accepted: List<TagClassification.AcceptedStable>) {
    accepted.groupBy { it.version.core to it.version.revision }.values
        .filter { it.map { acceptedTag -> acceptedTag.version.rawTag }.distinct().size > 1 }
        .forEach { conflict ->
            val evidence = conflict.joinToString("; ") { tag -> evidence(tag.tag, "ambiguous_raw_patch_spelling") }
            throw DiscoveryBlockedException("$evidence; accepted syntax: $ACCEPTED_STABLE_SYNTAX")
        }
}

private fun blocked(refs: GitilesRefs, rawTag: String, reason: String): DiscoveryBlockedException {
    val ref = refs.refs.getValue("refs/tags/$rawTag")
    return DiscoveryBlockedException("${evidence(UpstreamTag(rawTag, ref.value, ref.peeled ?: ref.value), reason)}; accepted syntax: $ACCEPTED_STABLE_SYNTAX")
}

private fun evidence(tag: UpstreamTag, reason: String): String =
    "raw tag=${tag.name}, tag object=${tag.tagObject}, peeled commit=${tag.peeledCommit}, reason=$reason"
