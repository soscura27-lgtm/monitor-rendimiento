package com.rendimiento.monitor

import java.net.InetAddress

/** Utilidades de red compartidas por la app y el proceso de shell. */
object NetUtil {

    /**
     * Convierte la direccion en hexadecimal de /proc/net/{tcp,udp}[6] a texto.
     * IPv4: 8 caracteres en orden little-endian. IPv6: 4 palabras de 32 bits, cada una
     * little-endian. Las IPv4 mapeadas en IPv6 (::ffff:a.b.c.d) se devuelven como IPv4.
     */
    fun hexToIp(h: String): String? {
        return try {
            when (h.length) {
                8 -> {
                    val b = ByteArray(4)
                    for (i in 0 until 4) b[i] = h.substring((3 - i) * 2, (3 - i) * 2 + 2).toInt(16).toByte()
                    v4(b, 0)
                }
                32 -> {
                    val b = ByteArray(16)
                    for (w in 0 until 4) {
                        for (k in 0 until 4) {
                            val idx = w * 8 + (3 - k) * 2
                            b[w * 4 + k] = h.substring(idx, idx + 2).toInt(16).toByte()
                        }
                    }
                    var zero = true
                    for (i in 0 until 10) if (b[i].toInt() != 0) zero = false
                    if (zero && b[10] == 0xFF.toByte() && b[11] == 0xFF.toByte()) {
                        v4(b, 12)
                    } else {
                        InetAddress.getByAddress(b).hostAddress
                    }
                }
                else -> null
            }
        } catch (e: Throwable) {
            null
        }
    }

    private fun v4(b: ByteArray, off: Int): String =
        (b[off].toInt() and 0xFF).toString() + "." + (b[off + 1].toInt() and 0xFF) + "." +
            (b[off + 2].toInt() and 0xFF) + "." + (b[off + 3].toInt() and 0xFF)

    fun isIpv6(ip: String): Boolean = ip.indexOf(':') >= 0

    /** Solo sirven servidores publicos: se descartan IPs privadas, locales, multicast y reservadas. */
    fun isPublic(ip: String): Boolean {
        if (isIpv6(ip)) {
            val low = ip.lowercase()
            // NAT64 (64:ff9b::/96): redes solo IPv6 que traducen a IPv4.
            if (low.startsWith("64:ff9b:")) return true
            val first = low.substringBefore(':')
            val v = first.toIntOrNull(16) ?: return false
            // 2000::/3 = direcciones globales.
            return (v and 0xE000) == 0x2000
        }
        val p = ip.split('.')
        if (p.size != 4) return false
        val a = p[0].toIntOrNull() ?: return false
        val b = p[1].toIntOrNull() ?: return false
        return when {
            a == 0 || a == 10 || a == 127 -> false
            a == 169 && b == 254 -> false
            a == 172 && b in 16..31 -> false
            a == 192 && b == 168 -> false
            a == 100 && b in 64..127 -> false
            a >= 224 -> false
            else -> true
        }
    }
}
