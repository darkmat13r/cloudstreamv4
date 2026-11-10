# KMP-113 — A local media gateway, so every connection libVLC makes is address-checked

Design for review. No code until it is approved. Follows `BUG-90`, which narrowed the gap by checking a stream's url
again just before play; this closes it.

## The problem, in one paragraph

The Mac's media host (libVLC 3.0.24, in its own Seatbelt sandbox) is given a stream url that was checked once, by
name. libVLC then resolves it again, follows its own redirects, and fetches every variant, segment, key and map a
playlist names, on any host. Seatbelt can only say `localhost` or `*`, so the LAN, the router and
`169.254.169.254` are reachable from it. A forward proxy cannot fix this. Measured on 3.0.24: https ignores
`--socks`, `--http-proxy` is gone, and `http_proxy`/`https_proxy` are not read; only plain http goes through SOCKS.

## The shape of the fix

A **gateway** in the app's own process: an HTTP/1.1 *origin* server on `127.0.0.1`, one per media host.
- libVLC is given gateway urls only, over plain http on loopback. That is a path libVLC already takes.
- The gateway fetches upstream with the app's address-checked client: `posterHttpClient`'s DNS filter, its
  connected-address check on every hop, and `Proxy.NO_PROXY`. The JVM validates TLS.
- The media host's Seatbelt policy allows outbound **only** to `localhost:<gateway port>`. The `*:*` rule goes, and
  so does `mDNSResponder`, since the host no longer resolves anything.

**The kernel is the guarantee.** Anything the gateway does not route, libVLC cannot fetch directly: a url the rewriter
missed, an unknown scheme, a redirect libVLC tried to follow itself, `rtsp://`, `udp://`. Each fails closed with
"Operation not permitted" (measured in `BUG-90`). So **the rewriter is there for playback, not for safety**. A bug
in it can stop a stream from playing; it cannot open a connection the check has not seen. That is what keeps the
hostile-input parsing below small: it never has to be complete, only bounded.

## 1. The url encoding

```
http://127.0.0.1:<port>/<token>/<scheme>/<authority>/<path>[?<query>]
        e.g. /k3Jd…Q/https/cdn.example/v/1080/seg-0001.ts?sig=…
```
- `<token>`: 128 random bits, base64url, made per gateway and given only to its media host. A request without it gets
  `404` and a fixed body. Other processes on this Mac can reach the port, and without the token they cannot use the
  gateway as a relay into the LAN. The comparison is constant-time.
- `<scheme>`: `http` or `https`, and nothing else.
- `<authority>`: the host, lowercased, as `URI` reads it (IDN as ASCII), with `:port` when it is not the default.
  User info is never encoded; a url with any is refused, as `SandboxPolicy` refuses it today.
- `<path>` and `<query>`: passed as received, percent-encoding kept byte for byte, so a signed url's signature
  survives. A fragment is dropped.
- **Decoding is strict.** The gateway percent-decodes nothing it does not have to, and refuses a path whose dot
  segments would climb above `<authority>` once normalised. A path over `SandboxPolicy.MAX_URL_CHARS` is refused, as
  is any control character.
- **Every url in a rewritten playlist is absolute** (§3), so libVLC never resolves a relative reference itself. A
  root-relative `/x` resolved against a gateway url would drop the token, the scheme and the host. It would only ever
  get a `404`, never a leak, but it would also not play.

## 2. Upstream fetches

- **Methods:** `GET` and `HEAD` only. Anything else gets `405`. An absolute-form request line or `CONNECT` gets
  `400`: the gateway is an origin, never a proxy.
- **Redirects:** the gateway follows them itself, up to 5 hops, each hop through the same checked client.
  - It never follows https to http.
  - The final url is the base for resolving the playlist's relative urls (§3).
  - libVLC never sees a 3xx, so it has nothing to follow on its own.
- **Byte ranges:** `Range` and `If-Range` go upstream as they came, and `206`, `Content-Range`, `Content-Length` and
  `Accept-Ranges` come back. A playlist is always fetched whole and served `200`, because it is rewritten.
- **Keep-alive:** HTTP/1.1 persistent connections from libVLC, with a 30 s idle limit. Upstream, OkHttp's pool is
  per gateway. Its network interceptor checks the connected address on every request, so a reused connection is
  still one the check passed.
