package fr.acinq.lightning.io.rustls

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import cnames.structs.rustls_client_config
import cnames.structs.rustls_client_config_builder
import cnames.structs.rustls_connection
import cnames.structs.rustls_root_cert_store
import cnames.structs.rustls_server_cert_verifier
import rustls.cinterop.rustls_pinned_key
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.memcmp
import platform.posix.memcpy
import rustls.cinterop.RUSTLS_RESULT_CERTIFICATE_PARSE_ERROR
import rustls.cinterop.RUSTLS_RESULT_CERT_APPLICATION_VERIFICATION_FAILURE
import rustls.cinterop.RUSTLS_RESULT_CERT_OTHER_ERROR
import rustls.cinterop.RUSTLS_RESULT_OK
import rustls.cinterop.rustls_client_config_builder_build
import rustls.cinterop.rustls_client_config_builder_dangerous_set_certificate_verifier
import rustls.cinterop.rustls_client_config_builder_new
import rustls.cinterop.rustls_client_config_builder_set_alpn_protocols
import rustls.cinterop.rustls_client_config_builder_set_server_verifier
import rustls.cinterop.rustls_client_config_free
import rustls.cinterop.rustls_connection_set_userdata
import rustls.cinterop.rustls_root_cert_store_builder_build
import rustls.cinterop.rustls_root_cert_store_builder_load_roots_from_file
import rustls.cinterop.rustls_root_cert_store_builder_new
import rustls.cinterop.rustls_root_cert_store_free
import rustls.cinterop.rustls_server_cert_verifier_free
import rustls.cinterop.rustls_slice_bytes
import rustls.cinterop.rustls_verify_server_cert_params
import rustls.cinterop.rustls_web_pki_server_cert_verifier_builder_build
import rustls.cinterop.rustls_web_pki_server_cert_verifier_builder_new
import rustls.cinterop.wrapper_client_connection_new

/** Default locations of the system CA bundle, in probe order. */
private val DEFAULT_CA_BUNDLES = listOf(
    "/etc/ssl/certs/ca-certificates.crt", // Debian/Ubuntu/Alpine
    "/etc/pki/tls/certs/ca-bundle.crt",   // Fedora/RHEL
    "/etc/ssl/cert.pem",                  // OpenSSL default / macOS-ish
)

/**
 * INSECURE custom verifier that accepts ANY server certificate without checking it.
 *
 * Used by `build(dangerousSkipCertVerification = true)`. The callback runs deep inside
 * rustls' handshake state machine, so it must not allocate on the Kotlin heap or call
 * suspend functions; it only returns `RUSTLS_RESULT_OK` to accept the peer.
 */
@OptIn(ExperimentalForeignApi::class)
private val acceptAnyCertCallback = staticCFunction {
        _: COpaquePointer?, _: CPointer<rustls_verify_server_cert_params>? ->
    RUSTLS_RESULT_OK
}

/**
 * Public-key-pinning verifier: accept the server iff the end-entity certificate's
 * SubjectPublicKeyInfo matches a pinned key, ignoring the regular CA chain.
 *
 * The pinned key (a [rustls_pinned_key]) arrives as the connection `userdata`. Like
 * [acceptAnyCertCallback] this runs inside rustls' handshake state machine, so it does
 * no Kotlin-heap allocation and no suspension: it walks the DER certificate and compares
 * bytes entirely through C pointers.
 */
