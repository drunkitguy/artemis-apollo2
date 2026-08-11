package com.voidlink.android.protocol.crypto

/**
 * A fixed, self-signed X.509 certificate used across the protocol tests.
 *
 * Generated once, outside this codebase, with the JDK's `keytool`:
 *
 * ```
 * keytool -genkeypair -alias t -keyalg RSA -keysize 2048 \
 *         -dname "CN=VoidLink Test Fixture" -validity 7300 \
 *         -keystore t.p12 -storetype PKCS12 -storepass changeit
 * keytool -exportcert -rfc -alias t -keystore t.p12 -storepass changeit
 * ```
 *
 * It carries no private key and is not used to secure anything — it exists so the PEM/DER/hex
 * conversions of spec §2 can be exercised against a real certificate rather than a mock.
 */
object CertificateFixture {

    /** The base64 body, exactly as `keytool -rfc` emitted it, at the standard 64-column width. */
    private val BODY: List<String> = listOf(
        "MIIC4zCCAcugAwIBAgIIAhWd0M9HlzowDQYJKoZIhvcNAQEMBQAwIDEeMBwGA1UE",
        "AxMVVm9pZExpbmsgVGVzdCBGaXh0dXJlMB4XDTI2MDgxMTIzMzkzNFoXDTQ2MDgw",
        "NjIzMzkzNFowIDEeMBwGA1UEAxMVVm9pZExpbmsgVGVzdCBGaXh0dXJlMIIBIjAN",
        "BgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA/t8t2f5ThtHLdbZngjPSKQvYQB+b",
        "T08Xj132MS/FX8v/KR2UCeLajJvGaQVjVUggGJvgoEVgr+TA6dwHuNMMiSsw+ZKq",
        "VgmYrTsp/p7bV3Kg4YUvu7EGYrMUf6BVp0bENKfNcg/+a3EX5wavrpqTpBhFM7oN",
        "rByrMHdPClLZgndXmw/aMjsmcqQ1Bwt43O/INkWAbEV7uSWN1OB7iwx8L9E5Fnnj",
        "D6I/e3HFH55xy7N8mm7iWA9OeBbVWPsVxRSy+w4wSYqLDXECG4nUQpNTVELnISQT",
        "eU4fFTFyHd2A8UHkI4ndlOiD4ROX43f54UjoV4uwxAs44w91cQzR29Vk8wIDAQAB",
        "oyEwHzAdBgNVHQ4EFgQUnGittA2+UBcdzu5rnGpbJ4+5WuwwDQYJKoZIhvcNAQEM",
        "BQADggEBAB8ulYBVEiz4yk7jNX5Ft/xdB88cmw75dKbaxgmKjalKqGtrZSCh+pxF",
        "gw4GnOllO59I9UvbpjDoy5fCI2qdDcXfGuxGqSae/U5MCsYO310BUtfiVHlEt/ZX",
        "eUfPA0yvt6NqRJRGcM23b2MG1NoX1ihsQwNlvMlOuUok4WKMO+MQV5a3w+vg755z",
        "7gHH/tpVWkPBE1qjTDPsPS5qKRiRYw+pn+4N3zoYrPebJABjWlmaynDfJjK1oddS",
        "B4l4Dk9on4VFdLHvaDX+noB6yOTRyL6jQ6OfXCUzmKxLXOt1uAId2Z+cn7Bfyozm",
        "8rWGlM0u/eRG7RZHcjkQYsxgBGKkrZ8=",
    )

    /** The certificate's subject and issuer distinguished name. */
    const val SUBJECT_DN: String = "CN=VoidLink Test Fixture"

    /**
     * The certificate as PEM text with explicit `\n` line endings.
     *
     * Built from [BODY] rather than a raw string literal so the line endings cannot vary with the
     * checkout — this text becomes bytes on the wire during pairing.
     */
    val PEM: String = buildString {
        append("-----BEGIN CERTIFICATE-----\n")
        BODY.forEach { append(it).append('\n') }
        append("-----END CERTIFICATE-----\n")
    }
}
