package com.example.listazakupow

import android.content.Context
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.content.edit
import android.os.Bundle
import android.os.Looper
import android.os.Handler
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
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import coil.compose.AsyncImage
import com.example.listazakupow.components.UserHeader
import com.example.listazakupow.ui.theme.ListaZakupowTheme
import com.google.firebase.firestore.*
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
// KONFIGURACJA I STAŁE
// =============================================================

private const val UPDATE_PREFS = "lista_zakupow_updates"
private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
private const val UPDATE_CHECK_INTERVAL = 24L * 60L * 60L * 1000L
private const val CHANNEL_UPDATES = "updates"
private const val CHANNEL_ACTIVITY = "list_activity"
private const val INVITE_VALIDITY_MS = 24L * 60L * 60L * 1000L
private const val INVITE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
private const val INVITE_BASE_URL = "https://mythoria.pl/invite"

private data class GitHubRelease(val versionCode: Int?, val versionName: String, val downloadUrl: String?)

private fun parseVersionParts(version: String): List<Int>? {
    val clean = version.trim().removePrefix("v").removePrefix("V")
    if (!Regex("^\\d+(?:\\.\\d+)*$").matches(clean)) return null
    return clean.split(".").mapNotNull { it.toIntOrNull() }.takeIf { it.isNotEmpty() }
}

private fun compareVersions(a: String, b: String): Int {
    val pa = parseVersionParts(a) ?: return 0
    val pb = parseVersionParts(b) ?: return 0
    val size = maxOf(pa.size, pb.size)
    for (i in 0 until size) {
        val av = pa.getOrElse(i) { 0 }; val bv = pb.getOrElse(i) { 0 }
        if (av != bv) return av.compareTo(bv)
    }
    return 0
}

private fun isReleaseNewer(release: GitHubRelease): Boolean {
    val local = BuildConfig.VERSION_NAME
    val remote = release.versionName
    val lp = parseVersionParts(local); val rp = parseVersionParts(remote)
    if (lp != null && rp != null && (lp.size > 1 || rp.size > 1)) return compareVersions(remote, local) > 0
    return release.versionCode != null && release.versionCode > BuildConfig.VERSION_CODE
}

private fun generateInviteCode(): String {
    val random = SecureRandom()
    return buildString(8) { repeat(8) { append(INVITE_ALPHABET[random.nextInt(INVITE_ALPHABET.length)]) } }
}

private fun inviteUri(code: String): Uri = "$INVITE_BASE_URL/$code".toUri()

private fun extractInviteId(uri: Uri?): String? {
    if (uri == null) return null
    return if (uri.host == "mythoria.pl" && uri.pathSegments.firstOrNull() == "invite") uri.pathSegments.getOrNull(1)
    else if (uri.scheme == "listazakupow") uri.pathSegments.firstOrNull() else null
}

private fun checkGitHubLatestRelease(onResult: (GitHubRelease?) -> Unit) {
    Thread {
        var conn: HttpURLConnection? = null
        var result: GitHubRelease? = null
        try {
            conn = URL("https://api.github.com/repos/janczesko12/ListaZakupow/releases/latest").openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000; conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "ListaZakupow-Android")
            if (conn.responseCode !in 200..299) return@Thread
            val res = conn.inputStream.bufferedReader().use { it.readText() }
            val tag = Regex("""\"tag_name\"\s*:\s*\"([^\"]+)\"""")
                .find(res)?.groupValues?.get(1) ?: return@Thread
            val cleanTag = tag.removePrefix("v").removePrefix("V")
            val versionCode = if (Regex("^\\d+$").matches(cleanTag)) cleanTag.toIntOrNull() else null
            val apk = Regex(
                """\"browser_download_url\"\s*:\s*\"([^\"]+\.apk(?:\?[^\"]*)?)\"""",
                RegexOption.IGNORE_CASE
            ).find(res)?.groupValues?.get(1)
            result = GitHubRelease(versionCode, cleanTag, apk)
        } catch (_: Exception) { result = null } finally { conn?.disconnect() }
        Handler(Looper.getMainLooper()).post { onResult(result) }
    }.start()
}

private fun downloadAndInstallUpdate(c: Context, url: String) {
    try {
        val req = DownloadManager.Request(url.toUri()).setTitle("Aktualizacja").setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "ListaZakupow.apk").setMimeType("application/vnd.android.package-archive")
        val dm = c.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(req)
        Toast.makeText(c, "Pobieranie...", Toast.LENGTH_SHORT).show()
        val recv = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) == id) {
                    val uri = dm.getUriForDownloadedFile(id) ?: return
                    ctx.startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, "application/vnd.android.package-archive"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION) })
                }
            }
        }
        ContextCompat.registerReceiver(c, recv, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_NOT_EXPORTED)
    } catch (_: Exception) {}
}

object NotificationHelper {
    fun createChannels(c: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val m = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            m.createNotificationChannel(NotificationChannel(CHANNEL_UPDATES, "Aktualizacje", NotificationManager.IMPORTANCE_DEFAULT))
            m.createNotificationChannel(NotificationChannel(CHANNEL_ACTIVITY, "Aktywność na liście", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }
    fun showUpdateNotification(c: Context, n: String) {
        if (!settingsPrefs(c).getBoolean(KEY_NOTIF_UPDATES, true)) return
        val b = NotificationCompat.Builder(c, CHANNEL_UPDATES).setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle("Nowa wersja!").setContentText("Wersja $n jest gotowa.").setAutoCancel(true)
        try { NotificationManagerCompat.from(c).notify(1001, b.build()) } catch (_: SecurityException) {}
    }
    fun showActivityNotification(c: Context, t: String, msg: String) {
        if (!settingsPrefs(c).getBoolean(KEY_NOTIF_CHANGES, true)) return
        val b = NotificationCompat.Builder(c, CHANNEL_ACTIVITY).setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle(t).setContentText(msg).setAutoCancel(true)
        try { NotificationManagerCompat.from(c).notify(System.currentTimeMillis().toInt(), b.build()) } catch (_: SecurityException) {}
    }
}

// =============================================================
// MAIN ACTIVITY
// =============================================================

class MainActivity : ComponentActivity() {
    private var incomingInviteId by mutableStateOf<String?>(null)
    private val launcher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingInviteId = extractInviteId(intent.data)
        enableEdgeToEdge()
        NotificationHelper.createChannels(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)

        setContent {
            var upd by remember { mutableStateOf<GitHubRelease?>(null) }
            val ctx = LocalContext.current
            LaunchedEffect(Unit) {
                val last = ctx.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_UPDATE_CHECK, 0L)
                if (System.currentTimeMillis() - last >= UPDATE_CHECK_INTERVAL) {
                    ctx.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE).edit { putLong(KEY_LAST_UPDATE_CHECK, System.currentTimeMillis()) }
                    checkGitHubLatestRelease { r -> if (r != null && isReleaseNewer(r)) { upd = r; NotificationHelper.showUpdateNotification(ctx, r.versionName) } }
                }
            }

            var hap by remember { mutableStateOf(getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE).getBoolean(KEY_HAPTICS, true)) }
            var thm by remember { mutableStateOf(getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE).getString(KEY_THEME, "system") ?: "system") }
            val isDark = when(thm) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }

            ListaZakupowTheme(darkTheme = isDark) {
                Scaffold(modifier = Modifier.fillMaxSize().hapticTapFeedback(hap)) { p ->
                    LoginScreen(Modifier.padding(p), { thm = it }, { hap = it }, incomingInviteId) { incomingInviteId = null }
                }
                upd?.let { r ->
                    AlertDialog(onDismissRequest = { upd = null }, title = { Text("Aktualizacja") }, text = { Text("Dostępna nowa wersja: ${r.versionName}") },
                        confirmButton = { TextButton(onClick = { upd = null; r.downloadUrl?.let { downloadAndInstallUpdate(ctx, it) } }) { Text("POBIERZ") } },
                        dismissButton = { TextButton(onClick = { upd = null }) { Text("PÓŹNIEJ") } })
                }
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); incomingInviteId = extractInviteId(intent.data) }
}

@Composable
private fun Modifier.hapticTapFeedback(enabled: Boolean): Modifier {
    val v = LocalView.current
    return this.pointerInput(enabled) {
        awaitEachGesture {
            val down = awaitFirstDown(false, PointerEventPass.Initial)
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (ch.changedToUp()) {
                    if (enabled && (ch.position - down.position).getDistance() <= 24f) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    break
                }
            }
        }
    }
}

// =============================================================
// LOGIN & GŁÓWNY EKRAN
// =============================================================