@OptIn(ExperimentalForeignApi::class)
private val pinnedKeyCallback = staticCFunction {
        userdata: COpaquePointer?, params: CPointer<rustls_verify_server_cert_params>? ->
    if (userdata == null || params == null) return@staticCFunction RUSTLS_RESULT_CERT_OTHER_ERROR
    val expected = userdata.reinterpret<rustls_pinned_key>().pointed
    val cert = params.pointed.end_entity_cert_der
    val der = cert.data ?: return@staticCFunction RUSTLS_RESULT_CERTIFICATE_PARSE_ERROR
    val spki = findSpki(der, cert.len.toInt())
    if (spki < 0L) return@staticCFunction RUSTLS_RESULT_CERTIFICATE_PARSE_ERROR
    val spkiStart = (spki ushr 32).toInt()
    val spkiLen = (spki and 0xFFFFFFFFL).toInt()
    if (spkiLen.toULong() != expected.len) {
        return@staticCFunction RUSTLS_RESULT_CERT_APPLICATION_VERIFICATION_FAILURE
    }
    if (memcmp(der + spkiStart, expected.data, expected.len) != 0) {
        return@staticCFunction RUSTLS_RESULT_CERT_APPLICATION_VERIFICATION_FAILURE
    }
    RUSTLS_RESULT_OK
}

/** Sentinel for [findSpki]: the certificate could not be parsed. */
private const val SPKI_NOT_FOUND = -1L

/**
 * Locate the SubjectPublicKeyInfo within a DER-encoded X.509 certificate.
 *
 * @return the SPKI element packed as `(offset shl 32) or length`, or [SPKI_NOT_FOUND].
 *   The packed range covers the full SPKI TLV (tag + length + content), i.e. exactly the
 *   DER bytes you get from `openssl x509 -pubkey` (minus the PEM armor).
 *
 * Walks `Certificate -> tbsCertificate -> subjectPublicKeyInfo`, skipping the optional
 * explicit version then serialNumber, signature, issuer, validity and subject. Operates
 * purely on the C buffer so it is safe to call from the verifier callback.
 */
@OptIn(ExperimentalForeignApi::class)
private fun findSpki(p: CPointer<UByteVar>, total: Int): Long {
    if (total < 2 || tagAt(p, 0) != 0x30) return SPKI_NOT_FOUND // Certificate ::= SEQUENCE
    if (derElementLen(p, 0, total) < 0) return SPKI_NOT_FOUND
    val tbsOff = derContentStart(p, 0)
    if (tbsOff < 0 || tbsOff >= total || tagAt(p, tbsOff) != 0x30) return SPKI_NOT_FOUND // TBSCertificate ::= SEQUENCE
    val tbsLen = derElementLen(p, tbsOff, total)
    if (tbsLen < 0) return SPKI_NOT_FOUND
    val tbsEnd = tbsOff + tbsLen
    var off = derContentStart(p, tbsOff)
    if (off < 0) return SPKI_NOT_FOUND
    // Optional [0] EXPLICIT version.
    if (off < tbsEnd && tagAt(p, off) == 0xA0) {
        val l = derElementLen(p, off, tbsEnd)
        if (l < 0) return SPKI_NOT_FOUND
        off += l
    }
    // Skip serialNumber, signature, issuer, validity, subject.
    repeat(5) {
        if (off >= tbsEnd) return SPKI_NOT_FOUND
        val l = derElementLen(p, off, tbsEnd)
        if (l < 0) return SPKI_NOT_FOUND
        off += l
    }
    if (off >= tbsEnd || tagAt(p, off) != 0x30) return SPKI_NOT_FOUND // SubjectPublicKeyInfo ::= SEQUENCE
    val spkiLen = derElementLen(p, off, tbsEnd)
    if (spkiLen < 0) return SPKI_NOT_FOUND
    return (off.toLong() shl 32) or spkiLen.toLong()
}

/** The DER tag byte (unsigned) at [off]. */
@OptIn(ExperimentalForeignApi::class)
private fun tagAt(p: CPointer<UByteVar>, off: Int): Int = p[off].toInt() and 0xFF

/**
 * Total size (tag + length + content) of the DER element whose tag byte is at [off],
 * or -1 if the header is malformed or the element would run past [end].
 */
