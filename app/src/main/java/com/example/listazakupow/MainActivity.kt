package com.example.listazakupow

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.net.toUri
import androidx.core.content.edit
import android.os.Bundle
import android.view.HapticFeedbackConstants
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import coil.compose.AsyncImage
import com.example.listazakupow.components.UserHeader
import com.example.listazakupow.ui.theme.ListaZakupowTheme
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.milliseconds

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
            val url = URL(
                "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
            )

            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty(
                "Accept",
                "application/vnd.github+json"
            )
            connection.setRequestProperty(
                "User-Agent",
                "ListaZakupow-Android"
            )

            if (connection.responseCode !in 200..299) {
                onResult(null)
                return@Thread
            }

            val response = connection.inputStream
                .bufferedReader()
                .use { it.readText() }

            val tagName = Regex(
                """"tag_name"\s*:\s*"([^"]+)"""
            ).find(response)?.groupValues?.getOrNull(1)

            val versionCode = tagName?.let {
                extractReleaseVersion(it)
            }

            if (versionCode == null) {
                onResult(null)
                return@Thread
            }

            val versionName = tagName.removePrefix("v")

            val apkDownloadUrl = Regex(
                """"browser_download_url"\s*:\s*"([^"]+\.apk)""",
                RegexOption.IGNORE_CASE
            ).find(response)?.groupValues?.getOrNull(1)

            onResult(
                GitHubRelease(
                    versionCode = versionCode,
                    versionName = versionName,
                    downloadUrl = apkDownloadUrl
                )
            )
        } catch (_: Exception) {
            onResult(null)
        } finally {
            connection?.disconnect()
        }
    }
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
    ).edit {
        putLong(
            KEY_LAST_UPDATE_CHECK,
            System.currentTimeMillis()
        )
    }
}

