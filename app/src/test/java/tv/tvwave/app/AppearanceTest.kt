package tv.tvwave.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppearanceTest {
    @Test fun everyFontSizeSurvivesPortableBackup(){
        for(size in 60..130 step 5){
            val state=AppState(fontPercent=size,large=size>=120)
            assertEquals(state,BackupFile.decode(BackupFile.encode(state)))
        }
    }
    @Test fun oldLargeSettingMigratesAndInvalidValuesAreRejected(){
        for(large in listOf(false,true)){
            val old=JSONObject(String(StateCodec.encode(AppState(large=large)))).apply{remove("fontPercent")}
            assertEquals(if(large)120 else 100,StateCodec.decode(old.toString().toByteArray()).fontPercent)
        }
        for(value in listOf<Any>(0,55,135,101,100.5,"100")){
            val invalid=JSONObject(String(StateCodec.encode(AppState()))).put("fontPercent",value)
            try{StateCodec.decode(invalid.toString().toByteArray());fail("Accepted $value")}catch(_:UserError){}
        }
    }
}
