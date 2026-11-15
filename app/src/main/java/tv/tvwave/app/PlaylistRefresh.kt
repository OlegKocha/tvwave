package tv.tvwave.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** Refresh contents at the saved URL; never turn a file import into a network source. */
object PlaylistRefresh {
    private val client=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS)
        .readTimeout(20,TimeUnit.SECONDS).callTimeout(60,TimeUnit.SECONDS).build()

    suspend fun download(playlist:Playlist):Playlist {
        if(playlist.source.isBlank())throw UserError(tr("this_local_playlist_is_stored_in_the_app_choose_a_new_file_to_update_i"))
        val call=client.newCall(Request.Builder().url(SourceIO.url(playlist.source)).build())
        try {
            val response=suspendCancellableCoroutine<Response>{continuation->
                continuation.invokeOnCancellation{call.cancel()}
                call.enqueue(object:Callback {
                    override fun onFailure(call:Call,e:IOException){if(!continuation.isCancelled)continuation.resumeWithException(e)}
                    override fun onResponse(call:Call,response:Response){continuation.resume(response){_,value,_->value.close()}}
                })
            }
            return response.use{r->runInterruptible(Dispatchers.IO){
                if(!r.isSuccessful)throw UserError(tr("the_source_returned_http",r.code))
                val body=r.body?:throw UserError(tr("playlist_refresh_failed"))
                if(body.contentLength()>SourceIO.MAX_PLAYLIST)throw UserError(tr("playlist_refresh_failed"))
                val raw=SourceIO.text(SourceIO.read(body.byteStream(),SourceIO.MAX_PLAYLIST))
                playlist.copy(raw=raw,channels=M3u.parse(raw,playlist.id,playlist.source))
            }}
        } finally {call.cancel()}
    }

    fun replace(old:AppState,lists:List<Playlist>):AppState {
        val ids=lists.flatMap{it.channels}.map{it.id}.toSet()
        return old.copy(playlists=lists,favourites=old.favourites.intersect(ids),history=old.history.filter{it in ids},last=old.last.takeIf{it in ids}.orEmpty(),epgBindings=old.epgBindings.filterKeys{it in ids},epgOffsets=old.epgOffsets.filterKeys{it in ids},playlistEpgSources=old.playlistEpgSources.filterKeys{id->lists.any{it.id==id}})
    }

    /** Do not overwrite edits/deletions made while the request was in flight. */
    fun merge(latest:AppState,before:Playlist,downloaded:Playlist):AppState {
        val current=latest.playlists.find{it.id==before.id}?:return latest
        if(current.source!=before.source||current.raw!=before.raw)return latest
        val lists=latest.playlists.map{if(it.id==before.id)downloaded.copy(name=current.name)else it}
        if(lists.sumOf{it.channels.size}>50000)throw UserError(tr("limit_reached_15_playlists_or_50_000_channels"))
        return replace(latest,lists)
    }
}
