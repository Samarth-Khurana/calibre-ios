# Calibre reading-progress model and its sync surfaces

Research for issue #4. Evidence is from calibre's source at `master` (calibre 9.x, read August 2026),
the calibre manual, and justRead's own marketing/support pages. Everything here is the *documented /
source-derived* picture; issue #6 should confirm it against a live server.

## Verdict up front

**Partly achievable — and the asymmetry is the whole story.**

- **Third-party client → calibre's *web* reader (Content server): YES.** There is a real, working HTTP
  write endpoint (`POST /book-set-last-read-position/...`) and the web reader auto-resumes from it.
- **Third-party client → calibre's *desktop* E-book viewer (the app on the Mac): NO, not over HTTP.**
  The desktop viewer never reads `last_read_positions` from `metadata.db` when deciding where to open a
  book. Its resume position comes only from local files on the machine running the viewer (a per-book
  JSON in the viewer's config dir, and `META-INF/calibre_bookmarks.txt` embedded in the EPUB itself).
  No Content server endpoint can write either of those.
- **Desktop viewer → third-party client: YES, two ways** — the viewer *writes* its position into
  `last_read_positions` (readable over HTTP), and also embeds it in the EPUB file in the library
  (readable by parsing the downloaded EPUB).

So "opens where I left off, on both sides" is achievable if "the other side" is the calibre **web
reader**. If it must be the **desktop viewer app**, the only route is writing to the library on disk
(direct `metadata.db`/EPUB access or a calibre plugin), not the Content server API.

---

## 1. Where calibre stores position and annotations

### `metadata.db` schema
Source: [`resources/metadata_sqlite.sql`](https://github.com/kovidgoyal/calibre/blob/master/resources/metadata_sqlite.sql)

```sql
CREATE TABLE last_read_positions ( id INTEGER PRIMARY KEY,
    book INTEGER NOT NULL,
    format TEXT NOT NULL COLLATE NOCASE,
    user TEXT NOT NULL,
    device TEXT NOT NULL,
    cfi TEXT NOT NULL,
    epoch REAL NOT NULL,
    pos_frac REAL NOT NULL DEFAULT 0,
    UNIQUE(user, device, book, format)
);

CREATE TABLE annotations ( id INTEGER PRIMARY KEY,
    book INTEGER NOT NULL,
    format TEXT NOT NULL COLLATE NOCASE,
    user_type TEXT NOT NULL,
    user TEXT NOT NULL,
    timestamp REAL NOT NULL,
    annot_id TEXT NOT NULL,
    annot_type TEXT NOT NULL,
    annot_data TEXT NOT NULL,
    searchable_text TEXT NOT NULL DEFAULT '',
    UNIQUE(book, user_type, user, format, annot_type, annot_id)
);
```
Plus `annotations_fts` / `annotations_fts_stemmed` (full-text search) and `annotations_dirtied`.
Rows are cascaded away by a delete trigger on `books`.

Key point: **position and annotations are two different tables with two different identity models.**
`last_read_positions` is keyed by `(user, device, book, format)` — one row per device, so multiple
devices coexist and the reader picks the newest by `epoch`. `annotations` is keyed by
`(book, user_type, user, format, annot_type, annot_id)` — no device dimension.

### Who writes which row
- **Content server web reader** → `last_read_positions` with `user = <logged-in server username>`,
  `device = <browser-generated device uuid>`.
- **Desktop E-book viewer** → `last_read_positions` with the constants from
  [`src/calibre/db/constants.py`](https://github.com/kovidgoyal/calibre/blob/master/src/calibre/db/constants.py):
  `EBOOK_VIEWER_USER = 'local'`, `EBOOK_VIEWER_DEVICE = 'calibre-desktop-viewer'`. See
  [`src/calibre/gui2/viewer/integration.py`](https://github.com/kovidgoyal/calibre/blob/master/src/calibre/gui2/viewer/integration.py).
  It writes either through the running calibre GUI (IPC message `save-annotations:`) or, if the GUI
  isn't running, by opening `metadata.db` directly with apsw in READWRITE mode.
- **Desktop viewer annotations** → `annotations` with `user_type='local', user='viewer'`, and
  additionally mirrored to `user_type='web', user=<sync_annots_user>` if the viewer preference
  *"Sync bookmarks/highlights with Content server user"* is set
  ([`src/pyj/read_book/prefs/misc.pyj`](https://github.com/kovidgoyal/calibre/blob/master/src/pyj/read_book/prefs/misc.pyj),
  [`db/backend.py: save_annotations_list_to_cursor`](https://github.com/kovidgoyal/calibre/blob/master/src/calibre/db/backend.py)).
- **Content server annotations** → `annotations` with `user_type='web', user=<server username>`
  (hard-coded in [`src/calibre/srv/books.py`](https://github.com/kovidgoyal/calibre/blob/master/src/calibre/srv/books.py)).

### Per-book / on-disk copies (the desktop viewer's real source of truth)
From [`src/calibre/gui2/viewer/annotations.py`](https://github.com/kovidgoyal/calibre/blob/master/src/calibre/gui2/viewer/annotations.py)
and `gui2/viewer/ui.py`:
1. A JSON blob in the viewer's `annotations_dir()`, keyed per book — always written.
2. `META-INF/calibre_bookmarks.txt` **inside the EPUB file in the library** — written when the
   `save_annotations_in_ebook` preference is on (default **True**). Format: magic prefix
   `EPUB_FILE_TYPE_MAGIC` followed by base64 of a JSON annotation list; an older line-based legacy
   format is still parsed. See
   [`srv/render_book.py: get_stored_annotations`](https://github.com/kovidgoyal/calibre/blob/master/src/calibre/srv/render_book.py)
   and [`ebooks/oeb/iterator/bookmarks.py`](https://github.com/kovidgoyal/calibre/blob/master/src/calibre/ebooks/oeb/iterator/bookmarks.py).

The annotation list uses a `type` field; the last-read position is the pseudo-annotation
`{'type': 'last-read', 'pos': 'epubcfi(/...)', 'pos_type': 'epubcfi', 'timestamp': <iso8601>}`.

**Trap worth knowing:** `last-read` entries are silently dropped when written to the `annotations`
table. `annot_db_data()` in
[`db/annotations.py`](https://github.com/kovidgoyal/calibre/blob/master/src/calibre/db/annotations.py)
only produces an `annot_id` for `bookmark` and `highlight`; `save_annotations_for_book` skips rows
with `aid is None`. So you cannot smuggle a reading position into the desktop viewer via
`/book-update-annotations` — it will be accepted and then discarded.

---

## 2. Content server HTTP API

**Undocumented.** The [Content server manual page](https://manual.calibre-ebook.com/server.html)
documents no HTTP API for progress or annotations at all; it only says, user-facing:
> "If you leave the Content server running, you can even open the same book on multiple devices and it
> will remember your last read position. If it does not you can force a sync by tapping in the top
> quarter and choosing Sync."

The endpoints below are read from
[`src/calibre/srv/books.py`](https://github.com/kovidgoyal/calibre/blob/master/src/calibre/srv/books.py)
(`master`). They are the routes calibre's own web reader uses; they are ordinary authenticated HTTP
routes with no browser-only guard, so a third-party client can call them — but they are private API
and can change without notice.

| Route | Method | Notes |
|---|---|---|
| `/book-get-last-read-position/{library_id}/{which}` | GET | `which` = `bookid-FMT_bookid-FMT...` (underscore-separated, hyphen between id and format). **Returns 404 `login required for sync` if unauthenticated.** Result keyed `"<book_id>:<FMT>"`. |
| `/book-set-last-read-position/{library_id}/{book_id}/{fmt}` | **POST** | JSON body `{"device": <str>, "cfi": <str>, "pos_frac": <float>}`. Missing keys → 404 `Invalid data`. Empty `cfi` **deletes** the row. Returns empty `text/plain`. |
| `/book-get-annotations/{library_id}/{which}` | GET | Returns `{ "<id>:<FMT>": {"last_read_positions": [...], "annotations_map": {...}} }`. Falls back to user `'*'` when anonymous. |
| `/book-update-annotations/{library_id}/{book_id}/{fmt}` | **POST** | Body is an annotations *map* (`{type: [annot, ...]}`); merged server-side via `merge_annotations_for_book(..., user_type='web', user=<username>)`. |
| `/book-manifest/{book_id}/{fmt}` | GET | Includes `last_read_positions` and `annotations_map` for the logged-in user — how the web reader resumes on open. |
| `/cdb/set-fields/{book_id}/{library_id}` | POST | Generic metadata write (custom columns). This is what justRead uses — see §3. |
| `/ajax/*` | GET | Read-only library/metadata browsing ([`srv/ajax.py`](https://github.com/kovidgoyal/calibre/blob/master/src/calibre/srv/ajax.py)). No progress endpoints. |

**Write is genuinely available to third parties, with one hard prerequisite: user accounts must be
enabled.** `set_last_read_position` takes `user = rd.username or None`, and `user` is `NOT NULL` in the
schema; `get_last_read_position` explicitly refuses anonymous requests. So the server must run with
authentication (`calibre-server --enable-auth`, and `--auth-mode=basic` is the friendlier mode for a
non-browser client — [calibre-server manual](https://manual.calibre-ebook.com/generated/en/calibre-server.html)),
and the iOS client must log in as a named user. Sync data is **per-username**: two different usernames
do not see each other's positions.

### How the web reader consumes it
- On open, `read_book/db.pyj` picks the row with the **highest `epoch`** across all devices for the
  current username and uses its `cfi` as the initial position.
- The manual "Sync" action (`read_book/overlay.pyj`) fetches `/book-get-annotations` and picks the
  newest position **from a device other than its own** (`d.device is not dev and d.epoch > epoch`).

Implication for the iOS client: **pick a stable, distinct `device` string** (a per-install uuid). If it
collides with a browser's device uuid it overwrites that row; if it were reused across your own
installs you'd lose the ability to distinguish them.

### Why this does not reach the desktop viewer
`gui2/viewer/ui.py: initial_cfi_for_current_book()` reads `annotations_map['last-read']`, and
`load_book_annotations()` populates that map from (a) `calibre-book-annotations.json` extracted from
the EPUB, (b) the viewer's local annotations dir, (c) the library `annotations` table for
`user_type='local', user='viewer'` and optionally `user_type='web', user=<sync_annots_user>`. It
**never queries `last_read_positions`**. Combined with the `annot_db_data` trap above (last-read is
never persisted into the `annotations` table by anyone), there is no HTTP path that changes where the
desktop viewer opens a book.

Confirmed by usage search: `last_read_positions` appears only in `srv/*`, `db/*`, the `pyj` web-reader
code, `book_list/home.pyj`, and `utils/formatter_functions.py` (a `reading_progress()` template
function for showing progress in the library grid) — not in the viewer's open path.

**Routes to the desktop viewer, if that becomes a requirement:**
1. Write `META-INF/calibre_bookmarks.txt` into the library's EPUB (needs filesystem access to the
   library, e.g. a synced folder or an agent on the Mac; there is no upload-format HTTP endpoint).
2. A calibre plugin / local helper on the Mac that watches `last_read_positions` and mirrors the row
   into the viewer's local annotation store. Positions written by an iOS client *are* visible in
   `last_read_positions`, so this bridge is small — but it is extra software on the Mac.
3. Accept the asymmetry: read the desktop's position (it *is* published to `last_read_positions` under
   `user='local'`, `device='calibre-desktop-viewer'` — but note `/book-get-last-read-position` filters
   by the requesting username, so an iOS client authenticated as `sam` will **not** see the `local`
   user's row; `/book-get-annotations` has the same per-user filter). Reading the desktop position
   over HTTP therefore likely requires either naming your server user `local` or parsing the
   downloaded EPUB's `calibre_bookmarks.txt`. **Flagging this for #6 to verify empirically** — it is
   the single most consequential detail and it hinges on how `rd.username` resolves on the live server.

---

## 3. How justRead does its "two-way sync"

Mechanism: **Calibre Content server over the LAN** (auto-discovery or manual address, HTTP Digest
auth), and the write-back is **metadata only, into custom columns** — not reading position.

From justRead's own pages:
- "The integration talks to Calibre's Content Server over your local network." Books are pulled in as
  EPUB/PDF with metadata. ([calibre-sync](https://justread.app/en/calibre-sync),
  [content-server guide](https://justread.app/en/posts/calibre-content-server-iphone-ipad))
- "Bidirectional sync means justRead also exports data back to Calibre: reading progress, star ratings,
  want-to-read flags, finished dates, session counts, and reading statistics." — where "reading
  progress" is a **percentage in a custom column**, not a resume position.
- The write-back targets ~12 optional custom columns: Progress (float), Finished, Up Next, Start /
  Finished Date, Finish Count, Days Read, Max Streak, Read Time, Average Minutes Per Day, Consistency,
  plus the built-in Rating. "The write-back is metadata only. justRead never uploads book files."
  ([Mac O'Clock writeup by the developer](https://medium.com/macoclock/sync-justread-with-calibre-d0312bcacf8b))
- "Highlights and notes don't sync to Calibre." Reading position itself "syncs between your iOS devices
  via iCloud" — i.e. **justRead's own cloud, not calibre**.

That maps cleanly onto `/cdb/set-fields`. **Conclusion: justRead is not evidence that
resume-position sync with calibre is solved — it deliberately did not do it.** The reference app
solved the problem by keeping position in its own iCloud sync and reporting only a percentage to
calibre. That is a legitimate design to copy, and it is materially cheaper than CFI round-tripping.

---

## 4. The position format: is it a real EPUB CFI?

**It is an EPUB CFI-*like* string, and it is calibre-specific in its first step.**

`last_read_positions.cfi` looks like `epubcfi(/6/4/2/1:57)`. From
[`src/pyj/read_book/view.pyj`](https://github.com/kovidgoyal/calibre/blob/master/src/pyj/read_book/view.pyj):

```python
# generating
self.currently_showing.bookpos = 'epubcfi(/{})'.format(2 * (idx + 1))   # idx = spine index
# parsing
snum, rest = cfi.partition('/')[::2]
name = book.manifest.spine[(int(snum) // 2) - 1]
```

So the **first step is `2 * (spine_index + 1)`** — a direct index into calibre's spine list. A standard
EPUB CFI's first steps address the OPF package document (`/6/4[chap01]!...`), which is *not* what this
is. `srv/render_book.py` uses the same convention when converting legacy bookmarks. The remainder
(after the first step) is a conventional CFI path resolved inside the spine document, including
`:offset` character positions and CFI escaping (`^` before `[],^();~@!` —
[`read_book/cfi.pyj`](https://github.com/kovidgoyal/calibre/blob/master/src/pyj/read_book/cfi.pyj)).

Can an independent reader map to/from it faithfully?

- **The spine step: easy.** Index into the EPUB's own spine. Trivially reversible.
- **The in-document path: risky but tractable.** The CFI is generated against the DOM the *browser*
  built from the file the Content server served, and the server re-serializes every XHTML file through
  lxml (`srv/render_book.py: transform_html`). The transforms observed are attribute-level (adding
  `data-calibre-src` to images, clearing non-stylesheet `<link>` attrs, rewriting inline CSS) and do not
  insert or remove elements — so **even-numbered (element) steps should line up** with the original
  file. **Odd-numbered (text-node) steps and `:offset` are the fragile part**: CFI odd steps count
  character-data nodes, and whitespace handling / text-node coalescing can differ between lxml's
  serialization, calibre's parser, and whatever parser the iOS reader uses. Also note the desktop
  viewer's CFIs are generated against the *viewer's* render of the original file, and the server's
  against the server's re-serialization — those two are not guaranteed byte-identical.
- **`pos_frac` is the safety net.** Every row also carries `pos_frac` (0..1 progress through the whole
  book) alongside the CFI. It is renderer-independent and always writable. Worst case, an iOS reader
  can consume `pos_frac` for "roughly where I was" and emit a best-effort CFI plus an exact `pos_frac`.
  The recent `reading_progress()` template function in calibre reads `pos_frac` too.

**Recommended posture for V1:** treat the CFI as *authored by whoever generated it* — resolve
element-level steps precisely, degrade to the nearest element when the text-offset does not resolve,
and always write both a CFI and an honest `pos_frac`. Do not attempt to make the CFI byte-exact against
calibre's renderer; make it *resolvable*.

---

## 5. What this means for the map

- The **Content server is the sync bus**, and it works in both directions for the *web reader*. Server
  must have auth enabled and the client must log in.
- **"Both sides" must be defined.** If it means "iPhone ↔ calibre web reader", it's a go. If it means
  "iPhone ↔ the calibre desktop viewer app on the Mac", the Content server alone cannot do it and the
  decision ticket needs to choose between (a) narrowing the requirement to the web reader,
  (b) filesystem/plugin access to the library on the Mac, or (c) justRead's approach — own the position
  yourself, report only `pos_frac` to calibre.
- The API is **private and undocumented**. Pin behaviour with the hands-on probes in #6 and expect to
  re-verify on calibre upgrades.

## Open questions for #6 (hands-on)

1. Does `POST /book-set-last-read-position` succeed against the live server, and with what auth mode?
2. Does an authenticated `GET /book-get-last-read-position` return the desktop viewer's row
   (`user='local'`), or only rows matching the requesting username? **This is the pivotal one.**
3. Does the desktop viewer, after the web reader moves the position, open at the new spot? (Expected:
   **no**.)
4. Is `META-INF/calibre_bookmarks.txt` actually present in EPUBs the server serves, and does it carry a
   `last-read` entry?
5. Does a CFI produced by calibre's web reader resolve in an independent EPUB renderer against the same
   file — at element granularity, and at character granularity?

## Sources

- [metadata_sqlite.sql](https://github.com/kovidgoyal/calibre/blob/master/resources/metadata_sqlite.sql)
- [src/calibre/srv/books.py](https://github.com/kovidgoyal/calibre/blob/master/src/calibre/srv/books.py)
- [src/calibre/srv/render_book.py](https://github.com/kovidgoyal/calibre/blob/master/src/calibre/srv/render_book.py)
- [src/calibre/srv/cdb.py](https://github.com/kovidgoyal/calibre/blob/master/src/calibre/srv/cdb.py)
- [src/calibre/srv/ajax.py](https://github.com/kovidgoyal/calibre/blob/master/src/calibre/srv/ajax.py)
- [src/calibre/db/backend.py](https://github.com/kovidgoyal/calibre/blob/master/src/calibre/db/backend.py)
- [src/calibre/db/cache.py](https://github.com/kovidgoyal/calibre/blob/master/src/calibre/db/cache.py)
- [src/calibre/db/annotations.py](https://github.com/kovidgoyal/calibre/blob/master/src/calibre/db/annotations.py)
- [src/calibre/db/constants.py](https://github.com/kovidgoyal/calibre/blob/master/src/calibre/db/constants.py)
- [src/calibre/gui2/viewer/integration.py](https://github.com/kovidgoyal/calibre/blob/master/src/calibre/gui2/viewer/integration.py)
- [src/calibre/gui2/viewer/annotations.py](https://github.com/kovidgoyal/calibre/blob/master/src/calibre/gui2/viewer/annotations.py)
- [src/calibre/gui2/viewer/ui.py](https://github.com/kovidgoyal/calibre/blob/master/src/calibre/gui2/viewer/ui.py)
- [src/pyj/read_book/view.pyj](https://github.com/kovidgoyal/calibre/blob/master/src/pyj/read_book/view.pyj)
- [src/pyj/read_book/ui.pyj](https://github.com/kovidgoyal/calibre/blob/master/src/pyj/read_book/ui.pyj)
- [src/pyj/read_book/db.pyj](https://github.com/kovidgoyal/calibre/blob/master/src/pyj/read_book/db.pyj)
- [src/pyj/read_book/overlay.pyj](https://github.com/kovidgoyal/calibre/blob/master/src/pyj/read_book/overlay.pyj)
- [src/pyj/read_book/cfi.pyj](https://github.com/kovidgoyal/calibre/blob/master/src/pyj/read_book/cfi.pyj)
- [src/pyj/read_book/prefs/misc.pyj](https://github.com/kovidgoyal/calibre/blob/master/src/pyj/read_book/prefs/misc.pyj)
- [calibre manual: Content server](https://manual.calibre-ebook.com/server.html)
- [calibre manual: calibre-server](https://manual.calibre-ebook.com/generated/en/calibre-server.html)
- [justRead: Calibre sync](https://justread.app/en/calibre-sync)
- [justRead: Content server on iPhone & iPad](https://justread.app/en/posts/calibre-content-server-iphone-ipad)
- [Mac O'Clock: Sync justRead with Calibre. Both Ways](https://medium.com/macoclock/sync-justread-with-calibre-d0312bcacf8b)
- [MobileRead: Calibre database: last_read_positions table](https://www.mobileread.com/forums/showthread.php?t=336212)
