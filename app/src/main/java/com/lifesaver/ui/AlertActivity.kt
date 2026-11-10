package com.lifesaver.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifesaver.EscalationBus
import com.lifesaver.GuardianService
import com.lifesaver.LifeSaverApp
import com.lifesaver.LiveIncident
import com.lifesaver.alert.Action
import com.lifesaver.alert.Coordinates
import com.lifesaver.alert.Fix
import com.lifesaver.learn.CancelReason
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Alarm ekranı.
 *
 * İki görevi vardır:
 *  1. Kullanıcıya iptal şansı vermek (BASILI TUTARAK — tek dokunuşla değil).
 *  2. Servisin yapamadığı, AKTİVİTE gerektiren eylemleri yürütmek:
 *     yakınları aramak ve 112 çeviricisini açmak. Android 10'dan beri
 *     bir servis arka plandan aktivite başlatamaz; bu ekran görünür olduğu
 *     için bunları o yapabilir.
 *
 * Kilit ekranının üstünde gösterilir ve ekranı uyandırır (manifest'teki
 * showWhenLocked/turnScreenOn). Kilidi AÇMAZ ve açmaya çalışmaz.
 */
class AlertActivity : ComponentActivity() {

    private companion object {
        const val TAG = "LifeSaver/Alert"
        /** İptal için gereken basılı tutma süresi. */
        const val HOLD_TO_CANCEL_MS = 3000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ekran açık ve en parlak kalsın: baygın kullanıcıyı bulan kişi
        // sağlık kartını ve koordinatı okuyabilmelidir.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 1.0f }

        setContent {
            val incident by EscalationBus.live.collectAsState()
            // İptalden SONRA sebep sorulur. Sıra bilinçlidir: siren derhal
            // susar, soru ondan sonra gelir. Cevap verilmezse sistem hiçbir
            // şey öğrenmez — bu güvenli varsayılandır.
            var askingReason by remember { mutableStateOf(false) }
            if (askingReason) {
                CancelReasonScreen(
                    onPick = { reason ->
                        GuardianService.Commands.cancelReason(this, reason)
                        finish()
                    },
                    onSkip = { finish() },
                )
            } else {
                AlertScreen(
                    incident = incident,
                    onCancel = {
                        GuardianService.Commands.cancel(this)
                        // Tatbikatta sebep sorulmaz: cevabı zaten bilinir ve
                        // öğrenmeye girmez.
                        if (incident?.learnable == true) askingReason = true else finish()
                    },
                )
            }
        }

        observeActions()
    }

    /**
     * Servisten gelen, aktivite gerektiren emirleri yürütür.
     */
    private fun observeActions() {
        val app = application as LifeSaverApp
        lifecycleScope.launch {
            EscalationBus.actions.collect { action ->
                val drill = app.storage.state.value.config.drillMode
                when (action) {
                    is Action.CallContact -> placeCall(action.contact.phone, drill)
                    Action.Surface112Dialer -> surfaceDialer112(drill)
                    else -> Unit
                }
            }
        }
    }

