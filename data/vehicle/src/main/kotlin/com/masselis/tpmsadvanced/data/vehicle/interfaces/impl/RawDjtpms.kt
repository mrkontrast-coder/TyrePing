package com.masselis.tpmsadvanced.data.vehicle.interfaces.impl

import android.bluetooth.le.ScanResult
import android.os.ParcelUuid
import androidx.core.util.size
import com.masselis.tpmsadvanced.core.common.now
import com.masselis.tpmsadvanced.data.vehicle.model.Pressure.CREATOR.kpa
import com.masselis.tpmsadvanced.data.vehicle.model.ScannerRecord
import com.masselis.tpmsadvanced.data.vehicle.model.SensorBattery
import com.masselis.tpmsadvanced.data.vehicle.model.Temperature.CREATOR.celsius
import com.masselis.tpmsadvanced.data.vehicle.model.Tyre
import java.util.UUID.fromString
import kotlin.math.roundToInt

@ConsistentCopyVisibility
@Suppress("MagicNumber")
internal data class RawDjtpms private constructor(
    private val rssi: Int,
    private val manufacturerData: ByteArray,
) : Raw {

    // Last 3 bytes of the 6-byte MAC embedded at manufacturerData[6..11]
    fun id() = ((manufacturerData[9].toInt() and 0xFF) shl 16) or
        ((manufacturerData[10].toInt() and 0xFF) shl 8) or
        (manufacturerData[11].toInt() and 0xFF)

    fun pressure() = (
        ((manufacturerData[2].toInt() and 0xFF) shl 8) or
            (manufacturerData[3].toInt() and 0xFF)
        )
        .minus(101)
        .coerceAtLeast(0)
        .toFloat()
        .kpa

    // Signed int8 in Celsius
    fun temperature() = manufacturerData[1].toInt()
        .toFloat()
        .celsius

    // Returns 2.9 for 2.9 volts (raw 0x1D = 29)
    fun voltage() = (manufacturerData[0].toInt() and 0xFF) * 0.1f

    override fun asTyre(): ScannerRecord = Tyre.Unlocated(
        now(),
        rssi,
        id(),
        pressure(),
        temperature(),
        voltage().times(10f).roundToInt().toUShort(),
        false,
        SensorBattery.Voltage(voltage()),
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RawDjtpms

        if (rssi != other.rssi) return false
        return manufacturerData.contentEquals(other.manufacturerData)
    }

    override fun hashCode(): Int {
        var result = rssi
        result = 31 * result + manufacturerData.contentHashCode()
        return result
    }

    companion object {
        internal val SERVICE_UUID = ParcelUuid(fromString("0000ffe0-0000-1000-8000-00805f9b34fb"))

        private const val MIN_MANUFACTURER_DATA_LENGTH = 12

        @Suppress("ReturnCount")
        operator fun invoke(scanResult: ScanResult): RawDjtpms? {
            val scanRecord = scanResult.scanRecord ?: return null
            if (scanRecord.deviceName != "DJTPMS") return null
            if (scanRecord.serviceUuids?.contains(SERVICE_UUID)?.not() ?: true) return null
            // Company ID changes dynamically (observed 0x0a00 and 0x0000); read the payload regardless of key.
            val manufacturerData = scanRecord.manufacturerSpecificData
                ?.takeIf { it.size > 0 }
                ?.valueAt(0)
                ?.takeIf { it.size >= MIN_MANUFACTURER_DATA_LENGTH }
                ?: return null

            return RawDjtpms(scanResult.rssi, manufacturerData)
        }
    }
}
