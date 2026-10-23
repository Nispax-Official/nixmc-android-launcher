package in.nispax.nixmc

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val NixBackground = Color(0xFF0B0D12)
private val NixSurface = Color(0xFF151922)
private val NixAccent = Color(0xFF9BFF65)
private val NixMuted = Color(0xFF9AA3B2)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = NixAccent,
                    onPrimary = Color(0xFF10150C),
                    background = NixBackground,
                    surface = NixSurface,
                    onSurface = Color(0xFFF3F5F8)
                )
            ) {
                NixMCHome()
            }
        }
    }
}

@Composable
private fun NixMCHome() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = NixBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp, vertical = 26.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .background(NixAccent, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text("N", color = NixBackground, fontSize = 24.sp, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("NixMC", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Text("JAVA FOR ANDROID", fontSize = 10.sp, color = NixMuted, letterSpacing = 2.sp)
                    }
                }
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = NixMuted
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("YOUR NEXT", color = NixMuted, fontSize = 12.sp, letterSpacing = 3.sp)
                Text("WORLD AWAITS.", fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    "A Java Edition launcher built for your Android device.",
                    color = NixMuted,
                    fontSize = 14.sp
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = NixSurface)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Build, contentDescription = null, tint = NixAccent)
                        Spacer(Modifier.width(10.dp))
                        Text("GAME PROFILE", color = NixMuted, fontSize = 11.sp, letterSpacing = 2.sp)
                    }
                    Text("Minecraft Java Edition", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Tag("Not installed")
                        Tag("Offline profile ready to plan")
                    }
                    Button(
                        onClick = { },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NixAccent,
                            contentColor = Color(0xFF10150C)
                        )
                    ) {
                        Text("LAUNCHER SETUP", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                    Text(
                        "Game installation and runtime support are coming next.",
                        color = NixMuted,
                        fontSize = 12.sp
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111A14))
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.CloudOff, contentDescription = null, tint = NixAccent)
                    Spacer(Modifier.width(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Offline profiles", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Plan a local player profile for worlds and servers that allow offline-mode connections.",
                            color = NixMuted,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))
            Text("NIXMC  •  EARLY DEVELOPMENT", color = NixMuted, fontSize = 10.sp, letterSpacing = 1.5.sp)
        }
    }
}

@Composable
private fun Tag(label: String) {
    Box(
        modifier = Modifier
            .background(Color(0xFF242B35), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Text(label, color = NixMuted, fontSize = 10.sp)
    }
}
