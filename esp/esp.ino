#include <ArduinoJson.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <Wire.h>
#include <Adafruit_SHT4x.h>
#include <Preferences.h>
#include <esp_task_wdt.h>
#include <SPI.h>
#include <SD.h>
#include <Update.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include <freertos/semphr.h>

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
struct __attribute__((__packed__)) SensorDataPacket {
  float temperature;    // 4 bajty
  float humidity;       // 4 bajty
  uint16_t soilMoisture;// 2 bajty
  uint8_t statusFlags;  // 1 bajt (Bit 0: Fan, Bit 1: Valve, Bit 2: Alarm, Bit 3: SD_OK, Bit 4: HeaterOn)
  uint8_t padding;      // 1 bajt (Wyrównanie do 32-bitów)
};

// Zabezpieczenie zmiennej współdzielonej
SemaphoreHandle_t dataMutex;
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

// --- BLE UUID ---
#define SERVICE_UUID           "12345678-1234-5678-1234-56789abcdef0"
#define CHAR_STATUS_UUID       "12345678-1234-5678-1234-56789abcdef1"
#define CHAR_CMD_UUID          "12345678-1234-5678-1234-56789abcdef2"
#define CHAR_OTA_DATA_UUID     "12345678-1234-5678-1234-56789abcdef3"

BLEServer* pServer = NULL;
BLECharacteristic* pStatusChar = NULL;
BLECharacteristic* pCmdChar = NULL;
BLECharacteristic* pOtaDataChar = NULL;

// --- ZMIENNE OTA (SD Staging) ---
File otaFile;
uint16_t expectedChunkIdx = 0;
String expectedMD5 = "";
bool otaModeActive = false;
SemaphoreHandle_t sdMutex; // Zabezpieczenie dostępu do karty SD

// ==========================================
// 1. OCHRONA NVRAM (Potrójna nadmiarowość)
// ==========================================
void saveSecureCalibration(int dry, int wet) {
  cfg_soilAdcDry = dry; cfg_soilAdcWet = wet;
  preferences.putInt("adcDry_1", dry); preferences.putInt("adcDry_2", dry); preferences.putInt("adcDry_3", dry);
  preferences.putInt("adcWet_1", wet); preferences.putInt("adcWet_2", wet); preferences.putInt("adcWet_3", wet);
}

void loadSecureConfig() {
  preferences.begin("greenhouse", false);
  
  // Głosowanie większościowe dla AdcDry
  int d1 = preferences.getInt("adcDry_1", 4095);
  int d2 = preferences.getInt("adcDry_2", 4095);
  int d3 = preferences.getInt("adcDry_3", 4095);
  if (d1 == d2) cfg_soilAdcDry = d1;
  else if (d2 == d3) cfg_soilAdcDry = d2;
  else if (d1 == d3) cfg_soilAdcDry = d1;
  else cfg_soilAdcDry = d1;

  // Głosowanie większościowe dla AdcWet (NAPRAWIONO BŁĄD)
  int w1 = preferences.getInt("adcWet_1", 1500);
  int w2 = preferences.getInt("adcWet_2", 1500);
  int w3 = preferences.getInt("adcWet_3", 1500);
  if (w1 == w2) cfg_soilAdcWet = w1;
  else if (w2 == w3) cfg_soilAdcWet = w2;
  else if (w1 == w3) cfg_soilAdcWet = w1;
  else cfg_soilAdcWet = w1;

  // Naprawa w przypadku uszkodzenia
  if (d1 != cfg_soilAdcDry || d2 != cfg_soilAdcDry || d3 != cfg_soilAdcDry ||
      w1 != cfg_soilAdcWet || w2 != cfg_soilAdcWet || w3 != cfg_soilAdcWet) {
    saveSecureCalibration(cfg_soilAdcDry, cfg_soilAdcWet);
  }

  cfg_fanOnTemp = preferences.getFloat("fanOn", 30.0);
  cfg_fanOffTemp = preferences.getFloat("fanOff", 26.0);
  cfg_soilWaterStart = preferences.getInt("soilStart", 40);
  cfg_soilWaterStop = preferences.getInt("soilStop", 70);
}