private fun downloadAndInstallUpdate(
    context: Context,
    downloadUrl: String
) {
    try {
        val request = DownloadManager.Request(
            downloadUrl.toUri()
        )
            .setTitle("Lista Zakupów — aktualizacja")
            .setDescription("Pobieranie nowej wersji aplikacji")
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "ListaZakupow-update.apk"
            )
            .setMimeType("application/vnd.android.package-archive")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val downloadManager = context.getSystemService(
            Context.DOWNLOAD_SERVICE
        ) as DownloadManager

        val downloadId = downloadManager.enqueue(request)

        Toast.makeText(
            context,
            "Pobieranie aktualizacji rozpoczęte.",
            Toast.LENGTH_SHORT
        ).show()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(
                receiverContext: Context,
                intent: Intent
            ) {
                val completedId = intent.getLongExtra(
                    DownloadManager.EXTRA_DOWNLOAD_ID,
                    -1L
                )

                if (completedId != downloadId) return

                try {
                    val apkUri = downloadManager.getUriForDownloadedFile(
                        downloadId
                    )

                    if (apkUri == null) {
                        Toast.makeText(
                            context,
                            "Nie udało się pobrać aktualizacji.",
                            Toast.LENGTH_LONG
                        ).show()
                        return
                    }

                    val installIntent = Intent(
                        Intent.ACTION_VIEW
                    ).apply {
                        setDataAndType(
                            apkUri,
                            "application/vnd.android.package-archive"
                        )
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }

                    context.startActivity(installIntent)
                } catch (_: Exception) {
                    Toast.makeText(
                        context,
                        "Nie udało się uruchomić instalatora.",
                        Toast.LENGTH_LONG
                    ).show()
                } finally {
                    try {
                        receiverContext.unregisterReceiver(this)
                    } catch (_: Exception) {
                    }
                }
            }
        }

        androidx.core.content.ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    } catch (_: Exception) {
        Toast.makeText(
            context,
            "Nie udało się rozpocząć pobierania aktualizacji.",
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
                Text("Dostępna jest nowa wersja aplikacji Lista Zakupów.")
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
// MAIN ACTIVITY
// =============================================================

@Composable
private fun Modifier.hapticTapFeedback(
    enabled: Boolean
): Modifier {
    val view = LocalView.current
    return this.pointerInput(enabled) {
        awaitEachGesture {
            val down = awaitFirstDown(
                requireUnconsumed = false,
                pass = PointerEventPass.Initial
            )
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break

                if (change.changedToUp()) {
                    val distance = (change.position - down.position).getDistance()
                    if (enabled && distance <= 24f) {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                    break
                }
            }
        }
    }
}

private const val INVITE_SCHEME = "listazakupow"
private const val INVITE_HOST = "invite"
private const val INVITE_VALIDITY_MS = 24L * 60L * 60L * 1000L
private const val INVITE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

private fun generateInviteCode(length: Int = 8): String {
    val random = SecureRandom()
    return buildString(length) {
        repeat(length) {
            append(INVITE_ALPHABET[random.nextInt(INVITE_ALPHABET.length)])
        }
    }
}

private fun inviteUri(code: String): Uri =
    "$INVITE_SCHEME://$INVITE_HOST/$code".toUri()

private fun extractInviteId(uri: Uri?): String? {
    if (uri == null) return null
    return when {
        uri.scheme == INVITE_SCHEME && uri.host == INVITE_HOST ->
            uri.pathSegments.firstOrNull()?.takeIf { it.matches(Regex("[A-Z2-9]{8}")) }
        uri.scheme == "https" && uri.host == "listazakupow.app" && uri.pathSegments.firstOrNull() == "invite" ->
            uri.pathSegments.getOrNull(1)
        else -> null
    }
}

class MainActivity : ComponentActivity() {

    private var incomingInviteId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingInviteId = extractInviteId(intent.data)
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
                    ).getBoolean(
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
                    ).getString(
                        KEY_THEME,
                        "system"
                    ) ?: "system"
                )
            }

            val darkTheme = when (ustawionyMotyw) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }

            ListaZakupowTheme(darkTheme = darkTheme) {
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .hapticTapFeedback(enabled = wibracjeWlaczone)
                ) { padding ->
                    LoginScreen(
                        modifier = Modifier.padding(padding),
                        onThemeChanged = { ustawionyMotyw = it },
                        onHapticsChanged = { wibracjeWlaczone = it },
                        incomingInviteId = incomingInviteId,
                        onInviteHandled = { incomingInviteId = null }
                    )
                }

                dostepnaAktualizacja?.let { release ->
                    UpdateDialog(
                        release = release,
                        onDismiss = { dostepnaAktualizacja = null },
                        onUpdate = {
                            val url = release.downloadUrl
                            if (url != null) {
                                dostepnaAktualizacja = null
                                downloadAndInstallUpdate(updateContext, url)
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingInviteId = extractInviteId(intent.data)
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
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val original = BitmapFactory.decodeStream(inputStream)
        inputStream.close()

        if (original == null) return null

        val maxSize = 256
        val ratio = minOf(
            maxSize.toFloat() / original.width,
            maxSize.toFloat() / original.height,
            1f
        )

        val width = (original.width * ratio).toInt().coerceAtLeast(1)
        val height = (original.height * ratio).toInt().coerceAtLeast(1)

        val resized = Bitmap.createScaledBitmap(original, width, height, true)
        val output = ByteArrayOutputStream()

        resized.compress(Bitmap.CompressFormat.JPEG, 65, output)
        resized.recycle()
        original.recycle()

        Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    } catch (_: Exception) {
        null
    }
}

// =============================================================
// BASE64 -> BITMAP
// =============================================================

fun base64ToBitmap(data: String): Bitmap? {
    return try {
        if (data.isBlank()) return null
        val bytes = Base64.decode(data, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (_: Exception) {
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
    if (sklep.typIkony == "image" && sklep.obrazDane.isNotEmpty()) {
        val bitmap = remember(sklep.obrazDane) {
            base64ToBitmap(sklep.obrazDane)
        }

        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = sklep.nazwa,
                contentScale = ContentScale.Fit,
                modifier = modifier
            )
        } else {
            Text(
                text = sklep.emoji,
                style = MaterialTheme.typography.headlineMedium,
                modifier = modifier
            )
        }
    } else {
        Text(
            text = sklep.emoji,
            style = MaterialTheme.typography.headlineMedium,
            modifier = modifier
        )
    }
}

// =============================================================
// PUSTA LISTA
// =============================================================

@Composable
fun EmptyShoppingImage() {
    val darkTheme = isSystemInDarkTheme()

    Image(
        painter = painterResource(
            if (darkTheme) R.drawable.cart_dark else R.drawable.cart_light
        ),
        contentDescription = null,
        contentScale = ContentScale.FillWidth,
        modifier = Modifier
            .fillMaxWidth()
            .scale(1.25f)
            .height(550.dp)
            .offset(y = 125.dp)
            .graphicsLayer {
                this.alpha = if (darkTheme) 0.40f else 0.75f
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
    onHapticsChanged: (Boolean) -> Unit,
    incomingInviteId: String? = null,
    onInviteHandled: () -> Unit = {}
) {
    val db = remember { FirebaseFirestore.getInstance() }
    val auth = remember { FirebaseAuth.getInstance() }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("lista_zakupow", Context.MODE_PRIVATE)

    var login by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var zalogowany by remember { mutableStateOf(auth.currentUser != null) }
    var emailKonta by remember { mutableStateOf("") }

    // Współdzielenie listy przez zaproszenie.
    // Nazwa osoby jest lokalna dla właściciela i nie jest wysyłana odbiorcy.
    var udostepnieniUzytkownicy by remember { mutableStateOf<List<String>>(emptyList()) }
    var udostepnioneNazwy by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var sharedOwnerIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pokazWspoldzielenie by remember { mutableStateOf(false) }
    var pokazZaproszenie by remember { mutableStateOf(false) }
    var inviteId by remember { mutableStateOf("") }
    var inviteOwnerUid by remember { mutableStateOf("") }
    var inviteOwnerName by remember { mutableStateOf("") }
    var inviteListName by remember { mutableStateOf("Lista Zakupów") }
    var inviteLoading by remember { mutableStateOf(false) }
    var inviteError by remember { mutableStateOf<String?>(null) }

    var pokazRejestracje by remember { mutableStateOf(false) }
    var rejestracjaImie by remember { mutableStateOf("") }
    var rejestracjaLogin by remember { mutableStateOf("") }
    var rejestracjaEmail by remember { mutableStateOf("") }
    var rejestracjaHaslo by remember { mutableStateOf("") }
    var rejestracjaHaslo2 by remember { mutableStateOf("") }
    var rejestracjaTrwa by remember { mutableStateOf(false) }
    var bladRejestracji by remember { mutableStateOf<String?>(null) }

    var imie by remember {
        mutableStateOf(prefs.getString("imie", "") ?: "")
    }

    var nowyProdukt by remember { mutableStateOf("") }
    var wyszukiwanieProduktu by remember { mutableStateOf("") }
    var produktDoUsuniecia by remember { mutableStateOf<Produkt?>(null) }
    var produktDoEdycji by remember { mutableStateOf<Produkt?>(null) }
    var pokazUsunZaznaczone by remember { mutableStateOf(false) }
    var wybranaZakladka by remember { mutableIntStateOf(0) }
    var wybranySklep by remember { mutableStateOf<String?>(null) }
    var wybranaKategoria by remember { mutableStateOf("wszystkie") }
    var trybSortowania by remember { mutableStateOf("reczna") }
    var pokazSortowanie by remember { mutableStateOf(false) }
    var pokazWyborListy by remember { mutableStateOf(false) }
    var produktDoPrzydzielenia by remember { mutableStateOf<Produkt?>(null) }

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

                        udostepnieniUzytkownicy =
                            (document.get("sharedWith") as? List<*>)
                                ?.filterIsInstance<String>()
                                ?: emptyList()

                        @Suppress("UNCHECKED_CAST")
                        val loginMap = (document.get("sharedWithNames") as? Map<*, *>)
                            ?.mapNotNull { (key, value) ->
                                if (key is String && value is String) key to value else null
                            }
                            ?.toMap()
                            ?: emptyMap()

                        udostepnioneNazwy = loginMap

                        val profileSharedOwnerId = document.getString("sharedOwnerId") ?: ""
                        if (profileSharedOwnerId.isNotBlank()) {
                            sharedOwnerIds = setOf(profileSharedOwnerId)
                        }
                    }
                }
        }
    }

    LaunchedEffect(zalogowany) {
        if (!zalogowany) {
            udostepnieniUzytkownicy = emptyList()
            udostepnioneNazwy = emptyMap()
            sharedOwnerIds = emptySet()
        }
    }

    LaunchedEffect(incomingInviteId, zalogowany) {
        val incoming = incomingInviteId ?: return@LaunchedEffect
        if (!zalogowany || auth.currentUser == null) return@LaunchedEffect

        inviteId = incoming
        inviteLoading = true
        inviteError = null

        db.collection("invitations").document(incoming).get()
            .addOnSuccessListener { doc ->
                val status = doc.getString("status") ?: "pending"
                val expiresAt = doc.getLong("expiresAt") ?: 0L

                when {
                    !doc.exists() -> inviteError = "To zaproszenie nie istnieje."
                    status != "pending" -> inviteError = "To zaproszenie zostało już wykorzystane."
                    expiresAt > 0L && expiresAt < System.currentTimeMillis() -> inviteError = "To zaproszenie wygasło."
                    else -> {
                        inviteOwnerUid = doc.getString("ownerUid") ?: ""
                        inviteOwnerName = doc.getString("ownerName") ?: "Użytkownik"
                        inviteListName = doc.getString("listName") ?: "Lista Zakupów"
                    }
                }

                inviteLoading = false
                pokazZaproszenie = true
                onInviteHandled()
            }
            .addOnFailureListener { e ->
                inviteLoading = false
                inviteError = e.message ?: "Nie udało się pobrać zaproszenia."
                pokazZaproszenie = true
                onInviteHandled()
            }
    }

    val lista = remember { mutableStateListOf<Produkt>() }
    val posortowanaListaLocal = remember { mutableStateListOf<Produkt>() }

    val sklepy = remember { mutableStateListOf<Sklep>() }
    val posortowaneSklepyLocal = remember { mutableStateListOf<Sklep>() }
    var sklepDoEdycji by remember { mutableStateOf<Sklep?>(null) }
    var sklepDoUsuniecia by remember { mutableStateOf<Sklep?>(null) }
    var pokazDodajSklep by remember { mutableStateOf(false) }

    var wlasneProduktySnapshot by remember { mutableStateOf<List<Produkt>>(emptyList()) }
    var wspoldzieloneProduktySnapshot by remember { mutableStateOf<List<Produkt>>(emptyList()) }

    DisposableEffect(zalogowany, auth.currentUser?.uid) {
        val uid = auth.currentUser?.uid

        if (!zalogowany || uid.isNullOrBlank()) {
            wlasneProduktySnapshot = emptyList()
            wspoldzieloneProduktySnapshot = emptyList()
            sharedOwnerIds = emptySet()
            onDispose { }
        } else {
            val ownRegistration = db.collection("zakupy")
                .whereEqualTo("userId", uid)
                .addSnapshotListener { result, error ->
                    if (error != null || result == null) return@addSnapshotListener

                    wlasneProduktySnapshot = result.documents.map { document ->
                        Produkt(
                            id = document.id,
                            nazwa = document.getString("nazwa") ?: "",
                            dodal = document.getString("dodal") ?: "",
                            kupione = document.getBoolean("kupione") ?: false,
                            kupioneOd = document.getLong("kupioneOd") ?: 0L,
                            kolejnosc = document.getLong("kolejnosc") ?: 0L,
                            kategoria = document.getString("kategoria") ?: "glowna"
                        )
                    }
                }

            val sharedRegistration = db.collection("zakupy")
                .whereArrayContains("sharedWith", uid)
                .addSnapshotListener { result, error ->
                    if (error != null || result == null) return@addSnapshotListener

                    wspoldzieloneProduktySnapshot = result.documents.map { document ->
                        Produkt(
                            id = document.id,
                            nazwa = document.getString("nazwa") ?: "",
                            dodal = document.getString("dodal") ?: "",
                            kupione = document.getBoolean("kupione") ?: false,
                            kupioneOd = document.getLong("kupioneOd") ?: 0L,
                            kolejnosc = document.getLong("kolejnosc") ?: 0L,
                            kategoria = document.getString("kategoria") ?: "glowna"
                        )
                    }

                    sharedOwnerIds = result.documents
                        .mapNotNull { it.getString("userId") }
                        .filter { it != uid }
                        .toSet()
                }

            onDispose {
                ownRegistration.remove()
                sharedRegistration.remove()
            }
        }
    }

    LaunchedEffect(wlasneProduktySnapshot, wspoldzieloneProduktySnapshot) {
        val merged = (wlasneProduktySnapshot + wspoldzieloneProduktySnapshot)
            .associateBy { it.id }
            .values
            .toList()

        lista.clear()
        lista.addAll(merged)
    }

    DisposableEffect(zalogowany, auth.currentUser?.uid, sharedOwnerIds) {
        val uid = auth.currentUser?.uid
        if (!zalogowany || uid.isNullOrBlank()) {
            onDispose { }
        } else {
            val ownRef = db.collection("sklepy").whereEqualTo("userId", uid)
            val sharedRef = if (sharedOwnerIds.isNotEmpty()) {
                db.collection("sklepy").whereArrayContains("sharedWith", uid)
            } else null

            var ownShops: List<Sklep> = emptyList()
            var sharedShops: List<Sklep> = emptyList()

            fun applyShops() {
                val merged = (ownShops + sharedShops).associateBy { it.id }.values.sortedBy { it.kolejnosc }
                sklepy.clear()
                sklepy.addAll(merged)
            }

            ownRef.get().addOnSuccessListener { snapshot ->
                if (snapshot.isEmpty && sharedOwnerIds.isEmpty()) {
                    val domyslneSklepy = listOf(
                        Sklep(id = "lidl", nazwa = "Lidl", typIkony = "emoji", emoji = "🛒", kolejnosc = 1L),
                        Sklep(id = "biedronka", nazwa = "Biedronka", typIkony = "emoji", emoji = "🐞", kolejnosc = 2L),
                        Sklep(id = "rossmann", nazwa = "Rossmann", typIkony = "emoji", emoji = "🧴", kolejnosc = 3L),
                        Sklep(id = "castorama", nazwa = "Castorama", typIkony = "emoji", emoji = "🔨", kolejnosc = 4L)
                    )
                    domyslneSklepy.forEach { sklep ->
                        db.collection("sklepy")
                            .document("${uid}_${sklep.id}")
                            .set(
                                mapOf(
                                    "nazwa" to sklep.nazwa,
                                    "typIkony" to sklep.typIkony,
                                    "emoji" to sklep.emoji,
                                    "obrazDane" to sklep.obrazDane,
                                    "kolejnosc" to sklep.kolejnosc,
                                    "userId" to uid,
                                    "sharedWith" to emptyList<String>()
                                )
                            )
                    }
                }
            }

            val ownListener = ownRef.addSnapshotListener { snapshot, error ->
                if (error == null && snapshot != null) {
                    ownShops = snapshot.documents.map { document ->
                        Sklep(
                            id = document.id,
                            nazwa = document.getString("nazwa") ?: "",
                            typIkony = document.getString("typIkony") ?: "emoji",
                            emoji = document.getString("emoji") ?: "🏪",
                            obrazDane = document.getString("obrazDane") ?: "",
                            kolejnosc = document.getLong("kolejnosc") ?: 0L
                        )
                    }
                    applyShops()
                }
            }

            val sharedListener = sharedRef?.addSnapshotListener { snapshot, error ->
                if (error == null && snapshot != null) {
                    sharedShops = snapshot.documents.map { document ->
                        Sklep(
                            id = document.id,
                            nazwa = document.getString("nazwa") ?: "",
                            typIkony = document.getString("typIkony") ?: "emoji",
                            emoji = document.getString("emoji") ?: "🏪",
                            obrazDane = document.getString("obrazDane") ?: "",
                            kolejnosc = document.getLong("kolejnosc") ?: 0L
                        )
                    }
                    applyShops()
                }
            }

            onDispose {
                ownListener.remove()
                sharedListener?.remove()
            }
        }
    }

    var przeciaganieSklepu by remember { mutableStateOf(false) }

    LaunchedEffect(
        sklepy.size,
        sklepy.joinToString("|") { "${it.id}:${it.nazwa}:${it.typIkony}:${it.emoji}:${it.obrazDane}:${it.kolejnosc}" }
    ) {
        if (!przeciaganieSklepu) {
            posortowaneSklepyLocal.clear()
            posortowaneSklepyLocal.addAll(sklepy.sortedBy { it.kolejnosc })
        }
    }

    LaunchedEffect(
        wybranaKategoria,
        wyszukiwanieProduktu,
        trybSortowania,
        lista.joinToString("|") { "${it.id}:${it.kolejnosc}:${it.kupione}:${it.kategoria}:${it.nazwa}" }
    ) {
        val fraza = wyszukiwanieProduktu.trim().lowercase()
        val przefiltrowana = lista.filter {
            val pasujeDoKategorii = if (wybranaKategoria == "wszystkie") true else it.kategoria == wybranaKategoria
            val pasujeDoWyszukiwania = fraza.isEmpty() || it.nazwa.lowercase().contains(fraza)
            pasujeDoKategorii && pasujeDoWyszukiwania
        }

        val aktualna = when (trybSortowania) {
            "az" -> przefiltrowana.sortedBy { it.nazwa.lowercase() }
            "za" -> przefiltrowana.sortedByDescending { it.nazwa.lowercase() }
            "dokupienia" -> przefiltrowana.sortedWith(compareBy<Produkt> { it.kupione }.thenBy { it.nazwa.lowercase() })
            "kupione" -> przefiltrowana.sortedWith(compareByDescending<Produkt> { it.kupione }.thenBy { it.nazwa.lowercase() })
            else -> przefiltrowana.sortedBy { it.kolejnosc }
        }

        posortowanaListaLocal.clear()
        posortowanaListaLocal.addAll(aktualna)
    }

    LaunchedEffect(Unit) {
        while (true) {
            val teraz = System.currentTimeMillis()
            lista.filter {
                settingsPrefs(context).getBoolean(KEY_AUTO_DELETE, true) &&
                        it.kupione &&
                        it.kupioneOd > 0 &&
                        teraz - it.kupioneOd >= settingsPrefs(context).getInt(KEY_DELETE_MINUTES, 20) * 60 * 1000
            }.forEach { produkt ->
                db.collection("zakupy").document(produkt.id).delete()
            }
            delay(1.minutes)
        }
    }

    // =========================================================
    // LOGOWANIE I REJESTRACJA
    // =========================================================

    if (!zalogowany) {
        var logowanieTrwa by remember { mutableStateOf(false) }
        var bladLogowania by remember { mutableStateOf<String?>(null) }

        if (pokazRejestracje) {
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
                            .padding(24.dp)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("🛒", style = MaterialTheme.typography.displaySmall)
                        Spacer(Modifier.height(8.dp))
                        Text("Utwórz konto", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Utwórz własne konto Lista Zakupów",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(Modifier.height(20.dp))

                        OutlinedTextField(
                            value = rejestracjaImie,
                            onValueChange = {
                                rejestracjaImie = it
                                bladRejestracji = null
                                val pierwszeSlow = it.trim().split(Regex("\\s+")).firstOrNull()?.trim() ?: ""
                                rejestracjaLogin = if (pierwszeSlow.isNotEmpty()) {
                                    pierwszeSlow.replaceFirstChar { char -> char.lowercase() }
                                } else ""
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !rejestracjaTrwa,
                            singleLine = true,
                            label = { Text("Imię") },
                            placeholder = { Text("np. Jan") },
                            shape = RoundedCornerShape(16.dp)
                        )

                        Spacer(Modifier.height(12.dp))

                        OutlinedTextField(
                            value = rejestracjaLogin,
                            onValueChange = {
                                rejestracjaLogin = it.trim().lowercase().replace(Regex("\\s+"), "")
                                bladRejestracji = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !rejestracjaTrwa,
                            singleLine = true,
                            label = { Text("Login") },
                            placeholder = { Text("np. jan") },
                            supportingText = { Text("Domyślnie: imię zapisane małą literą.") },
                            shape = RoundedCornerShape(16.dp)
                        )

                        Spacer(Modifier.height(12.dp))

                        OutlinedTextField(
                            value = rejestracjaEmail,
                            onValueChange = {
                                rejestracjaEmail = it
                                bladRejestracji = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !rejestracjaTrwa,
                            singleLine = true,
                            label = { Text("Adres e-mail") },
                            placeholder = { Text("np. jan@example.com") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            shape = RoundedCornerShape(16.dp)
                        )

                        Spacer(Modifier.height(12.dp))

                        OutlinedTextField(
                            value = rejestracjaHaslo,
                            onValueChange = {
                                rejestracjaHaslo = it
                                bladRejestracji = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !rejestracjaTrwa,
                            singleLine = true,
                            label = { Text("Hasło") },
                            visualTransformation = PasswordVisualTransformation(),
                            shape = RoundedCornerShape(16.dp)
                        )

                        Spacer(Modifier.height(12.dp))

                        OutlinedTextField(
                            value = rejestracjaHaslo2,
                            onValueChange = {
                                rejestracjaHaslo2 = it
                                bladRejestracji = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !rejestracjaTrwa,
                            singleLine = true,
                            label = { Text("Powtórz hasło") },
                            visualTransformation = PasswordVisualTransformation(),
                            shape = RoundedCornerShape(16.dp)
                        )

                        if (bladRejestracji != null) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "⚠ $bladRejestracji",
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        Spacer(Modifier.height(18.dp))

                        Button(
                            onClick = {
                                val cleanName = rejestracjaImie.trim()
                                val cleanLogin = rejestracjaLogin.trim().lowercase()
                                val cleanEmail = rejestracjaEmail.trim()

                                when {
                                    cleanName.isBlank() -> {
                                        bladRejestracji = "Wpisz imię."
                                        return@Button
                                    }
                                    cleanLogin.isBlank() -> {
                                        bladRejestracji = "Wpisz login."
                                        return@Button
                                    }
                                    !cleanLogin.matches(Regex("[a-z0-9ąćęłńóśźż]+")) -> {
                                        bladRejestracji = "Login może zawierać tylko litery i cyfry."
                                        return@Button
                                    }
                                    cleanLogin.length < 2 -> {
                                        bladRejestracji = "Login musi mieć co najmniej 2 znaki."
                                        return@Button
                                    }
                                    !android.util.Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches() -> {
                                        bladRejestracji = "Wpisz poprawny adres e-mail."
                                        return@Button
                                    }
                                    rejestracjaHaslo.length < 6 -> {
                                        bladRejestracji = "Hasło musi mieć co najmniej 6 znaków."
                                        return@Button
                                    }
                                    rejestracjaHaslo != rejestracjaHaslo2 -> {
                                        bladRejestracji = "Hasła nie są takie same."
                                        return@Button
                                    }
                                }

                                rejestracjaTrwa = true
                                bladRejestracji = null

                                db.collection("loginLookup")
                                    .document(cleanLogin)
                                    .get()
                                    .addOnSuccessListener { snapshot ->
                                        if (snapshot.exists()) {
                                            rejestracjaTrwa = false
                                            bladRejestracji = "Ten login jest już zajęty."
                                            return@addOnSuccessListener
                                        }

                                        auth.createUserWithEmailAndPassword(cleanEmail, rejestracjaHaslo)
                                            .addOnSuccessListener { result ->
                                                val user = result.user
                                                if (user == null) {
                                                    rejestracjaTrwa = false
                                                    bladRejestracji = "Nie udało się utworzyć konta."
                                                    return@addOnSuccessListener
                                                }

                                                val userData = hashMapOf(
                                                    "uid" to user.uid,
                                                    "imie" to cleanName,
                                                    "login" to cleanLogin,
                                                    "email" to cleanEmail,
                                                    "utworzono" to System.currentTimeMillis(),
                                                    "sharedWith" to emptyList<String>(),
                                                    "sharedWithNames" to emptyMap<String, String>(),
                                                    "sharedOwnerId" to "",
                                                    "sharedOwnerName" to ""
                                                )

                                                db.collection("users")
                                                    .document(user.uid)
                                                    .set(userData)
                                                    .addOnSuccessListener {
                                                        db.collection("loginLookup")
                                                            .document(cleanLogin)
                                                            .set(
                                                                mapOf(
                                                                    "uid" to user.uid,
                                                                    "email" to cleanEmail
                                                                )
                                                            )
                                                            .addOnSuccessListener {
                                                                imie = cleanName
                                                                login = cleanLogin
                                                                emailKonta = cleanEmail
                                                                pin = ""

                                                                prefs.edit {
                                                                    putBoolean("zalogowany", true)
                                                                    putString("imie", cleanName)
                                                                }

                                                                rejestracjaTrwa = false
                                                                pokazRejestracje = false
                                                                zalogowany = true

                                                                rejestracjaImie = ""
                                                                rejestracjaLogin = ""
                                                                rejestracjaEmail = ""
                                                                rejestracjaHaslo = ""
                                                                rejestracjaHaslo2 = ""
                                                            }
                                                            .addOnFailureListener { error ->
                                                                rejestracjaTrwa = false
                                                                bladRejestracji = error.message ?: "Nie udało się zapisać loginu."
                                                            }
                                                    }
                                                    .addOnFailureListener { error ->
                                                        rejestracjaTrwa = false
                                                        bladRejestracji = error.message ?: "Konto utworzono, ale nie udało się zapisać profilu."
                                                    }
                                            }
                                            .addOnFailureListener { error ->
                                                rejestracjaTrwa = false
                                                val message = error.message?.lowercase() ?: ""
                                                bladRejestracji = when {
                                                    message.contains("email address is already in use") || message.contains("email-already-in-use") ->
                                                        "Ten adres e-mail jest już używany."
                                                    message.contains("weak password") ->
                                                        "Hasło jest za słabe. Użyj co najmniej 6 znaków."
                                                    message.contains("network") ->
                                                        "Brak połączenia z Internetem."
                                                    else ->
                                                        "Nie udało się utworzyć konta. Spróbuj ponownie."
                                                }
                                            }
                                    }
                                    .addOnFailureListener { error ->
                                        rejestracjaTrwa = false
                                        bladRejestracji = error.message ?: "Nie udało się sprawdzić loginu."
                                    }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp),
                            enabled = !rejestracjaTrwa,
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            if (rejestracjaTrwa) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Utwórz konto")
                            }
                        }

                        Spacer(Modifier.height(6.dp))

                        TextButton(
                            enabled = !rejestracjaTrwa,
                            onClick = {
                                pokazRejestracje = false
                                bladRejestracji = null
                            }
                        ) {
                            Text("← Wróć do logowania")
                        }
                    }
                }
            }
            return
        }

        // Ekran Logowania
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
                    Text(
                        "Lista Zakupów",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        "Zaloguj się, aby korzystać ze swojej listy",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(24.dp))

                    OutlinedTextField(
                        value = login,
                        onValueChange = {
                            login = it
                            bladLogowania = null
                        },
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
                        onValueChange = {
                            pin = it
                            bladLogowania = null
                        },
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
                            "⚠ $bladLogowania",
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

                            db.collection("loginLookup")
                                .document(loginValue)
                                .get()
                                .addOnSuccessListener { lookup ->
                                    if (!lookup.exists()) {
                                        logowanieTrwa = false
                                        bladLogowania = "Nie znaleziono takiego loginu."
                                        return@addOnSuccessListener
                                    }

                                    val email = lookup.getString("email")?.trim()

                                    if (email.isNullOrBlank()) {
                                        logowanieTrwa = false
                                        bladLogowania = "Ten login nie ma przypisanego adresu e-mail."
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

                                            db.collection("users")
                                                .document(user.uid)
                                                .get()
                                                .addOnSuccessListener { document ->
                                                    imie = document.getString("imie") ?: loginValue
                                                    login = document.getString("login") ?: loginValue
                                                    emailKonta = document.getString("email") ?: email
                                                    pin = ""
                                                    zalogowany = true
                                                    logowanieTrwa = false

                                                    val profileSharedOwner = document.getString("sharedOwnerId") ?: ""
                                                    val profileSharedOwnerName = document.getString("sharedOwnerName") ?: ""
                                                    prefs.edit {
                                                        putBoolean("zalogowany", true)
                                                        putString("imie", imie)
                                                        putString("shared_owner_id", profileSharedOwner)
                                                        putString("shared_owner_name", profileSharedOwnerName)
                                                    }
                                                }
                                                .addOnFailureListener {
                                                    imie = loginValue
                                                    login = loginValue
                                                    emailKonta = email
                                                    pin = ""
                                                    zalogowany = true
                                                    logowanieTrwa = false

                                                    prefs.edit {
                                                        putBoolean("zalogowany", true)
                                                        putString("imie", imie)
                                                    }
                                                }
                                        }
                                        .addOnFailureListener { error ->
                                            logowanieTrwa = false
                                            val message = error.message?.lowercase() ?: ""
                                            bladLogowania = when {
                                                message.contains("password") ||
                                                        message.contains("credential") ||
                                                        message.contains("invalid") -> "Nieprawidłowy login lub hasło."
                                                message.contains("network") -> "Brak połączenia z Internetem."
                                                else -> "Nie udało się zalogować. Spróbuj ponownie."
                                            }
                                        }
                                }
                                .addOnFailureListener { error ->
                                    logowanieTrwa = false
                                    bladLogowania = error.message ?: "Nie udało się sprawdzić loginu."
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

                    Spacer(Modifier.height(10.dp))

                    TextButton(
                        enabled = !logowanieTrwa,
                        onClick = {
                            pokazRejestracje = true
                            bladLogowania = null
                        }
                    ) {
                        Text("Nie masz konta? Zarejestruj się")
                    }
                }
            }
        }
        return
    }

    // =========================================================
    // GŁÓWNY WIDOK DLA ZALOGOWANEGO UŻYTKOWNIKA
    // =========================================================

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        AnimatedContent(
            targetState = wybranaZakladka,
            transitionSpec = {
                fadeIn(animationSpec = tween(180)) togetherWith fadeOut(animationSpec = tween(120))
            },
            label = "mainTabTransition"
        ) { zakladka ->
            when (zakladka) {
                0 -> {
                    val produktyGlownejListy = if (wybranaKategoria == "wszystkie") {
                        lista
                    } else {
                        lista.filter { it.kategoria == wybranaKategoria }
                    }

                    val zaznaczoneProdukty = produktyGlownejListy.filter { it.kupione }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 160.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "🛒 Lista zakupów",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }

                        UserHeader(
                            imie = imie,
                            liczbaProduktow = produktyGlownejListy.size,
                            onLogout = {
                                prefs.edit { clear() }
                                auth.signOut()
                                zalogowany = false
                                login = ""
                                pin = ""
                                imie = ""
                            }
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = nowyProdukt,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(58.dp),
                                onValueChange = { nowyProdukt = it },
                                label = { Text("🛍️ Produkt") },
                                singleLine = true,
                                shape = RoundedCornerShape(18.dp)
                            )

                            Spacer(modifier = Modifier.width(8.dp))

                            Button(
                                modifier = Modifier.height(58.dp),
                                shape = RoundedCornerShape(18.dp),
                                onClick = {
                                    val produkt = nowyProdukt.trim()
                                    if (produkt.isNotEmpty()) {
                                        if (wybranaKategoria != "wszystkie") {
                                            dodajProduktDoListy(context, produkt, imie, wybranaKategoria)
                                            nowyProdukt = ""
                                            scope.launch {
                                                snackbarHostState.showSnackbar("✅ Dodano do wybranej listy")
                                            }
                                        } else {
                                            pokazWyborListy = true
                                        }
                                    }
                                },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)
                            ) {
                                Text("＋")
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = wyszukiwanieProduktu,
                                onValueChange = { wyszukiwanieProduktu = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(58.dp),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall,
                                shape = RoundedCornerShape(18.dp),
                                placeholder = {
                                    Text(
                                        text = "🔎 Szukaj produktu...",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                },
                                trailingIcon = {
                                    if (wyszukiwanieProduktu.isNotEmpty()) {
                                        Box(
                                            modifier = Modifier
                                                .size(32.dp)
                                                .clickable { wyszukiwanieProduktu = "" },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("✕")
                                        }
                                    }
                                }
                            )

                            Button(
                                modifier = Modifier.height(58.dp),
                                shape = RoundedCornerShape(18.dp),
                                onClick = { pokazSortowanie = true },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)
                            ) {
                                Text("↕")
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterButton(
                                text = "Wszystkie",
                                selected = wybranaKategoria == "wszystkie",
                                onClick = { wybranaKategoria = "wszystkie" }
                            )

                            sklepy.sortedBy { it.kolejnosc }.forEach { sklep ->
                                FilterButton(
                                    text = "${sklep.emoji} ${sklep.nazwa}",
                                    selected = wybranaKategoria == sklep.id,
                                    onClick = { wybranaKategoria = sklep.id }
                                )
                            }
                        }

                        if (pokazSortowanie) {
                            AlertDialog(
                                onDismissRequest = { pokazSortowanie = false },
                                title = { Text("Sortowanie produktów") },
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        TextButton(onClick = { trybSortowania = "reczna"; pokazSortowanie = false }) {
                                            Text("↕ Ręczna kolejność")
                                        }
                                        TextButton(onClick = { trybSortowania = "az"; pokazSortowanie = false }) {
                                            Text("🔤 Alfabetycznie A → Z")
                                        }
                                        TextButton(onClick = { trybSortowania = "za"; pokazSortowanie = false }) {
                                            Text("🔤 Alfabetycznie Z → A")
                                        }
                                        TextButton(onClick = { trybSortowania = "dokupienia"; pokazSortowanie = false }) {
                                            Text("🛒 Najpierw do kupienia")
                                        }
                                        TextButton(onClick = { trybSortowania = "kupione"; pokazSortowanie = false }) {
                                            Text("✅ Najpierw kupione")
                                        }
                                    }
                                },
                                confirmButton = {
                                    TextButton(onClick = { pokazSortowanie = false }) {
                                        Text("Zamknij")
                                    }
                                }
                            )
                        }

                        if (pokazWyborListy) {
                            AlertDialog(
                                onDismissRequest = { pokazWyborListy = false },
                                title = { Text("Do której listy dodać?") },
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        ListaWyboruButton(
                                            emoji = "🏠",
                                            nazwa = "Główna",
                                            onClick = {
                                                dodajProduktDoListy(context, nowyProdukt, imie, "glowna")
                                                nowyProdukt = ""
                                                pokazWyborListy = false
                                                scope.launch {
                                                    snackbarHostState.showSnackbar("✅ Dodano do głównej")
                                                }
                                            }
                                        )

                                        sklepy.sortedBy { it.kolejnosc }.forEach { sklep ->
                                            ListaWyboruButton(
                                                emoji = sklep.emoji,
                                                nazwa = sklep.nazwa,
                                                sklep = sklep,
                                                onClick = {
                                                    dodajProduktDoListy(context, nowyProdukt, imie, sklep.id)
                                                    nowyProdukt = ""
                                                    pokazWyborListy = false
                                                    scope.launch {
                                                        snackbarHostState.showSnackbar("✅ Dodano do ${sklep.nazwa}")
                                                    }
                                                }
                                            )
                                        }
                                    }
                                },
                                confirmButton = {},
                                dismissButton = {
                                    TextButton(onClick = { pokazWyborListy = false }) {
                                        Text("ANULUJ")
                                    }
                                }
                            )
                        }

                        if (posortowanaListaLocal.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.BottomCenter
                            ) {
                                EmptyShoppingImage()
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            ) {
                                ProductDragList(
                                    produkty = posortowanaListaLocal,
                                    onMove = { from, to ->
                                        if (
                                            trybSortowania == "reczna" &&
                                            from in posortowanaListaLocal.indices &&
                                            to in posortowanaListaLocal.indices
                                        ) {
                                            val item = posortowanaListaLocal.removeAt(from)
                                            posortowanaListaLocal.add(to, item)
                                        }
                                    },
                                    onDragEnd = {
                                        if (trybSortowania == "reczna") {
                                            zapiszNowaKolejnosc(posortowanaListaLocal)
                                        }
                                    },
                                    onToggleProduct = { produkt, checked ->
                                        produkt.kupione = checked
                                        val now = if (checked) System.currentTimeMillis() else 0L
                                        produkt.kupioneOd = now
                                        db.collection("zakupy")
                                            .document(produkt.id)
                                            .update(
                                                mapOf(
                                                    "kupione" to checked,
                                                    "kupioneOd" to now
                                                )
                                            )
                                    },
                                    onDeleteProduct = { produkt -> produktDoUsuniecia = produkt },
                                    onEditProduct = { produkt -> produktDoEdycji = produkt },
                                    sklepy = sklepy,
                                    onAssignProduct = { produkt -> produktDoPrzydzielenia = produkt }
                                )
                            }
                        }

                        if (zaznaczoneProdukty.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                FloatingActionButton(
                                    onClick = { pokazUsunZaznaczone = true },
                                    containerColor = Color.Red,
                                    modifier = Modifier.padding(16.dp)
                                ) {
                                    Text("🗑 ${zaznaczoneProdukty.size}")
                                }
                            }

                            if (pokazUsunZaznaczone) {
                                AlertDialog(
                                    onDismissRequest = { pokazUsunZaznaczone = false },
                                    title = { Text("Usunąć produkty?") },
                                    text = {
                                        Text("Czy na pewno chcesz usunąć ${zaznaczoneProdukty.size} zaznaczone produkty?")
                                    },
                                    confirmButton = {
                                        TextButton(
                                            onClick = {
                                                zaznaczoneProdukty.forEach { produkt ->
                                                    db.collection("zakupy")
                                                        .document(produkt.id)
                                                        .delete()
                                                }
                                                pokazUsunZaznaczone = false
                                            }
                                        ) {
                                            Text("USUŃ", color = Color.Red)
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { pokazUsunZaznaczone = false }) {
                                            Text("ANULUJ")
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                1 -> {
                    if (wybranySklep == null) {
                        SklepyScreen(
                            sklepy = posortowaneSklepyLocal,
                            onSklepClick = { sklepId -> wybranySklep = sklepId },
                            onDodajSklep = { pokazDodajSklep = true },
                            onEdytujSklep = { sklep -> sklepDoEdycji = sklep },
                            onUsunSklep = { sklep -> sklepDoUsuniecia = sklep },
                            onDragStart = { przeciaganieSklepu = true },
                            onDragEnd = {
                                przeciaganieSklepu = false
                                zapiszNowaKolejnoscSklepow(posortowaneSklepyLocal)
                            },
                            onMove = { from, to ->
                                if (from in posortowaneSklepyLocal.indices && to in posortowaneSklepyLocal.indices) {
                                    val item = posortowaneSklepyLocal.removeAt(from)
                                    posortowaneSklepyLocal.add(to, item)
                                }
                            }
                        )
                    } else {
                        ListaSklepuScreen(
                            sklepId = wybranySklep!!,
                            sklep = sklepy.find { it.id == wybranySklep },
                            lista = lista,
                            imie = imie,
                            sharedWith = (udostepnieniUzytkownicy + sharedOwnerIds).distinct(),
                            onBack = { wybranySklep = null },
                            onDeleteProduct = { produkt -> produktDoUsuniecia = produkt },
                            onEditProduct = { produkt -> produktDoEdycji = produkt },
                            onAssignProduct = { produkt -> produktDoPrzydzielenia = produkt }
                        )
                    }
                }

                2 -> {
                    UstawieniaScreen(
                        currentEmail = emailKonta,
                        onEmailChanged = { emailKonta = it },
                        onThemeChanged = onThemeChanged,
                        onHapticsChanged = onHapticsChanged,
                        onShareList = { pokazWspoldzielenie = true },
                        onLogout = {
                            auth.signOut()
                            zalogowany = false
                            login = ""
                            pin = ""
                            imie = ""
                            emailKonta = ""
                            prefs.edit { clear() }
                        }
                    )
                }
            }

            if (pokazDodajSklep) {
                DodajLubEdytujSklepDialog(
                    sklep = null,
                    onDismiss = { pokazDodajSklep = false },
                    onSaved = { pokazDodajSklep = false }
                )
            }

            sklepDoEdycji?.let { sklep ->
                DodajLubEdytujSklepDialog(
                    sklep = sklep,
                    onDismiss = { sklepDoEdycji = null },
                    onSaved = { sklepDoEdycji = null }
                )
            }

            sklepDoUsuniecia?.let { sklep ->
                AlertDialog(
                    onDismissRequest = { sklepDoUsuniecia = null },
                    title = { Text("🗑️ Usunąć sklep?") },
                    text = {
                        Text(
                            "Czy na pewno chcesz usunąć sklep „${sklep.nazwa}”?\n\n" +
                                    "Produkty z tego sklepu zostaną przeniesione do listy Główna."
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                db.collection("zakupy")
                                    .whereEqualTo("kategoria", sklep.id)
                                    .whereEqualTo("userId", auth.currentUser?.uid ?: "")
                                    .get()
                                    .addOnSuccessListener { snapshot ->
                                        val batch = db.batch()
                                        snapshot.documents.forEach { document ->
                                            batch.update(document.reference, "kategoria", "glowna")
                                        }
                                        batch.commit().addOnSuccessListener {
                                            db.collection("sklepy")
                                                .document(sklep.id)
                                                .delete()
                                                .addOnSuccessListener {
                                                    if (wybranySklep == sklep.id) wybranySklep = null
                                                    if (wybranaKategoria == sklep.id) wybranaKategoria = "wszystkie"
                                                    sklepDoUsuniecia = null
                                                    scope.launch {
                                                        snackbarHostState.showSnackbar("🗑️ Usunięto sklep ${sklep.nazwa}")
                                                    }
                                                }
                                        }
                                    }
                            }
                        ) {
                            Text("USUŃ", color = Color.Red)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { sklepDoUsuniecia = null }) {
                            Text("ANULUJ")
                        }
                    }
                )
            }
        }

        if (pokazWspoldzielenie) {
            UdostepnijListeDialog(
                ownerUid = auth.currentUser?.uid ?: "",
                ownerName = imie.ifBlank { login },
                onSent = { pokazWspoldzielenie = false },
                onDismiss = { pokazWspoldzielenie = false }
            )
        }

        if (pokazZaproszenie) {
            PotwierdzZaproszenieDialog(
                inviteId = inviteId,
                ownerOfListUid = inviteOwnerUid,
                ownerName = inviteOwnerName,
                listName = inviteListName,
                initialError = inviteError,
                loading = inviteLoading,
                onAccepted = { ownerUid ->
                    sharedOwnerIds = setOf(ownerUid)
                    pokazZaproszenie = false
                    inviteError = null
                },
                onDismiss = {
                    pokazZaproszenie = false
                    inviteError = null
                }
            )
        }

        produktDoPrzydzielenia?.let { produkt ->
            AlertDialog(
                onDismissRequest = { produktDoPrzydzielenia = null },
                title = { Text("🏪 Przydziel do sklepu") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = produkt.nazwa,
                            style = MaterialTheme.typography.bodyMedium,
                            softWrap = true,
                            maxLines = 3,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Clip
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        sklepy.sortedBy { it.kolejnosc }.forEach { sklep ->
                            ListaWyboruButton(
                                emoji = sklep.emoji,
                                nazwa = sklep.nazwa,
                                sklep = sklep,
                                onClick = {
                                    FirebaseFirestore.getInstance()
                                        .collection("zakupy")
                                        .document(produkt.id)
                                        .update("kategoria", sklep.id)
                                    produktDoPrzydzielenia = null
                                    scope.launch {
                                        snackbarHostState.showSnackbar("✅ Przydzielono do ${sklep.nazwa}")
                                    }
                                }
                            )
                        }

                        TextButton(
                            onClick = {
                                FirebaseFirestore.getInstance()
                                    .collection("zakupy")
                                    .document(produkt.id)
                                    .update("kategoria", "glowna")
                                produktDoPrzydzielenia = null
                                scope.launch {
                                    snackbarHostState.showSnackbar("✅ Produkt nie należy do żadnego sklepu")
                                }
                            }
                        ) {
                            Text("🏠 Żadna")
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { produktDoPrzydzielenia = null }) {
                        Text("ANULUJ")
                    }
                }
            )
        }

        produktDoEdycji?.let { produkt ->
            var edytowanaNazwa by remember(produkt.id) { mutableStateOf(produkt.nazwa) }
            var edytowanaKategoria by remember(produkt.id) { mutableStateOf(produkt.kategoria) }

            AlertDialog(
                onDismissRequest = { produktDoEdycji = null },
                title = { Text("✏️ Edytuj produkt") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = edytowanaNazwa,
                            onValueChange = { edytowanaNazwa = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Nazwa produktu") },
                            singleLine = true
                        )

                        Text("Lista:")

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterButton(
                                text = "🏠 Główna",
                                selected = edytowanaKategoria == "glowna",
                                onClick = { edytowanaKategoria = "glowna" }
                            )

                            sklepy.forEach { sklep ->
                                FilterButton(
                                    text = "${sklep.emoji} ${sklep.nazwa}",
                                    selected = edytowanaKategoria == sklep.id,
                                    onClick = { edytowanaKategoria = sklep.id }
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val nowaNazwa = edytowanaNazwa.trim()
                            if (nowaNazwa.isNotEmpty()) {
                                db.collection("zakupy")
                                    .document(produkt.id)
                                    .update(
                                        mapOf(
                                            "nazwa" to nowaNazwa,
                                            "kategoria" to edytowanaKategoria
                                        )
                                    )
                                produktDoEdycji = null
                            }
                        }
                    ) {
                        Text("ZAPISZ")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { produktDoEdycji = null }) {
                        Text("ANULUJ")
                    }
                }
            )
        }

        produktDoUsuniecia?.let { produkt ->
            AlertDialog(
                onDismissRequest = { produktDoUsuniecia = null },
                title = { Text("🗑️ Usunąć produkt?") },
                text = { Text(produkt.nazwa) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            db.collection("zakupy")
                                .document(produkt.id)
                                .delete()
                            produktDoUsuniecia = null
                            scope.launch {
                                snackbarHostState.showSnackbar("🗑️ Usunięto ${produkt.nazwa}")
                            }
                        }
                    ) {
                        Text("USUŃ", color = Color.Red)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { produktDoUsuniecia = null }) {
                        Text("ANULUJ")
                    }
                }
            )
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 0.dp)
                .padding(bottom = 58.dp)
                .align(Alignment.BottomCenter),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                DolnaNawigacjaItem(
                    icon = "🛒",
                    label = "Lista",
                    selected = wybranaZakladka == 0,
                    onClick = {
                        wybranaZakladka = 0
                        wybranySklep = null
                    }
                )

                DolnaNawigacjaItem(
                    icon = "🏪",
                    label = "Sklepy",
                    selected = wybranaZakladka == 1,
                    onClick = {
                        wybranaZakladka = 1
                        wybranySklep = null
                    }
                )

                DolnaNawigacjaItem(
                    icon = "⚙️",
                    label = "Ustawienia",
                    selected = wybranaZakladka == 2,
                    onClick = {
                        wybranaZakladka = 2
                        wybranySklep = null
                    }
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp)
        )
    }
}

