package com.lifesaver.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.lifesaver.GuardianService
import com.lifesaver.LifeSaverApp
import com.lifesaver.detect.ActivityMode
import com.lifesaver.detect.DetectionProfile
import kotlinx.coroutines.launch

/**
 * Bildirimden açılan kip seçici.
 *
 * KİP NEDEN ÖNEMLİ: yaya düşmesinin darbe imzası ile motosiklet kazasınınki
 * birbirine karışırsa iki hata da ağır olur — koşarken her adım alarm çalar,
 * ya da araçtaki gerçek çarpışma "sıradan bir sarsıntı" sayılır. Bu yüzden
 * kip önceden bildirilir ve DEĞİŞTİRMESİ tek dokunuş kadar kolay olmalıdır:
 * arabaya binerken uygulamayı açmak zorunda kalan kimse bunu yapmaz.
 *
 * Kip diske BURADA yazılır; servise yalnızca "yeniden yükle" denir.
 */
class ModePickerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Kilit ekranında da çalışsın: kip değişimi genelde araca binerken,
        // telefon kilitliyken gerekir.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        }

        val storage = (application as LifeSaverApp).storage

        setContent {
            val state by storage.state.collectAsState()
            MaterialTheme {
                Surface(color = Color(0xFF0B0F14), modifier = Modifier.fillMaxSize()) {
                    ModePickerScreen(
                        current = ActivityMode.fromName(state.config.activityMode),
                        onPick = { mode -> commit(mode) },
                        onDismiss = { finish() },
                    )
                }
            }
        }
    }

    private fun commit(mode: ActivityMode) {
        val storage = (application as LifeSaverApp).storage
        lifecycleScope.launch {
            storage.update { it.copy(config = it.config.copy(activityMode = mode.name)) }
            // Yazma bittikten SONRA haber ver: servis diskten okur.
            GuardianService.Commands.modeChanged(this@ModePickerActivity)
            finish()
        }
    }
}

@Composable
private fun ModePickerScreen(
    current: ActivityMode,
    onPick: (ActivityMode) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Etkinlik kipi", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "Kaza algılama her kipte açıktır ve kendiliğinden silahlanır. " +
                "Kip yalnızca düşme duyarlılığını ve konumun sürekli izlenip " +
                "izlenmediğini belirler.",
            color = Color(0xFF9AA6B2), fontSize = 13.sp, lineHeight = 18.sp,
        )
        Spacer(Modifier.height(6.dp))

        ActivityMode.entries.forEach { mode ->
            val selected = mode == current
            val profile = DetectionProfile.forMode(mode)
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) Color(0xFF13324A) else Color(0xFF141A21)
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().clickable { onPick(mode) },
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                    Spacer(
                        Modifier.size(14.dp).background(
                            if (selected) Color(0xFF5AA9E6) else Color(0xFF2A323B), CircleShape
                        )
                    )
                    Spacer(Modifier.size(14.dp))
                    Column {
                        Text(
                            mode.turkish, color = Color.White,
                            fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            mode.description, color = Color(0xFF9AA6B2),
                            fontSize = 12.sp, lineHeight = 17.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            profile.batteryNote, color = Color(0xFFFFC46B),
                            fontSize = 11.sp, lineHeight = 15.sp,
                        )
                        profile.gaps.forEach { gap ->
                            Text(
                                "• $gap", color = Color(0xFFFF8A7A),
                                fontSize = 11.sp, lineHeight = 15.sp,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Vazgeç",
            color = Color(0xFF9AA6B2),
            fontSize = 15.sp,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onDismiss() }
                .padding(vertical = 14.dp),
        )
        Spacer(Modifier.height(24.dp))
    }
}