// ==========================================
// 2. ODCZYTY SENSORÓW (Antykorozja + Grzałka)
// ==========================================
void processSensors() {
  unsigned long currentMillis = millis();

  if (isHeaterActive) {
    // Naprawiono wrap-around millis()
    if (currentMillis - (heaterCooldownEnd - 30000) > 30000) {
      isHeaterActive = false;
      xSemaphoreTake(dataMutex, pdMS_TO_TICKS(100));
      bitClear(bleData.statusFlags, 4);
      xSemaphoreGive(dataMutex);
    } else {
      return;
    }
  }

  sensors_event_t hum, temp;
  sht4.getEvent(&hum, &temp);

  xSemaphoreTake(dataMutex, pdMS_TO_TICKS(100));
  bleData.temperature = temp.temperature;
  bleData.humidity = hum.relative_humidity;

  if (bleData.humidity > 98.0) {
    if (!isHighHumidity) {
      isHighHumidity = true;
      highHumidityStartTime = currentMillis;
    } else if (currentMillis - highHumidityStartTime > 30 * 60 * 1000) {
      sht4.setHeater(SHT4X_HIGH_HEATER_1S);
      isHeaterActive = true;
      heaterCooldownEnd = currentMillis + 30000;
      isHighHumidity = false; 
      bitSet(bleData.statusFlags, 4);
      xSemaphoreGive(dataMutex);
      return; 
    }
  } else {
    isHighHumidity = false;
  }

  digitalWrite(PIN_SOIL_VCC, HIGH);
  delay(15);
  long sum = 0;
  for(int i = 0; i < 10; i++) {
    sum += analogRead(PIN_SOIL_ADC);
    delay(2);
  }
  digitalWrite(PIN_SOIL_VCC, LOW);
  
  rawAdc = sum / 10;
  int moisture = map(rawAdc, cfg_soilAdcDry, cfg_soilAdcWet, 0, 100);
  bleData.soilMoisture = constrain(moisture, 0, 100);
  xSemaphoreGive(dataMutex);
}

// ==========================================
// 3. STEROWANIE I ALARMY (Safety Timeout)
// ==========================================
void controlLogic() {
  unsigned long currentMillis = millis();

  xSemaphoreTake(dataMutex, pdMS_TO_TICKS(100));
  bool isFanOn = bitRead(bleData.statusFlags, 0);
  float currentTemp = bleData.temperature;
  uint16_t currentMoisture = bleData.soilMoisture;
  xSemaphoreGive(dataMutex);

  if (currentTemp >= cfg_fanOnTemp && !isFanOn) {
    xSemaphoreTake(dataMutex, pdMS_TO_TICKS(100));
    bitSet(bleData.statusFlags, 0); digitalWrite(PIN_RELAY_FAN, HIGH);
    xSemaphoreGive(dataMutex);
  } else if (currentTemp <= cfg_fanOffTemp && isFanOn) {
    xSemaphoreTake(dataMutex, pdMS_TO_TICKS(100));
    bitClear(bleData.statusFlags, 0); digitalWrite(PIN_RELAY_FAN, LOW);
    xSemaphoreGive(dataMutex);
  }

  if (safetyAlarmActive) {
    digitalWrite(PIN_RELAY_VALVE, LOW);
    xSemaphoreTake(dataMutex, pdMS_TO_TICKS(100));
    bitClear(bleData.statusFlags, 1);
    bitSet(bleData.statusFlags, 2);
    xSemaphoreGive(dataMutex);
    return;
  }

  switch (waterState) {
    case IDLE:
      if (currentMoisture < cfg_soilWaterStart) {
        waterState = WATERING; waterStateTimer = currentMillis; wateringSessionStart = currentMillis;
        xSemaphoreTake(dataMutex, pdMS_TO_TICKS(100));
        bitSet(bleData.statusFlags, 1); digitalWrite(PIN_RELAY_VALVE, HIGH);
        xSemaphoreGive(dataMutex);
      }
      break;
    case WATERING:
      if (currentMillis - waterStateTimer >= cfg_pulseWateringTime) {
        waterState = SOAKING; waterStateTimer = currentMillis;
        xSemaphoreTake(dataMutex, pdMS_TO_TICKS(100));
        bitClear(bleData.statusFlags, 1); digitalWrite(PIN_RELAY_VALVE, LOW);
        xSemaphoreGive(dataMutex);
      }
      if (currentMillis - wateringSessionStart >= cfg_safetyTimeout) safetyAlarmActive = true;
      break;
    case SOAKING:
      if (currentMillis - waterStateTimer >= cfg_pulseSoakingTime) {
        if (currentMoisture >= cfg_soilWaterStop) waterState = IDLE;
        else {
          waterState = WATERING; waterStateTimer = currentMillis;
          xSemaphoreTake(dataMutex, pdMS_TO_TICKS(100));
          bitSet(bleData.statusFlags, 1); digitalWrite(PIN_RELAY_VALVE, HIGH);
          xSemaphoreGive(dataMutex);
        }
      }
      if (currentMillis - wateringSessionStart >= cfg_safetyTimeout) safetyAlarmActive = true;
      break;
    case TIMEOUT_ERROR:
      break;
  }
}

