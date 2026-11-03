package tv.tvwave.app

import java.io.*
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

/** Compressed logical records, independent of device paths and SQLite versions. */
object EpgArchive {
    const val MAX_PACKED=16*1024*1024
    fun encode(url:String,updated:Long,mapping:Map<String,String>,count:Int,programmes:((Programme)->Unit)->Unit):String {
        val bytes=ByteArrayOutputStream()
        DataOutputStream(GZIPOutputStream(bytes)).use{out->
            out.writeUTF("TVWave-EPG-1");out.writeUTF(url);out.writeLong(updated);out.writeInt(mapping.size)
            mapping.forEach{(id,epg)->out.writeUTF(id);out.writeUTF(epg)}
            out.writeInt(count);programmes{p->out.writeUTF(p.epgId);out.writeLong(p.start);out.writeLong(p.end);out.writeUTF(p.title);out.writeUTF(p.description)}
        }
        if(bytes.size()>MAX_PACKED)throw UserError(tr("the_epg_cache_is_too_large_for_a_backup"))
        return bytes.toByteArray().toByteString().base64()
    }
    fun read(encoded:String,url:String,ids:Set<String>,onHeader:(Long,Map<String,String>)->Unit={_,_->},onProgramme:(Programme)->Unit={}) {
        try{
            require(encoded.length<=MAX_PACKED*4/3+8)
            val packed=encoded.decodeBase64()?.toByteArray()?:throw IllegalArgumentException()
            require(packed.size<=MAX_PACKED)
            DataInputStream(Epg.Bounded(GZIPInputStream(ByteArrayInputStream(packed)),256L*1024*1024)).use{input->
                require(input.readUTF() in setOf("TVWave-EPG-1",LegacyBackup.EPG_FORMAT)&&input.readUTF()==url)
                val updated=input.readLong();require(updated>0)
                val size=input.readInt();require(size in 0..50000)
                val mapping=mutableMapOf<String,String>()
                repeat(size){val id=input.readUTF();val epg=input.readUTF();require(id in ids&&epg.isNotBlank()&&epg.length<=256&&mapping.put(id,epg)==null)}
                val allowed=mapping.values.toSet();onHeader(updated,mapping)
                val count=input.readInt();require(count in 0..Epg.MAX_PROGRAMMES)
                repeat(count){
                    val p=Programme(input.readUTF(),input.readLong(),input.readLong(),input.readUTF(),input.readUTF())
                    require(p.epgId in allowed&&p.start>0&&p.end>p.start&&p.title.isNotBlank()&&p.title.length<=512&&p.description.length<=512)
                    onProgramme(p)
                }
                require(input.read()==-1)
            }
        }catch(_:Exception){throw UserError(tr("the_programme_cache_in_the_backup_is_damaged"))}
    }
}
