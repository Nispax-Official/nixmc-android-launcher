package `in`.nispax.nixmc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import `in`.nispax.nixmc.version.MinecraftVersionCatalog
import `in`.nispax.nixmc.version.MinecraftVersion
import `in`.nispax.nixmc.profile.PlayerNameValidator
import `in`.nispax.nixmc.profile.PlayerProfileRepository

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

        setContent {
            NixMCTheme {
                NixMCApp(
                    initialUsername = profileRepository.loadUsername(),
                    initialSelectedVersion = profileRepository.loadSelectedVersion(),
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
                Text("The installer and runtime are not connected yet.", color = NixMuted, fontSize = 13.sp, lineHeight = 19.sp)
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
    selectedVersion: String,
    onVersionSelected: (String) -> Unit,
    onOpenProfile: () -> Unit
) {
    var versions by rememberSaveable { mutableStateOf(emptyList<MinecraftVersion>()) }
    var isLoading by rememberSaveable { mutableStateOf(false) }
    var loadError by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        PageHeading(
            eyebrow = "LAUNCHER SETUP",
            title = "Prepare to play.",
            subtitle = "NixMC's Android foundation is in place. The game installation pipeline is the next major milestone."
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
                Text("MINECRAFT VERSION", color = NixMuted, fontSize = 11.sp, letterSpacing = 1.6.sp)
                Text(
                    if (selectedVersion == "latest") "Latest release (not selected yet)" else selectedVersion,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Fetch the official Mojang Java Edition version manifest to browse stable releases.",
                    color = NixMuted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                Button(
                    onClick = {
                        scope.launch {
                            isLoading = true
                            loadError = null
                            try {
                                versions = MinecraftVersionCatalog.fetchLatestReleases()
                                if (versions.isEmpty()) loadError = "No release versions were returned."
                            } catch (error: Exception) {
                                loadError = error.message ?: "Could not load the version catalog. Check your connection."
                            } finally {
                                isLoading = false
                            }
                        }
                    },
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF10150C)
                        )
                        Spacer(Modifier.width(10.dp))
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (isLoading) "LOADING RELEASES..." else "LOAD OFFICIAL RELEASES")
                }
                if (loadError != null) {
                    Text(loadError!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                versions.forEach { version ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (selectedVersion == version.id) Color(0xFF202C28) else NixSurfaceRaised,
                                RoundedCornerShape(12.dp)
                            )
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(version.id, fontWeight = FontWeight.SemiBold)
                            Text("Released \${version.releaseTime.take(10)}", color = NixMuted, fontSize = 11.sp)
                        }
                        if (selectedVersion == version.id) {
                            Icon(Icons.Default.CheckCircle, contentDescription = "Selected", tint = NixAccent)
                        } else {
                            OutlinedButton(onClick = { onVersionSelected(version.id) }) {
                                Text("SELECT")
                            }
                        }
                    }
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
                Text("IMPLEMENTATION STATUS", color = NixMuted, fontSize = 11.sp, letterSpacing = 1.6.sp)
                SetupRow("Android app shell", "Implemented", true)
                SetupRow("Offline profile storage", "Implemented", true)
                SetupRow("Official release catalog", "Implemented", true)
                SetupRow("Game file installer", "Next", false)
                SetupRow("Java runtime integration", "Not implemented", false)
                SetupRow("Rendering and touch controls", "Not implemented", false)
                SetupRow("Game launch process", "Not implemented", false)
            }
        }

        Text(
            "Selecting a version saves your preference only. It does not download the game yet; installation, runtime, and rendering still need integration.",
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
                InfoRow("App version", "0.1.0")
                InfoRow("Package ID", "in.nispax.nixmc")
                InfoRow("Minimum Android", "Android 8.0 (API 26)")
                InfoRow("UI toolkit", "Kotlin + Jetpack Compose")
                InfoRow("Game runtime", "Not integrated yet")
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
