package tv.tvwave.app

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

val reminderMinuteOptions=listOf(5,10,15,30,60)

/** Local reminders contain no stream URLs. Expired programmes cannot notify again. */
data class ProgrammeReminder(val channelId:String,val channelName:String,val start:Long,val end:Long,val title:String,val notified:Boolean=false,val notifiedMinutes:Set<Int> = if(notified)setOf(5)else emptySet()) {
    private val deliveredMinutes get()=if(notified&&notifiedMinutes.isEmpty())setOf(5)else notifiedMinutes
    val id get()="$channelId:$start"
    fun due(now:Long,minutes:Set<Int> = setOf(5))=now<start&&minutes.any{it !in deliveredMinutes&&now>=start-it*60_000L}
    // On resume, coalesce already crossed thresholds into one notification.
    fun delivered(now:Long,minutes:Set<Int>)=copy(notified=true,notifiedMinutes=deliveredMinutes+minutes.filter{now>=start-it*60_000L})
    fun programme()=Programme("",start,end,title)
}
class ProgrammeReminders(private val prefs:SharedPreferences) {
    fun read():List<ProgrammeReminder> = try {
        val array=JSONArray(prefs.getString("items","[]"))
        (0 until array.length()).map{array.getJSONObject(it)}.map{j->
            ProgrammeReminder(j.getString("channel"),j.getString("name"),j.getLong("start"),j.getLong("end"),j.getString("title"),j.optBoolean("notified"),j.optJSONArray("notifiedMinutes")?.let{a->(0 until a.length()).map{a.getInt(it)}.toSet()}?:if(j.optBoolean("notified"))setOf(5)else emptySet())
        }.distinctBy{it.id}.sortedBy{it.start}
    }catch(_:Exception){emptyList()}
    fun save(items:List<ProgrammeReminder>) {
        val array=JSONArray()
        items.distinctBy{it.id}.sortedBy{it.start}.forEach{r->array.put(JSONObject().put("channel",r.channelId).put("name",r.channelName).put("start",r.start).put("end",r.end).put("title",r.title).put("notified",r.notified).put("notifiedMinutes",JSONArray(r.notifiedMinutes.sorted())))}
        prefs.edit().putString("items",array.toString()).apply()
    }
}
