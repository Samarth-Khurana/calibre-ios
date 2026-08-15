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

view.addEventListener('load', ({ detail }) => {
    // Suppress in-book link navigation; the shell owns navigation.
    const doc = detail.doc
    if (!doc) return
    doc.addEventListener('click', e => {
        const a = e.target.closest?.('a[href]')
        if (a) e.preventDefault()
    }, true)
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
        // Still scroll, so a chunk below the fold pages into view.
        view.renderer.scrollToAnchor(range, true)
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
            if (!initialLocation && useCalibreBookmark) {
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

            const source = initialLocation ?? calibrePos
            const fromCalibre = initialLocation ? isCalibre : calibrePos != null
            const last = source
                ? (fromCalibre ? CFI.fromCalibrePos(source) : source)
                : null

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
                p, li, blockquote, dd, div {
                    font-family: ${t.fontFamily};
                    line-height: ${t.lineHeight};
                    text-align: ${t.justify ? 'justify' : 'start'};
                    -webkit-hyphens: ${t.hyphenate ? 'auto' : 'manual'};
                    hyphens: ${t.hyphenate ? 'auto' : 'manual'};
                }
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
    ttsFromCurrent() {
        try {
            const sel = view.renderer?.getContents?.()?.[0]?.doc?.getSelection?.()
            const range = sel && sel.rangeCount ? sel.getRangeAt(0) : null
            const ssml = range ? view.tts.from(range) : view.tts.start()
            post({ type: 'ttsChunks', chunks: ssmlToChunks(ssml), done: !ssml })
        } catch (e) { fail('ttsFromCurrent', e) }
    },

    currentCFI() { return lastCFI },
}

post({ type: 'ready' })
