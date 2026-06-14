import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID

// ==========================================
// 1. ZMIENNE STANU I STRUKTURY DANYCH
// ==========================================
enum class LinkState { DISCONNECTED, SCANNING, CONNECTING, LINK_TEST, CONNECTED }
enum class OtaState { IDLE, DOWNLOADING, PREPARING, UPLOADING, SUCCESS, ERROR }

data class SensorData(
    val temp: Float = 0f,
    val hum: Float = 0f,
    val soil: Int = 0,
    val isFanOn: Boolean = false,
    val isValveOn: Boolean = false,
    val isAlarmActive: Boolean = false,
    val isSdOk: Boolean = true,
    val isHeaterOn: Boolean = false
)

@SuppressLint("MissingPermission") // Uprawnienia sprawdzamy w MainActivity przed uruchomieniem Managera
class SzklarniaManager(private val context: Context, private val soundManager: SoundManager) {

    // Stany widoczne dla interfejsu (Jetpack Compose)
    val linkState = MutableStateFlow(LinkState.DISCONNECTED)
    val otaState = MutableStateFlow(OtaState.IDLE)
    val sensorData = MutableStateFlow(SensorData())

    val otaProgress = MutableStateFlow(0f)
    val alertMessage = MutableStateFlow("") // Do komunikatów awaryjnych

    // UUIDs
    private val UUID_SERVICE = UUID.fromString("12345678-1234-5678-1234-56789abcdef0")
    private val UUID_STATUS = UUID.fromString("12345678-1234-5678-1234-56789abcdef1")
    private val UUID_CMD = UUID.fromString("12345678-1234-5678-1234-56789abcdef2")
    private val UUID_OTA_DATA = UUID.fromString("12345678-1234-5678-1234-56789abcdef3")

    private var bluetoothGatt: BluetoothGatt? = null
    private var pingPongSuccessCount = 0
    private var isTestingLink = false

    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ==========================================
    // 2. SKANOWANIE I ŁĄCZENIE (AUTO-RECONNECT)
    // ==========================================
    fun startScanning() {
        if (linkState.value != LinkState.DISCONNECTED) return
        linkState.value = LinkState.SCANNING

        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        managerScope.launch {
            soundManager.playRadarPing() // Pierwszy sygnał szukania

            adapter.bluetoothLeScanner?.startScan(object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    if (result.device.name == "Szklarnia Dziadka") {
                        adapter.bluetoothLeScanner?.stopScan(this)
                        connectToDevice(result.device)
                    }
                }
            })
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        linkState.value = LinkState.CONNECTING
        bluetoothGatt = device.connectGatt(context, true, gattCallback) // autoConnect = true
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // Koniecznie wymuszamy maksymalne MTU dla szybkiego OTA!
                gatt.requestMtu(512)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                linkState.value = LinkState.DISCONNECTED
                bluetoothGatt?.close()
                bluetoothGatt = null
                // Jeśli byliśmy w trakcie OTA, a połączenie zerwało:
                if (otaState.value == OtaState.UPLOADING) {
                    alertMessage.value = "Zbliż telefon do urządzenia! Wznawiam..."
                }
                startScanning() // Auto-resume!
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                gatt.discoverServices() // Szukamy serwisów po ustawieniu dużego MTU
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(UUID_SERVICE) ?: return

