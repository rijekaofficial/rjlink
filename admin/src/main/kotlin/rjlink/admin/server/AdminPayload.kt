package rjlink.admin.server

/**
 * Helpers to encode/decode list payloads inside `Map<String,String>` data of admin packets.
 *
 * The flat-map protocol restricts us to string-to-string maps; rather than introduce a
 * JSON dependency we use indexed keys: `count`, `<prefix>.0.<field>`, `<prefix>.1.<field>`, ...
 *
 * Example for sessions:
 * ```
 * count = "2"
 * s.0.nick = "alice"
 * s.0.admin = "false"
 * s.1.nick = "bob"
 * s.1.admin = "true"
 * ```
 */
internal object AdminPayload {

    /** Encode a homogeneous list of records into a flat string map. */
    fun encode(prefix: String, records: List<Map<String, String>>): Map<String, String> {
        val out = HashMap<String, String>(2 + records.size * 4)
        out["count"] = records.size.toString()
        records.forEachIndexed { i, record ->
            for ((field, value) in record) out["$prefix.$i.$field"] = value
        }
        return out
    }

    /** Reverse of [encode]: pull records out of a payload by the given prefix. */
    fun decode(prefix: String, data: Map<String, String>): List<Map<String, String>> {
        val count = data["count"]?.toIntOrNull() ?: 0
        if (count == 0) return emptyList()
        val records = ArrayList<Map<String, String>>(count)
        val pfx = "$prefix."
        for (i in 0 until count) {
            val recordPrefix = "$pfx$i."
            val record = HashMap<String, String>()
            for ((k, v) in data) {
                if (k.startsWith(recordPrefix)) record[k.substring(recordPrefix.length)] = v
            }
            records.add(record)
        }
        return records
    }
}
