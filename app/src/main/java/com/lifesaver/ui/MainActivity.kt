package com.lifesaver.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.lifesaver.GuardianService
import com.lifesaver.LifeSaverApp
import com.lifesaver.data.EmergencyContact
import com.lifesaver.detect.ActivityMode
import com.lifesaver.detect.DetectionProfile
import com.lifesaver.learn.Tuning
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as LifeSaverApp

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF0B0F14))) {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF0B0F14)) {
                    HomeScreen(
                        storage = app.storage,
                        onArm = { GuardianService.Commands.start(this) },
                        onDisarm = { GuardianService.Commands.stop(this) },
                        onDrill = { GuardianService.Commands.drill(this) },
                        onSave = { block -> lifecycleScope.launch { app.storage.update(block) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    storage: com.lifesaver.data.Storage,
    onArm: () -> Unit,
    onDisarm: () -> Unit,
    onDrill: () -> Unit,
    onSave: ((com.lifesaver.data.AppState) -> com.lifesaver.data.AppState) -> Unit,
) {
    val context = LocalContext.current
    val state by storage.state.collectAsState()
    var refreshKey by remember { mutableStateOf(0) }
    val readiness = remember(state, refreshKey) { Readiness.evaluate(context, state) }
    val ready = Readiness.canGuard(readiness)
    val score = Readiness.score(readiness)

    val scope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshKey++ }

    LazyColumn(
        modifier = Modifier.fillMaxSize().systemBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("LifeSaver", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Color.White)
            Text(
                "Siz yanıt veremeyecek durumdayken yardımın size ulaşmasını sağlar.",
                fontSize = 14.sp, color = Color(0xFF9AA6B2),
            )
        }

        // ---- Dürüstlük paneli: en başta, gizlenmeden ----
        item { HonestyPanel() }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (state.config.armed) Color(0xFF10331B) else Color(0xFF1A1F26)
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (state.config.armed) "KORUMA ETKİN" else "KORUMA KAPALI",
                        fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        color = if (state.config.armed) Color(0xFF5BD98A) else Color(0xFFB9C2CC),
                    )
                    Text("Hazırlık: %$score", color = Color(0xFF9AA6B2), fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (state.config.armed) {
                                    onDisarm(); onSave { it.copy(config = it.config.copy(armed = false)) }
                                } else {
                                    onArm(); onSave { it.copy(config = it.config.copy(armed = true)) }
                                }
                            },
                            enabled = ready || state.config.armed,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (state.config.armed) Color(0xFF7A2E2E) else Color(0xFF1E6E3C)
                            ),
                        ) { Text(if (state.config.armed) "Korumayı durdur" else "Korumayı başlat") }

                        OutlinedButton(onClick = onDrill) { Text("Tatbikat yap") }
                    }
                    if (!ready) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Aşağıdaki engelleyici maddeler tamamlanmadan koruma başlatılamaz.",
                            color = Color(0xFFFFB4A9), fontSize = 13.sp,
                        )
                    }
                }
            }
        }

        item { SectionTitle("Etkinlik kipi") }
        item {
            ActivityModeSelector(
                current = state.config.activityMode,
                armed = state.config.armed,
                onSelect = { mode ->
                    // Sıra önemlidir: servis kipi DİSKTEN okur, bu yüzden
                    // önce yazma bitmeli, sonra haber verilmelidir.
                    scope.launch {
                        storage.update { it.copy(config = it.config.copy(activityMode = mode)) }
                        if (state.config.armed) GuardianService.Commands.modeChanged(context)
                    }
                },
            )
        }

        item { SectionTitle("Öğrenilenler") }
        item {
            LearningPanel(
                tunings = state.config.tunings,
                enabled = state.config.learningEnabled,
                onToggle = { on ->
                    scope.launch {
                        storage.update { it.copy(config = it.config.copy(learningEnabled = on)) }
                        if (state.config.armed) GuardianService.Commands.modeChanged(context)
                    }
                },
                onRevert = { tuning ->
                    scope.launch {
                        storage.update { st ->
                            st.copy(
                                config = st.config.copy(
                                    tunings = st.config.tunings.filterNot {
                                        it.mode == tuning.mode && it.parameter == tuning.parameter
                                    }
                                )
                            )
                        }
                        if (state.config.armed) GuardianService.Commands.modeChanged(context)
                    }
                },
            )
        }

        item { SectionTitle("Hazırlık kontrolü") }
        items(readiness) { item ->
            ReadinessRow(item) { intent ->
                when {
                    intent != null -> runCatching { context.startActivity(intent) }
                    item.id == "konum" -> permissionLauncher.launch(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION)
                    )
                    item.id == "sms" -> permissionLauncher.launch(arrayOf(Manifest.permission.SEND_SMS))
                    item.id == "arama" -> permissionLauncher.launch(arrayOf(Manifest.permission.CALL_PHONE))
                    item.id == "bildirim" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                        permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                    item.id == "aktivite" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                        permissionLauncher.launch(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION))
                    item.id == "tatbikat" ->
                        onSave { it.copy(config = it.config.copy(drillMode = !it.config.drillMode)) }
                }
                refreshKey++
            }
        }

        item { SectionTitle("Kimlik") }
        item {
            ProfileEditor(state.profile) { profile -> onSave { it.copy(profile = profile) } }
        }

        item { SectionTitle("Acil durum kişileri") }
        item {
            ContactEditor(
                contacts = state.contacts,
                onAdd = { c -> onSave { it.copy(contacts = it.contacts + c) } },
                onRemove = { c -> onSave { it.copy(contacts = it.contacts - c) } },
            )
        }
    }
}

