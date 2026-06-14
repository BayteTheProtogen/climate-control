#include <Wire.h>
#include <Adafruit_SHT4x.h>
#include <Preferences.h>
#include <esp_task_wdt.h>
#include <SPI.h>
#include <SD.h>
#include <Update.h>

// --- KONFIGURACJA PINÓW ---
#define PIN_SOIL_ADC 34
#define PIN_SOIL_VCC 32   // Zasilanie czujnika gleby (Antykorozja)
#define PIN_RELAY_FAN 25
#define PIN_RELAY_VALVE 26
#define PIN_BUZZER 27
#define PIN_SD_CS 5

// --- WATCHDOG (Dla ESP32 Core 3.x) ---
#define WDT_TIMEOUT 10 

// --- STRUKTURA BINARNA (Byte Packing - 12 bajtów) ---
// Tę samą strukturę odtworzymy w Kotlinie. Zero parsowania JSON!
struct __attribute__((__packed__)) SensorDataPacket {
  float temperature;    // 4 bajty
  float humidity;       // 4 bajty
  uint16_t soilMoisture;// 2 bajty
  uint8_t statusFlags;  // 1 bajt (Bit 0: Fan, Bit 1: Valve, Bit 2: Alarm, Bit 3: SD_OK, Bit 4: HeaterOn)
  uint8_t padding;      // 1 bajt (Wyrównanie do 32-bitów)
};

SensorDataPacket bleData;

// --- OBIEKTY GŁÓWNE ---
Adafruit_SHT4x sht4 = Adafruit_SHT4x();
Preferences preferences;

// --- ZMIENNE STANU ---
int rawAdc = 0;
bool safetyAlarmActive = false;
bool sdCardReady = false;
bool isHeaterActive = false;
bool isBleConnected = false;

// --- TIMERY I GRZAŁKA SHT41 ---
unsigned long lastSensorRead = 0;
unsigned long highHumidityStartTime = 0;
unsigned long heaterCooldownEnd = 0;
bool isHighHumidity = false;

// --- KONFIGURACJA (Pobierana z NVRAM) ---
float cfg_fanOnTemp = 30.0;
float cfg_fanOffTemp = 26.0;
int cfg_soilWaterStart = 40;
int cfg_soilWaterStop = 70;
int cfg_soilAdcDry = 4095;
int cfg_soilAdcWet = 1500;

// Logika podlewania pulsacyjnego
unsigned long cfg_pulseWateringTime = 5 * 60 * 1000;
unsigned long cfg_pulseSoakingTime = 10 * 60 * 1000;
unsigned long cfg_safetyTimeout = 120 * 60 * 1000;

enum WateringState { IDLE, WATERING, SOAKING, TIMEOUT_ERROR };
WateringState waterState = IDLE;
unsigned long waterStateTimer = 0;
unsigned long wateringSessionStart = 0;

// ==========================================
// 1. OCHRONA NVRAM (Potrójna nadmiarowość)
// ==========================================
void loadSecureConfig() {
  preferences.begin("greenhouse", false);
  
  // Głosowanie większościowe dla kluczowej kalibracji (przykład dla AdcDry)
  int d1 = preferences.getInt("adcDry_1", 4095);
  int d2 = preferences.getInt("adcDry_2", 4095);
  int d3 = preferences.getInt("adcDry_3", 4095);

  if (d1 == d2) cfg_soilAdcDry = d1;
  else if (d2 == d3) cfg_soilAdcDry = d2;
  else if (d1 == d3) cfg_soilAdcDry = d1;
  else cfg_soilAdcDry = d1; // Jeśli wszystko uszkodzone, bierzemy pierwsze

  // Jeśli NVS naprawił błąd, nadpisujemy poprawną wartością wszystkie 3 sloty
  if (d1 != cfg_soilAdcDry || d2 != cfg_soilAdcDry || d3 != cfg_soilAdcDry) {
    saveSecureCalibration(cfg_soilAdcDry, cfg_soilAdcWet);
  }

  cfg_soilAdcWet = preferences.getInt("adcWet_1", 1500);
  cfg_fanOnTemp = preferences.getFloat("fanOn", 30.0);
  cfg_fanOffTemp = preferences.getFloat("fanOff", 26.0);
  cfg_soilWaterStart = preferences.getInt("soilStart", 40);
  cfg_soilWaterStop = preferences.getInt("soilStop", 70);
}

