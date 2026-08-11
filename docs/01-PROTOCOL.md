# 01 — GameStream / Sunshine Protocol Specification

**Status:** normative for implementation. Every claim here is either (a) traceable to a source
listed in §14, or (b) explicitly marked **UNVERIFIED**.

**Reading rule for the coder:** where this document gives a byte layout, implement it exactly.
Where it says UNVERIFIED, implement the described best guess, hide it behind a named constant,
log when the path executes, and mark it `// UNVERIFIED(spec 01 §N)` in code.

---

## 0. Conventions, endianness, and host generations

### 0.1 Endianness — the number-one bug source

The protocol is **inconsistent** about byte order. There is no single rule. Per-field:

| Context | Byte order |
|---|---|
| RTP headers (video + audio) | **Big-endian** (standard RTP) |
| NV video packet header (`NV_VIDEO_PACKET`) | **Little-endian** |
| Input packet `size` field | **Big-endian** |
| Input packet `magic` field | **Little-endian** |
| Input packet bodies (sticks, flags, etc.) | **Big-endian** except where noted; floats are **little-endian** ("netfloat") |
| Control (ENet) packet type + length | **Little-endian** |
| Control encrypted header seq | **Little-endian** |
| Sunshine FEC-status control payload | **Big-endian** |
| Termination error code payload | **Big-endian** |
| Audio FEC header | **Big-endian** |

Write a `ByteBuffer`-based helper that makes the order explicit at every call site. Never rely
on a default.

### 0.2 Structure packing

All C structs quoted in this document are `#pragma pack(1)` — **no padding, no alignment**.
Kotlin serializers must write fields back-to-back.

### 0.3 Host generation detection

Everything branches on the host's `appversion` string from `/serverinfo`, which looks like
`7.1.431.0` — four dot-separated integers. Call them `AppVersionQuad[0..3]`.

```
gen = AppVersionQuad[0]          // 3, 4, 5, or 7. Modern hosts all report 7.
```

Helper predicate used throughout: `APP_VERSION_AT_LEAST(a,b,c)` — lexicographic compare of the
first three components.

**Sunshine/Apollo detection:** the `<state>` element of `/serverinfo` is
`SUNSHINE_SERVER_FREE` / `SUNSHINE_SERVER_BUSY` for Sunshine-family hosts and
`MJOLNIR_SERVER_FREE` / `..._BUSY` (contains `MJOLNIR`) for NVIDIA GFE. Moonlight-android uses
`state.contains("MJOLNIR")` to mean "this is a real NVIDIA server".

```kotlin
val isNvidiaGfe   = state.contains("MJOLNIR")
val isSunshineish = state.startsWith("SUNSHINE")   // Sunshine, Apollo, and forks
```

**Practical guidance:** GFE is discontinued and rare. **Target Gen 7 + Sunshine/Apollo as the
primary path.** Implement Gen 3/4/5 constants where they are cheap to carry (they are just
different magic numbers in tables), but do not spend time testing them.

### 0.4 Port summary

| Purpose | Transport | Default port | How it is really determined |
|---|---|---|---|
| NVHTTP (plaintext) | TCP | **47989** | Fixed / user-supplied. |
| NVHTTP (TLS, client cert) | TCP | **47984** | Read from `<HttpsPort>` in `/serverinfo`; fall back to 47984. |
| RTSP | TCP (or ENet/UDP for Sunshine) | **48010** | Parsed from the `sessionUrl0` returned by `/launch`; fall back to 48010. |
| Video RTP | UDP | **47998** | Parsed from `server_port=` in the RTSP `SETUP streamid=video` response `Transport` header; fall back to 47998. |
| Control (ENet) | UDP | **47999** | Parsed from `server_port=` in `SETUP streamid=control`; fall back to 47999. |
| Audio RTP | UDP | **48000** | Parsed from `server_port=` in `SETUP streamid=audio`; fall back to 48000. |
| Legacy control (Gen 3/4) | TCP | **47995** | Hardcoded; do **not** use the negotiated control port for these old versions. |
| mDNS | UDP | 5353 | Standard. |

Sunshine allows a configurable base port; all of the above are offsets from it, which is
exactly why you must parse the negotiated ports rather than hardcoding.

---

## 1. Discovery

### 1.1 mDNS

* **Service type:** `_nvstream._tcp` (in the `.local` domain, so the full name Android's
  `NsdManager` wants is `_nvstream._tcp.` and it appends `.local.` itself).
* The advertised SRV record's **port is the NVHTTP plaintext port** (47989 by default).
* The instance name is the host's friendly name, but **do not trust it** — always follow up
  with a `/serverinfo` query and use `<hostname>` and `<uniqueid>` from there.
* TXT records: hosts may publish TXT keys, but **UNVERIFIED** which keys are guaranteed.
  Do not depend on TXT contents. Treat discovery as "here is an IP and port", nothing more.

**Android implementation:** `NsdManager.discoverServices("_nvstream._tcp", PROTOCOL_DNS_SD, listener)`.

Known Android pitfalls the coder must handle:

1. `NsdManager` resolution is serialized on older APIs — resolving two services concurrently
   throws/fails. Queue resolves one at a time, or use `NsdManager.registerServiceInfoCallback`
   on API 34+.
2. Multicast may require a `WifiManager.MulticastLock` on some devices/ROMs. Acquire one for
   the duration of discovery and release it after.
3. Discovery does not work when the device is on cellular or a VPN routes all traffic. Detect
   and show "no Wi-Fi network" in the empty state rather than spinning forever.
4. Some routers block mDNS between wireless clients (client isolation). **Manual IP entry is
   not a fallback nicety — it is a required feature.**

### 1.2 Manual entry

Accept `host`, `host:port`, an IPv4 literal, an IPv6 literal in brackets, or a hostname.
Default port 47989. Immediately probe `/serverinfo` over plaintext HTTP to validate.

### 1.3 Polling known hosts

For each saved host, poll `/serverinfo` periodically (suggest **every 5 s while the Hosts
screen is visible, paused otherwise**) to update online/offline and paired state. Use a short
connect timeout for hosts we believe are offline (suggest 1 s) and a longer one for hosts we
believe are online (suggest 5 s), because a slow probe of a dead host stalls the whole list.

### 1.4 Wake-on-LAN

For an offline host we have previously seen, we know its MAC address (from `<mac>` in a prior
`/serverinfo` — note that Sunshine only returns the real MAC over **HTTPS to a paired client**;
over plaintext HTTP it deliberately returns `00:00:00:00:00:00`, which we must treat as "unknown").

Magic packet format (standard WoL, not GameStream-specific):

```
6 bytes of 0xFF, then the 6-byte target MAC repeated 16 times = 102 bytes
```

Send as a UDP broadcast datagram to the subnet broadcast address (and to
`255.255.255.255`) on ports **9** and **7** (send to both; different NICs listen on different
ones). Also send to the host's last-known unicast IP on those ports — some setups keep an ARP
entry alive. `DatagramSocket.broadcast = true` is required.

**UNVERIFIED:** whether GameStream hosts respond to WoL on any port other than 9/7. Sending to
both is the common practice.

---

## 2. Client identity: certificate and key

Generated **once per install**, reused for every host.

* **Key:** RSA 2048-bit. (ECDSA P-256 also works with modern Sunshine, and the pairing code
  paths select `SHA256withECDSA` for EC keys — but **GFE requires RSA**, and Sunshine's
  certificate handling is best-tested with RSA. **Use RSA 2048.**)
* **Certificate:** self-signed X.509 v3.
  * Subject/Issuer: `CN=NVIDIA GameStream Client` (any CN works; keep it stable).
  * Serial: random positive `BigInteger`.
  * Validity: now − 1 day to now + 20 years. (Hosts pin the cert; a short expiry means pairing
    silently breaks later.)
  * Signature algorithm: `SHA256withRSA`.
* **Encoding used on the wire:** the client certificate is sent during pairing as the
  **PEM-encoded** bytes, hex-encoded. That is: take the PEM text
  (`-----BEGIN CERTIFICATE-----\n<base64>\n-----END CERTIFICATE-----\n`), take its **ASCII
  bytes**, and hex-encode those bytes. It is *not* the DER bytes hex-encoded.
  This matters and is easy to get wrong.
* **Unique ID:** a client identifier string sent as `uniqueid=` on every NVHTTP request.
  Moonlight uses a fixed-length hex string. Generate 16 random hex chars once at first launch
  and persist it. **UNVERIFIED:** whether any host validates its format; both GFE and Sunshine
  appear to treat it as an opaque string, and Sunshine rejects only a *missing* `uniqueid`.

**Android generation:** BouncyCastle (`bcpkix-jdk18on`) is the pragmatic choice for building
a self-signed X.509 — the platform `KeyPairGenerator` can make the key but not the cert.
Alternative: `KeyPairGeneratorSpec`/`KeyGenParameterSpec` with the Android Keystore produces a
self-signed cert, but the private key is then non-exportable, which is fine for TLS but means
you must sign through the Keystore for the pairing signature step. **Recommendation: generate
in-process with BouncyCastle, store the PKCS#8 key and DER cert in `filesDir` with `MODE_PRIVATE`.**