@Composable
fun LoginScreen(modifier: Modifier, onThemeChanged: (String) -> Unit, onHapticsChanged: (Boolean) -> Unit, incomingInviteId: String?, onInviteHandled: () -> Unit) {
    val db = remember { FirebaseFirestore.getInstance() }
    val auth = remember { FirebaseAuth.getInstance() }
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("lista_zakupow", Context.MODE_PRIVATE)

    var login by remember { mutableStateOf("") }; var pin by remember { mutableStateOf("") }
    var zalogowany by remember { mutableStateOf(auth.currentUser != null) }
    var emailKonta by remember { mutableStateOf("") }; var imie by remember { mutableStateOf(prefs.getString("imie", "") ?: "") }
    val currentImie by rememberUpdatedState(imie)

    var sharedOwnerId by remember { mutableStateOf(prefs.getString("shared_owner_id", "") ?: "") }
    var sharedOwnerName by remember { mutableStateOf(prefs.getString("shared_owner_name", "") ?: "") }

    var pokazZaproszenie by remember { mutableStateOf(false) }
    var inviteId by remember { mutableStateOf("") }; var inviteOwnerUid by remember { mutableStateOf("") }
    var inviteOwnerName by remember { mutableStateOf("") }; var inviteLoading by remember { mutableStateOf(false) }; var inviteError by remember { mutableStateOf<String?>(null) }

    var pUsun by remember { mutableStateOf<Produkt?>(null) }; var pEdytuj by remember { mutableStateOf<Produkt?>(null) }; var pSklep by remember { mutableStateOf<Produkt?>(null) }
    var wybranaZakladka by remember { mutableIntStateOf(0) }
    var wybranySklep by remember { mutableStateOf<String?>(null) }
    var wybranaKategoria by remember { mutableStateOf("wszystkie") }
    var wyszukiwanieProduktu by remember { mutableStateOf("") }
    val setPrefs = remember { ctx.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE) }
    var trybSortowania by remember { mutableStateOf(setPrefs.getString(KEY_DEFAULT_SORT, "reczna") ?: "reczna") }
    var pokazDialogSortowania by remember { mutableStateOf(false) }
    var pDodajSklep by remember { mutableStateOf(false) }; var sEdytuj by remember { mutableStateOf<Sklep?>(null) }; var sUsun by remember { mutableStateOf<Sklep?>(null) }
    var pokazWspoldzielenie by remember { mutableStateOf(false) }
    var przeciaganieSklepu by remember { mutableStateOf(false) }
    var pokazWyborListy by remember { mutableStateOf(false) }
    var nowyProduktDlaDialogu by remember { mutableStateOf("") }

    DisposableEffect(zalogowany) {
        val u = auth.currentUser
        if (zalogowany && u != null) {
            val reg = db.collection("users").document(u.uid).addSnapshotListener { d, _ ->
                if (d != null && d.exists()) {
                    val newSharedId = d.getString("sharedOwnerId") ?: ""
                    val newSharedName = d.getString("sharedOwnerName") ?: ""

                    // POWIADOMIENIE: Jeśli właśnie zostaliśmy dodani do listy
                    if (sharedOwnerId.isEmpty() && newSharedId.isNotEmpty()) {
                        NotificationHelper.showActivityNotification(ctx, "Nowa lista!", "Dodano cię do listy! Kliknij aby sprawdzić!")
                    }

                    imie = d.getString("imie") ?: d.getString("login") ?: ""
                    emailKonta = d.getString("email") ?: ""
                    sharedOwnerId = newSharedId
                    sharedOwnerName = newSharedName
                    prefs.edit {
                        putString("shared_owner_id", newSharedId)
                        putString("shared_owner_name", newSharedName)
                        putString("imie", imie)
                    }
                }
            }
            onDispose { reg.remove() }
        } else {
            onDispose {}
        }
    }

    // Samoczynne odpinanie listy u gościa, gdy właściciel usunie zaproszenie
    DisposableEffect(zalogowany, sharedOwnerId) {
        val u = auth.currentUser
        if (zalogowany && u != null && sharedOwnerId.isNotEmpty()) {
            val reg = db.collection("invitations")
                .whereEqualTo("ownerUid", sharedOwnerId)
                .whereEqualTo("acceptedBy", u.uid)
                .addSnapshotListener { res, _ ->
                    if (res != null) {
                        val active = res.documents.any { it.getString("status") == "accepted" }
                        if (!active) {
                            // Brak aktywnych zaproszeń od tego konkretnego właściciela -> Czyścimy dostęp
                            db.collection("users").document(u.uid).update(
                                "sharedOwnerId", "",
                                "sharedOwnerName", ""
                            ).addOnSuccessListener {
                                // Dodatkowe czyszczenie lokalne na wypadek gdyby users listener był wolny
                                sharedOwnerId = ""
                                sharedOwnerName = ""
                                prefs.edit {
                                    putString("shared_owner_id", "")
                                    putString("shared_owner_name", "")
                                }
                            }
                        }
                    }
                }
            onDispose { reg.remove() }
        } else {
            onDispose {}
        }
    }

    LaunchedEffect(incomingInviteId, zalogowany) {
        val inc = incomingInviteId ?: return@LaunchedEffect
        if (!zalogowany) return@LaunchedEffect
        inviteId = inc; inviteLoading = true; inviteError = null
        db.collection("invitations").document(inc).get()
            .addOnSuccessListener { d ->
                if (!d.exists()) {
                    inviteError = "Błędny lub nieistniejący link."
                } else {
                    val owner = d.getString("ownerUid") ?: ""
                    val status = d.getString("status") ?: ""
                    val expiresAt = d.getLong("expiresAt") ?: 0L
                    when {
                        owner.isBlank() -> inviteError = "Zaproszenie jest uszkodzone."
                        owner == auth.currentUser?.uid -> inviteError = "To Twoja lista."
                        status != "pending" -> inviteError = "To zaproszenie zostało już wykorzystane."
                        expiresAt > 0L && expiresAt <= System.currentTimeMillis() -> inviteError = "To zaproszenie wygasło."
                        else -> {
                            inviteOwnerUid = owner
                            inviteOwnerName = d.getString("ownerName")?.trim().takeUnless { it.isNullOrBlank() } ?: "Użytkownik"
                        }
                    }
                }
                inviteLoading = false
                pokazZaproszenie = true
                onInviteHandled()
            }
            .addOnFailureListener { e ->
                inviteLoading = false
                inviteError = "Nie udało się pobrać zaproszenia: ${e.message ?: "błąd Firestore"}"
                pokazZaproszenie = true
                onInviteHandled()
                android.util.Log.e("INVITE", "Błąd pobierania invitations/$inc", e)
            }
    }

    val lista = remember { mutableStateListOf<Produkt>() }
    val posortowanaListaLocal = remember { mutableStateListOf<Produkt>() }
    val sklepy = remember { mutableStateListOf<Sklep>() }
    val posortowaneSklepyLocal = remember { mutableStateListOf<Sklep>() }

    val effectiveOwnerId = sharedOwnerId.ifEmpty { auth.currentUser?.uid ?: "" }

    // Automatyczne usuwanie kupionych produktów według czasu ustawionego w Ustawieniach.
    // Sprawdzamy okresowo, aby zmiana czasu obowiązywała również bez restartu aplikacji.
    LaunchedEffect(zalogowany, effectiveOwnerId) {
        while (isActive) {
            if (zalogowany && effectiveOwnerId.isNotEmpty()) {
                val autoDelete = setPrefs.getBoolean(KEY_AUTO_DELETE, true)
                val seconds = setPrefs.getInt(KEY_DELETE_SECONDS, 1200).coerceAtLeast(1)
                if (autoDelete) {
                    val limit = System.currentTimeMillis() - seconds * 1_000L
                    lista.filter { it.kupione && it.kupioneOd > 0L && it.kupioneOd <= limit }.forEach { produkt ->
                        db.collection("shoppingLists").document(produkt.id).delete()
                    }
                }
            }
            // Sprawdzamy co sekundę, aby działały również bardzo krótkie czasy, np. 5 sekund.
            delay(1_000L)
        }
    }

    DisposableEffect(zalogowany, effectiveOwnerId) {
        if (!zalogowany || effectiveOwnerId.isEmpty()) {
            lista.clear()
            onDispose {}
        } else {
            val currentUid = auth.currentUser?.uid ?: ""
            val mode = if (sharedOwnerId.isEmpty()) "TRYB WŁAŚCICIELA" else "TRYB UDOSTĘPNIONEJ LISTY"
            android.util.Log.d("PRODUCT_DEBUG", "Pobieranie produktów: currentUid=$currentUid, sharedOwnerId=$sharedOwnerId, queryOwnerUid=$effectiveOwnerId, $mode")

            var initial = true
            val reg = db.collection("shoppingLists").whereEqualTo("userId", effectiveOwnerId).addSnapshotListener { res, err ->
                if (err != null) {
                    android.util.Log.e("PRODUCT_DEBUG", "Błąd pobierania produktów", err)
                    return@addSnapshotListener
                }
                if (res == null) return@addSnapshotListener

                android.util.Log.d("PRODUCT_DEBUG", "Pobrano produkty: liczba znalezionych produktów=${res.size()}")

                if (!initial) res.documentChanges.forEach { c ->
                    val p = c.document.toObject(Produkt::class.java).copy(id = c.document.id)
                    if (p.dodal != currentImie) when(c.type) {
                        DocumentChange.Type.ADDED -> NotificationHelper.showActivityNotification(ctx, "Nowy produkt!", "${p.dodal} dodał ${p.nazwa}")
                        DocumentChange.Type.MODIFIED -> NotificationHelper.showActivityNotification(ctx, "Zmiana", "${p.dodal} edytował ${p.nazwa}")
                        DocumentChange.Type.REMOVED -> NotificationHelper.showActivityNotification(ctx, "Usunięto", "Usunięto ${p.nazwa}")
                    }
                }
                initial = false
                lista.clear()
                lista.addAll(res.documents.mapNotNull { it.toObject(Produkt::class.java)?.copy(id = it.id) })
            }
            onDispose { reg.remove() }
        }
    }

    DisposableEffect(zalogowany, effectiveOwnerId) {
        if (!zalogowany || effectiveOwnerId.isEmpty()) {
            sklepy.clear()
            onDispose {}
        } else {
            val currentUid = auth.currentUser?.uid ?: ""
            val mode = if (sharedOwnerId.isEmpty()) "TRYB WŁAŚCICIELA" else "TRYB UDOSTĘPNIONEJ LISTY"
            android.util.Log.d("STORE_DEBUG", "Pobieranie sklepów: currentUid=$currentUid, sharedOwnerId=$sharedOwnerId, queryOwnerUid=$effectiveOwnerId, $mode")

            val reg = db.collection("sklepy").whereEqualTo("userId", effectiveOwnerId).addSnapshotListener { res, err ->
                if (err != null) {
                    android.util.Log.e("STORE_DEBUG", "Błąd pobierania sklepów", err)
                    return@addSnapshotListener
                }
                if (res != null) {
                    android.util.Log.d("STORE_DEBUG", "Pobrano sklepy: liczba znalezionych sklepów=${res.size()}")
                    val merged = res.documents.mapNotNull { it.toObject(Sklep::class.java)?.copy(id = it.id) }
                    sklepy.clear()
                    sklepy.addAll(merged.sortedBy { it.kolejnosc })
                }
            }
            onDispose { reg.remove() }
        }
    }

    LaunchedEffect(sklepy.toList(), przeciaganieSklepu) { if (!przeciaganieSklepu) { posortowaneSklepyLocal.clear(); posortowaneSklepyLocal.addAll(sklepy) } }

    LaunchedEffect(lista.toList(), wybranaKategoria, wyszukiwanieProduktu, trybSortowania) {
        val fraza = wyszukiwanieProduktu.lowercase()
        val filtered = lista.filter { (wybranaKategoria == "wszystkie" || it.kategoria == wybranaKategoria) && it.nazwa.lowercase().contains(fraza) }
        posortowanaListaLocal.clear()
        posortowanaListaLocal.addAll(when(trybSortowania) {
            "az" -> filtered.sortedBy { it.nazwa.lowercase() }
            "za" -> filtered.sortedByDescending { it.nazwa.lowercase() }
            "dokupienia" -> filtered.sortedWith(compareBy<Produkt> { it.kupione }.thenBy { it.nazwa.lowercase() })
            "kupione" -> filtered.sortedWith(compareByDescending<Produkt> { it.kupione }.thenBy { it.nazwa.lowercase() })
            else -> filtered.sortedBy { it.kolejnosc }
        })
    }

    if (!zalogowany) {
        var isReg by remember { mutableStateOf(false) }
        var trw by remember { mutableStateOf(false) }; var err by remember { mutableStateOf<String?>(null) }
        var rImie by remember { mutableStateOf("") }; var rLog by remember { mutableStateOf("") }; var rMail by remember { mutableStateOf("") }; var rPass by remember { mutableStateOf("") }
        var passVisible by remember { mutableStateOf(false) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(shape = RoundedCornerShape(28.dp), elevation = CardDefaults.cardElevation(6.dp)) {
                Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🛒", style = MaterialTheme.typography.displaySmall)
                    Text(if(isReg) "Rejestracja" else "Zaloguj się", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(16.dp))
                    if(isReg) {
                        OutlinedTextField(value = rImie, onValueChange = { rImie = it; rLog = it.lowercase().replace(" ", "") }, label = { Text("Imię") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = rLog, onValueChange = { rLog = it }, label = { Text("Login") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = rMail, onValueChange = { rMail = it }, label = { Text("E-mail") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(
                            value = rPass,
                            onValueChange = { rPass = it },
                            label = { Text("Hasło") },
                            visualTransformation = if (passVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { passVisible = !passVisible }) {
                                    Text(if (passVisible) "👁️" else "🙈")
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        OutlinedTextField(value = login, onValueChange = { login = it }, label = { Text("Login") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(
                            value = pin,
                            onValueChange = { pin = it },
                            label = { Text("Hasło") },
                            visualTransformation = if (passVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { passVisible = !passVisible }) {
                                    Text(if (passVisible) "👁️" else "🙈")
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                            TextButton(
                                contentPadding = PaddingValues(0.dp),
                                onClick = {
                                    val cleanLogin = login.trim().lowercase()
                                    if (cleanLogin.isEmpty()) { err = "Wpisz login, aby zresetować hasło"; return@TextButton }
                                    trw = true; err = null
                                    db.collection("loginLookup").document(cleanLogin).get().addOnSuccessListener { d ->
                                        if (!d.exists()) { trw = false; err = "Brak loginu: $cleanLogin" }
                                        else {
                                            val email = d.getString("email") ?: ""
                                            if (email.isEmpty()) { trw = false; err = "Błąd konta" }
                                            else auth.sendPasswordResetEmail(email).addOnSuccessListener {
                                                trw = false; Toast.makeText(ctx, "Link do resetu wysłany na e-mail", Toast.LENGTH_LONG).show()
                                            }.addOnFailureListener { trw = false; err = "Błąd wysyłania" }
                                        }
                                    }.addOnFailureListener { e ->
                                        trw = false
                                        err = "Błąd bazy: ${e.message ?: "nieznany błąd"}"
                                        android.util.Log.e("FIRESTORE_LOGIN", "Błąd loginLookup/$cleanLogin", e)
                                    }
                                }
                            ) {
                                Text("Zapomniałeś hasła?", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    err?.let { Text(it, color = Color.Red, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
                    Button(onClick = {
                        trw = true; err = null
                        if(isReg) {
                            db.collection("loginLookup").document(rLog).get().addOnSuccessListener { d ->
                                if(d.exists()) { trw = false; err = "Login zajęty" }
                                else auth.createUserWithEmailAndPassword(rMail, rPass).addOnSuccessListener { res ->
                                    val uid = res.user!!.uid
                                    val data = mapOf("uid" to uid, "imie" to rImie, "login" to rLog, "email" to rMail, "sharedOwnerId" to "", "sharedOwnerName" to "")
                                    db.collection("users").document(uid)
                                        .set(data)
                                        .addOnSuccessListener {
                                            db.collection("loginLookup")
                                                .document(rLog.trim().lowercase())
                                                .set(
                                                    mapOf(
                                                        "uid" to uid,
                                                        "email" to rMail.trim().lowercase()
                                                    )
                                                )
                                                .addOnSuccessListener {
                                                    trw = false
                                                    zalogowany = true
                                                }
                                                .addOnFailureListener { e ->
                                                    trw = false
                                                    err = "Błąd zapisu loginu: ${e.message ?: "nieznany błąd"}"
                                                    android.util.Log.e("FIRESTORE_REGISTER_LOGIN", "Błąd loginLookup", e)
                                                }
                                        }
                                        .addOnFailureListener { e ->
                                            trw = false
                                            err = "Błąd zapisu konta: ${e.message ?: "nieznany błąd"}"
                                            android.util.Log.e("FIRESTORE_REGISTER_USER", "Błąd users/$uid", e)
                                        }
                                }.addOnFailureListener { trw = false; err = it.message }
                            }
                        } else {
                            val cleanLogin = login.trim().lowercase()
                            if (cleanLogin.isEmpty() || pin.isEmpty()) { err = "Wpisz dane"; trw = false; return@Button }
                            db.collection("loginLookup").document(cleanLogin).get().addOnSuccessListener { d ->
                                if(!d.exists()) { trw = false; err = "Brak loginu: $cleanLogin" }
                                else {
                                    val email = d.getString("email")?.trim()?.lowercase() ?: ""
                                    if (email.isEmpty()) { trw = false; err = "Błąd konta" }
                                    else auth.signInWithEmailAndPassword(email, pin).addOnSuccessListener { zalogowany = true }.addOnFailureListener { trw = false; err = "Błędne dane" }
                                }
                            }.addOnFailureListener { e ->
                                trw = false
                                err = "Błąd bazy: ${e.message ?: "nieznany błąd"}"
                                android.util.Log.e("FIRESTORE_RESET", "Błąd loginLookup/$cleanLogin", e)
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { if(trw) CircularProgressIndicator(Modifier.size(20.dp)) else Text(if(isReg) "Utwórz konto" else "Zaloguj się") }
                    TextButton(
                        contentPadding = PaddingValues(0.dp),
                        onClick = { isReg = !isReg; err = null }
                    ) { Text(if(isReg) "Masz konto? Zaloguj się" else "Nie masz konta? Zarejestruj się") }
                }
            }
        }
        return
    }

    Box(modifier.fillMaxSize()) {
        // Zawartość znajduje się pod dolnym paskiem.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset(y = (-12).dp)
                .clipToBounds()
                .zIndex(0f)
        ) {
            AnimatedContent(targetState = wybranaZakladka, label = "tabs") { zak ->
                when(zak) {
                    0 -> Column(
                        Modifier
                            .fillMaxSize()
                            .offset(y = (-29).dp)
                            .background(MaterialTheme.colorScheme.background)
                            .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        var pokazGorneMenu by remember { mutableStateOf(true) }
                        if (pokazGorneMenu) {
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(text = "🛒 Lista zakupów", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                IconButton(onClick = { pokazGorneMenu = false }) {
                                    Text("✓", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            UserHeader(imie, lista.size) { auth.signOut(); zalogowany = false; prefs.edit { clear() } }
                        } else {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Dodawanie produktów", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                IconButton(onClick = { pokazGorneMenu = true }) {
                                    Text("⌄", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        var nPr by remember { mutableStateOf("") }
                        if (pokazGorneMenu) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = nPr,
                                    onValueChange = { nPr = it },
                                    label = { Text("🛍️ Produkt") },
                                    modifier = Modifier.weight(1f).height(64.dp),
                                    singleLine = true,
                                    shape = RoundedCornerShape(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    modifier = Modifier.height(64.dp),
                                    shape = RoundedCornerShape(18.dp),
                                    onClick = {
                                        if(nPr.isNotBlank()) {
                                            if (wybranaKategoria != "wszystkie") {
                                                dodajProduktDoListy(ctx, nPr, imie, wybranaKategoria)
                                                nPr = ""
                                            } else {
                                                nowyProduktDlaDialogu = nPr
                                                pokazWyborListy = true
                                            }
                                        }
                                    }
                                ) { Text("＋") }
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = wyszukiwanieProduktu,
                                    onValueChange = { wyszukiwanieProduktu = it },
                                    label = { Text("🔍 Szukaj...") },
                                    modifier = Modifier.weight(1f).height(60.dp),
                                    singleLine = true,
                                    shape = RoundedCornerShape(16.dp),
                                    textStyle = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    onClick = { pokazDialogSortowania = true },
                                    modifier = Modifier.height(60.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                ) {
                                    Text("⋮", style = MaterialTheme.typography.titleLarge)
                                }
                            }
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterButton("Wszystkie", wybranaKategoria == "wszystkie") { wybranaKategoria = "wszystkie" }
                                sklepy.forEach { s -> FilterButton("${s.emoji} ${s.nazwa}", wybranaKategoria == s.id) { wybranaKategoria = s.id } }
                            }
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            if(posortowanaListaLocal.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.BottomCenter) { EmptyShoppingImage() }
                            else ProductDragList(posortowanaListaLocal, trybSortowania == "reczna", { f, t -> val itm = posortowanaListaLocal.removeAt(f); posortowanaListaLocal.add(t, itm) }, { zapiszNowaKolejnosc(posortowanaListaLocal) }, { p, c -> db.collection("shoppingLists").document(p.id).update(mapOf("kupione" to c, "kupioneOd" to if(c) System.currentTimeMillis() else 0L)) }, { pUsun = it }, { pEdytuj = it }, sklepy, { pSklep = it })
                        }
                    }
                    1 -> if(wybranySklep == null) SklepyScreen(posortowaneSklepyLocal, { wybranySklep = it }, { pDodajSklep = true }, { sEdytuj = it }, { sUsun = it }, { przeciaganieSklepu = true }, { przeciaganieSklepu = false; zapiszNowaKolejnoscSklepow(posortowaneSklepyLocal) }, { f, t -> val itm = posortowaneSklepyLocal.removeAt(f); posortowaneSklepyLocal.add(t, itm) })
                    else ListaSklepuScreen(wybranySklep!!, sklepy.find { it.id == wybranySklep }, lista, imie, { wybranySklep = null }, { pUsun = it }, { pEdytuj = it }, { pSklep = it })
                    2 -> UstawieniaScreen(
                        emailKonta,
                        { emailKonta = it },
                        onThemeChanged,
                        onHapticsChanged,
                        { pokazWspoldzielenie = true },
                        { auth.signOut(); zalogowany = false; prefs.edit { clear() } },
                        onSortChanged = { trybSortowania = it }
                    )
                }
            }        }


        if (pokazWyborListy) {
            AlertDialog(
                onDismissRequest = { pokazWyborListy = false },
                title = { Text("Do której listy dodać?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ListaWyboruButton(emoji = "🏠", nazwa = "Główna", onClick = { dodajProduktDoListy(ctx, nowyProduktDlaDialogu, imie, "glowna"); nowyProduktDlaDialogu = ""; pokazWyborListy = false })
                        sklepy.forEach { sklep ->
                            ListaWyboruButton(emoji = sklep.emoji, nazwa = sklep.nazwa, sklep = sklep, onClick = { dodajProduktDoListy(ctx, nowyProduktDlaDialogu, imie, sklep.id); nowyProduktDlaDialogu = ""; pokazWyborListy = false })
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { pokazWyborListy = false }) { Text("ANULUJ") } }
            )
        }

        pSklep?.let { produkt ->
            AlertDialog(
                onDismissRequest = { pSklep = null },
                title = { Text("🏪 Przydziel do sklepu") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        sklepy.forEach { sklep ->
                            ListaWyboruButton(emoji = sklep.emoji, nazwa = sklep.nazwa, sklep = sklep, onClick = {
                                db.collection("shoppingLists").document(produkt.id).update("kategoria", sklep.id)
                                pSklep = null
                            })
                        }
                        TextButton(onClick = { db.collection("shoppingLists").document(produkt.id).update("kategoria", "glowna"); pSklep = null }) { Text("🏠 Żadna") }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { pSklep = null }) { Text("ANULUJ") } }
            )
        }

        if (pDodajSklep) DodajLubEdytujSklepDialog(null, { pDodajSklep = false }, { pDodajSklep = false })
        sEdytuj?.let { DodajLubEdytujSklepDialog(it, { sEdytuj = null }, { sEdytuj = null }) }
        sUsun?.let { s ->
            AlertDialog(onDismissRequest = { sUsun = null }, title = { Text("Usuń sklep") }, text = { Text("Usunąć ${s.nazwa}?") },
                confirmButton = { TextButton(onClick = { db.collection("sklepy").document(s.id).delete(); sUsun = null }) { Text("USUŃ", color = Color.Red) } },
                dismissButton = { TextButton(onClick = { sUsun = null }) { Text("ANULUJ") } })
        }
        pEdytuj?.let { p ->
            var n by remember { mutableStateOf(p.nazwa) }
            var k by remember { mutableStateOf(p.kategoria) }
            AlertDialog(onDismissRequest = { pEdytuj = null }, title = { Text("Edytuj") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = n, onValueChange = { n = it }, label = { Text("Nazwa produktu") })
                    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterButton(text = "🏠 Główna", selected = k == "glowna", onClick = { k = "glowna" })
                        sklepy.forEach { sklep -> FilterButton(text = "${sklep.emoji} ${sklep.nazwa}", selected = k == sklep.id, onClick = { k = sklep.id }) }
                    }
                }
            }, confirmButton = { TextButton(onClick = { if(n.isNotBlank()){db.collection("shoppingLists").document(p.id).update(mapOf("nazwa" to n, "kategoria" to k)); pEdytuj = null} }) { Text("ZAPISZ") } })
        }
        pUsun?.let { p ->
            AlertDialog(onDismissRequest = { pUsun = null }, title = { Text("Usuń") }, text = { Text(p.nazwa) },
                confirmButton = { TextButton(onClick = { db.collection("shoppingLists").document(p.id).delete(); pUsun = null }) { Text("USUŃ", color = Color.Red) } },
                dismissButton = { TextButton(onClick = { pUsun = null }) { Text("ANULUJ") } })
        }

        if (pokazWspoldzielenie) UdostepnijListeDialog(auth.currentUser?.uid ?: "", imie, { pokazWspoldzielenie = false }, { pokazWspoldzielenie = false })
        if (pokazZaproszenie) PotwierdzZaproszenieDialog(
            inviteId,
            inviteOwnerUid,
            inviteOwnerName,
            { id ->
                sharedOwnerId = id
                sharedOwnerName = inviteOwnerName
                prefs.edit {
                    putString("shared_owner_id", id)
                    putString("shared_owner_name", inviteOwnerName)
                }
                pokazZaproszenie = false
            },
            { pokazZaproszenie = false }
        )
        if (pokazDialogSortowania) ChoiceDialog("Sortowanie", listOf("reczna" to "Ręczna", "az" to "A-Z", "za" to "Z-A", "dokupienia" to "Do kupienia", "kupione" to "Kupione"), trybSortowania, { trybSortowania = it; pokazDialogSortowania = false }, { pokazDialogSortowania = false })

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .align(Alignment.BottomCenter)
                .offset(y = 10.dp)
                .zIndex(10f), shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(8.dp), Arrangement.SpaceEvenly, Alignment.CenterVertically) {
                DolnaNawigacjaItem("🛒", "Lista", wybranaZakladka == 0) { wybranaZakladka = 0 }
                DolnaNawigacjaItem("🏪", "Sklepy", wybranaZakladka == 1) { wybranaZakladka = 1 }
                DolnaNawigacjaItem("⚙️", "Ustawienia", wybranaZakladka == 2) { wybranaZakladka = 2 }
            }
        }
    }
}

@Composable
fun RowScope.DolnaNawigacjaItem(icon: String, label: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
    Column(Modifier.weight(1f).clickable { onClick() }.background(bg, RoundedCornerShape(20.dp)).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(icon); Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun ProductDragList(produkty: List<Produkt>, canDrag: Boolean = true, onMove: (Int, Int) -> Unit, onDragEnd: () -> Unit, onToggle: (Produkt, Boolean) -> Unit, onDelete: (Produkt) -> Unit, onEdit: (Produkt) -> Unit, sklepy: List<Sklep>, onAssign: (Produkt) -> Unit) {
    val state = rememberLazyListState()
    var dragged by remember { mutableStateOf<Produkt?>(null) }
    var curIdx by remember { mutableStateOf<Int?>(null) }
    var viewY by remember { mutableFloatStateOf(0f) }; var offY by remember { mutableFloatStateOf(0f) }; var scroll by remember { mutableFloatStateOf(0f) }

    val checkSwap = {
        val i = curIdx
        if (i != null && dragged != null) {
            val center = viewY - offY + 40.dp.value
            val prev = state.layoutInfo.visibleItemsInfo.find { it.index == i - 1 }
            val next = state.layoutInfo.visibleItemsInfo.find { it.index == i + 1 }
            if (prev != null && center < prev.offset + prev.size / 2f) { onMove(i, i - 1); curIdx = i - 1 }
            else if (next != null && center > next.offset + next.size / 2f) { onMove(i, i + 1); curIdx = i + 1 }
        }
    }

    LaunchedEffect(scroll) { if(scroll != 0f) while(isActive) { state.scrollBy(scroll); delay(16.milliseconds) } }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state, userScrollEnabled = dragged == null) {
            items(produkty, { it.id }) { p ->
                Card(Modifier.fillMaxWidth().padding(bottom = 8.dp).graphicsLayer { alpha = if(p == dragged) 0f else 1f }.then(
                    if (canDrag) Modifier.pointerInput(p.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { o -> val i = produkty.indexOf(p); if(i >= 0) { val v = state.layoutInfo.visibleItemsInfo.find { it.key == p.id }; if(v != null) { offY = o.y; viewY = v.offset + o.y; curIdx = i; dragged = p } } },
                            onDrag = { c, d -> c.consume(); viewY += d.y; scroll = if(viewY < 100) -15f else if(viewY > 800) 15f else 0f; checkSwap() },
                            onDragEnd = { onDragEnd(); dragged = null; curIdx = null; scroll = 0f },
                            onDragCancel = { dragged = null; curIdx = null; scroll = 0f }
                        )
                    } else Modifier
                )) { ProductCardContent(p, onToggle, onDelete, onEdit, sklepy.find { it.id == p.kategoria }?.nazwa, onAssign) }
            }
        }
        if(dragged != null) Card(Modifier.fillMaxWidth().offset { IntOffset(0, (viewY - offY).roundToInt()) }.scale(1.05f)) { ProductCardContent(dragged!!, onToggle, onDelete, onEdit, sklepy.find { it.id == dragged!!.kategoria }?.nazwa) }
    }
}

@Composable
fun SklepCardContent(s: Sklep, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        SklepIcon(s, Modifier.size(40.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(s.nazwa, style = MaterialTheme.typography.titleMedium); Text("Lista zakupów", style = MaterialTheme.typography.labelSmall) }
        IconButton(onClick = onEdit) { Text("✏️") }; IconButton(onClick = onDelete) { Text("🗑️") }
    }
}

@Composable
fun SklepyScreen(sklepy: List<Sklep>, onClick: (String) -> Unit, onAdd: () -> Unit, onEdit: (Sklep) -> Unit, onDelete: (Sklep) -> Unit, onStart: () -> Unit, onEnd: () -> Unit, onMove: (Int, Int) -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp).padding(bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("🏪 Sklepy", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Button(onClick = onAdd) { Text("+ Sklep") }
        }
        Spacer(Modifier.height(16.dp))
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(sklepy, { it.id }) { s ->
                Card(Modifier.fillMaxWidth().clickable { onClick(s.id) }.pointerInput(s.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onStart() },
                        onDragEnd = { onEnd() },
                        onDragCancel = { onEnd() },
                        onDrag = { c, d -> c.consume() }
                    )
                }) { SklepCardContent(s, { onEdit(s) }, { onDelete(s) }) }
            }
        }
    }
}

@Composable
fun ListaSklepuScreen(id: String, s: Sklep?, lista: List<Produkt>, imie: String, onBack: () -> Unit, onDelete: (Produkt) -> Unit, onEdit: (Produkt) -> Unit, onAssign: (Produkt) -> Unit) {
    val ctx = LocalContext.current
    val prods = lista.filter { it.kategoria == id }
    Column(Modifier.fillMaxSize().padding(24.dp).padding(bottom = 8.dp)) {
        TextButton(onClick = onBack) { Text("← Wróć") }
        Text(s?.nazwa ?: id, style = MaterialTheme.typography.headlineSmall)
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            var n by remember { mutableStateOf("") }
            OutlinedTextField(value = n, onValueChange = { n = it }, label = { Text("Produkt") }, modifier = Modifier.weight(1f))
            Button(onClick = { if(n.isNotBlank()) { dodajProduktDoListy(ctx, n, imie, id); n = "" } }) { Text("+") }
        }
        LazyColumn(Modifier.weight(1f).padding(top = 16.dp)) {
            items(prods, { it.id }) { p -> Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) { ProductCardContent(p, { prod, c -> FirebaseFirestore.getInstance().collection("shoppingLists").document(prod.id).update("kupione", c, "kupioneOd", if(c) System.currentTimeMillis() else 0L) }, onDelete, onEdit, s?.nazwa, onAssign) } }
        }
    }
}

@Composable
fun ProductCardContent(p: Produkt, onToggle: (Produkt, Boolean) -> Unit, onDelete: (Produkt) -> Unit, onEdit: (Produkt) -> Unit, shopName: String? = null, onAssign: ((Produkt) -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(modifier = Modifier.scale(0.78f), checked = p.kupione, onCheckedChange = { onToggle(p, it) })
        Column(Modifier.weight(1f).padding(start = 4.dp, end = 4.dp)) {
            Text(p.nazwa, style = MaterialTheme.typography.bodyMedium, softWrap = true, maxLines = 4, textDecoration = if(p.kupione) TextDecoration.LineThrough else TextDecoration.None)
            Text("Dodał: ${p.dodal}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (shopName?.isNotBlank() == true) "Sklep: $shopName" else "Sklep: Żadna", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        onAssign?.let { IconButton(onClick = { it(p) }, modifier = Modifier.size(30.dp)) { Text("🏪", fontSize = 15.sp) } }
        Box(modifier = Modifier.size(36.dp).clickable { onEdit(p) }, contentAlignment = Alignment.Center) { Text("✏️") }
        Box(modifier = Modifier.size(36.dp).clickable { onDelete(p) }, contentAlignment = Alignment.Center) { Text("🗑️") }
        Text(text = "⋮⋮", modifier = Modifier.padding(start = 2.dp))
    }
}

@Composable
fun ListaWyboruButton(emoji: String, nazwa: String, sklep: Sklep? = null, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (sklep != null) { SklepIcon(sklep = sklep, modifier = Modifier.height(42.dp).fillMaxWidth(0.15f)) }
            else { Text(text = emoji, style = MaterialTheme.typography.headlineSmall) }
            Text(text = nazwa, modifier = Modifier.padding(start = 14.dp), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable fun FilterButton(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
    val content by animateColorAsState(if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
    val scale by animateFloatAsState(if (selected) 1.03f else 1f)
    Card(onClick = onClick, modifier = Modifier.scale(scale).height(20.dp), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = bg)) {
        Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Text(if (selected) "✓ $text" else text, color = content, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable fun UdostepnijListeDialog(ownerUid: String, ownerName: String, onSent: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val db = FirebaseFirestore.getInstance()
    val auth = FirebaseAuth.getInstance()
    var name by remember { mutableStateOf("") }
    var load by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!load) onDismiss() },
        title = { Text("Udostępnij") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nazwa osoby") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                error?.let { Text("❌ $it", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(enabled = !load, onClick = {
                val personName = name.trim()
                val uid = auth.currentUser?.uid
                if (personName.isBlank()) { error = "Wpisz nazwę osoby."; return@Button }
                if (uid.isNullOrBlank()) { error = "Musisz być zalogowany."; return@Button }

                load = true
                error = null
                val code = generateInviteCode()
                val now = System.currentTimeMillis()
                val inviteRef = db.collection("invitations").document(code)
                val ownerRef = db.collection("users").document(uid)
                val inviteData = hashMapOf<String, Any>(
                    "ownerUid" to uid,
                    "ownerName" to ownerName.trim().ifBlank { "Użytkownik" },
                    "personName" to personName,
                    "listName" to "Lista Zakupów",
                    "status" to "pending",
                    "createdAt" to now,
                    "expiresAt" to now + INVITE_VALIDITY_MS
                )

                db.runBatch { batch ->
                    batch.set(inviteRef, inviteData)
                    batch.set(ownerRef, mapOf("sharedWithNames.$code" to personName), SetOptions.merge())
                }.addOnSuccessListener {
                    ctx.getSharedPreferences("lista_zakupow", Context.MODE_PRIVATE)
                        .edit { putString("invite_name_$code", personName) }
                    val link = inviteUri(code).toString()
                    try {
                        ctx.startActivity(Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "🤝 Dołącz do mojej wspólnej listy zakupów w aplikacji Lista Zakupów:\n\n$link\n\nLink wygaśnie za 24 godziny.")
                            }, "Wyślij zaproszenie"))
                        load = false
                        onSent()
                    } catch (e: Exception) {
                        load = false
                        error = "Nie udało się otworzyć udostępniania."
                        android.util.Log.e("INVITE", "Share Sheet error", e)
                    }
                }.addOnFailureListener { e ->
                    load = false
                    error = "Nie udało się utworzyć zaproszenia: ${e.message ?: "błąd Firestore"}"
                    android.util.Log.e("INVITE", "Błąd tworzenia invitations/$code", e)
                }
            }) {
                if (load) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("WYŚLIJ")
            }
        },
        dismissButton = { TextButton(enabled = !load, onClick = onDismiss) { Text("ANULUJ") } }
    )
}

@Composable
fun PotwierdzZaproszenieDialog(
    inviteId: String,
    ownerUid: String,
    ownerName: String,
    onAccepted: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val db = FirebaseFirestore.getInstance()
    val auth = FirebaseAuth.getInstance()
    val ctx = LocalContext.current
    var work by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!work) onDismiss() },
        title = { Text("Dołącz") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$ownerName zaprasza Cię do wspólnej listy.")
                error?.let { Text("❌ $it", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(enabled = !work, onClick = {
                val uid = auth.currentUser?.uid
                if (uid.isNullOrBlank()) { error = "Musisz być zalogowany."; return@Button }
                if (ownerUid.isBlank() || inviteId.isBlank()) { error = "Zaproszenie jest nieprawidłowe."; return@Button }
                if (uid == ownerUid) { error = "Nie możesz dołączyć do własnej listy."; return@Button }

                work = true
                error = null
                val inviteRef = db.collection("invitations").document(inviteId)
                val memberRef = db.collection("users").document(uid)

                db.runBatch { batch ->
                    batch.set(memberRef, mapOf("sharedOwnerId" to ownerUid, "sharedOwnerName" to ownerName), SetOptions.merge())
                    batch.update(inviteRef, mapOf(
                        "status" to "accepted",
                        "acceptedBy" to uid,
                        "acceptedAt" to System.currentTimeMillis()
                    ))
                }.addOnSuccessListener {
                    ctx.getSharedPreferences("lista_zakupow", Context.MODE_PRIVATE).edit {
                        putString("shared_owner_id", ownerUid)
                        putString("shared_owner_name", ownerName)
                    }
                    android.util.Log.d("INVITE_ACCEPT", "OK invite=$inviteId member=$uid owner=$ownerUid")
                    work = false
                    onAccepted(ownerUid)
                }.addOnFailureListener { e ->
                    work = false
                    error = "Nie udało się dołączyć: ${e.message ?: "błąd Firestore"}"
                    android.util.Log.e("INVITE_ACCEPT", "Błąd akceptacji invitations/$inviteId", e)
                }
            }) {
                if (work) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("DOŁĄCZ DO LISTY")
            }
        },
        dismissButton = { TextButton(enabled = !work, onClick = onDismiss) { Text("ODRZUĆ") } }
    )
}

@Composable
fun UstawieniaScreen(currentEmail: String, onEmailChanged: (String) -> Unit, onThemeChanged: (String) -> Unit, onHapticsChanged: (Boolean) -> Unit, onShareList: () -> Unit, onLogout: () -> Unit, onSortChanged: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { settingsPrefs(context) }
    var autoDelete by remember { mutableStateOf(prefs.getBoolean(KEY_AUTO_DELETE, true)) }
    var deleteSeconds by remember { mutableIntStateOf(
        if (prefs.contains(KEY_DELETE_SECONDS)) prefs.getInt(KEY_DELETE_SECONDS, 1200)
        else (prefs.getInt(KEY_DELETE_MINUTES, 20).coerceAtLeast(1) * 60)
    ) }
    var theme by remember { mutableStateOf(prefs.getString(KEY_THEME, "system") ?: "system") }
    var defaultSort by remember { mutableStateOf(prefs.getString(KEY_DEFAULT_SORT, "reczna") ?: "reczna") }
    var haptics by remember { mutableStateOf(prefs.getBoolean(KEY_HAPTICS, true)) }
    var notifUpdates by remember { mutableStateOf(prefs.getBoolean(KEY_NOTIF_UPDATES, true)) }
    var notifChanges by remember { mutableStateOf(prefs.getBoolean(KEY_NOTIF_CHANGES, true)) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var nowyEmail by remember { mutableStateOf(currentEmail) }
    var emailTrwa by remember { mutableStateOf(false) }
    var komunikatEmail by remember { mutableStateOf<String?>(null) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<GitHubRelease?>(null) }
    val db = FirebaseFirestore.getInstance()

    fun saveBoolean(k: String, v: Boolean) = prefs.edit { putBoolean(k, v) }
    fun saveInt(k: String, v: Int) = prefs.edit { putInt(k, v) }
    fun saveString(k: String, v: String) = prefs.edit { putString(k, v) }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("⚙️ Ustawienia", style = MaterialTheme.typography.headlineSmall)
        SettingsSectionTitle("🎨 Wygląd")
        SettingsCard { SettingsRow("Motyw aplikacji", when(theme){"dark"->"Ciemny";"light"->"Jasny";else->"Systemowy"}, "›", onClick = { dialog = "theme" }) }
        SettingsSectionTitle("👤 Konto")
        SettingsCard {
            SettingsRow("Zalogowane konto", currentEmail.ifBlank { "Nie ustawiono" }, "›", onClick = { nowyEmail = currentEmail; komunikatEmail = null; dialog = "email" })
            HorizontalDivider(); SettingsRow("Resetuj hasło", "Wyślij link", "›", onClick = { dialog = "email_reset_info" })
            HorizontalDivider(); SettingsRow("Wyloguj", "Zakończ sesję", "↪", onClick = onLogout)
        }
        SettingsSectionTitle("👥 Współdzielenie")
        SettingsCard {
            SettingsRow("Udostępnij listę", "Dodaj osoby", "›", onClick = onShareList)
            HorizontalDivider()
            SettingsRow("Zarządzaj dostępem", "Osoby z dostępem", "›", onClick = { dialog = "manage_access" })
        }
        SettingsSectionTitle("🛒 Lista zakupów")
        SettingsCard {
            SettingsSwitchRow("Auto-usuwanie", if(autoDelete)"Włączone" else "Wyłączone", autoDelete) { autoDelete = it; saveBoolean(KEY_AUTO_DELETE, it) }
            HorizontalDivider(); SettingsRow("Czas usunięcia", formatDeleteTime(deleteSeconds), "›", enabled = autoDelete, onClick = { if(autoDelete) dialog = "delete_time" })
            HorizontalDivider(); SettingsRow("Sortowanie", when(defaultSort){"az"->"A-Z";"za"->"Z-A";"dokupienia"->"Do kupienia";"kupione"->"Kupione";else->"Ręczna"}, "›", onClick = { dialog = "sort" })
        }
        SettingsSectionTitle("🔔 Powiadomienia")
        SettingsCard {
            SettingsSwitchRow("Aktualizacje", "Nowe wersje", notifUpdates) { notifUpdates = it; saveBoolean(KEY_NOTIF_UPDATES, it) }
            HorizontalDivider(); SettingsSwitchRow("Aktywność", "Zmiany na liście", notifChanges) { notifChanges = it; saveBoolean(KEY_NOTIF_CHANGES, it) }
        }
        SettingsSectionTitle("📱 Dodatkowe")
        SettingsCard {
            SettingsSwitchRow("Wibracje", if(haptics)"Włączone" else "Wyłączone", haptics) { haptics = it; saveBoolean(KEY_HAPTICS, it); onHapticsChanged(it) }
            HorizontalDivider(); SettingsRow("Informacje", "Wersja ${BuildConfig.VERSION_NAME}", "›", onClick = { dialog = "about" })
            HorizontalDivider(); SettingsRow("Aktualizacja", if(checkingUpdate) "Sprawdzanie..." else "Sprawdź wersję", "↻", onClick = {
            if(!checkingUpdate) {
                checkingUpdate = true
                checkGitHubLatestRelease { r ->
                    checkingUpdate = false
                    if(r != null) {
                        updateInfo = r
                        dialog = "update_status"
                    } else {
                        komunikatEmail = "Nie udało się sprawdzić aktualizacji."
                        dialog = "error_info"
                    }
                }
            }
        })
        }
        SettingsSectionTitle("☕ Wesprzyj projekt")
        SettingsCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Podoba Ci się Lista Zakupów? Możesz postawić mi kawę i pomóc w dalszym rozwoju aplikacji. ❤️", style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://ko-fi.com/listazakupowdev"))
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF29ABE2))
                ) {
                    Text("☕ POSTAW MI KAWĘ", fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
        TextButton(modifier = Modifier.fillMaxWidth(), onClick = { prefs.edit { clear() }; autoDelete = true; deleteSeconds = 1200; saveInt(KEY_DELETE_SECONDS, 1200); theme = "system"; defaultSort = "reczna"; haptics = true; notifUpdates = true; notifChanges = true; onHapticsChanged(true); onThemeChanged("system") }) { Text("Domyślne") }
    }

    if (dialog != null) {
        when (dialog) {
            "theme" -> ChoiceDialog("Motyw", listOf("system" to "Systemowy", "light" to "Jasny", "dark" to "Ciemny"), theme, { theme = it; saveString(KEY_THEME, it); onThemeChanged(it); dialog = null }, { dialog = null })
            "delete_time" -> ChoiceDialog("Czas usunięcia", listOf(
                "5" to "5 sekund",
                "10" to "10 sekund",
                "30" to "30 sekund",
                "60" to "1 minuta",
                "300" to "5 minut",
                "600" to "10 minut",
                "1200" to "20 minut",
                "1800" to "30 minut",
                "3600" to "1 godzina",
                "custom" to "✏️ Własny czas..."
            ), deleteSeconds.toString(), { value ->
                if (value == "custom") {
                    dialog = "delete_custom"
                } else {
                    deleteSeconds = value.toInt()
                    saveInt(KEY_DELETE_SECONDS, deleteSeconds)
                    dialog = null
                }
            }, { dialog = null })
            "delete_custom" -> {
                var customSeconds by remember(dialog) { mutableStateOf(deleteSeconds.toString()) }
                var customError by remember(dialog) { mutableStateOf<String?>(null) }
                AlertDialog(
                    onDismissRequest = { dialog = null },
                    title = { Text("⏱️ Własny czas usunięcia") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = customSeconds,
                                onValueChange = {
                                    if (it.all(Char::isDigit) && it.length <= 7) {
                                        customSeconds = it
                                        customError = null
                                    }
                                },
                                label = { Text("Liczba sekund") },
                                suffix = { Text("s") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text("Możesz ustawić od 1 sekundy do 7 dni (604800 s). Np. 5 = usuń po 5 sekundach.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            customError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val seconds = customSeconds.toIntOrNull()
                            if (seconds == null || seconds !in 1..604800) {
                                customError = "Podaj wartość od 1 do 604800 sekund."
                            } else {
                                deleteSeconds = seconds
                                saveInt(KEY_DELETE_SECONDS, seconds)
                                dialog = null
                            }
                        }) { Text("ZAPISZ") }
                    },
                    dismissButton = { TextButton(onClick = { dialog = null }) { Text("ANULUJ") } }
                )
            }
            "sort" -> ChoiceDialog("Sortowanie", listOf("reczna" to "Ręczna", "az" to "A-Z", "za" to "Z-A", "dokupienia" to "Do kupienia", "kupione" to "Kupione"), defaultSort, { defaultSort = it; saveString(KEY_DEFAULT_SORT, it); onSortChanged(it); dialog = null }, { dialog = null })
            "email" -> AlertDialog(onDismissRequest = { if (!emailTrwa) dialog = null }, title = { Text("E-mail") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = nowyEmail, onValueChange = { nowyEmail = it; komunikatEmail = null }, modifier = Modifier.fillMaxWidth(), label = { Text("E-mail") })
                    komunikatEmail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }, dismissButton = { TextButton(enabled = !emailTrwa, onClick = { dialog = null }) { Text("Anuluj") } }, confirmButton = {
                TextButton(enabled = !emailTrwa, onClick = {
                    val v = nowyEmail.trim(); val u = FirebaseAuth.getInstance().currentUser
                    if (u == null || !android.util.Patterns.EMAIL_ADDRESS.matcher(v).matches()) { komunikatEmail = "Błędne dane."; return@TextButton }
                    emailTrwa = true; u.verifyBeforeUpdateEmail(v).addOnSuccessListener { db.collection("users").document(u.uid).update("email", v).addOnSuccessListener { emailTrwa = false; onEmailChanged(v); dialog = null } }.addOnFailureListener { emailTrwa = false; komunikatEmail = "Błąd." }
                }) { if (emailTrwa) CircularProgressIndicator(Modifier.size(18.dp)) else Text("ZAPISZ") }
            })
            "email_reset_info" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Hasło") }, text = { Text("Wyloguj się i użyj opcji resetowania hasła.") }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("OK") } })
            "about" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("O aplikacji") }, text = { Text("Wersja ${BuildConfig.VERSION_NAME}\nFirebase Firestore") }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("OK") } })
            "update_status" -> {
                val isNew = updateInfo?.let { isReleaseNewer(it) } == true
                AlertDialog(
                    onDismissRequest = { dialog = null },
                    title = { Text("Aktualizacja") },
                    text = {
                        Column {
                            Text(if(isNew) "Dostępna nowa wersja!" else "Masz aktualną wersję.")
                            Spacer(Modifier.height(8.dp))
                            Text("Twoja: ${BuildConfig.VERSION_NAME}")
                            Text("Najnowsza: ${updateInfo?.versionName ?: "???"}")
                        }
                    },
                    confirmButton = {
                        if(isNew) {
                            TextButton(onClick = {
                                dialog = null
                                updateInfo?.downloadUrl?.let { downloadAndInstallUpdate(context, it) }
                            }) { Text("POBIERZ") }
                        } else {
                            TextButton(onClick = { dialog = null }) { Text("OK") }
                        }
                    },
                    dismissButton = if(isNew) { { TextButton(onClick = { dialog = null }) { Text("ANULUJ") } } } else null
                )
            }
            "error_info" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Błąd") }, text = { Text(komunikatEmail ?: "Wystąpił nieoczekiwany błąd.") }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("OK") } })
            "manage_access" -> ZarzadzajDostepemDialog(FirebaseAuth.getInstance().currentUser?.uid ?: "", { dialog = null })
        }
    }
}

