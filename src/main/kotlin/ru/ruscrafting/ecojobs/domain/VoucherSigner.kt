package ru.ruscrafting.ecojobs.domain

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class VoucherSigner(private val secret: ByteArray) {
    init {
        require(secret.size >= 32) { "Voucher signing key must contain at least 32 bytes" }
    }

    fun sign(payload: VoucherPayload): ByteArray = hmac(payload.canonical().toByteArray(StandardCharsets.UTF_8))

    fun verify(payload: VoucherPayload, signature: ByteArray): Boolean =
        MessageDigest.isEqual(sign(payload), signature)

    private fun hmac(bytes: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(secret, "HmacSHA256"))
        doFinal(bytes)
    }
}
