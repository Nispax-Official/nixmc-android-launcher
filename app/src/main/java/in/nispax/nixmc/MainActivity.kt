package `in`.nispax.nixmc

import android.os.Bundle
import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.io.File
import `in`.nispax.nixmc.profile.PlayerNameValidator
import `in`.nispax.nixmc.profile.PlayerProfileRepository
import `in`.nispax.nixmc.engine.EngineBridgeVerifier
import `in`.nispax.nixmc.download.DownloadActions
import `in`.nispax.nixmc.download.DownloadStateStore
import `in`.nispax.nixmc.download.GameDownloadService

private val NixBackground = Color(0xFF0B0D12)
private val NixSurface = Color(0xFF151922)
private val NixSurfaceRaised = Color(0xFF1C222D)
private val NixAccent = Color(0xFF9BFF65)
private val NixMuted = Color(0xFF9AA3B2)
private val NixBlue = Color(0xFF93B7FF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val profileRepository = PlayerProfileRepository(applicationContext)
        profileRepository.saveSelectedVersion("1.21.11")

        setContent {
            NixMCTheme {
                NixMCApp(
                    initialUsername = profileRepository.loadUsername(),
                    initialSelectedVersion = "1.21.11",
                    saveUsername = profileRepository::saveUsername,
                    saveSelectedVersion = profileRepository::saveSelectedVersion
                )
            }
        }
    }
}

@Composable
private fun NixMCTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = NixAccent,
            onPrimary = Color(0xFF10150C),
            secondary = NixBlue,
            background = NixBackground,
            surface = NixSurface,
            onSurface = Color(0xFFF3F5F8),
            onBackground = Color(0xFFF3F5F8),
            error = Color(0xFFFF7777)
        ),
        content = content
    )
}

private enum class AppScreen(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    PROFILE("Profile", Icons.Default.Person),
    SETUP("Setup", Icons.Default.Download),
    SETTINGS("Settings", Icons.Default.Settings)
}

@Composable
private fun NixMCApp(
    initialUsername: String,
    initialSelectedVersion: String?,
    saveUsername: (String) -> Boolean,
    saveSelectedVersion: (String) -> Boolean
) {
    var currentScreen by rememberSaveable { mutableStateOf(AppScreen.HOME) }
    var username by rememberSaveable { mutableStateOf(initialUsername) }
    var draftUsername by rememberSaveable { mutableStateOf(initialUsername) }
    var selectedVersion by rememberSaveable { mutableStateOf(initialSelectedVersion ?: "latest") }
    var feedback by rememberSaveable { mutableStateOf<String?>(null) }
    var feedbackIsError by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = NixBackground,
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF10131A)) {
                AppScreen.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentScreen == tab,
                        onClick = {
                            currentScreen = tab
                            if (tab != AppScreen.PROFILE) feedback = null
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            when (currentScreen) {
                AppScreen.HOME -> HomeScreen(
                    username = username,
                    onOpenProfile = { currentScreen = AppScreen.PROFILE },
                    onOpenSetup = { currentScreen = AppScreen.SETUP },
                    onOpenSettings = { currentScreen = AppScreen.SETTINGS }
                )

                AppScreen.PROFILE -> ProfileScreen(
                    username = username,
                    draftUsername = draftUsername,
                    onDraftUsernameChange = {
                        draftUsername = it
                        feedback = null
                    },
                    onSave = {
                        val saved = saveUsername(it)
                        if (saved) {
                            username = it
                            draftUsername = it
                            feedback = "Player name saved on this device."
                            feedbackIsError = false
                        } else {
                            feedback = "That player name could not be saved."
                            feedbackIsError = true
                        }
                        saved
                    },
                    feedback = feedback,
                    feedbackIsError = feedbackIsError
                )

                AppScreen.SETUP -> SetupScreen(
                    username = username,
                    selectedVersion = selectedVersion,
                    onVersionSelected = { version ->
                        if (saveSelectedVersion(version)) selectedVersion = version
                    },
                    onOpenProfile = { currentScreen = AppScreen.PROFILE }
                )
                AppScreen.SETTINGS -> SettingsScreen()
            }
        }
    }
}