@Composable
private fun ActivityModeSelector(
    current: String,
    armed: Boolean,
    onSelect: (String) -> Unit,
) {
    val currentMode = ActivityMode.fromName(current)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ActivityMode.entries.forEach { mode ->
            val selected = mode == currentMode
            val profile = DetectionProfile.forMode(mode)
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) Color(0xFF13324A) else Color(0xFF141A21)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().clickable { onSelect(mode.name) },
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                    Spacer(
                        Modifier.size(12.dp).background(
                            if (selected) Color(0xFF5AA9E6) else Color(0xFF2A323B), CircleShape
                        )
                    )
                    Spacer(Modifier.size(12.dp))
                    Column {
                        Text(mode.turkish, color = Color.White,
                            fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(mode.description, color = Color(0xFF9AA6B2), fontSize = 12.sp,
                            lineHeight = 17.sp)
                        if (selected) {
                            Spacer(Modifier.height(6.dp))
                            Text(profile.batteryNote, color = Color(0xFFFFC46B),
                                fontSize = 11.sp, lineHeight = 15.sp)
                            profile.gaps.forEach { gap ->
                                Text("• $gap", color = Color(0xFFFF8A7A), fontSize = 11.sp,
                                    lineHeight = 15.sp)
                            }
                        }
                    }
                }
            }
        }
        if (armed) {
            Text("Kip değiştirildiğinde koruma yeniden başlatılır.",
                color = Color(0xFF9AA6B2), fontSize = 11.sp)
        }
        Text(
            "Kaza algılama her kipte kuruludur ve kanıtla kendiliğinden " +
                "silahlanır; kip yalnızca düşme duyarlılığını ve konumun " +
                "sürekli izlenip izlenmediğini belirler. Araç kipi, araçtan " +
                "indiğiniz anlaşılınca ya da 3 saat sonunda kendiliğinden " +
                "Günlük kipe döner — geri almayı unutmanız korumasız " +
                "kalmanıza yol açmaz.",
            color = Color(0xFF9AA6B2), fontSize = 11.sp, lineHeight = 15.sp,
        )
        Text(
            "Kipi bildirim çubuğundan ve hızlı ayarlar döşemesinden de " +
                "değiştirebilirsiniz.",
            color = Color(0xFF9AA6B2), fontSize = 11.sp, lineHeight = 15.sp,
        )
    }
}

/**
 * Öğrenilenler paneli.
 *
 * Kara kutu YOKTUR: sistem bir eşiği değiştirdiyse hangi sayıyı, neden ve ne
 * kadar değiştirdiği burada okunur ve tek dokunuşla geri alınır. Bu, öğrenen
 * bir hayat kurtarma sisteminin taşıması gereken asgari şeffaflıktır —
 * kullanıcı kendisini koruyan eşiğin sessizce gevşetildiğini fark
 * edemiyorsa, sistem güvenilir değildir.
 */
@Composable
private fun LearningPanel(
    tunings: List<Tuning>,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onRevert: (Tuning) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141A21)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(14.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("İptallerden öğrenme", color = Color.White,
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Yalnızca \"böyle bir şey olmadı\" cevabı öğretir; en az 3 " +
                            "benzer olay gerekir ve hiçbir eşik özgün değerinin " +
                            "%30'undan fazla gevşetilemez.",
                        color = Color(0xFF9AA6B2), fontSize = 12.sp, lineHeight = 17.sp,
                    )
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
        }

        if (tunings.isEmpty()) {
            Text(
                "Henüz hiçbir eşik değiştirilmedi. Değiştirilirse burada tam olarak " +
                    "ne olduğu yazacak ve geri alabileceksiniz.",
                color = Color(0xFF9AA6B2), fontSize = 12.sp, lineHeight = 17.sp,
            )
        }

        tunings.forEach { tuning ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1410)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "${ActivityMode.fromName(tuning.mode).turkish} · ${tuning.parameter}",
                        color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(tuning.note, color = Color(0xFFD8C7A8), fontSize = 12.sp, lineHeight = 17.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Duyarlılık %${tuning.changePercent} düşürüldü · " +
                            "${tuning.supportingCancellations} iptale dayanıyor" +
                            if (tuning.isAtCeiling) " · TAVANDA" else "",
                        color = Color(0xFFFFC46B), fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = { onRevert(tuning) }) {
                        Text("Geri al (özgün değere dön)")
                    }
                }
            }
        }
    }
}

