package nl.basverbeek.videoaudioconverter

import android.app.Application
import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val initialized: Boolean = false,
        val busy: Boolean = false,
        /** 0..100, of -1 voor "onbepaald" */
        val progress: Int = -1,
        val status: String = "",
        val log: String = "",
        val ytdlpVersion: String? = null,
    )

    private val app: Application get() = getApplication()

    private val _state = MutableStateFlow(UiState(status = str(R.string.status_initializing)))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var downloadJob: Job? = null
    @Volatile private var currentProcessId: String? = null
    @Volatile private var cancelRequested = false
    private val logLines = ArrayDeque<String>()

    init {
        viewModelScope.launch { initialize() }
    }

    private fun str(@StringRes id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    // ---------------------------------------------------------------- opstarten

    private suspend fun initialize() {
        try {
            withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
            }
            _state.update {
                it.copy(
                    initialized = true,
                    status = str(R.string.status_ready),
                    ytdlpVersion = YoutubeDL.getInstance().version(app)
                )
            }
            autoUpdateIfDue()
        } catch (e: Exception) {
            _state.update { it.copy(status = str(R.string.status_init_error, e.message ?: e.javaClass.simpleName)) }
        }
    }

    /** YouTube verandert vaak; daarom 1x per dag automatisch yt-dlp bijwerken. */
    private fun autoUpdateIfDue() {
        val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val last = prefs.getLong("last_ytdlp_update", 0L)
        val dayMs = 24L * 60 * 60 * 1000
        if (System.currentTimeMillis() - last > dayMs) {
            updateYtDlp(manual = false)
        }
    }

    // ---------------------------------------------------------------- yt-dlp bijwerken

    fun updateYtDlp(manual: Boolean) {
        if (!_state.value.initialized || _state.value.busy) return
        viewModelScope.launch {
            if (manual) _state.update { it.copy(busy = true, progress = -1, status = str(R.string.status_updating)) }
            try {
                val result = withContext(Dispatchers.IO) { YoutubeDL.getInstance().updateYoutubeDL(app) }
                val version = YoutubeDL.getInstance().version(app) ?: "?"
                app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                    .putLong("last_ytdlp_update", System.currentTimeMillis()).apply()
                _state.update {
                    it.copy(
                        ytdlpVersion = version,
                        status = if (!manual) it.status
                        else if (result == YoutubeDL.UpdateStatus.DONE) str(R.string.status_updated, version)
                        else str(R.string.status_up_to_date, version)
                    )
                }
            } catch (e: Exception) {
                if (manual) {
                    _state.update { it.copy(status = str(R.string.status_error, e.message ?: e.javaClass.simpleName)) }
                }
            } finally {
                if (manual) _state.update { it.copy(busy = false) }
            }
        }
    }

    // ---------------------------------------------------------------- downloaden

    fun download(url: String, format: MediaFormat, quality: QualityOption) {
        if (!_state.value.initialized || downloadJob?.isActive == true) return

        val processId = UUID.randomUUID().toString()
        currentProcessId = processId
        cancelRequested = false
        synchronized(logLines) { logLines.clear() }

        downloadJob = viewModelScope.launch {
            _state.update { it.copy(busy = true, progress = -1, status = str(R.string.status_starting), log = "") }
            val workDir = File(app.cacheDir, "download_$processId").apply { mkdirs() }

            try {
                val request = buildRequest(url, format, quality, workDir)

                withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().execute(
                        request,
                        processId = processId,
                        redirectErrorStream = false,
                        callback = { progress, etaSeconds, line -> onProgress(progress, etaSeconds, line) }
                    )
                }

                _state.update { it.copy(progress = -1, status = str(R.string.status_saving)) }

                val wantedExt = if (format == MediaFormat.MP4) "mp4" else "mp3"
                val allFiles = workDir.listFiles()?.filter {
                    it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl")
                } ?: emptyList()
                val files = allFiles.filter { it.extension.equals(wantedExt, true) }.ifEmpty { allFiles }
                if (files.isEmpty()) throw IllegalStateException(str(R.string.error_no_file))

                val saved = withContext(Dispatchers.IO) {
                    files.map { DownloadSaver.saveToDownloads(app, it) }
                }
                _state.update {
                    it.copy(progress = 100, status = str(R.string.status_done, saved.joinToString("\n")))
                }
            } catch (e: Exception) {
                val msg = if (cancelRequested || e is YoutubeDL.CanceledException) {
                    str(R.string.status_cancelled)
                } else {
                    appendLog("FOUT: ${e.message}")
                    str(R.string.status_error, friendlyError(e))
                }
                _state.update { it.copy(progress = -1, status = msg) }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { workDir.deleteRecursively() }
                currentProcessId = null
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun buildRequest(
        url: String,
        format: MediaFormat,
        quality: QualityOption,
        workDir: File
    ): YoutubeDLRequest = YoutubeDLRequest(url).apply {
        addOption("--no-playlist")
        addOption("--no-mtime")
        addOption("--newline")
        addOption("-o", File(workDir, "%(title).150B.%(ext)s").absolutePath)

        when (format) {
            MediaFormat.MP4 -> {
                addOption("-f", DownloadOptions.videoFormatSelector(quality.value))
                addOption("--merge-output-format", "mp4")
                addOption("--remux-video", "mp4")
            }
            MediaFormat.MP3 -> {
                addOption("-f", "ba/b")
                addOption("-x")
                addOption("--audio-format", "mp3")
                addOption("--audio-quality", quality.value ?: "0")
            }
        }
    }

    private fun onProgress(progress: Float, etaSeconds: Long, line: String) {
        if (line.isNotBlank()) appendLog(line)
        val pct = progress.toInt().coerceIn(-1, 100)
        val status = when {
            line.contains("[Merger]") || line.contains("[ExtractAudio]") ||
                line.contains("[VideoRemuxer]") || line.contains("[FFmpeg") -> str(R.string.status_processing)
            pct >= 0 -> str(R.string.status_downloading, pct, formatEta(etaSeconds))
            else -> _state.value.status
        }
        _state.update { it.copy(progress = if (status == str(R.string.status_processing)) -1 else pct, status = status) }
    }

    private fun appendLog(line: String) {
        val text = synchronized(logLines) {
            logLines.addLast(line.trim())
            while (logLines.size > 12) logLines.removeFirst()
            logLines.joinToString("\n")
        }
        _state.update { it.copy(log = text) }
    }

    private fun formatEta(seconds: Long): String = when {
        seconds < 0 -> "?"
        seconds < 60 -> "${seconds}s"
        else -> "${seconds / 60}m ${seconds % 60}s"
    }

    private fun friendlyError(e: Exception): String {
        val m = e.message ?: return e.javaClass.simpleName
        return when {
            m.contains("Unsupported URL", true) -> "deze link wordt niet ondersteund"
            m.contains("Video unavailable", true) -> "video is niet beschikbaar"
            m.contains("Private video", true) -> "dit is een privévideo"
            m.contains("Sign in to confirm", true) -> "site vraagt om in te loggen; probeer 'Downloader bijwerken'"
            m.contains("Unable to resolve host", true) || m.contains("network", true) -> "geen internetverbinding"
            else -> m.lineSequence().lastOrNull { it.isNotBlank() }?.take(200) ?: m.take(200)
        }
    }

    fun cancel() {
        val id = currentProcessId ?: return
        cancelRequested = true
        // Het yt-dlp-proces stoppen laat execute() met een fout terugkeren;
        // de coroutine ruimt daarna zelf netjes op (geen job.cancel() nodig).
        try {
            YoutubeDL.getInstance().destroyProcessById(id)
        } catch (e: Exception) {
            appendLog("Annuleren: ${e.message}")
        }
    }
}
