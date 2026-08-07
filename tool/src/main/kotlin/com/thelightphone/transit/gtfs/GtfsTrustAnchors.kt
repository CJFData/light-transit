package com.thelightphone.transit.gtfs

import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager

/**
 * An extra root certificate one agency's static feed host needs trusted beyond the platform's own
 * store -- e.g. because it's a root recently added to a CA's program that some Android system
 * images haven't picked up yet (see [LtcTrustAnchor], the concrete case this exists for). An
 * [AgencyComponent] like [LiveVehicleSource], so it's opted into via one agency's own
 * `components` list (see [GtfsAgency.LTC]) rather than changing every agency's HTTP client --
 * [GtfsIngestor] only builds a custom [SSLContext] at all for an agency that has one of these.
 *
 * This is deliberately *not* solved via the :netconfig module's network_security_config.xml
 * (unlike RIPTA/LTC's cleartext exceptions there, which are a genuinely different problem -- see
 * that file's own doc comment). :netconfig's survival through Light's actual build/signing
 * pipeline is unverified: `builder/lightbuilder/extract.py` only extracts `tool/` and discards
 * every other module, and the plugin's `ManifestGenerator` never emits a `networkSecurityConfig`
 * manifest attribute at all -- so a fix that lives there might silently do nothing on a real
 * build. A missing trust anchor, unlike a cleartext exception, doesn't need OS/manifest
 * involvement at all: it's purely about which roots the TLS handshake code trusts, which is fully
 * controllable from application code via a custom [SSLContext] handed to the HTTP client -- so
 * this fix lives entirely in :tool instead, where it's guaranteed to actually ship.
 */
class BundledRootTrustAnchor(pem: String) : AgencyComponent {
    /** Lazy per instance, not shared globally -- each agency component that needs one builds and
     * caches its own, so a chain this agency's host doesn't present is never even considered for
     * a different agency's requests. */
    val trustManager: X509ExtendedTrustManager by lazy {
        CompositeTrustManager(systemTrustManager(), singleCertTrustManager(pem))
    }

    val sslSocketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }.socketFactory
    }
}

/**
 * LTC London's static feed host (www.londontransit.ca) serves a valid cert chain rooted at
 * "Sectigo Public Server Authentication Root R46", a root Sectigo only added to its program in
 * 2021 -- recent enough that it's missing from some Android system trust stores still in the
 * field (confirmed on this project's own Android 14 test emulator: every request failed with
 * `SSLHandshakeException: Trust anchor for certification path not found`, even though the chain
 * itself is valid -- verified independently with `openssl verify` against a current CA bundle).
 */
val LtcTrustAnchor = BundledRootTrustAnchor(
    """
-----BEGIN CERTIFICATE-----
MIIFijCCA3KgAwIBAgIQdY39i658BwD6qSWn4cetFDANBgkqhkiG9w0BAQwFADBf
MQswCQYDVQQGEwJHQjEYMBYGA1UEChMPU2VjdGlnbyBMaW1pdGVkMTYwNAYDVQQD
Ey1TZWN0aWdvIFB1YmxpYyBTZXJ2ZXIgQXV0aGVudGljYXRpb24gUm9vdCBSNDYw
HhcNMjEwMzIyMDAwMDAwWhcNNDYwMzIxMjM1OTU5WjBfMQswCQYDVQQGEwJHQjEY
MBYGA1UEChMPU2VjdGlnbyBMaW1pdGVkMTYwNAYDVQQDEy1TZWN0aWdvIFB1Ymxp
YyBTZXJ2ZXIgQXV0aGVudGljYXRpb24gUm9vdCBSNDYwggIiMA0GCSqGSIb3DQEB
AQUAA4ICDwAwggIKAoICAQCTvtU2UnXYASOgHEdCSe5jtrch/cSV1UgrJnwUUxDa
ef0rty2k1Cz66jLdScK5vQ9IPXtamFSvnl0xdE8H/FAh3aTPaE8bEmNtJZlMKpnz
SDBh+oF8HqcIStw+KxwfGExxqjWMrfhu6DtK2eWUAtaJhBOqbchPM8xQljeSM9xf
iOefVNlI8JhD1mb9nxc4Q8UBUQvX4yMPFF1bFOdLvt30yNoDN9HWOaEhUTCDsG3X
ME6WW5HwcCSrv0WBZEMNvSE6Lzzpng3LILVCJ8zab5vuZDCQOc2TZYEhMbUjUDM3
IuM47fgxMMxF/mL50V0yeUKH32rMVhlATc6qu/m1dkmU8Sf4kaWD5QazYw6A3OAS
VYCmO2a0OYctyPDQ0RTp5A1NDvZdV3LFOxxHVp3i1fuBYYzMTYCQNFu31xR13NgE
SJ/AwSiItOkcyqex8Va3e0lMWeUgFaiEAin6OJRpmkkGj80feRQXEgyDet4fsZfu
+Zd4KKTIRJLpfSYFplhym3kT2BFfrsU4YjRosoYwjviQYZ4ybPUHNs2iTG7sijbt
8uaZFURww3y8nDnAtOFr94MlI1fZEoDlSfB1D++N6xybVCi0ITz8fAr/73trdf+L
HaAZBav6+CuBQug4urv7qv094PPK306Xlynt8xhW6aWWrL3DkJiy4Pmi1KZHQ3xt
zwIDAQABo0IwQDAdBgNVHQ4EFgQUVnNYZJX5khqwEioEYnmhQBWIIUkwDgYDVR0P
AQH/BAQDAgGGMA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZIhvcNAQEMBQADggIBAC9c
mTz8Bl6MlC5w6tIyMY208FHVvArzZJ8HXtXBc2hkeqK5Duj5XYUtqDdFqij0lgVQ
YKlJfp/imTYpE0RHap1VIDzYm/EDMrraQKFz6oOht0SmDpkBm+S8f74TlH7Kph52
gDY9hAaLMyZlbcp+nv4fjFg4exqDsQ+8FxG75gbMY/qB8oFM2gsQa6H61SilzwZA
Fv97fRheORKkU55+MkIQpiGRqRxOF3yEvJ+M0ejf5lG5Nkc/kLnHvALcWxxPDkjB
JYOcCj+esQMzEhonrPcibCTRAUH4WAP+JWgiH5paPHxsnnVI84HxZmduTILA7rpX
DhjvLpr3Etiga+kFpaHpaPi8TD8SHkXoUsCjvxInebnMMTzD9joiFgOgyY9mpFui
TdaBJQbpdqQACj7LzTWb4OE4y2BThihCQRxEV+ioratF4yUQvNs+ZUH7G6aXD+u5
dHn5HrwdVw1Hr8Mvn4dGp+smWg9WY7ViYG4A++MnESLn/pmPNPW56MORcr3Ywx65
LvKRRFHQV80MNNVIIb/bE/FmJUNS0nAiNs2fxBx1IK1jcmMGDw4nztJqDby1ORrp
0XZ60Vzk50lJLVU3aPAaOpg+VBeHVOmmJ1CJeyAvP/+/oYtKR5j/K3tJPsMpRmAY
QqszKbrAKbkTidOIijlBO8n9pu0f9GBj39ItVQGL
-----END CERTIFICATE-----
    """,
)

