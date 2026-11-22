package nl.basverbeek.videoaudioconverter

enum class MediaFormat { MP4, MP3 }

/** Een kwaliteitskeuze zoals die in de keuzelijst staat. */
data class QualityOption(val label: String, val value: String?)

object DownloadOptions {

    /** value = maximale hoogte in pixels (null = beste beschikbaar) */
    val videoQualities = listOf(
        QualityOption("Beste kwaliteit", null),
        QualityOption("1080p (Full HD)", "1080"),
        QualityOption("720p (HD)", "720"),
        QualityOption("480p", "480"),
        QualityOption("360p (klein bestand)", "360"),
    )

    /** value = yt-dlp --audio-quality */
    val audioQualities = listOf(
        QualityOption("320 kbps (beste)", "320K"),
        QualityOption("192 kbps", "192K"),
        QualityOption("128 kbps (klein bestand)", "128K"),
    )

    fun qualitiesFor(format: MediaFormat): List<QualityOption> =
        if (format == MediaFormat.MP4) videoQualities else audioQualities

    /** yt-dlp formaatselectie voor MP4, met voorkeur voor H.264/AAC (speelt overal af). */
    fun videoFormatSelector(maxHeight: String?): String {
        val h = if (maxHeight != null) "[height<=$maxHeight]" else ""
        return listOf(
            "bv*${h}[ext=mp4][vcodec^=avc]+ba[ext=m4a]",
            "bv*${h}[ext=mp4]+ba[ext=m4a]",
            "b${h}[ext=mp4]",
            "bv*${h}+ba",
            "b${h}",
            "b",
        ).joinToString("/")
    }
}
