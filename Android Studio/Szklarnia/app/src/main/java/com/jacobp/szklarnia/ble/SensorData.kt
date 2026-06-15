package com.jacobp.szklarnia.ble

data class SensorData(
    val temperature: Float = 0f,
    val humidity: Float = 0f,
    val soilMoisture: Int = 0,
    val isFanOn: Boolean = false,
    val isValveOn: Boolean = false,
    val isAlarmActive: Boolean = false,
    val isSdOk: Boolean = true,
    val isHeaterOn: Boolean = false
) {
    companion object {
        fun fromBytes(bytes: ByteArray): SensorData {
            if (bytes.size < 12) return SensorData()

            // Konwersja ByteArray na binarne typy C++
            val tempRaw = (bytes[0].toInt() and 0xFF) or
                    ((bytes[1].toInt() and 0xFF) shl 8) or
                    ((bytes[2].toInt() and 0xFF) shl 16) or
                    ((bytes[3].toInt() and 0xFF) shl 24)
            val humRaw = (bytes[4].toInt() and 0xFF) or
                    ((bytes[5].toInt() and 0xFF) shl 8) or
                    ((bytes[6].toInt() and 0xFF) shl 16) or
                    ((bytes[7].toInt() and 0xFF) shl 24)

            val temp = Float.fromBits(tempRaw)
            val hum = Float.fromBits(humRaw)

            val soil = (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8)
            val flags = bytes[10].toInt() and 0xFF

            return SensorData(
                temperature = temp,
                humidity = hum,
                soilMoisture = soil,
                isFanOn = (flags and (1 shl 0)) != 0,
                isValveOn = (flags and (1 shl 1)) != 0,
                isAlarmActive = (flags and (1 shl 2)) != 0,
                isSdOk = (flags and (1 shl 3)) != 0,
                isHeaterOn = (flags and (1 shl 4)) != 0
            )
        }
    }
}
