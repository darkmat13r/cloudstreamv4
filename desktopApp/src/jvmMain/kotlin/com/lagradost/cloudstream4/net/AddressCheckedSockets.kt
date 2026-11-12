package com.lagradost.cloudstream4.net

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/**
 * `BUG-105`: sockets that refuse, before they connect, any destination [allow] does not accept.
 *
 * The Mac's clients for a URL a plugin chose check an address twice: a DNS filter on what a name resolves to, and a
 * network interceptor on the address connected to. OkHttp asks the DNS filter only for a name. An address written as
 * one (`127.0.0.1`, `[::ffff:127.0.0.1]`, `2130706433`) is never looked up, so only the interceptor saw it, and by then
 * the connection was open and a TLS hello had reached whatever listened there. The image was refused; the probe was not.
 *
 * Every connection OkHttp makes goes through its socket factory, to the destination itself: a written address, each
 * resolved one, and each redirect's. So this is where the address is checked first. The DNS filter and the interceptor
 * stay, as layers behind it.
 *
 * With a proxy the destination would be the proxy, never the target, so the clients that use this set no proxy
 * (`Proxy.NO_PROXY`, `BUG-88`).
 *
 * @param refused what a refusal raises, so a client that names its own refusal (the media gateway counts its own
 *   type) sees this one as one of its own.
 */
internal class AddressCheckedSocketFactory(
    private val allow: (InetSocketAddress) -> Boolean,
    private val refused: () -> IOException = ::RefusedDestination,
) : SocketFactory() {

    override fun createSocket(): Socket = AddressCheckedSocket(allow, refused)

    override fun createSocket(host: String, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket().apply {
            bind(InetSocketAddress(localHost, localPort))
            connect(InetSocketAddress(host, port))
        }

    override fun createSocket(host: InetAddress, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        createSocket().apply {
            bind(InetSocketAddress(localAddress, localPort))
            connect(InetSocketAddress(address, port))
        }
}

/** Raised before connecting to an address the check refuses. Fixed text: never the address, which a plugin chose. */
internal class RefusedDestination : IOException("refused: not a public address")

private class AddressCheckedSocket(
    private val allow: (InetSocketAddress) -> Boolean,
    private val refused: () -> IOException,
) : Socket() {
    override fun connect(endpoint: SocketAddress?) = connect(endpoint, 0)

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        // An unresolved name would be resolved by the socket itself, past every check: refused, as is anything else.
        val destination = endpoint as? InetSocketAddress
        if (destination == null || destination.isUnresolved || !allow(destination)) {
            close()
            throw refused()
        }
        super.connect(destination, timeout)
    }
}
