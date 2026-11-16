package com.lagradost.cloudstream4.net

import com.lagradost.cloudstream4.core.http.AddressCheckedSocketFactory
import com.lagradost.cloudstream4.download.EngineDownloadSwitch
import com.lagradost.cloudstream4.media.download.isPublicAddress
import com.lagradost.nicehttp.Requests
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient

/**
 * `BUG-109`: the clients Android fetches a page's content with, which never connect to a private address.
 *
 * A plugin passes on the urls it scrapes: posters, subtitles, audio tracks, files to download. A hostile or compromised
 * site can write `http://192.168.1.1/…` there, and without this the app fetched it from the user's own network: a blind
 * request the site has no other way to make. The owner decided, 2026-11-15, to block it, with no setting: content on the
 * user's own network (a home media server) no longer loads.
 *
 * Each client is derived from the one it replaces, so it keeps that client's DNS setting, cache and interceptors. The
 * plugins' own client, `app.baseClient`, is not changed: a plugin is code in this process and has the network anyway.
 *
 * The address is checked at the socket, before it connects ([AddressCheckedSocketFactory], as on the Mac since
 * `BUG-105`): an address written as one, every address a name resolves to, and each redirect's. With a proxy the socket
 * would go to the proxy and the target's address would never be seen, so these clients use none.
 *
 * They also have their own connection pool. OkHttp hands a pooled connection to any client whose `Address` matches, and
 * that comparison leaves out the socket factory (`Address.equalsNonHost`, OkHttp 5.5.0). So a connection the plugin's own
 * client had opened to `192.168.1.1:80` could serve a content request for the same address and never pass the check.
 *
 * Since `BUG-112` the repository fetches use them too (`RepositoryManager`): a repository's `repo.json`, its plugin
 * lists and each plugin's file are urls the repository chose, so a repository on the user's own network no longer loads.
 *
 * A device test that serves from this device turns the check off through `EngineDownloadSwitch`'s existing switch, so
 * no hook of this object's is part of `classes.jar`.
 */
internal object ContentClients {
    private val publicOnly: (InetSocketAddress) -> Boolean = { destination ->
        EngineDownloadSwitch.allowPrivateAddressesForTests || isPublicAddress(destination.address.address)
    }

    /** Shared by the checked clients, never with the clients they derive from. */
    private val pool = ConnectionPool()

    /** [base], connecting only to public addresses, with no proxy. */
    fun checked(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .socketFactory(AddressCheckedSocketFactory(publicOnly))
        .proxy(Proxy.NO_PROXY)
        .connectionPool(pool)
        .build()

    /** [base]'s helper over a [checked] client, with its default headers and parser. `verify = false` keeps the check. */
    fun requests(base: Requests): Requests =
        Requests(checked(base.baseClient), responseParser = base.responseParser).apply { defaultHeaders = base.defaultHeaders }
}