- **Bodies:** a media body streams through with a fixed buffer (64 KiB) and is never held whole. `Accept-Encoding`
  is not forwarded, and the gateway asks for identity on media. Only a playlist is decompressed and buffered, under
  §3's limits.

## 3. The playlist rewriter (HLS first; DASH second)

**When:** the response is a playlist when its `Content-Type` is one of `application/vnd.apple.mpegurl`,
`application/x-mpegurl`, `audio/mpegurl` or `audio/x-mpegurl`, or when its body starts `#EXTM3U` (after an optional
BOM). Anything else streams through untouched.

**Limits.** Past any one of them the gateway answers `502` with a fixed body, and the player shows its failure:
- 4 MiB decompressed;
- 100,000 lines;
- 16 KiB per line (`MAX_URL_CHARS`);
- 64 attributes per tag;
- 5 s to read it all, so a slow trickle is a failure, not a wait.

**What is rewritten.** Each one goes through the gateway encoding (§1), resolved against the playlist's *final* url:
- every uri line (a non-blank line not starting with `#`);
- the quoted `URI` attribute of `EXT-X-KEY`, `EXT-X-SESSION-KEY`, `EXT-X-MAP`, `EXT-X-MEDIA`,
  `EXT-X-I-FRAME-STREAM-INF`, `EXT-X-SESSION-DATA`, `EXT-X-PART`, `EXT-X-PRELOAD-HINT` and
  `EXT-X-RENDITION-REPORT`;
- `SERVER-URI` on `EXT-X-CONTENT-STEERING`;
- the `X-ASSET-URI` and `X-ASSET-LIST` attributes of `EXT-X-DATERANGE` (interstitials).

**Attribute lists** are parsed as RFC 8216 §4.2 defines them: `NAME=value` pairs, commas outside quotes, quoted
strings with no escapes. A tag whose list does not parse is passed through unchanged; the kernel stops whatever it
names (see "The shape of the fix").

**What is not rewritten:**
- A url whose scheme is not http(s): `skd://` for FairPlay, `data:`. It passes unchanged. `data:` needs no network.
  Anything else that needs one fails closed.
- **Unknown tags** pass through unchanged, for the same reason. Guessing which attribute of a tag nobody has seen is
  a url would be parsing for its own sake.
- `#EXT-X-DEFINE` variable substitution (`{$name}`) is not expanded. A url holding a variable passes unchanged, and
  plays only if it needs no expansion. That is accepted for now.

**DASH (`application/dash+xml`), a second step.**
- Parsing: StAX with DTDs and external entities off, 4 MiB, depth 64, 100,000 elements.
- Rewritten: every `BaseURL` (the MPD's, each Period's, each AdaptationSet's, each Representation's), `Location`,
  `PatchLocation`, and the absolute http(s) values of `@media`, `@initialization`, `@index` and `@sourceURL`. Each is
  made absolute against its effective base and encoded.
- `SegmentTemplate` `$…$` identifiers sit in the path and survive the encoding as text.
- Until this step lands, a DASH manifest streams through unrewritten: a relative `BaseURL` plays, an absolute one
  fails closed.

## 4. Headers

**From libVLC upstream, forwarded:** `Range`, `If-Range`, `Accept`, `User-Agent`, `Referer`. The last two are the
plugin's `:http-user-agent` and `:http-referrer`, which the gateway could hold itself (open question 2).

**Never forwarded upstream:**
- `Host`: the gateway sets it from the decoded url;
- `Cookie` and `Authorization`;
- `Proxy-*`;
- the hop-by-hop headers: `Connection`, `Keep-Alive`, `TE`, `Trailer`, `Transfer-Encoding`, `Upgrade`;
- `Accept-Encoding`: the gateway decides it.

The gateway never adds `Forwarded`, `X-Forwarded-*` or `Via`.

**Cookies.** CDNs often hand out token cookies. libVLC would keep them against `127.0.0.1`, so one host's cookie
would go to every other. So:
- the gateway keeps its own cookie jar per gateway, in memory only, keyed by the real host, through OkHttp's
  `CookieJar`;
- it never passes `Set-Cookie` on to libVLC;
- the jar goes with the gateway when the stream ends.