@Composable
private fun HomeScreen(
    username: String,
    onOpenProfile: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(NixAccent, RoundedCornerShape(15.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("N", color = NixBackground, fontSize = 27.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("NixMC", fontSize = 25.sp, fontWeight = FontWeight.ExtraBold)
                Text("JAVA EDITION · ANDROID", color = NixMuted, fontSize = 10.sp, letterSpacing = 1.6.sp)
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Open settings", tint = NixMuted)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("YOUR NEXT", color = NixAccent, fontSize = 11.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold)
            Text(
                "WORLD AWAITS.",
                fontSize = 34.sp,
                lineHeight = 38.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                "A Java Edition launcher built around your Android device.",
                color = NixMuted,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = NixSurface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(listOf(Color(0xFF202C28), NixSurface, NixSurface))
                    )
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(15.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = NixAccent)
                    Spacer(Modifier.width(8.dp))
                    Text("ACTIVE PLAYER PROFILE", color = NixMuted, fontSize = 10.sp, letterSpacing = 1.6.sp)
                }
                Text(username, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                Text("Offline profile · saved locally", color = NixMuted, fontSize = 13.sp)
                Button(
                    onClick = onOpenProfile,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NixAccent,
                        contentColor = Color(0xFF10150C)
                    )
                ) {
                    Icon(Icons.Default.Person, contentDescription = null)
                    Spacer(Modifier.width(9.dp))
                    Text("MANAGE PLAYER PROFILE", fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = NixSurface)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Download, contentDescription = null, tint = NixBlue)
                    Spacer(Modifier.width(10.dp))
                    Text("GAME INSTALLATION", fontSize = 11.sp, color = NixMuted, letterSpacing = 1.5.sp)
                }
                Text("Minecraft Java Edition", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text("Install verified game files and Java 21. Downloads continue with a visible notification while NixMC is in the background.", color = NixMuted, fontSize = 13.sp, lineHeight = 19.sp)
                OutlinedButton(
                    onClick = onOpenSetup,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("VIEW SETUP STATUS")
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111A14))
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(Icons.Default.CloudOff, contentDescription = null, tint = NixAccent)
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Offline profiles", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Use a local player name for single-player testing and environments that explicitly allow offline-mode connections. This does not sign in to a Microsoft account.",
                        color = NixMuted,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        }

        Text(
            "NIXMC  •  EARLY DEVELOPMENT",
            modifier = Modifier.fillMaxWidth(),
            color = NixMuted,
            fontSize = 10.sp,
            letterSpacing = 1.5.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ProfileScreen(
    username: String,
    draftUsername: String,
    onDraftUsernameChange: (String) -> Unit,
    onSave: (String) -> Boolean,
    feedback: String?,
    feedbackIsError: Boolean
) {
    val validationError = PlayerNameValidator.errorFor(draftUsername)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        PageHeading(
            eyebrow = "PLAYER PROFILE",
            title = "Choose your name.",
            subtitle = "Set the local player name NixMC will use when offline-profile launching is implemented."
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = NixSurface)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("PLAYER NAME", color = NixMuted, fontSize = 11.sp, letterSpacing = 1.6.sp)
                OutlinedTextField(
                    value = draftUsername,
                    onValueChange = onDraftUsernameChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Username") },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                    singleLine = true,
                    isError = validationError != null,
                    supportingText = {
                        Text(validationError ?: "3–16 characters: letters, numbers, and underscores.")
                    }
                )
                Text("Current saved name: $username", color = NixMuted, fontSize = 12.sp)
                Button(
                    onClick = { onSave(draftUsername) },
                    enabled = validationError == null,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NixAccent,
                        contentColor = Color(0xFF10150C)
                    )
                ) {
                    Text("SAVE PLAYER PROFILE", fontWeight = FontWeight.Bold)
                }
                if (feedback != null) {
                    Text(
                        text = feedback,
                        color = if (feedbackIsError) MaterialTheme.colorScheme.error else NixAccent,
                        fontSize = 13.sp
                    )
                }
            }
        }

        InfoCard(
            icon = { Icon(Icons.Default.CloudOff, contentDescription = null, tint = NixAccent) },
            title = "What offline means",
            body = "This stores a player name on this device; it does not authenticate an account. Online-mode servers require authenticated accounts and will not accept an offline identity."
        )
        InfoCard(
            icon = { Icon(Icons.Default.Info, contentDescription = null, tint = NixBlue) },
            title = "Not connected to the game yet",
            body = "The profile is saved now. Passing it into Minecraft's launch arguments will be implemented with the launcher/runtime integration."
        )
    }
}

