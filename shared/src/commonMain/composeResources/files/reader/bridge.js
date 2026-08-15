// Kotlin <-> foliate-js bridge.
//
// Native -> web:  window.__reader.<command>(...)   (via evaluateJavaScript)
// Web -> native:  window.webkit.messageHandlers.reader.postMessage(event)
//
// Every command returns a Promise; native code fires them and listens for the
// resulting events rather than awaiting, so the boundary stays one-way per call.
// See ReaderContract.kt for the Kotlin-side definition of this protocol.

import './foliate/view.js'
import * as CFI from './foliate/epubcfi.js'
import { Overlayer } from './foliate/overlayer.js'

const view = document.getElementById('view')

const post = event => {
    try {
        // Stringify here, not in an injected shim: window.webkit.messageHandlers
        // is not writable, so overriding postMessage from a user script silently
        // fails and Kotlin receives an NSDictionary description instead of JSON.
        window.webkit.messageHandlers.reader.postMessage(JSON.stringify(event))
    } catch (e) {
        // No native host (e.g. opened in a plain browser for debugging).
        console.log('[bridge]', JSON.stringify(event))
    }
}

const fail = (where, e) =>
    post({ type: 'error', where, message: String(e && e.message || e) })

// ---------------------------------------------------------------- location

let lastCFI = null

view.addEventListener('relocate', ({ detail }) => {
    lastCFI = detail.cfi ?? null
    post({
        type: 'location',
        cfi: lastCFI,
        fraction: detail.fraction ?? null,
        tocLabel: detail.tocItem?.label ?? null,
        pageItem: detail.pageItem?.label ?? null,
    })
})

const currentSelectionRange = () => {
    for (const c of view.renderer?.getContents?.() ?? []) {
        const sel = c.doc?.defaultView?.getSelection?.()
        if (sel && sel.rangeCount && !sel.isCollapsed) return sel.getRangeAt(0)
    }
    return null
}

view.addEventListener('load', ({ detail }) => {
    // Suppress in-book link navigation; the shell owns navigation.
    const doc = detail.doc
    if (!doc) return
    doc.addEventListener('click', e => {
        const a = e.target.closest?.('a[href]')
        if (a) e.preventDefault()
    }, true)

    // Report selection so the shell can offer "Read from here" (#13). The bar
    // is ours rather than an item in the iOS callout: that would need UIMenu
    // interop with no clean Kotlin/Native binding, and no Android equivalent.
    doc.addEventListener('selectionchange', () => {
        const sel = doc.defaultView?.getSelection?.()
        const text = sel && !sel.isCollapsed ? sel.toString().trim() : ''
        post({ type: 'selection', text: text.slice(0, 120), active: text.length > 0 })
    })
})

// ---------------------------------------------------------------- TTS

// foliate's TTS yields SSML per block, with <mark name="N"/> per segment
// (segment = sentence when granularity is 'sentence'). We flatten that into
// plain-text chunks, because AVSpeechSynthesizer takes strings, not SSML.
let ttsReady = false

// Key under which the read-aloud highlight is registered with the overlayer.
// Reusing one key means painting the next chunk implicitly replaces the last.
const TTS_MARK = 'tts-current'

let highlightColor = 'rgba(255, 214, 0, .45)'

// foliate's DEFAULT tts highlight callback is `scrollToAnchor(range, true)`,
// which only SCROLLS -- the flag merely tags the relocation reason. Nothing is
// ever painted. Drawing the chunk is the entire point of the feature, so we
// pass our own callback into initTTS and drive the Overlayer directly.
//
// Via the overlayer rather than view.addAnnotation because we already hold a
// live Range; addAnnotation takes a CFI and would resolve it back into the
// range we started from.
const paintHighlight = range => {
    try {
        const contents = view.renderer?.getContents?.() ?? []
        for (const c of contents) c.overlayer?.remove(TTS_MARK)
        const target = contents.find(c => c.overlayer)
        if (target) {
            target.overlayer.add(TTS_MARK, range, Overlayer.highlight, { color: highlightColor })
        }
        // `false`, not `true`. The flag means "reason: selection", and foliate's
        // paginator responds by actually SELECTING the range -- which is how
        // upstream's TTS highlight worked at all, since it had nothing else.
        // We paint our own overlay, so that selection is pure interference: it
        // fires selectionchange on every chunk and pops the "Read from here"
        // bar over the sentence currently being read.
        view.renderer.scrollToAnchor(range, false)
    } catch (e) { fail('highlight', e) }
}

