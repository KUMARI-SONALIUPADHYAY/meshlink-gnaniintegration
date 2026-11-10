package com.meshlink.app.core.ai

/** Timbre v2.5 voice/language pairs copied from Gnani's official voice catalog. */
object TimbreCatalog {
    val voicesByLanguage: Map<String, List<String>> = linkedMapOf(
        "en-IN" to listOf("Kaveri", "Trupti", "Devika", "Pranav", "Shlok", "Girish"),
        "hi-IN" to listOf("Nalini", "Bhavna", "Yashvi", "Urmila", "Jwala", "Chitra", "Ambuja", "Deepak", "Roopesh", "Vikrant", "Hemraj", "Jalaj", "Omkar"),
        "hi-en" to listOf("Poorvi"),
        "ta-IN" to listOf("Asmita", "Trisha", "Brinda", "Vedika", "Noopur"),
        "te-IN" to listOf("Suhana", "Lehara", "Lavanya", "Yukti", "Varuni"),
        "kn-IN" to listOf("Saanvi", "Kavin"),
        "ml-IN" to listOf("Reshma", "Riyaan"),
        "mr-IN" to listOf("Zahira", "Ishaan"),
        "bn-IN" to listOf("Kirra", "Dhruva"),
        "gu-IN" to listOf("Falak", "Veera"),
        "pa-IN" to listOf("Mehuli", "Zayan")
    )

    val languages: List<Pair<String, String>> = listOf(
        "hi-IN" to "Hindi", "en-IN" to "English", "ta-IN" to "Tamil",
        "te-IN" to "Telugu", "kn-IN" to "Kannada", "ml-IN" to "Malayalam",
        "mr-IN" to "Marathi", "pa-IN" to "Punjabi", "bn-IN" to "Bengali",
        "gu-IN" to "Gujarati", "hi-en" to "Hinglish"
    )

    fun voicesFor(language: String): List<String> = voicesByLanguage[language].orEmpty()
    fun isSupported(language: String, voice: String): Boolean = voice in voicesFor(language)
}
