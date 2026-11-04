package com.mg.player.data.parser

import android.util.JsonToken
import com.mg.player.domain.model.ConnectionStatus
import java.io.InputStream

/** Liest aus player_api.php (ohne action) die Verbindungen des Kontos: "active_cons" und "max_connections". */
object XtreamAccountParser {

    /** null = keine verwertbare Antwort (z. B. Anmeldung abgelehnt oder Panel ohne diese Angaben). */
    fun parse(input: InputStream): ConnectionStatus? {
        jsonReader(input).use { reader ->
            if (reader.peek() != JsonToken.BEGIN_OBJECT) return null
            var status: ConnectionStatus? = null
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() == "user_info" && reader.peek() == JsonToken.BEGIN_OBJECT) {
                    status = fromUserInfo(reader.readFlatObject())
                } else {
                    reader.skipValue()
                }
            }
            reader.endObject()
            return status
        }
    }

    private fun fromUserInfo(info: Map<String, String?>): ConnectionStatus? {
        val auth = info.str("auth")
        if (auth != "1" && auth != "true") return null
        val max = info.str("max_connections")?.toDoubleOrNull()?.toInt() ?: return null
        val active = info.str("active_cons")?.toDoubleOrNull()?.toInt() ?: 0
        val expires = info.str("exp_date")?.toLongOrNull()?.takeIf { it > 0 }?.times(1000)
        return ConnectionStatus(active = active.coerceAtLeast(0), max = max, expiresAtMs = expires)
    }
}
