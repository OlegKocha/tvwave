package tv.tvwave.app

object EpgLinks {
    /** Keep matching schedules in every eligible source so reordering is immediate.
     * Explicit matches may point to a channel with a completely different name. */
    fun needed(state:AppState,source:String,catalog:List<EpgChannel>):Set<String> {
        val channels=state.playlists.filter{state.playlistEpgSources[it.id].let{chosen->chosen==null||chosen==source}}.flatMap{it.channels}
        val wanted=Epg.match(channels,catalog).values.toMutableSet()
        val ids=state.channels.map{it.id}.toSet()
        val available=catalog.map{it.id}.toSet()
        state.epgBindings.filterKeys{it in ids}.values.filter{it.source==source&&it.channel in available}.forEach{wanted.add(it.channel)}
        return wanted
    }
    fun resolve(state:AppState,catalogs:Map<String,List<EpgChannel>>):Map<String,EpgBinding> {
        val result=mutableMapOf<String,EpgBinding>()
        for(source in state.sources){
            for((id,epg) in Epg.match(state.playlists.filter{state.playlistEpgSources[it.id].let{chosen->chosen==null||chosen==source.id}}.flatMap{it.channels},catalogs[source.id].orEmpty()))result.getOrPut(id){EpgBinding(source.id,epg)}
        }
        val ids=state.channels.map{it.id}.toSet()
        for((id,b) in state.epgBindings)if(id in ids){
            result.remove(id)
            if(catalogs[b.source].orEmpty().any{it.id==b.channel})result[id]=b
        }
        return result
    }
}
/** A session freezes Recently watched until the user selects another group. */
class RecentOrder {
    private var inside=false
    var ids:List<String> = emptyList();private set
    fun enter(recent:Boolean,history:List<String>){if(recent&&!inside)ids=history.toList();inside=recent}
}