void saveSecureCalibration(int dry, int wet) {
  cfg_soilAdcDry = dry; cfg_soilAdcWet = wet;
  preferences.putInt("adcDry_1", dry); preferences.putInt("adcDry_2", dry); preferences.putInt("adcDry_3", dry);
  preferences.putInt("adcWet_1", wet); preferences.putInt("adcWet_2", wet); preferences.putInt("adcWet_3", wet);
}

// ==========================================
// 2. ODCZYTY SENSORÓW (Antykorozja + Grzałka)
// ==========================================
void processSensors() {
  unsigned long currentMillis = millis();

  // Sprawdź czy trwa chłodzenie grzałki po suszeniu czujnika
  if (isHeaterActive) {
    if (currentMillis > heaterCooldownEnd) {
      isHeaterActive = false;
      bitClear(bleData.statusFlags, 4); // Zdejmij flagę grzałki
    } else {
      return; // Czekamy na schłodzenie SHT41, zamrażamy odczyty
    }
  }

  // --- SHT41 ODCZYT ---
  sensors_event_t hum, temp;
  sht4.getEvent(&hum, &temp);
  bleData.temperature = temp.temperature;
  bleData.humidity = hum.relative_humidity;

  // Logika przeciwroszeniowa (Smart Heater)
  if (bleData.humidity > 98.0) {
    if (!isHighHumidity) {
      isHighHumidity = true;
      highHumidityStartTime = currentMillis;
    } else if (currentMillis - highHumidityStartTime > 30 * 60 * 1000) { // Po 30 min w 98%+
      // Uruchom grzałkę na 1s
      sht4.setHeater(SHT4X_HIGH_HEATER_1S);
      isHeaterActive = true;
      heaterCooldownEnd = currentMillis + 30000; // 30 sekund chłodzenia
      isHighHumidity = false; 
      bitSet(bleData.statusFlags, 4); // Ustaw flagę grzałki do telefonu
      return; 
    }
  } else {
    isHighHumidity = false;
  }

  // --- HD-38 ODCZYT (ANTYKOROZJA) ---
  digitalWrite(PIN_SOIL_VCC, HIGH); // Zasil czujnik tylko na czas odczytu
  delay(15); // Czekaj na ustabilizowanie napięcia w glebie
  long sum = 0;
  for(int i = 0; i < 10; i++) {
    sum += analogRead(PIN_SOIL_ADC);
    delay(2);
  }
  digitalWrite(PIN_SOIL_VCC, LOW); // Wyłącz prąd - koniec elektrolizy!
  
  rawAdc = sum / 10;
  int moisture = map(rawAdc, cfg_soilAdcDry, cfg_soilAdcWet, 0, 100);
  bleData.soilMoisture = constrain(moisture, 0, 100);
}

