package com.abrogani.servetorr.net

import com.abrogani.servetorr.net.Transport.CELLULAR
import com.abrogani.servetorr.net.Transport.ETHERNET
import com.abrogani.servetorr.net.Transport.OTHER
import com.abrogani.servetorr.net.Transport.VPN
import com.abrogani.servetorr.net.Transport.WIFI
import org.junit.Test
import strikt.api.expectThat
import strikt.assertions.isFalse
import strikt.assertions.isTrue

class WifiCheckTest {
    @Test
    fun `wifi and ethernet are allowed`() {
        expectThat(WifiCheck.isOnWifi(setOf(WIFI), emptyList())).isTrue()
        expectThat(WifiCheck.isOnWifi(setOf(ETHERNET), emptyList())).isTrue()
    }

    @Test
    fun `cellular, other transports and no network are not`() {
        expectThat(WifiCheck.isOnWifi(setOf(CELLULAR), listOf(setOf(WIFI)))).isFalse()
        expectThat(WifiCheck.isOnWifi(setOf(OTHER), emptyList())).isFalse()
        expectThat(WifiCheck.isOnWifi(null, listOf(setOf(WIFI)))).isFalse()
    }

    @Test
    fun `a vpn reporting its underlying transport follows it`() {
        expectThat(WifiCheck.isOnWifi(setOf(VPN, WIFI), emptyList())).isTrue()
        expectThat(WifiCheck.isOnWifi(setOf(VPN, ETHERNET), emptyList())).isTrue()
        expectThat(WifiCheck.isOnWifi(setOf(VPN, CELLULAR), listOf(setOf(WIFI)))).isFalse()
        expectThat(WifiCheck.isOnWifi(setOf(VPN, WIFI, CELLULAR), emptyList())).isTrue()
    }

    @Test
    fun `a bare vpn counts as wifi only when a wifi or ethernet network is up underneath`() {
        expectThat(WifiCheck.isOnWifi(setOf(VPN), listOf(setOf(VPN), setOf(WIFI)))).isTrue()
        expectThat(WifiCheck.isOnWifi(setOf(VPN), listOf(setOf(CELLULAR), setOf(ETHERNET)))).isTrue()
        expectThat(WifiCheck.isOnWifi(setOf(VPN), listOf(setOf(VPN), setOf(CELLULAR)))).isFalse()
        expectThat(WifiCheck.isOnWifi(setOf(VPN), listOf(setOf(VPN, WIFI)))).isFalse()
        expectThat(WifiCheck.isOnWifi(setOf(VPN), emptyList())).isFalse()
    }
}
