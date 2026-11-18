package tv.tvwave.app

import java.text.Normalizer
import java.util.Locale

/** Fallback for provider labels after the normal XMLTV ID and display-name lookup. */
internal class EpgNameVariants(guide: List<EpgChannel>) {
    private val exact = mutableMapOf<String, MutableSet<String>>()
    private val base = mutableMapOf<String, MutableSet<String>>()
    private val countries = mutableMapOf<String, Set<String>>()
    private val byId = guide.associateBy { it.id }

    init {
        for (channel in guide) {
            val tags = mutableSetOf<String>()
            val namedCountries = mutableSetOf<String>()
            val idCountry = channel.id.substringAfterLast('-', "").uppercase(Locale.ROOT)
            if (idCountry in countryCodes) tags.add(canonicalCountry(idCountry))
            addNamedCountries(channel.id, namedCountries)
            for (alias in channel.names) {
                val tag = countrySuffix.find(alias)
                tag?.let { tags.add(country(it)) }
                addNamedCountries(alias, namedCountries)
                val title = if (tag == null) alias else alias.take(tag.range.first).trim()
                val name = clean(title)
                index(exact, name, channel.id)
                index(base, quality.replace(name, ""), channel.id)
            }
            countries[channel.id] = tags.ifEmpty { namedCountries }
        }
    }

    fun match(label: String): String? {
        val name = clean(label)
        val tag = countrySuffix.find(name)
        val region = tag?.let(::country)
        val title = clean(if (tag == null) name else name.take(tag.range.first))
        val simple = quality.replace(title, "")
        // Keep the locality inside parentheses. Only a quality marker before it may disappear.
        val qualified = qualityBeforeLocality.replace(title, " ")
        val candidates = exact[key(title)] ?: exact[key(qualified)] ?: base[key(simple)]
        val reviewed = reviewedAliases[region.orEmpty() to key(simple)]
        if (reviewed != null && (candidates.isNullOrEmpty() || candidates == setOf(reviewed.first))) {
            val channel = byId[reviewed.first]
            val sourceCountries = countries[reviewed.first].orEmpty()
            val compatible = sourceCountries.isEmpty() || sourceCountries.singleOrNull() == region
            if (compatible && channel != null && channel.names.any { key(it) == key(reviewed.second) }) return channel.id
        }
        if (candidates == null) return null
        if (region != null) return candidates.singleOrNull { countries[it] == setOf(region) }
        val id = candidates.singleOrNull() ?: return null
        val known = countries[id].orEmpty()
        if (known.isEmpty()) return id
        val stated = mutableSetOf<String>()
        addNamedCountries(title, stated)
        return id.takeIf { known.size == 1 && stated.containsAll(known) }
    }

    private companion object {
        val countryCodes = "AL AM AT AU AZ BG BY CN CZ DE EE ES FR GB GR HU IL IT JP KZ MX NL PK PL PT RO RS RU SE TR UA UK US".split(' ').toSet()
        val codes = countryCodes.joinToString("|")
        val countrySuffix = Regex("""(?:\s+($codes)|\s*\[($codes)\])$""")
        fun canonicalCountry(code: String) = if (code == "GB") "UK" else code
        fun country(tag: MatchResult) = canonicalCountry(tag.groupValues[1].ifEmpty { tag.groupValues[2] })

        val countryWords = mapOf(
            "россия" to "RU", "russia" to "RU", "belarus" to "BY", "беларусь" to "BY", "белоруссия" to "BY",
            "ukraine" to "UA", "украина" to "UA", "україна" to "UA", "kazakhstan" to "KZ",
            "казахстан" to "KZ", "қазақстан" to "KZ", "britain" to "UK", "usa" to "US", "сша" to "US",
            "germany" to "DE", "германия" to "DE", "japan" to "JP", "япония" to "JP", "poland" to "PL",
            "польша" to "PL", "france" to "FR", "франция" to "FR"
        )
        val words = Regex("""\p{L}+""")
        fun addNamedCountries(text: String, result: MutableSet<String>) {
            val lower = text.lowercase(Locale.ROOT)
            for (word in words.findAll(lower)) countryWords[word.value]?.let(result::add)
            if ("united kingdom" in lower) result.add("UK")
        }

        val fps = Regex("""\b(UHD|FHD|HD)\s+(?:50|60)(?:\s*fps)?\b""", RegexOption.IGNORE_CASE)
        val original = Regex("""\s+orig$""", RegexOption.IGNORE_CASE)
        const val formats = "UHD|FHD|HD|SD|4K|8K|HDR|orig|(?:50|60)\\s*fps"
        val quality = Regex("""(?:\s+(?:$formats))+$""", RegexOption.IGNORE_CASE)
        val qualityBeforeLocality = Regex("""(?:\s+(?:$formats))+\s+(?=\()""", RegexOption.IGNORE_CASE)
        fun clean(s: String) = original.replace(fps.replace(s.trim(), "$1"), "").trim()
        fun key(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            .replace('ё','е').filter { it.isLetterOrDigit() || it == '+' }
        fun index(map: MutableMap<String, MutableSet<String>>, name: String, id: String) {
            val k = key(name)
            if (k.isNotEmpty()) map.getOrPut(k) { mutableSetOf() }.add(id)
        }

        // Reviewed provider spellings. Require both the XMLTV ID and an identifying alias,
        // since XMLTV IDs are local to a source.
        val reviewedAliases = mapOf(
            ("BY" to "яснае") to ("minsk-tv" to "Яснае ТВ"),
            ("KZ" to "qadamtv") to ("qadam-kz" to "Qadam"),
            ("KZ" to "седьмойканал") to ("7kanal-kz" to "7 канал [KZ]"),
            ("KZ" to "astanatv") to ("astana" to "Astana"),
            ("KZ" to "otyrar") to ("otyrar-tv" to "Отырар TV"),
            ("KZ" to "31арна") to ("31-kanal-kz" to "31 канал [KZ]"),
            ("UA" to "інтер") to ("inter-ua" to "Интер"),
            ("UA" to "бігуді") to ("bigudi" to "Бигуди"),
            ("UA" to "enterфільм") to ("enter-film" to "Enter-фильм"),
            ("UA" to "пiксельtv") to ("pixel" to "Пиксель"),
            ("UA" to "арміяtv") to ("armia-tv-ua" to "Армія ТБ"),
            ("UA" to "надiятв") to ("nadia" to "Надія"),
            ("" to "фениксплюскино") to ("feniks-plus-kino" to "Феникс+ Кино")
        )
    }
}