@Composable
fun ZarzadzajDostepemDialog(uid: String, onDismiss: () -> Unit) {
    val db = FirebaseFirestore.getInstance()
    var osoby by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var ladowanie by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        db.collection("users").document(uid).get().addOnSuccessListener { d ->
            val raw = d.get("sharedWithNames")
            if (raw is Map<*, *>) {
                osoby = raw.entries.associate { it.key.toString() to it.value.toString() }
            }
            ladowanie = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Osoby z dostępem") },
        text = {
            if (ladowanie) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (osoby.isEmpty()) {
                Text("Nikomu nie udostępniasz listy.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    osoby.forEach { (kod, nazwa) ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(nazwa, style = MaterialTheme.typography.bodyLarge)
                                Text("Kod: $kod", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            }
                            IconButton(onClick = {
                                // 1. Usuń z listy właściciela
                                db.collection("users").document(uid).update("sharedWithNames.$kod", FieldValue.delete())

                                // 2. Usuń samo zaproszenie (Gość sam to wykryje przez listener)
                                db.collection("invitations").document(kod).delete().addOnSuccessListener {
                                    // 3. Odśwież lokalną listę w oknie właściciela
                                    osoby = osoby.filterKeys { it != kod }
                                }
                            }) { Text("🗑️") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("ZAMKNIJ") } }
    )
}

@Composable private fun SettingsSectionTitle(t: String) { Text(t, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)) }
@Composable private fun SettingsCard(c: @Composable ColumnScope.() -> Unit) { Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) { Column(modifier = Modifier.fillMaxWidth(), content = c) } }
@Composable private fun SettingsRow(t: String, s: String, v: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) { Text(t, style = MaterialTheme.typography.bodyLarge); Text(s, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(v, style = MaterialTheme.typography.titleLarge)
    }
}
@Composable private fun SettingsSwitchRow(t: String, s: String, c: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) { Text(t, style = MaterialTheme.typography.bodyLarge); Text(s, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked = c, onCheckedChange = onCheckedChange)
    }
}
@Composable fun ChoiceDialog(t: String, o: List<Pair<String, String>>, s: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(t) }, text = { Column { o.forEach { option -> Row(modifier = Modifier.fillMaxWidth().clickable { onSelect(option.first) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { Text(if (option.first == s) "●" else "○", modifier = Modifier.width(28.dp)); Text(option.second) } } } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } })
}

