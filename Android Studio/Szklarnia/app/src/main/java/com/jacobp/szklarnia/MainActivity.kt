package com.jacobp.szklarnia

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.jacobp.szklarnia.ble.BleManager
import com.jacobp.szklarnia.ui.theme.*

class MainActivity : ComponentActivity() {
    private lateinit var bleManager: BleManager

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            bleManager.startScan()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bleManager = BleManager(this)

        checkPermissionsAndScan()

        setContent {
            SzklarniaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DashboardScreen(bleManager)
                }
            }
        }
    }

    private fun checkPermissionsAndScan() {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            bleManager.startScan()
        } else {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }
}

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

@Composable
fun DashboardScreen(bleManager: BleManager) {
    val connectionState by bleManager.connectionState.collectAsState()
    val sensorData by bleManager.sensorData.collectAsState()

    var showOtaSlide by remember { mutableStateOf(false) }

    if (showOtaSlide) {
        CaffeineEffect()
        OtaUpdateScreen(onComplete = { showOtaSlide = false })
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Górny pasek statusu połączenia
        val statusColor = when (connectionState) {
            BleManager.ConnectionState.CONNECTED -> GreenActive
            BleManager.ConnectionState.SCANNING -> OrangeWarning
            else -> UnselectedGray
        }
        val statusText = when (connectionState) {
            BleManager.ConnectionState.CONNECTED -> "POŁĄCZONO ZE SZKLARNIĄ"
            BleManager.ConnectionState.SCANNING -> "SZUKAM SZKLARNI..."
            else -> "ODŁĄCZONO"
        }

        Surface(
            color = statusColor,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(statusText, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Temperatura i Wilgotność
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MetricCard("Temperatura", "${sensorData.temperature}°C", Modifier.weight(1f))
            Spacer(modifier = Modifier.width(16.dp))
            MetricCard("Wilgotność", "${sensorData.humidity}%", Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Gleba i Alarmy SD
        val sdStatus = if (!sensorData.isSdOk) "\nBŁĄD KARTY SD!" else ""
        MetricCard("Stan Gleby", "Mokro (${sensorData.soilMoisture}%)$sdStatus", Modifier.fillMaxWidth())

        Spacer(modifier = Modifier.height(32.dp))

        // Podpięte przełączniki
        ControlButton(
            label = "WENTYLATOR",
            isOn = sensorData.isFanOn,
            onClick = { bleManager.sendCommand("{\"cmd\":\"force_relay\",\"relay\":\"fan\",\"state\":${!sensorData.isFanOn}}") }
        )
        Spacer(modifier = Modifier.height(16.dp))
        ControlButton(
            label = "PODLEWANIE",
            isOn = sensorData.isValveOn,
            onClick = { bleManager.sendCommand("{\"cmd\":\"force_relay\",\"relay\":\"valve\",\"state\":${!sensorData.isValveOn}}") }
        )

        Spacer(modifier = Modifier.weight(1f))

        // Opcjonalny przycisk aktualizacji
        Button(
            onClick = { showOtaSlide = true },
            colors = ButtonDefaults.buttonColors(containerColor = OrangeWarning),
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("DOSTĘPNA AKTUALIZACJA", color = CharcoalText, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
fun OtaUpdateScreen(onComplete: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("OSTRZEŻENIE", fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, color = RedAlert)
        Spacer(modifier = Modifier.height(32.dp))
        Text("Zbliż telefon do urządzenia\nNie wyłączaj aplikacji", fontSize = 24.sp, color = CharcoalText)
        Spacer(modifier = Modifier.height(64.dp))
        CircularProgressIndicator(modifier = Modifier.size(100.dp), color = OrangeWarning)
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onComplete) {
            Text("Zakończ Symulację OTA")
        }
    }
}

@Composable
fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.height(140.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, fontSize = 18.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(value, fontSize = 36.sp, color = CharcoalText, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
fun ControlButton(label: String, isOn: Boolean, onClick: () -> Unit) {
    val bgColor = if (isOn) GreenActive else UnselectedGray
    val textColor = if (isOn) Color.White else CharcoalText

    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = bgColor),
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(if (isOn) "$label: WŁĄCZONY" else "$label: WYŁĄCZONY", color = textColor, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
    }
}