    /** Yakını arar. Normal numaralar için ACTION_CALL çalışır. */
    private fun placeCall(number: String, drill: Boolean) {
        if (drill) { Log.i(TAG, "TATBIKAT: $number aranmadi"); return }
        runCatching {
            startActivity(
                Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Log.e(TAG, "Arama basarisiz: $number", it) }
    }

    /**
     * 112 çeviricisini numarası girilmiş halde açar.
     *
     * BURASI UYGULAMANIN EN DÜRÜST OLMASI GEREKEN YERİ: bu çağrıyı
     * tamamlayacak olan bir İNSAN parmağıdır. AOSP Telecom, varsayılan
     * çevirici olmayan bir uygulamanın acil numaraya ACTION_CALL'ını iptal
     * eder. Baygın bir kullanıcı bu düğmeye basamaz; bu ekran çevrede
     * BİRİNİN olduğu durum için vardır.
     */
    private fun surfaceDialer112(drill: Boolean) {
        if (drill) { Log.i(TAG, "TATBIKAT: 112 cevirici acilmadi"); return }
        runCatching {
            startActivity(
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:112"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Log.e(TAG, "112 cevirici acilamadi", it) }
    }
}

@Composable
private fun AlertScreen(incident: LiveIncident?, onCancel: () -> Unit) {
    // Dikkat çekmek için 1 Hz yanıp sönen zemin.
    val transition = rememberInfiniteTransition(label = "flash")
    val flash by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "flashValue",
    )

    val background = if (incident?.escalated == true) {
        Color(0xFF7F0000)
    } else {
        Color(red = 0.35f + 0.4f * flash, green = 0f, blue = 0f)
    }

    var holdProgress by remember { mutableFloatStateOf(0f) }
    var holdStart by remember { mutableLongStateOf(0L) }

    LaunchedEffect(holdStart) {
        if (holdStart == 0L) { holdProgress = 0f; return@LaunchedEffect }
        while (holdProgress < 1f) {
            val elapsed = System.currentTimeMillis() - holdStart
            holdProgress = (elapsed / 3000f).coerceIn(0f, 1f)
            if (holdProgress >= 1f) { onCancel(); return@LaunchedEffect }
            delay(30)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        holdStart = System.currentTimeMillis()
                        // Parmak kalkarsa iptal ilerlemesi sıfırlanır:
                        // cepteki tek bir dokunuş alarmı susturmamalıdır.
                        tryAwaitRelease()
                        holdStart = 0L
                        holdProgress = 0f
                    }
                )
            }
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (incident?.drill == true) {
                Text(
                    "TATBİKAT - GERÇEK YARDIM ÇAĞRILMIYOR",
                    color = Color.Yellow,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
            }

            Text(
                text = incident?.scenarioTurkish?.uppercase() ?: "ACİL DURUM",
                color = Color.White,
                fontSize = 34.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))

            if (incident?.escalated == true) {
                Text(
                    "YARDIM ÇAĞRILDI",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Bu kişinin bilinci kapalı olabilir.\nLütfen yardım edin ve 112'yi arayın.",
                    color = Color.White,
                    fontSize = 20.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 28.sp,
                )
            } else {
                Text(
                    text = "${incident?.secondsRemaining ?: 0}",
                    color = Color.White,
                    fontSize = 96.sp,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    "saniye sonra yardım çağrılacak",
                    color = Color.White,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(24.dp))

            // Konum: bulan kişinin okuyup telefonda söyleyebilmesi için büyük.
            if (incident?.latitude != null && incident.longitude != null) {
                val fix = Fix(
                    incident.latitude,
                    incident.longitude,
                    incident.accuracyMeters ?: 0f,
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0x33000000), RoundedCornerShape(12.dp))
                        .padding(16.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("KONUM", color = Color.White, fontSize = 13.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            Coordinates.decimal(fix),
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            Coordinates.degreesDecimalMinutes(fix),
                            color = Color.White,
                            fontSize = 15.sp,
                        )
                        val acc = Coordinates.accuracyText(fix)
                        if (acc.isNotEmpty()) {
                            Text("doğruluk $acc", color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
            } else {
                Text("Konum alınıyor...", color = Color.White, fontSize = 15.sp)
            }

            if (!incident?.reason.isNullOrEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Algılama gerekçesi: " + incident.reason.joinToString(", "),
                    color = Color(0xCCFFFFFF),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(32.dp))

            Text(
                "İYİYSENİZ EKRANA 3 SANİYE BASILI TUTUN",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { holdProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp),
                color = Color.White,
                trackColor = Color(0x44FFFFFF),
            )
        }
    }
}

/**
 * İptal sebebi ekranı — öğrenen sistemin TEK güvenli girdisi.
 *
 * Neden sorulduğu önemlidir: iptaller yalnızca olumsuz örnek üretir ve
 * hepsinden öğrenilirse sistem tek yönlü olarak sağırlaşır. "Oldu ama
 * iyiyim" cevabı eşiğin DOĞRU çalıştığını söyler; ondan öğrenmek dedektöre
 * gerçek düşmeleri görmezden gelmeyi öğretir. Bu yüzden yalnızca "böyle bir
 * şey olmadı" cevabı öğretir, diğerleri sadece kayda geçer.
 *
 * Cevapsız kapatmak da geçerli bir seçenektir: kayıt "belirtilmedi" kalır
 * ve hiçbir eşiğe dokunulmaz.
 */
@Composable
private fun CancelReasonScreen(
    onPick: (CancelReason) -> Unit,
    onSkip: () -> Unit,
) {
    // Kimse cevaplamazsa ekran kilit ekranında asılı kalmasın.
    LaunchedEffect(Unit) { delay(45_000); onSkip() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F14))
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Alarm durduruldu",
            color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Yardım çağrılmadı. Sistemin doğru öğrenebilmesi için ne olduğunu " +
                "söyleyebilir misiniz?",
            color = Color(0xFF9AA6B2), fontSize = 15.sp, lineHeight = 21.sp,
        )
        Spacer(Modifier.height(24.dp))

        listOf(
            CancelReason.FALSE_ALARM to "Telefon düştü, çarptım, sarsıldı — bana bir şey olmadı.",
            CancelReason.REAL_BUT_FINE to "Gerçekten düştüm/çarpıştım ama iyiyim. Eşik doğru çalıştı.",
            CancelReason.ACCIDENTAL to "Alarmı yanlışlıkla durdurdum.",
        ).forEach { (reason, explanation) ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .background(Color(0xFF141A21), RoundedCornerShape(14.dp))
                    .clickable { onPick(reason) }
                    .padding(18.dp)
            ) {
                Column {
                    Text(
                        reason.turkish, color = Color.White,
                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(explanation, color = Color(0xFF9AA6B2), fontSize = 13.sp, lineHeight = 18.sp)
                    if (reason.teaches) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Yalnızca bu cevap eşikleri değiştirebilir — o da birkaç " +
                                "benzer olaydan sonra ve sınırlı ölçüde.",
                            color = Color(0xFFFFC46B), fontSize = 12.sp, lineHeight = 16.sp,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Cevaplamadan kapat",
            color = Color(0xFF9AA6B2), fontSize = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSkip() }
                .padding(vertical = 14.dp),
        )
    }
}
