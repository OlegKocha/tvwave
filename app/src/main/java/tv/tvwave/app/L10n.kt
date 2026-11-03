package tv.tvwave.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** Loaded once, shared with background updates and the local phone page. */
object L10n {
    var language by mutableStateOf("en")
        private set
    private var packs:Map<String,LanguagePack> = emptyMap()
    private var context:Context?=null
    private var effective:Map<String,Map<String,String>> = emptyMap()
    val available:List<LanguagePack> get()=packs.values.sortedWith(compareBy<LanguagePack>{it.locale!="en"}.thenBy{it.nativeName})
    val locale:Locale get()=Locale.forLanguageTag(language)
    fun initialize(app:Context) {
        context=app.applicationContext
        val loaded=app.assets.list("i18n").orEmpty().filter{it.endsWith(".json")}.mapNotNull{file->
            runCatching{app.assets.open("i18n/$file").bufferedReader().use{LanguagePack.parse(it.readText())}}.onFailure{android.util.Log.e("TVWaveLocale","Invalid bundled catalogue: $file",it)}.getOrNull()
        }.associateBy{it.locale}
        require("en" in loaded) { "English translation catalogue is required" }
        packs=loaded
        val base=loaded.getValue("en")
        effective=loaded.mapValues{(_,pack)->base.strings.mapValues{(key,value)->
            pack.strings[key]?.takeIf{LanguagePack.tokens(it)==LanguagePack.tokens(value)}?:value
        }}
        select(app.getSharedPreferences("ui-language",Context.MODE_PRIVATE).getString("language","en")?:"en")
    }
    fun select(code:String) {
        language=code.takeIf{it in packs}?:"en"
        context?.getSharedPreferences("ui-language",Context.MODE_PRIVATE)?.edit()?.putString("language",language)?.apply()
    }
    fun text(key:String,vararg args:Any?):String {
        val template=(effective[language]?:effective["en"])?.get(key)?:return key
        return if(args.isEmpty())template else LanguagePack.format(template,args)
    }
    fun days(count:Int):String {
        val category=if(android.os.Build.VERSION.SDK_INT>=24) android.icu.text.PluralRules.forLocale(locale).select(count.toDouble())
        else if(language=="ru") when {count%100 in 11..14->"many";count%10==1->"one";count%10 in 2..4->"few";else->"many"}
        else if(count==1)"one"else"other"
        return text("archive_days_"+(category.takeIf{it in setOf("one","few","many")}?:"other"),count)
    }
}
fun tr(key:String,vararg args:Any?):String=L10n.text(key,*args)

class TvWaveApplication:Application() {
    override fun onCreate(){super.onCreate();L10n.initialize(this)}
}

/** Stable existing values are retained for saved playlists and focus keys. */
object BuiltinGroups {
    const val ALL="Все каналы"
    const val FAVOURITES="Избранное"
    const val RECENT="Недавно смотрели"
    const val UNGROUPED="Без группы"
    fun label(value:String)=when(value){
        ALL->tr("all_channels")
        FAVOURITES->tr("favourites")
        RECENT->tr("recently_watched")
        UNGROUPED->tr("ungrouped")
        else->value
    }
}