@Composable
private fun HonestyPanel() {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1F0E)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Bu uygulamanın YAPAMADIKLARI", fontWeight = FontWeight.Bold,
                color = Color(0xFFFFD08A), fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            listOf(
                "112'yi sizin yerinize SESLİ olarak arayamaz. Android bunu sistem " +
                    "uygulaması olmayan hiçbir uygulamaya izin vermez. Yapabildiği: " +
                    "112'ye konumlu SMS göndermek ve çeviriciyi numara girilmiş halde açmak.",
                "112 operatörüne konuşamaz. Uygulamanın sesi karşı tarafa yalnızca " +
                    "hoparlörden mikrofona, akustik olarak ulaşabilir.",
                "Su altında hiçbir şey gönderemez. Denize düşmede tek şansı, telefon " +
                    "hâlâ havadayken mesajı çıkarmaktır.",
                "Kalp krizini telefonla algılayamaz. Bunun için bilekten/göğüsten " +
                    "nabız ölçen bir cihaz gerekir.",
                "Can yeleği, AIS MOB cihazı veya 406 MHz PLB yerine geçmez.",
            ).forEach {
                Text("• $it", color = Color(0xFFE6D5B8), fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 6.dp), lineHeight = 17.sp)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White,
        modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun ReadinessRow(item: ReadinessItem, onFix: (Intent?) -> Unit) {
    val colour = when {
        item.satisfied -> Color(0xFF5BD98A)
        item.severity == Severity.BLOCKING -> Color(0xFFFF6B5A)
        item.severity == Severity.DEGRADED -> Color(0xFFFFC46B)
        else -> Color(0xFF9AA6B2)
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141A21)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clickable { onFix(item.fix) },
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Spacer(
                Modifier.size(12.dp).background(colour, CircleShape)
                    .padding(top = 4.dp)
            )
            Spacer(Modifier.size(12.dp))
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(item.title, color = Color.White, fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp)
                    Text(
                        when {
                            item.satisfied -> "tamam"
                            item.severity == Severity.BLOCKING -> "ENGELLEYİCİ"
                            item.severity == Severity.DEGRADED -> "zayıf"
                            else -> ""
                        },
                        color = colour, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(item.explanation, color = Color(0xFF9AA6B2), fontSize = 12.sp,
                    lineHeight = 17.sp)
            }
        }
    }
}

@Composable
private fun ProfileEditor(
    profile: com.lifesaver.data.UserProfile,
    onChange: (com.lifesaver.data.UserProfile) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Field("Ad Soyad", profile.name) { onChange(profile.copy(name = it)) }
        Field("Kendi telefon numaranız", profile.phone, KeyboardType.Phone) {
            onChange(profile.copy(phone = it))
        }
        Field("Kan grubu", profile.bloodType) { onChange(profile.copy(bloodType = it)) }
        Field("Alerjiler", profile.allergies) { onChange(profile.copy(allergies = it)) }
        Field("Kronik hastalıklar", profile.conditions) { onChange(profile.copy(conditions = it)) }
        Field("Kullandığı ilaçlar", profile.medications) { onChange(profile.copy(medications = it)) }
        Field("Ev adresi tarifi (kat, daire, kapı kodu)", profile.locationNote) {
            onChange(profile.copy(locationNote = it))
        }
    }
}

@Composable
private fun ContactEditor(
    contacts: List<EmergencyContact>,
    onAdd: (EmergencyContact) -> Unit,
    onRemove: (EmergencyContact) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        contacts.forEach { contact ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141A21)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(14.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(contact.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text(contact.phone, color = Color(0xFF9AA6B2), fontSize = 13.sp)
                    }
                    OutlinedButton(onClick = { onRemove(contact) }) { Text("Sil") }
                }
            }
        }
        Field("Kişi adı", name) { name = it }
        Field("Telefon (+90...)", phone, KeyboardType.Phone) { phone = it }
        Button(
            onClick = {
                if (name.isNotBlank() && phone.isNotBlank()) {
                    onAdd(EmergencyContact(name.trim(), phone.trim()))
                    name = ""; phone = ""
                }
            },
            enabled = name.isNotBlank() && phone.isNotBlank(),
        ) { Text("Kişi ekle") }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}