**Storage:** `filesDir/identity/client.key` (PKCS#8 DER), `filesDir/identity/client.crt`
(DER), `filesDir/identity/client.pem` (PEM text — cache this, we hex it on every pair).

---

## 3. NVHTTP control API

### 3.1 Request shape

Every request is `GET /<endpoint>?<params>` and every response is a small XML document.

**Two query parameters are appended to every request**, in addition to the endpoint-specific
ones:

```
uniqueid=<our persistent client id>
uuid=<a NEW random UUID v4, lowercase, with dashes, per request>
```

The `uuid` is a per-request nonce — generate a fresh one on every call.

**Transport selection:**

| Endpoint | Transport |
|---|---|
| `/serverinfo` | Try HTTPS-with-client-cert first if we believe we are paired; **fall back to plaintext HTTP** if TLS fails or we are unpaired. |
| `/pair` | **Plaintext HTTP (47989)** — all four pairing phases. |
| `/unpair` | Plaintext HTTP. |
| `/pair` challenge (final step) | **HTTPS**, see §4 phase 5. |
| `/applist`, `/appasset`, `/launch`, `/resume`, `/cancel` | **HTTPS with client certificate (47984)**. |

**TLS specifics for port 47984:**

* We present our client certificate + private key (`KeyManager` from a `KeyStore` holding our
  identity).
* The server's certificate is **self-signed and will not validate against any CA**. We must
  use a custom `X509TrustManager` that trusts **exactly the server certificate we learned
  during pairing** (byte-equality against the stored `plaincert`), and nothing else. Never
  use a blanket trust-all trust manager. Hostname verification must be disabled/replaced
  (the cert has no matching SAN) — pinning the exact certificate is what provides security.
* If we have no stored server cert for a host, HTTPS endpoints are unavailable — pair first.
* Older GFE hosts negotiate old TLS versions/cipher suites. Enable TLSv1.2 explicitly; be
  prepared for hosts that only offer TLSv1.0/1.1 — **UNVERIFIED** whether any still-in-use
  host requires < TLSv1.2. If a handshake fails with `no cipher suites in common`, surface it.

### 3.2 XML response shape

All responses share this envelope:

```xml
<?xml version="1.0" encoding="utf-8"?>
<root status_code="200" status_message="OK">
  ... elements ...
</root>
```

Parsing rules:

1. **Check `status_code` on `<root>` first.** Anything other than `200` is an error; surface
   `status_message`.
2. Read named child elements by tag name; treat missing as `null` and decide per call site
   whether that is fatal.
3. **The document may be truncated or the root tag unterminated** on host errors. Verify the
   root element was properly closed before trusting the parse. Use `XmlPullParser`
   (`org.xmlpull.v1` is on Android) or `kotlinx` XML; do not regex it.

### 3.3 `/serverinfo`

`GET /serverinfo?uniqueid=..&uuid=..`

Elements we consume:

| Element | Type | Meaning / use |
|---|---|---|
| `hostname` | string | Friendly display name. |
| `appversion` | string `a.b.c.d` | **Generation detection.** Required. |
| `GfeVersion` | string | Informational; GFE version. Sunshine reports a synthetic value. |
| `uniqueid` | string | **The host's** id. This is our primary key for saved hosts. Required. |
| `HttpsPort` | int | TLS port. Default 47984 if absent/unparseable. |
| `ExternalPort` | int | WAN port. Default to the HTTP port we used. |
| `mac` | string | MAC for Wake-on-LAN. `00:00:00:00:00:00` = unknown (Sunshine over plaintext). |
| `LocalIP` | IPv4 string | Host's LAN address. Sunshine returns `127.0.0.1` for IPv6 requests — ignore that value. |
| `ExternalIP` | string (optional) | WAN address. |
| `MaxLumaPixelsHEVC` | int | `0` ⇒ **no HEVC support**. Non-zero (e.g. `1869449984`) ⇒ HEVC available. |
| `MaxLumaPixelsH264` | int | Max H.264 luma pixels; used to sanity-check requested resolution. |
| `ServerCodecModeSupport` | int bitfield | Codec/profile capability flags. See §3.3.1. |
| `PairStatus` | `0`/`1` | Whether **this client** (by `uniqueid` + cert) is paired. |
| `currentgame` | int | App ID of the running app; `0` if idle. |
| `state` | string | `..._SERVER_FREE` / `..._SERVER_BUSY`; also the Sunshine-vs-GFE discriminator (§0.3). |
| `gputype` | string | Informational. |
| `SupportedDisplayMode` | container | Zero or more `<DisplayMode><Width/><Height/><RefreshRate/></DisplayMode>`. **UNVERIFIED** how reliable/complete this is on Sunshine; use it to *suggest* resolutions, never to restrict them. |

Notes:

* `PairStatus` over **plaintext HTTP** reflects only weak identification. The authoritative
  check is: can we complete an HTTPS request with our client cert? If HTTPS `/serverinfo`
  succeeds, we are genuinely paired.
* `state` ending in `_SERVER_BUSY` means an app is running; `currentgame` tells you which. Use
  this to show "Resume" / "Quit" instead of "Launch".

#### 3.3.1 `ServerCodecModeSupport` bitfield

A bitmask of codec/profile support. Sunshine composes it from the active encoder's
capabilities. Known/used semantics:

| Bit(s) | Meaning |
|---|---|
| `0x0001` | H.264 supported |
| `0x0100` | HEVC (Main) supported |
| `0x0200` | HEVC Main10 (10-bit / HDR) supported |
| `0x1000` | AV1 Main8 supported |
| `0x2000` | AV1 Main10 supported |

**UNVERIFIED (important):** the exact bit assignments of `ServerCodecModeSupport` are not
formally documented, and the values above are inferred from the fact that they mirror the
client-side `VIDEO_FORMAT_*` masks (§7.1). Treat this field as a **hint**:

* If `MaxLumaPixelsHEVC == 0`, HEVC is definitely unavailable — that check *is* reliable.
* For HDR and AV1 availability, prefer to attempt negotiation and handle failure, rather than
  hard-gating the UI on this bitfield. Log the raw value so we can learn from real hosts.

### 3.4 `/applist`

`GET /applist?uniqueid=..&uuid=..` over **HTTPS**.

```xml
<root status_code="200">
  <App>
    <IsHdrSupported>1</IsHdrSupported>
    <AppTitle>Desktop</AppTitle>
    <ID>881448767</ID>
  </App>
  <App>...</App>
</root>
```

* `ID` is an **unsigned 32-bit integer in a string** — parse as `Long`, store as `Int`
  bit-pattern or `Long`; do not assume it fits a signed `Int` (some GFE ids exceed
  `Int.MAX_VALUE`). Recommend storing as `Long`.
* Discard any `<App>` missing either `ID` or `AppTitle`.
* GFE may return apps with duplicate names; the ID is the key.
* Sunshine returns whatever the user configured, typically including a "Desktop" entry.
  If no entry named `Desktop` exists, **do not synthesize one** — on GFE the Desktop app is a
  real entry; on Sunshine it may genuinely be absent. Instead, sort a `Desktop`-named entry
  first if present (matching the reference UI).

### 3.5 `/appasset` (box art)

`GET /appasset?uniqueid=..&uuid=..&appid=<ID>&AssetType=2&AssetIdx=0` over **HTTPS**.

* Response body is a **PNG image**, not XML.
* `AssetType=2` = box art. `AssetIdx=0` = the primary image. Other values exist but are unused.
* Aspect ratio is portrait, nominally **300×400 (3:4)**.
* If the host has no art, the response may be a 404, an empty body, or a placeholder image.
  Handle all three: fall back to a generated tile (first letter of the app name on a tinted
  background).
* **Cache aggressively** to disk keyed by `hostUuid + appId`; box art rarely changes.

### 3.6 `/launch`

`GET /launch?...` over **HTTPS**, with a **long or absent read timeout** — launching a game can
take tens of seconds.

Full parameter list, in the order the reference implementation sends them:

| Param | Value |
|---|---|
| `uniqueid` | client id (universal) |
| `uuid` | fresh per-request UUID (universal) |
| `appid` | the app's `ID` |
| `mode` | `<width>x<height>x<fps>` — e.g. `1920x1080x60` |
| `additionalStates` | `1` |
| `sops` | `1` to let the host change the game's own resolution settings ("optimize game settings"), else `0` |
| `rikey` | hex of the 16-byte AES **remote-input key** we generated |
| `rikeyid` | a 32-bit integer, sent in **decimal**, used as the initial AES IV counter |
| `hdrMode` | `1` — **only present when HDR is requested** |
| `clientHdrCapVersion` | `0` — only when HDR requested |
| `clientHdrCapSupportedFlagsInUint32` | `0` — only when HDR requested |
| `clientHdrCapMetaDataId` | `NV_STATIC_METADATA_TYPE_1` — only when HDR requested |
| `clientHdrCapDisplayData` | `0x0x0x0x0x0x0x0x0x0x0` — only when HDR requested |
| `localAudioPlayMode` | `1` to also play audio on the host's speakers, else `0` |
| `surroundAudioInfo` | `(channelMask << 16) | channelCount` as a decimal int (§8.2) |
| `remoteControllersBitmap` | bitmask of attached gamepads |
| `gcmap` | same bitmask as `remoteControllersBitmap` |
| `gcpersist` | `1` to keep virtual gamepads alive after a controller disconnects, else `0` |

Additional Sunshine-only parameters may be appended by newer clients; none are required.

**SOPS caveat:** on NVIDIA hosts, requesting a non-standard resolution with `sops=1` makes GFE
clamp the session to 720p60. Rule: if the host is NVIDIA (`MJOLNIR`) and
`width*height > 1280*720` and the resolution is not exactly 1920×1080 or 3840×2160, **force
`sops=0`**.

**FPS caveat:** on NVIDIA hosts, requesting `fps > 60` in `mode` is rejected by some GFE
versions; the workaround used in the wild is to send `fps = 0` in the `mode` string for
NVIDIA hosts when the desired rate exceeds 60, and let the real rate be negotiated over RTSP.
**UNVERIFIED** for which exact GFE builds this is required; apply it only when
`isNvidiaGfe && fps > 60`.

**Response:**

```xml
<root status_code="200">
  <gamesession>1</gamesession>
  <sessionUrl0>rtsp://192.168.1.50:48010</sessionUrl0>
</root>
```

* `gamesession` != `0` ⇒ success. `0` ⇒ failure.
* `sessionUrl0` is **optional** (older GFE omits it). When present, **parse the port from it**
  — this is how we learn a non-default RTSP port. Scheme may be `rtsp://` or `rtspru://`
  (see §6.1).

### 3.7 `/resume`

Identical parameter set and semantics to `/launch`, except:

* Success is indicated by `<resume>` != `0` (not `<gamesession>`).
* Used when `/serverinfo` reports `currentgame != 0` and we want to reconnect to the
  already-running app.
* The `rikey`/`rikeyid` must be **regenerated** for the resumed session (a resume is a new
  streaming session even though the game keeps running).

### 3.8 `/cancel` (quit the running app)

`GET /cancel?uniqueid=..&uuid=..` over **HTTPS**.

```xml
<root status_code="200"><cancel>1</cancel></root>
```

`<cancel>` == `0` means the quit failed (commonly: another client owns the session).

### 3.9 `/unpair`

`GET /unpair?uniqueid=..&uuid=..` over **plaintext HTTP**. Removes our pairing. Also used
internally to **abort a failed pairing attempt** — see §4.

---

## 4. Pairing — the PIN challenge/response

This is the trickiest part of the protocol. Get it exactly right.

### 4.0 Setup

Before starting, fetch `/serverinfo` (plaintext) and read `appversion` to pick the hash:

```
gen = major(appversion)
hashAlgo   = if (gen >= 7) SHA-256 else SHA-1
hashLength = if (gen >= 7) 32       else 20
```

Generate and display a **4-digit decimal PIN** to the user (`"%d%d%d%d"` of four
`SecureRandom.nextInt(10)` — leading zeros are legal and must be preserved as characters).
The user types this PIN into the host (GFE popup / Sunshine web UI).

All five HTTP calls below go to **`/pair` on plaintext HTTP (47989)** except the final
challenge, and every one of them carries this prefix in the query string:

```
devicename=roth&updateState=1&<phase-specific params>&uniqueid=..&uuid=..
```

`devicename=roth` is a legacy constant. **UNVERIFIED** whether any host validates it; it is
sent verbatim by every known client, so send it verbatim. (`roth` was the codename of the
NVIDIA Shield tablet.)

Every response must contain `<paired>1</paired>`. **Any `<paired>` value other than `1`, at any
phase, means abort: call `/unpair` and report failure.** The `/unpair` cleanup is mandatory —
leaving a half-finished pairing on the host wedges subsequent attempts.

### 4.1 Key derivation

```
salt      = SecureRandom(16 bytes)
saltedPin = salt || UTF8(pinString)          // 16 + 4 = 20 bytes
aesKey    = hashAlgo(saltedPin)[0 .. 15]     // first 16 bytes of the digest
```

* The PIN is appended as its **ASCII/UTF-8 digit characters**, not as a number.
* For SHA-256 we take the first 16 of 32 bytes; for SHA-1 the first 16 of 20.

### 4.2 The AES mode used for pairing — read carefully

Pairing uses **AES-128 in ECB mode with NO padding**, applied block-by-block, where the input
is **zero-padded** up to a multiple of 16 bytes.

That is:

```
paddedLen = (len + 15) and 15.inv()      // round up to multiple of 16
buf       = input zero-extended to paddedLen
output    = for each 16-byte block: AES-ECB-encrypt(block, aesKey)   // or decrypt
```

Concretely in Kotlin: `Cipher.getInstance("AES/ECB/NoPadding")` with a manually zero-padded
input. **Do not use PKCS5/PKCS7 padding here** — it will produce an extra block and the host
will reject the value. Decryption is the same in reverse, and the plaintext is simply
truncated to the length you need (the trailing zero bytes are ignored).

### 4.3 Phase 1 — `getservercert`

**Request** (no read timeout — the host blocks here until the user enters the PIN, which can be
minutes; set connect timeout normally but **read timeout = infinite/very large**):

```
GET /pair?devicename=roth&updateState=1
        &phrase=getservercert
        &salt=<hex(salt)>
        &clientcert=<hex(PEM-ASCII-bytes of our certificate)>
        &uniqueid=..&uuid=..
```

**Response:**

```xml
<root status_code="200">
  <paired>1</paired>
  <plaincert>2d2d2d2d2d424547494e...</plaincert>
</root>
```

* `plaincert` is the **hex of the PEM text bytes** of the host's certificate. Hex-decode, then
  parse the resulting PEM/ASCII with `CertificateFactory.getInstance("X.509")`
  (`generateCertificate` accepts PEM).
* **`plaincert` may be absent or empty.** That means *another client is already mid-pairing*
  with this host. Treat as a distinct outcome (`ALREADY_IN_PROGRESS`), call `/unpair`, and tell
  the user to try again in a moment.
* Store this server certificate — it is the TLS pin used for all future HTTPS calls (§3.1).

### 4.4 Phase 2 — `clientchallenge`

```
clientChallenge          = SecureRandom(16 bytes)
encryptedClientChallenge = AES-ECB-encrypt(clientChallenge, aesKey)     // 16 bytes in, 16 out
```

```
GET /pair?...&clientchallenge=<hex(encryptedClientChallenge)>
```

**Response:** `<paired>1</paired>` and `<challengeresponse>` (hex).

```
decrypted     = AES-ECB-decrypt(hex2bytes(challengeresponse), aesKey)
serverResponse  = decrypted[0 .. hashLength-1]          // 32 bytes (SHA-256) or 20 (SHA-1)
serverChallenge = decrypted[hashLength .. hashLength+15] // 16 bytes
```

Note the decrypted buffer is longer than `hashLength + 16` because of block rounding; ignore
the tail.

### 4.5 Phase 3 — `serverchallengeresp`

```
clientSecret      = SecureRandom(16 bytes)
challengeRespHash = hashAlgo( serverChallenge || clientCert.signature || clientSecret )
encrypted         = AES-ECB-encrypt(challengeRespHash, aesKey)
```

`clientCert.signature` is the **signature bytes of our own X.509 certificate**
(`X509Certificate.getSignature()` in Java — the raw signature value of the cert, typically 256
bytes for SHA256withRSA-2048). This is a fixed property of our identity, not a fresh signature.

```
GET /pair?...&serverchallengeresp=<hex(encrypted)>
```

**Response:** `<paired>1</paired>` and `<pairingsecret>` (hex).

```
serverSecretResp = hex2bytes(pairingsecret)
serverSecret     = serverSecretResp[0 .. 15]              // 16 bytes
serverSignature  = serverSecretResp[16 ..]                // remainder
```

**Two verifications, both mandatory:**

1. **Authenticity (MITM check).**
   `verify(serverSecret, serverSignature)` using the **server certificate's public key** with
   `SHA256withRSA` (or `SHA256withECDSA` if the server key is EC).
   Failure ⇒ abort, `/unpair`, report **FAILED** (possible MITM).

2. **PIN correctness.**
   ```
   expected = hashAlgo( clientChallenge || serverCert.signature || serverSecret )
   ```
   Compare to the `serverResponse` we extracted in phase 2. Mismatch ⇒ abort, `/unpair`,
   report **PIN_WRONG** (this is the "user typed the wrong PIN" path, and must be shown
   differently from a generic failure).

   Note this hash uses **our** `clientChallenge` from phase 2 and the **server's** certificate
   signature — the mirror image of the phase-3 hash.

### 4.6 Phase 4 — `clientpairingsecret`

```
clientPairingSecret = clientSecret || sign(clientSecret, ourPrivateKey)    // SHA256withRSA
```

```
GET /pair?...&clientpairingsecret=<hex(clientPairingSecret)>
```

**Response:** `<paired>1</paired>`.

### 4.7 Phase 5 — the HTTPS pairing challenge

The final step, and it is easy to miss: **the host does not consider us paired until we make
one successful client-certificate HTTPS request.**

```
GET https://<host>:<HttpsPort>/pair?devicename=roth&updateState=1&phrase=pairchallenge&uniqueid=..&uuid=..
```

using the TLS setup from §3.1 (our client cert, pinned to the server cert we just learned).

**Response:** `<paired>1</paired>` ⇒ **PAIRED**. Anything else ⇒ `/unpair` and fail.

### 4.8 Pairing state machine summary

```
NOT_PAIRED
   │ user taps "Pair with PIN"
   ▼
GENERATE_PIN ──show PIN to user──▶ PHASE1 (blocking, no read timeout)
   │ plaincert empty ⇒ ALREADY_IN_PROGRESS ──▶ /unpair ──▶ NOT_PAIRED
   ▼
PHASE2 ──▶ PHASE3 ──(sig bad)──▶ FAILED(MITM) ──▶ /unpair
   │        └──(hash mismatch)──▶ PIN_WRONG    ──▶ /unpair
   ▼
PHASE4 ──▶ PHASE5(HTTPS) ──▶ PAIRED   (persist serverCert + hostUuid)
```

**Cancellation:** if the user backs out while phase 1 is blocked, we must (a) close the socket
and (b) call `/unpair` so the host dismisses its PIN prompt.

---

## 5. Streaming session negotiation — data we must prepare

Before RTSP we must have:

| Item | Where it comes from |
|---|---|
| `riKey` (16 bytes) | `SecureRandom`, sent hex in `/launch?rikey=` |
| `riKeyId` (int32) | `SecureRandom.nextInt()`, sent decimal in `/launch?rikeyid=` |
| Negotiated width/height/fps | User settings, clamped by host capabilities |
| `packetSize` | Video RTP payload size. **1024** for remote/WAN, **1392** for LAN. Must be a multiple of 16 when video encryption is on. |
| `supportedVideoFormats` | Bitmask from §7.1, computed from decoder probing (§7.2) |
| `audioConfiguration` | §8.2 |
| `attachedGamepadMask` | Bitfield of currently-connected controllers |
| `encryptionFlags` | §6.4 |

The **remote-input AES IV** starts as the 16-byte big-endian-ish encoding of `riKeyId`:
the first 4 bytes of the IV are the big-endian `riKeyId` and the remaining 12 bytes are zero.
**UNVERIFIED in detail** — the reference implementation initializes `currentAesIv` from
`riKeyId` and, on Gen 7+, subsequently *replaces* the IV with the last 16 bytes of each
transmitted ciphertext (chaining). See §9.2 for exactly what we implement and what we log.

---

## 6. RTSP handshake

### 6.1 Transport

* **Default: plain TCP to port 48010** (the port parsed from `sessionUrl0`, else 48010).
* **`rtspru://` scheme** in `sessionUrl0` means the host wants RTSP carried **over ENet
  (reliable UDP) on the same port number**, instead of TCP. This is a Sunshine extension.
  * v1 rule: **implement TCP RTSP only.** If `sessionUrl0` uses `rtspru://`, still connect via
    TCP to that port — Sunshine listens on TCP as well. Log it.
    **UNVERIFIED:** whether all Sunshine builds keep the TCP RTSP listener open when
    advertising `rtspru`. If TCP connect fails on a Sunshine host, that is the first thing to
    suspect, and ENet-RTSP becomes a required follow-up.

* Connect timeout: 10 s is a sane value. Once connected, disable Nagle (`TCP_NODELAY`).

### 6.2 Message format

RTSP/1.0, textual, CRLF line endings, exactly like HTTP:

```
<METHOD> <target> RTSP/1.0\r\n
CSeq: <n>\r\n
X-GS-ClientVersion: 14\r\n
<other headers>\r\n
\r\n
<optional binary payload>
```

* `CSeq` increments from 1 for every request.
* `X-GS-ClientVersion` is the RTSP client version. Modern hosts expect **`14`**.
  **UNVERIFIED** whether a lower value degrades anything on Sunshine; `14` is what current
  clients send and what this spec mandates.
* Responses look like `RTSP/1.0 200 OK\r\n<headers>\r\n\r\n<payload>`.
* **Known host quirk:** some GFE builds emit a malformed status line / mis-ordered `CSeq`.
  Our parser must be forgiving: split on the first `\r\n\r\n` for header/body, tolerate an
  absent `CSeq`, and never crash on an unknown header.
* Payload length is given by `Content-length`.

### 6.3 The four exchanges, in order

Let `target` be `rtsp://<hostAddress>:<rtspPort>` (bracketed for IPv6).

Stream-id targets depend on generation:

| Stream | Gen ≥ 5 target | Gen < 5 target |
|---|---|---|
| audio | `streamid=audio/0/0` | `streamid=audio` |
| video | `streamid=video/0/0` | `streamid=video` |
| control | `streamid=control/13/0` if `appversion ≥ 7.1.431`, else `streamid=control/1/0` | n/a |

#### (1) OPTIONS

```
OPTIONS rtsp://<host>:<port> RTSP/1.0
CSeq: 1
X-GS-ClientVersion: 14
```

Expect `200`. We do not parse the `Public:` header; this is a liveness probe.

#### (2) DESCRIBE

```
DESCRIBE rtsp://<host>:<port> RTSP/1.0
CSeq: 2
X-GS-ClientVersion: 14
Accept: application/sdp
If-Modified-Since: Thu, 01 Jan 1970 00:00:00 GMT
```

Expect `200` with an **SDP body describing the host's capabilities**. What we extract:

* **`a=fmtp:97 surround-params=<N><streams><coupledStreams><mapping…>`** — the Opus
  multistream configuration for `N` channels. See §8.3.
* **`sprop-parameter-sets`** may appear for H.264 — we do **not** need it (we get SPS/PPS
  in-band from the video stream), but log it.
* Presence of Sunshine-specific attributes tells us about extensions. **UNVERIFIED:** the
  complete set of attributes Sunshine emits in DESCRIBE. Log the entire SDP body at debug
  level on first connect to a new host so we can learn.

`If-Modified-Since` is sent verbatim by existing clients; some GFE builds require the header
to be present. Send it.

#### (3) SETUP × 3 (audio, then video, then control)

**Order matters** — audio first, then video, then control. The audio SETUP is what establishes
the RTSP `Session` id used by everything after it.

```
SETUP rtsp://<host>:<port>/<streamid target> RTSP/1.0
CSeq: n
X-GS-ClientVersion: 14
Transport: unicast;X-GS-ClientPort=50000-50001
If-Modified-Since: Thu, 01 Jan 1970 00:00:00 GMT
Session: <sessionId>            <-- present on all SETUPs after the first
```

`Transport: unicast;X-GS-ClientPort=50000-50001` is sent as a literal constant. The port range
in it is **not** actually used for binding — we bind ephemeral local UDP sockets. Send it
verbatim.

From each SETUP **response**:

* **`Transport:` header** contains `server_port=<p>[-<p2>]`. Parse `<p>`:
  * audio SETUP ⇒ **audio RTP port** (fallback 48000)
  * video SETUP ⇒ **video RTP port** (fallback 47998)
  * control SETUP ⇒ **control/ENet port** (fallback 47999)
* **`Session:` header** (audio SETUP): value may be `DEADBEEFCAFE;timeout=90`. **Take only the
  substring before the first `;`.** Sending the full string back causes `454 Session Not
  Found` on strict RTSP servers.
* **`X-SS-Ping-Payload:`** (Sunshine, on audio and video SETUP): a **16-character** payload we
  must echo in our UDP keep-alive pings (§7.5). If absent, use the legacy 4-byte ping.
* **`X-SS-Connect-Data:`** (Sunshine, on control SETUP): an unsigned 32-bit value (parse with
  base auto-detection — it may be `0x`-prefixed) to pass as ENet connect data. If absent, use `0`.

The control SETUP is only performed when `gen >= 5`.

#### (4) ANNOUNCE — the client's stream configuration

```
ANNOUNCE rtsp://<host>:<port>/<target> RTSP/1.0
CSeq: n
X-GS-ClientVersion: 14
Session: <sessionId>
Content-type: application/sdp
Content-length: <len>

<SDP payload>
```

Target: the **control stream id** (`streamid=control/13/0`) when `appversion ≥ 7.1.431`,
otherwise `streamid=video`.

Expect `200`. A non-200 here means the host rejected our configuration — the most common
real-world cause is an unsupported resolution/codec/bitrate combination. Surface the status
code and message.

#### (5) PLAY × 2

```
PLAY rtsp://<host>:<port>/streamid=video RTSP/1.0
CSeq: n
Session: <sessionId>
X-GS-ClientVersion: 14
```

then the same for `streamid=audio`. Both must return `200`.

**After PLAY succeeds, media begins flowing on the negotiated UDP ports.**

### 6.4 The ANNOUNCE SDP payload

Structure:

```
v=0\r\n
o=android 0 <rtspClientVersion> IN <IP4|IP6> <hostAddress>\r\n
s=NVIDIA Streaming Client\r\n
<a= attribute lines, one per option>
<tail: media lines>
```

Each option is serialized as `a=<name>:<value>\r\n`. Values are either ASCII strings or, for
some Gen-3 options, **raw little-endian binary integers embedded in the line** — those are
Gen 3 only and we do not need them for Gen 5/7 hosts.

The tail contains the media description referencing the negotiated video port.
**UNVERIFIED (low risk):** the precise `m=` lines in the tail. Existing clients emit a
minimal tail referencing the video port; hosts do not appear to parse it strictly. Implement:

```
t=0 0\r\n
m=video <videoPort>  \r\n
```

and log the host's response. If ANNOUNCE fails on a real host, the tail is the first thing to
adjust.

#### Attribute set we must send (Gen 5/7, i.e. everything modern)

**Video geometry and rate**

| Attribute | Value |
|---|---|
| `x-nv-video[0].clientViewportWd` | width |
| `x-nv-video[0].clientViewportHt` | height |
| `x-nv-video[0].maxFPS` | fps |
| `x-nv-video[0].packetSize` | packet size (1392 LAN / 1024 WAN; minus 32 if video encryption is on, keeping it a multiple of 16) |
| `x-nv-video[0].rateControlMode` | `4` |
| `x-nv-video[0].timeoutLengthMs` | `7000` |
| `x-nv-video[0].framesWithInvalidRefThreshold` | `0` |
| `x-nv-video[0].videoEncoderSlicesPerFrame` | number of slices we want (1 for hardware decoders; more only helps multi-threaded software decode) |
| `x-nv-video[0].clientRefreshRateX100` | display refresh × 100 (e.g. `12000` for 120 Hz) — Gen 7+ only |

**Bitrate** (all in kbps; `bitrate` is our configured value)

| Attribute | Value |
|---|---|
| `x-nv-video[0].initialBitrateKbps` | bitrate |
| `x-nv-video[0].initialPeakBitrateKbps` | bitrate |
| `x-nv-vqos[0].bw.minimumBitrateKbps` | bitrate |
| `x-nv-vqos[0].bw.maximumBitrateKbps` | bitrate |
| `x-ml-video.configuredBitrateKbps` | bitrate (Sunshine extension — lets the host know the *user's* number even if we adjusted the negotiated one) |

Setting min == max == our value **disables the host's adaptive bitrate**, which is what we
want: we control quality from the client. (Gen ≥ 7 uses the `…Kbps` names; older gens use
`x-nv-vqos[0].bw.minimumBitrate` / `maximumBitrate` without the suffix, and
`x-nv-video[0].averageBitrate=4` / `peakBitrate=4`.)

**FEC and QoS**

| Attribute | Value |
|---|---|
| `x-nv-vqos[0].fec.enable` | `1` |
| `x-nv-vqos[0].fec.repairPercent` | `20` on LAN, `5` on WAN — this is the parity overhead percentage |
| `x-nv-vqos[0].fec.minRequiredFecPackets` | `2` |
| `x-nv-vqos[0].bllFec.enable` | `0` |
| `x-nv-vqos[0].videoQualityScoreUpdateTime` | `5000` |
| `x-nv-vqos[0].qosTrafficType` | `5` on LAN, `0` on WAN |
| `x-nv-aqos.qosTrafficType` | `4` on LAN, `0` on WAN |
| `x-nv-vqos[0].drc.enable` | `1` if we accept dynamic resolution changes, else `0`. **Set `0` for v1** — dynamic resolution change requires reconfiguring MediaCodec mid-stream. |
| `x-nv-vqos[0].drc.tableType` | `2` (only meaningful when drc.enable=1) |
| `x-nv-general.enableRecoveryMode` | `0` |
| `x-nv-general.useReliableUdp` | `13` on Gen 7+ (bitmask enabling reliable-UDP for several substreams), `1` on Gen 5 |
| `x-nv-ri.useControlChannel` | `1` — send input over the control channel rather than a separate socket. **Required for our design.** |

**Codec selection**

| Attribute | Value |
|---|---|
| `x-nv-vqos[0].bitStreamFormat` | `0` = H.264, `1` = HEVC, `2` = AV1 |
| `x-nv-clientSupportHevc` | `1` when requesting HEVC, `0` otherwise |
| `x-nv-video[0].dynamicRangeMode` | `1` for HDR (10-bit), `0` for SDR |
| `x-nv-video[0].maxNumReferenceFrames` | `1` when the decoder supports reference-frame invalidation, `0` otherwise. **Send `0` for v1** (we do not implement RFI). |
| `x-nv-video[0].encoderFeatureSetting` | `0` when requesting HEVC without RFI |
| `x-nv-video[0].encoderCscMode` | `(colorSpace << 1) | colorRange`, Gen 7+ only. `colorSpace`: 0=BT.601, 1=BT.709, 2=BT.2020. `colorRange`: 0=limited, 1=full. |

**Audio**

| Attribute | Value |
|---|---|
| `x-nv-audio.surround.numChannels` | 2, 6, or 8 |
| `x-nv-audio.surround.channelMask` | `0x3` stereo, `0x3F` 5.1, `0x63F` 7.1 — sent as a **decimal** integer |
| `x-nv-audio.surround.enable` | `1` when channels > 2, else `0` |
| `x-nv-audio.surround.AudioQuality` | `1` for high-quality surround (only when bitrate is high, channels > 2, the host advertised support, and our decoder is fast), else `0` |
| `x-nv-aqos.packetDuration` | **5** (ms) by default; **10** for slow decoders or low bitrate. Gen 7+ only; legacy is always 5. |

**Sunshine-only extensions** (send only when `isSunshineish`)

| Attribute | Value |
|---|---|
| `x-ml-general.featureFlags` | client feature bitmask — `0x1` = we send per-frame FEC status, `0x2` = we support session-id v1. Send `0x3`. |
| `x-ss-general.encryptionEnabled` | bitmask of encryption features we are enabling (§6.5) |
| `x-ss-video[0].chromaSamplingType` | `1` for YUV 4:4:4, `0` for 4:2:0 |

**Gen-3-only options** (`x-nv-general.serverAddress`, `x-nv-video[N].transferProtocol`,
`x-nv-vqos[0].bw.flags=14083`, `videoQosMaxConsecutiveDrops=0`, per-index `rateControlMode`)
are documented here for completeness but **not required** — do not implement unless a Gen 3
host actually shows up.

### 6.5 Encryption feature negotiation (Sunshine)

Bit values used in `x-ss-general.encryptionEnabled`:

| Bit | Name | Meaning |
|---|---|---|
| `0x01` | `SS_ENC_CONTROL_V2` | New-style AES-GCM control-stream encryption |
| `0x02` | `SS_ENC_VIDEO` | Video payload encryption |
| `0x04` | `SS_ENC_AUDIO` | Audio payload encryption |

**UNVERIFIED:** the exact numeric bit assignments above. They are inferred from the ordering of
the feature flags and the way they are combined. The **supported** set is advertised by the
host — where? The reference reads `EncryptionFeaturesSupported` /
`EncryptionFeaturesRequested`, which come from the host's `/serverinfo` or DESCRIBE.
**UNVERIFIED which.** 

**v1 decision:** send `x-ss-general.encryptionEnabled=0` (no video/audio encryption) and do
**not** enable control-v2 unless we can confirm the flag values against a live Sunshine host.
Plain control-stream framing (§9) works on all hosts. Revisit in a later phase; the video
path must then also handle the 32-byte `ENC_VIDEO_HEADER` described in §7.6.

---

## 7. Video stream (UDP)

### 7.1 Codec/profile bitmask (`supportedVideoFormats`)

```
VIDEO_FORMAT_H264            = 0x0001   // H.264 High Profile
VIDEO_FORMAT_H264_HIGH8_444  = 0x0004   // H.264 High 4:4:4 8-bit
VIDEO_FORMAT_H265            = 0x0100   // HEVC Main
VIDEO_FORMAT_H265_MAIN10     = 0x0200   // HEVC Main10 (HDR)
VIDEO_FORMAT_H265_REXT8_444  = 0x0400   // HEVC RExt 4:4:4 8-bit
VIDEO_FORMAT_H265_REXT10_444 = 0x0800   // HEVC RExt 4:4:4 10-bit
VIDEO_FORMAT_AV1_MAIN8       = 0x1000
VIDEO_FORMAT_AV1_MAIN10      = 0x2000
VIDEO_FORMAT_AV1_HIGH8_444   = 0x4000
VIDEO_FORMAT_AV1_HIGH10_444  = 0x8000

MASK_H264   = 0x000F
MASK_H265   = 0x0F00
MASK_AV1    = 0xF000
MASK_10BIT  = 0xAA00      // the Main10/10-bit-444 members of H265 and AV1
MASK_YUV444 = 0xCC04
```

These constants are how we express our capabilities internally; they map onto the SDP
`bitStreamFormat` / `dynamicRangeMode` / `chromaSamplingType` attributes at ANNOUNCE time.

**Colorspace / range constants**

```
COLORSPACE_REC_601 = 0, COLORSPACE_REC_709 = 1, COLORSPACE_REC_2020 = 2
COLOR_RANGE_LIMITED = 0, COLOR_RANGE_FULL = 1
```

Default: REC_709 + limited for SDR; REC_2020 + limited for HDR.

### 7.2 Codec selection algorithm (client side)

1. Probe `MediaCodecList(REGULAR_CODECS)` for decoders for `video/avc`, `video/hevc`,
   `video/av01` — **hardware only** (`MediaCodecInfo.isHardwareAccelerated` on API 29+;
   below that, exclude names starting with `OMX.google.` / `c2.android.`).
2. For each, check `CodecCapabilities.getVideoCapabilities().areSizeAndRateSupported(w,h,fps)`.
3. Build our format mask from what passed.
4. Intersect with what the host supports:
   * HEVC requires `MaxLumaPixelsHEVC != 0`.
   * HDR requires a 10-bit profile on both sides *and* the app's `IsHdrSupported=1`.
   * AV1 requires the host's `ServerCodecModeSupport` AV1 bits (treat as a hint, §3.3.1).
5. Apply the user's **Preferred Codec** setting (`H.264` | `Auto`, per the reference UI —
   with `HEVC` and `AV1` as additional explicit choices in our Android version):
   * `H.264` ⇒ force `bitStreamFormat=0`.
   * `Auto` ⇒ prefer AV1 > HEVC > H.264, but **only pick AV1 if the decoder is hardware and
     the device is known-good**; AV1 hardware decode at low latency is spotty. Default the
     Auto ladder to HEVC > H.264 unless AV1 probes clean.
6. HDR forces a 10-bit profile, which forces HEVC or AV1.
7. YUV 4:4:4 forces the 444 profile variants — **Sunshine-only**, and rarely supported by
   mobile decoders. Gate the toggle on a successful decoder probe.

### 7.3 RTP header

Every video datagram begins with a 12-byte RTP header (`FIXED_RTP_HEADER_SIZE = 12`,
`MAX_RTP_HEADER_SIZE = 16` when a header extension is present):

```
offset 0  : uint8   header      // V/P/X/CC bits; bit 0x10 (FLAG_EXTENSION) = extension present
offset 1  : uint8   packetType  // payload type
offset 2  : uint16  sequenceNumber   BIG-ENDIAN
offset 4  : uint32  timestamp        BIG-ENDIAN
offset 8  : uint32  ssrc             BIG-ENDIAN
```

If `header and 0x10 != 0`, a 4-byte extension follows the fixed header (total 16). Skip it.

### 7.4 NV video packet header

Immediately after the RTP header (and after the optional encryption header, §7.6):

```
offset 0  : uint32 streamPacketIndex   LITTLE-ENDIAN   // global packet counter (top 24 bits meaningful)
offset 4  : uint32 frameIndex          LITTLE-ENDIAN
offset 8  : uint8  flags
offset 9  : uint8  extraFlags
offset 10 : uint8  multiFecFlags
offset 11 : uint8  multiFecBlocks
offset 12 : uint32 fecInfo             LITTLE-ENDIAN
--- 16 bytes total; video payload follows ---
```

**`flags`:**

```
FLAG_CONTAINS_PIC_DATA = 0x1   // this packet carries encoded picture bytes
FLAG_EOF               = 0x2   // last packet of the frame
FLAG_SOF               = 0x4   // first packet of the frame
```

**`extraFlags`:**

```
NV_VIDEO_PACKET_EXTRA_FLAG_LTR_FRAME = 0x1   // this frame is a long-term reference frame
```

**`fecInfo` bit layout** (after converting from little-endian):

| Bits | Field |
|---|---|
| `fecInfo & 0x3FF000 >> 12` | **fecIndex** — index of this shard within its FEC block (0-based; data shards come first, then parity shards) |
| `fecInfo & 0xFF0 >> 4` | **fecPercentage** — parity overhead as a percentage of data shards |
| `fecInfo & 0xFFC00000 >> 22` | **dataShards** — number of data shards in this FEC block |

Derived:

```
parityShards = (dataShards * fecPercentage + 99) / 100      // ceiling division
totalShards  = dataShards + parityShards
blockBaseSequenceNumber = rtpSequenceNumber - fecIndex      // 16-bit wrapping subtract
```

`multiFecBlocks` / `multiFecFlags` describe frames split across **multiple** FEC blocks
(needed for very large frames). `multiFecBlocks` is the block count; the current block index
is carried in `multiFecFlags`. **UNVERIFIED:** the exact bit packing of `multiFecFlags` (it
appears to encode the current block index in its low bits along with first/last markers).
**v1 approach:** handle `multiFecBlocks <= 1` fully; when `multiFecBlocks > 1`, treat each
block independently keyed by `(frameIndex, blockIndex)` derived from `multiFecFlags & 0x3`,
and log a warning. Frames that fail this path simply request an IDR.

### 7.5 UDP keep-alive ping

The host will not send video to us until it has seen a packet from our source port (NAT/
firewall pinhole). We must ping from **the same socket we receive on**, every **500 ms**,
starting immediately after PLAY, on **both** the video and audio sockets:

* **Legacy (GFE, and Sunshine without the extension):** the 4 ASCII bytes `PING`
  (`0x50 0x49 0x4E 0x47`).
* **Sunshine with `X-SS-Ping-Payload`:** the 16-byte payload from the SETUP response,
  followed by a **big-endian uint32 sequence number** starting at 1 and incrementing per ping
  (total 20 bytes).

Ignore all send errors here — the host may not have bound its socket yet, which produces ICMP
port-unreachable. Errors are handled on the receive side.

### 7.6 Video payload encryption (Sunshine, optional)

When `SS_ENC_VIDEO` is enabled, a 32-byte header sits between the RTP header and the NV header:

```
offset 0  : uint8[12] iv
offset 12 : uint32    frameNumber
offset 16 : uint8[16] tag         // AES-GCM authentication tag
```

Decrypt with AES-128-GCM using the session key. Because this header is 32 bytes (a multiple
of 16), the FEC block size stays 16-aligned — which is why `packetSize` must be reduced by 32
and stay a multiple of 16 when encryption is on.

**v1: not implemented** (see §6.5). The parser must still *detect* an unexpected encrypted
packet and fail with a clear message rather than producing garbage.

### 7.7 Reassembly algorithm

For each incoming datagram:

1. Parse RTP header; drop if `length < 12`.
2. If `packetType` is the FEC-status/control type rather than video data, route accordingly.
3. Parse the NV header.
4. Compute `blockBaseSequenceNumber`, `dataShards`, `parityShards`.
5. Insert into the FEC block keyed by `(frameIndex, blockBaseSequenceNumber)`.
6. **If all `dataShards` data shards arrived** ⇒ concatenate their payloads in sequence order;
   the frame is complete (subject to §7.8).
7. **If ≥ `dataShards` of the `totalShards` arrived (data + parity)** ⇒ run Reed-Solomon
   recovery over `totalShards` shards of `blockSize` bytes each, with a `marks[]` array
   flagging which are missing, to recover the missing data shards.
8. **If the block times out** (a later frame has begun and we still lack shards) ⇒ discard the
   frame and **request an IDR** (§9.5).

**Reed-Solomon parameters:** GF(2^8), systematic Vandermonde/Cauchy matrix, as implemented by
the `nanors`/`jerasure`-style `reed_solomon_new(dataShards, parityShards)` family.
All shards in a block are **the same size** (`blockSize`), zero-padded as needed.

**UNVERIFIED (and this is the single riskiest detail in the whole document):** the exact
Reed-Solomon variant — specifically the generator matrix construction and whether the
implementation is "Cauchy" or "Vandermonde with the systematic-form row reduction". Two
RS implementations that both claim GF(2^8) will **not** interoperate if the matrix differs.

**Mitigation, mandatory:** implement the reassembly path so that **FEC recovery is optional**.
When all data shards arrive (the overwhelmingly common case on a LAN), we never touch the RS
code. Ship phase 1 with RS recovery disabled (drop-and-request-IDR on loss); add RS in a later
phase and validate it against captured traffic. See `04-ROADMAP.md`.

### 7.8 Frame assembly into a decode unit

* Concatenate data-shard payloads in ascending sequence order, **excluding** shards where
  `FLAG_CONTAINS_PIC_DATA` is clear.
* The result is an Annex-B elementary stream fragment: start codes (`00 00 00 01` /
  `00 00 01`) followed by NAL units. **Feed it to MediaCodec verbatim** — do not attempt to
  parse or re-frame NALs.
* Detect IDR/keyframes by scanning for the NAL type (H.264 type 5, or SPS type 7; HEVC types
  32–34 for VPS/SPS/PPS and 16–21 for IRAP; AV1 sequence header OBU). Used only to know
  whether we can start decoding — set `BUFFER_FLAG_KEY_FRAME` accordingly.
* **The first frame we submit must be a keyframe.** Drop everything until one arrives.
* `frameIndex` is monotonically increasing; a gap means a dropped frame ⇒ request IDR.
* Presentation timestamp: use the RTP `timestamp` field for relative ordering but pass
  `System.nanoTime()/1000` as the MediaCodec `presentationTimeUs`. We render immediately; PTS
  is not used for scheduling.

---

## 8. Audio stream (UDP)

### 8.1 Transport

Audio arrives on the negotiated audio port as RTP with the **same 12-byte header** as video
(§7.3).

| `packetType` | Meaning |
|---|---|
| **97** | Opus audio data |
| **127** | FEC parity shard |

### 8.2 Audio configuration encoding

```
MAKE_AUDIO_CONFIGURATION(channelCount, channelMask) =
        (channelMask shl 16) or (channelCount shl 8) or 0xCA      // 0xCA = magic marker

AUDIO_CONFIGURATION_STEREO      = MAKE(2, 0x3)
AUDIO_CONFIGURATION_51_SURROUND = MAKE(6, 0x3F)
AUDIO_CONFIGURATION_71_SURROUND = MAKE(8, 0x63F)

CHANNEL_COUNT_FROM(x) = (x shr 8)  and 0xFF
CHANNEL_MASK_FROM(x)  = (x shr 16) and 0xFFFF
```

**UNVERIFIED:** the low byte `0xCA` marker value. It is a sentinel used to distinguish a
composed configuration from a legacy small integer. Our code never sends this composite value
on the wire — only the derived `numChannels` / `channelMask` (SDP) and:

```
surroundAudioInfo = (channelMask shl 16) or channelCount      // the /launch query parameter
```

which **is** on the wire and is well established.

### 8.3 Opus multistream configuration

Sample rate is **always 48000 Hz**.

**Stereo** needs no negotiation:

```
channelCount = 2, streams = 1, coupledStreams = 1, mapping = [0, 1]
```

**Surround** is parsed from the DESCRIBE SDP. Look for the literal prefix:

```
a=fmtp:97 surround-params=<N>
```

where `<N>` is the channel count we requested (6 or 8). Immediately after the prefix comes a
run of **single ASCII digit characters**, with no separators:

```
<streams><coupledStreams><mapping[0]><mapping[1]>...<mapping[N-1]>
```

So for 6 channels the parameter string is `6` followed by 2 + 6 = 8 digits. Each digit is a
value 0–9 read as `char - '0'`.

**Channel-order fix-up (required for 6 and 8 channels):** the host's normal-quality mapping
uses the order `FL FR C RL RR SL SR LFE`, while decoders (and our `AudioTrack` channel mask)
expect `FL FR C LFE RL RR SL SR`. Transform the parsed mapping:

```
original = mapping.copyOf()
mapping[3] = original[channelCount - 1]                       // LFE moves to index 3
copy original[3 .. channelCount-2]  into  mapping[4 ..]       // slide the rest up
```

**High-quality surround** (`x-nv-audio.surround.AudioQuality=1`) uses a **different**
`surround-params` entry in the SDP and **does not** need the fix-up above.
**UNVERIFIED:** the exact SDP key for the high-quality variant. **v1: do not request high-quality
surround** (always send `AudioQuality=0`); revisit later.

### 8.4 Audio FEC

Fixed geometry, unlike video:

```
RTPA_DATA_SHARDS  = 4
RTPA_FEC_SHARDS   = 2
RTPA_TOTAL_SHARDS = 6
```

FEC blocks are aligned on sequence-number multiples of 4:

```
blockBaseSequenceNumber = (sequenceNumber / 4) * 4
blockBaseTimestamp      = timestamp - ((sequenceNumber - blockBaseSequenceNumber) * packetDurationMs)
```

A parity packet (`packetType == 127`) carries a **12-byte big-endian header** after the RTP
header, then the parity payload:

```
offset 0  : uint8  fecShardIndex        // 0..1
offset 1  : uint8  payloadType          // the payload type of the data shards (97)
offset 2  : uint16 baseSequenceNumber   BIG-ENDIAN
offset 4  : uint32 baseTimestamp        BIG-ENDIAN
offset 8  : uint32 ssrc                 BIG-ENDIAN
```

Recovery uses RS(4,2) over GF(2^8) with **constant-size shards**. Same
interoperability caveat as §7.7 applies.

**v1 approach:** implement the block assembly and in-order dequeue, but **skip RS recovery for
audio entirely.** A missing audio packet is handled by feeding the decoder a "packet loss
concealment" hint (see §8.5). Audio loss concealment is far cheaper than getting RS wrong.

Out-of-order wait: hold an incomplete block for at most **10 ms** past when it should have
completed, then release what we have.

### 8.5 Decoding and playback

* Payload after the RTP header is a **raw Opus packet** (one frame of `packetDuration` ms —
  5 ms by default, 10 ms for slow decoders).
* The **first byte of every Opus packet is the TOC byte** and must stay constant for the whole
  stream. Log a warning if it changes (Sunshine legitimately may vary it; GFE must not).
* On a detected gap, invoke packet-loss concealment: decode a null/empty packet, which makes
  libopus synthesize a concealment frame. `MediaCodec`'s Opus decoder has no explicit PLC API;
  the practical substitute is to submit a buffer of the right duration containing silence, or
  simply skip and let `AudioTrack` underrun briefly. **Prefer submitting silence** of exactly
  `packetDuration` ms to keep the timeline aligned.

**Android decode path — two options, pick per §8.6:**

| Option | Notes |
|---|---|
| `MediaCodec` `audio/opus` | Available since API 21 in theory; **reliable from API 29+**. Requires the 3 "codec-specific data" buffers: `csd-0` = the 19-byte OpusHead identification header, `csd-1` = pre-skip nanoseconds (int64 LE), `csd-2` = seek pre-roll nanoseconds (int64 LE, typically 80 000 000). **Multistream (surround) Opus via MediaCodec is unreliable** — many devices only handle mono/stereo. |
| Bundled libopus via NDK | Handles multistream correctly, adds a native dependency. |

**v1 decision:** `MediaCodec` for **stereo only**. Surround (5.1/7.1) is deferred and its
settings rows are disabled with an explanation until we ship the native decoder. This is an
honest limitation, documented in `04-ROADMAP.md`.

`csd-0` (OpusHead) we must construct ourselves, since it is not sent by the host:

```
"OpusHead"          8 bytes ASCII
version = 1         1 byte
channelCount        1 byte
preSkip = 0         2 bytes LE   (312 is the common default; 0 works because we never seek)
sampleRate = 48000  4 bytes LE
outputGain = 0      2 bytes LE
mappingFamily = 0   1 byte       (0 for mono/stereo)
--- 19 bytes ---
```

For surround, `mappingFamily = 1` plus the stream count / coupled count / mapping table appended
— which is exactly the part MediaCodec implementations handle badly.

**Playback:** `AudioTrack` in `MODE_STREAM`, `AudioAttributes` with
`USAGE_GAME` + `CONTENT_TYPE_MOVIE`, `PERFORMANCE_MODE_LOW_LATENCY` (API 26+),
`AudioFormat.ENCODING_PCM_16BIT`, `CHANNEL_OUT_STEREO`, 48 kHz. Buffer size:
`max(AudioTrack.getMinBufferSize(...), bytesFor(30 ms))` — small, because latency matters more
than robustness here.

---

## 9. Control stream (ENet over UDP)

### 9.1 Transport

* **Gen ≥ 5: ENet over UDP** on the negotiated control port (default 47999).
* **Gen 3/4: plain TCP on port 47995** (hardcoded — not the negotiated port). Legacy;
  implement only if a Gen 3/4 host appears.

**ENet is a library, not a trivial protocol.** We must implement enough of it in Kotlin:

* Connect handshake (`ENET_PROTOCOL_COMMAND_CONNECT` with our peer id, window size, channel
  count, MTU, and the 32-bit **connect data** from `X-SS-Connect-Data`).
* Reliable ordered delivery per channel, with sequence numbers and ACKs.
* Unreliable and unsequenced packet flags.
* Fragmentation for packets > MTU.
* Ping/timeout handling.

Parameters:

* **Channel count: 3** (we use two logically — see below).
* Peer count 1, no bandwidth throttling.
* **Connect timeout: 10 s** (`CONTROL_STREAM_TIMEOUT_SEC`).
* Linger on disconnect: 2 s.
* Channel usage: `CTRL_CHANNEL_GENERIC` for periodic pings and FEC status;
  `CTRL_CHANNEL_URGENT` for input, IDR requests, and termination.
  **UNVERIFIED:** the exact numeric channel ids. They are small integers (0 and 1).
  Implement as `GENERIC = 0`, `URGENT = 1` and log; if the host ignores our messages, swapping
  them is the first thing to try.

**This is the largest single implementation risk in the project after RS FEC.** See
`04-ROADMAP.md` for the mitigation (a minimal ENet subset, reliable-only, validated against a
loopback test).

### 9.2 Packet framing

Inside an ENet packet, the control message is:

**Unencrypted (V2 header, Gen 5+):**

```
offset 0 : uint16 type            LITTLE-ENDIAN
offset 2 : uint16 payloadLength   LITTLE-ENDIAN
offset 4 : payload
```

(Gen-5 V1 framing omits `payloadLength` and has only the 2-byte type.)

**Encrypted (`SS_ENC_CONTROL_V2`, Sunshine):**

```
offset 0 : uint16 encryptedHeaderType   // always 0x0001, LITTLE-ENDIAN
offset 2 : uint16 length                // = 4 (seq) + 16 (tag) + plaintext length, LITTLE-ENDIAN
offset 4 : uint32 seq                   // monotonically increasing, LITTLE-ENDIAN
offset 8 : uint8[16] tag                // AES-GCM tag
offset 24: ciphertext of { V2 header + payload }
```

IV construction from `seq` (**before** byte-swapping `seq` into little-endian on the wire):

```
iv = ByteArray(12)                       // 12 bytes for control-v2
iv[0] = seq ushr 0;  iv[1] = seq ushr 8;  iv[2] = seq ushr 16;  iv[3] = seq ushr 24
// remaining bytes zero
```

(The older, non-v2 encrypted control path uses a shorter derivation with only `iv[0] = seq`.)

**v1: unencrypted framing only** (§6.5).

### 9.3 Message type tables

Message type ids differ per generation. The index names are ours; the values are the wire types.

| Index | Meaning | Gen 3 | Gen 4 | Gen 5 | Gen 7 | Gen 7 (encrypted) |
|---|---|---|---|---|---|---|
| 0 | Request IDR frame / Start A | `0x1407` | `0x0606` | `0x0305` | `0x0305` | `0x0302` |
| 1 | Start B | `0x1410` | `0x0609` | `0x0307` | `0x0307` | `0x0307` |
| 2 | Invalidate reference frames | `0x1404` | `0x0604` | `0x0301` | `0x0301` | `0x0301` |
| 3 | Loss stats | `0x140c` | `0x060a` | `0x0201` | `0x0201` | `0x0201` |
| 4 | Frame stats (unused) | `0x1417` | `0x0611` | `0x0204` | `0x0204` | `0x0204` |
| 5 | **Input data** | — | — | `0x0207` | `0x0206` | `0x0206` |
| 6 | Rumble data (host→client) | — | — | — | `0x010b` | `0x010b` |
| 7 | Termination (host→client) | — | — | — | `0x0100` | `0x0109` |
| 8 | HDR mode (host→client) | — | — | — | `0x010e` | `0x010e` |
| 9 | Rumble triggers (host→client) | — | — | — | Sunshine ext | Sunshine ext |
| 10 | Set motion event state (host→client) | — | — | — | Sunshine ext | Sunshine ext |
| 11 | Set RGB LED (host→client) | — | — | — | Sunshine ext | Sunshine ext |
| 12 | DualSense adaptive triggers (host→client) | — | — | — | Sunshine ext | Sunshine ext |

**UNVERIFIED:** the wire type values for indices 9–12 (Sunshine controller-feedback
extensions). We know they exist and what their payloads mean (§9.6) but not their numeric ids.
**v1: ignore unrecognized control message types** (log the type + length at debug). Rumble
(index 6) is the only feedback we act on in v1.

Additional well-known types:

| Type | Direction | Meaning |
|---|---|---|
| `0x0200` | client→host | **Periodic ping** (Gen ≥ 7.1.415). |
| `0x5502` | client→host | Sunshine per-frame FEC status report. |
| `0x0350` | client→host | Long-term-reference frame ACK. |
| `0x0301` | client→host | Reference-frame-invalidation request. |

### 9.4 Session start sequence

After the ENet connection is established:

1. Send **Start A** (index 0) with payload `{0x00, 0x00}` for Gen 5+ (Gen 3 has a 4-int
   payload; Gen 4 has a 1-byte `{0}`).
2. Send **Start B** (index 1) with payload `{0x00}` for Gen 5+ (Gen 3 payload is the four
   little-endian ints `0, 0, 0, 0x0a`).

On Gen 3/4 (TCP control), each of these expects a reply which we read and discard. On ENet we
fire and forget.

### 9.5 Periodic messages (client → host)

**Periodic ping** — required on `appversion ≥ 7.1.415`, sent **reliably** on the generic
channel every `PERIODIC_PING_INTERVAL_MS`:

```
type    = 0x0200
payload = 8 bytes, LITTLE-ENDIAN:
            uint16 4          // "length of payload"
            uint32 0          // timestamp placeholder
            (2 bytes of the 8-byte buffer remain zero)
```

**UNVERIFIED:** `PERIODIC_PING_INTERVAL_MS`. Existing clients use a value on the order of
**500 ms**. Implement `500` as a named constant; if the host times us out, this is the knob.
It must be sent *reliably* because the RTT estimate is derived from the ACK.

**Loss stats** (older hosts, `appversion < 7.1.415`) — sent every `LOSS_REPORT_INTERVAL_MS`
(**UNVERIFIED**, on the order of 50–100 ms), payload little-endian:

```
uint32 lostFrames (0)
uint32 LOSS_REPORT_INTERVAL_MS
uint32 1000
uint64 lastGoodFrameIndex
uint32 0
uint32 0
uint32 0x14
```

**Sunshine per-frame FEC status** (`type 0x5502`) — sent unsequenced/unreliable, one per frame,
all fields **big-endian**:

```
uint32 frameIndex
uint16 highestReceivedSequenceNumber
uint16 nextContiguousSequenceNumber
uint16 missingPacketsBeforeHighestReceived
uint16 totalDataPackets
uint16 totalParityPackets
uint16 receivedDataPackets
uint16 receivedParityPackets
uint8  fecPercentage
uint8  multiFecBlockIndex
uint8  multiFecBlockCount
```

Only send this when `isSunshineish` **and** we advertised `x-ml-general.featureFlags` bit
`0x1`. It drives Sunshine's adaptive FEC.

**IDR request** — send message index 0 on the **urgent** channel whenever we detect a frame we
cannot reconstruct. **Rate-limit it**: at most one per ~100 ms, or a lossy link turns into an
IDR storm that makes things worse.

### 9.6 Messages from host → client

Parse the V2 header, switch on type:

**Rumble** (index 6): payload contains a controller number and two 16-bit motor values.
**UNVERIFIED:** the exact payload layout. The consumed fields are
`(uint16 controllerNumber, uint16 lowFreqRumble, uint16 highFreqRumble)`, and the payload
appears to carry 4 leading bytes before them. **Implementation rule:** if
`payloadLength >= 4 + 6`, read the three uint16s from offset 4 (little-endian); otherwise read
them from offset 0. Log which branch fired. Map to `VibratorManager` (§12.4).

**Termination** (index 7): the session is ending.

```
if (payloadLength >= 6):  uint32 terminationErrorCode, BIG-ENDIAN, read from the payload start
else:                     no code available
```

Known codes worth special-casing:

| Code | Meaning |
|---|---|
| `0x800e9403` | Video encoder failed to convert the input frame |
| `0x80030023` | **UNVERIFIED** — commonly reported as "graceful termination / user closed the app on the host" |
| `0x800e9302` | **UNVERIFIED** — commonly reported as "protected content" (DRM'd window on screen) |

Everything else: show the raw hex code. **Do not invent friendly text for codes we have not
verified.**

**HDR mode change** (index 8): the host toggled HDR mid-stream. Payload carries an enable flag
plus static HDR metadata (mastering display primaries, luminance). We must at minimum read the
enable flag and reconfigure the `SurfaceView`'s HDR state.
**UNVERIFIED:** the metadata layout. v1: read only the first byte as the enable flag; ignore
the rest; log the full payload hex.

**Controller feedback extensions** (indices 9–12): ignore in v1 (§9.3).

### 9.7 Teardown

Ordered shutdown, and **the order matters** — doing it wrong leaves the host stuck with a live
session:

1. Stop sending input.
2. Send the termination/disconnect message on the urgent channel (Gen 7 type `0x0100`).
3. ENet `disconnect` and pump the host for up to `CONTROL_STREAM_LINGER_TIMEOUT_SEC` (2 s) for
   the disconnect ACK.
4. Close the video/audio sockets, stop the ping threads.
5. Stop and release `MediaCodec` and `AudioTrack`.
6. Optionally `GET /cancel` if the user chose "quit app" rather than "disconnect".

If we are killed abruptly (process death, network loss), the host times the session out on its
own — typically in 10–30 s. On the next `/serverinfo` we will see `currentgame != 0` and can
offer Resume/Quit.

---

## 10. Input protocol

All input travels **inside control-stream messages of type index 5** ("input data"), as an
**encrypted blob**. The structure is:

```
[ ENet packet ]
  [ control header: type=<inputDataType>, payloadLength=N ]
    [ uint32 encryptedLength  BIG-ENDIAN ]
    [ ciphertext ... ]
```

### 10.1 Input encryption

| Generation | Algorithm |
|---|---|
| Gen ≥ 7 | **AES-128-GCM**. Output layout is `tag(16 bytes) || ciphertext`. Note the tag comes **first**. |
| Gen < 7 | **AES-128-CBC** with each message padded to the 16-byte block size (PKCS#7). |

Key: the 16-byte `riKey` we sent in `/launch?rikey=`.

IV: initialized from `riKeyId` (§5). On Gen 7+, after each message is sent, the IV for the
next message is taken from **the last 16 bytes of the ciphertext just produced**
(when the ciphertext is at least `16 + 16` bytes).

**UNVERIFIED — read carefully:** the precise IV chaining rule is the least well-documented part
of the input path, and getting it wrong means the host silently discards all our input (no
error, just an unresponsive game). **Implementation plan:**

1. Implement IV = 16 bytes where `iv[0..3] = riKeyId` big-endian and `iv[4..15] = 0`.
2. After each encrypt, if `ciphertextLen >= 32`, copy the final 16 bytes of the ciphertext
   into the IV for the next message.
3. Put this behind a strategy interface with a second implementation that uses a simple
   incrementing counter IV, selectable by a debug setting.
4. **Acceptance test:** move the mouse and see the host's cursor move. If it does not, the IV
   rule is the first suspect, not the packet layout.

For AES-GCM, Java requires a 12-byte IV for `GCMParameterSpec`; the protocol supplies 16.
Use the **first 12 bytes** of the 16-byte IV. **UNVERIFIED** — if input is rejected, trying
the last 12 bytes is the second thing to test.

### 10.2 Common input header

Every input packet begins with:

```
offset 0 : uint32 size    BIG-ENDIAN     // size of the packet EXCLUDING this field
offset 4 : uint32 magic   LITTLE-ENDIAN  // packet type
```

So a packet with a 4-byte body has `size = 4 (magic) + 4 (body) = 8`.

### 10.3 Packet types

Values are the `magic` field. Multi-byte body fields are **big-endian** unless the type is
`netfloat`, which is a **little-endian IEEE-754 float**.

#### Keyboard

```
KEY_DOWN_EVENT_MAGIC = 0x00000003
KEY_UP_EVENT_MAGIC   = 0x00000004

body:
  int8  flags        // Sunshine extension; 0 for GFE. 0x01 = SS_KBE_FLAG_NON_NORMALIZED
  int16 keyCode      // Windows virtual-key code
  int8  modifiers    // bitmask
  int16 zero2        // always 0
```

Modifier bits: `SHIFT = 0x01`, `CTRL = 0x02`, `ALT = 0x04`, `META = 0x08`.

`keyCode` is a **Windows VK code**, not an Android keycode. We need a translation table
(`KeyEvent.KEYCODE_A` → `0x41`, etc.). Build it explicitly; there is no shortcut.

**UNVERIFIED:** whether GFE expects the VK code to be offset or transformed (some clients send
`keyCode | 0x8000`). **v1: send the plain VK code**, and if keyboard input does not register,
test the `0x8000` variant.

#### UTF-8 text

```
UTF8_TEXT_EVENT_MAGIC = 0x00000017
body: up to 32 bytes of UTF-8 text (UTF8_TEXT_EVENT_MAX_COUNT = 32)
```

`size` reflects the actual text length. Use this for IME / soft-keyboard input where we have
characters rather than scan codes. Sunshine-friendly; **UNVERIFIED** on GFE.

#### Mouse — relative move

```
MOUSE_MOVE_REL_MAGIC      = 0x00000006   // Gen < 5
MOUSE_MOVE_REL_MAGIC_GEN5 = 0x00000007   // Gen >= 5

body:
  int16 deltaX
  int16 deltaY
```

Deltas are accumulated and **batched**: coalesce all pending relative moves into one packet per
send tick rather than sending one per touch event. This matters a lot for touchpad mode.

#### Mouse — absolute position

```
MOUSE_MOVE_ABS_MAGIC = 0x00000005

body:
  int16 x
  int16 y
  int16 unused    // 0
  int16 width     // reference width  the x is scaled against
  int16 height    // reference height the y is scaled against
```

Send our stream's video dimensions as `width`/`height` and the pointer position in that
coordinate space. This is what "Absolute Touch" mode uses.

#### Mouse — buttons

```
MOUSE_BUTTON_DOWN_EVENT_MAGIC_GEN5 = 0x00000008
MOUSE_BUTTON_UP_EVENT_MAGIC_GEN5   = 0x00000009

body:
  uint8 button    // 1 = left, 2 = middle, 3 = right, 4 = X1(back), 5 = X2(forward)
```

**UNVERIFIED:** the button numbering for X1/X2. Left/middle/right as 1/2/3 is solid.

#### Mouse — scroll

```
SCROLL_MAGIC       = 0x00000009   // Gen < 5
SCROLL_MAGIC_GEN5  = 0x0000000A   // Gen >= 5

body:
  int16 scrollAmt1     // high-resolution scroll amount
  int16 scrollAmt2     // same value (legacy duplicate)
  int16 zero3          // 0
```

One "click" of a wheel is **120** (the Windows `WHEEL_DELTA`). For a high-resolution scroll,
send fractional multiples of 120.

```
SS_HSCROLL_MAGIC = 0x55000001    // Sunshine only, horizontal scroll
body: int16 scrollAmount
```

#### Controller — single (legacy)

```
CONTROLLER_MAGIC = 0x0000000A
C_HEADER_B = 0x1400, C_TAIL_A = 0x0000009C, C_TAIL_B = 0x0055

body:
  int16 headerB       = 0x1400
  int16 buttonFlags
  uint8 leftTrigger   // 0..255
  uint8 rightTrigger  // 0..255
  int16 leftStickX    // -32768..32767
  int16 leftStickY
  int16 rightStickX
  int16 rightStickY
  int32 tailA         = 0x0000009C
  int16 tailB         = 0x0055
```

#### Controller — multi (what we actually use)

```
MULTI_CONTROLLER_MAGIC      = 0x0000000D   // Gen < 5
MULTI_CONTROLLER_MAGIC_GEN5 = 0x0000000C   // Gen >= 5
MC_HEADER_B = 0x001A, MC_MID_B = 0x0014, MC_TAIL_A = 0x009C, MC_TAIL_B = 0x0055

body:
  int16 headerB          = 0x001A
  int16 controllerNumber // 0..3 (GFE) or 0..15 (Sunshine)
  int16 activeGamepadMask// bit per connected pad; 4 bits max on GFE, 16 on Sunshine
  int16 midB             = 0x0014
  int16 buttonFlags      // low 16 bits of the button bitmask
  uint8 leftTrigger
  uint8 rightTrigger
  int16 leftStickX
  int16 leftStickY
  int16 rightStickX
  int16 rightStickY
  int16 tailA            = 0x009C
  int16 buttonFlags2     // HIGH bits of the button bitmask; Sunshine only, 0 for GFE
  int16 tailB            = 0x0055
```

**Arrival** of a controller = send an otherwise-empty event with that controller's number and
its bit **set** in `activeGamepadMask`.
**Removal** = empty event with the number and its bit **cleared**.

#### Button flags

```
UP_FLAG      = 0x0001    LEFT_FLAG   = 0x0004
DOWN_FLAG    = 0x0002    RIGHT_FLAG  = 0x0008
PLAY_FLAG    = 0x0010    (Start)
BACK_FLAG    = 0x0020    (Select/Back)
LS_CLK_FLAG  = 0x0040    RS_CLK_FLAG = 0x0080
LB_FLAG      = 0x0100    RB_FLAG     = 0x0200
SPECIAL_FLAG = 0x0400    (Guide/Home)
A_FLAG       = 0x1000    B_FLAG      = 0x2000
X_FLAG       = 0x4000    Y_FLAG      = 0x8000

// Sunshine extensions — go in buttonFlags2 (the high 16 bits of a 32-bit mask)
PADDLE1_FLAG  = 0x010000   PADDLE2_FLAG = 0x020000
PADDLE3_FLAG  = 0x040000   PADDLE4_FLAG = 0x080000
TOUCHPAD_FLAG = 0x100000   MISC_FLAG    = 0x200000
```

Note the odd ordering: D-pad occupies the low nibble, face buttons the high nibble.

#### Controller arrival (Sunshine)

```
SS_CONTROLLER_ARRIVAL_MAGIC = 0x55000004

body:
  uint8  controllerNumber
  uint8  type                  // 0x00 unknown, 0x01 Xbox, 0x02 PlayStation, 0x03 Nintendo
  uint16 capabilities
  uint32 supportedButtonFlags  // the full 32-bit button mask this pad can produce
```

Capability bits:

```
LI_CCAP_ANALOG_TRIGGERS = 0x001
LI_CCAP_RUMBLE          = 0x002
LI_CCAP_TRIGGER_RUMBLE  = 0x004
LI_CCAP_TOUCHPAD        = 0x008
LI_CCAP_ACCEL           = 0x010
LI_CCAP_GYRO            = 0x020
LI_CCAP_BATTERY_STATE   = 0x040
LI_CCAP_RGB_LED         = 0x080
LI_CCAP_DUAL_TOUCHPAD   = 0x100
```

Prefer this over a bare multi-controller arrival event when the host is Sunshine: it lets the
host emulate the right pad type (which is what the "Emulated Controller Type" setting maps to).
On GFE, fall back to the empty multi-controller event.

#### Controller motion (gyro / accelerometer, Sunshine)

```
SS_CONTROLLER_MOTION_MAGIC = 0x55000006

body:
  uint8    controllerNumber
  uint8    motionType     // 0x01 = accelerometer, 0x02 = gyroscope
  uint8[2] zero
  netfloat x              // little-endian float
  netfloat y
  netfloat z
```

**Units are load-bearing:** accelerometer in **m/s²** (including gravity), gyroscope in
**degrees per second**. Android's `Sensor.TYPE_GYROSCOPE` reports **radians per second** —
multiply by `180/π (57.29578)`. Android's accelerometer is already m/s² including gravity.
Axis orientation must be mapped from Android's device frame to the controller frame; expect to
need per-axis sign flips, and expose "invert X/Y" as a debug setting.

The host asks us to start/stop motion reporting with the **set-motion-event** control message
(index 10, §9.6), which specifies a controller number, a motion type, and a **report rate in
Hz**. Honor the requested rate by throttling; do not just dump every sensor callback.

#### Controller touchpad (Sunshine)

```
SS_CONTROLLER_TOUCH_MAGIC = 0x55000005

body:
  uint8    controllerNumber
  uint8    eventType
  uint8    zero
  uint8    touchpadIndex     // 0, or 1 with LI_CCAP_DUAL_TOUCHPAD
  uint32   pointerId
  netfloat x                 // normalized 0.0 .. 1.0
  netfloat y
  netfloat pressure
```

#### Controller battery (Sunshine)

```
SS_CONTROLLER_BATTERY_MAGIC = 0x55000007

body:
  uint8 controllerNumber
  uint8 batteryState     // 0 unknown, 1 not present, 2 discharging, 3 charging,
                         // 4 connected-not-charging, 5 full
  uint8 batteryPercentage// 0..100, or 0xFF for unknown
  uint8 zero
```

#### Native touch (Sunshine)

```
SS_TOUCH_MAGIC = 0x55000002

body:
  uint8    eventType
  uint8[1] zero
  uint16   rotation             // 0..359, or 0xFFFF for unknown
  uint32   pointerId
  netfloat x                    // normalized 0.0 .. 1.0 across the video surface
  netfloat y
  netfloat pressureOrDistance   // pressure for contact, hover distance otherwise
  netfloat contactAreaMajor
  netfloat contactAreaMinor
```

Touch event types:

```
LI_TOUCH_EVENT_HOVER       = 0x00
LI_TOUCH_EVENT_DOWN        = 0x01
LI_TOUCH_EVENT_UP          = 0x02
LI_TOUCH_EVENT_MOVE        = 0x03
LI_TOUCH_EVENT_CANCEL      = 0x04   // only pointerId is meaningful
LI_TOUCH_EVENT_BUTTON_ONLY = 0x05
LI_TOUCH_EVENT_HOVER_LEAVE = 0x06
LI_TOUCH_EVENT_CANCEL_ALL  = 0x07   // cancel every active touch (use on focus loss)
```

This is the "Native Touch" mode of the reference UI: pointer ids and normalized coordinates go
straight to the host, which synthesizes Windows touch input. **Sunshine/Apollo only** — GFE has
no equivalent, so Native Touch must be disabled (with an explanation) on NVIDIA hosts.

#### Pen / stylus (Sunshine)

```
SS_PEN_MAGIC = 0x55000003

body:
  uint8    eventType     // same enum as touch
  uint8    toolType
  uint8    penButtons
  uint8[1] zero
  netfloat x
  netfloat y
  netfloat pressureOrDistance
  uint16   rotation
  uint8    tilt
  uint8[1] zero2
  netfloat contactAreaMajor
  netfloat contactAreaMinor
```

#### Haptics enable

```
ENABLE_HAPTICS_MAGIC = 0x0000000D
body: uint16 enable
```

Note the magic collides numerically with `MULTI_CONTROLLER_MAGIC` for Gen < 5. Context (host
generation) disambiguates. **UNVERIFIED** in practice; we do not send haptics-enable in v1.

### 10.4 Input batching rules

Sending one packet per Android `MotionEvent` will flood the control channel and add latency.
Required behaviour:

* **Relative mouse moves:** accumulate deltas; flush at most once per **~4 ms** (or once per
  frame of the stream, whichever is longer).
* **Controller state:** send only on change, and coalesce multiple axis changes within the
  same tick into one packet. Analog stick jitter must be dead-zoned before it counts as a
  change.
* **Touch:** do **not** coalesce down/up events; coalesce moves per pointer.
* Everything is sent on the **urgent** ENet channel, unreliably where loss is acceptable
  (moves) and reliably where it is not (button down/up, controller arrival/removal).
  **UNVERIFIED:** whether hosts tolerate unreliable input packets given the AES-GCM IV
  chaining (a dropped packet would desynchronize the IV). **v1: send ALL input reliably.**
  This is the safe choice and the IV chaining strongly suggests it is the required one.

---

## 11. Error handling and status

### 11.1 Connection-listener style error codes

Codes the streaming layer surfaces to the UI:

```
ML_ERROR_GRACEFUL_TERMINATION       =    0   // normal end
ML_ERROR_NO_VIDEO_TRAFFIC           = -100   // nothing arrived on the video port at all
ML_ERROR_NO_VIDEO_FRAME             = -101   // packets arrived but no frame ever completed
ML_ERROR_UNEXPECTED_EARLY_TERMINATION = -102
ML_ERROR_PROTECTED_CONTENT          = -103
ML_ERROR_FRAME_CONVERSION           = -104
```

`-100` almost always means a firewall or a NAT problem (our ping never opened the pinhole).
`-101` means we are receiving but reassembly is failing — the FEC/reassembly code is broken or
the packet size is wrong. These two diagnoses are worth surfacing as distinct user-facing text.

### 11.2 Connection quality

```
CONN_STATUS_OKAY = 0
CONN_STATUS_POOR = 1
```

Compute from measured loss over a rolling window (suggest a 3 s sample period):
report POOR when the interval loss rate exceeds ~5%, and treat >15% sustained or >30%
instantaneous as "very poor" for UI purposes. Show a warning chip in the stream overlay.

---

## 12. Android platform notes

### 12.1 Video decode — `MediaCodec`, asynchronous mode

```kotlin
val codec = MediaCodec.createByCodecName(chosenDecoderName)
codec.setCallback(callback, decoderHandler)     // ASYNC mode, dedicated HandlerThread
codec.configure(format, surface, null, 0)
codec.start()
```

**Always use async mode with a callback on a dedicated `HandlerThread`.** Synchronous
`dequeueInputBuffer` polling adds latency and burns a thread.

Format keys to set:

| Key | Value | Notes |
|---|---|---|
| `MediaFormat.KEY_WIDTH` / `KEY_HEIGHT` | negotiated dimensions | |
| `MediaFormat.KEY_MAX_INPUT_SIZE` | ≥ largest expected frame; suggest `width*height` bytes | Under-sizing causes buffer-too-small errors on big IDRs. |
| `"low-latency"` (`KEY_LOW_LATENCY`) | `1` | **API 30+**. Set by string literal so it compiles on lower `compileSdk` paths. |
| `MediaFormat.KEY_OPERATING_RATE` | `Short.MAX_VALUE` | API 23+. Tells the codec "run as fast as you can". |
| `MediaFormat.KEY_PRIORITY` | `0` (realtime) | API 23+. |
| `MediaFormat.KEY_COLOR_STANDARD` | `COLOR_STANDARD_BT709` or `BT2020` | API 24+. |
| `MediaFormat.KEY_COLOR_RANGE` | `COLOR_RANGE_LIMITED` | API 24+. |
| `MediaFormat.KEY_COLOR_TRANSFER` | `COLOR_TRANSFER_ST2084` for HDR10 | API 24+. |
| `MediaFormat.KEY_HDR_STATIC_INFO` | `ByteBuffer` of the SMPTE ST 2086 static metadata | API 24+, only when HDR. |

**Vendor low-latency keys** — set these *in addition to* the standard key, guarded so that a
codec that rejects one does not sink the configure. Known keys in the wild:

```
"vendor.qti-ext-dec-low-latency.enable"                                  = 1   (Qualcomm)
"vendor.qti-ext-dec-picture-order.enable"                                = 1   (Qualcomm)
"vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req"  = 1   (HiSilicon)
"vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy"  = -1  (HiSilicon)
"vendor.rtc-ext-dec-low-latency.enable"                                  = 1   (MediaTek/others)
"vendor.low-latency.enable"                                              = 1   (generic)
"vdec-lowlatency"                                                        = 1   (legacy)
```

Discover which a codec actually supports via
`MediaCodecInfo.CodecCapabilities.getSupportedVendorParameters()` (API 31+); below that, try
and catch. **Strategy: configure once with all applicable keys; if `configure()` throws, retry
with only the standard keys, then with none.** Never let a vendor key prevent streaming.

**Rendering:** `codec.releaseOutputBuffer(index, true)` immediately in the async
`onOutputBufferAvailable` callback. Do **not** use the timestamped
`releaseOutputBuffer(index, renderTimestampNs)` variant — it schedules, which adds latency.

**Surface:** a `SurfaceView` (not `TextureView`) with a fixed buffer size matching the stream
dimensions. `TextureView` costs an extra copy through the GPU and adds a frame of latency.

**Input submission:** in `onInputBufferAvailable`, take the next complete frame from the
reassembly queue. If none is ready, hold the buffer index in a free list rather than blocking.
`BUFFER_FLAG_KEY_FRAME` on IDRs; `BUFFER_FLAG_CODEC_CONFIG` is unnecessary because SPS/PPS
arrive in-band in the first IDR's Annex-B data.

**Error recovery:** on `MediaCodec.CodecException`, check `isTransient` (retry) vs
`isRecoverable` (stop/flush/start) vs neither (tear down the session with a clear error).

### 12.2 HDR

* Requires a 10-bit profile (`VIDEO_FORMAT_H265_MAIN10` or `AV1_MAIN10`) and a display that
  reports HDR support (`Display.getHdrCapabilities()`).
* Set `KEY_COLOR_TRANSFER = COLOR_TRANSFER_ST2084` and supply `KEY_HDR_STATIC_INFO`.
* The Activity's window must not be forcing SDR; on API 34 use
  `Window.setDesiredHdrHeadroom` / the display's HDR/SDR ratio APIs where available.
* **UNVERIFIED:** whether any additional Android-side opt-in is required for HDR10 passthrough
  on all vendors. Treat "HDR toggled on but the picture looks washed out" as an expected
  early-stage bug and log the codec's output format.

### 12.3 Controllers

* Enumerate with `InputManager.getInputDeviceIds()` + `InputDevice.getDevice(id)`; a device is
  a gamepad if `sources and (SOURCE_GAMEPAD or SOURCE_JOYSTICK) != 0`.
* Listen for hotplug via `InputManager.registerInputDeviceListener` →
  `onInputDeviceAdded/Removed/Changed`. Each add/remove maps to a controller
  arrival/removal packet (§10.3).
* Axes: `AXIS_X`/`AXIS_Y` (left stick), `AXIS_Z`/`AXIS_RZ` (right stick),
  `AXIS_LTRIGGER`/`AXIS_BRAKE` and `AXIS_RTRIGGER`/`AXIS_GAS` (triggers — devices vary, check
  `getMotionRange` for which exist), `AXIS_HAT_X`/`AXIS_HAT_Y` (D-pad on many pads).
* Apply the device's `MotionRange.getFlat()` as the dead zone; scale sticks to `-32768..32767`
  and triggers to `0..255`.
* **Y axis is inverted** relative to the protocol: Android reports −1 as up, the protocol wants
  positive-up. Negate.
* `dispatchKeyEvent` / `dispatchGenericMotionEvent` on the Activity are the reliable capture
  points; consume events so the system does not also act on them.
* **Back button:** on a gamepad, `KEYCODE_BACK` must map to `BACK_FLAG`, not to Activity finish.

### 12.4 Rumble

`VibratorManager` (API 31+) / `Vibrator` (below). The protocol gives two 16-bit motor
intensities (low-frequency and high-frequency); Android's generic vibrator has one channel.
Map `amplitude = max(low, high) shr 8` (to 0..255) and use
`VibrationEffect.createOneShot(durationMs, amplitude)` with a short duration (~80 ms) that we
re-issue as new rumble packets arrive. If the physical controller exposes its own vibrator
(`InputDevice.getVibratorManager()` on API 31+), prefer that over the phone's.

### 12.5 Motion sensors

`SensorManager` with `TYPE_GYROSCOPE` and `TYPE_ACCELEROMETER`, registered at
`SENSOR_DELAY_GAME` or an explicit microsecond period matching the host's requested Hz.
Unit conversion per §10.3. Unregister the moment the stream ends — leaving sensors registered
is a real battery bug.

### 12.6 Foreground service

Streaming must survive brief backgrounding (notification shade, incoming call UI) and must not
be killed mid-session.

* Declare a foreground service with **`android:foregroundServiceType="mediaPlayback"`**
  (and `"connectedDevice"` if we want controller access framed correctly).
* API 34 requires the type in the manifest **and** the matching runtime permission
  `FOREGROUND_SERVICE_MEDIA_PLAYBACK`.
* Post an ongoing notification with the host/app name and a "Disconnect" action.
* Permissions needed overall: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`,
  `CHANGE_WIFI_MULTICAST_STATE` (for the multicast lock), `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `VIBRATE`, `POST_NOTIFICATIONS` (API 33+),
  and `WAKE_LOCK`.
* Hold a `WifiLock` (`WIFI_MODE_FULL_LOW_LATENCY` on API 29+) and a partial `WakeLock` for the
  session duration. `WIFI_MODE_FULL_LOW_LATENCY` measurably reduces jitter.
* Keep the screen on with `FLAG_KEEP_SCREEN_ON` on the stream window.

### 12.7 Networking

* `DatagramChannel`/`DatagramSocket` with **`setReceiveBufferSize` bumped to ≥ 1 MB** — the
  default is far too small for a 4K60 bitstream and causes bursty loss.
* Set `setTrafficClass(IPTOS_LOWDELAY)` where the platform honours it.
* Do all socket I/O on dedicated threads (§`02-ARCHITECTURE.md`), never on Dispatchers.IO's
  shared pool for the hot receive loop — a blocked pool thread means dropped frames.
* Prefer `receive()` into a pre-allocated `DirectByteBuffer` pool; per-packet allocation at
  60 fps × ~100 packets/frame will make the GC visible.

---

## 13. Consolidated list of UNVERIFIED items

Ordered by how much damage a wrong guess does. The coder must treat every one of these as a
"log loudly and make it swappable" point.

| # | Item | § | Risk if wrong |
|---|---|---|---|
| 1 | Reed-Solomon matrix variant (video + audio FEC) | 7.7, 8.4 | Recovery produces corrupt frames. **Mitigated by shipping without recovery first.** |
| 2 | Input AES IV chaining rule (Gen 7 GCM) | 10.1 | All input silently ignored by the host. |
| 3 | GCM 12-of-16 IV truncation | 10.1 | Same as above. |
| 4 | ENet channel ids (`GENERIC`/`URGENT`) | 9.1 | Messages ignored or mis-prioritized. |
| 5 | Sunshine encryption feature bit values, and where the host advertises support | 6.5 | Mitigated: v1 sends `0`. |
| 6 | Rumble payload offset | 9.6 | Wrong/absent haptics. Cosmetic. |
| 7 | Control message types for indices 9–12 | 9.3 | Extensions ignored. Cosmetic in v1. |
| 8 | `multiFecFlags` bit packing | 7.4 | Large frames fail to assemble; recovered by IDR request. |
| 9 | `PERIODIC_PING_INTERVAL_MS`, `LOSS_REPORT_INTERVAL_MS` | 9.5 | Host may time us out. Easy to tune. |
| 10 | `ServerCodecModeSupport` bit assignments | 3.3.1 | Wrong codec offered; fails at ANNOUNCE with a clear error. |
| 11 | HDR metadata payload layout in the HDR-mode control message | 9.6 | HDR toggle mid-stream mishandled. |
| 12 | High-quality-surround SDP key | 8.3 | Mitigated: v1 never requests it. |
| 13 | ANNOUNCE SDP tail `m=` lines | 6.4 | ANNOUNCE rejected. Found immediately in testing. |
| 14 | `rtspru://` — whether TCP RTSP stays available | 6.1 | Cannot connect to some Sunshine builds. |
| 15 | Keyboard VK code transformation (`0x8000` variant) | 10.3 | Keyboard input ignored. |
| 16 | Mouse X1/X2 button numbering | 10.3 | Two buttons wrong. Cosmetic. |
| 17 | `0xCA` audio-configuration marker | 8.2 | Internal only; never on the wire. |
| 18 | mDNS TXT record contents | 1.1 | None — we do not depend on them. |
| 19 | `devicename=roth` significance | 4.0 | None expected. |
| 20 | Termination error code meanings beyond `0x800e9403` | 9.6 | Unfriendly error text only. |
| 21 | `uniqueid` format requirements | 2 | Pairing rejected; would show up immediately. |
| 22 | Whether input may be sent unreliably | 10.4 | Mitigated: v1 sends everything reliably. |
| 23 | GFE `fps=0` workaround applicability | 3.6 | Only affects >60 fps on NVIDIA hosts. |

---

## 14. Sources

Protocol facts in this document were derived from the following public sources, consulted as
**documentation of an interoperability protocol**. No code was copied.

**Protocol reference implementations (read as documentation):**

* `moonlight-stream/moonlight-common-c` — core protocol implementation.
  * `src/Limelight.h` — https://github.com/moonlight-stream/moonlight-common-c/blob/master/src/Limelight.h
    (video format masks, audio configuration macros, button flags, capability flags, touch/motion
    enums, error codes)
  * `src/Input.h` — https://github.com/moonlight-stream/moonlight-common-c/blob/master/src/Input.h
    (every input packet magic and struct in §10.3)
  * `src/Video.h` — https://github.com/moonlight-stream/moonlight-common-c/blob/master/src/Video.h
    (`RTP_PACKET`, `NV_VIDEO_PACKET`, `ENC_VIDEO_HEADER`, `SS_FRAME_FEC_STATUS`, `SS_PING`)
  * `src/RtspConnection.c` — https://github.com/moonlight-stream/moonlight-common-c/blob/master/src/RtspConnection.c
    (RTSP method sequence, headers, stream ids, `server_port=` parsing, Opus config parsing)
  * `src/SdpGenerator.c` — https://github.com/moonlight-stream/moonlight-common-c/blob/master/src/SdpGenerator.c
    (the complete `x-nv-*` / `x-ss-*` / `x-ml-*` attribute set in §6.4)
  * `src/ControlStream.c` — https://github.com/moonlight-stream/moonlight-common-c/blob/master/src/ControlStream.c
    (ENet framing, encrypted control header, per-generation message type tables, ping/loss-stats
    payloads, termination codes)
  * `src/InputStream.c` — https://github.com/moonlight-stream/moonlight-common-c/blob/master/src/InputStream.c
    (input encryption: GCM for Gen 7+, CBC below)
  * `src/VideoStream.c` — https://github.com/moonlight-stream/moonlight-common-c/blob/master/src/VideoStream.c
    (UDP ping payloads and 500 ms interval)
  * `src/RtpVideoQueue.c` — https://github.com/moonlight-stream/moonlight-common-c/blob/master/src/RtpVideoQueue.c
    (`fecInfo` bit layout, parity-shard count formula, RS invocation)
  * `src/RtpAudioQueue.h` / `.c` — https://github.com/moonlight-stream/moonlight-common-c/blob/master/src/RtpAudioQueue.h
    (audio payload types 97/127, RS(4,2), `AUDIO_FEC_HEADER`)
  * `src/Connection.c` — https://github.com/moonlight-stream/moonlight-common-c/blob/master/src/Connection.c
    (port defaults and the 47984→47989→48010 resolution order)

* `moonlight-stream/moonlight-android` — client-side HTTP/pairing/Android specifics.
  * `PairingManager.java` — https://github.com/moonlight-stream/moonlight-android/blob/master/app/src/main/java/com/limelight/nvstream/http/PairingManager.java
    (the entire §4 pairing sequence, salt/PIN derivation, SHA-1 vs SHA-256 selection, AES-ECB
    block handling, signature verification order)
  * `NvHTTP.java` — https://github.com/moonlight-stream/moonlight-android/blob/master/app/src/main/java/com/limelight/nvstream/http/NvHTTP.java
    (ports 47989/47984, the `uniqueid`+`uuid` universal params, `/serverinfo` element names,
    `/applist` parsing, the complete `/launch` query string, `devicename=roth&updateState=1`)
  * `MediaCodecHelper.java` — https://github.com/moonlight-stream/moonlight-android/blob/master/app/src/main/java/com/limelight/binding/video/MediaCodecHelper.java
    (the vendor low-latency key names in §12.1)

* `LizardByte/Sunshine` — the host side, which is authoritative for what a modern host expects.
  * `src/nvhttp.cpp` — https://github.com/LizardByte/Sunshine/blob/master/src/nvhttp.cpp
    (the exact `/serverinfo` and `/applist` XML element set, the four `/pair` phase responses,
    `SUNSHINE_SERVER_FREE`/`BUSY`, the plaintext-MAC placeholder behaviour)
  * `src/platform/common.h` — https://github.com/LizardByte/Sunshine/blob/master/src/platform/common.h
    (`SERVICE_TYPE = "_nvstream._tcp"`, confirming the mDNS service name in §1.1)

**Product reference (features/UI only, no code):**

* VoidLink README — https://github.com/The-Fried-Fish/VoidLink-previously-moonlight-zwm
* VoidLink releases / changelogs — https://github.com/The-Fried-Fish/VoidLink-previously-moonlight-zwm/releases
  (feature list: native 10-finger touch pass-through, absolute touch mode, touch pointer
  velocity, on-screen controller with custom layouts, in-stream settings menu, bitrates up to
  500 Mbps, portrait streaming, favorites)

**Background:**

* Moonlight FAQ — https://github.com/moonlight-stream/moonlight-docs/wiki/Frequently-Asked-Questions
  (pairing rationale, bitrate guidance, end-to-end encryption from Sunshine v0.22)
* Moonlight project site — https://moonlight-stream.org/

**Android platform:**

* `MediaFormat` / `MediaCodec` reference — https://developer.android.com/reference/android/media/MediaFormat
  and https://developer.android.com/reference/android/media/MediaCodec
* Foreground service types — https://developer.android.com/about/versions/14/changes/fgs-types-required
* `NsdManager` — https://developer.android.com/reference/android/net/nsd/NsdManager
