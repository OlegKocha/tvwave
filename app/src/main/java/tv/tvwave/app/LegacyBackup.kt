package tv.tvwave.app

/** Read-only identifiers of older backups. New installations and exports use TV Wave. */
internal object LegacyBackup {
    const val STATE_FORMAT = "hometv-state"
    const val EXTENSION = "hometvbackup"
    const val EPG_FORMAT = "HomeTV-EPG-1"
    val V1 = "HOMTVBK1".toByteArray(Charsets.US_ASCII)
    val V2 = "HOMTVBK2".toByteArray(Charsets.US_ASCII)
    val V3 = "HOMTVBK3".toByteArray(Charsets.US_ASCII)
}
