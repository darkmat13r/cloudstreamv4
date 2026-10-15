package com.lagradost.cloudstream3.ui.player

import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * A trust manager that accepts every certificate chain and every issuer.
 *
 * **Nothing in this app installs it, and nothing should.** It is retained only because it is part
 * of the published extension API (`app/api/classes-jar.api`), so deleting the class would be an ABI
 * removal of the kind `DEC-08` covers. `BUG-10` removed the one caller, which installed it as
 * `HttpsURLConnection`'s JVM-wide default and so disabled TLS verification for the whole process,
 * permanently, in every build including release.
 *
 * An extension that genuinely needs to talk to a host with a broken certificate uses `insecureApp`,
 * which is per-client and sits behind the `@UnsafeSSL` opt-in. Android lint reports this class under
 * `TrustAllX509TrustManager` and `CustomX509TrustManager`; those warnings are expected and describe
 * the class accurately.
 */
class SSLTrustManager : X509TrustManager {
    override fun checkClientTrusted(p0: Array<out X509Certificate>?, p1: String?) {
    }

    override fun checkServerTrusted(p0: Array<out X509Certificate>?, p1: String?) {
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> {
        return arrayOf()
    }
}