@Composable fun EmptyShoppingImage() {
    val dark = isSystemInDarkTheme()
    Image(painterResource(if(dark) R.drawable.cart_dark else R.drawable.cart_light), null,
        Modifier.fillMaxWidth().scale(1.25f).height(300.dp).offset(y = 40.dp).graphicsLayer { alpha = if(dark) 0.4f else 0.75f },
        contentScale = ContentScale.Fit)
}
private const val PREFS_SETTINGS = "lista_zakupow_settings"
private const val KEY_AUTO_DELETE = "auto_delete"
private const val KEY_DELETE_MINUTES = "delete_minutes"
private const val KEY_DELETE_SECONDS = "delete_seconds"
private const val KEY_THEME = "theme"
private const val KEY_DEFAULT_SORT = "default_sort"
private const val KEY_CONFIRM_DELETE = "confirm_delete"
private const val KEY_HAPTICS = "haptics"
private const val KEY_NOTIF_UPDATES = "notif_updates"
private const val KEY_NOTIF_CHANGES = "notif_changes"
private fun formatDeleteTime(seconds: Int): String = when {
    seconds < 60 -> "$seconds s"
    seconds % 3600 == 0 -> "${seconds / 3600} godz."
    seconds % 60 == 0 -> "${seconds / 60} min"
    else -> "${seconds}s"
}