            // Włączamy notyfikacje na powiadomienia (JSON) i paczki binarne (Status)
            val statusChar = service.getCharacteristic(UUID_STATUS)
            gatt.setCharacteristicNotification(statusChar, true)
            val desc = statusChar.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(desc)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            // 1. Odbiór 12-bajtowych paczek binarnych (Zero JSONa!)
            if (characteristic.uuid == UUID_STATUS) {
                parseBinaryStatus(characteristic.value)
            }
            // 2. Odbiór powiadomień Ping-Pong i komend awaryjnych
            else if (characteristic.uuid == UUID_CMD) {
                val rx = String(characteristic.value)
                if (isTestingLink && rx == "pong") pingPongSuccessCount++
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            // Gdy notyfikacje są włączone, odpalamy Link Test (Ping-Pong) przed wejściem do apki
            if (linkState.value == LinkState.CONNECTING) {
                managerScope.launch { runLinkTest() }
            }
        }
    }

    // ==========================================
    // 3. SILNIK DIAGNOSTYCZNY (Ping-Pong & RSSI)
    // ==========================================
    private suspend fun runLinkTest() {
        linkState.value = LinkState.LINK_TEST
        isTestingLink = true
        pingPongSuccessCount = 0

        val cmdChar = bluetoothGatt?.getService(UUID_SERVICE)?.getCharacteristic(UUID_CMD)

        // Szybka seria 5 pakietów, żeby sprawdzić zrywanie i opóźnienia
        for (i in 1..5) {
            cmdChar?.value = "{\"cmd\":\"ping\"}".toByteArray()
            bluetoothGatt?.writeCharacteristic(cmdChar)
            delay(150) // Czekamy na "pong" z ESP32
        }

        isTestingLink = false

        if (pingPongSuccessCount >= 4) {
            // Wszystko super! Melodia sukcesu!
            linkState.value = LinkState.CONNECTED
            soundManager.playConnectionChime()
        } else {
            // Zły sygnał, telefon wisi w stanie TEST, UI wyświetli "Zbliż telefon..."
            alertMessage.value = "Zbliż telefon do urządzenia..."
            delay(2000)
            runLinkTest() // Powtarzamy do skutku (Pętla Autoregeneracji)
        }
    }

    // ==========================================
    // 4. PARSER BINARNY C++ DO KOTLINA
    // ==========================================
    private fun parseBinaryStatus(bytes: ByteArray) {
        if (bytes.size < 12) return
        // Odczyt surowej struktury z pamięci ESP32
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val temp = buffer.float
        val hum = buffer.float
        val soil = buffer.short.toInt()
        val flags = buffer.get().toInt()

        sensorData.value = SensorData(
            temp = temp,
            hum = hum,
            soil = soil,
            isFanOn = (flags and 1) != 0,
            isValveOn = (flags and 2) != 0,
            isAlarmActive = (flags and 4) != 0,
            isSdOk = (flags and 8) != 0,
            isHeaterOn = (flags and 16) != 0
        )
    }

    // ==========================================
    // 5. OBSŁUGA STAGED OTA (.bin z GitHuba przez BLE)
    // ==========================================
    fun startFirmwareUpdate() {
        managerScope.launch {
            otaState.value = OtaState.DOWNLOADING
            alertMessage.value = ""

            try {
                // 1. Pobranie z GitHuba (Zawsze pobieramy bezpieczny plik, u Ciebie będzie to update.bin z Release)
                val url = URL("https://github.com/BayteTheProtogen/climate-control/releases/latest/download/update.bin")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000 // Ustawienia na słaby internet EDGE na działce
                connection.readTimeout = 60000

                val binFile = File(context.cacheDir, "update.bin")
                connection.inputStream.use { input ->
                    binFile.outputStream().use { output -> input.copyTo(output) }
                }

                // 2. Przygotowanie urządzenia (Obliczanie MD5)
                otaState.value = OtaState.PREPARING
                val md5Hash = calculateMD5(binFile)

                // Sygnał do ESP32: "Gotuj się na plik!"
                val cmdChar = bluetoothGatt?.getService(UUID_SERVICE)?.getCharacteristic(UUID_CMD)
                cmdChar?.value = "{\"cmd\":\"ota_start\", \"md5\":\"$md5Hash\"}".toByteArray()
                bluetoothGatt?.writeCharacteristic(cmdChar)

                delay(1000) // Czekamy aż SD się otworzy w ESP32

                // 3. Wgrywanie Chunkami (Sliding Window / Write Without Response)
                otaState.value = OtaState.UPLOADING
                val otaChar = bluetoothGatt?.getService(UUID_SERVICE)?.getCharacteristic(UUID_OTA_DATA)
                otaChar?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

                val fileBytes = binFile.readBytes()
                val totalBytes = fileBytes.size
                val chunkSize = 240 // Zostawiamy miejsce na 2 bajty nagłówka i mieścimy się w MTU 512

                var bytesSent = 0
                var chunkIndex = 0

                while (bytesSent < totalBytes) {
                    // Sprawdzanie czy nie odeszliśmy za daleko
                    if (linkState.value != LinkState.CONNECTED) {
                        delay(1000)
                        continue // Auto-resume: Pętla czeka aż Bluetooth wróci!
                    }

                    val end = (bytesSent + chunkSize).coerceAtMost(totalBytes)
                    val payloadSize = end - bytesSent

                    // Budowa paczki: [INDEX_L, INDEX_H, DATA...]
                    val chunkPacket = ByteArray(2 + payloadSize)
                    chunkPacket[0] = (chunkIndex and 0xFF).toByte()
                    chunkPacket[1] = ((chunkIndex shr 8) and 0xFF).toByte()
                    System.arraycopy(fileBytes, bytesSent, chunkPacket, 2, payloadSize)

                    otaChar?.value = chunkPacket
                    bluetoothGatt?.writeCharacteristic(otaChar)

                    bytesSent += payloadSize
                    chunkIndex++

                    otaProgress.value = bytesSent.toFloat() / totalBytes.toFloat()

                    // Ochrona bufora ESP32 przed przepełnieniem (Sztuczne zwolnienie tempa)
                    delay(15)
                }

                // 4. Koniec!
                cmdChar?.value = "{\"cmd\":\"ota_end\"}".toByteArray()
                bluetoothGatt?.writeCharacteristic(cmdChar)

                otaState.value = OtaState.SUCCESS
                soundManager.playConnectionChime() // Radosny triumf na koniec!

            } catch (e: Exception) {
                Log.e("SzklarniaManager", "OTA Error: ${e.message}")
                otaState.value = OtaState.ERROR
                alertMessage.value = "Błąd pobierania: ${e.message}"
            }
        }
    }

    // ==========================================
    // 6. OBSŁUGA POZOSTAŁYCH KOMEND (Kalibracja)
    // ==========================================
    fun sendJsonCommand(json: String) {
        val cmdChar = bluetoothGatt?.getService(UUID_SERVICE)?.getCharacteristic(UUID_CMD)
        cmdChar?.value = json.toByteArray()
        bluetoothGatt?.writeCharacteristic(cmdChar)
    }

    // Pomocnicze: Obliczanie MD5
    private fun calculateMD5(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                md.update(buffer, 0, bytesRead)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}