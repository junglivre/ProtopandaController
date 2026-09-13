package gay.protopanda.controller

import android.content.Context
import java.util.UUID

data class BleIdentity(
    val serviceUuid: UUID,
    val readWriteUuid: UUID,
    val notifyUuid: UUID
) {
    companion object {
        private const val PREFERENCES = "ble_identity"
        private const val SERVICE_UUID = "service_uuid"
        private const val READ_WRITE_UUID = "read_write_uuid"
        private const val NOTIFY_UUID = "notify_uuid"

        val DEFAULT = BleIdentity(
            serviceUuid = UUID.fromString("d4d31337-c4c1-c2c3-b4b3-b2b1a4a3a2a1"),
            readWriteUuid = UUID.fromString("d4d3fafb-c4c1-c2c3-b4b3-b2b1a4a3a2a1"),
            notifyUuid = UUID.fromString("d4d3afaf-c4c1-c2c3-b4b3-b2b1a4a3a2a1")
        )

        fun load(context: Context): BleIdentity {
            val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            return BleIdentity(
                serviceUuid = UUID.fromString(prefs.getString(SERVICE_UUID, DEFAULT.serviceUuid.toString())),
                readWriteUuid = UUID.fromString(prefs.getString(READ_WRITE_UUID, DEFAULT.readWriteUuid.toString())),
                notifyUuid = UUID.fromString(prefs.getString(NOTIFY_UUID, DEFAULT.notifyUuid.toString()))
            )
        }

        fun save(context: Context, identity: BleIdentity) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putString(SERVICE_UUID, identity.serviceUuid.toString())
                .putString(READ_WRITE_UUID, identity.readWriteUuid.toString())
                .putString(NOTIFY_UUID, identity.notifyUuid.toString())
                .apply()
        }
    }
}
