package tv.tvwave.app

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Portable format v1: authenticated header, PBKDF2-HMAC-SHA1/1.3M, AES-256-GCM.
 * SHA1 is used as a PRF, not as a collision-resistant signature. Available on API23. */
object BackupCrypto {
    private val magic = LegacyBackup.V1
    private const val ITERATIONS = 1_300_000
    private const val HEADER = 44
    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try { val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
            try { SecretKeySpec(bytes,"AES") } finally { bytes.fill(0) }
        } finally { spec.clearPassword() }
    }
    fun encrypt(plain: ByteArray, password: CharArray): ByteArray {
        if (password.size < 8) throw UserError(tr("the_backup_password_must_contain_at_least_8_characters"))
        require(plain.size <= SourceIO.MAX_STATE)
        val random = SecureRandom(); val salt = ByteArray(16).also(random::nextBytes); val iv = ByteArray(12).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER).put(magic).putInt(1).putInt(ITERATIONS).put(salt).put(iv).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key(password,salt),GCMParameterSpec(128,iv)); cipher.updateAAD(header)
        return header + cipher.doFinal(plain)
    }
    fun decrypt(bytes: ByteArray, password: CharArray): ByteArray {
        if (bytes.size !in (HEADER + 16)..(SourceIO.MAX_STATE + HEADER + 16)) throw UserError(tr("invalid_backup_size"))
        val header = bytes.copyOfRange(0,HEADER); val buffer = ByteBuffer.wrap(header)
        val id = ByteArray(8).also(buffer::get)
        if (!id.contentEquals(magic) || buffer.int != 1 || buffer.int != ITERATIONS) throw UserError(tr("this_backup_format_is_not_supported"))
        val salt = ByteArray(16).also(buffer::get); val iv = ByteArray(12).also(buffer::get)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE,key(password,salt),GCMParameterSpec(128,iv)); cipher.updateAAD(header)
            cipher.doFinal(bytes,HEADER,bytes.size-HEADER)
        } catch (_: Exception) { throw UserError(tr("incorrect_password_or_damaged_backup_your_current_data_is_unchanged")) }
    }
}
