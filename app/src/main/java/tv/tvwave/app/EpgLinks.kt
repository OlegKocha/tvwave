package tv.tvwave.app

object EpgLinks {
    private fun eligibleChannels(state:AppState,source:String) =
        state.playlists.filter { playlist ->
            val selected = state.playlistEpgSources[playlist.id]
            selected == null || selected == source
        }.flatMap { it.channels }

    /** Keep matching schedules in every eligible source so reordering is immediate.
     * Explicit matches may point to a channel with a completely different name. */
    fun needed(state:AppState,source:String,catalog:List<EpgChannel>):Set<String> {
        val channels=eligibleChannels(state,source)
        val wanted=Epg.match(channels,catalog).values.toMutableSet()
        val ids=state.channels.map{it.id}.toSet()
        val available=catalog.map{it.id}.toSet()
        state.epgBindings.filterKeys{it in ids}.values.filter{it.source==source&&it.channel in available}.forEach{wanted.add(it.channel)}
        return wanted
    }
    fun resolve(state:AppState,catalogs:Map<String,List<EpgChannel>>):Map<String,EpgBinding> {
        return ranked(state,catalogs).mapValues { it.value.first() }
    }

    fun ranked(state:AppState,catalogs:Map<String,List<EpgChannel>>):Map<String,List<EpgBinding>> {
        val result=mutableMapOf<String,MutableList<EpgBinding>>()
        for(source in state.sources) {
            val matches=Epg.match(eligibleChannels(state,source.id),catalogs[source.id].orEmpty())
            for((id,epg) in matches) {
                result.getOrPut(id) { mutableListOf() }.add(EpgBinding(source.id,epg))
            }
        }
        val ids=state.channels.mapTo(mutableSetOf()) { it.id }
        for((id,binding) in state.epgBindings) {
            if(id !in ids)continue
            if(catalogs[binding.source].orEmpty().any { it.id==binding.channel }) {
                result[id]=mutableListOf(binding)
            } else result.remove(id)
        }
        return result
    }
}
/** A session freezes Recently watched until the user selects another group. */
class RecentOrder {
    private var inside=false
    var ids:List<String> = emptyList();private set
    fun retain(history:List<String>){if(inside)ids=ids.filter{it in history}}
    fun enter(recent:Boolean,history:List<String>){if(recent&&!inside)ids=history.toList();inside=recent}
}