// ==========================================
// 3. STEROWANIE I ALARMY (Safety Timeout)
// ==========================================
void controlLogic() {
  unsigned long currentMillis = millis();

  // Wiatrak (Histereza)
  bool isFanOn = bitRead(bleData.statusFlags, 0);
  if (bleData.temperature >= cfg_fanOnTemp && !isFanOn) {
    bitSet(bleData.statusFlags, 0); digitalWrite(PIN_RELAY_FAN, HIGH);
  } else if (bleData.temperature <= cfg_fanOffTemp && isFanOn) {
    bitClear(bleData.statusFlags, 0); digitalWrite(PIN_RELAY_FAN, LOW);
  }

  // Bezpieczeństwo i Podlewanie Pulsujące
  if (safetyAlarmActive) {
    digitalWrite(PIN_RELAY_VALVE, LOW); bitClear(bleData.statusFlags, 1);
    bitSet(bleData.statusFlags, 2); // Ustaw flagę Alarm
    return;
  }

  switch (waterState) {
    case IDLE:
      if (bleData.soilMoisture < cfg_soilWaterStart) {
        waterState = WATERING; waterStateTimer = currentMillis; wateringSessionStart = currentMillis;
        bitSet(bleData.statusFlags, 1); digitalWrite(PIN_RELAY_VALVE, HIGH);
      }
      break;
    case WATERING:
      if (currentMillis - waterStateTimer >= cfg_pulseWateringTime) {
        waterState = SOAKING; waterStateTimer = currentMillis;
        bitClear(bleData.statusFlags, 1); digitalWrite(PIN_RELAY_VALVE, LOW);
      }
      if (currentMillis - wateringSessionStart >= cfg_safetyTimeout) safetyAlarmActive = true;
      break;
    case SOAKING:
      if (currentMillis - waterStateTimer >= cfg_pulseSoakingTime) {
        if (bleData.soilMoisture >= cfg_soilWaterStop) waterState = IDLE;
        else {
          waterState = WATERING; waterStateTimer = currentMillis;
          bitSet(bleData.statusFlags, 1); digitalWrite(PIN_RELAY_VALVE, HIGH);
        }
      }
      if (currentMillis - wateringSessionStart >= cfg_safetyTimeout) safetyAlarmActive = true;
      break;
  }
}
#include <ArduinoJson.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// --- BLE UUID ---
#define SERVICE_UUID           "12345678-1234-5678-1234-56789abcdef0"
#define CHAR_STATUS_UUID       "12345678-1234-5678-1234-56789abcdef1" // Pakiety binarne 12-bajtowe
#define CHAR_CMD_UUID          "12345678-1234-5678-1234-56789abcdef2" // Komendy (JSON)
#define CHAR_OTA_DATA_UUID     "12345678-1234-5678-1234-56789abcdef3" // Surowe dane oprogramowania

BLEServer* pServer = NULL;
BLECharacteristic* pStatusChar = NULL;
BLECharacteristic* pCmdChar = NULL;
BLECharacteristic* pOtaDataChar = NULL;

// --- ZMIENNE OTA (SD Staging) ---
File otaFile;
uint16_t expectedChunkIdx = 0;
String expectedMD5 = "";
bool otaModeActive = false;

// ==========================================
// 4. WIELOWĄTKOWOŚĆ - ZAPIS NA SD (Core 0)
// ==========================================
// Ten proces działa w tle. Nawet jeśli SD zatnie się na 500ms,
// BLE i podlewanie (Core 1) działają bez najmniejszego zająknięcia.
void sdLoggingTask(void *pvParameters) {
  for (;;) {
    vTaskDelay(15 * 60 * 1000 / portTICK_PERIOD_MS); // Czekaj 15 minut
    
    if (sdCardReady && !otaModeActive) {
      File file = SD.open("/historia.csv", FILE_APPEND);
      if (file) {
        file.printf("%lu,%.1f,%.1f,%d\n", millis() / 60000, bleData.temperature, bleData.humidity, bleData.soilMoisture);
        file.close();
      }
    }
  }
}

// ==========================================
// 5. OBSŁUGA STAGED OTA (Instalacja z SD)
// ==========================================
void performStagedOTA() {
  Serial.println("Rozpoczynam wgrywanie z karty SD...");
  
  // 1. Zwalniamy RAM wyłączając BLE
  BLEDevice::deinit(true);
  delay(500);

  // 2. Otwieramy plik z karty
  File file = SD.open("/update.bin", FILE_READ);
  if (!file) {
    Serial.println("Błąd: Brak pliku aktualizacji na SD.");
    ESP.restart();
  }

  // 3. Weryfikacja i Flashowanie
  size_t fileSize = file.size();
  if (!Update.begin(fileSize)) {
    Update.printError(Serial);
    ESP.restart();
  }

  Update.setMD5(expectedMD5.c_str());
  
  size_t written = Update.writeStream(file);
  if (written == fileSize) {
    Serial.println("Zapisano! Weryfikacja MD5...");
  } else {
    Serial.println("Błąd zapisu do Flash.");
  }

  if (Update.end()) {
    Serial.println("OTA SUKCES! Restart systemu...");
    file.close();
    SD.remove("/update.bin"); // Sprzątamy
    delay(1000);
    ESP.restart();
  } else {
    Update.printError(Serial);
    Serial.println("MD5 BŁĄD! Aktualizacja odrzucona. Odtwarzam starą wersję.");
    file.close();
    SD.remove("/update.bin");
    delay(2000);
    ESP.restart();
  }
}

