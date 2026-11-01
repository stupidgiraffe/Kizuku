// Adapted from Porter's ADB adapter (a288febb4e42, Apache-2.0). See NOTICE.
package io.github.stupidgiraffe.kizuku.adb

import io.github.stupidgiraffe.kizuku.core.adb.AdbPacket
import java.io.*
import java.net.*
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLException

/** Manager-only transport to this device. This is not a client shell capability. */
class AdbClient(private val host: String, private val port: Int, private val key: AdbKey, private val requireTls: Boolean) : Closeable {
    private val socket=Socket()
    private lateinit var input: DataInputStream
    private lateinit var output: DataOutputStream
    private var tls: SSLSocket?=null
    fun connect() {
        LocalAdb.requireLocal(host,port)
        socket.connect(InetSocketAddress(host,port),5000)
        socket.soTimeout=30000;socket.tcpNoDelay=true
        input=DataInputStream(socket.getInputStream());output=DataOutputStream(socket.getOutputStream())
        write(AdbPacket.text(AdbPacket.CNXN,0x01000000,4096,"host::"))
        var message=AdbPacket.read(input)
        if(message.command()==AdbPacket.STLS) {
            check(android.os.Build.VERSION.SDK_INT>=30) { "TLS ADB requires Android 11" }
            write(AdbPacket(AdbPacket.STLS,0x01000000,0,byteArrayOf()))
            val secure=key.sslContext.socketFactory.createSocket(socket,host,port,true) as SSLSocket
            tls=secure;secure.soTimeout=30000
            try {
                secure.startHandshake()
                input=DataInputStream(secure.inputStream);output=DataOutputStream(secure.outputStream)
                message=AdbPacket.read(input)
            } catch(e:SSLException) {
                val reason=e.message.orEmpty().lowercase()
                if(listOf("certificate_unknown","certificate unknown","bad_certificate","bad certificate","unknown_ca").any(reason::contains)) throw AdbPairingRequiredException(e)
                throw e
            }
        } else if(message.command()==AdbPacket.AUTH) {
            check(!requireTls) { "The connection port did not offer wireless TLS" }
            check(message.arg0()==1 && message.data().size==20) { "Invalid ADB authentication challenge" }
            write(AdbPacket(AdbPacket.AUTH,2,0,key.sign(message.data())))
            message=AdbPacket.read(input)
            if(message.command()==AdbPacket.AUTH) {
                write(AdbPacket(AdbPacket.AUTH,3,0,key.adbPublicKey))
                message=AdbPacket.read(input) // Android's RSA approval prompt may be open.
            }
        } else check(!requireTls) { "Wireless connection did not negotiate TLS" }
        check(message.command()==AdbPacket.CNXN) { "ADB connection was not authorized" }
    }
    fun command(command:String):String {
        require(command.length<=8192)
        write(AdbPacket.text(AdbPacket.OPEN,1,0,command))
        val initial=AdbPacket.read(input)
        check(initial.command()==AdbPacket.OKAY) { "ADB rejected the startup shell" }
        val remote=initial.arg0();check(initial.arg1()==1 && remote!=0)
        val result=ByteArrayOutputStream()
        val deadline=android.os.SystemClock.elapsedRealtime()+20000
        while(android.os.SystemClock.elapsedRealtime()<deadline) {
            val message=AdbPacket.read(input)
            check(message.arg0()==remote && message.arg1()==1) { "Unexpected ADB stream" }
            when(message.command()) {
                AdbPacket.WRTE -> {
                    check(result.size()+message.data().size<=65536) { "Startup output exceeded limit" }
                    result.write(message.data());write(AdbPacket(AdbPacket.OKAY,1,remote,byteArrayOf()))
                }
                AdbPacket.CLSE -> { write(AdbPacket(AdbPacket.CLSE,1,remote,byteArrayOf()));return result.toString("UTF-8") }
                else -> error("Unexpected ADB stream packet")
            }
        }
        error("ADB startup command timed out")
    }
    private fun write(packet:AdbPacket) { output.write(packet.encode());output.flush() }
    override fun close() { runCatching { tls?.close() };runCatching { socket.close() } }
}

object LocalAdb {
    fun requireLocal(host:String,port:Int) {
        require(port in 1..65535) { "Port must be between 1 and 65535" }
        require(host=="localhost" || host.matches(Regex("[0-9.]+")) || (host.contains(':') && host.matches(Regex("[a-fA-F0-9:%a-zA-Z_.-]+")))) { "Enter this device's IP address or localhost" }
        val address=InetAddress.getByName(host)
        val local=address.isLoopbackAddress || NetworkInterface.getNetworkInterfaces().toList().any { iface->iface.inetAddresses.toList().any { it==address } }
        require(local) { "Kizuku activation must connect to this device" }
    }
}

class AdbTransportUnavailable(cause:IOException):IOException(cause)
