package com.lagradost.cloudstream4.account

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import qrcode.QRCode
import java.awt.image.BufferedImage

/**
 * A11's QR (`KMP-95d`): [url], the page a code is approved on, as `:app`'s TV pairing draws it with the same library.
 * Only the address is encoded, never the code. Null if it cannot be drawn: the address and Open still lead there.
 */
internal fun pairQr(url: String): ImageBitmap? = runCatching {
    (QRCode.ofSquares().build(url).render().nativeImage() as BufferedImage).toComposeImageBitmap()
}.getOrNull()
