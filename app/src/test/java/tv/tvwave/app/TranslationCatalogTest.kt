package tv.tvwave.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TranslationCatalogTest {
    private fun pack(code:String)=LanguagePack.parse(File("src/main/assets/i18n/$code.json").readText())
    @Test fun bundledCataloguesHaveEveryKeyAndMatchingParameters(){
        val en=pack("en");val ru=pack("ru")
        assertEquals(en.strings.keys,ru.strings.keys)
        en.strings.forEach{(key,value)->assertEquals(key,LanguagePack.tokens(value),LanguagePack.tokens(ru.strings.getValue(key)))}
    }
    @Test fun languageNeedsOnlyJsonAndFormattingDoesNotInterpretUserContent(){
        val pack=LanguagePack.parse("""{"locale":"de","nativeName":"Deutsch","strings":{"welcome":"{p2}: {p1}"}}""")
        val en=LanguagePack("en","English",mapOf("welcome" to "{p1}: {p2}"))
        assertEquals("Name: {p2}",pack.text("welcome",en,"{p2}","Name"))
    }
    @Test fun missingAndInvalidPlaceholdersFallBackToEnglish(){
        val en=LanguagePack("en","English",mapOf("hello" to "Hello {p1}"))
        assertEquals("Hello Alex",LanguagePack("de","Deutsch",emptyMap()).text("hello",en,"Alex"))
        assertEquals("Hello Alex",LanguagePack("de","Deutsch",mapOf("hello" to "Hallo {p2}")).text("hello",en,"Alex"))
    }
    @Test fun translationMigrationPreservesBomAndExistingChannelIds(){
        val id="00000000-0000-0000-0000-000000000001"
        val raw="\uFEFF#EXTM3U\n#EXTINF:-1 tvg-id=\"demo\",Demo\nhttps://example.test/live\n"
        val c=M3u.parse(raw,id).single()
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest("id:demo\u00000".toByteArray()).joinToString(""){"%02x".format(it)}
        assertEquals("$id:$hash",c.id)
        assertEquals("#EXTM3U",SourceIO.text("\uFEFF#EXTM3U".toByteArray()))
    }
    @Test fun backupRetainsLocaleAndAcceptsBackupsWithoutIt(){
        val original=AppState(uiLanguage="pt-BR",fontPercent=85)
        val bytes=BackupFile.encode(original)
        assertEquals("pt-BR",BackupFile.decode(bytes).uiLanguage)
        assertEquals(85,BackupFile.decode(bytes).fontPercent)
        val old=org.json.JSONObject(String(StateCodec.encode(original))).apply{remove("uiLanguage")}.toString().toByteArray()
        assertEquals("",StateCodec.decode(old).uiLanguage)
    }
}
