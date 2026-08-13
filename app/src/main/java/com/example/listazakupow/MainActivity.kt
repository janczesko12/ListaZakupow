package com.example.listazakupow

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material3.Switch
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.listazakupow.components.UserHeader
import com.example.listazakupow.ui.theme.ListaZakupowTheme
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import java.io.File
import java.io.IOException

// =============================================================
// STAN POBIERANIA AKTUALIZACJI
// =============================================================

private object UpdateDownloadState {

    var isDownloading by mutableStateOf(false)

    var progress by mutableStateOf(0)

    var downloadedBytes by mutableStateOf(0L)

    var totalBytes by mutableStateOf(-1L)

    var status by mutableStateOf("")

    var error by mutableStateOf<String?>(null)

    fun start() {
        isDownloading = true
        progress = 0
        downloadedBytes = 0L
        totalBytes = -1L
        status = "Pobieranie aktualizacji..."
        error = null
    }

    fun update(
        downloaded: Long,
        total: Long
    ) {

        downloadedBytes = downloaded
        totalBytes = total

        progress =
            if (total > 0) {
                ((downloaded * 100L) / total)
                    .toInt()
                    .coerceIn(0, 100)
            } else {
                0
            }

        status =
            if (total > 0) {
                "Pobieranie aktualizacji..."
            } else {
                "Pobieranie aktualizacji..."
            }
    }

    fun preparing() {
        progress = 100
        status = "Przygotowywanie instalacji..."
    }

    fun finish() {
        isDownloading = false
    }
}


// =============================================================
// AUTOMATYCZNE SPRAWDZANIE AKTUALIZACJI
// =============================================================

private const val UPDATE_PREFS = "lista_zakupow_updates"
private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
private const val UPDATE_CHECK_INTERVAL = 7L * 24L * 60L * 60L * 1000L

private const val GITHUB_OWNER = "janczesko12"
private const val GITHUB_REPO = "ListaZakupow"

private data class GitHubRelease(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String?
)

private fun extractReleaseVersion(tagName: String): Int? {
    val number = Regex("""\d+""").find(tagName)?.value ?: return null
    return number.toIntOrNull()
}

private fun checkGitHubLatestRelease(
    onResult: (GitHubRelease?) -> Unit
) {
    Thread {
        var connection: HttpURLConnection? = null

        try {
            android.util.Log.d(
                "ListaZakupowUpdate",
                "Rozpoczynam sprawdzanie aktualizacji..."
            )

            val url = URL(
                "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
            )

            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.useCaches = false

            connection.setRequestProperty(
                "Accept",
                "application/vnd.github+json"
            )

            connection.setRequestProperty(
                "User-Agent",
                "ListaZakupow-Android"
            )

            val responseCode = connection.responseCode

            android.util.Log.d(
                "ListaZakupowUpdate",
                "GitHub HTTP: $responseCode"
            )

            if (responseCode !in 200..299) {
                onResult(null)
                return@Thread
            }

            val response = connection.inputStream
                .bufferedReader()
                .use { it.readText() }

            android.util.Log.d(
                "ListaZakupowUpdate",
                "Odpowiedź GitHub otrzymana"
            )

            val tagName = Regex(
                """"tag_name"\s*:\s*"([^"]+)"""
            ).find(response)
                ?.groupValues
                ?.getOrNull(1)

            android.util.Log.d(
                "ListaZakupowUpdate",
                "tag_name = $tagName"
            )

            if (tagName.isNullOrBlank()) {
                onResult(null)
                return@Thread
            }

            val versionCode = extractReleaseVersion(tagName)

            if (versionCode == null) {
                android.util.Log.e(
                    "ListaZakupowUpdate",
                    "Nie udało się odczytać numeru wersji z: $tagName"
                )
                onResult(null)
                return@Thread
            }

            val versionName = tagName.removePrefix("v")

            val apkDownloadUrl = Regex(
                """"browser_download_url"\s*:\s*"([^"]+\.apk)""",
                RegexOption.IGNORE_CASE
            ).find(response)
                ?.groupValues
                ?.getOrNull(1)

            android.util.Log.d(
                "ListaZakupowUpdate",
                "GitHub versionCode = $versionCode"
            )

            android.util.Log.d(
                "ListaZakupowUpdate",
                "GitHub versionName = $versionName"
            )

            android.util.Log.d(
                "ListaZakupowUpdate",
                "APK = $apkDownloadUrl"
            )

            onResult(
                GitHubRelease(
                    versionCode = versionCode,
                    versionName = versionName,
                    downloadUrl = apkDownloadUrl
                )
            )

        } catch (e: Exception) {
            android.util.Log.e(
                "ListaZakupowUpdate",
                "Błąd sprawdzania aktualizacji",
                e
            )
            onResult(null)

        } finally {
            try {
                connection?.disconnect()
            } catch (_: Exception) {
            }
        }
    }.start()
}

private fun shouldCheckForUpdate(context: Context): Boolean {
    val prefs = context.getSharedPreferences(
        UPDATE_PREFS,
        Context.MODE_PRIVATE
    )

    val lastCheck = prefs.getLong(
        KEY_LAST_UPDATE_CHECK,
        0L
    )

    return System.currentTimeMillis() - lastCheck >= UPDATE_CHECK_INTERVAL
}

private fun markUpdateCheck(context: Context) {
    context.getSharedPreferences(
        UPDATE_PREFS,
        Context.MODE_PRIVATE
    )
        .edit()
        .putLong(
            KEY_LAST_UPDATE_CHECK,
            System.currentTimeMillis()
        )
        .apply()
}

