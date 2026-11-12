package com.example.smbsync

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

class FoundHost(val ip: String, val name: String?)

/** Finds computers on the current network that have file sharing (SMB, port 445) switched on. */
object Discovery {

    /** The phone's own IPv4 address on the active network, or null when offline. */
    private fun ownAddress(ctx: Context): Inet4Address? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val props = cm.getLinkProperties(cm.activeNetwork ?: return null) ?: return null
        return props.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>().firstOrNull { !it.isLoopbackAddress }
    }

    /** Scans the 254 addresses around the phone's own address. Throws if there is no network. */
    suspend fun scan(ctx: Context): List<FoundHost> = withContext(Dispatchers.IO) {
        val own = ownAddress(ctx) ?: throw IllegalStateException("Not connected to a network. Connect to your Wi-Fi first.")
        val b = own.address
        val prefix = "${b[0].toInt() and 0xff}.${b[1].toInt() and 0xff}.${b[2].toInt() and 0xff}."
        val self = own.hostAddress
        coroutineScope {
            (1..254).map { n ->
                async {
                    val ip = prefix + n
                    if (ip != self && smbOpen(ip)) FoundHost(ip, netbiosName(ip)) else null
                }
            }.awaitAll().filterNotNull()
        }
    }

    private fun smbOpen(ip: String): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, 445), 600) }
        true
    } catch (_: Exception) {
        false
    }

    /** Asks the machine for its Windows computer name (NetBIOS node status). Null if it doesn't answer. */
    private fun netbiosName(ip: String): String? = try {
        DatagramSocket().use { s ->
            s.soTimeout = 700
            val q = ByteArray(50)
            q[1] = 1                       // transaction id
            q[5] = 1                       // one question
            q[12] = 0x20                   // encoded name length
            q[13] = 'C'.code.toByte()      // "*" encoded as CK, padded with AA
            q[14] = 'K'.code.toByte()
            for (i in 15 until 45) q[i] = 'A'.code.toByte()
            q[47] = 0x21                   // NBSTAT
            q[49] = 1                      // class IN
            s.send(DatagramPacket(q, q.size, InetAddress.getByName(ip), 137))
            val buf = ByteArray(1024)
            val p = DatagramPacket(buf, buf.size)
            s.receive(p)
            val count = buf[56].toInt() and 0xff
            var found: String? = null
            for (i in 0 until count) {
                val off = 57 + i * 18
                if (off + 18 > p.length) break
                val suffix = buf[off + 15].toInt()
                val group = buf[off + 16].toInt() and 0x80 != 0
                if (suffix == 0 && !group) {
                    found = String(buf, off, 15, Charsets.US_ASCII).trim()
                    break
                }
            }
            found?.takeIf { it.isNotEmpty() }
        }
    } catch (_: Exception) {
        null
    }
}
