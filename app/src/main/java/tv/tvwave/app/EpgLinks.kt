package tv.tvwave.app

object EpgLinks {
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