@Composable
private fun SetupScreen(
    username: String,
    selectedVersion: String,
    onVersionSelected: (String) -> Unit,
    onOpenProfile: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val downloadState by produceState(
        initialValue = DownloadStateStore.read(context),
        key1 = context
    ) {
        while (true) {
            value = DownloadStateStore.read(context)
            delay(500)
        }
    }
    var pendingDownloadAction by remember { mutableStateOf<String?>(null) }
    var notificationPermissionMessage by rememberSaveable { mutableStateOf<String?>(null) }

    fun startServiceIfNotificationsEnabled(action: String) {
        val notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val channelDisabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.getNotificationChannel(GameDownloadService.CHANNEL_ID)?.importance ==
                NotificationManager.IMPORTANCE_NONE
        } else {
            false
        }
        if (!notificationsEnabled || channelDisabled) {
            notificationPermissionMessage =
                "Turn on NixMC download notifications in Android settings first. No download was started."
            return
        }
        ContextCompat.startForegroundService(
            context,
            android.content.Intent(context, GameDownloadService::class.java).setAction(action)
        )
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pendingDownloadAction
        pendingDownloadAction = null
        if (granted && action != null) {
            startServiceIfNotificationsEnabled(action)
        } else if (!granted) {
            notificationPermissionMessage =
                "Allow NixMC notifications in Android settings so background download progress and errors stay visible. No download was started."
        }
    }

    fun requestBackgroundDownload(action: String) {
        notificationPermissionMessage = null
        if (downloadState.isRunning) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownloadAction = action
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startServiceIfNotificationsEnabled(action)
        }
    }
    val isDownloading = downloadState.isRunning && downloadState.action == DownloadActions.GAME
    val isInstallingRuntime = downloadState.isRunning && downloadState.action == DownloadActions.JAVA
    val installStatus = downloadState.takeIf { it.action == DownloadActions.GAME }?.status
        ?: "Game files not verified yet. Tap install to start a visible background download."
    val runtimeStatus = downloadState.takeIf { it.action == DownloadActions.JAVA }?.status
        ?: "Java 21 runtime has not been checked in build 0.5.0."
    val installError = downloadState.action == DownloadActions.GAME && (downloadState.isFailed || downloadState.isCanceled)
    val runtimeError = downloadState.action == DownloadActions.JAVA && (downloadState.isFailed || downloadState.isCanceled)
    var isCheckingLaunchPlan by rememberSaveable("0.5.0", "isCheckingLaunchPlan") { mutableStateOf(false) }
    var launchPlanStatus by rememberSaveable("0.5.0", "launchPlanStatus") { mutableStateOf("Readiness has not been checked in build 0.5.0.") }
    var launchPlanError by rememberSaveable("0.5.0", "launchPlanError") { mutableStateOf(false) }
    var gameFilesVerified by rememberSaveable("0.5.0", "gameFilesVerified") { mutableStateOf(false) }
    var runtimeCheckPassed by rememberSaveable("0.4.0", "runtimeCheckPassed") { mutableStateOf(false) }
    var bridgeManifestPassed by rememberSaveable("0.5.0", "bridgeManifestPassed") { mutableStateOf(false) }
    LaunchedEffect(downloadState.action, downloadState.phase) {
        if (downloadState.action == DownloadActions.GAME && downloadState.isComplete) gameFilesVerified = true
        if (downloadState.action == DownloadActions.JAVA && downloadState.isComplete) runtimeCheckPassed = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        PageHeading(
            eyebrow = "LAUNCHER SETUP · BUILD 0.5.0",
            title = "Prepare to play.",
            subtitle = "NixMC 0.5.0 keeps downloads visible in Android notifications, verifies official hashes, and stores game data privately. Locked to Minecraft Java Edition 1.21.11."
        )
        Text(
            "BUILD CHECK: 0.5.0 · If this label is not visible, the previous APK is still installed.",
            color = NixAccent,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.2.sp
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = NixSurface)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("FIXED TEST VERSION", color = NixMuted, fontSize = 11.sp, letterSpacing = 1.6.sp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF202C28), RoundedCornerShape(14.dp))
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("Minecraft Java Edition", color = NixMuted, fontSize = 12.sp)
                        Text("1.21.11", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                        Text("Official Mojang client", color = NixAccent, fontSize = 12.sp)
                    }
                    Icon(Icons.Default.CheckCircle, contentDescription = "Fixed test version", tint = NixAccent, modifier = Modifier.size(28.dp))
                }

                Button(
                    onClick = { requestBackgroundDownload(DownloadActions.GAME) },
                    enabled = !downloadState.isRunning,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    if (isDownloading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF10150C)
                        )
                        Spacer(Modifier.width(10.dp))
                    } else {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (isDownloading) "DOWNLOADING GAME FILES…" else "INSTALL 1.21.11 GAME FILES")
                }
                Text(
                    installStatus,
                    color = if (installError) MaterialTheme.colorScheme.error else NixMuted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                if (isDownloading && downloadState.progress > 0) {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { downloadState.progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Button(
                    onClick = { requestBackgroundDownload(DownloadActions.JAVA) },
                    enabled = !downloadState.isRunning,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NixBlue,
                        contentColor = Color(0xFF10150C)
                    )
                ) {
                    if (isInstallingRuntime) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF10150C)
                        )
                        Spacer(Modifier.width(10.dp))
                    } else {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (isInstallingRuntime) "DOWNLOADING JAVA 21…" else "INSTALL JAVA 21 RUNTIME")
                }
                Text(
                    runtimeStatus,
                    color = if (runtimeError) MaterialTheme.colorScheme.error else NixMuted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                if (isInstallingRuntime && downloadState.progress > 0) {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { downloadState.progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (notificationPermissionMessage != null) {
                    Text(
                        notificationPermissionMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            isCheckingLaunchPlan = true
                            launchPlanError = false
                            gameFilesVerified = false
                            runtimeCheckPassed = false
                            bridgeManifestPassed = false
                            launchPlanStatus = "Verifying and repairing installed game files…"
                            try {
                                // Readiness is read-only. All downloads must use the visible foreground service.
                                launchPlanStatus = "Checking downloaded game files, Java runtime, and engine bridge…"
                                val plan = withContext(Dispatchers.IO) {
                                    `in`.nispax.nixmc.install.MinecraftLaunchPlanner.create(
                                        File(context.filesDir, "minecraft"),
                                        username,
                                        architecture = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
                                    )
                                }
                                gameFilesVerified = plan.isComplete

                                val runtimeCheck = `in`.nispax.nixmc.install.AndroidJavaRuntimeInstaller
                                    .inspectInstalled(context.filesDir)
                                runtimeCheckPassed = runtimeCheck.isReady

                                val bridgeCheck = withContext(Dispatchers.IO) {
                                    EngineBridgeVerifier.inspect(
                                        bridgeRoot = File(context.filesDir, "minecraft/engine/bridge"),
                                        androidAbi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
                                        javaMajorVersion = runtimeCheck.runtime?.majorVersion
                                    )
                                }
                                bridgeManifestPassed = bridgeCheck.isReady

                                val blockers = buildList {
                                    if (!plan.isComplete) {
                                        add("Game files missing: ${plan.missingFiles.take(3).joinToString()}")
                                    }
                                    if (!runtimeCheck.isReady) add(runtimeCheck.diagnostic)
                                    addAll(bridgeCheck.blockers.take(3))
                                    // The contract is ready for integration, but no in-process native launcher,
                                    // Android surface renderer, or touch-input session is wired into NixMC yet.
                                    add("Minecraft launch is still blocked: the in-process Java/LWJGL/GLFW bridge and game-session renderer are not integrated.")
                                }
                                launchPlanStatus = blockers.joinToString(separator = " ")
                                launchPlanError = true
                            } catch (error: Exception) {
                                launchPlanError = true
                                gameFilesVerified = false
                                runtimeCheckPassed = false
                                bridgeManifestPassed = false
                                launchPlanStatus = "Readiness check failed: " + (error.message ?: "Check the install and retry.")
                            } finally {
                                isCheckingLaunchPlan = false
                            }
                        }
                    },
                    enabled = !isCheckingLaunchPlan && !isDownloading && !isInstallingRuntime,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    if (isCheckingLaunchPlan) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (isCheckingLaunchPlan) "CHECKING LAUNCH PLAN…" else "CHECK LAUNCH READINESS")
                }
                Text(
                    launchPlanStatus,
                    color = if (launchPlanError) MaterialTheme.colorScheme.error else NixMuted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = NixSurface)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("LAUNCH READINESS", color = NixMuted, fontSize = 11.sp, letterSpacing = 1.6.sp)
                SetupRow("Android app and GUI", "Implemented", true)
                SetupRow("Version lock", "1.21.11", true)
                SetupRow("Client JAR + SHA-1 check", "Implemented", true)
                SetupRow("Library and asset downloads", "Implemented", true)
                SetupRow(
                    "Official client, libraries, and assets",
                    if (gameFilesVerified) "Verified" else "Not checked",
                    gameFilesVerified
                )
                SetupRow(
                    "Android Java 21 runtime",
                    if (runtimeCheckPassed) "Startup probe passed" else "Not verified",
                    runtimeCheckPassed
                )
                SetupRow(
                    "Native bridge manifest",
                    if (bridgeManifestPassed) "Files verified" else "Missing / invalid",
                    bridgeManifestPassed
                )
                SetupRow("Graphics renderer and touch controls", "Not integrated", false)
                SetupRow("Game launch process", "Blocked", false)
            }
        }

        Text(
            "Install game files and the Android Java 21 runtime above. Minecraft still cannot launch until the Android-native LWJGL/GLFW bridge, native library handling, graphics renderer, and touch controls are integrated.",
            color = NixMuted,
            fontSize = 13.sp,
            lineHeight = 19.sp
        )

        Button(
            onClick = onOpenProfile,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("CONFIGURE PLAYER PROFILE")
        }
    }
}

