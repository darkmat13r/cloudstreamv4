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
  so does `mDNSResponder`, since the host no longer resolves anything (measured, §9).
- **A host that plays a downloaded file has no network at all** (review of PR 2). It gets its file as a descriptor
  and needs nothing else.
- **Neither host may ask the system's certificate services** (`trustd`, `ocspd`, `securityd`). Behind the gateway
  libVLC speaks only plain http to `127.0.0.1`. Those services fetch OCSP responses, CRLs and issuers on a caller's
  behalf, so a crafted certificate could aim them at an address the kernel refuses the host itself. With sound off
  and no certificate services, the media rules name no mach service. Then they write no `mach-lookup` rule at all,
  because one with no names would allow every service.
- **Order:** the gateway binds its port first, on both `127.0.0.1` and `[::1]`, and only then is the media host's
  profile written with that port. Seatbelt's `localhost:<port>` allows both loopback addresses (measured, §9). If the
  gateway held only one, another process could bind the same port on the other and catch whatever was aimed there.
  The media urls name `127.0.0.1`; the `[::1]` socket exists only to hold the port, and answers `404` to everything.

**The kernel is the guarantee.** Anything the gateway does not route, libVLC cannot fetch directly: a url the rewriter
missed, an unknown scheme, a redirect libVLC tried to follow itself, `rtsp://`, `udp://`. Each fails closed with
"Operation not permitted" (measured in `BUG-90`). So **the rewriter is there for playback, not for safety**. A bug
in it can stop a stream from playing; it cannot open a connection the check has not seen. That is what keeps the
hostile-input parsing below small: it never has to be complete, only bounded.

## 1. The url encoding

```
http://127.0.0.1:<port>/<token>/<role>/<scheme>/<authority>/<path>[?<query>]
        e.g. /k3Jd…Q/s/https/cdn.example/v/1080/seg-0001.ts?sig=…
```
- `<token>`: 128 random bits, base64url, made per gateway and given only to its media host. A request without it gets
  `404` and a fixed body. Other processes on this Mac can reach the port. The token stops them using the gateway as an
  open relay that fetches with the app's identity: its cookies, its address and its client. It is not what stops a
  relay into the LAN; the address check does that for every request, token or not. The comparison is constant-time.
- `<role>` (review, decision (i)): what the url is for, set by the rewriter.
  - `p` is a playlist: the uri after `EXT-X-STREAM-INF`, and `URI` on `EXT-X-MEDIA`, `EXT-X-I-FRAME-STREAM-INF`,
    `EXT-X-RENDITION-REPORT`, and `X-ASSET-URI`.
  - `s` is media: segments, keys, maps, parts, hints, data and steering files.
  - `u` is the stream's first url, whose role nobody knows. Its `Range` goes upstream (review 2), because most
    streams are a single file, and seeking needs it. If the answer is a playlist, it is fetched again, whole,
    without the range, and rewritten from that.
  - It decides `Range` (§2).
- `<scheme>`: `http` or `https`, and nothing else.
- `<authority>`: the host, lowercased, as `URI` reads it (IDN as ASCII), with `:port` when it is not the default.
  - User info is never encoded; a url with any is refused, as `SandboxPolicy` refuses it today.
  - An IPv6 literal is encoded bracketed (`[2001:db8::1]`). One with a zone id (`%25…`, `fe80::1%25en0`) is refused.
  - An empty host is refused.
  - A non-default port is allowed. That is safe because the check is by address, whatever the port.
- `<path>` and `<query>`: passed as received, percent-encoding kept byte for byte, so a signed url's signature
  survives. A fragment is dropped.
- **Decoding is strict, and in this order:**
  1. The request target is split on `/` while still raw. `<token>`, `<scheme>` and `<authority>` are taken from the
     first three raw segments, before anything is percent-decoded, so an encoded `/` (`%2F`) can never move a
     boundary.
  2. The rest is the upstream path. Its dot segments are removed as RFC 3986 §5.2.4 says, treating `%2e` and `%2E`
     as `.` for that purpose only. A path whose dot segments would climb above its root is refused; it is never
     clamped.
  3. Nothing else is decoded. The path and query go upstream byte for byte, so a signature survives.
  A target over `SandboxPolicy.MAX_URL_CHARS`, or holding a control character (raw or percent-encoded below `%20`),
  is refused.
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
- **Byte ranges** (review, decision (i)): `Range` and `If-Range` go upstream only for a url whose role is `s`, and
  `206`, `Content-Range`, `Content-Length` and `Accept-Ranges` come back.
  - A `p` url never sends a range. It is fetched whole, and served `200` rewritten.
  - A `u` url sends its range. Media comes back as it came, a `206` included. A playlist, even a `206` one, is
    fetched again whole, and only that is rewritten. A partial body is never rewritten.
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
- 5 s to read it all, so a slow trickle is a failure, not a wait;
- the **rewritten output** too: 8 MiB in total and 16 KiB per line after encoding, since each url grows by the
  gateway prefix and the token. Past either, `502`.

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
- A list that names `URI` twice does not parse, and passes through unchanged. The gateway never picks one of two.
- Tag and attribute names are matched exactly as RFC 8216 writes them, and written back exactly as received. Case is
  never changed, so a tag that only differs in case is an unknown tag.