private fun settingsPrefs(c: Context) = c.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)

fun dodajProduktDoListy(ctx: Context, nazwa: String, imie: String, kategoria: String) {
    val p = nazwa.trim(); if (p.isEmpty()) return
    val auth = FirebaseAuth.getInstance(); val db = FirebaseFirestore.getInstance(); val uid = auth.currentUser?.uid ?: return
    val prefs = ctx.getSharedPreferences("lista_zakupow", Context.MODE_PRIVATE)
    val ownerId = prefs.getString("shared_owner_id", "")?.takeIf { it.isNotBlank() } ?: uid
    db.collection("shoppingLists").add(hashMapOf("nazwa" to p, "dodal" to imie, "kupione" to false, "kupioneOd" to 0L, "kolejnosc" to System.currentTimeMillis(), "kategoria" to kategoria, "userId" to ownerId))
}

fun zapiszNowaKolejnosc(prods: List<Produkt>) {
    val db = FirebaseFirestore.getInstance()
    prods.forEachIndexed { i, p -> db.collection("shoppingLists").document(p.id).update("kolejnosc", i.toLong()) }
}

fun zapiszNowaKolejnoscSklepow(sklepy: List<Sklep>) {
    val db = FirebaseFirestore.getInstance()
    sklepy.forEachIndexed { i, s -> db.collection("sklepy").document(s.id).update("kolejnosc", i.toLong()) }
}

