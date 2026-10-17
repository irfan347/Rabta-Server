package com.irfan.rabta

import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType

/** Pairing records contain a public RSA key/signature and can exceed QR version 20. */
object InstantQr {
    val formats=listOf(BarcodeFormat.QR_CODE)
    // Use the normal camera detector with dense-code row sampling, never PURE_BARCODE.
    val decodeHints:Map<DecodeHintType,Any> = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to formats,
        DecodeHintType.TRY_HARDER to true
    )
}