// =============================================================
// ELEMENT DOLNEJ NAWIGACJI
// =============================================================

@Composable
fun RowScope.DolnaNawigacjaItem(
    icon: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        label = "bottomNavBackground"
    )

    val contentColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "bottomNavContent"
    )

    Column(
        modifier = Modifier
            .weight(1f)
            .clickable(onClick = onClick)
            .background(
                color = backgroundColor,
                shape = RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = icon,
            modifier = Modifier.padding(bottom = 1.dp)
        )

        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor
        )
    }
}

// =============================================================
// DRAG & DROP PRODUKTÓW
// =============================================================

@Composable
fun ProductDragList(
    produkty: List<Produkt>,
    onMove: (Int, Int) -> Unit,
    onDragEnd: () -> Unit,
    onToggleProduct: (Produkt, Boolean) -> Unit,
    onDeleteProduct: (Produkt) -> Unit,
    onEditProduct: (Produkt) -> Unit,
    sklepy: List<Sklep> = emptyList(),
    onAssignProduct: ((Produkt) -> Unit)? = null
) {
    val listState = rememberLazyListState()
    val density = androidx.compose.ui.platform.LocalDensity.current

    var initiallyDraggedElement by remember { mutableStateOf<Produkt?>(null) }
    var currentIndexOfDraggedItem by remember { mutableStateOf<Int?>(null) }
    var fingerViewportY by remember { mutableFloatStateOf(0f) }
    var fingerOffsetInItem by remember { mutableFloatStateOf(0f) }
    var draggingItemSize by remember { mutableIntStateOf(0) }
    var autoScrollSpeed by remember { mutableFloatStateOf(0f) }

    val checkSwap = {
        val currentIndex = currentIndexOfDraggedItem
        val initiallyDragged = initiallyDraggedElement

        if (currentIndex != null && initiallyDragged != null) {
            val currentCenterY = fingerViewportY - fingerOffsetInItem + draggingItemSize / 2f
            val previousItem = listState.layoutInfo.visibleItemsInfo.find { it.index == currentIndex - 1 }
            val nextItem = listState.layoutInfo.visibleItemsInfo.find { it.index == currentIndex + 1 }

            var targetIndex = currentIndex

            if (previousItem != null && currentCenterY < previousItem.offset + previousItem.size / 2f) {
                targetIndex = currentIndex - 1
            } else if (nextItem != null && currentCenterY > nextItem.offset + nextItem.size / 2f) {
                targetIndex = currentIndex + 1
            }

            if (targetIndex != currentIndex) {
                onMove(currentIndex, targetIndex)
                currentIndexOfDraggedItem = targetIndex
            }
        }
    }

    LaunchedEffect(autoScrollSpeed) {
        if (autoScrollSpeed != 0f) {
            while (isActive) {
                listState.scrollBy(autoScrollSpeed)
                checkSwap()
                delay(16.milliseconds)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (produkty.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = "🛒", style = MaterialTheme.typography.displaySmall)
                    Text(text = "Lista jest pusta", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Dodaj pierwszy produkt,\naby rozpocząć zakupy.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                userScrollEnabled = initiallyDraggedElement == null,
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                items(items = produkty, key = { it.id }) { produkt ->
                    val isDragging = produkt.id == initiallyDraggedElement?.id
                    val alpha by animateFloatAsState(
                        targetValue = if (isDragging) 0f else if (produkt.kupione) 0.7f else 1f,
                        label = "alpha"
                    )

                    Box(modifier = Modifier) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                                .graphicsLayer { this.alpha = alpha }
                                .pointerInput(produkt.id) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { offset ->
                                            val index = produkty.indexOf(produkt)
                                            if (index >= 0) {
                                                val visibleItem = listState.layoutInfo.visibleItemsInfo.find { it.key == produkt.id }
                                                if (visibleItem != null) {
                                                    fingerOffsetInItem = offset.y
                                                    fingerViewportY = visibleItem.offset + offset.y
                                                    draggingItemSize = visibleItem.size
                                                    currentIndexOfDraggedItem = index
                                                    initiallyDraggedElement = produkt
                                                    autoScrollSpeed = 0f
                                                }
                                            }
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            fingerViewportY += dragAmount.y

                                            val viewportHeight = listState.layoutInfo.viewportSize.height.toFloat()
                                            val scrollThreshold = with(density) { 80.dp.toPx() }
                                            val distFromTop = fingerViewportY - fingerOffsetInItem
                                            val distFromBottom = viewportHeight - (fingerViewportY - fingerOffsetInItem + draggingItemSize)

                                            autoScrollSpeed = when {
                                                distFromTop < scrollThreshold -> {
                                                    val factor = 1f - (distFromTop / scrollThreshold).coerceIn(0f, 1f)
                                                    -(factor * 20f + 5f)
                                                }
                                                distFromBottom < scrollThreshold -> {
                                                    val factor = 1f - (distFromBottom / scrollThreshold).coerceIn(0f, 1f)
                                                    factor * 20f + 5f
                                                }
                                                else -> 0f
                                            }
                                            checkSwap()
                                        },
                                        onDragEnd = {
                                            onDragEnd()
                                            initiallyDraggedElement = null
                                            currentIndexOfDraggedItem = null
                                            autoScrollSpeed = 0f
                                        },
                                        onDragCancel = {
                                            initiallyDraggedElement = null
                                            currentIndexOfDraggedItem = null
                                            autoScrollSpeed = 0f
                                        }
                                    )
                                },
                            shape = RoundedCornerShape(28.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                        ) {
                            ProductCardContent(
                                produkt = produkt,
                                onToggleProduct = onToggleProduct,
                                onDeleteProduct = onDeleteProduct,
                                onEditProduct = onEditProduct,
                                sklepNazwa = sklepy.find { it.id == produkt.kategoria }?.nazwa,
                                onAssignProduct = { prod -> onAssignProduct?.invoke(prod) }
                            )
                        }
                    }
                }
            }
        }

        if (initiallyDraggedElement != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset {
                        IntOffset(0, (fingerViewportY - fingerOffsetInItem).roundToInt())
                    }
                    .scale(1.04f)
                    .graphicsLayer {
                        shadowElevation = 28.dp.toPx()
                        alpha = 1f
                    },
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 24.dp)
            ) {
                ProductCardContent(
                    produkt = initiallyDraggedElement!!,
                    onToggleProduct = { _, _ -> },
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
    sklepy: List<Sklep>,
    onMove: (Int, Int) -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onClick: (String) -> Unit,
    onEdit: (Sklep) -> Unit,
    onDelete: (Sklep) -> Unit
) {
    val listState = rememberLazyListState()
    val density = androidx.compose.ui.platform.LocalDensity.current

    var draggedShop by remember { mutableStateOf<Sklep?>(null) }
    var currentIndex by remember { mutableStateOf<Int?>(null) }
    var fingerViewportY by remember { mutableFloatStateOf(0f) }
    var fingerOffsetInItem by remember { mutableFloatStateOf(0f) }
    var draggedItemSize by remember { mutableIntStateOf(0) }
    var autoScrollSpeed by remember { mutableFloatStateOf(0f) }

    val checkSwap = {
        val index = currentIndex
        if (index != null && draggedShop != null) {
            val centerY = fingerViewportY - fingerOffsetInItem + draggedItemSize / 2f
            val previous = listState.layoutInfo.visibleItemsInfo.find { it.index == index - 1 }
            val next = listState.layoutInfo.visibleItemsInfo.find { it.index == index + 1 }

            var target = index

            if (previous != null && centerY < previous.offset + previous.size / 2f) {
                target = index - 1
            } else if (next != null && centerY > next.offset + next.size / 2f) {
                target = index + 1
            }

            if (target != index) {
                onMove(index, target)
                currentIndex = target
            }
        }
    }

    LaunchedEffect(autoScrollSpeed) {
        if (autoScrollSpeed != 0f) {
            while (isActive) {
                listState.scrollBy(autoScrollSpeed)
                checkSwap()
                delay(16.milliseconds)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            userScrollEnabled = draggedShop == null,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(items = sklepy, key = { it.id }) { sklep ->
                val isDragging = sklep.id == draggedShop?.id
                val alpha by animateFloatAsState(
                    targetValue = if (isDragging) 0f else 1f,
                    label = "shopAlpha"
                )

                Box(modifier = Modifier) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer { this.alpha = alpha }
                            .pointerInput(sklep.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { offset ->
                                        val index = sklepy.indexOf(sklep)
                                        if (index >= 0) {
                                            val visibleItem = listState.layoutInfo.visibleItemsInfo.find { it.key == sklep.id }
                                            if (visibleItem != null) {
                                                draggedShop = sklep
                                                currentIndex = index
                                                draggedItemSize = visibleItem.size
                                                fingerOffsetInItem = offset.y
                                                fingerViewportY = visibleItem.offset + offset.y
                                                autoScrollSpeed = 0f
                                                onDragStart()
                                            }
                                        }
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        fingerViewportY += dragAmount.y

                                        val viewportHeight = listState.layoutInfo.viewportSize.height.toFloat()
                                        val threshold = with(density) { 80.dp.toPx() }
                                        val topDistance = fingerViewportY - fingerOffsetInItem
                                        val bottomDistance = viewportHeight - (fingerViewportY - fingerOffsetInItem + draggedItemSize)

                                        autoScrollSpeed = when {
                                            topDistance < threshold -> {
                                                val factor = 1f - (topDistance / threshold).coerceIn(0f, 1f)
                                                -(factor * 20f + 5f)
                                            }
                                            bottomDistance < threshold -> {
                                                val factor = 1f - (bottomDistance / threshold).coerceIn(0f, 1f)
                                                factor * 20f + 5f
                                            }
                                            else -> 0f
                                        }
                                        checkSwap()
                                    },
                                    onDragEnd = {
                                        autoScrollSpeed = 0f
                                        draggedShop = null
                                        currentIndex = null
                                        onDragEnd()
                                    },
                                    onDragCancel = {
                                        autoScrollSpeed = 0f
                                        draggedShop = null
                                        currentIndex = null
                                        onDragEnd()
                                    }
                                )
                            },
                        onClick = { onClick(sklep.id) },
                        shape = RoundedCornerShape(24.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = if (isDragging) 14.dp else 5.dp)
                    ) {
                        SklepCardContent(
                            sklep = sklep,
                            onEdit = { onEdit(sklep) },
                            onDelete = { onDelete(sklep) }
                        )
                    }
                }
            }
        }

        if (draggedShop != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset {
                        IntOffset(0, (fingerViewportY - fingerOffsetInItem).roundToInt())
                    }
                    .scale(1.04f)
                    .graphicsLayer {
                        shadowElevation = 28.dp.toPx()
                        alpha = 1f
                    },
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 24.dp)
            ) {
                SklepCardContent(
                    sklep = draggedShop!!,
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
    sklep: Sklep,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(16.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            SklepIcon(
                sklep = sklep,
                modifier = Modifier.size(40.dp)
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp, end = 4.dp)
        ) {
            Text(
                text = sklep.nazwa,
                style = MaterialTheme.typography.titleMedium,
                softWrap = true,
                maxLines = 3,
                overflow = androidx.compose.ui.text.style.TextOverflow.Clip
            )

            Text(
                text = "Lista zakupów",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Box(
            modifier = Modifier
                .size(36.dp)
                .clickable { onEdit() },
            contentAlignment = Alignment.Center
        ) {
            Text("✏️")
        }

        Box(
            modifier = Modifier
                .size(36.dp)
                .clickable { onDelete() },
            contentAlignment = Alignment.Center
        ) {
            Text("🗑️")
        }

        Text(
            text = "⋮⋮",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 2.dp)
        )
    }
}

// =============================================================
// EKRAN SKLEPÓW
// =============================================================

@Composable
fun SklepyScreen(
    sklepy: List<Sklep>,
    onSklepClick: (String) -> Unit,
    onDodajSklep: () -> Unit,
    onEdytujSklep: (Sklep) -> Unit,
    onUsunSklep: (Sklep) -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onMove: (Int, Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "🏪 Sklepy",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = "Przytrzymaj sklep, aby zmienić kolejność",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(onClick = onDodajSklep) {
                Text("＋ Sklep")
            }
        }

        if (sklepy.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = "🏪", style = MaterialTheme.typography.displaySmall)
                    Text(text = "Brak sklepów", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Dodaj pierwszy sklep,\naby utworzyć własną listę.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                SklepDragList(
                    sklepy = sklepy,
                    onMove = onMove,
                    onDragStart = onDragStart,
                    onDragEnd = onDragEnd,
                    onClick = onSklepClick,
                    onEdit = onEdytujSklep,
                    onDelete = onUsunSklep
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
    sklepId: String,
    sklep: Sklep?,
    lista: List<Produkt>,
    imie: String,
    sharedWith: List<String> = emptyList(),
    onBack: () -> Unit,
    onDeleteProduct: (Produkt) -> Unit,
    onEditProduct: (Produkt) -> Unit,
    onAssignProduct: (Produkt) -> Unit
) {
    val context = LocalContext.current
    var nowyProduktSklepu by remember(sklepId) { mutableStateOf("") }
    val produktySklepu = lista.filter { it.kategoria == sklepId }.sortedBy { it.kolejnosc }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 24.dp, top = 16.dp, end = 24.dp, bottom = 160.dp)
    ) {
        TextButton(onClick = onBack) {
            Text("← Sklepy")
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (sklep != null) {
                SklepIcon(
                    sklep = sklep,
                    modifier = Modifier
                        .height(50.dp)
                        .fillMaxWidth(0.15f)
                )
            }

            Text(
                text = sklep?.nazwa ?: sklepId,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
                style = MaterialTheme.typography.headlineSmall,
                softWrap = true,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Clip
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = nowyProduktSklepu,
                onValueChange = { nowyProduktSklepu = it },
                modifier = Modifier
                    .weight(1f)
                    .height(58.dp),
                label = { Text("🛍️ Produkt") },
                singleLine = true,
                shape = RoundedCornerShape(18.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                modifier = Modifier.height(58.dp),
                shape = RoundedCornerShape(18.dp),
                onClick = {
                    val produkt = nowyProduktSklepu.trim()
                    if (produkt.isNotEmpty()) {
                        dodajProduktDoListy(context, produkt, imie, sklepId, sharedWith)
                        nowyProduktSklepu = ""
                    }
                },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)
            ) {
                Text("＋")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (produktySklepu.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text("Brak produktów w tym sklepie")
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items = produktySklepu, key = { it.id }) { produkt ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        ProductCardContent(
                            produkt = produkt,
                            onToggleProduct = { p, checked ->
                                val now = if (checked) System.currentTimeMillis() else 0L
                                p.kupione = checked
                                p.kupioneOd = now
                                FirebaseFirestore.getInstance()
                                    .collection("zakupy")
                                    .document(p.id)
                                    .update(
                                        mapOf(
                                            "kupione" to checked,
                                            "kupioneOd" to now
                                        )
                                    )
                            },
                            onDeleteProduct = onDeleteProduct,
                            onEditProduct = onEditProduct,
                            sklepNazwa = sklep?.nazwa ?: sklepId,
                            onAssignProduct = onAssignProduct
                        )
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
    produkt: Produkt,
    onToggleProduct: (Produkt, Boolean) -> Unit,
    onDeleteProduct: (Produkt) -> Unit,
    onEditProduct: (Produkt) -> Unit,
    sklepNazwa: String? = null,
    onAssignProduct: ((Produkt) -> Unit)? = null
) {
    var checked by remember(produkt.id, produkt.kupione) {
        mutableStateOf(produkt.kupione)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            modifier = Modifier.scale(0.78f),
            checked = checked,
            onCheckedChange = {
                checked = it
                onToggleProduct(produkt, it)
            }
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp, end = 4.dp)
        ) {
            Text(
                text = produkt.nazwa,
                style = MaterialTheme.typography.bodyMedium,
                softWrap = true,
                maxLines = 4,
                overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
                textDecoration = if (produkt.kupione) TextDecoration.LineThrough else TextDecoration.None
            )

            Text(
                text = "Dodał: ${produkt.dodal}",
                style = MaterialTheme.typography.labelSmall,
                softWrap = true,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (sklepNazwa?.isNotBlank() == true) "Sklep: $sklepNazwa" else "Sklep: Żadna",
                    style = MaterialTheme.typography.labelSmall,
                    softWrap = true,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (onAssignProduct != null) {
            IconButton(
                onClick = { onAssignProduct(produkt) },
                modifier = Modifier.size(30.dp)
            ) {
                Text(text = "🏪", fontSize = 15.sp)
            }
        }

        Box(
            modifier = Modifier
                .size(36.dp)
                .clickable { onEditProduct(produkt) },
            contentAlignment = Alignment.Center
        ) {
            Text("✏️")
        }

        Box(
            modifier = Modifier
                .size(36.dp)
                .clickable { onDeleteProduct(produkt) },
            contentAlignment = Alignment.Center
        ) {
            Text("🗑️")
        }

        Text(
            text = "⋮⋮",
            modifier = Modifier.padding(start = 2.dp)
        )
    }
}

// =============================================================
// WYBÓR LISTY
// =============================================================

@Composable
fun ListaWyboruButton(
    emoji: String,
    nazwa: String,
    sklep: Sklep? = null,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (sklep != null) {
                SklepIcon(
                    sklep = sklep,
                    modifier = Modifier
                        .height(42.dp)
                        .fillMaxWidth(0.15f)
                )
            } else {
                Text(
                    text = emoji,
                    style = MaterialTheme.typography.headlineSmall
                )
            }

            Text(
                text = nazwa,
                modifier = Modifier.padding(start = 14.dp),
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

// =============================================================
// FILTR
// =============================================================

@Composable
fun FilterButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        label = "filterBackground"
    )

    val contentColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "filterContent"
    )

    val scale by animateFloatAsState(
        targetValue = if (selected) 1.03f else 1f,
        label = "filterScale"
    )

    Card(
        onClick = onClick,
        modifier = Modifier
            .scale(scale)
            .height(20.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 3.dp else 1.dp)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 0.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (selected) "✓ $text" else text,
                color = contentColor,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

// =============================================================
// DODAJ PRODUKT
// =============================================================

fun dodajProduktDoListy(
    context: Context,
    nazwa: String,
    imie: String,
    kategoria: String,
    sharedWith: List<String> = emptyList()
) {
    val produkt = nazwa.trim()
    if (produkt.isEmpty()) return

    val auth = FirebaseAuth.getInstance()
    val db = FirebaseFirestore.getInstance()
    val uid = auth.currentUser?.uid ?: return
    val prefs = context.getSharedPreferences("lista_zakupow", Context.MODE_PRIVATE)
    val sharedOwnerId = prefs.getString("shared_owner_id", "") ?: ""

    if (sharedOwnerId.isNotBlank()) {
        db.collection("users").document(sharedOwnerId).get()
            .addOnSuccessListener { owner ->
                val members = (owner.get("sharedWith") as? List<*>)
                    ?.filterIsInstance<String>()
                    ?.toMutableList() ?: mutableListOf()
                if (uid !in members) members.add(uid)
                if (sharedOwnerId !in members) members.add(sharedOwnerId)

                db.collection("zakupy").add(
                    hashMapOf(
                        "nazwa" to produkt,
                        "dodal" to imie,
                        "kupione" to false,
                        "kupioneOd" to 0L,
                        "kolejnosc" to System.currentTimeMillis(),
                        "kategoria" to kategoria,
                        "userId" to sharedOwnerId,
                        "sharedWith" to members.distinct()
                    )
                )
            }
        return
    }

    db.collection("zakupy").add(
        hashMapOf(
            "nazwa" to produkt,
            "dodal" to imie,
            "kupione" to false,
            "kupioneOd" to 0L,
            "kolejnosc" to System.currentTimeMillis(),
            "kategoria" to kategoria,
            "userId" to uid,
            "sharedWith" to sharedWith.distinct()
        )
    )
}

// =============================================================
// ZAPIS KOLEJNOŚCI PRODUKTÓW
// =============================================================

fun zapiszNowaKolejnosc(produkty: List<Produkt>) {
    val db = FirebaseFirestore.getInstance()
    produkty.forEachIndexed { index, produkt ->
        val nowaKolejnosc = index.toLong()
        produkt.kolejnosc = nowaKolejnosc
        db.collection("zakupy")
            .document(produkt.id)
            .update("kolejnosc", nowaKolejnosc)
    }
}

// =============================================================
// ZAPIS KOLEJNOŚCI SKLEPÓW
// =============================================================

fun zapiszNowaKolejnoscSklepow(sklepy: List<Sklep>) {
    val db = FirebaseFirestore.getInstance()
    sklepy.forEachIndexed { index, sklep ->
        val nowaKolejnosc = index.toLong()
        db.collection("sklepy")
            .document(sklep.id)
            .update("kolejnosc", nowaKolejnosc)
    }
}

// =============================================================
// DODAJ / EDYTUJ SKLEP
// =============================================================

@Composable
fun DodajLubEdytujSklepDialog(
    sklep: Sklep?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val db = FirebaseFirestore.getInstance()

    var nazwa by remember(sklep?.id) { mutableStateOf(sklep?.nazwa ?: "") }
    var emoji by remember(sklep?.id) { mutableStateOf(sklep?.emoji ?: "🏪") }
    var typIkony by remember(sklep?.id) { mutableStateOf(sklep?.typIkony ?: "emoji") }
    var wybraneUri by remember(sklep?.id) { mutableStateOf<Uri?>(null) }
    var obrazDane by remember(sklep?.id) { mutableStateOf(sklep?.obrazDane ?: "") }
    var blad by remember { mutableStateOf<String?>(null) }
    var zapisywanie by remember { mutableStateOf(false) }

    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            wybraneUri = uri
            typIkony = "image"
        }
    }

    AlertDialog(
        onDismissRequest = { if (!zapisywanie) onDismiss() },
        title = {
            Text(if (sklep == null) "➕ Dodaj sklep" else "✏️ Edytuj sklep")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = nazwa,
                    onValueChange = { nazwa = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Nazwa sklepu") },
                    singleLine = true
                )

                Text("Ikona sklepu")

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterButton(
                        text = "😀 Emoji",
                        selected = typIkony == "emoji",
                        onClick = {
                            typIkony = "emoji"
                            wybraneUri = null
                            obrazDane = ""
                        }
                    )

                    FilterButton(
                        text = "🖼️ Logo",
                        selected = typIkony == "image",
                        onClick = {
                            typIkony = "image"
                            launcher.launch("image/*")
                        }
                    )
                }

                if (typIkony == "emoji") {
                    OutlinedTextField(
                        value = emoji,
                        onValueChange = { emoji = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Emoji") },
                        singleLine = true
                    )
                } else {
                    if (wybraneUri != null) {
                        AsyncImage(
                            model = wybraneUri,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                        )
                    } else if (obrazDane.isNotEmpty()) {
                        SklepIcon(
                            sklep = Sklep(
                                nazwa = nazwa,
                                typIkony = "image",
                                obrazDane = obrazDane
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                        )
                    } else {
                        Text("Nie wybrano zdjęcia")
                    }

                    if (wybraneUri != null || obrazDane.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                wybraneUri = null
                                obrazDane = ""
                                typIkony = "emoji"
                            }
                        ) {
                            Text("🗑️ Usuń logo")
                        }
                    }
                }

                blad?.let {
                    Text(
                        text = "❌ $it",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !zapisywanie,
                onClick = {
                    val cleanName = nazwa.trim()
                    if (cleanName.isEmpty()) {
                        blad = "Podaj nazwę sklepu"
                        return@TextButton
                    }

                    zapisywanie = true
                    val uid = FirebaseAuth.getInstance().currentUser?.uid

                    if (uid.isNullOrBlank()) {
                        zapisywanie = false
                        blad = "Brak zalogowanego użytkownika."
                        return@TextButton
                    }

                    val baseId = cleanName.lowercase().replace(" ", "_").replace(Regex("[^a-z0-9ąćęłńóśźż_]"), "")
                    val id = sklep?.id ?: "${uid}_${baseId}"
                    val kolejnosc = sklep?.kolejnosc ?: System.currentTimeMillis()

                    fun zapisz(finalImage: String) {
                        db.collection("users").document(uid).get()
                            .addOnSuccessListener { profile ->
                                val sharedWith = (profile.get("sharedWith") as? List<*>)
                                    ?.filterIsInstance<String>()
                                    ?: emptyList()

                                db.collection("sklepy")
                                    .document(id)
                                    .set(
                                        mapOf(
                                            "nazwa" to cleanName,
                                            "typIkony" to if (finalImage.isNotEmpty()) "image" else "emoji",
                                            "emoji" to emoji,
                                            "obrazDane" to finalImage,
                                            "kolejnosc" to kolejnosc,
                                            "userId" to uid,
                                            "sharedWith" to sharedWith
                                        )
                                    )
                                    .addOnSuccessListener {
                                        zapisywanie = false
                                        onSaved()
                                    }
                                    .addOnFailureListener { error ->
                                        zapisywanie = false
                                        blad = error.message ?: "Nie udało się zapisać sklepu"
                                    }
                            }
                            .addOnFailureListener { error ->
                                zapisywanie = false
                                blad = error.message ?: "Nie udało się pobrać ustawień udostępniania"
                            }
                    }

                    if (typIkony == "image" && wybraneUri != null) {
                        val encoded = imageUriToBase64(context, wybraneUri!!)
                        if (encoded == null) {
                            zapisywanie = false
                            blad = "Nie udało się przetworzyć zdjęcia"
                        } else {
                            obrazDane = encoded
                            zapisz(encoded)
                        }
                    } else {
                        zapisz(if (typIkony == "image") obrazDane else "")
                    }
                }
            ) {
                Text(
                    if (zapisywanie) "ZAPISYWANIE..." else if (sklep == null) "DODAJ" else "ZAPISZ"
                )
            }
        },
        dismissButton = {
            TextButton(
                enabled = !zapisywanie,
                onClick = onDismiss
            ) {
                Text("ANULUJ")
            }
        }
    )
}

// =============================================================
// WSPÓŁDZIELENIE LISTY
// =============================================================

@Composable
fun UdostepnijListeDialog(
    ownerUid: String,
    ownerName: String,
    onSent: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val db = FirebaseFirestore.getInstance()
    var personName by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = { Text("👥 Udostępnij listę") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Wpisz nazwę osoby, np. „Ciocia”. Ta nazwa jest tylko dla Ciebie.")
                OutlinedTextField(
                    value = personName,
                    onValueChange = { personName = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading,
                    singleLine = true,
                    label = { Text("Nazwa osoby") },
                    placeholder = { Text("np. Ciocia") }
                )
                Text(
                    "Link będzie działał przez 24 godziny i nie wymaga domeny.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !loading,
                onClick = {
                    val cleanName = personName.trim()
                    if (cleanName.isBlank()) {
                        error = "Wpisz nazwę osoby."
                        return@Button
                    }
                    if (ownerUid.isBlank()) {
                        error = "Brak zalogowanego użytkownika."
                        return@Button
                    }

                    loading = true
                    val inviteCode = generateInviteCode()
                    val inviteRef = db.collection("invitations").document(inviteCode)
                    val now = System.currentTimeMillis()
                    val link = inviteUri(inviteCode).toString()

                    inviteRef.set(
                        mapOf(
                            "ownerUid" to ownerUid,
                            "ownerName" to ownerName.ifBlank { "Użytkownik" },
                            "personName" to cleanName,
                            "listName" to "Lista Zakupów",
                            "status" to "pending",
                            "createdAt" to now,
                            "expiresAt" to now + INVITE_VALIDITY_MS
                        )
                    ).addOnSuccessListener {
                        context.getSharedPreferences("lista_zakupow", Context.MODE_PRIVATE)
                            .edit { putString("invite_name_$inviteCode", cleanName) }

                        // Zapamiętujemy nazwę osoby u właściciela od razu.
                        db.collection("users")
                            .document(ownerUid)
                            .set(
                                mapOf(
                                    "sharedWithNames.$inviteCode" to cleanName
                                ),
                                com.google.firebase.firestore.SetOptions.merge()
                            )
                            .addOnCompleteListener {
                                val shareText =
                                    "Zaproszenie do wspólnej listy zakupów od ${ownerName.ifBlank { "Użytkownik" }}:\n$link"

                                try {
                                    context.startActivity(
                                        Intent.createChooser(
                                            Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, shareText)
                                            },
                                            "Wyślij zaproszenie"
                                        )
                                    )
                                    loading = false
                                    onSent()
                                } catch (_: Exception) {
                                    loading = false
                                    error = "Nie udało się otworzyć udostępniania. Link: $link"
                                }
                            }
                    }.addOnFailureListener { e ->
                        loading = false
                        error = e.message ?: "Nie udało się utworzyć zaproszenia."
                    }
                }
            ) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text("WYŚLIJ LINK")
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !loading, onClick = onDismiss) {
                Text("ANULUJ")
            }
        }
    )
}

@Composable
fun PotwierdzZaproszenieDialog(
    inviteId: String,
    ownerOfListUid: String,
    ownerName: String,
    listName: String,
    initialError: String?,
    loading: Boolean,
    onAccepted: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val db = FirebaseFirestore.getInstance()
    val auth = FirebaseAuth.getInstance()
    var working by remember { mutableStateOf(loading) }
    var error by remember(initialError) { mutableStateOf(initialError) }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("🤝 Zaproszenie do wspólnej listy") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (ownerOfListUid.isBlank()) {
                    Text("To zaproszenie jest nieprawidłowe.")
                } else {
                    Text("$ownerName zaprasza Cię do wspólnej listy „$listName”.")
                    Text("Po dołączeniu obie osoby będą widzieć i edytować tę samą listę zakupów.")
                }
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !working && inviteId.isNotBlank() && ownerOfListUid.isNotBlank(),
                onClick = {
                    val memberUid = auth.currentUser?.uid
                    if (memberUid.isNullOrBlank()) {
                        error = "Najpierw zaloguj się do swojego konta."
                        return@Button
                    }
                    if (memberUid == ownerOfListUid) {
                        error = "Nie możesz zaakceptować własnego zaproszenia."
                        return@Button
                    }

                    working = true
                    val inviteRef = db.collection("invitations").document(inviteId)
                    inviteRef.get().addOnSuccessListener { invite ->
                        if (!invite.exists()) {
                            working = false
                            error = "To zaproszenie nie istnieje."
                            return@addOnSuccessListener
                        }

                        val status = invite.getString("status") ?: "pending"
                        val expiresAt = invite.getLong("expiresAt") ?: 0L
                        val realOwnerName = invite.getString("ownerName") ?: ownerName
                        val invitedPersonName = invite.getString("personName") ?: "Użytkownik"

                        when {
                            status != "pending" -> {
                                working = false
                                error = "To zaproszenie zostało już wykorzystane."
                            }
                            expiresAt > 0L && expiresAt < System.currentTimeMillis() -> {
                                working = false
                                error = "To zaproszenie wygasło."
                            }
                            else -> {
                                val ownerRef = db.collection("users").document(ownerOfListUid)
                                val memberRef = db.collection("users").document(memberUid)

                                ownerRef.get().addOnSuccessListener { owner ->
                                    val ownerNameFromProfile =
                                        owner.getString("imie")
                                            ?: owner.getString("login")
                                            ?: realOwnerName

                                    val batch = db.batch()
                                    batch.update(
                                        ownerRef,
                                        "sharedWith",
                                        FieldValue.arrayUnion(memberUid),
                                        "sharedWithNames.$memberUid",
                                        invitedPersonName
                                    )
                                    batch.set(
                                        memberRef,
                                        mapOf(
                                            "sharedOwnerId" to ownerOfListUid,
                                            "sharedOwnerName" to ownerNameFromProfile
                                        ),
                                        com.google.firebase.firestore.SetOptions.merge()
                                    )
                                    batch.update(
                                        inviteRef,
                                        "status",
                                        "accepted",
                                        "acceptedBy",
                                        memberUid,
                                        "acceptedAt",
                                        System.currentTimeMillis()
                                    )

                                    batch.commit().addOnSuccessListener {
                                        // Udostępniamy wszystkie istniejące produkty właściciela.
                                        db.collection("zakupy")
                                            .whereEqualTo("userId", ownerOfListUid)
                                            .get()
                                            .addOnSuccessListener { snapshot ->
                                                val productBatch = db.batch()
                                                snapshot.documents.forEach { doc ->
                                                    productBatch.update(
                                                        doc.reference,
                                                        "sharedWith",
                                                        FieldValue.arrayUnion(memberUid)
                                                    )
                                                }

                                                productBatch.commit().addOnSuccessListener {
                                                    // Udostępniamy również istniejące sklepy/kategorie.
                                                    db.collection("sklepy")
                                                        .whereEqualTo("userId", ownerOfListUid)
                                                        .get()
                                                        .addOnSuccessListener { shopSnapshot ->
                                                            val shopBatch = db.batch()
                                                            shopSnapshot.documents.forEach { doc ->
                                                                shopBatch.update(
                                                                    doc.reference,
                                                                    "sharedWith",
                                                                    FieldValue.arrayUnion(memberUid)
                                                                )
                                                            }

                                                            shopBatch.commit().addOnSuccessListener {
                                                                context
                                                                    .getSharedPreferences("lista_zakupow", Context.MODE_PRIVATE)
                                                                    .edit {
                                                                        putString("shared_owner_id", ownerOfListUid)
                                                                        putString("shared_owner_name", ownerNameFromProfile)
                                                                    }
                                                                working = false
                                                                onAccepted(ownerOfListUid)
                                                            }.addOnFailureListener { e ->
                                                                working = false
                                                                error = e.message ?: "Nie udało się udostępnić sklepów."
                                                            }
                                                        }
                                                        .addOnFailureListener { e ->
                                                            working = false
                                                            error = e.message ?: "Nie udało się pobrać sklepów właściciela."
                                                        }
                                                }.addOnFailureListener { e ->
                                                    working = false
                                                    error = e.message ?: "Nie udało się udostępnić istniejących produktów."
                                                }
                                            }
                                            .addOnFailureListener { e ->
                                                working = false
                                                error = e.message ?: "Nie udało się pobrać listy właściciela."
                                            }
                                    }.addOnFailureListener { e ->
                                        working = false
                                        error = e.message ?: "Nie udało się zaakceptować zaproszenia."
                                    }
                                }.addOnFailureListener { e ->
                                    working = false
                                    error = e.message ?: "Nie znaleziono właściciela listy."
                                }
                            }
                        }
                    }.addOnFailureListener { e ->
                        working = false
                        error = e.message ?: "Nie udało się sprawdzić zaproszenia."
                    }
                }
            ) {
                if (working) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text("DOŁĄCZ DO LISTY")
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !working, onClick = onDismiss) {
                Text("ODRZUĆ")
            }
        }
    )
}

