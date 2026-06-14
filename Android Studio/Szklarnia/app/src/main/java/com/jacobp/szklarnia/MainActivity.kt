package com.jacobp.szklarnia
import LinkState
import SensorData
import SoundManager
import SzklarniaManager
import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import java.util.Locale

// --- KOFEINA (Systemowy Keep Screen On) ---
@Composable
fun CaffeineEffect() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

// Stany Nawigacji UI
enum class AppScreen { GRANDPA, DEVELOPER, OTA_FLOW }
enum class OtaUiStep { SLIDES, WAITING_DOWNLOAD, LINK_TEST, FLASHING, SUCCESS }

class MainActivity : ComponentActivity() {

    private lateinit var soundManager: SoundManager
    private lateinit var szklarniaManager: SzklarniaManager

    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        szklarniaManager.startScanning()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        soundManager = SoundManager(this)
        szklarniaManager = SzklarniaManager(this, soundManager)

        requestPermissions.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        ))

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF0F4F8)) {
                    val currentScreen = remember { mutableStateOf(AppScreen.GRANDPA) }
                    val sensorData by szklarniaManager.sensorData.collectAsState()
                    val linkState by szklarniaManager.linkState.collectAsState()

                    Crossfade(targetState = currentScreen.value, label = "ScreenTransition") { screen ->
                        when (screen) {
                            AppScreen.GRANDPA -> GrandpaScreen(
                                data = sensorData,
                                linkState = linkState,
                                onDevClick = { currentScreen.value = AppScreen.DEVELOPER },
                                onUpdateClick = { currentScreen.value = AppScreen.OTA_FLOW }
                            )
                            AppScreen.DEVELOPER -> DeveloperScreen(
                                onBack = { currentScreen.value = AppScreen.GRANDPA },
                                onCalDry = { szklarniaManager.sendJsonCommand("{\"cmd\":\"cal_dry\"}") },
                                onCalWet = { szklarniaManager.sendJsonCommand("{\"cmd\":\"cal_wet\"}") }
                            )
                            AppScreen.OTA_FLOW -> OtaUpdateScreen(
                                manager = szklarniaManager,
                                soundManager = soundManager,
                                onFinish = { currentScreen.value = AppScreen.GRANDPA }
                            )
                        }
                    }
                }
            }
        }
    }



    // ==========================================
    // EKRAN 1: ODPICOWANY INTERFEJS DZIADKA
    // ==========================================
    @Composable
    fun GrandpaScreen(data: SensorData, linkState: LinkState, onDevClick: () -> Unit, onUpdateClick: () -> Unit) {
        var clickCount by remember { mutableStateOf(0) }

        Column(modifier = Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {

            // Nagłówek statusu BLE (z ładniejszym paddingiem)
            Text(
                text = when(linkState) {
                    LinkState.CONNECTED -> "✅ POŁĄCZONO ZE SZKLARNIĄ"
                    LinkState.SCANNING -> "⏳ SZUKAM SZKLARNI..."
                    else -> "❌ ROZŁĄCZONO"
                },
                fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp,
                color = if (linkState == LinkState.CONNECTED) Color(0xFF2E7D32) else Color(0xFFD32F2F),
                modifier = Modifier
                    .padding(top = 24.dp, bottom = 24.dp)
                    .clickable {
                        clickCount++
                        if (clickCount >= 4) { clickCount = 0; onDevClick() }
                    }
            )

            // CICHY BŁĄD KARTY SD (Ładniejszy design błędu)
            if (!data.isSdOk) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = "Błąd", tint = Color(0xFFD32F2F))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Brak karty SD (Historia nie jest zapisywana)", color = Color(0xFFB71C1C), fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    }
                }
            }

            // KAFLE DANYCH (Nowoczesne, z ikonami i cieniami)

            // Formatowanie temperatury do 1 miejsca po przecinku (np. 23.1 °C)
            val formattedTemp = String.format(Locale.US, "%.1f", data.temp)

            StatusCard(
                title = "Temperatura wewnątrz",
                value = "$formattedTemp °C",
                bgColor = Color(0xFFFFF3E0),
                iconColor = Color(0xFFFF9800),
                icon = Icons.Rounded.Settings
            )

            Spacer(modifier = Modifier.height(20.dp))

            StatusCard(
                title = "Wilgotność ziemi",
                value = "${data.soil} %",
                bgColor = Color(0xFFE3F2FD),
                iconColor = Color(0xFF2196F3),
                icon = Icons.Rounded.Settings
            )

            Spacer(modifier = Modifier.weight(1f))

            // Przycisk Ręcznej Aktualizacji (Nowoczesny)
            Button(
                onClick = onUpdateClick,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E1E1E)),
                shape = RoundedCornerShape(24.dp), // Mocno zaokrąglony
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp),
                modifier = Modifier.fillMaxWidth().height(64.dp)
            ) {
                Text("ZAKTUALIZUJ SZKLARNIĘ", fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // ==========================================
    // NOWOCZESNY WIDŻET KARTY
    // ==========================================
    @Composable
    fun StatusCard(title: String, value: String, bgColor: Color, iconColor: Color, icon: androidx.compose.ui.graphics.vector.ImageVector) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp), // Trochę wyższa
            shape = RoundedCornerShape(28.dp), // Bardzo gładkie rogi w stylu iOS
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp), // Dodajemy cień!
            colors = CardDefaults.cardColors(containerColor = bgColor)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(title, fontSize = 16.sp, color = Color.DarkGray, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(value, fontSize = 46.sp, fontWeight = FontWeight.Black, color = Color.Black)
                }

                // Piktogram po prawej stronie karty (półprzezroczysty)
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp).alpha(0.8f),
                    tint = iconColor
                )
            }
        }
    }
    // ==========================================
    // EKRAN 2: TRYB DEWELOPERA (Kalibracja)
    // ==========================================
    @Composable
    fun DeveloperScreen(onBack: () -> Unit, onCalDry: () -> Unit, onCalWet: () -> Unit) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Button(onClick = onBack) { Text("⬅ Wróć") }
            Spacer(modifier = Modifier.height(24.dp))
            Text("🛠 Kalibracja HD-38", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = onCalDry, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("Zapisz 0%") }
                Button(onClick = onCalWet, colors = ButtonDefaults.buttonColors(containerColor = Color.Blue)) { Text("Zapisz 100%") }
            }
        }
    }

    // ==========================================
    // EKRAN 3: MASTERPIECE OTA (Slajdy, Lupa, Wgrywanie)
    // ==========================================
    @Composable
    fun OtaUpdateScreen(manager: SzklarniaManager, soundManager: SoundManager, onFinish: () -> Unit) {
        CaffeineEffect() // Kofeina - zapobiega uśpieniu telefonu przez cały proces!

        val otaState by manager.otaState.collectAsState()
        val linkState by manager.linkState.collectAsState()
        val progress by manager.otaProgress.collectAsState()
        val alertMsg by manager.alertMessage.collectAsState()

        var uiStep by remember { mutableStateOf(OtaUiStep.SLIDES) }
        val slideTexts = listOf("Zaraz rozpocznie się wgrywanie...", "Pozostań blisko urządzenia...", "Nie dotykaj telefonu ani szklarni...", "To potrwa kilka minut.")
        var currentSlide by remember { mutableStateOf(0) }

        // Start pobierania i Głośności w TLE w momencie wejścia
        LaunchedEffect(Unit) {
            soundManager.setVolumeToOptimal()
            manager.startFirmwareUpdate() // Asynchroniczne pobieranie po sieci EDGE/LTE

            // Pętla slajdów (4x 5s = 20 sekund sztywnego czasu)
            for (i in 0..3) {
                currentSlide = i
                delay(5000)
            }

            // Slajdy się skończyły. Sprawdzamy co z pobieraniem:
            if (otaState == OtaState.DOWNLOADING) uiStep = OtaUiStep.WAITING_DOWNLOAD
            else uiStep = OtaUiStep.LINK_TEST
        }

        // Reakcja na pobranie jeśli wciąż czekaliśmy
        LaunchedEffect(otaState) {
            if (uiStep == OtaUiStep.WAITING_DOWNLOAD && (otaState == OtaState.PREPARING || otaState == OtaState.UPLOADING)) {
                uiStep = OtaUiStep.LINK_TEST
            }
        }

        // Kontrola przejść Diagnostyki Lupy
        LaunchedEffect(linkState) {
            if (uiStep == OtaUiStep.LINK_TEST && linkState == LinkState.CONNECTED) {
                delay(3000) // Wymuszony czas oglądania Checkmarka
                uiStep = OtaUiStep.FLASHING
            }
        }

        // Kontrola alarmu wibracyjnego z Manager'a (Zbliż telefon!)
        val context = LocalContext.current
        LaunchedEffect(alertMsg) {
            if (alertMsg.isNotEmpty()) {
                soundManager.playAlarmSweep()
            }
        }

        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF1E1E1E)) {
            Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {

                Crossfade(targetState = uiStep, animationSpec = tween(1000), label = "ota_flow") { step ->
                    when(step) {
                        OtaUiStep.SLIDES -> SlideShow(slideTexts[currentSlide], currentSlide)
                        OtaUiStep.WAITING_DOWNLOAD -> DownloadingWait()
                        OtaUiStep.LINK_TEST -> LinkDiagnostic(linkState)
                        OtaUiStep.FLASHING -> FlashingProgress(otaState, progress, alertMsg)
                        OtaUiStep.SUCCESS -> {
                            SuccessScreen {
                                soundManager.restoreOriginalVolume()
                                onFinish()
                            }
                        }
                    }
                }
            }
        }

        if (otaState == OtaState.SUCCESS) { LaunchedEffect(Unit) { uiStep = OtaUiStep.SUCCESS } }
    }

    // --- SUB-KOMPONENTY OTA ---

    @Composable
    fun SlideShow(text: String, slideIndex: Int) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, fontSize = 24.sp, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
            Spacer(modifier = Modifier.height(40.dp))

            // Animacja magnesu - przybliżające się pudełka
            val offsetPhone by animateFloatAsState(targetValue = if (slideIndex == 0) -150f else if(slideIndex == 3) -20f else -60f, animationSpec = tween(3000), label = "p")
            val offsetEsp by animateFloatAsState(targetValue = if (slideIndex == 0) 150f else if(slideIndex == 3) 20f else 60f, animationSpec = tween(3000), label = "e")
            val opacityLink by animateFloatAsState(targetValue = if (slideIndex >= 2) 1f else 0f, animationSpec = tween(2000), label = "o")

            Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                // Linia Bluetooth
                Text("~ ~ ~ ~ ~", color = Color.Cyan, fontSize = 30.sp, modifier = Modifier.alpha(opacityLink))
                // Telefon
                Box(modifier = Modifier.offset(x = offsetPhone.dp).size(60.dp, 100.dp).background(Color.White, RoundedCornerShape(8.dp)))
                // ESP32 Szklarnia
                Box(modifier = Modifier.offset(x = offsetEsp.dp).size(70.dp).background(Color(0xFF2E7D32), RoundedCornerShape(4.dp)))
            }
        }
    }

    @Composable
    fun DownloadingWait() {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Color.White)
            Spacer(modifier = Modifier.height(24.dp))
            Text("Słaby zasięg internetu.\nPobieram najnowszą wersję...", fontSize = 20.sp, color = Color.White, textAlign = TextAlign.Center)
        }
    }

    @Composable
    fun LinkDiagnostic(linkState: LinkState) {
        val infiniteTransition = rememberInfiniteTransition(label = "lupa")
        val offsetX by infiniteTransition.animateFloat(initialValue = -50f, targetValue = 50f, animationSpec = infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "l_x")

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (linkState == LinkState.CONNECTED) {
                Text("✓", fontSize = 100.sp, color = Color.Green, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                Text("Połączenie jest stabilne!", fontSize = 24.sp, color = Color.White)
            } else {
                Text("🔍", fontSize = 80.sp, modifier = Modifier.offset(x = offsetX.dp))
                Spacer(modifier = Modifier.height(24.dp))
                Text(if (linkState == LinkState.LINK_TEST) "Testuję stabilność połączenia..." else "Szukam urządzenia...", fontSize = 22.sp, color = Color.White, textAlign = TextAlign.Center)
            }
        }
    }

    @Composable
    fun FlashingProgress(otaState: OtaState, progress: Float, alertMsg: String) {
        Column(modifier = Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (alertMsg.isNotEmpty()) {
                Text(alertMsg, color = Color.Yellow, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(24.dp))
            }

            Text("Przygotowuję urządzenie...", fontSize = 22.sp, color = Color.LightGray)
            Spacer(modifier = Modifier.height(8.dp))

            val statusText = when(otaState) {
                OtaState.PREPARING -> "Obliczam sumy kontrolne i tworzę backup..."
                OtaState.UPLOADING -> "Przesyłam pliki do szklarni..."
                OtaState.SUCCESS -> "Kończę aktualizację..."
                else -> "Pracuję..."
            }
            Text(statusText, fontSize = 28.sp, color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)

            Spacer(modifier = Modifier.height(32.dp))
            LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth().height(16.dp), color = Color.Cyan, trackColor = Color.DarkGray)
            Spacer(modifier = Modifier.height(16.dp))
            Text("${(progress * 100).toInt()}%", fontSize = 32.sp, color = Color.White, fontWeight = FontWeight.Black)
        }
    }

    @Composable
    fun SuccessScreen(onClick: () -> Unit) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🎉", fontSize = 100.sp)
            Spacer(modifier = Modifier.height(24.dp))
            Text("SUKCES!", fontSize = 36.sp, color = Color.Green, fontWeight = FontWeight.Bold)
            Text("Szklarnia zaktualizowana.", fontSize = 20.sp, color = Color.White)
            Spacer(modifier = Modifier.height(40.dp))
            Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = Color.White)) {
                Text("WRÓĆ DO EKRANU GŁÓWNEGO", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}