package cn.listenly.app.importer

/** In-memory folder for parser tests. Audio files map to a decoded duration (null = undecodable). */
class FakeSource(
    private val texts: Map<String, String>,
    private val audio: Map<String, Double?> = mapOf("paper.mp3" to 100.0),
) : ImportSource {
    override fun files(): List<String> = texts.keys.toList() + audio.keys
    override fun readText(path: String): String = texts.getValue(path)
    override fun audioDuration(path: String): Double? = audio[path]
    override fun audioLocation(path: String): String = "/data/imports/x/$path"
}