// ==========================================
// 6. CALLBACKI BLUETOOTH
// ==========================================
class MyServerCallbacks: public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) {
      isBleConnected = true;
      tone(PIN_BUZZER, 2640, 150); // Odpowiedź piezo (wysoki chime, jak ustaliliśmy)
    }
    void onDisconnect(BLEServer* pServer) {
      isBleConnected = false;
      BLEDevice::startAdvertising();
    }
};

class MyCmdCallbacks: public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pCharacteristic) {
      // W ESP32 v3.x getValue() zwraca Arduino String
      String rxValue = pCharacteristic->getValue();
      if (rxValue.length() == 0) return;
      
      JsonDocument doc;
      if(deserializeJson(doc, rxValue)) return;
      String cmd = doc["cmd"].as<String>();
      
      if (cmd == "ping") {
        pCmdChar->setValue("pong");
        pCmdChar->notify();
      } else if (cmd == "cal_dry") {
        saveSecureCalibration(rawAdc, cfg_soilAdcWet);
        tone(PIN_BUZZER, 1000, 200);
      } else if (cmd == "cal_wet") {
        saveSecureCalibration(cfg_soilAdcDry, rawAdc);
        tone(PIN_BUZZER, 1500, 200);
      } else if (cmd == "restore_cal") {
        saveSecureCalibration(doc["dry"], doc["wet"]);
      } else if (cmd == "ota_start") {
        otaModeActive = true;
        expectedMD5 = doc["md5"].as<String>();
        expectedChunkIdx = 0;
        if (SD.exists("/update.bin")) SD.remove("/update.bin");
        otaFile = SD.open("/update.bin", FILE_WRITE);
      } else if (cmd == "ota_end") {
        if (otaFile) otaFile.close();
      }
    }
};

class OtaDataCallbacks: public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pCharacteristic) {
      if (!otaModeActive || !otaFile) return;

      // Do binarnych danych (OTA) używamy czystych bajtów, żeby uniknąć ucinania tekstu przez bajty NULL
      uint8_t* rxData = pCharacteristic->getData();
      size_t rxLen = pCharacteristic->getLength();

      if (rxLen < 2) return;

      // Dekodowanie nagłówka (2 bajty = Index)
      uint16_t chunkIdx = (rxData[0] & 0xFF) | ((rxData[1] & 0xFF) << 8);

      if (chunkIdx == expectedChunkIdx) {
        // Zapisz Payload (od 3. bajtu) na kartę SD
        otaFile.write(rxData + 2, rxLen - 2);
        expectedChunkIdx++;
      }
    }
};

