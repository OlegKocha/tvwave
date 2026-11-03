package tv.tvwave.app

import org.json.JSONObject

/** JSON is the source of truth. A new language needs no Kotlin registration. */
data class LanguagePack(val locale:String,val nativeName:String,val strings:Map<String,String>) {
    companion object {
        val placeholders=Regex("\\{(p[1-9][0-9]*)\\}")
        fun parse(text:String):LanguagePack {
            val json=JSONObject(text)
            val locale=json.getString("locale")
            require(locale.matches(Regex("[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})*")))
            val name=json.getString("nativeName");require(name.isNotBlank()&&name.length<=60)
            val values=json.getJSONObject("strings")
            val strings=values.keys().asSequence().associateWith{key->
                require(values.get(key) is String)
                values.getString(key).also{require(it.isNotBlank())}
            }
            return LanguagePack(locale,name,strings)
        }
        fun format(template:String,args:Array<out Any?>):String = placeholders.replace(template){match->
            // One replacement pass: user-supplied names cannot inject other placeholders.
            args.getOrNull(match.groupValues[1].drop(1).toInt()-1)?.toString().orEmpty()
        }
        fun tokens(value:String)=placeholders.findAll(value).map{it.value}.toSet()
    }
    fun text(key:String,fallback:LanguagePack,vararg args:Any?):String {
        val base=fallback.strings[key]?:key
        val translated=strings[key]?.takeIf{tokens(it)==tokens(base)}?:base
        return format(translated,args)
    }
}
