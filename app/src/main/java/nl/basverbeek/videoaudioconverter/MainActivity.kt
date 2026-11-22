package nl.basverbeek.videoaudioconverter

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.basverbeek.videoaudioconverter.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private var currentQualities: List<QualityOption> = DownloadOptions.videoQualities
    private var selectedQuality: QualityOption = DownloadOptions.videoQualities.first()

    // In-app updater (GitHub Releases)
    private var pendingRelease: Updater.Release? = null
    private var appUpdateBusy = false

    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startDownload() else toast(getString(R.string.error_permission))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupQualityList(selectedFormat())
        binding.formatToggle.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) setupQualityList(selectedFormat())
        }
        binding.qualityInput.setOnItemClickListener { _, _, position, _ ->
            selectedQuality = currentQualities[position]
        }

        binding.btnPaste.setOnClickListener { pasteFromClipboard() }
        binding.btnDownload.setOnClickListener { onDownloadClicked() }
        binding.btnCancel.setOnClickListener { viewModel.cancel() }
        binding.btnUpdate.setOnClickListener { viewModel.updateYtDlp(manual = true) }
        binding.btnAppUpdate.setOnClickListener { onAppUpdateButton() }
        binding.appVersionText.text = getString(R.string.app_version, BuildConfig.VERSION_NAME)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }

        if (savedInstanceState == null) {
            handleShareIntent(intent)
            checkForAppUpdate(manual = false)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    // ---------------------------------------------------------------- UI

    private fun selectedFormat(): MediaFormat =
        if (binding.formatToggle.checkedButtonId == R.id.btnMp3) MediaFormat.MP3 else MediaFormat.MP4

    private fun setupQualityList(format: MediaFormat) {
        currentQualities = DownloadOptions.qualitiesFor(format)
        selectedQuality = currentQualities.first()
        val labels = currentQualities.map { it.label }
        binding.qualityInput.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))
        binding.qualityInput.setText(selectedQuality.label, false)
    }

    private fun render(state: MainViewModel.UiState) {
        binding.statusText.text = state.status
        binding.logText.text = state.log
        binding.versionText.text = getString(R.string.ytdlp_version, state.ytdlpVersion ?: "…")

        val controlsEnabled = state.initialized && !state.busy
        binding.btnDownload.isEnabled = controlsEnabled
        binding.btnUpdate.isEnabled = controlsEnabled
        binding.formatToggle.isEnabled = !state.busy
        binding.btnMp4.isEnabled = !state.busy
        binding.btnMp3.isEnabled = !state.busy
        binding.qualityLayout.isEnabled = !state.busy
        binding.urlLayout.isEnabled = !state.busy
        binding.btnPaste.isEnabled = !state.busy
        binding.btnCancel.visibility = if (state.busy) View.VISIBLE else View.GONE

        val showProgress = state.busy || !state.initialized
        binding.progress.visibility = if (showProgress) View.VISIBLE else View.INVISIBLE
        if (showProgress) {
            if (state.progress < 0) {
                binding.progress.isIndeterminate = true
            } else {
                binding.progress.isIndeterminate = false
                binding.progress.setProgressCompat(state.progress, true)
            }
        }

        // Scherm aan houden tijdens downloaden, zodat Android de app niet pauzeert.
        if (state.busy) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ---------------------------------------------------------------- acties

    private fun onDownloadClicked() {
        if (!viewModel.state.value.initialized) {
            toast(getString(R.string.error_not_ready))
            return
        }
        if (extractUrl(binding.urlInput.text?.toString().orEmpty()) == null) {
            binding.urlLayout.error = getString(R.string.error_no_url)
            return
        }
        binding.urlLayout.error = null

        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            startDownload()
        }
    }

    private fun startDownload() {
        val url = extractUrl(binding.urlInput.text?.toString().orEmpty()) ?: return
        viewModel.download(url, selectedFormat(), selectedQuality)
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) {
            toast(getString(R.string.error_clipboard_empty))
            return
        }
        binding.urlInput.setText(extractUrl(text) ?: text.trim())
        binding.urlLayout.error = null
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        extractUrl(text)?.let {
            binding.urlInput.setText(it)
            binding.urlLayout.error = null
        }
    }

    private fun extractUrl(text: String): String? =
        Regex("""https?://\S+""").find(text)?.value?.trimEnd('.', ',', ')', '"', '\'')

    // ---------------------------------------------------------------- app-updates

    private fun onAppUpdateButton() {
        if (appUpdateBusy) return
        val release = pendingRelease
        if (release != null) startAppUpdate(release) else checkForAppUpdate(manual = true)
    }

    private fun showAppUpdateStatus(text: String) {
        binding.appUpdateStatus.text = text
        binding.appUpdateStatus.visibility = View.VISIBLE
    }

    private fun checkForAppUpdate(manual: Boolean) {
        appUpdateBusy = true
        if (manual) showAppUpdateStatus(getString(R.string.app_update_checking))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Updater.fetchLatest() } }
            appUpdateBusy = false
            result.onSuccess { release ->
                if (release != null && Updater.isNewer(release.version, BuildConfig.VERSION_NAME)) {
                    pendingRelease = release
                    val notes = release.notes.trim()
                    showAppUpdateStatus(
                        getString(R.string.app_update_available, release.version) +
                            if (notes.isNotEmpty()) "\n\n$notes" else ""
                    )
                    binding.btnAppUpdate.text = getString(R.string.app_update_button, release.version)
                } else if (manual) {
                    showAppUpdateStatus(getString(R.string.app_update_latest))
                }
            }.onFailure {
                if (manual) showAppUpdateStatus(getString(R.string.app_update_check_failed, it.message ?: ""))
            }
        }
    }

    private fun startAppUpdate(release: Updater.Release) {
        appUpdateBusy = true
        binding.btnAppUpdate.isEnabled = false
        binding.appUpdateProgress.visibility = View.VISIBLE
        binding.appUpdateProgress.isIndeterminate = false
        binding.appUpdateProgress.progress = 0
        showAppUpdateStatus(getString(R.string.app_update_downloading, 0))

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    Updater.download(this@MainActivity, release) { done, total ->
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            runOnUiThread {
                                binding.appUpdateProgress.progress = pct
                                binding.appUpdateStatus.text = getString(R.string.app_update_downloading, pct)
                            }
                        }
                    }
                }
            }
            appUpdateBusy = false
            binding.btnAppUpdate.isEnabled = true
            binding.appUpdateProgress.visibility = View.GONE
            result.onSuccess { apk ->
                val started = Updater.install(this@MainActivity, apk)
                showAppUpdateStatus(
                    getString(if (started) R.string.app_update_install_started else R.string.app_update_allow_unknown)
                )
            }.onFailure {
                showAppUpdateStatus(getString(R.string.app_update_download_failed, it.message ?: ""))
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
