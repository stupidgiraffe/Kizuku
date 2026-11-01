package io.github.stupidgiraffe.kizuku.adb

import android.os.Build
import javax.net.ssl.SSLSocket

internal object TlsExporter {
    fun export(socket: SSLSocket,label:String,size:Int):ByteArray {
        if(Build.VERSION.SDK_INT<30)error("Wireless pairing requires Android 11 or later")
        if(Build.VERSION.SDK_INT>=31) return requireNotNull(android.net.ssl.SSLSockets.exportKeyingMaterial(socket,label,null,size))
        // Android 11 alone predates the public API. Exempt only its platform TLS implementation.
        org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("Lcom/android/org/conscrypt/")
        return Class.forName("com.android.org.conscrypt.Conscrypt")
            .getDeclaredMethod("exportKeyingMaterial",SSLSocket::class.java,String::class.java,ByteArray::class.java,Int::class.javaPrimitiveType)
            .invoke(null,socket,label,null,size) as ByteArray
    }
}
