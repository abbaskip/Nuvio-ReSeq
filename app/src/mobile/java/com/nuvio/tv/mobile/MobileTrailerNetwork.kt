package com.nuvio.tv.mobile

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.staticCompositionLocalOf
import com.nuvio.tv.data.trailer.TrailerNetworkAccess
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

internal val LocalTrailerNetwork = staticCompositionLocalOf<MobileTrailerNetwork> { error("Missing mobile trailer policy") }
internal const val TRAILER_DATA_MESSAGE = "Trailers are off on mobile data. Connect to Wi-Fi to play one."

@Singleton
internal class MobileTrailerNetwork @Inject constructor(@ApplicationContext context: Context) : TrailerNetworkAccess {
    private val prefs = context.getSharedPreferences("mobile_video_preferences", Context.MODE_PRIVATE)
    private val networks = context.getSystemService(ConnectivityManager::class.java)
    private val preference = MutableStateFlow(prefs.getBoolean("trailers_on_mobile_data", true))
    val allowMobileData = preference.asStateFlow()
    private var defaultNetwork: Network? = null
    private var onFixedNetwork = false
    private val allowed = MutableStateFlow(preference.value)
    override val playbackAllowed = allowed.asStateFlow()

    init {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network): Unit = synchronized(this@MobileTrailerNetwork) {
                if (network != defaultNetwork) updateRoute(network, false)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities): Unit =
                synchronized(this@MobileTrailerNetwork) {
                    if (network == defaultNetwork) updateRoute(network, capabilities.allowsTrailers())
                }

            override fun onLost(network: Network): Unit = synchronized(this@MobileTrailerNetwork) {
                if (network == defaultNetwork) updateRoute(null, false)
            }
        }
        synchronized(this) {
            networks.registerDefaultNetworkCallback(callback)
            val initial = networks.activeNetwork
            updateRoute(initial, initial?.let(networks::getNetworkCapabilities).allowsTrailers())
        }
    }

    @Synchronized fun setAllowMobileData(value: Boolean) {
        prefs.edit().putBoolean("trailers_on_mobile_data", value).apply()
        preference.value = value
        allowed.value = value || onFixedNetwork
    }

    override fun allowsPlaybackNow(): Boolean {
        if (preference.value) return true
        val active = networks.activeNetwork ?: return false
        return allowed.value && networks.getNetworkCapabilities(active).allowsTrailers()
    }

    private fun NetworkCapabilities?.allowsTrailers(): Boolean {
        if (this == null) return false
        // Android includes the underlying transports on VPN networks. An unknown route
        // stays blocked until the default network is positively identified.
        return !hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            (hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }

    private fun updateRoute(network: Network?, fixed: Boolean) {
        defaultNetwork = network
        onFixedNetwork = fixed
        allowed.value = preference.value || fixed
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class MobileTrailerNetworkModule {
    @Binds abstract fun access(network: MobileTrailerNetwork): TrailerNetworkAccess
}