// ==========================================
// 7. SETUP I GŁÓWNA PĘTLA
// ==========================================
void setup() {
  Serial.begin(115200);
  
  pinMode(PIN_RELAY_FAN, OUTPUT);
  pinMode(PIN_RELAY_VALVE, OUTPUT);
  pinMode(PIN_SOIL_VCC, OUTPUT);
  pinMode(PIN_BUZZER, OUTPUT);
  digitalWrite(PIN_RELAY_FAN, LOW);
  digitalWrite(PIN_RELAY_VALVE, LOW);
  digitalWrite(PIN_SOIL_VCC, LOW);
  digitalWrite(PIN_BUZZER, LOW);

  // Zabezpieczenie Watchdog (Nowe API dla ESP32 v3.x)
  esp_task_wdt_config_t wdt_config = {
      .timeout_ms = WDT_TIMEOUT * 1000,
      .idle_core_mask = (1 << portNUM_PROCESSORS) - 1, 
      .trigger_panic = true
  };
  esp_task_wdt_init(&wdt_config);
  esp_task_wdt_add(NULL);

  loadSecureConfig();

  // Inicjalizacja I2C
  Wire.begin(21, 22);
  if (sht4.begin(&Wire)) {
    sht4.setPrecision(SHT4X_HIGH_PRECISION);
    sht4.setHeater(SHT4X_NO_HEATER);
  }

  // --- AUTO-FORMAT SD CARD ---
  // Jeśli zabrakło prądu i system plików FAT jest uszkodzony,
  // ESP32 automatycznie sformatuje go w locie (parametr 'true' na końcu).
  SPI.begin(18, 19, 23, PIN_SD_CS);
  if (SD.begin(PIN_SD_CS, SPI, 4000000, "/sd", 5, true)) {
    sdCardReady = true;
    bitSet(bleData.statusFlags, 3); // Flaga SD_OK = 1
  } else {
    bitClear(bleData.statusFlags, 3); // Flaga SD_OK = 0 (Telefon zgłosi błąd!)
  }

  // Tworzymy zadanie na Core 0 dla karty SD
  xTaskCreatePinnedToCore(sdLoggingTask, "SDTask", 4096, NULL, 1, NULL, 0);

  // --- BLE SETUP ---
  BLEDevice::init("Szklarnia Dziadka");
  
  // Opcjonalne zwiększenie mocy anteny, żeby zasięg był lepszy
  esp_ble_tx_power_set(ESP_BLE_PWR_TYPE_DEFAULT, ESP_PWR_LVL_P9); 
  esp_ble_tx_power_set(ESP_BLE_PWR_TYPE_ADV, ESP_PWR_LVL_P9);

  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());

  BLEService *pService = pServer->createService(SERVICE_UUID);

  // Charakterystyka Statusu (Tylko Odczyt / Notyfikacje) - 12 Bajtów
  pStatusChar = pService->createCharacteristic(CHAR_STATUS_UUID, BLECharacteristic::PROPERTY_NOTIFY | BLECharacteristic::PROPERTY_READ);
  pStatusChar->addDescriptor(new BLE2902());

  // Charakterystyka Komend
  pCmdChar = pService->createCharacteristic(CHAR_CMD_UUID, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_NOTIFY);
  pCmdChar->setCallbacks(new MyCmdCallbacks());
  pCmdChar->addDescriptor(new BLE2902());

  // Charakterystyka Danych OTA (Write Without Response - Max Speed!)
  pOtaDataChar = pService->createCharacteristic(CHAR_OTA_DATA_UUID, BLECharacteristic::PROPERTY_WRITE_NR);
  pOtaDataChar->setCallbacks(new OtaDataCallbacks());

  pService->start();
  BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  BLEDevice::startAdvertising();
}

void loop() {
  esp_task_wdt_reset(); // "Pies" dostaje jedzenie

  // Jeśli jesteśmy w trybie wgrywania oprogramowania i dostaliśmy flagę końca
  // Uruchamiamy instalację (Bezpieczne w głównej pętli z dala od wątków BLE)
  if (otaModeActive && !otaFile) { 
    performStagedOTA();
  }

  unsigned long currentMillis = millis();

  // Wysłanie 12-bajtowego pakietu do telefonu co 1.5 sekundy
  if (currentMillis - lastSensorRead >= 1500) {
    lastSensorRead = currentMillis;
    
    if (!otaModeActive) { // Nie psujmy pomiarów w trakcie flashowania
      processSensors();
      controlLogic();
    }
    
    if (isBleConnected) {
      // Magia: wysyłamy bezpośrednio zrzut pamięci ze struktury C++ (zero JSONa!)
      pStatusChar->setValue((uint8_t*)&bleData, sizeof(SensorDataPacket));
      pStatusChar->notify();
    }
  }

  // Obsługa piszczenia alarmu bezpieczeństwa
  if (safetyAlarmActive) {
    static unsigned long lastBeep = 0;
    static bool beepState = false;
    if (currentMillis - lastBeep >= 500) {
      lastBeep = currentMillis;
      beepState = !beepState;
      if (beepState) tone(PIN_BUZZER, 2000, 200); 
    }
  }
}