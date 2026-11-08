package tv.tvwave.app

import android.content.SharedPreferences
import org.json.JSONArray

/** Device-local history of submitted searches, deliberately excluded from AppState and .tvwb backups. */
class SearchHistory(private val preferences:SharedPreferences) {
    companion object { const val LIMIT=30 }
    fun read():List<String> = runCatching {
        val array=JSONArray(preferences.getString("queries","[]"))
        (0 until array.length()).map{array.optString(it).trim()}.filter{it.isNotEmpty()}
            .distinctBy(EpgSearch::normalize).take(LIMIT)
    }.getOrDefault(emptyList())

    fun record(query:String):List<String> {
        val term=query.trim().replace(Regex("\\s+")," ")
        val previous=read()
        if(term.isEmpty())return previous
        val recent=(listOf(term)+previous.filter{EpgSearch.normalize(it)!=EpgSearch.normalize(term)}).take(LIMIT)
        return save(recent)
    }

    fun remove(query:String):List<String> = save(read().filter{EpgSearch.normalize(it)!=EpgSearch.normalize(query)})

    private fun save(recent:List<String>):List<String> {
        preferences.edit().putString("queries",JSONArray(recent).toString()).apply()
        return recent
    }
}