@Composable
fun DodajLubEdytujSklepDialog(sklep: Sklep?, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalContext.current; val db = FirebaseFirestore.getInstance()
    var n by remember(sklep?.id) { mutableStateOf(sklep?.nazwa ?: "") }
    var e by remember(sklep?.id) { mutableStateOf(sklep?.emoji ?: "🏪") }
    var t by remember(sklep?.id) { mutableStateOf(sklep?.typIkony ?: "emoji") }
    var uri by remember(sklep?.id) { mutableStateOf<Uri?>(null) }
    var img by remember(sklep?.id) { mutableStateOf(sklep?.obrazDane ?: "") }
    var err by remember { mutableStateOf<String?>(null) }; var trw by remember { mutableStateOf(false) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { if (it != null) { uri = it; t = "image" } }

    AlertDialog(onDismissRequest = { if (!trw) onDismiss() }, title = { Text(if (sklep == null) "➕ Dodaj sklep" else "✏️ Edytuj sklep") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = n, onValueChange = { n = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Nazwa sklepu") })
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterButton("😀 Emoji", t == "emoji") { t = "emoji"; uri = null; img = "" }
                FilterButton("🖼️ Logo", t == "image") { t = "image"; launcher.launch("image/*") }
            }
            if (t == "emoji") { OutlinedTextField(value = e, onValueChange = { e = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Emoji") }) }
            else {
                if (uri != null) { AsyncImage(model = uri, contentDescription = null, modifier = Modifier.fillMaxWidth().height(120.dp), contentScale = ContentScale.Fit) }
                else if (img.isNotEmpty()) { SklepIcon(sklep = Sklep(nazwa = n, typIkony = "image", obrazDane = img), modifier = Modifier.fillMaxWidth().height(120.dp)) }
                if (uri != null || img.isNotEmpty()) { TextButton(onClick = { uri = null; img = ""; t = "emoji" }) { Text("🗑️ Usuń logo") } }
            }
            err?.let { Text("❌ $it", color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(enabled = !trw, onClick = {
            if (n.trim().isEmpty()) { err = "Podaj nazwę"; return@TextButton }
            trw = true
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return@TextButton
            val prefs = ctx.getSharedPreferences("lista_zakupow", Context.MODE_PRIVATE)
            val ownId = prefs.getString("shared_owner_id", "")?.takeIf { it.isNotBlank() } ?: uid
            val id = sklep?.id ?: "${uid}_${System.currentTimeMillis()}"
            fun sv(f: String) { db.collection("sklepy").document(id).set(mapOf("nazwa" to n.trim(), "typIkony" to (if (f.isNotEmpty()) "image" else "emoji"), "emoji" to e, "obrazDane" to f, "kolejnosc" to (sklep?.kolejnosc ?: System.currentTimeMillis()), "userId" to ownId)).addOnSuccessListener { trw = false; onSaved() } }
            if (t == "image" && uri != null) { val b = imageUriToBase64(ctx, uri!!); if (b == null) { trw = false; err = "Błąd zdjęcia" } else sv(b) } else sv(if (t == "image") img else "")
        }) { Text("ZAPISZ") }
    }, dismissButton = { TextButton(enabled = !trw, onClick = onDismiss) { Text("ANULUJ") } })
}

fun imageUriToBase64(ctx: Context, uri: Uri): String? {
    return try {
        val stream = ctx.contentResolver.openInputStream(uri) ?: return null
        val orig = BitmapFactory.decodeStream(stream); stream.close()
        if (orig == null) return null
        val r = minOf(256f / orig.width, 256f / orig.height, 1f)
        val res = Bitmap.createScaledBitmap(orig, (orig.width * r).toInt(), (orig.height * r).toInt(), true)
        val out = ByteArrayOutputStream()
        res.compress(Bitmap.CompressFormat.JPEG, 65, out); res.recycle(); orig.recycle()
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    } catch (_: Exception) { null }
}

fun base64ToBitmap(data: String): Bitmap? {
    return try {
        val b = Base64.decode(data, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(b, 0, b.size)
    } catch (_: Exception) { null }
}

@Composable
fun SklepIcon(sklep: Sklep, modifier: Modifier = Modifier) {
    if (sklep.typIkony == "image" && sklep.obrazDane.isNotEmpty()) {
        val b = remember(sklep.obrazDane) { base64ToBitmap(sklep.obrazDane) }
        if (b != null) Image(bitmap = b.asImageBitmap(), contentDescription = sklep.nazwa, contentScale = ContentScale.Fit, modifier = modifier)
        else Text(text = sklep.emoji, style = MaterialTheme.typography.headlineMedium, modifier = modifier)
    } else Text(text = sklep.emoji, style = MaterialTheme.typography.headlineMedium, modifier = modifier)
}
