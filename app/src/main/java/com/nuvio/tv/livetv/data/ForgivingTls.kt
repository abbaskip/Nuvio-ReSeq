package com.nuvio.tv.livetv.data

import android.util.Log
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Downloads of your own playlists and guides, the way other IPTV players do them: a server whose
 * certificate this TV can't check ("Chain validation failed": older boxes don't know newer
 * certificate authorities, or the provider's certificate is out of date or self-made) still works.
 * The TV's own check runs first; only when it fails is the download let through, with a line in
 * the Start-Up Report. The server's name is still checked as usual. Only for the addresses you
 * typed in yourself: nothing of Nuvio's own uses this.
 */
internal object ForgivingTls {
    private const val TAG = "ForgivingTls"
    private val reported = java.util.Collections.synchronizedSet(HashSet<String>())

    private val system: X509TrustManager? = runCatching {
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
    }.getOrNull()

    val trustManager: X509TrustManager? = system?.let { sys ->
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
                sys.checkClientTrusted(chain, authType)

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                try {
                    sys.checkServerTrusted(chain, authType)
                } catch (e: CertificateException) {
                    val who = chain?.firstOrNull()?.subjectX500Principal?.name.orEmpty()
                    Log.w(TAG, "Certificate not trusted by this TV, allowed for a playlist or guide: $who", e)
                    // Once per server, not on every connection.
                    if (reported.add(who)) runCatching {
                        com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
                            "A playlist or guide server's certificate couldn't be checked on this TV (${e.message ?: "chain validation failed"}); downloaded anyway: $who"
                        )
                    }
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = sys.acceptedIssuers
        }
    }

    val socketFactory: SSLSocketFactory? = trustManager?.let { tm ->
        runCatching {
            SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(tm), SecureRandom()) }.socketFactory
        }.getOrNull()
    }
}