@OptIn(ExperimentalForeignApi::class)
private fun derElementLen(p: CPointer<UByteVar>, off: Int, end: Int): Int {
    val lenStart = off + 1
    if (lenStart >= end) return -1
    val first = p[lenStart].toInt() and 0xFF
    if (first and 0x80 == 0) {
        val totalLen = 2 + first
        return if (off + totalLen <= end) totalLen else -1
    }
    val numBytes = first and 0x7F
    if (numBytes == 0 || numBytes > 4 || lenStart + 1 + numBytes > end) return -1 // unsupported / indefinite
    var value = 0
    for (k in 0 until numBytes) value = (value shl 8) or (p[lenStart + 1 + k].toInt() and 0xFF)
    if (value < 0) return -1 // length overflowed a (signed) Int
    val header = 2 + numBytes
    val totalLen = header + value
    return if (totalLen >= header && off + totalLen <= end) totalLen else -1
}

/**
 * Offset of the content (just past the tag + length) of the element at [off]. Only call
 * after [derElementLen] has confirmed the header at [off] is in bounds and well-formed.
 */
@OptIn(ExperimentalForeignApi::class)
private fun derContentStart(p: CPointer<UByteVar>, off: Int): Int {
    val lenStart = off + 1
    val first = p[lenStart].toInt() and 0xFF
    return if (first and 0x80 == 0) lenStart + 1 else lenStart + 1 + (first and 0x7F)
}

/**
 * An immutable, shareable rustls client configuration: which root certificates to
 * trust (or whether to verify at all).
 *
 * A single config can back many connections. Call [close] when you are done with it;
 * existing connections keep an internal reference, so closing it early is safe.
 */
