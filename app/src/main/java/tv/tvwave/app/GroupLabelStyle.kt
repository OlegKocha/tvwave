package tv.tvwave.app

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak

/** Playlist group names keep their own language even when the UI is switched to English. */
fun groupLabelStyle(base:TextStyle,label:String,language:String):TextStyle = base.copy(
    localeList=LocaleList(if(label.any{it in 'А'..'я'||it=='Ё'||it=='ё'}) "ru" else language),
    hyphens=Hyphens.Auto,
    lineBreak=LineBreak.Paragraph
)