private fun downloadAndInstallUpdate(
    context: Context,
    downloadUrl: String
) {
    try {
        val downloadManager =
            context.getSystemService(
                Context.DOWNLOAD_SERVICE
            ) as DownloadManager

        UpdateDownloadState.start()

        val request =
            DownloadManager.Request(
                Uri.parse(downloadUrl)
            )
                .setTitle(
                    "Lista Zakupów — aktualizacja"
                )
                .setDescription(
                    "Pobieranie nowej wersji aplikacji"
                )
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "ListaZakupow-update.apk"
                )
                .setMimeType(
                    "application/vnd.android.package-archive"
                )
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

        val downloadId =
            downloadManager.enqueue(request)

        Toast.makeText(
            context,
            "Pobieranie aktualizacji rozpoczęte.",
            Toast.LENGTH_SHORT
        ).show()

        // Śledzimy pobieranie co 250 ms i aktualizujemy pasek w aplikacji.
        val handler =
            android.os.Handler(
                android.os.Looper.getMainLooper()
            )

        val progressRunnable =
            object : Runnable {

                override fun run() {
                    try {
                        val query =
                            DownloadManager.Query()
                                .setFilterById(downloadId)

                        val cursor =
                            downloadManager.query(query)

                        cursor.use {
                            if (!it.moveToFirst()) {
                                UpdateDownloadState.finish()
                                Toast.makeText(
                                    context,
                                    "Nie znaleziono pobierania.",
                                    Toast.LENGTH_LONG
                                ).show()
                                return
                            }

                            val status =
                                it.getInt(
                                    it.getColumnIndexOrThrow(
                                        DownloadManager.COLUMN_STATUS
                                    )
                                )

                            val downloaded =
                                it.getLong(
                                    it.getColumnIndexOrThrow(
                                        DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR
                                    )
                                )

                            val total =
                                it.getLong(
                                    it.getColumnIndexOrThrow(
                                        DownloadManager.COLUMN_TOTAL_SIZE_BYTES
                                    )
                                )

                            when (status) {
                                DownloadManager.STATUS_PENDING,
                                DownloadManager.STATUS_RUNNING -> {
                                    UpdateDownloadState.update(
                                        downloaded = downloaded,
                                        total = total
                                    )
                                    handler.postDelayed(this, 250L)
                                }

                                DownloadManager.STATUS_SUCCESSFUL -> {
                                    UpdateDownloadState.update(
                                        downloaded = downloaded,
                                        total = total
                                    )
                                    UpdateDownloadState.preparing()

                                    handler.post {
                                        try {
                                            val downloadedUri =
                                                downloadManager.getUriForDownloadedFile(downloadId)

                                            if (downloadedUri == null) {
                                                throw IOException(
                                                    "Nie udało się odnaleźć pobranego APK."
                                                )
                                            }

                                            val apkFile =
                                                File(
                                                    context.cacheDir,
                                                    "ListaZakupow-update.apk"
                                                )

                                            context.contentResolver
                                                .openInputStream(downloadedUri)
                                                ?.use { input ->
                                                    apkFile.outputStream().use { output ->
                                                        input.copyTo(output)
                                                    }
                                                }
                                                ?: throw IOException(
                                                    "Nie można otworzyć pobranego APK."
                                                )

                                            val apkUri =
                                                androidx.core.content.FileProvider
                                                    .getUriForFile(
                                                        context,
                                                        "${context.packageName}.fileprovider",
                                                        apkFile
                                                    )

                                            val installIntent =
                                                Intent(Intent.ACTION_VIEW).apply {
                                                    setDataAndType(
                                                        apkUri,
                                                        "application/vnd.android.package-archive"
                                                    )
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                                }

                                            android.util.Log.d(
                                                "ListaZakupowUpdate",
                                                "Uruchamiam instalator APK: ${apkFile.absolutePath}"
                                            )

                                            context.startActivity(installIntent)

                                            handler.postDelayed(
                                                { UpdateDownloadState.finish() },
                                                1500L
                                            )

                                        } catch (e: Exception) {
                                            android.util.Log.e(
                                                "ListaZakupowUpdate",
                                                "Błąd uruchamiania instalatora",
                                                e
                                            )
                                            UpdateDownloadState.finish()
                                            Toast.makeText(
                                                context,
                                                "Nie udało się uruchomić instalatora: ${e.message}",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                }

                                DownloadManager.STATUS_FAILED -> {
                                    val reason =
                                        it.getInt(
                                            it.getColumnIndexOrThrow(
                                                DownloadManager.COLUMN_REASON
                                            )
                                        )

                                    android.util.Log.e(
                                        "ListaZakupowUpdate",
                                        "Pobieranie APK nieudane. reason=$reason"
                                    )

                                    UpdateDownloadState.finish()
                                    Toast.makeText(
                                        context,
                                        "Pobieranie aktualizacji nie powiodło się.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }

                                DownloadManager.STATUS_PAUSED -> {
                                    UpdateDownloadState.status =
                                        "Pobieranie wstrzymane..."
                                    handler.postDelayed(this, 500L)
                                }

                                else -> {
                                    handler.postDelayed(this, 500L)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e(
                            "ListaZakupowUpdate",
                            "Błąd śledzenia pobierania",
                            e
                        )
                        UpdateDownloadState.finish()
                        Toast.makeText(
                            context,
                            "Błąd pobierania aktualizacji: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }

        handler.post(progressRunnable)

    } catch (e: Exception) {
        UpdateDownloadState.finish()
        android.util.Log.e(
            "ListaZakupowUpdate",
            "Nie udało się rozpocząć pobierania",
            e
        )
        Toast.makeText(
            context,
            "Nie udało się rozpocząć aktualizacji: ${e.message}",
            Toast.LENGTH_LONG
        ).show()
    }
}

@Composable
private fun UpdateDialog(
    release: GitHubRelease,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("🆕 Dostępna nowa wersja")
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Dostępna jest nowa wersja aplikacji Lista Zakupów."
                )

                Text(
                    "Nowa wersja: ${release.versionName}",
                    style = MaterialTheme.typography.titleMedium
                )

                Text(
                    "Aktualnie zainstalowana wersja: ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (release.downloadUrl == null) {
                    Text(
                        "Ta wersja nie ma jeszcze pliku APK do pobrania.",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = release.downloadUrl != null,
                onClick = onUpdate
            ) {
                Text("POBIERZ AKTUALIZACJĘ")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("PÓŹNIEJ")
            }
        }
    )
}

// =============================================================
// PASEK POBIERANIA AKTUALIZACJI
// =============================================================

@Composable
private fun UpdateDownloadBanner() {

    if (!UpdateDownloadState.isDownloading) {
        return
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = 12.dp,
                vertical = 8.dp
            ),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 6.dp
        )
    ) {

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Text(
                    text = "⬇️",
                    style =
                        MaterialTheme.typography.titleLarge
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp)
                ) {

                    Text(
                        text =
                            "Aktualizacja Lista Zakupów",
                        style =
                            MaterialTheme.typography.titleMedium
                    )

                    Text(
                        text =
                            UpdateDownloadState.status,
                        style =
                            MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant
                    )
                }

                Text(
                    text =
                        "${UpdateDownloadState.progress}%",
                    style =
                        MaterialTheme.typography.titleMedium
                )
            }

            Spacer(
                modifier =
                    Modifier.height(8.dp)
            )

            LinearProgressIndicator(
                progress = {
                    UpdateDownloadState.progress / 100f
                },
                modifier =
                    Modifier.fillMaxWidth()
            )

            Spacer(
                modifier =
                    Modifier.height(6.dp)
            )

            if (
                UpdateDownloadState.totalBytes > 0
            ) {

                Text(
                    text =
                        "${formatBytes(UpdateDownloadState.downloadedBytes)} / " +
                                formatBytes(
                                    UpdateDownloadState.totalBytes
                                ),

                    style =
                        MaterialTheme.typography.bodySmall,

                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )
            }
        }
    }
}

private fun formatBytes(
    bytes: Long
): String {

    if (bytes < 1024) {
        return "$bytes B"
    }

    if (bytes < 1024 * 1024) {
        return "%.1f KB".format(
            bytes / 1024.0
        )
    }

    return "%.1f MB".format(
        bytes / (1024.0 * 1024.0)
    )
}

// =============================================================
// MAIN ACTIVITY
// =============================================================


private fun Modifier.hapticTapFeedback(
    enabled: Boolean
): Modifier =
    composed {

        val view =
            LocalView.current

        pointerInput(
            enabled
        ) {

            awaitEachGesture {

                val down =
                    awaitFirstDown(
                        requireUnconsumed =
                            false,

                        pass =
                            PointerEventPass.Initial
                    )

                while (true) {

                    val event =
                        awaitPointerEvent(
                            PointerEventPass.Initial
                        )

                    val change =
                        event.changes.firstOrNull {
                            it.id ==
                                    down.id
                        }
                            ?: break

                    if (
                        change.changedToUp()
                    ) {

                        val distance =
                            (
                                    change.position -
                                            down.position
                                    )
                                .getDistance()

                        if (
                            enabled &&
                            distance <= 24f
                        ) {

                            view.performHapticFeedback(
                                HapticFeedbackConstants.VIRTUAL_KEY
                            )
                        }

                        break
                    }
                }
            }
        }
    }


class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        setContent {

            var dostepnaAktualizacja by remember {
                mutableStateOf<GitHubRelease?>(null)
            }

            val updateContext = LocalContext.current

            LaunchedEffect(Unit) {
                if (shouldCheckForUpdate(updateContext)) {
                    markUpdateCheck(updateContext)

                    checkGitHubLatestRelease { release ->
                        if (
                            release != null &&
                            release.versionCode > BuildConfig.VERSION_CODE
                        ) {
                            runOnUiThread {
                                dostepnaAktualizacja = release
                            }
                        }
                    }
                }
            }

            var wibracjeWlaczone by remember {
                mutableStateOf(
                    getSharedPreferences(
                        PREFS_SETTINGS,
                        MODE_PRIVATE
                    )
                        .getBoolean(
                            KEY_HAPTICS,
                            true
                        )
                )
            }

            var ustawionyMotyw by remember {
                mutableStateOf(
                    getSharedPreferences(
                        PREFS_SETTINGS,
                        MODE_PRIVATE
                    )
                        .getString(
                            KEY_THEME,
                            "system"
                        ) ?: "system"
                )
            }

            val darkTheme =
                when (ustawionyMotyw) {

                    "dark" ->
                        true

                    "light" ->
                        false

                    else ->
                        isSystemInDarkTheme()
                }

            ListaZakupowTheme(
                darkTheme =
                    darkTheme
            ) {

                Scaffold(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .hapticTapFeedback(
                                enabled =
                                    wibracjeWlaczone
                            )
                ) { padding ->

                    LoginScreen(
                        modifier =
                            Modifier.padding(padding),

                        onThemeChanged = {

                            ustawionyMotyw =
                                it
                        },

                        onHapticsChanged = {

                            wibracjeWlaczone =
                                it
                        }
                    )
                }

                dostepnaAktualizacja?.let { release ->
                    UpdateDialog(
                        release = release,
                        onDismiss = {
                            dostepnaAktualizacja = null
                        },
                        onUpdate = {
                            val url = release.downloadUrl
                            if (url != null) {
                                dostepnaAktualizacja = null
                                downloadAndInstallUpdate(
                                    updateContext,
                                    url
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}


// =============================================================
// ZDJĘCIE -> BASE64
// =============================================================

fun imageUriToBase64(
    context: Context,
    uri: Uri
): String? {

    return try {

        val inputStream =
            context.contentResolver
                .openInputStream(uri)
                ?: return null

        val original =
            BitmapFactory.decodeStream(
                inputStream
            )

        inputStream.close()

        if (original == null) {
            return null
        }

        val maxSize = 256

        val ratio =
            minOf(
                maxSize.toFloat() / original.width,
                maxSize.toFloat() / original.height,
                1f
            )

        val width =
            (original.width * ratio)
                .toInt()
                .coerceAtLeast(1)

        val height =
            (original.height * ratio)
                .toInt()
                .coerceAtLeast(1)

        val resized =
            Bitmap.createScaledBitmap(
                original,
                width,
                height,
                true
            )

        val output =
            ByteArrayOutputStream()

        resized.compress(
            Bitmap.CompressFormat.JPEG,
            65,
            output
        )

        resized.recycle()
        original.recycle()

        Base64.encodeToString(
            output.toByteArray(),
            Base64.NO_WRAP
        )

    } catch (e: Exception) {

        null
    }
}


// =============================================================
// BASE64 -> BITMAP
// =============================================================

fun base64ToBitmap(
    data: String
): Bitmap? {

    return try {

        if (data.isBlank()) {
            return null
        }

        val bytes =
            Base64.decode(
                data,
                Base64.DEFAULT
            )

        BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size
        )

    } catch (e: Exception) {

        null
    }
}


// =============================================================
// IKONA SKLEPU
// =============================================================

@Composable
fun SklepIcon(
    sklep: Sklep,
    modifier: Modifier = Modifier
) {

    if (
        sklep.typIkony == "image" &&
        sklep.obrazDane.isNotEmpty()
    ) {

        val bitmap =
            remember(
                sklep.obrazDane
            ) {
                base64ToBitmap(
                    sklep.obrazDane
                )
            }

        if (bitmap != null) {

            Image(

                bitmap =
                    bitmap.asImageBitmap(),

                contentDescription =
                    sklep.nazwa,

                contentScale =
                    ContentScale.Fit,

                modifier =
                    modifier
            )

        } else {

            Text(

                text =
                    sklep.emoji,

                style =
                    MaterialTheme
                        .typography
                        .headlineMedium,

                modifier =
                    modifier
            )
        }

    } else {

        Text(

            text =
                sklep.emoji,

            style =
                MaterialTheme
                    .typography
                    .headlineMedium,

            modifier =
                modifier
        )
    }
}


// =============================================================
// PUSTA LISTA
// =============================================================

@Composable
fun EmptyShoppingImage() {

    val darkTheme =
        isSystemInDarkTheme()

    Image(

        painter =
            painterResource(

                if (darkTheme) {

                    R.drawable.cart_dark

                } else {

                    R.drawable.cart_light
                }
            ),

        contentDescription =
            null,

        contentScale =
            ContentScale.FillWidth,

        modifier =
            Modifier
                .fillMaxWidth()
                .scale(1.25f)
                .height(550.dp)
                .offset(
                    y = 125.dp
                )
                .graphicsLayer {
                    this.alpha = if (darkTheme) {
                        0.40f
                    } else {
                        0.75f
                    }
                }
    )
}


// =============================================================
// LOGIN + GŁÓWNA APLIKACJA
// =============================================================

@Composable
fun LoginScreen(
    modifier: Modifier = Modifier,
    onThemeChanged: (String) -> Unit,
    onHapticsChanged: (Boolean) -> Unit
) {

    val db =
        remember {
            FirebaseFirestore.getInstance()
        }

    val auth =
        remember {
            FirebaseAuth.getInstance()
        }

    val snackbarHostState =
        remember {
            SnackbarHostState()
        }

    val scope =
        rememberCoroutineScope()

    val context =
        LocalContext.current

    val prefs =
        context.getSharedPreferences(
            "lista_zakupow",
            Context.MODE_PRIVATE
        )


    // =========================================================
    // STANY
    // =========================================================

    var login by remember {
        mutableStateOf("")
    }

    var pin by remember {
        mutableStateOf("")
    }

    var zalogowany by remember {

        mutableStateOf(
            auth.currentUser != null
        )
    }

    var emailKonta by remember {
        mutableStateOf(
            ""
        )
    }

    var imie by remember {

        mutableStateOf(
            prefs.getString(
                "imie",
                ""
            ) ?: ""
        )
    }

    var nowyProdukt by remember {
        mutableStateOf("")
    }

    var wyszukiwanieProduktu by remember {
        mutableStateOf("")
    }

    var produktDoUsuniecia by remember {
        mutableStateOf<Produkt?>(null)
    }

    var produktDoEdycji by remember {
        mutableStateOf<Produkt?>(null)
    }

    var pokazUsunZaznaczone by remember {
        mutableStateOf(false)
    }

    var wybranaZakladka by remember {
        mutableStateOf(0)
    }

    var wybranySklep by remember {
        mutableStateOf<String?>(null)
    }

    var wybranaKategoria by remember {
        mutableStateOf("wszystkie")
    }

    var trybSortowania by remember {
        mutableStateOf("reczna")
    }

    var pokazSortowanie by remember {
        mutableStateOf(false)
    }

    var pokazWyborListy by remember {
        mutableStateOf(false)
    }

    // Aktualny profil użytkownika z Firestore.
    LaunchedEffect(zalogowany) {
        val user = auth.currentUser
        if (zalogowany && user != null) {
            db.collection("users")
                .document(user.uid)
                .get()
                .addOnSuccessListener { document ->
                    if (document.exists()) {
                        imie = document.getString("imie")
                            ?: document.getString("login")
                                    ?: ""
                        login = document.getString("login") ?: ""
                        emailKonta = document.getString("email") ?: user.email ?: ""
                    }
                }
        }
    }


    // =========================================================
    // LISTA PRODUKTÓW
    // =========================================================

    val lista =
        remember {
            mutableStateListOf<Produkt>()
        }

    val posortowanaListaLocal =
        remember {
            mutableStateListOf<Produkt>()
        }


    // =========================================================
    // SKLEPY
    // =========================================================

    val sklepy =
        remember {
            mutableStateListOf<Sklep>()
        }

    val posortowaneSklepyLocal =
        remember {
            mutableStateListOf<Sklep>()
        }

    var sklepDoEdycji by remember {
        mutableStateOf<Sklep?>(null)
    }

    var sklepDoUsuniecia by remember {
        mutableStateOf<Sklep?>(null)
    }

    var pokazDodajSklep by remember {
        mutableStateOf(false)
    }


    // =========================================================
// PRODUKTY FIRESTORE
// =========================================================

    LaunchedEffect(zalogowany) {

        if (!zalogowany || auth.currentUser == null) {
            return@LaunchedEffect
        }

        db.collection("zakupy")
            .addSnapshotListener { result, error ->

                if (error != null) {
                    android.util.Log.e(
                        "ListaZakupow",
                        "Błąd pobierania produktów z Firestore",
                        error
                    )
                    return@addSnapshotListener
                }

                if (result == null) {
                    return@addSnapshotListener
                }

                lista.clear()

                result.documents.forEach { document ->

                    lista.add(
                        Produkt(
                            id = document.id,

                            nazwa = document.getString(
                                "nazwa"
                            ) ?: "",

                            dodal = document.getString(
                                "dodal"
                            ) ?: "",

                            kupione = document.getBoolean(
                                "kupione"
                            ) ?: false,

                            kupioneOd = document.getLong(
                                "kupioneOd"
                            ) ?: 0L,

                            kolejnosc = document.getLong(
                                "kolejnosc"
                            ) ?: 0L,

                            kategoria = document.getString(
                                "kategoria"
                            ) ?: "glowna"
                        )
                    )
                }
            }
    }


    // =========================================================
    // SKLEPY FIRESTORE
    // =========================================================

    LaunchedEffect(zalogowany) {

        if (!zalogowany || auth.currentUser == null) {
            return@LaunchedEffect
        }

        val ref =
            db.collection("sklepy")

        ref.get()
            .addOnSuccessListener { snapshot ->

                if (snapshot.isEmpty) {

                    val domyslneSklepy =
                        listOf(

                            Sklep(

                                id =
                                    "lidl",

                                nazwa =
                                    "Lidl",

                                typIkony =
                                    "emoji",

                                emoji =
                                    "🛒",

                                kolejnosc =
                                    1L
                            ),

                            Sklep(

                                id =
                                    "biedronka",

                                nazwa =
                                    "Biedronka",

                                typIkony =
                                    "emoji",

                                emoji =
                                    "🐞",

                                kolejnosc =
                                    2L
                            ),

                            Sklep(

                                id =
                                    "rossmann",

                                nazwa =
                                    "Rossmann",

                                typIkony =
                                    "emoji",

                                emoji =
                                    "🧴",

                                kolejnosc =
                                    3L
                            ),

                            Sklep(

                                id =
                                    "castorama",

                                nazwa =
                                    "Castorama",

                                typIkony =
                                    "emoji",

                                emoji =
                                    "🔨",

                                kolejnosc =
                                    4L
                            )
                        )

                    domyslneSklepy.forEach { sklep ->

                        ref.document(
                            sklep.id
                        ).set(

                            mapOf(

                                "nazwa" to
                                        sklep.nazwa,

                                "typIkony" to
                                        sklep.typIkony,

                                "emoji" to
                                        sklep.emoji,

                                "obrazDane" to
                                        sklep.obrazDane,

                                "kolejnosc" to
                                        sklep.kolejnosc
                            )
                        )
                    }
                }
            }

        ref.addSnapshotListener {
                snapshot,
                error ->

            if (
                error != null ||
                snapshot == null
            ) {
                return@addSnapshotListener
            }

            sklepy.clear()

            snapshot.documents.forEach { document ->

                sklepy.add(

                    Sklep(

                        id =
                            document.id,

                        nazwa =
                            document.getString(
                                "nazwa"
                            ) ?: "",

                        typIkony =
                            document.getString(
                                "typIkony"
                            ) ?: "emoji",

                        emoji =
                            document.getString(
                                "emoji"
                            ) ?: "🏪",

                        obrazDane =
                            document.getString(
                                "obrazDane"
                            ) ?: "",

                        kolejnosc =
                            document.getLong(
                                "kolejnosc"
                            ) ?: 0L
                    )
                )
            }

            sklepy.sortBy {
                it.kolejnosc
            }
        }
    }


    // =========================================================
    // AKTUALIZACJA LOKALNEJ LISTY SKLEPÓW
    //
    // Nie nadpisujemy jej podczas przeciągania.
    // Dzięki temu Firestore nie będzie powodował skakania.
    // =========================================================

    var przeciaganieSklepu by remember {
        mutableStateOf(false)
    }

    LaunchedEffect(
        sklepy.size,
        sklepy.joinToString("|") {
            "${it.id}:${it.nazwa}:${it.typIkony}:${it.emoji}:${it.obrazDane}:${it.kolejnosc}"
        }
    ) {

        if (!przeciaganieSklepu) {

            posortowaneSklepyLocal.clear()

            posortowaneSklepyLocal.addAll(
                sklepy.sortedBy {
                    it.kolejnosc
                }
            )
        }
    }


    // =========================================================
    // AKTUALIZACJA LOKALNEJ LISTY PRODUKTÓW
    // =========================================================

    LaunchedEffect(

        wybranaKategoria,

        wyszukiwanieProduktu,

        trybSortowania,

        lista.joinToString("|") {

            "${it.id}:${it.kolejnosc}:${it.kupione}:${it.kategoria}:${it.nazwa}"
        }
    ) {

        val fraza =
            wyszukiwanieProduktu
                .trim()
                .lowercase()

        val przefiltrowana =
            lista
                .filter {

                    val pasujeDoKategorii =
                        if (
                            wybranaKategoria ==
                            "wszystkie"
                        ) {

                            true

                        } else {

                            it.kategoria ==
                                    wybranaKategoria
                        }

                    val pasujeDoWyszukiwania =
                        fraza.isEmpty() ||
                                it.nazwa
                                    .lowercase()
                                    .contains(
                                        fraza
                                    )

                    pasujeDoKategorii &&
                            pasujeDoWyszukiwania
                }

        val aktualna =
            when (trybSortowania) {

                "az" ->
                    przefiltrowana.sortedBy {
                        it.nazwa.lowercase()
                    }

                "za" ->
                    przefiltrowana.sortedByDescending {
                        it.nazwa.lowercase()
                    }

                "dokupienia" ->
                    przefiltrowana.sortedWith(
                        compareBy<Produkt> {
                            it.kupione
                        }.thenBy {
                            it.nazwa.lowercase()
                        }
                    )

                "kupione" ->
                    przefiltrowana.sortedWith(
                        compareByDescending<Produkt> {
                            it.kupione
                        }.thenBy {
                            it.nazwa.lowercase()
                        }
                    )

                else ->
                    przefiltrowana.sortedBy {
                        it.kolejnosc
                    }
            }

        posortowanaListaLocal.clear()

        posortowanaListaLocal.addAll(
            aktualna
        )
    }


    // =========================================================
    // AUTO USUWANIE KUPIONYCH
    // =========================================================

    LaunchedEffect(Unit) {

        while (true) {

            val teraz =
                System.currentTimeMillis()

            lista
                .filter {

                    settingsPrefs(context)
                        .getBoolean(
                            KEY_AUTO_DELETE,
                            true
                        ) &&
                            it.kupione &&
                            it.kupioneOd > 0 &&
                            teraz -
                            it.kupioneOd >=
                            settingsPrefs(context)
                                .getInt(
                                    KEY_DELETE_MINUTES,
                                    20
                                ) *
                            60 *
                            1000
                }
                .forEach { produkt ->

                    db.collection(
                        "zakupy"
                    )
                        .document(
                            produkt.id
                        )
                        .delete()
                }

            delay(60_000)
        }
    }


    // =========================================================
    // LOGIN — FIREBASE AUTHENTICATION
    // =========================================================

    if (!zalogowany) {

        var logowanieTrwa by remember {
            mutableStateOf(false)
        }
        var bladLogowania by remember {
            mutableStateOf<String?>(null)
        }
        var pokazResetHasla by remember {
            mutableStateOf(false)
        }
        var resetLogin by remember {
            mutableStateOf("")
        }
        var resetTrwa by remember {
            mutableStateOf(false)
        }
        var komunikatResetu by remember {
            mutableStateOf<String?>(null)
        }

        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 430.dp),
                shape = RoundedCornerShape(28.dp),
                elevation = CardDefaults.cardElevation(6.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("🛒", style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.height(8.dp))
                    Text("Lista Zakupów", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Zaloguj się, aby korzystać ze swojej listy",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(24.dp))

                    OutlinedTextField(
                        value = login,
                        onValueChange = { login = it; bladLogowania = null },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !logowanieTrwa,
                        singleLine = true,
                        label = { Text("Login") },
                        placeholder = { Text("np. janek") },
                        shape = RoundedCornerShape(16.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it; bladLogowania = null },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !logowanieTrwa,
                        singleLine = true,
                        label = { Text("Hasło") },
                        visualTransformation = PasswordVisualTransformation(),
                        shape = RoundedCornerShape(16.dp)
                    )

                    if (bladLogowania != null) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "⚠ ${bladLogowania}",
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = {
                            val loginValue = login.trim().lowercase()
                            if (loginValue.isBlank()) {
                                bladLogowania = "Wpisz login."
                                return@Button
                            }
                            if (pin.isBlank()) {
                                bladLogowania = "Wpisz hasło."
                                return@Button
                            }

                            logowanieTrwa = true
                            bladLogowania = null

                            // Najpierw wyszukujemy prawdziwy e-mail po loginie w Firestore.
                            db.collection("users")
                                .whereEqualTo("login", loginValue)
                                .limit(1)
                                .get()
                                .addOnSuccessListener { snapshot ->
                                    if (snapshot.isEmpty) {
                                        logowanieTrwa = false
                                        bladLogowania = "Nie znaleziono takiego loginu."
                                        return@addOnSuccessListener
                                    }

                                    val document = snapshot.documents.first()
                                    val email = document.getString("email")?.trim()
                                    if (email.isNullOrBlank()) {
                                        logowanieTrwa = false
                                        bladLogowania = "To konto nie ma przypisanego adresu e-mail."
                                        return@addOnSuccessListener
                                    }

                                    auth.signInWithEmailAndPassword(email, pin)
                                        .addOnSuccessListener { result ->
                                            val user = result.user
                                            if (user == null) {
                                                logowanieTrwa = false
                                                bladLogowania = "Nie udało się pobrać konta."
                                                return@addOnSuccessListener
                                            }

                                            imie = document.getString("imie")
                                                ?: document.getString("login")
                                                        ?: loginValue
                                            login = document.getString("login") ?: loginValue
                                            emailKonta = document.getString("email") ?: email
                                            pin = ""
                                            zalogowany = true
                                            logowanieTrwa = false
                                            prefs.edit()
                                                .putBoolean("zalogowany", true)
                                                .putString("imie", imie)
                                                .apply()
                                        }
                                        .addOnFailureListener { error ->
                                            logowanieTrwa = false
                                            val message = error.message?.lowercase() ?: ""
                                            bladLogowania = when {
                                                message.contains("password") ||
                                                        message.contains("credential") ||
                                                        message.contains("invalid") ->
                                                    "Nieprawidłowy login lub hasło."
                                                message.contains("network") ->
                                                    "Brak połączenia z Internetem."
                                                else ->
                                                    "Nie udało się zalogować. Spróbuj ponownie."
                                            }
                                        }
                                }
                                .addOnFailureListener {
                                    logowanieTrwa = false
                                    bladLogowania = "Nie udało się połączyć z bazą danych."
                                }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        enabled = !logowanieTrwa,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        if (logowanieTrwa) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Zaloguj się")
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        enabled = !logowanieTrwa,
                        onClick = {
                            resetLogin = login
                            komunikatResetu = null
                            resetTrwa = false
                            pokazResetHasla = true
                        }
                    ) {
                        Text("Resetuj hasło")
                    }
                }
            }
        }

        if (pokazResetHasla) {
            AlertDialog(
                onDismissRequest = { if (!resetTrwa) pokazResetHasla = false },
                title = { Text("🔑 Resetuj hasło") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Podaj login przypisany do konta.")
                        OutlinedTextField(
                            value = resetLogin,
                            onValueChange = { resetLogin = it; komunikatResetu = null },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !resetTrwa,
                            singleLine = true,
                            label = { Text("Login") },
                            placeholder = { Text("np. janek") }
                        )
                        if (komunikatResetu != null) {
                            Text(
                                komunikatResetu!!,
                                color = if (komunikatResetu!!.startsWith("✅"))
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(enabled = !resetTrwa, onClick = { pokazResetHasla = false }) {
                        Text("Anuluj")
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = !resetTrwa,
                        onClick = {
                            val value = resetLogin.trim().lowercase()
                            if (value.isBlank()) {
                                komunikatResetu = "Wpisz login."
                                return@TextButton
                            }
                            resetTrwa = true
                            komunikatResetu = null
                            db.collection("users")
                                .whereEqualTo("login", value)
                                .limit(1)
                                .get()
                                .addOnSuccessListener { snapshot ->
                                    if (snapshot.isEmpty) {
                                        resetTrwa = false
                                        komunikatResetu = "Nie znaleziono takiego konta."
                                        return@addOnSuccessListener
                                    }
                                    val email = snapshot.documents.first().getString("email")?.trim()
                                    if (email.isNullOrBlank()) {
                                        resetTrwa = false
                                        komunikatResetu = "Do tego konta nie przypisano adresu e-mail."
                                        return@addOnSuccessListener
                                    }
                                    auth.sendPasswordResetEmail(email)
                                        .addOnSuccessListener {
                                            resetTrwa = false
                                            komunikatResetu = "✅ Link do resetowania hasła został wysłany na przypisany adres e-mail."
                                        }
                                        .addOnFailureListener {
                                            resetTrwa = false
                                            komunikatResetu = "Nie udało się wysłać wiadomości. Spróbuj ponownie."
                                        }
                                }
                                .addOnFailureListener {
                                    resetTrwa = false
                                    komunikatResetu = "Nie udało się znaleźć konta. Sprawdź połączenie z Internetem."
                                }
                        }
                    ) {
                        if (resetTrwa) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text("Wyślij link")
                    }
                }
            )
        }

        return
    }

    // =========================================================
    // GŁÓWNY BOX
    // =========================================================

    Box(
        modifier =
            Modifier.fillMaxSize()
    ) {

        // Pasek pobierania jest widoczny na górze całej aplikacji.
        UpdateDownloadBanner()

        AnimatedContent(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(
                        top =
                            if (UpdateDownloadState.isDownloading) {
                                96.dp
                            } else {
                                0.dp
                            }
                    ),

            targetState =
                wybranaZakladka,

            transitionSpec = {

                fadeIn(
                    animationSpec =
                        tween(180)
                ) togetherWith
                        fadeOut(
                            animationSpec =
                                tween(120)
                        )
            },

            label =
                "mainTabTransition"

        ) { zakladka ->

            when (zakladka) {


                // =================================================
                // LISTA
                // =================================================

                0 -> {

                    val produktyGlownejListy =

                        if (
                            wybranaKategoria ==
                            "wszystkie"
                        ) {

                            // "Wszystkie" pokazuje wszystkie produkty,
                            // niezależnie od wybranej listy/sklepu.
                            lista

                        } else {

                            lista
                                .filter {
                                    it.kategoria ==
                                            wybranaKategoria
                                }
                        }


                    val zaznaczoneProdukty =
                        produktyGlownejListy
                            .filter {
                                it.kupione
                            }


                    Column(

                        modifier =
                            modifier
                                .fillMaxSize()
                                .background(
                                    MaterialTheme
                                        .colorScheme
                                        .background
                                )
                                .padding(
                                    start = 24.dp,
                                    top = 24.dp,
                                    end = 24.dp,
                                    bottom = 88.dp
                                ),

                        verticalArrangement =
                            Arrangement.spacedBy(
                                8.dp
                            )
                    ) {

                        // =================================================
                        // NAGŁÓWEK LISTY
                        // =================================================

                        Row(

                            modifier =
                                Modifier.fillMaxWidth(),

                            verticalAlignment =
                                Alignment.CenterVertically
                        ) {

                            Text(

                                text =
                                    "🛒 Lista zakupów",

                                style =
                                    MaterialTheme
                                        .typography
                                        .titleMedium
                            )
                        }


                        UserHeader(

                            imie =
                                imie,

                            liczbaProduktow =
                                produktyGlownejListy
                                    .size,

                            onLogout = {

                                prefs.edit()
                                    .clear()
                                    .apply()

                                zalogowany =
                                    false

                                login =
                                    ""

                                pin =
                                    ""

                                imie =
                                    ""
                            }
                        )


                        // =================================================
                        // DODAWANIE PRODUKTU
                        // =================================================

                        Row(

                            modifier =
                                Modifier.fillMaxWidth(),

                            verticalAlignment =
                                Alignment.CenterVertically
                        ) {

                            OutlinedTextField(

                                value =
                                    nowyProdukt,

                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .height(58.dp),

                                onValueChange = {
                                    nowyProdukt =
                                        it
                                },

                                label = {
                                    Text(
                                        "🛍️ Produkt"
                                    )
                                },

                                singleLine =
                                    true,

                                shape =
                                    RoundedCornerShape(
                                        18.dp
                                    )
                            )


                            Spacer(
                                modifier =
                                    Modifier.width(
                                        8.dp
                                    )
                            )


                            Button(

                                modifier =
                                    Modifier
                                        .height(58.dp),

                                shape =
                                    RoundedCornerShape(
                                        18.dp
                                    ),

                                onClick = {

                                    if (
                                        nowyProdukt
                                            .trim()
                                            .isNotEmpty()
                                    ) {

                                        pokazWyborListy =
                                            true
                                    }
                                },

                                contentPadding =
                                    androidx.compose.foundation.layout.PaddingValues(
                                        horizontal = 16.dp
                                    )
                            ) {

                                Text(
                                    "＋"
                                )
                            }
                        }


                        // =================================================
                        // WYSZUKIWANIE + SORTOWANIE — ETAP 4.1.1 / 4.2
                        // =================================================

                        Row(

                            modifier =
                                Modifier.fillMaxWidth(),

                            horizontalArrangement =
                                Arrangement.spacedBy(
                                    6.dp
                                ),

                            verticalAlignment =
                                Alignment.CenterVertically
                        ) {

                            OutlinedTextField(

                                value =
                                    wyszukiwanieProduktu,

                                onValueChange = {

                                    wyszukiwanieProduktu =
                                        it
                                },

                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .height(
                                            58.dp
                                        ),

                                singleLine =
                                    true,

                                textStyle =
                                    MaterialTheme
                                        .typography
                                        .bodySmall,

                                shape =
                                    RoundedCornerShape(
                                        18.dp
                                    ),

                                placeholder = {

                                    Text(

                                        text =
                                            "🔎 Szukaj produktu...",

                                        style =
                                            MaterialTheme
                                                .typography
                                                .bodySmall
                                    )
                                },

                                trailingIcon = {

                                    if (
                                        wyszukiwanieProduktu
                                            .isNotEmpty()
                                    ) {

                                        Box(

                                            modifier =
                                                Modifier
                                                    .size(
                                                        32.dp
                                                    )
                                                    .clickable {

                                                        wyszukiwanieProduktu =
                                                            ""
                                                    },

                                            contentAlignment =
                                                Alignment.Center
                                        ) {

                                            Text(
                                                "✕"
                                            )
                                        }
                                    }
                                }
                            )


                            Button(

                                modifier =
                                    Modifier
                                        .height(
                                            58.dp
                                        ),

                                shape =
                                    RoundedCornerShape(
                                        18.dp
                                    ),

                                onClick = {

                                    pokazSortowanie =
                                        true
                                },

                                contentPadding =
                                    androidx.compose.foundation.layout.PaddingValues(
                                        horizontal = 16.dp
                                    )
                            ) {

                                Text(
                                    "↕"
                                )
                            }
                        }


                        // =================================================
                        // FILTRY
                        // =================================================

                        Row(

                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(
                                        rememberScrollState()
                                    ),

                            horizontalArrangement =
                                Arrangement.spacedBy(
                                    6.dp
                                )
                        ) {

                            FilterButton(

                                text =
                                    "Wszystkie",

                                selected =
                                    wybranaKategoria ==
                                            "wszystkie",

                                onClick = {

                                    wybranaKategoria =
                                        "wszystkie"
                                }
                            )


                            sklepy
                                .sortedBy {
                                    it.kolejnosc
                                }
                                .forEach { sklep ->

                                    FilterButton(

                                        text =
                                            "${sklep.emoji} ${sklep.nazwa}",

                                        selected =
                                            wybranaKategoria ==
                                                    sklep.id,

                                        onClick = {

                                            wybranaKategoria =
                                                sklep.id
                                        }
                                    )
                                }
                        }


                        // =================================================
                        // DIALOG SORTOWANIA
                        // =================================================

                        if (pokazSortowanie) {

                            AlertDialog(

                                onDismissRequest = {
                                    pokazSortowanie =
                                        false
                                },

                                title = {
                                    Text(
                                        "Sortowanie produktów"
                                    )
                                },

                                text = {

                                    Column(
                                        verticalArrangement =
                                            Arrangement.spacedBy(
                                                2.dp
                                            )
                                    ) {

                                        TextButton(
                                            onClick = {
                                                trybSortowania =
                                                    "reczna"
                                                pokazSortowanie =
                                                    false
                                            }
                                        ) {
                                            Text(
                                                "↕ Ręczna kolejność"
                                            )
                                        }

                                        TextButton(
                                            onClick = {
                                                trybSortowania =
                                                    "az"
                                                pokazSortowanie =
                                                    false
                                            }
                                        ) {
                                            Text(
                                                "🔤 Alfabetycznie A → Z"
                                            )
                                        }

                                        TextButton(
                                            onClick = {
                                                trybSortowania =
                                                    "za"
                                                pokazSortowanie =
                                                    false
                                            }
                                        ) {
                                            Text(
                                                "🔤 Alfabetycznie Z → A"
                                            )
                                        }

                                        TextButton(
                                            onClick = {
                                                trybSortowania =
                                                    "dokupienia"
                                                pokazSortowanie =
                                                    false
                                            }
                                        ) {
                                            Text(
                                                "🛒 Najpierw do kupienia"
                                            )
                                        }

                                        TextButton(
                                            onClick = {
                                                trybSortowania =
                                                    "kupione"
                                                pokazSortowanie =
                                                    false
                                            }
                                        ) {
                                            Text(
                                                "✅ Najpierw kupione"
                                            )
                                        }
                                    }
                                },

                                confirmButton = {

                                    TextButton(
                                        onClick = {
                                            pokazSortowanie =
                                                false
                                        }
                                    ) {
                                        Text(
                                            "Zamknij"
                                        )
                                    }
                                }
                            )
                        }


                        // =================================================
                        // DIALOG WYBORU LISTY
                        // =================================================

                        if (
                            pokazWyborListy
                        ) {

                            AlertDialog(

                                onDismissRequest = {

                                    pokazWyborListy =
                                        false
                                },

                                title = {

                                    Text(
                                        "Do której listy dodać?"
                                    )
                                },

                                text = {

                                    Column(

                                        verticalArrangement =
                                            Arrangement.spacedBy(
                                                8.dp
                                            )
                                    ) {

                                        ListaWyboruButton(

                                            emoji =
                                                "🏠",

                                            nazwa =
                                                "Główna",

                                            onClick = {

                                                dodajProduktDoListy(

                                                    nowyProdukt,

                                                    imie,

                                                    "glowna"
                                                )

                                                nowyProdukt =
                                                    ""

                                                pokazWyborListy =
                                                    false

                                                scope.launch {

                                                    snackbarHostState
                                                        .showSnackbar(
                                                            "✅ Dodano do głównej"
                                                        )
                                                }
                                            }
                                        )


                                        sklepy
                                            .sortedBy {
                                                it.kolejnosc
                                            }
                                            .forEach { sklep ->

                                                ListaWyboruButton(

                                                    emoji =
                                                        sklep.emoji,

                                                    nazwa =
                                                        sklep.nazwa,

                                                    sklep =
                                                        sklep,

                                                    onClick = {

                                                        dodajProduktDoListy(

                                                            nowyProdukt,

                                                            imie,

                                                            sklep.id
                                                        )

                                                        nowyProdukt =
                                                            ""

                                                        pokazWyborListy =
                                                            false

                                                        scope.launch {

                                                            snackbarHostState
                                                                .showSnackbar(
                                                                    "✅ Dodano do ${sklep.nazwa}"
                                                                )
                                                        }
                                                    }
                                                )
                                            }
                                    }
                                },

                                confirmButton = {},

                                dismissButton = {

                                    TextButton(

                                        onClick = {

                                            pokazWyborListy =
                                                false
                                        }
                                    ) {

                                        Text(
                                            "ANULUJ"
                                        )
                                    }
                                }
                            )
                        }


                        // =================================================
                        // EDYCJA PRODUKTU
                        // =================================================

                        produktDoEdycji?.let { produkt ->

                            var edytowanaNazwa by
                            remember(
                                produkt.id
                            ) {

                                mutableStateOf(
                                    produkt.nazwa
                                )
                            }

                            var edytowanaKategoria by
                            remember(
                                produkt.id
                            ) {

                                mutableStateOf(
                                    produkt.kategoria
                                )
                            }


                            AlertDialog(

                                onDismissRequest = {

                                    produktDoEdycji =
                                        null
                                },

                                title = {

                                    Text(
                                        "✏️ Edytuj produkt"
                                    )
                                },

                                text = {

                                    Column(

                                        verticalArrangement =
                                            Arrangement.spacedBy(
                                                12.dp
                                            )
                                    ) {

                                        OutlinedTextField(

                                            value =
                                                edytowanaNazwa,

                                            onValueChange = {
                                                edytowanaNazwa =
                                                    it
                                            },

                                            modifier =
                                                Modifier.fillMaxWidth(),

                                            label = {
                                                Text(
                                                    "Nazwa produktu"
                                                )
                                            },

                                            singleLine =
                                                true
                                        )


                                        Text(
                                            "Lista:"
                                        )


                                        Row(

                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .horizontalScroll(
                                                        rememberScrollState()
                                                    ),

                                            horizontalArrangement =
                                                Arrangement.spacedBy(
                                                    6.dp
                                                )
                                        ) {

                                            FilterButton(

                                                text =
                                                    "🏠 Główna",

                                                selected =
                                                    edytowanaKategoria ==
                                                            "glowna",

                                                onClick = {

                                                    edytowanaKategoria =
                                                        "glowna"
                                                }
                                            )


                                            sklepy.forEach {
                                                    sklep ->

                                                FilterButton(

                                                    text =
                                                        "${sklep.emoji} ${sklep.nazwa}",

                                                    selected =
                                                        edytowanaKategoria ==
                                                                sklep.id,

                                                    onClick = {

                                                        edytowanaKategoria =
                                                            sklep.id
                                                    }
                                                )
                                            }
                                        }
                                    }
                                },

                                confirmButton = {

                                    TextButton(

                                        onClick = {

                                            val nowaNazwa =
                                                edytowanaNazwa
                                                    .trim()

                                            if (
                                                nowaNazwa
                                                    .isNotEmpty()
                                            ) {

                                                db.collection(
                                                    "zakupy"
                                                )
                                                    .document(
                                                        produkt.id
                                                    )
                                                    .update(

                                                        mapOf(

                                                            "nazwa" to
                                                                    nowaNazwa,

                                                            "kategoria" to
                                                                    edytowanaKategoria
                                                        )
                                                    )

                                                produktDoEdycji =
                                                    null
                                            }
                                        }
                                    ) {

                                        Text(
                                            "ZAPISZ"
                                        )
                                    }
                                },

                                dismissButton = {

                                    TextButton(

                                        onClick = {

                                            produktDoEdycji =
                                                null
                                        }
                                    ) {

                                        Text(
                                            "ANULUJ"
                                        )
                                    }
                                }
                            )
                        }


                        // =================================================
                        // USUWANIE PRODUKTU
                        // =================================================

                        produktDoUsuniecia?.let { produkt ->

                            AlertDialog(

                                onDismissRequest = {

                                    produktDoUsuniecia =
                                        null
                                },

                                title = {

                                    Text(
                                        "Usunąć produkt?"
                                    )
                                },

                                text = {

                                    Text(
                                        produkt.nazwa
                                    )
                                },

                                confirmButton = {

                                    TextButton(

                                        onClick = {

                                            db.collection(
                                                "zakupy"
                                            )
                                                .document(
                                                    produkt.id
                                                )
                                                .delete()

                                            produktDoUsuniecia =
                                                null
                                        }
                                    ) {

                                        Text(
                                            "USUŃ",
                                            color =
                                                Color.Red
                                        )
                                    }
                                },

                                dismissButton = {

                                    TextButton(

                                        onClick = {

                                            produktDoUsuniecia =
                                                null
                                        }
                                    ) {

                                        Text(
                                            "ANULUJ"
                                        )
                                    }
                                }
                            )
                        }


                        // =================================================
                        // PRODUKTY
                        // =================================================

                        if (
                            posortowanaListaLocal
                                .isEmpty()
                        ) {

                            Box(

                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .weight(1f),

                                contentAlignment =
                                    Alignment.BottomCenter
                            ) {

                                EmptyShoppingImage()
                            }

                        } else {

                            Box(

                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                            ) {

                                ProductDragList(

                                    produkty =
                                        posortowanaListaLocal,

                                    onMove = {
                                            from,
                                            to ->

                                        if (
                                            trybSortowania ==
                                            "reczna" &&
                                            from in
                                            posortowanaListaLocal.indices &&
                                            to in
                                            posortowanaListaLocal.indices
                                        ) {

                                            val item =
                                                posortowanaListaLocal
                                                    .removeAt(
                                                        from
                                                    )

                                            posortowanaListaLocal
                                                .add(
                                                    to,
                                                    item
                                                )
                                        }
                                    },

                                    onDragEnd = {

                                        if (
                                            trybSortowania ==
                                            "reczna"
                                        ) {

                                            zapiszNowaKolejnosc(
                                                posortowanaListaLocal
                                            )
                                        }
                                    },

                                    onToggleProduct = {
                                            produkt,
                                            checked ->

                                        produkt.kupione =
                                            checked

                                        val now =

                                            if (
                                                checked
                                            ) {

                                                System
                                                    .currentTimeMillis()

                                            } else {

                                                0L
                                            }

                                        produkt.kupioneOd =
                                            now

                                        db.collection(
                                            "zakupy"
                                        )
                                            .document(
                                                produkt.id
                                            )
                                            .update(

                                                mapOf(

                                                    "kupione" to
                                                            checked,

                                                    "kupioneOd" to
                                                            now
                                                )
                                            )
                                    },

                                    onDeleteProduct = {
                                            produkt ->

                                        produktDoUsuniecia =
                                            produkt
                                    },

                                    onEditProduct = {
                                            produkt ->

                                        produktDoEdycji =
                                            produkt
                                    }
                                )
                            }
                        }


                        // =================================================
                        // USUWANIE ZAZNACZONYCH
                        // =================================================

                        if (
                            zaznaczoneProdukty
                                .isNotEmpty()
                        ) {

                            Row(

                                modifier =
                                    Modifier.fillMaxWidth(),

                                horizontalArrangement =
                                    Arrangement.Center
                            ) {

                                FloatingActionButton(

                                    onClick = {

                                        pokazUsunZaznaczone =
                                            true
                                    },

                                    containerColor =
                                        Color.Red,

                                    modifier =
                                        Modifier.padding(
                                            16.dp
                                        )
                                ) {

                                    Text(
                                        "🗑 ${zaznaczoneProdukty.size}"
                                    )
                                }
                            }


                            if (
                                pokazUsunZaznaczone
                            ) {

                                AlertDialog(

                                    onDismissRequest = {

                                        pokazUsunZaznaczone =
                                            false
                                    },

                                    title = {

                                        Text(
                                            "Usunąć produkty?"
                                        )
                                    },

                                    text = {

                                        Text(
                                            "Czy na pewno chcesz usunąć ${zaznaczoneProdukty.size} zaznaczone produkty?"
                                        )
                                    },

                                    confirmButton = {

                                        TextButton(

                                            onClick = {

                                                zaznaczoneProdukty
                                                    .forEach {
                                                            produkt ->

                                                        db.collection(
                                                            "zakupy"
                                                        )
                                                            .document(
                                                                produkt.id
                                                            )
                                                            .delete()
                                                    }

                                                pokazUsunZaznaczone =
                                                    false
                                            }
                                        ) {

                                            Text(
                                                "USUŃ",
                                                color =
                                                    Color.Red
                                            )
                                        }
                                    },

                                    dismissButton = {

                                        TextButton(

                                            onClick = {

                                                pokazUsunZaznaczone =
                                                    false
                                            }
                                        ) {

                                            Text(
                                                "ANULUJ"
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }
                }


                // =================================================
                // SKLEPY
                // =================================================

                1 -> {

                    if (
                        wybranySklep == null
                    ) {

                        SklepyScreen(

                            sklepy =
                                posortowaneSklepyLocal,

                            onSklepClick = {
                                    sklepId ->

                                wybranySklep =
                                    sklepId
                            },

                            onDodajSklep = {

                                pokazDodajSklep =
                                    true
                            },

                            onEdytujSklep = {
                                    sklep ->

                                sklepDoEdycji =
                                    sklep
                            },

                            onUsunSklep = {
                                    sklep ->

                                sklepDoUsuniecia =
                                    sklep
                            },

                            onDragStart = {

                                przeciaganieSklepu =
                                    true
                            },

                            onDragEnd = {

                                przeciaganieSklepu =
                                    false

                                zapiszNowaKolejnoscSklepow(
                                    posortowaneSklepyLocal
                                )
                            },

                            onMove = {
                                    from,
                                    to ->

                                if (
                                    from in
                                    posortowaneSklepyLocal.indices &&
                                    to in
                                    posortowaneSklepyLocal.indices
                                ) {

                                    val item =
                                        posortowaneSklepyLocal
                                            .removeAt(
                                                from
                                            )

                                    posortowaneSklepyLocal
                                        .add(
                                            to,
                                            item
                                        )
                                }
                            }
                        )

                    } else {

                        ListaSklepuScreen(

                            sklepId =
                                wybranySklep!!,

                            sklep =
                                sklepy.find {
                                    it.id ==
                                            wybranySklep
                                },

                            lista =
                                lista,

                            onBack = {

                                wybranySklep =
                                    null
                            }
                        )
                    }
                }


                // =================================================
                // USTAWIENIA
                // =================================================

                2 -> {

                    UstawieniaScreen(
                        currentEmail = emailKonta,
                        onEmailChanged = { emailKonta = it },
                        onThemeChanged = onThemeChanged,
                        onHapticsChanged = onHapticsChanged,
                        onLogout = {
                            auth.signOut()
                            zalogowany = false
                            login = ""
                            pin = ""
                            imie = ""
                            emailKonta = ""
                            prefs.edit().clear().apply()
                        }
                    )
                }
            }


            // =====================================================
            // DODAJ SKLEP
            // =====================================================

            if (
                pokazDodajSklep
            ) {

                DodajLubEdytujSklepDialog(

                    sklep =
                        null,

                    onDismiss = {

                        pokazDodajSklep =
                            false
                    },

                    onSaved = {

                        pokazDodajSklep =
                            false
                    }
                )
            }


            // =====================================================
            // EDYTUJ SKLEP
            // =====================================================

            sklepDoEdycji?.let { sklep ->

                DodajLubEdytujSklepDialog(

                    sklep =
                        sklep,

                    onDismiss = {

                        sklepDoEdycji =
                            null
                    },

                    onSaved = {

                        sklepDoEdycji =
                            null
                    }
                )
            }


            // =====================================================
            // USUŃ SKLEP
            // =====================================================

            sklepDoUsuniecia?.let { sklep ->

                AlertDialog(

                    onDismissRequest = {

                        sklepDoUsuniecia =
                            null
                    },

                    title = {

                        Text(
                            "🗑️ Usunąć sklep?"
                        )
                    },

                    text = {

                        Text(

                            "Czy na pewno chcesz usunąć sklep „${sklep.nazwa}”?\n\n" +
                                    "Produkty z tego sklepu zostaną przeniesione do listy Główna."
                        )
                    },

                    confirmButton = {

                        TextButton(

                            onClick = {

                                db.collection(
                                    "zakupy"
                                )
                                    .whereEqualTo(
                                        "kategoria",
                                        sklep.id
                                    )
                                    .get()
                                    .addOnSuccessListener {
                                            snapshot ->

                                        val batch =
                                            db.batch()

                                        snapshot.documents
                                            .forEach {
                                                    document ->

                                                batch.update(

                                                    document.reference,

                                                    "kategoria",

                                                    "glowna"
                                                )
                                            }

                                        batch.commit()
                                            .addOnSuccessListener {

                                                db.collection(
                                                    "sklepy"
                                                )
                                                    .document(
                                                        sklep.id
                                                    )
                                                    .delete()
                                                    .addOnSuccessListener {

                                                        if (
                                                            wybranySklep ==
                                                            sklep.id
                                                        ) {

                                                            wybranySklep =
                                                                null
                                                        }

                                                        if (
                                                            wybranaKategoria ==
                                                            sklep.id
                                                        ) {

                                                            wybranaKategoria =
                                                                "wszystkie"
                                                        }

                                                        sklepDoUsuniecia =
                                                            null

                                                        scope.launch {

                                                            snackbarHostState
                                                                .showSnackbar(
                                                                    "🗑️ Usunięto sklep ${sklep.nazwa}"
                                                                )
                                                        }
                                                    }
                                            }
                                    }
                            }
                        ) {

                            Text(
                                "USUŃ",
                                color =
                                    Color.Red
                            )
                        }
                    },

                    dismissButton = {

                        TextButton(

                            onClick = {

                                sklepDoUsuniecia =
                                    null
                            }
                        ) {

                            Text(
                                "ANULUJ"
                            )
                        }
                    }
                )
            }


        }

        // =====================================================
        // DOLNA NAWIGACJA — 3.5
        // =====================================================

        Card(

            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = 12.dp,
                        vertical = 0.dp
                    )
                    .padding(
                        bottom = 58.dp
                    )
                    .align(
                        Alignment.BottomCenter
                    ),

            shape =
                RoundedCornerShape(
                    28.dp
                ),

            colors =
                CardDefaults.cardColors(
                    containerColor =
                        MaterialTheme
                            .colorScheme
                            .surface
                ),

            elevation =
                CardDefaults.cardElevation(
                    defaultElevation =
                        8.dp
                )
        ) {

            Row(

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = 8.dp,
                            vertical = 8.dp
                        ),

                horizontalArrangement =
                    Arrangement.SpaceEvenly,

                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                DolnaNawigacjaItem(

                    icon =
                        "🛒",

                    label =
                        "Lista",

                    selected =
                        wybranaZakladka ==
                                0,

                    onClick = {

                        wybranaZakladka =
                            0

                        wybranySklep =
                            null
                    }
                )


                DolnaNawigacjaItem(

                    icon =
                        "🏪",

                    label =
                        "Sklepy",

                    selected =
                        wybranaZakladka ==
                                1,

                    onClick = {

                        wybranaZakladka =
                            1

                        wybranySklep =
                            null
                    }
                )


                DolnaNawigacjaItem(

                    icon =
                        "⚙️",

                    label =
                        "Ustawienia",

                    selected =
                        wybranaZakladka ==
                                2,

                    onClick = {

                        wybranaZakladka =
                            2

                        wybranySklep =
                            null
                    }
                )
            }
        }


        // =====================================================
        // SNACKBAR
        // =====================================================

        SnackbarHost(

            hostState =
                snackbarHostState,

            modifier =
                Modifier
                    .align(
                        Alignment.BottomCenter
                    )
                    .padding(
                        bottom = 80.dp
                    )
        )
    }
}


// =============================================================
// ELEMENT DOLNEJ NAWIGACJI
// =============================================================

@Composable
fun RowScope.DolnaNawigacjaItem(

    icon:
    String,

    label:
    String,

    selected:
    Boolean,

    onClick:
        () -> Unit
) {

    val backgroundColor by
    animateColorAsState(

        targetValue =
            if (selected) {

                MaterialTheme
                    .colorScheme
                    .primaryContainer

            } else {

                Color.Transparent
            },

        label =
            "bottomNavBackground"
    )


    val contentColor by
    animateColorAsState(

        targetValue =
            if (selected) {

                MaterialTheme
                    .colorScheme
                    .onPrimaryContainer

            } else {

                MaterialTheme
                    .colorScheme
                    .onSurfaceVariant
            },

        label =
            "bottomNavContent"
    )


    Column(

        modifier =
            Modifier
                .weight(1f)
                .clickable(
                    onClick =
                        onClick
                )
                .background(
                    color =
                        backgroundColor,

                    shape =
                        RoundedCornerShape(
                            20.dp
                        )
                )
                .padding(
                    horizontal = 14.dp,
                    vertical = 6.dp
                ),

        horizontalAlignment =
            Alignment.CenterHorizontally,

        verticalArrangement =
            Arrangement.Center
    ) {

        Text(

            text =
                icon,

            modifier =
                Modifier.padding(
                    bottom = 1.dp
                )
        )


        Text(

            text =
                label,

            style =
                MaterialTheme
                    .typography
                    .labelMedium,

            color =
                contentColor
        )
    }
}


// =============================================================
// DRAG & DROP PRODUKTÓW
// =============================================================

@Composable
fun ProductDragList(

    produkty:
    List<Produkt>,

    onMove:
        (Int, Int) -> Unit,

    onDragEnd:
        () -> Unit,

    onToggleProduct:
        (Produkt, Boolean) -> Unit,

    onDeleteProduct:
        (Produkt) -> Unit,

    onEditProduct:
        (Produkt) -> Unit
) {

    val listState =
        rememberLazyListState()

    val density =
        androidx.compose.ui.platform.LocalDensity.current


    var initiallyDraggedElement by
    remember {

        mutableStateOf<Produkt?>(null)
    }

    var currentIndexOfDraggedItem by
    remember {

        mutableStateOf<Int?>(null)
    }

    var fingerViewportY by
    remember {

        mutableStateOf(0f)
    }

    var fingerOffsetInItem by
    remember {

        mutableStateOf(0f)
    }

    var draggingItemSize by
    remember {

        mutableStateOf(0)
    }

    var autoScrollSpeed by
    remember {

        mutableStateOf(0f)
    }


    val checkSwap = {

        val currentIndex =
            currentIndexOfDraggedItem

        val initiallyDragged =
            initiallyDraggedElement

        if (
            currentIndex != null &&
            initiallyDragged != null
        ) {

            val currentCenterY =
                fingerViewportY -
                        fingerOffsetInItem +
                        draggingItemSize /
                        2f


            val previousItem =
                listState
                    .layoutInfo
                    .visibleItemsInfo
                    .find {
                        it.index ==
                                currentIndex - 1
                    }

            val nextItem =
                listState
                    .layoutInfo
                    .visibleItemsInfo
                    .find {
                        it.index ==
                                currentIndex + 1
                    }


            var targetIndex =
                currentIndex


            if (
                previousItem != null &&
                currentCenterY <
                previousItem.offset +
                previousItem.size /
                2f
            ) {

                targetIndex =
                    currentIndex - 1

            } else if (
                nextItem != null &&
                currentCenterY >
                nextItem.offset +
                nextItem.size /
                2f
            ) {

                targetIndex =
                    currentIndex + 1
            }


            if (
                targetIndex !=
                currentIndex
            ) {

                onMove(
                    currentIndex,
                    targetIndex
                )

                currentIndexOfDraggedItem =
                    targetIndex
            }
        }
    }


    LaunchedEffect(
        autoScrollSpeed
    ) {

        if (
            autoScrollSpeed != 0f
        ) {

            while (isActive) {

                listState.scrollBy(
                    autoScrollSpeed
                )

                checkSwap()

                delay(16)
            }
        }
    }


    Box(
        modifier =
            Modifier.fillMaxSize()
    ) {

        if (
            produkty.isEmpty()
        ) {

            Box(

                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(
                            horizontal = 32.dp
                        ),

                contentAlignment =
                    Alignment.Center
            ) {

                Column(

                    horizontalAlignment =
                        Alignment.CenterHorizontally,

                    verticalArrangement =
                        Arrangement.spacedBy(
                            8.dp
                        )
                ) {

                    Text(
                        text =
                            "🛒",

                        style =
                            MaterialTheme
                                .typography
                                .displaySmall
                    )

                    Text(

                        text =
                            "Lista jest pusta",

                        style =
                            MaterialTheme
                                .typography
                                .titleMedium
                    )

                    Text(

                        text =
                            "Dodaj pierwszy produkt,\naby rozpocząć zakupy.",

                        style =
                            MaterialTheme
                                .typography
                                .bodyMedium,

                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant,

                        textAlign =
                            androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }

        } else {

            LazyColumn(

                modifier =
                    Modifier.fillMaxSize(),

                state =
                    listState,

                userScrollEnabled =
                    initiallyDraggedElement ==
                            null,

                verticalArrangement =
                    Arrangement.spacedBy(
                        0.dp
                    )
            ) {

                items(

                    items =
                        produkty,

                    key = {
                        it.id
                    }
                ) { produkt ->

                    val isDragging =
                        produkt.id ==
                                initiallyDraggedElement
                                    ?.id


                    val alpha by
                    animateFloatAsState(

                        targetValue =

                            if (
                                isDragging
                            ) {

                                0f

                            } else if (
                                produkt.kupione
                            ) {

                                0.7f

                            } else {

                                1f
                            },

                        label =
                            "alpha"
                    )


                    Box(

                        modifier =
                            Modifier
                    ) {

                        Card(

                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        bottom = 8.dp
                                    )
                                    .graphicsLayer {
                                        this.alpha = alpha
                                    }
                                    .pointerInput(
                                        produkt.id
                                    ) {

                                        detectDragGesturesAfterLongPress(

                                            onDragStart = {
                                                    offset ->

                                                val index =
                                                    produkty.indexOf(
                                                        produkt
                                                    )

                                                if (
                                                    index >= 0
                                                ) {

                                                    val visibleItem =
                                                        listState
                                                            .layoutInfo
                                                            .visibleItemsInfo
                                                            .find {
                                                                it.key ==
                                                                        produkt.id
                                                            }

                                                    if (
                                                        visibleItem !=
                                                        null
                                                    ) {

                                                        fingerOffsetInItem =
                                                            offset.y

                                                        fingerViewportY =
                                                            visibleItem.offset +
                                                                    offset.y

                                                        draggingItemSize =
                                                            visibleItem.size

                                                        currentIndexOfDraggedItem =
                                                            index

                                                        initiallyDraggedElement =
                                                            produkt

                                                        autoScrollSpeed =
                                                            0f
                                                    }
                                                }
                                            },

                                            onDrag = {
                                                    change,
                                                    dragAmount ->

                                                change.consume()

                                                fingerViewportY +=
                                                    dragAmount.y


                                                val viewportHeight =
                                                    listState
                                                        .layoutInfo
                                                        .viewportSize
                                                        .height
                                                        .toFloat()


                                                val scrollThreshold =
                                                    with(
                                                        density
                                                    ) {

                                                        80.dp.toPx()
                                                    }


                                                val distFromTop =
                                                    fingerViewportY -
                                                            fingerOffsetInItem


                                                val distFromBottom =
                                                    viewportHeight -
                                                            (
                                                                    fingerViewportY -
                                                                            fingerOffsetInItem +
                                                                            draggingItemSize
                                                                    )


                                                autoScrollSpeed =

                                                    when {

                                                        distFromTop <
                                                                scrollThreshold -> {

                                                            val factor =
                                                                1f -
                                                                        (
                                                                                distFromTop /
                                                                                        scrollThreshold
                                                                                )
                                                                            .coerceIn(
                                                                                0f,
                                                                                1f
                                                                            )

                                                            -(
                                                                    factor *
                                                                            20f +
                                                                            5f
                                                                    )
                                                        }

                                                        distFromBottom <
                                                                scrollThreshold -> {

                                                            val factor =
                                                                1f -
                                                                        (
                                                                                distFromBottom /
                                                                                        scrollThreshold
                                                                                )
                                                                            .coerceIn(
                                                                                0f,
                                                                                1f
                                                                            )

                                                            factor *
                                                                    20f +
                                                                    5f
                                                        }

                                                        else ->
                                                            0f
                                                    }


                                                checkSwap()
                                            },

                                            onDragEnd = {

                                                onDragEnd()

                                                initiallyDraggedElement =
                                                    null

                                                currentIndexOfDraggedItem =
                                                    null

                                                autoScrollSpeed =
                                                    0f
                                            },

                                            onDragCancel = {

                                                initiallyDraggedElement =
                                                    null

                                                currentIndexOfDraggedItem =
                                                    null

                                                autoScrollSpeed =
                                                    0f
                                            }
                                        )
                                    },

                            shape =
                                RoundedCornerShape(
                                    28.dp
                                ),

                            colors =
                                CardDefaults
                                    .cardColors(
                                        containerColor =
                                            MaterialTheme
                                                .colorScheme
                                                .surface
                                    ),

                            elevation =
                                CardDefaults
                                    .cardElevation(
                                        defaultElevation =
                                            6.dp
                                    )
                        ) {

                            ProductCardContent(

                                produkt =
                                    produkt,

                                onToggleProduct =
                                    onToggleProduct,

                                onDeleteProduct =
                                    onDeleteProduct,

                                onEditProduct =
                                    onEditProduct
                            )
                        }
                    }
                }
            }
        }


        if (
            initiallyDraggedElement !=
            null
        ) {

            Card(

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .offset {

                            IntOffset(

                                0,

                                (
                                        fingerViewportY -
                                                fingerOffsetInItem
                                        )
                                    .roundToInt()
                            )
                        }
                        .scale(
                            1.04f
                        )
                        .graphicsLayer {

                            shadowElevation =
                                28.dp.toPx()

                            alpha =
                                1f
                        },

                shape =
                    RoundedCornerShape(
                        28.dp
                    ),

                colors =
                    CardDefaults
                        .cardColors(
                            containerColor =
                                MaterialTheme
                                    .colorScheme
                                    .surface
                        ),

                elevation =
                    CardDefaults
                        .cardElevation(
                            defaultElevation =
                                24.dp
                        )
            ) {

                ProductCardContent(

                    produkt =
                        initiallyDraggedElement!!,

                    onToggleProduct = {
                            _, _ ->
                    },

                    onDeleteProduct = {},

                    onEditProduct = {}
                )
            }
        }
    }
}


// =============================================================
// DRAG & DROP SKLEPÓW
// =============================================================

@Composable
fun SklepDragList(

    sklepy:
    List<Sklep>,

    onMove:
        (Int, Int) -> Unit,

    onDragStart:
        () -> Unit,

    onDragEnd:
        () -> Unit,

    onClick:
        (String) -> Unit,

    onEdit:
        (Sklep) -> Unit,

    onDelete:
        (Sklep) -> Unit
) {

    val listState =
        rememberLazyListState()

    val density =
        androidx.compose.ui.platform.LocalDensity.current


    var draggedShop by
    remember {

        mutableStateOf<Sklep?>(null)
    }

    var currentIndex by
    remember {

        mutableStateOf<Int?>(null)
    }

    var fingerViewportY by
    remember {

        mutableStateOf(0f)
    }

    var fingerOffsetInItem by
    remember {

        mutableStateOf(0f)
    }

    var draggedItemSize by
    remember {

        mutableStateOf(0)
    }

    var autoScrollSpeed by
    remember {

        mutableStateOf(0f)
    }


    val checkSwap = {

        val index =
            currentIndex

        if (
            index != null &&
            draggedShop != null
        ) {

            val centerY =
                fingerViewportY -
                        fingerOffsetInItem +
                        draggedItemSize /
                        2f


            val previous =
                listState
                    .layoutInfo
                    .visibleItemsInfo
                    .find {
                        it.index ==
                                index - 1
                    }

            val next =
                listState
                    .layoutInfo
                    .visibleItemsInfo
                    .find {
                        it.index ==
                                index + 1
                    }


            var target =
                index


            if (
                previous != null &&
                centerY <
                previous.offset +
                previous.size /
                2f
            ) {

                target =
                    index - 1

            } else if (
                next != null &&
                centerY >
                next.offset +
                next.size /
                2f
            ) {

                target =
                    index + 1
            }


            if (
                target !=
                index
            ) {

                onMove(
                    index,
                    target
                )

                currentIndex =
                    target
            }
        }
    }


    LaunchedEffect(
        autoScrollSpeed
    ) {

        if (
            autoScrollSpeed != 0f
        ) {

            while (isActive) {

                listState.scrollBy(
                    autoScrollSpeed
                )

                checkSwap()

                delay(16)
            }
        }
    }


    Box(
        modifier =
            Modifier.fillMaxSize()
    ) {

        LazyColumn(

            modifier =
                Modifier.fillMaxSize(),

            state =
                listState,

            userScrollEnabled =
                draggedShop == null,

            verticalArrangement =
                Arrangement.spacedBy(
                    10.dp
                )
        ) {

            items(

                items =
                    sklepy,

                key = {
                    it.id
                }
            ) { sklep ->

                val isDragging =
                    sklep.id ==
                            draggedShop?.id


                val alpha by
                animateFloatAsState(

                    targetValue =
                        if (
                            isDragging
                        ) {
                            0f
                        } else {
                            1f
                        },

                    label =
                        "shopAlpha"
                )


                Box(

                    modifier = Modifier
                ) {

                    Card(

                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    this.alpha = alpha
                                }
                                .pointerInput(
                                    sklep.id
                                ) {

                                    detectDragGesturesAfterLongPress(

                                        onDragStart = {
                                                offset ->

                                            val index =
                                                sklepy.indexOf(
                                                    sklep
                                                )

                                            if (
                                                index >= 0
                                            ) {

                                                val visibleItem =
                                                    listState
                                                        .layoutInfo
                                                        .visibleItemsInfo
                                                        .find {
                                                            it.key ==
                                                                    sklep.id
                                                        }

                                                if (
                                                    visibleItem !=
                                                    null
                                                ) {

                                                    draggedShop =
                                                        sklep

                                                    currentIndex =
                                                        index

                                                    draggedItemSize =
                                                        visibleItem.size

                                                    fingerOffsetInItem =
                                                        offset.y

                                                    fingerViewportY =
                                                        visibleItem.offset +
                                                                offset.y

                                                    autoScrollSpeed =
                                                        0f

                                                    onDragStart()
                                                }
                                            }
                                        },

                                        onDrag = {
                                                change,
                                                dragAmount ->

                                            change.consume()

                                            fingerViewportY +=
                                                dragAmount.y


                                            val viewportHeight =
                                                listState
                                                    .layoutInfo
                                                    .viewportSize
                                                    .height
                                                    .toFloat()


                                            val threshold =
                                                with(
                                                    density
                                                ) {

                                                    80.dp.toPx()
                                                }


                                            val topDistance =
                                                fingerViewportY -
                                                        fingerOffsetInItem


                                            val bottomDistance =
                                                viewportHeight -
                                                        (
                                                                fingerViewportY -
                                                                        fingerOffsetInItem +
                                                                        draggedItemSize
                                                                )


                                            autoScrollSpeed =

                                                when {

                                                    topDistance <
                                                            threshold -> {

                                                        val factor =
                                                            1f -
                                                                    (
                                                                            topDistance /
                                                                                    threshold
                                                                            )
                                                                        .coerceIn(
                                                                            0f,
                                                                            1f
                                                                        )

                                                        -(
                                                                factor *
                                                                        20f +
                                                                        5f
                                                                )
                                                    }

                                                    bottomDistance <
                                                            threshold -> {

                                                        val factor =
                                                            1f -
                                                                    (
                                                                            bottomDistance /
                                                                                    threshold
                                                                            )
                                                                        .coerceIn(
                                                                            0f,
                                                                            1f
                                                                        )

                                                        factor *
                                                                20f +
                                                                5f
                                                    }

                                                    else ->
                                                        0f
                                                }


                                            checkSwap()
                                        },

                                        onDragEnd = {

                                            autoScrollSpeed =
                                                0f

                                            draggedShop =
                                                null

                                            currentIndex =
                                                null

                                            onDragEnd()
                                        },

                                        onDragCancel = {

                                            autoScrollSpeed =
                                                0f

                                            draggedShop =
                                                null

                                            currentIndex =
                                                null

                                            onDragEnd()
                                        }
                                    )
                                },

                        onClick = {

                            onClick(
                                sklep.id
                            )
                        },

                        shape =
                            RoundedCornerShape(
                                24.dp
                            ),

                        elevation =
                            CardDefaults
                                .cardElevation(
                                    defaultElevation =
                                        if (
                                            isDragging
                                        ) {
                                            14.dp
                                        } else {
                                            5.dp
                                        }
                                )
                    ) {

                        SklepCardContent(

                            sklep =
                                sklep,

                            onEdit = {

                                onEdit(
                                    sklep
                                )
                            },

                            onDelete = {

                                onDelete(
                                    sklep
                                )
                            }
                        )
                    }
                }
            }
        }


        if (
            draggedShop != null
        ) {

            Card(

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .offset {

                            IntOffset(

                                0,

                                (
                                        fingerViewportY -
                                                fingerOffsetInItem
                                        )
                                    .roundToInt()
                            )
                        }
                        .scale(
                            1.04f
                        )
                        .graphicsLayer {

                            shadowElevation =
                                28.dp.toPx()

                            alpha =
                                1f
                        },

                shape =
                    RoundedCornerShape(
                        24.dp
                    ),

                elevation =
                    CardDefaults
                        .cardElevation(
                            defaultElevation =
                                24.dp
                        )
            ) {

                SklepCardContent(

                    sklep =
                        draggedShop!!,

                    onEdit = {},

                    onDelete = {}
                )
            }
        }
    }
}


// =============================================================
// ZAWARTOŚĆ KAFELKA SKLEPU
// =============================================================

@Composable
fun SklepCardContent(

    sklep:
    Sklep,

    onEdit:
        () -> Unit,

    onDelete:
        () -> Unit
) {

    Row(

        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 10.dp,
                    vertical = 8.dp
                ),

        verticalAlignment =
            Alignment.CenterVertically
    ) {

        // =====================================================
        // IKONA / LOGO SKLEPU
        // =====================================================

        Box(

            modifier =
                Modifier
                    .size(52.dp)
                    .background(
                        MaterialTheme
                            .colorScheme
                            .surfaceVariant,
                        RoundedCornerShape(
                            16.dp
                        )
                    ),

            contentAlignment =
                Alignment.Center
        ) {

            SklepIcon(

                sklep =
                    sklep,

                modifier =
                    Modifier
                        .size(40.dp)
            )
        }


        // =====================================================
        // NAZWA SKLEPU
        // Długie nazwy można przesuwać poziomo.
        // =====================================================

        Column(

            modifier =
                Modifier
                    .weight(1f)
                    .padding(
                        start = 12.dp,
                        end = 4.dp
                    )
        ) {

            Row(

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(
                            rememberScrollState()
                        )
            ) {

                Text(

                    text =
                        sklep.nazwa,

                    style =
                        MaterialTheme
                            .typography
                            .titleMedium,

                    maxLines =
                        1,

                    softWrap =
                        false
                )
            }


            Text(

                text =
                    "Lista zakupów",

                style =
                    MaterialTheme
                        .typography
                        .labelSmall,

                maxLines =
                    1,

                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )
        }


        // =====================================================
        // EDYCJA
        // =====================================================

        Box(

            modifier =
                Modifier
                    .size(36.dp)
                    .clickable {

                        onEdit()
                    },

            contentAlignment =
                Alignment.Center
        ) {

            Text(
                "✏️"
            )
        }


        // =====================================================
        // USUWANIE
        // =====================================================

        Box(

            modifier =
                Modifier
                    .size(36.dp)
                    .clickable {

                        onDelete()
                    },

            contentAlignment =
                Alignment.Center
        ) {

            Text(
                "🗑️"
            )
        }


        // =====================================================
        // UCHWYT PRZECIĄGANIA
        // =====================================================

        Text(

            text =
                "⋮⋮",

            style =
                MaterialTheme
                    .typography
                    .titleLarge,

            modifier =
                Modifier.padding(
                    start = 2.dp
                )
        )
    }
}


// =============================================================
// EKRAN SKLEPÓW
// =============================================================

@Composable
fun SklepyScreen(

    sklepy:
    List<Sklep>,

    onSklepClick:
        (String) -> Unit,

    onDodajSklep:
        () -> Unit,

    onEdytujSklep:
        (Sklep) -> Unit,

    onUsunSklep:
        (Sklep) -> Unit,

    onDragStart:
        () -> Unit,

    onDragEnd:
        () -> Unit,

    onMove:
        (Int, Int) -> Unit
) {

    Column(

        modifier =
            Modifier
                .fillMaxSize()
                .background(
                    MaterialTheme
                        .colorScheme
                        .background
                )
                .padding(
                    start = 24.dp,
                    top = 24.dp,
                    end = 24.dp,
                    bottom = 88.dp
                ),

        verticalArrangement =
            Arrangement.spacedBy(
                12.dp
            )
    ) {

        Row(

            modifier =
                Modifier.fillMaxWidth(),

            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Column(

                modifier =
                    Modifier.weight(1f)
            ) {

                Text(

                    text =
                        "🏪 Sklepy",

                    style =
                        MaterialTheme
                            .typography
                            .headlineSmall
                )

                Text(

                    text =
                        "Przytrzymaj sklep, aby zmienić kolejność",

                    style =
                        MaterialTheme
                            .typography
                            .bodySmall,

                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )
            }


            Button(
                onClick =
                    onDodajSklep
            ) {

                Text(
                    "＋ Sklep"
                )
            }
        }


        if (
            sklepy.isEmpty()
        ) {

            Box(

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),

                contentAlignment =
                    Alignment.Center
            ) {

                Column(

                    horizontalAlignment =
                        Alignment.CenterHorizontally,

                    verticalArrangement =
                        Arrangement.spacedBy(
                            8.dp
                        )
                ) {

                    Text(
                        text =
                            "🏪",

                        style =
                            MaterialTheme
                                .typography
                                .displaySmall
                    )

                    Text(

                        text =
                            "Brak sklepów",

                        style =
                            MaterialTheme
                                .typography
                                .titleMedium
                    )

                    Text(

                        text =
                            "Dodaj pierwszy sklep,\naby utworzyć własną listę.",

                        style =
                            MaterialTheme
                                .typography
                                .bodyMedium,

                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant,

                        textAlign =
                            androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }

        } else {

            Box(

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
            ) {

                SklepDragList(

                    sklepy =
                        sklepy,

                    onMove =
                        onMove,

                    onDragStart =
                        onDragStart,

                    onDragEnd =
                        onDragEnd,

                    onClick =
                        onSklepClick,

                    onEdit =
                        onEdytujSklep,

                    onDelete =
                        onUsunSklep
                )
            }
        }
    }
}


// =============================================================
// LISTA KONKRETNEGO SKLEPU
// =============================================================

@Composable
fun ListaSklepuScreen(

    sklepId:
    String,

    sklep:
    Sklep?,

    lista:
    List<Produkt>,

    onBack:
        () -> Unit
) {

    val produktySklepu =
        lista
            .filter {
                it.kategoria ==
                        sklepId
            }
            .sortedBy {
                it.kolejnosc
            }


    Column(

        modifier =
            Modifier
                .fillMaxSize()
                .background(
                    MaterialTheme
                        .colorScheme
                        .background
                )
                .padding(
                    start = 24.dp,
                    top = 16.dp,
                    end = 24.dp,
                    bottom = 88.dp
                )
    ) {

        TextButton(
            onClick =
                onBack
        ) {

            Text(
                "← Sklepy"
            )
        }


        Row(

            verticalAlignment =
                Alignment.CenterVertically
        ) {

            if (
                sklep != null
            ) {

                SklepIcon(

                    sklep =
                        sklep,

                    modifier =
                        Modifier
                            .height(50.dp)
                            .fillMaxWidth(
                                0.15f
                            )
                )
            }


            Text(

                text =
                    sklep?.nazwa
                        ?: sklepId,

                modifier =
                    Modifier.padding(
                        start = 10.dp
                    ),

                style =
                    MaterialTheme
                        .typography
                        .headlineSmall
            )
        }


        Spacer(
            modifier =
                Modifier.height(
                    16.dp
                )
        )


        if (
            produktySklepu.isEmpty()
        ) {

            Box(

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),

                contentAlignment =
                    Alignment.Center
            ) {

                Text(
                    "Brak produktów w tym sklepie"
                )
            }

        } else {

            LazyColumn(

                modifier =
                    Modifier.weight(
                        1f
                    ),

                verticalArrangement =
                    Arrangement.spacedBy(
                        8.dp
                    )
            ) {

                items(

                    items =
                        produktySklepu,

                    key = {
                        it.id
                    }
                ) { produkt ->

                    Card(

                        modifier =
                            Modifier
                                .fillMaxWidth(),

                        shape =
                            RoundedCornerShape(
                                20.dp
                            )
                    ) {

                        Row(

                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        12.dp
                                    ),

                            verticalAlignment =
                                Alignment.CenterVertically
                        ) {

                            Checkbox(

                                checked =
                                    produkt.kupione,

                                onCheckedChange = {
                                        checked ->

                                    val now =

                                        if (
                                            checked
                                        ) {

                                            System
                                                .currentTimeMillis()

                                        } else {

                                            0L
                                        }

                                    FirebaseFirestore
                                        .getInstance()
                                        .collection(
                                            "zakupy"
                                        )
                                        .document(
                                            produkt.id
                                        )
                                        .update(

                                            mapOf(

                                                "kupione" to
                                                        checked,

                                                "kupioneOd" to
                                                        now
                                            )
                                        )
                                }
                            )


                            Column(

                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .padding(
                                            start = 8.dp
                                        )
                            ) {

                                Text(

                                    text =
                                        produkt.nazwa,

                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodyLarge,

                                    textDecoration =

                                        if (
                                            produkt.kupione
                                        ) {

                                            TextDecoration
                                                .LineThrough

                                        } else {

                                            TextDecoration
                                                .None
                                        }
                                )


                                Text(

                                    text =
                                        "Dodał: ${produkt.dodal}",

                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodySmall,

                                    color =
                                        MaterialTheme
                                            .colorScheme
                                            .onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}


// =============================================================
// KAFEL PRODUKTU
// =============================================================

@Composable
fun ProductCardContent(

    produkt:
    Produkt,

    onToggleProduct:
        (Produkt, Boolean) -> Unit,

    onDeleteProduct:
        (Produkt) -> Unit,

    onEditProduct:
        (Produkt) -> Unit
) {

    var checked by
    remember(
        produkt.id,
        produkt.kupione
    ) {

        mutableStateOf(
            produkt.kupione
        )
    }


    Row(

        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 8.dp,
                    vertical = 4.dp
                ),

        verticalAlignment =
            Alignment.CenterVertically
    ) {

        Checkbox(

            modifier =
                Modifier.scale(
                    0.78f
                ),

            checked =
                checked,

            onCheckedChange = {

                checked =
                    it

                onToggleProduct(
                    produkt,
                    it
                )
            }
        )


        Column(

            modifier =
                Modifier
                    .weight(1f)
                    .padding(
                        start = 4.dp,
                        end = 4.dp
                    )
        ) {

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(
                            rememberScrollState()
                        )
            ) {

                Text(

                    text =
                        produkt.nazwa,

                    style =
                        MaterialTheme
                            .typography
                            .bodyMedium,

                    maxLines =
                        1,

                    softWrap =
                        false,

                    textDecoration =

                        if (
                            produkt.kupione
                        ) {

                            TextDecoration
                                .LineThrough

                        } else {

                            TextDecoration
                                .None
                        }
                )
            }


            Text(

                text =
                    "Dodał: ${produkt.dodal}",

                style =
                    MaterialTheme
                        .typography
                        .labelSmall,

                maxLines =
                    1,

                overflow =
                    androidx.compose.ui.text.style.TextOverflow.Ellipsis,

                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )
        }


        // Kompaktowe przyciski — nie rozciągają kafelka jak TextButton.
        Box(

            modifier =
                Modifier
                    .size(36.dp)
                    .clickable {

                        onEditProduct(
                            produkt
                        )
                    },

            contentAlignment =
                Alignment.Center
        ) {

            Text(
                "✏️"
            )
        }


        Box(

            modifier =
                Modifier
                    .size(36.dp)
                    .clickable {

                        onDeleteProduct(
                            produkt
                        )
                    },

            contentAlignment =
                Alignment.Center
        ) {

            Text(
                "🗑️"
            )
        }


        Text(

            text =
                "⋮⋮",

            modifier =
                Modifier.padding(
                    start = 2.dp
                )
        )
    }
}


// =============================================================
// WYBÓR LISTY
// =============================================================

@Composable
fun ListaWyboruButton(

    emoji:
    String,

    nazwa:
    String,

    sklep:
    Sklep? = null,

    onClick:
        () -> Unit
) {

    Card(

        onClick =
            onClick,

        modifier =
            Modifier.fillMaxWidth(),

        shape =
            RoundedCornerShape(
                18.dp
            ),

        elevation =
            CardDefaults
                .cardElevation(
                    defaultElevation =
                        3.dp
                )
    ) {

        Row(

            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        14.dp
                    ),

            verticalAlignment =
                Alignment.CenterVertically
        ) {

            if (
                sklep != null
            ) {

                SklepIcon(

                    sklep =
                        sklep,

                    modifier =
                        Modifier
                            .height(
                                42.dp
                            )
                            .fillMaxWidth(
                                0.15f
                            )
                )

            } else {

                Text(

                    text =
                        emoji,

                    style =
                        MaterialTheme
                            .typography
                            .headlineSmall
                )
            }


            Text(

                text =
                    nazwa,

                modifier =
                    Modifier.padding(
                        start = 14.dp
                    ),

                style =
                    MaterialTheme
                        .typography
                        .titleMedium
            )
        }
    }
}


// =============================================================
// FILTR
// =============================================================

@Composable
fun FilterButton(

    text:
    String,

    selected:
    Boolean,

    onClick:
        () -> Unit
) {

    val backgroundColor by
    animateColorAsState(

        targetValue =

            if (
                selected
            ) {

                MaterialTheme
                    .colorScheme
                    .primary

            } else {

                MaterialTheme
                    .colorScheme
                    .surfaceVariant
            },

        label =
            "filterBackground"
    )


    val contentColor by
    animateColorAsState(

        targetValue =

            if (
                selected
            ) {

                MaterialTheme
                    .colorScheme
                    .onPrimary

            } else {

                MaterialTheme
                    .colorScheme
                    .onSurfaceVariant
            },

        label =
            "filterContent"
    )


    val scale by
    animateFloatAsState(

        targetValue =

            if (
                selected
            ) {
                1.03f
            } else {
                1f
            },

        label =
            "filterScale"
    )


    Card(

        onClick =
            onClick,

        modifier =
            Modifier
                .scale(scale)
                .height(20.dp),

        shape =
            RoundedCornerShape(
                16.dp
            ),

        colors =
            CardDefaults
                .cardColors(
                    containerColor =
                        backgroundColor
                ),

        elevation =
            CardDefaults
                .cardElevation(

                    defaultElevation =

                        if (
                            selected
                        ) {
                            3.dp
                        } else {
                            1.dp
                        }
                )
    ) {

        Box(

            modifier =
                Modifier.padding(
                    horizontal = 8.dp
                ),

            contentAlignment =
                Alignment.Center
        ) {

            Text(

                text =

                    if (
                        selected
                    ) {

                        "✓ $text"

                    } else {

                        text
                    },

                color =
                    contentColor,

                style =
                    MaterialTheme
                        .typography
                        .labelMedium
            )
        }
    }
}


// =============================================================
// DODAJ PRODUKT
// =============================================================

fun dodajProduktDoListy(

    nazwa:
    String,

    imie:
    String,

    kategoria:
    String
) {

    val produkt =
        nazwa.trim()

    if (
        produkt.isEmpty()
    ) {
        return
    }


    FirebaseFirestore
        .getInstance()
        .collection(
            "zakupy"
        )
        .add(

            hashMapOf(

                "nazwa" to
                        produkt,

                "dodal" to
                        imie,

                "kupione" to
                        false,

                "kupioneOd" to
                        0L,

                "kolejnosc" to
                        System
                            .currentTimeMillis(),

                "kategoria" to
                        kategoria
            )
        )
}


// =============================================================
// ZAPIS KOLEJNOŚCI PRODUKTÓW
// =============================================================

fun zapiszNowaKolejnosc(

    produkty:
    List<Produkt>
) {

    val db =
        FirebaseFirestore
            .getInstance()


    produkty.forEachIndexed {
            index,
            produkt ->

        val nowaKolejnosc =
            index.toLong()

        produkt.kolejnosc =
            nowaKolejnosc

        db.collection(
            "zakupy"
        )
            .document(
                produkt.id
            )
            .update(

                "kolejnosc",
                nowaKolejnosc
            )
    }
}


// =============================================================
// ZAPIS KOLEJNOŚCI SKLEPÓW
// =============================================================

fun zapiszNowaKolejnoscSklepow(

    sklepy:
    List<Sklep>
) {

    val db =
        FirebaseFirestore
            .getInstance()


    sklepy.forEachIndexed {
            index,
            sklep ->

        val nowaKolejnosc =
            index.toLong()

        db.collection(
            "sklepy"
        )
            .document(
                sklep.id
            )
            .update(

                "kolejnosc",
                nowaKolejnosc
            )
    }
}


// =============================================================
// DODAJ / EDYTUJ SKLEP
// =============================================================

@Composable
fun DodajLubEdytujSklepDialog(

    sklep:
    Sklep?,

    onDismiss:
        () -> Unit,

    onSaved:
        () -> Unit
) {

    val context =
        LocalContext.current

    val db =
        FirebaseFirestore
            .getInstance()


    var nazwa by remember(
        sklep?.id
    ) {

        mutableStateOf(
            sklep?.nazwa ?: ""
        )
    }


    var emoji by remember(
        sklep?.id
    ) {

        mutableStateOf(
            sklep?.emoji ?: "🏪"
        )
    }


    var typIkony by remember(
        sklep?.id
    ) {

        mutableStateOf(
            sklep?.typIkony ?: "emoji"
        )
    }


    var wybraneUri by remember(
        sklep?.id
    ) {

        mutableStateOf<Uri?>(null)
    }


    var obrazDane by remember(
        sklep?.id
    ) {

        mutableStateOf(
            sklep?.obrazDane ?: ""
        )
    }


    var blad by remember {

        mutableStateOf<String?>(null)
    }


    var zapisywanie by remember {

        mutableStateOf(false)
    }


    val launcher =
        androidx.activity.compose
            .rememberLauncherForActivityResult(

                contract =
                    ActivityResultContracts
                        .GetContent()

            ) { uri ->

                if (
                    uri != null
                ) {

                    wybraneUri =
                        uri

                    typIkony =
                        "image"
                }
            }


    AlertDialog(

        onDismissRequest = {

            if (
                !zapisywanie
            ) {

                onDismiss()
            }
        },

        title = {

            Text(

                if (
                    sklep == null
                ) {

                    "➕ Dodaj sklep"

                } else {

                    "✏️ Edytuj sklep"
                }
            )
        },

        text = {

            Column(

                verticalArrangement =
                    Arrangement.spacedBy(
                        12.dp
                    )
            ) {

                OutlinedTextField(

                    value =
                        nazwa,

                    onValueChange = {
                        nazwa = it
                    },

                    modifier =
                        Modifier.fillMaxWidth(),

                    label = {
                        Text(
                            "Nazwa sklepu"
                        )
                    },

                    singleLine =
                        true
                )


                Text(
                    "Ikona sklepu"
                )


                Row(

                    modifier =
                        Modifier.fillMaxWidth(),

                    horizontalArrangement =
                        Arrangement.spacedBy(
                            8.dp
                        )
                ) {

                    FilterButton(

                        text =
                            "😀 Emoji",

                        selected =
                            typIkony ==
                                    "emoji",

                        onClick = {

                            // Przełączenie na emoji
                            // natychmiast usuwa stare logo
                            // z formularza.
                            typIkony =
                                "emoji"

                            wybraneUri =
                                null

                            obrazDane =
                                ""
                        }
                    )


                    FilterButton(

                        text =
                            "🖼️ Logo",

                        selected =
                            typIkony ==
                                    "image",

                        onClick = {

                            typIkony =
                                "image"

                            launcher.launch(
                                "image/*"
                            )
                        }
                    )
                }


                if (
                    typIkony ==
                    "emoji"
                ) {

                    OutlinedTextField(

                        value =
                            emoji,

                        onValueChange = {
                            emoji = it
                        },

                        modifier =
                            Modifier.fillMaxWidth(),

                        label = {
                            Text(
                                "Emoji"
                            )
                        },

                        singleLine =
                            true
                    )

                } else {

                    if (
                        wybraneUri !=
                        null
                    ) {

                        AsyncImage(

                            model =
                                wybraneUri,

                            contentDescription =
                                null,

                            contentScale =
                                ContentScale.Fit,

                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(
                                        120.dp
                                    )
                        )

                    } else if (
                        obrazDane.isNotEmpty()
                    ) {

                        SklepIcon(

                            sklep =
                                Sklep(

                                    nazwa =
                                        nazwa,

                                    typIkony =
                                        "image",

                                    obrazDane =
                                        obrazDane
                                ),

                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(
                                        120.dp
                                    )
                        )

                    } else {

                        Text(
                            "Nie wybrano zdjęcia"
                        )
                    }

                    // -------------------------------------------------
                    // USUWANIE AKTUALNEGO LOGO
                    // -------------------------------------------------
                    if (
                        wybraneUri != null ||
                        obrazDane.isNotEmpty()
                    ) {

                        TextButton(

                            onClick = {

                                wybraneUri =
                                    null

                                obrazDane =
                                    ""

                                typIkony =
                                    "emoji"
                            }
                        ) {

                            Text(
                                "🗑️ Usuń logo"
                            )
                        }
                    }
                }


                blad?.let {

                    Text(

                        text =
                            "❌ $it",

                        color =
                            MaterialTheme
                                .colorScheme
                                .error
                    )
                }
            }
        },

        confirmButton = {

            TextButton(

                enabled =
                    !zapisywanie,

                onClick = {

                    val cleanName =
                        nazwa.trim()


                    if (
                        cleanName.isEmpty()
                    ) {

                        blad =
                            "Podaj nazwę sklepu"

                        return@TextButton
                    }


                    zapisywanie =
                        true

                    val id =

                        sklep?.id
                            ?: cleanName
                                .lowercase()
                                .replace(
                                    " ",
                                    "_"
                                )
                                .replace(
                                    Regex(
                                        "[^a-z0-9ąćęłńóśźż_]"
                                    ),
                                    ""
                                )


                    val kolejnosc =

                        sklep?.kolejnosc
                            ?: System
                                .currentTimeMillis()


                    fun zapisz(
                        finalImage:
                        String
                    ) {

                        db.collection(
                            "sklepy"
                        )
                            .document(
                                id
                            )
                            .set(

                                mapOf(

                                    "nazwa" to
                                            cleanName,

                                    "typIkony" to
                                            if (
                                                finalImage
                                                    .isNotEmpty()
                                            ) {
                                                "image"
                                            } else {
                                                "emoji"
                                            },

                                    "emoji" to
                                            emoji,

                                    "obrazDane" to
                                            finalImage,

                                    "kolejnosc" to
                                            kolejnosc
                                )
                            )
                            .addOnSuccessListener {

                                zapisywanie =
                                    false

                                onSaved()
                            }
                            .addOnFailureListener {
                                    error ->

                                zapisywanie =
                                    false

                                blad =
                                    error.message
                                        ?: "Nie udało się zapisać sklepu"
                            }
                    }


                    if (
                        typIkony ==
                        "image" &&
                        wybraneUri !=
                        null
                    ) {

                        val encoded =
                            imageUriToBase64(
                                context,
                                wybraneUri!!
                            )


                        if (
                            encoded ==
                            null
                        ) {

                            zapisywanie =
                                false

                            blad =
                                "Nie udało się przetworzyć zdjęcia"

                        } else {

                            obrazDane =
                                encoded

                            zapisz(
                                encoded
                            )
                        }

                    } else {

                        zapisz(

                            if (
                                typIkony ==
                                "image"
                            ) {

                                obrazDane

                            } else {

                                ""
                            }
                        )
                    }
                }
            ) {

                Text(

                    if (
                        zapisywanie
                    ) {

                        "ZAPISYWANIE..."

                    } else if (
                        sklep == null
                    ) {

                        "DODAJ"

                    } else {

                        "ZAPISZ"
                    }
                )
            }
        },

        dismissButton = {

            TextButton(

                enabled =
                    !zapisywanie,

                onClick =
                    onDismiss
            ) {

                Text(
                    "ANULUJ"
                )
            }
        }
    )
}


// =============================================================
// USTAWIENIA
// =============================================================


// =============================================================
// USTAWIENIA APLIKACJI
// =============================================================

private const val PREFS_SETTINGS =
    "lista_zakupow_settings"

private const val KEY_AUTO_DELETE =
    "auto_delete"

private const val KEY_DELETE_MINUTES =
    "delete_minutes"

private const val KEY_THEME =
    "theme"

private const val KEY_DEFAULT_SORT =
    "default_sort"

private const val KEY_CONFIRM_DELETE =
    "confirm_delete"

private const val KEY_HAPTICS =
    "haptics"


private fun settingsPrefs(
    context: Context
) =
    context.getSharedPreferences(
        PREFS_SETTINGS,
        Context.MODE_PRIVATE
    )


@Composable
fun UstawieniaScreen(
    currentEmail: String,
    onEmailChanged: (String) -> Unit,
    onThemeChanged: (String) -> Unit,
    onHapticsChanged: (Boolean) -> Unit,
    onLogout: () -> Unit
) {

    val context =
        LocalContext.current

    val prefs =
        remember {
            settingsPrefs(context)
        }

    var autoDelete by remember {
        mutableStateOf(
            prefs.getBoolean(
                KEY_AUTO_DELETE,
                true
            )
        )
    }

    var deleteMinutes by remember {
        mutableStateOf(
            prefs.getInt(
                KEY_DELETE_MINUTES,
                20
            )
        )
    }

    var theme by remember {
        mutableStateOf(
            prefs.getString(
                KEY_THEME,
                "system"
            ) ?: "system"
        )
    }

    var defaultSort by remember {
        mutableStateOf(
            prefs.getString(
                KEY_DEFAULT_SORT,
                "reczna"
            ) ?: "reczna"
        )
    }

    var confirmDelete by remember {
        mutableStateOf(
            prefs.getBoolean(
                KEY_CONFIRM_DELETE,
                true
            )
        )
    }

    var haptics by remember {
        mutableStateOf(
            prefs.getBoolean(
                KEY_HAPTICS,
                true
            )
        )
    }

    var dialog by remember {
        mutableStateOf<String?>(null)
    }

    var nowyEmail by remember { mutableStateOf(currentEmail) }
    var emailTrwa by remember { mutableStateOf(false) }
    var komunikatEmail by remember { mutableStateOf<String?>(null) }

    // =========================================================
    // AKTUALIZACJE — ręczne sprawdzanie
    // =========================================================

    var sprawdzanieAktualizacji by remember {
        mutableStateOf(false)
    }

    var komunikatAktualizacji by remember {
        mutableStateOf<String?>(null)
    }

    var dostepnaAktualizacjaUstawienia by remember {
        mutableStateOf<GitHubRelease?>(null)
    }


    fun saveBoolean(
        key: String,
        value: Boolean
    ) {

        prefs.edit()
            .putBoolean(
                key,
                value
            )
            .apply()
    }


    fun saveInt(
        key: String,
        value: Int
    ) {

        prefs.edit()
            .putInt(
                key,
                value
            )
            .apply()
    }


    fun saveString(
        key: String,
        value: String
    ) {

        prefs.edit()
            .putString(
                key,
                value
            )
            .apply()
    }


    Column(

        modifier =
            Modifier
                .fillMaxSize()
                .background(
                    MaterialTheme
                        .colorScheme
                        .background
                )
                .verticalScroll(
                    rememberScrollState()
                )
                .padding(
                    start = 20.dp,
                    top = 20.dp,
                    end = 20.dp,
                    bottom = 110.dp
                ),

        verticalArrangement =
            Arrangement.spacedBy(
                10.dp
            )
    ) {

        Text(

            text =
                "⚙️ Ustawienia",

            style =
                MaterialTheme
                    .typography
                    .headlineSmall
        )


        Text(

            text =
                "Dostosuj działanie aplikacji do swoich potrzeb.",

            style =
                MaterialTheme
                    .typography
                    .bodySmall,

            color =
                MaterialTheme
                    .colorScheme
                    .onSurfaceVariant
        )


        Spacer(
            modifier =
                Modifier.height(
                    4.dp
                )
        )


        SettingsSectionTitle(
            "🎨 Wygląd"
        )


        SettingsCard {

            SettingsRow(

                title =
                    "Motyw aplikacji",

                subtitle =
                    when (theme) {
                        "dark" ->
                            "Ciemny"
                        "light" ->
                            "Jasny"
                        else ->
                            "Systemowy"
                    },

                value =
                    "›",

                onClick = {
                    dialog =
                        "theme"
                }
            )
        }


        SettingsSectionTitle(
            "👤 Konto"
        )

        SettingsCard {
            SettingsRow(
                title = "Zalogowane konto",
                subtitle = if (currentEmail.isBlank()) "Nie ustawiono" else currentEmail,
                value = "›",
                onClick = {
                    nowyEmail = currentEmail
                    komunikatEmail = null
                    dialog = "email"
                }
            )

            Divider()

            SettingsRow(
                title = "Resetuj hasło",
                subtitle = "Wyślij link na przypisany e-mail",
                value = "›",
                onClick = {
                    // Reset hasła jest dostępny również z ekranu logowania.
                    dialog = "email_reset_info"
                }
            )

            Divider()

            SettingsRow(
                title = "Wyloguj",
                subtitle = "Zakończ sesję na tym urządzeniu",
                value = "↪",
                onClick = onLogout
            )
        }

        SettingsSectionTitle(
            "🛒 Lista zakupów"
        )


        SettingsCard {

            SettingsSwitchRow(

                title =
                    "Automatyczne usuwanie kupionych",

                subtitle =
                    if (autoDelete) {
                        "Włączone — po $deleteMinutes min"
                    } else {
                        "Wyłączone"
                    },

                checked =
                    autoDelete,

                onCheckedChange = {

                    autoDelete =
                        it

                    saveBoolean(
                        KEY_AUTO_DELETE,
                        it
                    )
                }
            )


            Divider()


            SettingsRow(

                title =
                    "Czas usunięcia",

                subtitle =
                    "$deleteMinutes minut",

                value =
                    "›",

                enabled =
                    autoDelete,

                onClick = {

                    if (autoDelete) {
                        dialog =
                            "delete_time"
                    }
                }
            )


            Divider()


            SettingsRow(

                title =
                    "Domyślne sortowanie",

                subtitle =
                    when (defaultSort) {
                        "az" ->
                            "A → Z"
                        "za" ->
                            "Z → A"
                        "dokupienia" ->
                            "Najpierw do kupienia"
                        "kupione" ->
                            "Najpierw kupione"
                        else ->
                            "Ręczna kolejność"
                    },

                value =
                    "›",

                onClick = {
                    dialog =
                        "sort"
                }
            )
        }


        SettingsSectionTitle(
            "🗑️ Bezpieczeństwo"
        )


        SettingsCard {

            SettingsSwitchRow(

                title =
                    "Potwierdzaj usuwanie",

                subtitle =
                    "Pokaż pytanie przed usunięciem sklepu lub produktu",

                checked =
                    confirmDelete,

                onCheckedChange = {

                    confirmDelete =
                        it

                    saveBoolean(
                        KEY_CONFIRM_DELETE,
                        it
                    )
                }
            )
        }


        SettingsSectionTitle(
            "🔄 Aktualizacje"
        )

        SettingsCard {
            SettingsRow(
                title = "Sprawdź aktualizacje",
                subtitle = when {
                    sprawdzanieAktualizacji ->
                        "Sprawdzanie najnowszej wersji..."
                    komunikatAktualizacji != null ->
                        komunikatAktualizacji!!
                    else ->
                        "Sprawdź ręcznie, czy jest dostępna nowa wersja"
                },
                value = if (sprawdzanieAktualizacji) "…" else "›",
                enabled = !sprawdzanieAktualizacji,
                onClick = {
                    sprawdzanieAktualizacji = true
                    komunikatAktualizacji = null

                    checkGitHubLatestRelease { release ->
                        android.os.Handler(
                            android.os.Looper.getMainLooper()
                        ).post {
                            sprawdzanieAktualizacji = false

                            if (release == null) {
                                komunikatAktualizacji =
                                    "❌ Nie udało się połączyć z GitHub"
                                return@post
                            }

                            if (
                                release.versionCode >
                                BuildConfig.VERSION_CODE
                            ) {
                                komunikatAktualizacji =
                                    "🆕 Dostępna wersja ${release.versionName}"

                                dostepnaAktualizacjaUstawienia =
                                    release
                            } else {
                                komunikatAktualizacji =
                                    "✅ Aplikacja jest aktualna (${BuildConfig.VERSION_NAME})"
                            }
                        }
                    }
                }
            )
        }

        SettingsSectionTitle(
            "📱 Dodatkowe"
        )


        SettingsCard {

            SettingsSwitchRow(

                title =
                    "Wibracje przy kliknięciu",

                subtitle =
                    if (haptics) {
                        "Włączone"
                    } else {
                        "Wyłączone"
                    },

                checked =
                    haptics,

                onCheckedChange = {

                    haptics =
                        it

                    saveBoolean(
                        KEY_HAPTICS,
                        it
                    )

                    onHapticsChanged(
                        it
                    )
                }
            )


            Divider()


            SettingsRow(

                title =
                    "ℹ️ Informacje o aplikacji",

                subtitle =
                    "Lista Zakupów V2.0 • wersja 2.0",

                value =
                    "›",

                onClick = {
                    dialog =
                        "about"
                }
            )
        }


        Spacer(
            modifier =
                Modifier.height(8.dp)
        )


        TextButton(

            modifier =
                Modifier.fillMaxWidth(),

            onClick = {

                prefs.edit()
                    .clear()
                    .apply()

                autoDelete =
                    true

                deleteMinutes =
                    20

                theme =
                    "system"

                defaultSort =
                    "reczna"

                confirmDelete =
                    true

                haptics =
                    true

                onHapticsChanged(
                    true
                )

                onThemeChanged(
                    "system"
                )
            }
        ) {

            Text(
                "Przywróć ustawienia domyślne"
            )
        }
    }


    dostepnaAktualizacjaUstawienia?.let { release ->
        UpdateDialog(
            release = release,
            onDismiss = {
                dostepnaAktualizacjaUstawienia = null
            },
            onUpdate = {
                val url = release.downloadUrl

                if (url == null) {
                    komunikatAktualizacji =
                        "Ta wersja nie ma pliku APK do pobrania."
                    dostepnaAktualizacjaUstawienia = null
                } else {
                    dostepnaAktualizacjaUstawienia = null
                    downloadAndInstallUpdate(
                        context,
                        url
                    )
                }
            }
        )
    }

    if (dialog != null) {

        when (dialog) {

            "theme" -> {

                ChoiceDialog(

                    title =
                        "🎨 Motyw aplikacji",

                    options =
                        listOf(
                            "system" to
                                    "Systemowy",
                            "light" to
                                    "Jasny",
                            "dark" to
                                    "Ciemny"
                        ),

                    selected =
                        theme,

                    onSelect = {

                        theme =
                            it

                        saveString(
                            KEY_THEME,
                            it
                        )

                        onThemeChanged(
                            it
                        )

                        dialog =
                            null
                    },

                    onDismiss = {
                        dialog =
                            null
                    }
                )
            }


            "delete_time" -> {

                ChoiceDialog(

                    title =
                        "⏱️ Czas usunięcia",

                    options =
                        listOf(
                            "5" to "5 minut",
                            "10" to "10 minut",
                            "20" to "20 minut",
                            "30" to "30 minut",
                            "60" to "60 minut"
                        ),

                    selected =
                        deleteMinutes
                            .toString(),

                    onSelect = {

                        deleteMinutes =
                            it.toInt()

                        saveInt(
                            KEY_DELETE_MINUTES,
                            deleteMinutes
                        )

                        dialog =
                            null
                    },

                    onDismiss = {
                        dialog =
                            null
                    }
                )
            }


            "sort" -> {

                ChoiceDialog(

                    title =
                        "↕️ Domyślne sortowanie",

                    options =
                        listOf(
                            "reczna" to
                                    "Ręczna kolejność",
                            "az" to
                                    "A → Z",
                            "za" to
                                    "Z → A",
                            "dokupienia" to
                                    "Najpierw do kupienia",
                            "kupione" to
                                    "Najpierw kupione"
                        ),

                    selected =
                        defaultSort,

                    onSelect = {

                        defaultSort =
                            it

                        saveString(
                            KEY_DEFAULT_SORT,
                            it
                        )

                        dialog =
                            null
                    },

                    onDismiss = {
                        dialog =
                            null
                    }
                )
            }


            "email" -> {
                AlertDialog(
                    onDismissRequest = { if (!emailTrwa) dialog = null },
                    title = { Text("📧 Adres e-mail") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Zmień adres e-mail przypisany do konta.")
                            OutlinedTextField(
                                value = nowyEmail,
                                onValueChange = { nowyEmail = it; komunikatEmail = null },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !emailTrwa,
                                singleLine = true,
                                label = { Text("E-mail") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                            )
                            if (komunikatEmail != null) {
                                Text(
                                    komunikatEmail!!,
                                    color = if (komunikatEmail!!.startsWith("✅")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    },
                    dismissButton = {
                        TextButton(enabled = !emailTrwa, onClick = { dialog = null }) { Text("Anuluj") }
                    },
                    confirmButton = {
                        TextButton(
                            enabled = !emailTrwa,
                            onClick = {
                                val value = nowyEmail.trim()
                                if (!android.util.Patterns.EMAIL_ADDRESS.matcher(value).matches()) {
                                    komunikatEmail = "Wpisz poprawny adres e-mail."
                                    return@TextButton
                                }
                                val user = FirebaseAuth.getInstance().currentUser
                                if (user == null) {
                                    komunikatEmail = "Brak zalogowanego konta."
                                    return@TextButton
                                }
                                emailTrwa = true
                                komunikatEmail = null
                                user.updateEmail(value)
                                    .addOnSuccessListener {
                                        FirebaseFirestore.getInstance()
                                            .collection("users")
                                            .document(user.uid)
                                            .update("email", value)
                                            .addOnSuccessListener {
                                                emailTrwa = false
                                                komunikatEmail = "✅ E-mail został zmieniony."
                                                onEmailChanged(value)
                                            }
                                            .addOnFailureListener {
                                                emailTrwa = false
                                                komunikatEmail = "E-mail zmieniono w Authentication, ale nie udało się zapisać go w Firestore."
                                            }
                                    }
                                    .addOnFailureListener { error ->
                                        emailTrwa = false
                                        val msg = error.message?.lowercase() ?: ""
                                        komunikatEmail = if (msg.contains("recent login") || msg.contains("requires-recent-login")) {
                                            "Ze względów bezpieczeństwa zaloguj się ponownie i spróbuj zmienić e-mail."
                                        } else {
                                            "Nie udało się zmienić adresu e-mail."
                                        }
                                    }
                            }
                        ) {
                            if (emailTrwa) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text("ZAPISZ")
                        }
                    }
                )
            }

            "email_reset_info" -> {
                AlertDialog(
                    onDismissRequest = { dialog = null },
                    title = { Text("🔑 Resetowanie hasła") },
                    text = {
                        Text("Aby zresetować hasło, wyloguj się i wybierz „Resetuj hasło” na ekranie logowania. Link zostanie wysłany na adres przypisany do konta w Firestore.")
                    },
                    confirmButton = {
                        TextButton(onClick = { dialog = null }) { Text("OK") }
                    }
                )
            }

            "about" -> {

                AlertDialog(

                    onDismissRequest = {
                        dialog =
                            null
                    },

                    title = {

                        Text(
                            "🛒 Lista Zakupów"
                        )
                    },

                    text = {

                        Text(
                            "Wersja 2.0\n\n" +
                                    "Aplikacja do tworzenia " +
                                    "i organizowania list zakupowych, " +
                                    "zarządzania sklepami oraz " +
                                    "synchronizacji danych.\n\n" +
                                    "Firebase Firestore"
                        )
                    },

                    confirmButton = {

                        TextButton(
                            onClick = {
                                dialog =
                                    null
                            }
                        ) {

                            Text(
                                "OK"
                            )
                        }
                    }
                )
            }
        }
    }
}


@Composable
private fun SettingsSectionTitle(
    title: String
) {

    Text(

        text =
            title,

        style =
            MaterialTheme
                .typography
                .titleSmall,

        modifier =
            Modifier.padding(
                top = 4.dp,
                bottom = 2.dp
            )
    )
}


@Composable
private fun SettingsCard(
    content: @Composable ColumnScope.() -> Unit
) {

    Card(

        modifier =
            Modifier.fillMaxWidth(),

        shape =
            RoundedCornerShape(
                18.dp
            ),

        colors =
            CardDefaults
                .cardColors(
                    containerColor =
                        MaterialTheme
                            .colorScheme
                            .surfaceVariant
                )
    ) {

        Column(
            modifier =
                Modifier.fillMaxWidth(),
            content =
                content
        )
    }
}


@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    value: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {

    Row(

        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(
                    enabled =
                        enabled,
                    onClick =
                        onClick
                )
                .padding(
                    horizontal = 16.dp,
                    vertical = 13.dp
                ),

        verticalAlignment =
            Alignment.CenterVertically
    ) {

        Column(
            modifier =
                Modifier.weight(1f)
        ) {

            Text(
                text =
                    title,

                style =
                    MaterialTheme
                        .typography
                        .bodyLarge
            )


            Text(
                text =
                    subtitle,

                style =
                    MaterialTheme
                        .typography
                        .bodySmall,

                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )
        }


        Text(
            text =
                value,

            style =
                MaterialTheme
                    .typography
                    .titleLarge,

            color =
                if (enabled) {
                    MaterialTheme
                        .colorScheme
                        .onSurface
                } else {
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
                }
        )
    }
}


@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {

    Row(

        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 16.dp,
                    vertical = 10.dp
                ),

        verticalAlignment =
            Alignment.CenterVertically
    ) {

        Column(
            modifier =
                Modifier.weight(1f)
        ) {

            Text(
                text =
                    title,

                style =
                    MaterialTheme
                        .typography
                        .bodyLarge
            )


            Text(
                text =
                    subtitle,

                style =
                    MaterialTheme
                        .typography
                        .bodySmall,

                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )
        }


        Switch(

            checked =
                checked,

            onCheckedChange =
                onCheckedChange
        )
    }
}


@Composable
private fun ChoiceDialog(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {

    AlertDialog(

        onDismissRequest =
            onDismiss,

        title = {
            Text(
                title
            )
        },

        text = {

            Column {

                options.forEach { option ->

                    Row(

                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelect(
                                        option.first
                                    )
                                }
                                .padding(
                                    vertical = 10.dp
                                ),

                        verticalAlignment =
                            Alignment.CenterVertically
                    ) {

                        Text(
                            text =
                                if (
                                    option.first ==
                                    selected
                                ) {
                                    "●"
                                } else {
                                    "○"
                                },

                            modifier =
                                Modifier.width(
                                    28.dp
                                )
                        )


                        Text(
                            option.second
                        )
                    }
                }
            }
        },

        confirmButton = {

            TextButton(
                onClick =
                    onDismiss
            ) {

                Text(
                    "Anuluj"
                )
            }
        }
    )
}