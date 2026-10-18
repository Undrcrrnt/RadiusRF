package app.radiusrf.core.sessions

object ExportNames {
    fun clean(raw: String, fallback: String): String {
        val trimmed = raw.trim()
            .replace(Regex("""[\\/:*?"<>|\u0000]"""), "_")
            .trim('.', ' ')
            .take(120)
        return trimmed.ifBlank { fallback }
    }

    fun withExtension(name: String, extension: String): String {
        val ext = ".$extension"
        return if (name.endsWith(ext, ignoreCase = true)) name else name + ext
    }
}