// =============================================================
// USTAWIENIA
// =============================================================

private const val PREFS_SETTINGS = "lista_zakupow_settings"
private const val KEY_AUTO_DELETE = "auto_delete"
private const val KEY_DELETE_MINUTES = "delete_minutes"
private const val KEY_THEME = "theme"
private const val KEY_DEFAULT_SORT = "default_sort"
private const val KEY_CONFIRM_DELETE = "confirm_delete"
private const val KEY_HAPTICS = "haptics"

private fun settingsPrefs(context: Context) =
    context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)

@Composable
fun UstawieniaScreen(
    currentEmail: String,
    onEmailChanged: (String) -> Unit,
    onThemeChanged: (String) -> Unit,
    onHapticsChanged: (Boolean) -> Unit,
    onShareList: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { settingsPrefs(context) }

    var autoDelete by remember { mutableStateOf(prefs.getBoolean(KEY_AUTO_DELETE, true)) }
    var deleteMinutes by remember { mutableIntStateOf(prefs.getInt(KEY_DELETE_MINUTES, 20)) }
    var theme by remember { mutableStateOf(prefs.getString(KEY_THEME, "system") ?: "system") }
    var defaultSort by remember { mutableStateOf(prefs.getString(KEY_DEFAULT_SORT, "reczna") ?: "reczna") }
    var confirmDelete by remember { mutableStateOf(prefs.getBoolean(KEY_CONFIRM_DELETE, true)) }
    var haptics by remember { mutableStateOf(prefs.getBoolean(KEY_HAPTICS, true)) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var nowyEmail by remember { mutableStateOf(currentEmail) }
    var emailTrwa by remember { mutableStateOf(false) }
    var komunikatEmail by remember { mutableStateOf<String?>(null) }

    fun saveBoolean(key: String, value: Boolean) {
        prefs.edit { putBoolean(key, value) }
    }

    fun saveInt(key: String, value: Int) {
        prefs.edit { putInt(key, value) }
    }

    fun saveString(key: String, value: String) {
        prefs.edit { putString(key, value) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 140.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "⚙️ Ustawienia",
            style = MaterialTheme.typography.headlineSmall
        )

        Text(
            text = "Dostosuj działanie aplikacji do swoich potrzeb.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(4.dp))

        SettingsSectionTitle("🎨 Wygląd")

        SettingsCard {
            SettingsRow(
                title = "Motyw aplikacji",
                subtitle = when (theme) {
                    "dark" -> "Ciemny"
                    "light" -> "Jasny"
                    else -> "Systemowy"
                },
                value = "›",
                onClick = { dialog = "theme" }
            )
        }

        SettingsSectionTitle("👤 Konto")

        SettingsCard {
            SettingsRow(
                title = "Zalogowane konto",
                subtitle = currentEmail.ifBlank { "Nie ustawiono" },
                value = "›",
                onClick = {
                    nowyEmail = currentEmail
                    komunikatEmail = null
                    dialog = "email"
                }
            )

            HorizontalDivider()

            SettingsRow(
                title = "Resetuj hasło",
                subtitle = "Wyślij link na przypisany e-mail",
                value = "›",
                onClick = { dialog = "email_reset_info" }
            )

            HorizontalDivider()

            SettingsRow(
                title = "Wyloguj",
                subtitle = "Zakończ sesję na tym urządzeniu",
                value = "↪",
                onClick = onLogout
            )
        }

        SettingsSectionTitle("👥 Współdzielenie")

        SettingsCard {
            SettingsRow(
                title = "Udostępnij listę",
                subtitle = "Dodaj osoby, które mogą korzystać z Twojej listy",
                value = "›",
                onClick = onShareList
            )
        }

        SettingsSectionTitle("🛒 Lista zakupów")

        SettingsCard {
            SettingsSwitchRow(
                title = "Automatyczne usuwanie kupionych",
                subtitle = if (autoDelete) "Włączone — po $deleteMinutes min" else "Wyłączone",
                checked = autoDelete,
                onCheckedChange = {
                    autoDelete = it
                    saveBoolean(KEY_AUTO_DELETE, it)
                }
            )

            HorizontalDivider()

            SettingsRow(
                title = "Czas usunięcia",
                subtitle = "$deleteMinutes minut",
                value = "›",
                enabled = autoDelete,
                onClick = {
                    if (autoDelete) dialog = "delete_time"
                }
            )

            HorizontalDivider()

            SettingsRow(
                title = "Domyślne sortowanie",
                subtitle = when (defaultSort) {
                    "az" -> "A → Z"
                    "za" -> "Z → A"
                    "dokupienia" -> "Najpierw do kupienia"
                    "kupione" -> "Najpierw kupione"
                    else -> "Ręczna kolejność"
                },
                value = "›",
                onClick = { dialog = "sort" }
            )
        }

        SettingsSectionTitle("🗑️ Bezpieczeństwo")

        SettingsCard {
            SettingsSwitchRow(
                title = "Potwierdzaj usuwanie",
                subtitle = "Pokaż pytanie przed usunięciem sklepu lub produktu",
                checked = confirmDelete,
                onCheckedChange = {
                    confirmDelete = it
                    saveBoolean(KEY_CONFIRM_DELETE, it)
                }
            )
        }

        SettingsSectionTitle("📱 Dodatkowe")

        SettingsCard {
            SettingsSwitchRow(
                title = "Wibracje przy kliknięciu",
                subtitle = if (haptics) "Włączone" else "Wyłączone",
                checked = haptics,
                onCheckedChange = {
                    haptics = it
                    saveBoolean(KEY_HAPTICS, it)
                    onHapticsChanged(it)
                }
            )

            HorizontalDivider()

            SettingsRow(
                title = "ℹ️ Informacje o aplikacji",
                subtitle = "Lista Zakupów V2.0 • wersja 2.0",
                value = "›",
                onClick = { dialog = "about" }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                prefs.edit { clear() }
                autoDelete = true
                deleteMinutes = 20
                theme = "system"
                defaultSort = "reczna"
                confirmDelete = true
                haptics = true
                onHapticsChanged(true)
                onThemeChanged("system")
            }
        ) {
            Text("Przywróć ustawienia domyślne")
        }
    }

    if (dialog != null) {
        when (dialog) {
            "theme" -> {
                ChoiceDialog(
                    title = "🎨 Motyw aplikacji",
                    options = listOf(
                        "system" to "Systemowy",
                        "light" to "Jasny",
                        "dark" to "Ciemny"
                    ),
                    selected = theme,
                    onSelect = {
                        theme = it
                        saveString(KEY_THEME, it)
                        onThemeChanged(it)
                        dialog = null
                    },
                    onDismiss = { dialog = null }
                )
            }

            "delete_time" -> {
                ChoiceDialog(
                    title = "⏱️ Czas usunięcia",
                    options = listOf(
                        "5" to "5 minut",
                        "10" to "10 minut",
                        "20" to "20 minut",
                        "30" to "30 minut",
                        "60" to "60 minut"
                    ),
                    selected = deleteMinutes.toString(),
                    onSelect = {
                        deleteMinutes = it.toInt()
                        saveInt(KEY_DELETE_MINUTES, deleteMinutes)
                        dialog = null
                    },
                    onDismiss = { dialog = null }
                )
            }

            "sort" -> {
                ChoiceDialog(
                    title = "↕️ Domyślne sortowanie",
                    options = listOf(
                        "reczna" to "Ręczna kolejność",
                        "az" to "A → Z",
                        "za" to "Z → A",
                        "dokupienia" to "Najpierw do kupienia",
                        "kupione" to "Najpierw kupione"
                    ),
                    selected = defaultSort,
                    onSelect = {
                        defaultSort = it
                        saveString(KEY_DEFAULT_SORT, it)
                        dialog = null
                    },
                    onDismiss = { dialog = null }
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
                                user.verifyBeforeUpdateEmail(value)
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
                        Text("Aby zresetować hasło, wyloguj się i wybierz opcję resetowania hasła na ekranie logowania.")
                    },
                    confirmButton = {
                        TextButton(onClick = { dialog = null }) { Text("OK") }
                    }
                )
            }

            "about" -> {
                AlertDialog(
                    onDismissRequest = { dialog = null },
                    title = { Text("🛒 Lista Zakupów") },
                    text = {
                        Text(
                            "Wersja 2.0\n\n" +
                                    "Aplikacja do tworzenia i organizowania list zakupowych, zarządzania sklepami oraz synchronizacji danych.\n\n" +
                                    "Firebase Firestore"
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { dialog = null }) { Text("OK") }
                    }
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            content = content
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
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(option.first) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (option.first == selected) "●" else "○",
                            modifier = Modifier.width(28.dp)
                        )
                        Text(option.second)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Anuluj")
            }
        }
    )
}