@OptIn(ExperimentalForeignApi::class)
class RustlsClientConfig private constructor(
    internal val config: CPointer<rustls_client_config>,
    /**
     * When pinning, the expected public key passed to the verifier callback as the
     * connection userdata. Backed by nativeHeap memory owned by this config; null
     * for the webpki / skip-verification verifiers.
     */
    private val pinnedKey: CPointer<rustls_pinned_key>? = null,
) : AutoCloseable {

    private var closed = false

    /**
     * Create a new rustls client connection bound to this config for [serverName] (SNI).
     * When the config pins a public key, the pinned key is attached as the connection
     * userdata so the verifier callback can read it.
     */
    internal fun newConnection(serverName: String): CPointer<rustls_connection> = memScoped {
        val out = allocPointerTo<rustls_connection>()
        rustlsCheck(wrapper_client_connection_new(config, serverName, out.ptr))
        val conn = out.value ?: error("rustls_client_connection_new produced null")
        pinnedKey?.let { rustls_connection_set_userdata(conn, it) }
        conn
    }

    override fun close() {
        if (!closed) {
            closed = true
            rustls_client_config_free(config)
            pinnedKey?.let {
                nativeHeap.free(it.pointed.data!!.rawValue)
                nativeHeap.free(it.rawValue)
            }
        }
    }

    companion object {
        /**
         * Build a client config.
         *
         * By default the server-certificate verifier trusts the roots in a PEM bundle
         * (the system CA bundle, or [caBundlePath]) and validates with webpki.
         *
         * @param caBundlePath PEM file of trusted roots; if null, the first existing
         *   path in [DEFAULT_CA_BUNDLES] is used. Ignored when
         *   [dangerousSkipCertVerification] is true.
         * @param dangerousSkipCertVerification if true, install a custom verifier that
         *   accepts ANY server certificate. **INSECURE** — this disables authentication
         *   and exposes the connection to man-in-the-middle attacks. Only for testing or
         *   when peer identity is established by other means (e.g. an out-of-band
         *   public-key check). Never use it for general traffic.
         * @param pinnedPublicKey if non-null, install a verifier that ignores the CA
         *   chain and instead accepts the server iff its end-entity certificate carries
         *   exactly this public key. The bytes are the DER-encoded SubjectPublicKeyInfo
         *   (the same structure as `openssl x509 -pubkey`, without the PEM armor). This is
         *   the right model when the peer is identified out-of-band (e.g. a self-signed
         *   Electrum server). Takes precedence over [caBundlePath] /
         *   [dangerousSkipCertVerification].
         */
        fun build(
            caBundlePath: String? = null,
            dangerousSkipCertVerification: Boolean = false,
            pinnedPublicKey: ByteArray? = null,
        ): RustlsClientConfig {
            val builder = rustls_client_config_builder_new()
                ?: error("rustls_client_config_builder_new returned null")

            // Copy the pinned key into config-lifetime native memory (freed in close()).
            val pinnedKey: CPointer<rustls_pinned_key>? = pinnedPublicKey?.let { allocPinnedKey(it) }

            memScoped {
                when {
                    pinnedKey != null -> rustlsCheck(
                        rustls_client_config_builder_dangerous_set_certificate_verifier(
                            builder, pinnedKeyCallback
                        )
                    )
                    dangerousSkipCertVerification -> rustlsCheck(
                        rustls_client_config_builder_dangerous_set_certificate_verifier(
                            builder, acceptAnyCertCallback
                        )
                    )
                    else -> setWebPkiVerifier(builder, caBundlePath)
                }
                val configOut = allocPointerTo<rustls_client_config>()
                rustlsCheck(rustls_client_config_builder_build(builder, configOut.ptr))
                return RustlsClientConfig(
                    configOut.value ?: error("rustls_client_config_builder_build produced null"),
                    pinnedKey,
                )
            }
        }

        /** Copy [key] into a [rustls_pinned_key] on nativeHeap (owned by the config). */
        private fun allocPinnedKey(key: ByteArray): CPointer<rustls_pinned_key> {
            val data = nativeHeap.allocArray<UByteVar>(key.size)
            key.usePinned { src ->
                if (key.isNotEmpty()) memcpy(data, src.addressOf(0), key.size.convert())
            }
            return nativeHeap.alloc<rustls_pinned_key>().apply {
                this.data = data
                this.len = key.size.convert()
            }.ptr
        }

        /** Trust the roots in [caBundlePath] (or the system bundle) via a webpki verifier. */
        private fun MemScope.setWebPkiVerifier(
            builder: CPointer<rustls_client_config_builder>,
            caBundlePath: String?,
        ) {
            val caPath = caBundlePath ?: DEFAULT_CA_BUNDLES.firstOrNull { fileExists(it) }
            ?: error("no CA bundle found; pass caBundlePath explicitly (tried $DEFAULT_CA_BUNDLES)")

            // Load trusted roots from the PEM bundle into a root cert store.
            val storeBuilder = rustls_root_cert_store_builder_new()
                ?: error("rustls_root_cert_store_builder_new returned null")
            rustlsCheck(
                rustls_root_cert_store_builder_load_roots_from_file(
                    storeBuilder, caPath, /* strict = */ false
                )
            )
            val storeOut = allocPointerTo<rustls_root_cert_store>()
            rustlsCheck(rustls_root_cert_store_builder_build(storeBuilder, storeOut.ptr))
            val store = storeOut.value ?: error("root cert store build produced null")

            // Build a webpki server-certificate verifier from that store.
            val verifierBuilder = rustls_web_pki_server_cert_verifier_builder_new(store)
                ?: error("server cert verifier builder new returned null")
            val verifierOut = allocPointerTo<rustls_server_cert_verifier>()
            rustlsCheck(
                rustls_web_pki_server_cert_verifier_builder_build(verifierBuilder, verifierOut.ptr)
            )
            val verifier = verifierOut.value ?: error("verifier build produced null")
            rustls_root_cert_store_free(store) // the verifier holds its own reference

            // Attach the verifier (refcounted, not owned) then drop our handle.
            rustls_client_config_builder_set_server_verifier(builder, verifier)
            rustls_server_cert_verifier_free(verifier)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun fileExists(path: String): Boolean {
    val f = fopen(path, "r") ?: return false
    fclose(f)
    return true
}