// ==========================================
// 4. WIELOWĄTKOWOŚĆ - ZAPIS NA SD (Core 0)
// ==========================================
void sdLoggingTask(void *pvParameters) {
  for (;;) {
    vTaskDelay(15 * 60 * 1000 / portTICK_PERIOD_MS);
    
    if (sdCardReady && !otaModeActive) {
      if (xSemaphoreTake(sdMutex, pdMS_TO_TICKS(5000)) == pdTRUE) {
        File file = SD.open("/historia.csv", FILE_APPEND);
        if (file) {
          xSemaphoreTake(dataMutex, pdMS_TO_TICKS(100));
          float t = bleData.temperature;
          float h = bleData.humidity;
          uint16_t m = bleData.soilMoisture;
          xSemaphoreGive(dataMutex);

          file.printf("%lu,%.1f,%.1f,%d\n", millis() / 60000, t, h, m);
          file.close();
        }
        xSemaphoreGive(sdMutex);
      }
    }
  }
}

// ==========================================
// 5. OBSŁUGA STAGED OTA (Instalacja z SD)
// ==========================================
void performStagedOTA() {
  Serial.println("Rozpoczynam wgrywanie z karty SD...");
  
  BLEDevice::deinit(true);
  delay(500);

  xSemaphoreTake(sdMutex, pdMS_TO_TICKS(100));
  File file = SD.open("/update.bin", FILE_READ);
  if (!file) {
    Serial.println("Błąd: Brak pliku aktualizacji na SD.");
    xSemaphoreGive(sdMutex);
    ESP.restart();
  }

  size_t fileSize = file.size();
  if (!Update.begin(fileSize)) {
    Update.printError(Serial);
    xSemaphoreGive(sdMutex);
    ESP.restart();
  }

  Update.setMD5(expectedMD5.c_str());
  size_t written = Update.writeStream(file);

  if (Update.end()) {
    Serial.println("OTA SUKCES! Restart systemu...");
    file.close();
    SD.remove("/update.bin");
    delay(1000);
    ESP.restart();
  } else {
    Update.printError(Serial);
    Serial.println("MD5 BŁĄD! Aktualizacja odrzucona.");
    file.close();
    SD.remove("/update.bin");
    delay(2000);
    ESP.restart();
  }
  xSemaphoreGive(sdMutex);
}

// ==========================================
// 6. CALLBACKI BLUETOOTH
// ==========================================
class MyServerCallbacks: public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) {
      isBleConnected = true;
      tone(PIN_BUZZER, 2640, 150);
    }
    void onDisconnect(BLEServer* pServer) {
      isBleConnected = false;
      BLEDevice::startAdvertising();
    }
};