/** Wraps two [X509ExtendedTrustManager]s so a chain is accepted if *either* trusts it -- [primary]
 * (the platform's own trust store) is tried first, and [fallback] only comes into play for the
 * specific chains [primary] rejects, so nothing already trusted by the OS is affected by this at
 * all.
 *
 * Must be the *extended* variant, not plain [javax.net.ssl.X509TrustManager]: this app's manifest
 * references a network security config (:netconfig's RIPTA/LTC cleartext exception -- an unrelated
 * concern, see that module's own doc comment), and once any per-domain config exists at all,
 * Android's TLS stack requires a hostname-aware trust manager for *any* custom [SSLContext] used
 * in the app. A plain [javax.net.ssl.X509TrustManager] here throws `SSLHandshakeException: Domain
 * specific configurations require that hostname aware checkServerTrusted(...) is used`. */
private class CompositeTrustManager(
    private val primary: X509ExtendedTrustManager,
    private val fallback: X509ExtendedTrustManager,
) : X509ExtendedTrustManager() {
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        primary.checkClientTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket) =
        primary.checkClientTrusted(chain, authType, socket)

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine) =
        primary.checkClientTrusted(chain, authType, engine)

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) =
        tryBoth({ primary.checkServerTrusted(chain, authType) }, { fallback.checkServerTrusted(chain, authType) })

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket) =
        tryBoth(
            { primary.checkServerTrusted(chain, authType, socket) },
            { fallback.checkServerTrusted(chain, authType, socket) },
        )

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine) =
        tryBoth(
            { primary.checkServerTrusted(chain, authType, engine) },
            { fallback.checkServerTrusted(chain, authType, engine) },
        )

    override fun getAcceptedIssuers(): Array<X509Certificate> =
        primary.acceptedIssuers + fallback.acceptedIssuers

    private inline fun tryBoth(primaryCheck: () -> Unit, fallbackCheck: () -> Unit) {
        try {
            primaryCheck()
        } catch (primaryFailure: CertificateException) {
            try {
                fallbackCheck()
            } catch (fallbackFailure: CertificateException) {
                throw primaryFailure
            }
        }
    }
}

private fun systemTrustManager(): X509ExtendedTrustManager {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as KeyStore?)
    return factory.trustManagers.filterIsInstance<X509ExtendedTrustManager>().first()
}

private fun singleCertTrustManager(pem: String): X509ExtendedTrustManager {
    // Android's Conscrypt-backed CertificateFactory, unlike the host JVM's default provider used
    // by this file's own unit test, doesn't tolerate the leading blank line/indentation the raw
    // triple-quoted string above has -- it throws an ASN.1 decode error instead of skipping ahead
    // to the "-----BEGIN CERTIFICATE-----" marker the way the JVM's Sun provider does. trim() first
    // so both parse identically.
    val cert = CertificateFactory.getInstance("X.509")
        .generateCertificate(pem.trim().byteInputStream()) as X509Certificate
    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
        load(null, null)
        setCertificateEntry("bundled-root", cert)
    }
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(keyStore)
    return factory.trustManagers.filterIsInstance<X509ExtendedTrustManager>().first()
}