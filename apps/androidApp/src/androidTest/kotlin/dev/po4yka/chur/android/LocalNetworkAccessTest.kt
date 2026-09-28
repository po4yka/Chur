package dev.po4yka.chur.android

import android.net.IpPrefix
import android.net.RouteInfo
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The on-link route check behind the local network request, `ANDROID.md` §24. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 37)
class LocalNetworkAccessTest {
    private val any = InetAddress.getByName("::")
    private val publicServer = InetAddress.getByName("2606:4700::1111")

    @Test
    fun aDefaultRouteWithNoGatewayIsNotOnLink() {
        // A cellular link can report its default route as `::/0` via `::`.
        val cellularDefault = route(IpPrefix(any, 0), any)
        assertTrue(cellularDefault.isDefaultRoute)
        assertFalse(cellularDefault.hasGateway())
        assertTrue(cellularDefault.matches(publicServer))

        assertFalse(onLink(listOf(cellularDefault), publicServer))
        assertFalse(onLink(listOf(route(IpPrefix(any, 0), InetAddress.getByName("fe80::1"))), publicServer))
    }

    @Test
    fun aPrefixRouteWithNoGatewayIsOnLink() {
        val link = route(IpPrefix(InetAddress.getByName("2001:db8:1::"), 64), any)
        assertEquals(64, link.destination.prefixLength)

        assertTrue(onLink(listOf(link), InetAddress.getByName("2001:db8:1::5")))
        assertFalse(onLink(listOf(link), publicServer))
    }

    /** Builds a route as the system sends it, since [RouteInfo] has no public constructor. */
    private fun route(destination: IpPrefix, gateway: InetAddress): RouteInfo {
        val parcel = Parcel.obtain()
        try {
            parcel.writeParcelable(destination, 0)
            parcel.writeByteArray(gateway.address)
            parcel.writeString("rmnet0")
            parcel.writeInt(RouteInfo.RTN_UNICAST)
            parcel.writeInt(0)
            parcel.setDataPosition(0)
            return RouteInfo.CREATOR.createFromParcel(parcel).also {
                assertEquals(destination, it.destination)
                assertEquals(RouteInfo.RTN_UNICAST, it.type)
            }
        } finally {
            parcel.recycle()
        }
    }
}