**Resolution is lenient** (review 2). Real CDN urls carry `|`, `^`, `{}`, `[]` and spaces, which `java.net.URI`
refuses. So every such character after a reference's authority is percent-encoded first, and a `%` that starts no
escape becomes `%25`; nothing already valid changes. A base with no path resolves as if its path were `/`.

**Dot segments** are removed here too, once a reference is resolved, by the gateway's own rule (§1). A url whose `..`
climbs above its root is left as written, and the kernel stops it.

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
- **Until this step lands, a DASH manifest is refused at once** (`502`, kind `dash-unsupported`). The gateway
  recognises it by its `Content-Type` or by a body starting `<MPD`, or `<?xml` with an `<MPD` element. Passed through
  unrewritten, libVLC would be left waiting on urls it cannot reach. That is a hang, where a refusal is a prompt
  failure.

## 4. Headers

**What libVLC sends (measured, §9):** `Accept`, `Accept-Encoding`, `Accept-Language`, `Cache-Control`, `Host`, `Range`
(including on a playlist) and `User-Agent`; `Referer` when given `:http-referrer`; and `Cookie` once anything has set
one on `127.0.0.1`.

**The gateway owns `Referer` and `User-Agent`.** Each is made sendable when the gateway starts (review 2). OkHttp
throws on a header value outside printable ASCII, with the value in its message. So a `Referer` url is written as
OkHttp writes urls: an IDN host as punycode, and a non-ASCII path percent-encoded. Anything still outside printable
ASCII is dropped. The gateway takes them from the stream's `StreamLink` when the stream starts,
sends them on every upstream request of that stream, and drops whatever libVLC sends for either. libVLC is no longer
given them (`OP_PLAY` stops carrying them). The gateway never forwards a value that names the gateway
(`127.0.0.1:<port>`, `[::1]:<port>`) or holds the token, whatever header it is in.

**From libVLC upstream, forwarded:** `Range` and `If-Range`, for media only (never for a playlist, which is fetched
whole), and `Accept`.

**Never forwarded upstream:**
- `Host`: the gateway sets it from the decoded url;
- `Cookie` and `Authorization`;
- `Proxy-*`;
- the hop-by-hop headers: `Connection`, `Keep-Alive`, `TE`, `Trailer`, `Transfer-Encoding`, `Upgrade`;
- `Accept-Encoding`: the gateway decides it;
- `Accept-Language` and `Cache-Control`: neither is needed, and both fingerprint;
- `Referer` and `User-Agent` as libVLC sends them: the gateway's own go instead.

The gateway never adds `Forwarded`, `X-Forwarded-*` or `Via`.

**Cookies.** CDNs often hand out token cookies. libVLC would keep them against `127.0.0.1`, so one host's cookie
would go to every other. So:
- the gateway keeps its own cookie jar per gateway, in memory only, keyed by the real host, through OkHttp's
  `CookieJar`;
- cookies are parsed and matched by OkHttp's rules, `Cookie.parse` and `Cookie.matches`. So a `Set-Cookie` for another
  domain (`Domain=other.example` from `cdn.example`) is never kept;
- it never passes `Set-Cookie` on to libVLC;
- the jar goes with the gateway when the stream ends.

**Back to libVLC, passed:** `Content-Type`, `Content-Length` (not for a rewritten playlist, which gets its own),
`Content-Range`, `Accept-Ranges`, `Last-Modified`, `ETag`, `Cache-Control`.

**Back to libVLC, never passed:** `Set-Cookie`, `Location`, `Alt-Svc`, `Strict-Transport-Security`, CORS headers,
and anything else not on the list above.

## 4a. Logging and disk

Nothing that holds a url, a header, a cookie, the token or a body ever reaches a log, stdout, stderr or the disk. Not
a failure's message, not a debug line, not an exception's text.
- A failure is one of a fixed set of kinds: `refused-address`, `too-many-redirects`, `downgrade`, `playlist-too-large`,
  `playlist-unreadable`, `dash-unsupported`, `upstream-tls`, `upstream-timeout`, `upstream-unreachable`,
  `upstream-status-4xx`, `upstream-status-5xx`, `bad-request`, `no-token`, `internal`.
- A log line, if any, is a kind and a count. Nothing else.
- Exceptions from OkHttp and the parser are mapped to a kind at the boundary, and their message is dropped.
- Each connection's thread catches everything that is left (`Throwable`), counts it as `internal`, and writes
  nothing. An uncaught exception would reach the default handler, which prints it and its message (review 2).
- The cookie jar is in memory only. Nothing is cached to disk: OkHttp's cache is off.

## 5. The gateway's own limits

