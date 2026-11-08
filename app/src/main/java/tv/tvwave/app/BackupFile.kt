package tv.tvwave.app

import java.nio.ByteBuffer
import java.security.MessageDigest

/** Compressed, configuration-only v3. Reads legacy password-free v2. SHA-256 detects accidental damage, not malicious changes. */
object BackupFile {
    const val EXTENSION="tvwb"
    fun accepts(name:String)=name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT) in setOf(EXTENSION,"tvwavebackup",LegacyBackup.EXTENSION)
    private val magic = "TVWAVBK3".toByteArray(Charsets.US_ASCII)
    private val v2 = LegacyBackup.V2
    private val legacy = LegacyBackup.V1
    private const val HEADER = 48
    fun needsPassword(bytes: ByteArray) = bytes.size >= 8 && bytes.copyOfRange(0,8).contentEquals(legacy)
    fun encode(state: AppState): ByteArray {
        // AppState includes watched channels, but never device-local search queries.
        val payload = java.io.ByteArrayOutputStream().apply{java.util.zip.GZIPOutputStream(this).use{it.write(StateCodec.encode(state.copy(epgBackup="")))}}.toByteArray()
        if (payload.size > SourceIO.MAX_STATE) throw UserError(tr("too_much_data_for_a_backup"))
        val checksum = MessageDigest.getInstance("SHA-256").digest(payload)
        return ByteBuffer.allocate(HEADER).put(magic).putInt(3).putInt(payload.size).put(checksum).array() + payload
    }
    fun decode(bytes: ByteArray): AppState {
        if (bytes.size !in HEADER..(SourceIO.MAX_STATE + HEADER)) throw UserError(tr("invalid_backup_size"))
        val buffer = ByteBuffer.wrap(bytes)
        val id = ByteArray(8).also(buffer::get)
        val version=buffer.int
        if (!((id.contentEquals(magic)||id.contentEquals(LegacyBackup.V3))&&version==3 || id.contentEquals(v2)&&version==2)) throw UserError(tr("this_backup_format_is_not_supported"))
        val length = buffer.int
        if (length != bytes.size - HEADER) throw UserError(tr("the_backup_is_damaged_or_incomplete"))
        val checksum = ByteArray(32).also(buffer::get)
        val payload = bytes.copyOfRange(HEADER,bytes.size)
        if (!MessageDigest.isEqual(checksum, MessageDigest.getInstance("SHA-256").digest(payload))) throw UserError(tr("the_backup_is_damaged_your_current_data_is_unchanged"))
        return try{StateCodec.decode(if(version==3)SourceIO.read(java.util.zip.GZIPInputStream(payload.inputStream()),SourceIO.MAX_STATE)else payload)}catch(e:UserError){throw e}catch(_:Exception){throw UserError(tr("the_compressed_backup_is_damaged"))}
    }
}