const clearHighlight = () => {
    try {
        for (const c of view.renderer?.getContents?.() ?? []) c.overlayer?.remove(TTS_MARK)
    } catch (e) { /* nothing was painted */ }
}

const ssmlToChunks = ssml => {
    const doc = new DOMParser().parseFromString(String(ssml), 'application/xml')
    const root = doc.documentElement

    // Walk in document order, opening a new chunk at each <mark> and
    // accumulating text nodes into whichever chunk is currently open.
    const walker = doc.createTreeWalker(root, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT)
    const chunks = []
    let current = null
    let node
    while ((node = walker.nextNode())) {
        if (node.nodeType === Node.ELEMENT_NODE) {
            if (node.localName === 'mark') {
                current = { mark: node.getAttribute('name'), text: '' }
                chunks.push(current)
            }
            continue
        }
        if (current) current.text += node.textContent
    }

    if (!chunks.length) {
        const text = root.textContent.trim()
        return text ? [{ mark: null, text }] : []
    }
    return chunks
        .map(c => ({ mark: c.mark, text: c.text.trim() }))
        .filter(c => c.text)
}

// ---------------------------------------------------------------- commands

window.__reader = {
    // `initialLocation` is applied as part of opening: view.open() only parses,
    // view.init() is what actually renders, and it takes the start position. So
    // resuming at a calibre position is one call, not open-then-seek (which
    // would render page 1 first and visibly jump).
    // `useCalibreBookmark` reads the desktop viewer's position out of
    // META-INF/calibre_bookmarks.txt inside the EPUB itself. foliate already
    // implements the read (`book.getCalibreBookmarks()`, base64+JSON) and
    // nothing was calling it -- so the whole Mac -> iPhone position path costs
    // no extra request and no ZIP handling on the Kotlin side.
    async open(url, initialLocation, isCalibre, useCalibreBookmark) {
        try {
            await view.open(url)
            const book = view.book

            let calibrePos = null
            let calibreCfi = null
            // -1 behind, 0 same, 1 ahead of where we actually opened.
            let calibreRelative = null
            if (useCalibreBookmark) {
                try {
                    const marks = await book?.getCalibreBookmarks?.()
                    const lastRead = marks?.find?.(m => m.type === 'last-read')
                    if (lastRead?.pos_type === 'epubcfi' && lastRead.pos) {
                        calibrePos = lastRead.pos
                    }
                } catch (e) {
                    // No bookmark file, or unreadable. Not an error -- most
                    // books have never been opened on the desktop.
                }
            }

            if (calibrePos) {
                try { calibreCfi = CFI.fromCalibrePos(calibrePos) } catch (e) { calibreCfi = null }
            }

            const source = initialLocation ?? calibrePos
            const fromCalibre = initialLocation ? isCalibre : calibrePos != null
            const last = source
                ? (fromCalibre ? CFI.fromCalibrePos(source) : source)
                : null

            // NOTE: calibre's bookmark carries no pos_frac -- that lives only in
            // metadata.db, which #6 proved is unreachable per-user over HTTP. So
            // "Mac was at 60%" cannot be computed from what we have. Comparing
            // the two CFIs answers the question that actually matters -- is the
            // Mac further on than the phone -- and CFI.compare is exact where a
            // cross-renderer fraction would only ever be approximate.
            if (calibreCfi && initialLocation) {
                try {
                    const rel = CFI.compare(calibreCfi, initialLocation)
                    calibreRelative = rel > 0 ? 1 : rel < 0 ? -1 : 0
                } catch (e) { calibreRelative = null }
            }

            await view.init(last ? { lastLocation: last } : { showTextStart: true })
            post({
                type: 'opened',
                title: book?.metadata?.title ?? null,
                author: (() => {
                    const a = book?.metadata?.author
                    return Array.isArray(a) ? a.map(x => x?.name ?? x).join(', ') : (a?.name ?? a ?? null)
                })(),
                sectionCount: book?.sections?.length ?? 0,
                calibrePos,
                calibreCfi,
                calibreRelative,
                resolvedCfi: last,
                diag: `vw=${window.innerWidth}x${window.innerHeight} ` +
                      `renderer=${!!view.renderer} ` +
                      `contents=${view.renderer?.getContents?.()?.length ?? -1}`,
            })
        } catch (e) { fail('open', e) }
    },

    // Accepts either a foliate CFI or a raw calibre one; calibre's flat
    // spine-step form is converted via foliate's purpose-built helper.
    async goTo(cfiOrCalibre, isCalibre) {
        try {
            const cfi = isCalibre ? CFI.fromCalibrePos(cfiOrCalibre) : cfiOrCalibre
            await view.goTo(cfi)
        } catch (e) { fail('goTo', e) }
    },

    async goToFraction(fraction) {
        try { await view.goToFraction(fraction) } catch (e) { fail('goToFraction', e) }
    },

    async prev() { try { await view.prev() } catch (e) { fail('prev', e) } },
    async next() { try { await view.next() } catch (e) { fail('next', e) } },

    // theme: { fontFamily, fontSize (em), lineHeight, justify, hyphenate, fg, bg, marginPx }
    setTheme(theme) {
        try {
            const t = typeof theme === 'string' ? JSON.parse(theme) : theme
            document.body.style.setProperty('--reader-bg', t.bg)
            if (t.highlight) highlightColor = t.highlight
            view.renderer.setStyles(`
                @namespace epub "http://www.idpf.org/2007/ops";

                html {
                    color-scheme: ${t.dark ? 'dark' : 'light'};
                    font-size: ${t.fontSize}em;
                }
                html, body {
                    color: ${t.fg} !important;
                    background: ${t.bg} !important;
                }
                a, a:link, a:visited {
                    color: ${t.link} !important;
                }

                /* The reader's font wins for body text and headings (#10).
                   The :not() chain carries the exemptions that decision named:
                     - code keeps a monospace face, or it stops lining up;
                     - text tagged as another language keeps the publisher's
                       font, which may be the only one with the glyphs;
                     - verse keeps its own, because the typography is the point.
                   Exempting by NOT matching, rather than overriding and then
                   trying to undo it: there is no way to restore a value we
                   never knew. */
                /* NOTE: no whitespace between the :is() and the :not()s. A
                   space is a descendant combinator, so breaking this across
                   lines silently changes the meaning to "a non-code element
                   INSIDE a paragraph" -- which matches inline children but not
                   the paragraph itself, so the font never applies. */
                :is(p, li, blockquote, dd, div, h1, h2, h3, h4, h5, h6):not(:is(pre, code, kbd, samp, pre *, code *)):not(:is([lang]:not([lang|="en"]), [lang]:not([lang|="en"]) *)):not(:is([epub|type~="z3998:verse"], [epub|type~="z3998:verse"] *)) {
                    font-family: ${t.fontFamily} !important;
                }

                p, li, blockquote, dd, div {
                    line-height: ${t.lineHeight};
                    text-align: ${t.justify ? 'justify' : 'start'};
                    -webkit-hyphens: ${t.hyphenate ? 'auto' : 'manual'};
                    hyphens: ${t.hyphenate ? 'auto' : 'manual'};
                }

                pre, code, kbd, samp {
                    font-family: ui-monospace, Menlo, monospace !important;
                }

                /* Dark themes only. A white-background diagram on a near-black
                   page is a flashbang at exactly the moment you chose dark
                   mode. Dimming rather than inverting: inversion fixes diagrams
                   and ruins photographs, and EPUBs do not mark which is which. */
                ${t.dark ? 'img, svg, picture, image { filter: brightness(.8); }' : ''}
            `)
            if (t.marginPx != null) view.renderer.setAttribute('margin', `${t.marginPx}px`)
            post({ type: 'themeApplied' })
        } catch (e) { fail('setTheme', e) }
    },

    // spec: 'sentence' | 'word' | { custom: '<regex source>' }
    //
    // The custom form is the calibre-style delimiter and is served by a local
    // patch to foliate's tts.js; see the LOCAL PATCH block there.
    async ttsInit(spec) {
        try {
            const granularity = spec || 'sentence'
            // initTTS is a no-op when TTS is already bound to this document, so
            // changing the delimiter mid-page would otherwise be silently
            // ignored. Dropping the instance forces re-segmentation.
            view.tts = null
            await view.initTTS(granularity, paintHighlight)
            ttsReady = true
            post({
                type: 'ttsReady',
                granularity: typeof granularity === 'string'
                    ? granularity
                    : `custom:${granularity.custom}`,
            })
        } catch (e) { fail('ttsInit', e) }
    },

    // Emits the next block's chunks. Native side speaks them one at a time,
    // calling ttsHighlight(mark) immediately before each so the upcoming
    // sentence is highlighted during the pre-utterance pause.
    ttsNext(fromStart) {
        try {
            if (!ttsReady) return fail('ttsNext', 'TTS not initialised')
            const ssml = fromStart ? view.tts.start() : view.tts.next()
            if (!ssml) return post({ type: 'ttsChunks', chunks: [], done: true })
            post({ type: 'ttsChunks', chunks: ssmlToChunks(ssml), done: false })
        } catch (e) { fail('ttsNext', e) }
    },

    ttsPrev() {
        try {
            const ssml = view.tts.prev()
            if (!ssml) return post({ type: 'ttsChunks', chunks: [], done: true })
            post({ type: 'ttsChunks', chunks: ssmlToChunks(ssml), done: false })
        } catch (e) { fail('ttsPrev', e) }
    },

    // Paints the highlight for a chunk. Anchored to a DOM Range, so it
    // survives repagination and re-theming.
    ttsHighlight(mark) {
        try { view.tts.setMark(mark) } catch (e) { fail('ttsHighlight', e) }
    },

    // Called when reading stops, so the last spoken chunk does not stay lit.
    ttsClearHighlight() { clearHighlight() },

    // Starts TTS from the currently visible position rather than block 0.
    // Start where the reader is looking (#13), not at the top of the section.
    //
    // The no-selection fallback is `view.lastLocation.range` -- the visible
    // range -- NOT `tts.start()`. That was the originally reported bug: reading
    // always restarted from the beginning of the section however far in you
    // were. A selection, when present, wins: that is "Read from here".
    ttsFromCurrent() {
        try {
            const range = currentSelectionRange() ?? view.lastLocation?.range ?? null
            const ssml = range ? view.tts.from(range) : view.tts.start()
            post({ type: 'ttsChunks', chunks: ssmlToChunks(ssml), done: !ssml })
        } catch (e) { fail('ttsFromCurrent', e) }
    },

    clearSelection() {
        try {
            for (const c of view.renderer?.getContents?.() ?? []) {
                c.doc?.defaultView?.getSelection?.()?.removeAllRanges?.()
            }
            post({ type: 'selection', text: '', active: false })
        } catch (e) { /* nothing selected */ }
    },

    // The table of contents, flattened with a depth so the shell can indent it
    // without modelling a tree.
    toc() {
        try {
            const flat = []
            const walk = (items, depth) => {
                for (const item of items ?? []) {
                    if (item?.label) {
                        flat.push({
                            label: String(item.label).trim(),
                            href: item.href ?? null,
                            depth,
                        })
                    }
                    if (item?.subitems) walk(item.subitems, depth + 1)
                }
            }
            walk(view.book?.toc, 0)
            post({ type: 'toc', entries: flat })
        } catch (e) { fail('toc', e) }
    },

    // Full-book search. Results stream in from an async generator, so they are
    // collected and posted once rather than dribbled across the bridge --
    // capped, because a common word in a 7 MB book yields thousands and the
    // shell only ever shows a list.
    async search(query) {
        try {
            if (!query || !query.trim()) {
                view.clearSearch()
                post({ type: 'searchResults', query: '', results: [], truncated: false })
                return
            }
            const results = []
            let truncated = false
            for await (const result of view.search({ query: query.trim() })) {
                if (result === 'done') break
                const items = result.subitems ?? (result.cfi ? [result] : [])
                for (const item of items) {
                    if (results.length >= 60) { truncated = true; break }
                    const ex = item.excerpt ?? {}
                    results.push({
                        cfi: item.cfi,
                        label: result.label ?? '',
                        excerpt: `${ex.pre ?? ''}${ex.match ?? ''}${ex.post ?? ''}`.trim(),
                    })
                }
                if (truncated) break
            }
            post({ type: 'searchResults', query, results, truncated })
        } catch (e) { fail('search', e) }
    },

    clearSearch() {
        try { view.clearSearch() } catch (e) { /* nothing to clear */ }
    },

    async goToHref(href) {
        try { await view.goTo(href) } catch (e) { fail('goToHref', e) }
    },

    currentCFI() { return lastCFI },
}

post({ type: 'ready' })
