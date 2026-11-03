package tv.tvwave.app

import java.io.*
import java.util.*
import java.util.zip.GZIPInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.ext.DefaultHandler2

/** XMLTV timestamps are absolute instants; rendering uses the television's zone. */
data class Programme(val epgId:String,val start:Long,val end:Long,val title:String,val description:String="")
data class EpgChannel(val id:String,val names:List<String>,val icon:String="")
data class EpgImportResult(val mapping:Map<String,String>,val programmes:Int)
object Epg {
    const val MAX_COMPRESSED=128L*1024*1024
    const val MAX_XML=1536L*1024*1024
    const val MAX_PROGRAMMES=1000000
    const val MAX_DESCRIPTION=32768
    const val UPDATE_MS=12L*60*60*1000
    private val timestamp=Regex("^(\\d{8}|\\d{10}|\\d{12}|\\d{14})(?:\\s+([+-]\\d{4}|Z))?\$")
    private val calendar=object:ThreadLocal<GregorianCalendar>(){
        override fun initialValue()=GregorianCalendar(TimeZone.getTimeZone("UTC"),Locale.ROOT).apply{isLenient=false}
    }
    fun time(value:String):Long? {
        val match=timestamp.matchEntire(value.trim())?:return null
        val digits=match.groupValues[1].padEnd(14,'0');val zone=match.groupValues[2].ifEmpty{"+0000"}.replace("Z","+0000")
        if(zone.drop(1).take(2).toInt()>23||zone.takeLast(2).toInt()>59)return null
        return runCatching{
            val c=calendar.get()!!;c.clear()
            c.set(digits.substring(0,4).toInt(),digits.substring(4,6).toInt()-1,digits.substring(6,8).toInt(),digits.substring(8,10).toInt(),digits.substring(10,12).toInt(),digits.substring(12,14).toInt())
            val offset=(zone.substring(1,3).toInt()*60+zone.substring(3,5).toInt())*60000L
            c.timeInMillis-if(zone[0]=='-')-offset else offset
        }.getOrNull()
    }
    private val punctuation=Regex("[^\\p{L}\\p{N}+]")
    private val qualitySuffix=Regex("(?i)(?:\\s+(?:UHD|FHD|HD|SD|4K|8K|orig))+\$")
    private fun name(s:String)=s.lowercase(Locale.ROOT).replace('ё','е').replace(punctuation,"")
    private fun base(s:String)=name(s.replace(qualitySuffix,""))
    fun match(channels:List<Channel>,guide:List<EpgChannel>):Map<String,String> {
        val ids=guide.map{it.id}.toSet()
        val exact=mutableMapOf<String,MutableSet<String>>();val quality=mutableMapOf<String,MutableSet<String>>()
        for(c in guide)for(n in c.names){exact.getOrPut(name(n)){mutableSetOf()}.add(c.id);quality.getOrPut(base(n)){mutableSetOf()}.add(c.id)}
        return channels.mapNotNull{c->
            val names=listOf(c.epgName,c.name).filter{it.isNotBlank()}
            val id=c.epgId.takeIf{it.isNotEmpty()&&it in ids}
                ?: names.firstNotNullOfOrNull{exact[name(it)]?.singleOrNull()}
                ?: names.firstNotNullOfOrNull{quality[base(it)]?.singleOrNull()}
            id?.let{c.id to it}
        }.toMap()
    }
    class Bounded(input:InputStream,private val limit:Long):FilterInputStream(input){
        var count=0L;private fun seen(n:Int){if(n>0){count+=n;if(count>limit)throw UserError(tr("the_epg_source_exceeds_the_size_limit"))};if(Thread.currentThread().isInterrupted)throw InterruptedIOException()}
        override fun read():Int=super.read().also{seen(if(it<0)0 else 1)}
        override fun read(b:ByteArray,off:Int,len:Int):Int=`in`.read(b,off,len).also{seen(it)}
    }
    fun parse(input:InputStream,channels:List<Channel>,now:Long,onProgramme:(Programme)->Unit,onProgress:(Int)->Unit={},onCatalog:(List<EpgChannel>)->Unit={},includeUnmatched:Boolean=false,baseUrl:String="",oldest:Long=EpgRetention.cutoff(now),selectChannels:((List<EpgChannel>)->Set<String>)?=null):EpgImportResult {
        val probe=PushbackInputStream(BufferedInputStream(Bounded(input,MAX_COMPRESSED)),2)
        val a=probe.read();val b=probe.read();if(b>=0)probe.unread(b);if(a>=0)probe.unread(a)
        val xml=Bounded(if(a==0x1f&&b==0x8b)GZIPInputStream(probe,65536)else probe,MAX_XML)
        val definitions=mutableListOf<EpgChannel>();var mapping:Map<String,String>?=null;var wanted=emptySet<String>()
        var channelId="";var channelIcon="";var aliases=mutableListOf<String>();var pid="";var start:Long?=null;var end:Long?=null
        var title="";var desc="";var capture="";var text=StringBuilder();var depth=0;var root=false;var rows=0;var seen=0;var matchedSeen=0
        val handler=object:DefaultHandler2(){
            override fun resolveEntity(publicId:String?,systemId:String?)=InputSource(StringReader(""))
            override fun internalEntityDecl(name:String?,value:String?){throw UserError(tr("the_epg_contains_unsupported_xml_entities"))}
            override fun startElement(uri:String?,local:String?,q:String,attrs:Attributes){
                depth++;if(depth>32)throw UserError(tr("the_xmltv_structure_is_too_complex"))
                if(depth==1){if(q!="tv")throw UserError(tr("this_is_not_xmltv_a_tv_element_was_expected"));root=true}
                when(q){
                    "channel"->{channelId=attrs.getValue("id").orEmpty().take(256);channelIcon="";aliases=mutableListOf()}
                    "icon"->if(depth==3&&channelId.isNotBlank()&&channelIcon.isEmpty())channelIcon=runCatching{SourceIO.url(attrs.getValue("src").orEmpty(),baseUrl)}.getOrDefault("")
                    "programme"->{
                        if(mapping==null){mapping=match(channels,definitions);wanted=selectChannels?.invoke(definitions)?:if(includeUnmatched)definitions.map{it.id}.toSet()else mapping!!.values.toSet();onCatalog(definitions)}
                        pid=attrs.getValue("channel").orEmpty()
                        if(pid in wanted)matchedSeen++
                        start=if(pid in wanted)time(attrs.getValue("start").orEmpty())else null
                        end=if(start!=null)time(attrs.getValue("stop").orEmpty())else null
                        title="";desc=""
                        seen++;if(seen%10000==0)onProgress(seen)
                    }
                    "display-name","title","desc"->{if(q=="display-name"||start!=null){capture=q;text=StringBuilder()}}
                }
            }
            override fun characters(chars:CharArray,offset:Int,length:Int){val limit=if(capture=="desc")MAX_DESCRIPTION else 2048;if(capture.isNotEmpty()&&text.length<limit)text.append(chars,offset,minOf(length,limit-text.length))}
            override fun endElement(uri:String?,local:String?,q:String){
                if(q==capture){val value=text.toString().trim();when(q){"display-name"->if(aliases.size<30)aliases.add(value.take(512));"title"->if(title.isEmpty())title=value.take(512);"desc"->if(desc.isEmpty())desc=value};capture=""}
                if(q=="channel"){if(channelId.isNotBlank())definitions.add(EpgChannel(channelId,aliases.toList(),channelIcon));channelId="";if(definitions.size>20000)throw UserError(tr("too_many_channels_in_the_epg"))}
                if(q=="programme"){
                    val s=start;val e=end
                    if(pid in wanted&&s!=null&&s<=now+3*86400000L&&(e?:s+86400000L)>oldest&&(e==null||e>s)&&title.isNotBlank()){
                        if(++rows>MAX_PROGRAMMES)throw UserError(tr("too_many_programmes_in_the_epg_cache"))
                        onProgramme(Programme(pid,s,e?:0,title,desc))
                    }
                }
                depth--
            }
        }
        try{
            val factory=SAXParserFactory.newInstance().apply{isNamespaceAware=false;isValidating=false}
            val reader=factory.newSAXParser().xmlReader
            for(feature in listOf("http://xml.org/sax/features/external-general-entities","http://xml.org/sax/features/external-parameter-entities","http://apache.org/xml/features/nonvalidating/load-external-dtd"))runCatching{reader.setFeature(feature,false)}
            reader.entityResolver=handler;reader.contentHandler=handler
            runCatching{reader.setProperty("http://xml.org/sax/properties/declaration-handler",handler)}
            reader.parse(InputSource(xml))
            if(!root||definitions.isEmpty())throw UserError(tr("no_channel_list_in_xmltv"))
            val result=mapping?:match(channels,definitions)
            if(mapping==null)onCatalog(definitions)
            if(result.isEmpty()&&!includeUnmatched&&selectChannels==null)throw UserError(tr("the_epg_channels_could_not_be_matched_to_your_playlist"))
            if(rows==0&&(selectChannels==null||seen==0||matchedSeen>0))throw UserError(tr("no_programmes_for_the_current_date_the_previous_cache_is_preserved"))
            return EpgImportResult(result,rows)
        }catch(e:UserError){throw e}catch(e:Exception){throw UserError(tr("could_not_read_xmltv_the_file_is_damaged_or_the_download_was_interrupt"))}
        finally{xml.close()}
    }
}
