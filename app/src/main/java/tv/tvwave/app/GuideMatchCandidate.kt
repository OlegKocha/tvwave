package tv.tvwave.app

internal data class GuideMatchCandidate(val source:EpgSource,val entry:EpgChannel) {
    fun matches(query:String) = entry.id.contains(query,true) || entry.names.any{it.contains(query,true)}
}

private val token = Regex("""[\p{L}\p{N}]+""")
private fun words(name:String) = token.findAll(EpgSearch.normalize(name)).map{it.value}.toSet()

/** Rank possible manual choices only. The viewer confirms the exact source and regional version. */
internal fun guideMatchCandidates(channel:Channel,catalogs:Map<EpgSource,List<EpgChannel>>):List<GuideMatchCandidate> {
    val target=words(channel.name)-setOf("hd","sd","fhd","uhd")
    return catalogs.flatMap{(source,entries)->entries.map{entry->
        val score=when {
            entry.id.equals(channel.epgId,true) && channel.epgId.isNotBlank()->Int.MAX_VALUE
            entry.names.any{it.equals(channel.name,true)}->Int.MAX_VALUE-1
            else->entry.names.maxOfOrNull{name->words(name).count{it in target}} ?: 0
        }
        score to GuideMatchCandidate(source,entry)
    }}.sortedByDescending{it.first}.map{it.second}
}
