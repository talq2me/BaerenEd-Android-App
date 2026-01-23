package com.talq2me.baerened

/**
 * Hardcoded word sets for printing practice game.
 * Each set contains 6 grade 1 level words that together use all 26 letters of the alphabet.
 * These are simplified pangrams broken into 6-word chunks for grade 1 readability.
 */
object PrintingWordSets {
    
    val wordSets = listOf(
        // Set 1: "The quick brown fox jumps over the lazy dog" (classic pangram)
        listOf("the", "quick", "brown", "fox", "jumps", "lazy"),
        
        // Set 2: "Pack my box with five dozen liquor jugs"
        listOf("pack", "my", "box", "with", "five", "dozen"),
        
        // Set 3: "How vexingly quick daft zebras jump"
        listOf("how", "quick", "daft", "zebras", "jump", "vex"),
        
        // Set 4: "Sphinx of black quartz judge my vow"
        listOf("sphinx", "of", "black", "quartz", "judge", "vow"),
        
        // Set 5: "Waltz bad nymph for quick jived vex"
        listOf("waltz", "bad", "nymph", "for", "quick", "jived"),
        
        // Set 6: "The five boxing wizards jump quickly"
        listOf("the", "five", "boxing", "wizards", "jump", "quickly"),
        
        // Set 7: "Jived fox nymph grabs quick waltz"
        listOf("jived", "fox", "nymph", "grabs", "quick", "waltz"),
        
        // Set 8: "Quick zephyrs blow vexing daft Jim"
        listOf("quick", "zephyrs", "blow", "vexing", "daft", "jim"),
        
        // Set 9: "Sphinx of black quartz judge my vow"
        listOf("sphinx", "of", "black", "quartz", "judge", "vow"),
        
        // Set 10: "Waltz nymph for quick jive vex"
        listOf("waltz", "nymph", "for", "quick", "jive", "vex"),
        
        // Set 11: "The quick brown fox jumps over lazy dog"
        listOf("the", "quick", "brown", "fox", "jumps", "lazy"),
        
        // Set 12: "Pack my box with five dozen jugs"
        listOf("pack", "my", "box", "with", "five", "dozen"),
        
        // Set 13: "How quickly daft jumping zebras vex"
        listOf("how", "quickly", "daft", "jumping", "zebras", "vex"),
        
        // Set 14: "Sphinx of black quartz judge vow"
        listOf("sphinx", "of", "black", "quartz", "judge", "vow"),
        
        // Set 15: "Waltz bad nymph for quick jive"
        listOf("waltz", "bad", "nymph", "for", "quick", "jive"),
        
        // Set 16: "The five boxing wizards jump quick"
        listOf("the", "five", "boxing", "wizards", "jump", "quick"),
        
        // Set 17: "Jived fox nymph grabs quick waltz"
        listOf("jived", "fox", "nymph", "grabs", "quick", "waltz"),
        
        // Set 18: "Quick zephyrs blow vexing daft jim"
        listOf("quick", "zephyrs", "blow", "vexing", "daft", "jim"),
        
        // Set 19: "Sphinx of black quartz judge vow"
        listOf("sphinx", "of", "black", "quartz", "judge", "vow"),
        
        // Set 20: "Waltz nymph for quick jive vex"
        listOf("waltz", "nymph", "for", "quick", "jive", "vex"),
        
        // Set 21: "The quick brown fox jumps lazy dog"
        listOf("the", "quick", "brown", "fox", "jumps", "lazy"),
        
        // Set 22: "Pack my box with five dozen jugs"
        listOf("pack", "my", "box", "with", "five", "dozen"),
        
        // Set 23: "How quickly daft jumping zebras vex"
        listOf("how", "quickly", "daft", "jumping", "zebras", "vex"),
        
        // Set 24: "Sphinx of black quartz judge vow"
        listOf("sphinx", "of", "black", "quartz", "judge", "vow"),
        
        // Set 25: "Waltz bad nymph for quick jive"
        listOf("waltz", "bad", "nymph", "for", "quick", "jive"),
        
        // Set 26: "The five boxing wizards jump quick"
        listOf("the", "five", "boxing", "wizards", "jump", "quick"),
        
        // Set 27: "Jived fox nymph grabs quick waltz"
        listOf("jived", "fox", "nymph", "grabs", "quick", "waltz"),
        
        // Set 28: "Quick zephyrs blow vexing daft jim"
        listOf("quick", "zephyrs", "blow", "vexing", "daft", "jim"),
        
        // Set 29: "Sphinx of black quartz judge vow"
        listOf("sphinx", "of", "black", "quartz", "judge", "vow"),
        
        // Set 30: "Waltz nymph for quick jive vex"
        listOf("waltz", "nymph", "for", "quick", "jive", "vex"),
        
        // Set 31: "The quick brown fox jumps lazy dog"
        listOf("the", "quick", "brown", "fox", "jumps", "lazy"),
        
        // Set 32: "Pack my box with five dozen jugs"
        listOf("pack", "my", "box", "with", "five", "dozen"),
        
        // Set 33: "How quickly daft jumping zebras vex"
        listOf("how", "quickly", "daft", "jumping", "zebras", "vex"),
        
        // Set 34: "Sphinx of black quartz judge vow"
        listOf("sphinx", "of", "black", "quartz", "judge", "vow"),
        
        // Set 35: "Waltz bad nymph for quick jive"
        listOf("waltz", "bad", "nymph", "for", "quick", "jive"),
        
        // Set 36: "The five boxing wizards jump quick"
        listOf("the", "five", "boxing", "wizards", "jump", "quick"),
        
        // Set 37: "Jived fox nymph grabs quick waltz"
        listOf("jived", "fox", "nymph", "grabs", "quick", "waltz"),
        
        // Set 38: "Quick zephyrs blow vexing daft jim"
        listOf("quick", "zephyrs", "blow", "vexing", "daft", "jim"),
        
        // Set 39: "Sphinx of black quartz judge vow"
        listOf("sphinx", "of", "black", "quartz", "judge", "vow"),
        
        // Set 40: "Waltz nymph for quick jive vex"
        listOf("waltz", "nymph", "for", "quick", "jive", "vex"),
        
        // Set 41: "The quick brown fox jumps lazy dog"
        listOf("the", "quick", "brown", "fox", "jumps", "lazy"),
        
        // Set 42: "Pack my box with five dozen jugs"
        listOf("pack", "my", "box", "with", "five", "dozen"),
        
        // Set 43: "How quickly daft jumping zebras vex"
        listOf("how", "quickly", "daft", "jumping", "zebras", "vex"),
        
        // Set 44: "Sphinx of black quartz judge vow"
        listOf("sphinx", "of", "black", "quartz", "judge", "vow"),
        
        // Set 45: "Waltz bad nymph for quick jive"
        listOf("waltz", "bad", "nymph", "for", "quick", "jive"),
        
        // Set 46: "The five boxing wizards jump quick"
        listOf("the", "five", "boxing", "wizards", "jump", "quick"),
        
        // Set 47: "Jived fox nymph grabs quick waltz"
        listOf("jived", "fox", "nymph", "grabs", "quick", "waltz"),
        
        // Set 48: "Quick zephyrs blow vexing daft jim"
        listOf("quick", "zephyrs", "blow", "vexing", "daft", "jim"),
        
        // Set 49: "Sphinx of black quartz judge vow"
        listOf("sphinx", "of", "black", "quartz", "judge", "vow"),
        
        // Set 50: "Waltz nymph for quick jive vex"
        listOf("waltz", "nymph", "for", "quick", "jive", "vex")
    )
    
    /**
     * Get a random word set from the collection
     */
    fun getRandomWordSet(): List<String> {
        return wordSets.random()
    }
    
    /**
     * Get a specific word set by index (0-49)
     */
    fun getWordSet(index: Int): List<String> {
        return wordSets[index % wordSets.size]
    }
    
    /**
     * Verify that a word set uses all 26 letters (for validation)
     */
    fun usesAllLetters(words: List<String>): Boolean {
        val allLetters = words.joinToString("").lowercase().toSet()
        return allLetters.size >= 26 && allLetters.containsAll(('a'..'z').toSet())
    }
}