@Composable
private fun SettingsScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        PageHeading(
            eyebrow = "SETTINGS",
            title = "NixMC details.",
            subtitle = "Launcher preferences and runtime configuration will live here as the project grows."
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = NixSurface)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                InfoRow("App version", "0.5.0 (build 5)")
                InfoRow("Package ID", "in.nispax.nixmc")
                InfoRow("Minimum Android", "Android 8.0 (API 26)")
                InfoRow("UI toolkit", "Kotlin + Jetpack Compose")
                InfoRow("Game runtime", "Not integrated yet")
                InfoRow("Game data storage", "Private app storage · removed on uninstall")
            }
        }

        InfoCard(
            icon = { Icon(Icons.Default.Info, contentDescription = null, tint = NixBlue) },
            title = "Project status",
            body = "NixMC is an early development project. The profile and launcher UI work locally; downloading Minecraft, Java runtime execution, rendering, and input support still need implementation and testing."
        )
    }
}

@Composable
private fun PageHeading(eyebrow: String, title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(eyebrow, color = NixAccent, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
        Text(title, fontSize = 29.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold)
        Text(subtitle, color = NixMuted, fontSize = 13.sp, lineHeight = 19.sp)
    }
}

@Composable
private fun SetupRow(label: String, status: String, complete: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (complete) Icons.Default.CheckCircle else Icons.Default.Info,
            contentDescription = null,
            tint = if (complete) NixAccent else NixMuted
        )
        Spacer(Modifier.width(12.dp))
        Text(label, modifier = Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(8.dp))
        Text(
            status,
            color = if (complete) NixAccent else NixMuted,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = NixMuted, fontSize = 12.sp)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun InfoCard(
    icon: @Composable () -> Unit,
    title: String,
    body: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = NixSurfaceRaised)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            icon()
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(body, color = NixMuted, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
    }
}