class MyCmdCallbacks: public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pCharacteristic) {
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
      } else if (cmd == "set_presets") {
        cfg_fanOnTemp = doc["fanOn"];
        cfg_fanOffTemp = doc["fanOff"];
        cfg_soilWaterStart = doc["soilStart"];
        cfg_soilWaterStop = doc["soilStop"];
        preferences.putFloat("fanOn", cfg_fanOnTemp);
        preferences.putFloat("fanOff", cfg_fanOffTemp);
        preferences.putInt("soilStart", cfg_soilWaterStart);
        preferences.putInt("soilStop", cfg_soilWaterStop);
        tone(PIN_BUZZER, 2000, 100);
      } else if (cmd == "force_heater") {
        sht4.setHeater(SHT4X_HIGH_HEATER_1S);
        isHeaterActive = true;
        heaterCooldownEnd = millis() + 30000;
        xSemaphoreTake(dataMutex, pdMS_TO_TICKS(100));
        bitSet(bleData.statusFlags, 4);
        xSemaphoreGive(dataMutex);
      } else if (cmd == "reset_alarm") {
        safetyAlarmActive = false;
        xSemaphoreTake(dataMutex, pdMS_TO_TICKS(100));
        bitClear(bleData.statusFlags, 2);
        xSemaphoreGive(dataMutex);
        tone(PIN_BUZZER, 1000, 100);
      } else if (cmd == "force_relay") {
        String relay = doc["relay"].as<String>();
        bool state = doc["state"].as<bool>();
        if (relay == "fan") {
          digitalWrite(PIN_RELAY_FAN, state ? HIGH : LOW);
          xSemaphoreTake(dataMutex, pdMS_TO_TICKS(100));
          if (state) bitSet(bleData.statusFlags, 0); else bitClear(bleData.statusFlags, 0);
          xSemaphoreGive(dataMutex);
        } else if (relay == "valve") {
          digitalWrite(PIN_RELAY_VALVE, state ? HIGH : LOW);
          xSemaphoreTake(dataMutex, pdMS_TO_TICKS(100));
          if (state) bitSet(bleData.statusFlags, 1); else bitClear(bleData.statusFlags, 1);
          xSemaphoreGive(dataMutex);
        }
      } else if (cmd == "ota_start") {
        otaModeActive = true;
        expectedMD5 = doc["md5"].as<String>();
        expectedChunkIdx = 0;
        xSemaphoreTake(sdMutex, pdMS_TO_TICKS(100));
        if (SD.exists("/update.bin")) SD.remove("/update.bin");
        otaFile = SD.open("/update.bin", FILE_WRITE);
        xSemaphoreGive(sdMutex);
      } else if (cmd == "ota_end") {
        xSemaphoreTake(sdMutex, pdMS_TO_TICKS(100));
        if (otaFile) otaFile.close();
        xSemaphoreGive(sdMutex);
      }
    }
};

class OtaDataCallbacks: public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pCharacteristic) {
      if (!otaModeActive || !otaFile) return;

      uint8_t* rxData = pCharacteristic->getData();
      size_t rxLen = pCharacteristic->getLength();

      if (rxLen < 2) return;

      uint16_t chunkIdx = (rxData[0] & 0xFF) | ((rxData[1] & 0xFF) << 8);

      // Zmienione WWR (Write Without Response) - aplikacja może retransmitować zgubiony pakiet
      if (chunkIdx == expectedChunkIdx) {
        xSemaphoreTake(sdMutex, pdMS_TO_TICKS(100));
        otaFile.write(rxData + 2, rxLen - 2);
        xSemaphoreGive(sdMutex);
        expectedChunkIdx++;

        // POTWIERDZENIE (ACK) co 16 pakietów dla stabilności transferu
        if (chunkIdx % 16 == 0) {
            String ack = "ack:" + String(chunkIdx);
            pCmdChar->setValue(ack.c_str());
            pCmdChar->notify();
        }
      }
    }
};