- Binds `127.0.0.1`, on an ephemeral port, and then the same port on `[::1]`.
  - Both sockets are exclusive: `SO_REUSEADDR` is off (a Java `ServerSocket` has it on by default on macOS; checked)
    and `SO_REUSEPORT` is off.
  - If the `[::1]` bind fails, both are closed and a new port is tried, five times.
  - After five, the gateway does not start, and nor does the stream. It fails closed and never plays without the
    gateway.
- 16 connections from its media host at once; the next is closed unanswered.
- Request line and headers: 16 KiB together, 100 headers.
- Upstream: 15 s to connect, 30 s between reads, and no limit on a media body's total length, since a film is long.
- One gateway per media host. It is closed with the host: its listeners, every connection it is serving, its pool
  and its cookie jar.

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
| a DASH manifest (before its step) | `502`, at once | failure |
| the gateway cannot hold its port | it does not start, and the stream does not start | failure |

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
- every network wait inside `withTimeout(60_000)`;
- **prints nothing:** a run whose urls, `Referer`, `User-Agent`, cookies and bodies each hold a marker, with stdout and
  stderr captured and the gateway's directory listed afterwards. No marker appears anywhere, and nothing is written to
  disk;
- `Referer` and `User-Agent` upstream are the `StreamLink`'s, whatever libVLC sends; a value naming the gateway or
  holding the token never goes upstream;
- an IPv6 literal round-trips bracketed; a zone id and an empty host are refused;
- `%2F` in the first three segments cannot move a boundary; `%2e%2e` above the root is refused;
- the rewritten-output limits, at the limit and one past it;
- a list naming `URI` twice passes through unchanged, and so does a tag in another case;
- the `[::1]` socket answers `404` to a valid token.

**With VLC.app** (skips without it or `sandbox-exec`):
- the Apple HLS test stream plays through the gateway, reaching buffering and starting its decoders;
- with the gateway closed, the same run fails closed;
- a playlist whose segments sit on a refused host plays nothing, with 0 requests at that host.

**Redirect hops** are decided by one pure function, `nextHop`, tested without a server (review 2): an https to http
step is refused, a scheme-relative one keeps https, http to https is allowed, and user info, a missing `Location`
and a non-http one are refused.

**The policy:** the media host's profile allows only the gateway port. Red-checked: with `*:*` put back, the
refused-segment test reaches the host.

## 9. Measured (scratch runs, VLC 3.0.24, nothing committed)

The streams were Apple's public HLS test streams, played with no audio or video output and a scratch home. A
stand-in gateway in a scratch script played the gateway's part: it fetched upstream outside the sandbox and rewrote
the playlists. The media host's policy allowed only that port.

1. **libVLC plays an HLS playlist from `http://127.0.0.1:<port>/…` under a gateway-only policy.** It buffered to 96%
   and started its decoders, over the new http module, with keep-alive (6 requests on one connection).
2. **libVLC does not need DNS for a gateway url.** With `mDNSResponder` denied it played the same, and nothing was
   refused. So the media host's profile drops it.
3. **Connections at once:** at most 2 on the test stream. §5's 16 leaves room.
4. **Seatbelt's port granularity holds.** With only `localhost:A` allowed, `127.0.0.1:B` and `[::1]:B` are refused
   ("Operation not permitted"). `127.0.0.1:A` and **`[::1]:A` both connect**, so the gateway holds `A` on both
   (§ "The shape of the fix").
5. **What libVLC sends** is listed in §4. It sends `Range` on a playlist, so "a playlist is fetched whole and served
   `200`" is needed, not tidiness. With `:http-referrer` and `:http-user-agent` it sends both on every request,
   segments included.
6. **libVLC follows a 3xx from loopback on its own** (it requested the target twice), and **it keeps a `Set-Cookie`
   from `127.0.0.1`** and sends it back (5 of 6 later requests). Neither is moot. The gateway never answers 3xx, never
   passes `Set-Cookie`, and never forwards `Cookie`, as §2 and §4 say.
7. **Through the wiring (PR 2):** `--check-playback` on the Apple HLS test streams, the gateway between. All played:
   - TS, master and variants, 4:3 and 16:9;
   - fMP4 with `EXT-X-MAP`, alternate audio and I-frame playlists, at 1920×1080;
   - one segment as a single file, through the first-url path.

   No stream url, and no OkHttp leak line, appeared in their output.
8. **The media host's vlcj player does not play a local playlist's items:** a `.m3u8` opened as a file expands into
   items it never plays. So the test of the sandbox uses a url the rewriter leaves as written (one with user info,
   which the gateway refuses to encode) inside a playlist served through the gateway. libVLC then tries it itself,
   and the sandbox refuses it. With `*:*` added to the policy, it is reached (checked).

## Open questions for review

1. **DASH:** in this task, or a follow-up? The proposal is HLS first, with DASH as its own step, because most plugin
   streams seen so far are HLS or progressive.
2. ~~The plugin's headers~~ — decided in review: the gateway owns them (§4), and `OP_PLAY` stops carrying them.
3. **`#EXT-X-DEFINE`:** is leaving it unexpanded acceptable?
