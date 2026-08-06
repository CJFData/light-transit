package com.thelightphone.lp3Keyboard.ui

fun isEmojiCodePoint(cp: Int): Boolean {
    // ZWJ and variation selector-16 are combiners, not standalone glyphs.
    if (cp == 0x200D || cp == 0xFE0F) return false
    return cp in 0x1F000..0x1FFFF ||  // Most modern emoji (supplementary plane)
            cp in 0x2300..0x23FF ||   // Misc Technical (⌚ ⌛ ⏰ …)
            cp in 0x2600..0x27BF ||   // Misc Symbols, Dingbats (☀ ✨ ❤ …)
            cp in 0x2B00..0x2BFF      // Misc Symbols & Arrows
}

fun parseEmojiString(allEmojis: String?): List<Int>? {
    if (allEmojis == null) return null
    val codePoints = mutableListOf<Int>()
    var i = 0
    while (i < allEmojis.length) {
        val cp = allEmojis.codePointAt(i)
        if (isEmojiCodePoint(cp)) codePoints.add(cp)
        i += Character.charCount(cp)
    }
    return codePoints
}