**Back to libVLC, passed:** `Content-Type`, `Content-Length` (not for a rewritten playlist, which gets its own),
`Content-Range`, `Accept-Ranges`, `Last-Modified`, `ETag`, `Cache-Control`.

**Back to libVLC, never passed:** `Set-Cookie`, `Location`, `Alt-Svc`, `Strict-Transport-Security`, CORS headers,
and anything else not on the list above.

## 5. The gateway's own limits

- Binds `127.0.0.1` only, on an ephemeral port.
- 16 connections from its media host at once; the next is closed unanswered.
- Request line and headers: 16 KiB together, 100 headers.
- Upstream: 15 s to connect, 30 s between reads, and no limit on a media body's total length, since a film is long.
- One gateway per media host. It is closed with the host: its listener, its connections, its pool and its cookie
  jar.

## 6. Failure modes

| What happens | What the gateway does | What the person sees |
|---|---|---|
| upstream address refused by the check | `502`, fixed body, nothing sent upstream | the player's existing failure |
| redirect to a refused address, or past 5 hops | `502` | failure |
| playlist past a limit, or unreadable | `502` | failure |
| TLS error upstream | `502` | failure |
| a url the rewriter missed, or a non-http scheme | nothing: libVLC tries directly and the kernel refuses | failure for that rendition or stream |
| the gateway dies | libVLC's connections are refused | failure, never a direct fetch |
| request without the token | `404` | nothing; not libVLC |

## 7. What stays as it is

- The link-time and play-time checks (`BUG-90`) stay. They fail early, with a clearer failure.
- Subtitles and downloads keep their own checked clients.
- Local files (`fd://`, `KMP-74`) do not touch the gateway.

## 8. Test plan (each case red-checked against its mutation)

**Encoding:**
- round trip;
- dot segments;
- a missing token, a wrong token, a token of the wrong length;
- user info refused;
- `MAX_URL_CHARS`;
- control characters.

**Rewriter** (hostile-input tests, made-up hosts only):
- each URI-bearing tag above;
- quoted commas in a list;
- an unknown tag passed through unchanged;
- relative, root-relative and protocol-relative urls resolved against the final url;
- `skd:` and `data:` left alone;
- a BOM and CRLF;
- each limit, one past and at it;
- an attribute list that does not parse, passed through;
- variant, then media, then segments, across three hosts.

**Gateway, against local servers** (allowed through the check's injection, as the existing tests do):
- a segment on a refused host gets 0 requests;
- a redirect to a refused address gets 0 requests;
- `Range` gives `206` with its headers;
- cookies from host A are never sent to host B, and never reach libVLC;
- each never-forwarded header never arrives upstream;
- `CONNECT` and absolute-form get `400`, other methods `405`;
- 16 connections at once, and the 17th closed;
- oversized headers refused;
- a slow playlist gives up at 5 s;
- every network wait inside `withTimeout(60_000)`.

**With VLC.app** (skips without it or `sandbox-exec`):
- the Apple HLS test stream plays through the gateway, reaching buffering and starting its decoders;
- with the gateway closed, the same run fails closed;
- a playlist whose segments sit on a refused host plays nothing, with 0 requests at that host.

**The policy:** the media host's profile allows only the gateway port. Red-checked: with `*:*` put back, the
refused-segment test reaches the host.

## 9. To measure before the first line of code

1. libVLC plays an HLS playlist served from `http://127.0.0.1:<port>/…` under a gateway-only policy, through the new
   http module, with keep-alive. Expected, from the plain-http run in `BUG-90`; not yet measured on loopback.
2. libVLC does not resolve a gateway url's host. It is a literal, so it should not, and mDNSResponder can then be
   denied without breaking playback.
3. How many connections at once libVLC's adaptive demuxer opens on the Apple test stream, to size §5's 16.

## Open questions for review

1. **DASH:** in this task, or a follow-up? The proposal is HLS first, with DASH as its own step, because most plugin
   streams seen so far are HLS or progressive.
2. **The plugin's headers:** should the gateway hold a stream's `Referer` and `User-Agent` itself, from the
   `StreamLink`, so libVLC never sees them? That is cleaner, but it changes `MediaProtocol`'s `OP_PLAY`.
3. **`#EXT-X-DEFINE`:** is leaving it unexpanded acceptable?