// ==========================================
// 7. SETUP I GŁÓWNA PĘTLA
// ==========================================
void setup() {
  Serial.begin(115200);
  
  dataMutex = xSemaphoreCreateMutex();
  sdMutex = xSemaphoreCreateMutex();

  pinMode(PIN_RELAY_FAN, OUTPUT);
  pinMode(PIN_RELAY_VALVE, OUTPUT);
  pinMode(PIN_SOIL_VCC, OUTPUT);
  pinMode(PIN_BUZZER, OUTPUT);
  digitalWrite(PIN_RELAY_FAN, LOW);
  digitalWrite(PIN_RELAY_VALVE, LOW);
  digitalWrite(PIN_SOIL_VCC, LOW);
  digitalWrite(PIN_BUZZER, LOW);

  esp_task_wdt_config_t wdt_config = {
      .timeout_ms = WDT_TIMEOUT * 1000,
      .idle_core_mask = (1 << portNUM_PROCESSORS) - 1, 
      .trigger_panic = true
  };
  esp_task_wdt_init(&wdt_config);
  esp_task_wdt_add(NULL);

  loadSecureConfig();

  Wire.begin(21, 22);
  if (sht4.begin(&Wire)) {
    sht4.setPrecision(SHT4X_HIGH_PRECISION);
    sht4.setHeater(SHT4X_NO_HEATER);
  }

  SPI.begin(18, 19, 23, PIN_SD_CS);
  if (SD.begin(PIN_SD_CS, SPI, 4000000, "/sd", 5, true)) {
    sdCardReady = true;
    xSemaphoreTake(dataMutex, pdMS_TO_TICKS(100));
    bitSet(bleData.statusFlags, 3);
    xSemaphoreGive(dataMutex);
  } else {
    xSemaphoreTake(dataMutex, pdMS_TO_TICKS(100));
    bitClear(bleData.statusFlags, 3);
    xSemaphoreGive(dataMutex);
  }

  xTaskCreatePinnedToCore(sdLoggingTask, "SDTask", 4096, NULL, 1, NULL, 0);

  BLEDevice::init("Szklarnia Dziadka");
  esp_ble_tx_power_set(ESP_BLE_PWR_TYPE_DEFAULT, ESP_PWR_LVL_P9); 
  esp_ble_tx_power_set(ESP_BLE_PWR_TYPE_ADV, ESP_PWR_LVL_P9);

  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());

  BLEService *pService = pServer->createService(SERVICE_UUID);

  pStatusChar = pService->createCharacteristic(CHAR_STATUS_UUID, BLECharacteristic::PROPERTY_NOTIFY | BLECharacteristic::PROPERTY_READ);
  pStatusChar->addDescriptor(new BLE2902());

  pCmdChar = pService->createCharacteristic(CHAR_CMD_UUID, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_NOTIFY);
  pCmdChar->setCallbacks(new MyCmdCallbacks());
  pCmdChar->addDescriptor(new BLE2902());

  pOtaDataChar = pService->createCharacteristic(CHAR_OTA_DATA_UUID, BLECharacteristic::PROPERTY_WRITE_NR);
  pOtaDataChar->setCallbacks(new OtaDataCallbacks());

  pService->start();
  BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  BLEDevice::startAdvertising();
}

void loop() {
  esp_task_wdt_reset();

  xSemaphoreTake(sdMutex, pdMS_TO_TICKS(100));
  bool isFileOpen = (otaFile == true);
  xSemaphoreGive(sdMutex);

  if (otaModeActive && !isFileOpen) {
    performStagedOTA();
  }

  unsigned long currentMillis = millis();

  if (currentMillis - lastSensorRead >= 1500) {
    lastSensorRead = currentMillis;
    
    if (!otaModeActive) {
      processSensors();
      controlLogic();
    }
    
    if (isBleConnected) {
      xSemaphoreTake(dataMutex, pdMS_TO_TICKS(100));
      pStatusChar->setValue((uint8_t*)&bleData, sizeof(SensorDataPacket));
      xSemaphoreGive(dataMutex);
      pStatusChar->notify();
    }
  }

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
