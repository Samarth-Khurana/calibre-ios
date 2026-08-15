# Fetching books from a Calibre library on the Mac, from iOS

Research for [issue #5](https://github.com/Samarth-Khurana/calibre-ios/issues/5). **Options only — no mechanism is chosen here.** That is issue #8.

Evaluated against the map's hard constraint: **offline-first**. Books get downloaded to device and read locally; streaming/read-without-download is out of scope. So the bar every option must clear is: *enumerate a remote library, and pull whole EPUB files down over the LAN.*

Date of research: August 2026. Calibre docs current at 9.x.

---

## TL;DR of the option space

| Option | Browse | Search | Auth | Bulk EPUB download | Discovery | Notes |
|---|---|---|---|---|---|---|
| **Content Server — `/ajax/*` + `/get/`** | yes, JSON | yes, server-side | HTTP Basic/Digest, or none | yes, one HTTP GET per book | manual host:port, or `_calibre._tcp` mDNS (opt-in) | Richest metadata. Undocumented-but-stable API. |
| **Content Server — OPDS** | yes, Atom XML | yes | same server auth | yes, acquisition links | same server, mDNS advertises the `/opds` path | Standardised, but thinner metadata than ajax. |
| **Smart device / wireless driver** | push-only model | no | SHA1 challenge-response | calibre *pushes* to the app | own mDNS `_calibresmartdeviceapp._tcp` + UDP broadcast | Custom socket protocol. Inverted control flow. |
| **SMB / direct folder access** | filesystem | no | macOS file sharing creds | yes | manual | No first-class iOS SMB client API. |
| **Files app / share sheet import** | n/a (user-driven) | n/a | n/a | one book at a time, manual | n/a | Zero-infrastructure fallback. |

---

## 1. Calibre Content Server

### Enabling it

- GUI: **Connect/share → Start Content server**. Default bind `http://127.0.0.1:8080`. Configured under **Preferences → Sharing → Sharing over the net**.
- Headless: the `calibre-server` command, which can be run as a launchd/systemd service pointed at a library folder.
- Source: <https://manual.calibre-ebook.com/server.html>, <https://manual.calibre-ebook.com/generated/en/calibre-server.html>

Relevant `calibre-server` options:

| Option | Meaning |
|---|---|
| `--port` | listening port (8080 default) |
| `--listen-on` | interface; default is all available IPv4 + IPv6 interfaces |
| `--enable-auth` / `--disable-auth` | password-based authentication |
| `--auth-mode` | `auto` (digest) or `basic`; docs say use `basic` behind an SSL proxy |
| `--userdb` | path to the SQLite user database |
| `--manage-users` | interactive user administration |
| `--url-prefix` | prefix for all URLs, for reverse proxying |
| `--ssl-certfile` / `--ssl-keyfile` | direct HTTPS |
| `--enable-use-bonjour` / `--disable-use-bonjour` | "Advertise OPDS feeds via the BonJour service, so that OPDS based reading apps can detect and connect to the server automatically" |

### Authentication

Three practical postures:

1. **No auth** (default on a trusted LAN). Anything on the network can read the library. Simplest client.
2. **Auth on, `--auth-mode=auto`** → HTTP **Digest**. Client must implement digest challenge-response. Ktor/OkHttp on the KMP side would need a digest auth provider (Ktor has one; check the darwin engine path).
3. **Auth on, `--auth-mode=basic`** → HTTP **Basic**. Trivial to implement, but sends credentials base64-only unless HTTPS is on. On a home LAN with a self-signed cert, HTTPS means a trust-anchor problem on iOS (either pin the cert or add an ATS exception) — a real cost to budget for.

Users are managed in a SQLite `users.sqlite`; the manual documents `--manage-users` for creating them. Per-user library restriction exists in the server preferences UI but is not detailed in the page fetched.

### The JSON (`/ajax/*`) API

Defined in [`src/calibre/srv/ajax.py`](https://github.com/kovidgoyal/calibre/blob/master/src/calibre/srv/ajax.py):

| Route | Params | Returns |
|---|---|---|
| `GET /ajax/library-info` | — | map of available libraries + the default library id |
| `GET /ajax/search/{library_id}` | `query`, `num`, `offset`, `sort`, `sort_order`, `vl` | matching book ids + result counts |
| `GET /ajax/books/{library_id}` | `ids`, `id_is_uuid`, `category_urls` | id → full metadata dict |
| `GET /ajax/book/{book_id}/{library_id}` | `id_is_uuid`, `category_urls` | one book's metadata |
| `GET /ajax/categories/{library_id}` | `vl` | top-level categories (authors, tags, series…) |
| `GET /ajax/category/{encoded_name}/{library_id}` | `num`, `offset`, `sort`, `sort_order` | category items, counts |
| `GET /ajax/books_in/{category}/{item}/{library_id}` | `num`, `offset`, `sort`, `sort_order` | book ids in that category |

The shape that matters for us: **search returns ids, then you batch-hydrate them with `/ajax/books`.** That is a two-call pattern with server-side paging (`num`/`offset`) — which is what an offline-first sync wants, since you can page the whole library and cache metadata locally.

`query` accepts calibre's own search grammar (`author:tolkien`, `tags:=scifi`, `format:epub`), so filtering to EPUB-only is a server-side concern, not a client one.

### Downloading files (`/get/`)

Defined in [`src/calibre/srv/content.py`](https://github.com/kovidgoyal/calibre/blob/master/src/calibre/srv/content.py):

```
GET /get/{what}/{book_id}/{library_id}
```

`what` is a format name (`epub`, `mobi`, …) or one of `cover`, `thumb`, `opf`, `json`.

- `GET /get/epub/123` → the EPUB bytes, with a `Content-Disposition` filename built from author + title.
- `GET /get/cover/123`, `GET /get/thumb/123?sz=60x80` → cover art / sized thumbnail.
- `GET /get/opf/123` → metadata as OPF.
- These routes are auth-required and validate that the book id is visible to the user.
- `/static/*` and the favicon routes are `auth_required=False`.

This is a plain HTTP GET of a static-ish file — so **HTTP range requests, resumable downloads, and a background `URLSession` transfer are all plausible** for the download layer. Worth verifying range support empirically against a live server; the code path was not confirmed in this pass.

`/get/` also serves per-book **data files** (`/data-files/get/{book_id}/{relpath}/{library_id}`) and there are `set-note` / `data-files/upload` write endpoints requiring write access — relevant later to the *sync* ticket, not to fetching.

### API stability

- The `/ajax/*` API is **not part of calibre's documented public API** — the manual's server page documents none of it. It is a long-lived internal API used by calibre's own web client, and third parties have relied on it for years (see the [MobileRead thread](https://www.mobileread.com/forums/showthread.php?t=291587) and [Launchpad #1184153](https://bugs.launchpad.net/bugs/1184153), a long-standing request to bless a REST API). Practically stable across the 3.x → 9.x era, but **no compatibility guarantee**.
- Kovid has historically declined to freeze it as a contract. Risk to record: a calibre upgrade on the Mac could break the client, with no deprecation window.
- Mitigation available: OPDS (below) is a published spec and is the more conservative contract.
- Version detection: `/ajax/library-info` is a cheap liveness/version probe; calibre also exposes an `/interface-data/*` family used by the modern web client (newer and more churn-prone than `/ajax/*` — avoid).

### OPDS

Defined in [`src/calibre/srv/opds.py`](https://github.com/kovidgoyal/calibre/blob/master/src/calibre/srv/opds.py). **OPDS 1.x** (Atom, namespace `http://opds-spec.org/2010/catalog`).

Routes:

- `/opds` — root catalog
- `/opds/navcatalog/{which}` — browse by category
- `/opds/category/{category}/{which}`
- `/opds/categorygroup/{category}/{which}`
- `/opds/search/{query}` — search

Acquisition entries carry per-format links of the form `/get?book_id={id}&library_id={id}&what={format}` with the correct MIME type (`application/epub+zip`), file size and mtime, under `rel="http://opds-spec.org/acquisition"`. Covers/thumbnails come as separate `cover`/`thumbnail`/`image` links.

Trade-off vs `/ajax/*`: OPDS is a **standard** (so a client written against it also works with calibre-web, Komga, Kavita, COPS — cf. justRead, which supports exactly that set: <https://justread.app/en/opds-reader>), but the Atom entries expose less of calibre's custom-column and metadata surface, and paging/sorting control is weaker than `num`/`offset`/`sort`.

---

## 2. Discovery on a home LAN

### Does calibre advertise itself?

**Yes, but only when Bonjour advertising is enabled, and it advertises OPDS.**

- [`src/calibre/srv/bonjour.py`](https://github.com/kovidgoyal/calibre/blob/master/src/calibre/srv/bonjour.py) publishes service type **`_calibre._tcp`** with a TXT record containing a **`path`** key, whose default value is `/opds` (prefixed by `--url-prefix` if set).
- Controlled by `--enable-use-bonjour` / `--disable-use-bonjour`, and by a toggle in the server preferences' advanced section.
- The service type `_calibre._tcp` has been in place for many years (see [Launchpad #1704877](https://bugs.launchpad.net/calibre/+bug/1704877)).

Consequence for a client: **mDNS discovery gets you host, port, and the OPDS path.** It does *not* advertise a `/ajax` path — but since ajax lives on the same host:port, discovering via Bonjour and then talking `/ajax/*` to the same origin is fine.

**Fallback that must exist regardless:** manual entry of `host:port` (or a `.local` hostname). Bonjour may be off, may be blocked by the router, or may not cross VLANs/guest networks.

### The `.local` hostname trick

A Mac publishes `<ComputerName>.local` via Bonjour by default (independent of calibre). Storing `mymac.local:8080` rather than `192.168.1.42:8080` sidesteps DHCP churn without needing calibre's own advertising — the resolution is done by mDNS at connect time. Worth noting as a middle path between "manual IP" and "full service discovery". Caveat: the user can rename the Mac, and some routers/AP-isolation setups break mDNS.

---

## 3. The smart-device / wireless-device protocol (Calibre Companion's protocol)

From [`src/calibre/devices/smart_device_app/driver.py`](https://github.com/kovidgoyal/calibre/blob/master/src/calibre/devices/smart_device_app/driver.py):

- Advertises **`_calibresmartdeviceapp._tcp`** via mDNS, identifier string `calibre smart device client`.
- Also does UDP discovery on fixed broadcast ports **54982, 48123, 39001, 44044, 59678** — the app broadcasts a hello, calibre replies with its TCP port and content-server details. This is a backup for mDNS.
- TCP control channel on a configurable port (tries 9090, else a random port in 8192–65525), advertised via mDNS.
- Wire format: **length-prefixed JSON** — an ASCII length, a `[` delimiter, then a JSON `[opcode, data_dict]` pair.
- ~20 opcodes: `NOOP, OK, SEND_BOOK, GET_BOOK_METADATA, DELETE_BOOK, FREE_SPACE, GET_DEVICE_INFORMATION, GET_INITIALIZATION_INFO, SEND_BOOKLISTS, CALIBRE_BUSY, ERROR`, plus metadata-sync and file-transfer ops.
- Auth: **SHA1 challenge-response** — calibre issues an ISO-format timestamp as challenge, client returns `sha1(password + challenge)`.

### Why this is awkward for us

The control flow is **inverted**: this protocol models the phone as a *device calibre drives*. Calibre pushes books to the app (`SEND_BOOK`); the app does not browse-and-pull. There is no search. The user has to sit at the Mac and select books to send. That is a legitimate offline-first mechanism — books do land on device — but it is a fundamentally different UX from "browse your library on the phone and tap download", and it requires the calibre GUI to be running with the wireless driver started (it is a *device driver*, not the content server).

Implementation cost: a bespoke socket protocol in KMP, plus a raw TCP listener and UDP broadcast handling on iOS — which is exactly the API surface that trips iOS local-network privacy hardest.

Ecosystem note: **Calibre Companion (iOS) is effectively abandoned** — last updated 2020, subscription-gated, unresponsive developer ([MobileRead](https://www.mobileread.com/forums/showthread.php?t=339399), [justRead's survey](https://justread.app/en/posts/calibre-alternative-for-ios)). So there is no reference client to lean on, and no pressure on calibre to keep this protocol working for iOS.

---

## 4. SMB / direct folder access

Calibre libraries are plain folders: `Calibre Library/Author/Title (id)/Title - Author.epub` plus `metadata.db` (SQLite) at the library root. Sharing the folder over macOS File Sharing (SMB) would expose both.

Limits:

- **iOS has no public SMB client API.** The Files app can mount an SMB share (Connect to Server) and a `UIDocumentPicker` can then read from it, but there is no framework for an app to enumerate an SMB share programmatically without shipping a third-party SMB implementation. That is a large, licence-sensitive dependency for a KMP project.
- Reading `metadata.db` directly would give a complete metadata picture (and SQLite is trivially available in KMP), but only if the file can be reached — which loops back to the same access problem. It also means reading calibre's schema, which *does* change between major versions, and racing with calibre's own writes.
- Auth is macOS account credentials — heavier than a content-server password.
- No search, no server-side paging; everything is a client-side scan.

Realistic role: a **user-mediated** path via the Files app, not a programmatic one.

---

## 5. Manual import — Files app and share sheet

The zero-infrastructure fallback, and the one that always works:

- **`UIDocumentPickerViewController` / SwiftUI `.fileImporter`** — user picks EPUBs from Files (iCloud Drive, On My iPhone, or a mounted SMB/WebDAV share). App copies them into its own container. Requires security-scoped resource handling for out-of-container URLs.
- **Share sheet / "Open in"** — declare EPUB (`org.idpf.epub-container`) in `CFBundleDocumentTypes` and the app appears as a destination from Safari, Mail, Files. Files arrive in `Documents/Inbox`.
- **iTunes/Finder file sharing** (`UIFileSharingEnabled`) — drag EPUBs onto the device from the Mac over USB. Notably relevant for a *personal sideload* build, where the Mac and the device are already tethered for installation.
- **AirDrop** from the Mac — same handler as the share sheet.

Capabilities: one book at a time (or a multi-select), no metadata beyond what's inside the EPUB's OPF, no covers beyond the embedded one, no library-side identity (so no reading-position sync anchor unless matched by ISBN/UUID). But it is fully offline-first, needs no server, no permission prompt, and no discovery.

Strong case for keeping this as a **secondary path regardless of the primary mechanism chosen** — it is the escape hatch when the Mac is asleep, off-network, or the user is travelling.

---

## 6. Practical constraints to design around

### The Mac is asleep or off

The single biggest reliability hole in every server-based option. On macOS:

- A sleeping Mac does not serve HTTP. Bonjour Sleep Proxy will keep the *advertisement* alive and can wake the Mac on a matching connection attempt (Wake on Demand), but only when the Mac is on Ethernet or on Wi-Fi with a compatible sleep-proxy on the network, and it is not dependable enough to build a UX on. <https://en.wikipedia.org/wiki/Bonjour_Sleep_Proxy>
- Practical mitigations to consider (all belong to a later decision ticket): run `calibre-server` as a launchd daemon rather than via the GUI; set the Mac's energy settings to prevent sleep / enable "Wake for network access"; and — most importantly — **design the app so an unreachable server is a normal state, not an error state**. Offline-first already implies this: the on-device library is the source of truth for reading, and sync is opportunistic.
- Corollary: downloads must be **queueable and resumable**. "Mark for download, fetch when the server next appears" is the shape that survives this constraint.

### DHCP / changing IPs

- A stored raw IP will eventually be wrong. Options in increasing robustness: (a) store `<name>.local` and let mDNS resolve; (b) re-discover via `_calibre._tcp` on each launch and cache the last-known address as a hint; (c) ask the user to set a DHCP reservation (pushes the problem onto them).
- A client should probably do (b) with a fallback to (a) and a manual override.

### iOS Local Network permission

Sources: [TN3179](https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy), [`NSLocalNetworkUsageDescription`](https://developer.apple.com/documentation/bundleresources/information-property-list/nslocalnetworkusagedescription).

- Since **iOS 14**, an app must hold Local Network permission to talk to hosts on the local network. This covers Bonjour/mDNS browsing (`NWBrowser`, `NWListener`, DNS-SD), multicast/broadcast, **and direct socket or HTTP connections to private IP ranges** (10/8, 172.16/12, 192.168/16). Connecting to `192.168.1.42:8080` with no Bonjour at all still trips it.
- Info.plist keys required:
  - `NSLocalNetworkUsageDescription` — a user-facing string. **The prompt does not appear without it**; the access simply fails.
  - `NSBonjourServices` — an array declaring every service type the app will browse for. Undeclared types are not discoverable. For our options that means `_calibre._tcp` and/or `_calibresmartdeviceapp._tcp` (and `_http._tcp` if generic browsing is ever wanted).
- Denial is **graceful but silent-ish**: discovery returns nothing and connections fail; there is no API to query the permission state directly, and no way to re-prompt — the user must go to Settings → Privacy & Security → Local Network. TN3179's advice is to trigger the alert deliberately (e.g. start a Bonjour browse) at a moment where the app can explain why, rather than letting it fire mid-task.
- **iOS 18** narrowed one case: connecting to the device's *own* addresses no longer requires local network access. It did not relax LAN-peer access. Apple's guidance in TN3179 is to move off ad-hoc direct-IP approaches toward the documented trigger pattern.
- **Sideloaded / development builds**: the permission model is the same — a personal-provisioning build still prompts and still needs the Info.plist keys. Two wrinkles worth flagging for the implementer: (1) the permission is tracked per-app-install, and deleting/reinstalling (which happens constantly with a 7-day personal provisioning profile) can reset it, so the user may see the prompt repeatedly; (2) Local Network permission state is one of the things that behaves inconsistently across rebuild-and-reinstall cycles in Xcode, so a "we can't see your Mac — check Settings" recovery screen is not optional polish, it's a requirement.
- Note this constraint applies to **every** LAN option here (Content Server, OPDS, smart-device). Only the Files-app import path avoids it entirely.

### HTTPS on a LAN

If auth is enabled and the transport should be encrypted, a self-signed cert means either an ATS exception or explicit cert pinning/trust in the client — and calibre's `--auth-mode=basic` guidance assumes SSL is present. On a personal-sideload app on a home LAN, plain HTTP with digest auth is a defensible posture, but it should be a recorded decision rather than an accident.

---

## Open questions for the decision ticket (#8)

1. Does `/get/{format}/{id}` support HTTP range requests? (Determines resumable/background downloads.) Needs empirical check against a live server.
2. Ktor's digest-auth support on the darwin engine — verified, or does that force `--auth-mode=basic`?
3. Is a KMP-shared mDNS browser feasible, or does discovery have to be `expect/actual` over `NWBrowser` on iOS? (Almost certainly the latter.)
4. `/ajax/*` (richer, unpromised) vs OPDS (thinner, standard) — and does the reading-position sync ticket need metadata that only `/ajax/*` exposes (custom columns)?
5. What is the acceptable degraded mode when the Mac is asleep — silent retry, or a visible "library offline" state?

---

## Sources

- calibre Content server manual — <https://manual.calibre-ebook.com/server.html>
- `calibre-server` options — <https://manual.calibre-ebook.com/generated/en/calibre-server.html>
- `srv/ajax.py` — <https://github.com/kovidgoyal/calibre/blob/master/src/calibre/srv/ajax.py>
- `srv/content.py` — <https://github.com/kovidgoyal/calibre/blob/master/src/calibre/srv/content.py>
- `srv/opds.py` — <https://github.com/kovidgoyal/calibre/blob/master/src/calibre/srv/opds.py>
- `srv/bonjour.py` — <https://github.com/kovidgoyal/calibre/blob/master/src/calibre/srv/bonjour.py>
- `devices/smart_device_app/driver.py` — <https://github.com/kovidgoyal/calibre/blob/master/src/calibre/devices/smart_device_app/driver.py>
- Apple TN3179, Understanding local network privacy — <https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy>
- `NSLocalNetworkUsageDescription` — <https://developer.apple.com/documentation/bundleresources/information-property-list/nslocalnetworkusagedescription>
- iOS 18 local network permission thread — <https://developer.apple.com/forums/thread/766133>
- REST API request (Launchpad #1184153) — <https://bugs.launchpad.net/bugs/1184153>
- Bonjour discovery bug (#1704877) — <https://bugs.launchpad.net/calibre/+bug/1704877>
- calibre server REST/ajax discussion — <https://www.mobileread.com/forums/showthread.php?t=291587>
- Calibre Companion status — <https://www.mobileread.com/forums/showthread.php?t=339399>
- justRead on iOS Calibre alternatives / OPDS — <https://justread.app/en/posts/calibre-alternative-for-ios>, <https://justread.app/en/opds-reader>
- Bonjour Sleep Proxy — <https://en.wikipedia.org/wiki/Bonjour_Sleep_Proxy>
