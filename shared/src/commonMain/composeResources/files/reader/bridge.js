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

const view = document.getElementById('view')

const post = event => {
    try {
        window.webkit.messageHandlers.reader.postMessage(event)
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

const ssmlToChunks = ssml => {
    const doc = new DOMParser().parseFromString(String(ssml), 'application/xml')
    const marks = [...doc.querySelectorAll('mark')]
    if (!marks.length) {
        const text = doc.documentElement.textContent.trim()
        return text ? [{ mark: null, text }] : []
    }
    // Text belonging to a mark is everything between it and the next mark.
    return marks.map((m, i) => {
        const next = marks[i + 1] ?? null
        let text = ''
        const walker = doc.createTreeWalker(doc.documentElement, NodeFilter.SHOW_TEXT)
        let started = false
        let node
        while ((node = walker.nextNode())) {
            const posM = m.compareDocumentPosition(node)
            if (!started && (posM & Node.DOCUMENT_POSITION_FOLLOWING)) started = true
            if (!started) continue
            if (next && (next.compareDocumentPosition(node) & Node.DOCUMENT_POSITION_PRECEDING)) break
            text += node.textContent
        }
        return { mark: m.getAttribute('name'), text: text.trim() }
    }).filter(c => c.text)
}

// ---------------------------------------------------------------- commands

window.__reader = {
    async open(url) {
        try {
            await view.open(url)
            const book = view.book
            post({
                type: 'opened',
                title: book?.metadata?.title ?? null,
                author: (() => {
                    const a = book?.metadata?.author
                    return Array.isArray(a) ? a.map(x => x?.name ?? x).join(', ') : (a?.name ?? a ?? null)
                })(),
                sectionCount: book?.sections?.length ?? 0,
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

    // granularity: 'sentence' (delimiter-style chunking) or 'word'
    async ttsInit(granularity) {
        try {
            await view.initTTS(granularity || 'sentence')
            ttsReady = true
            post({ type: 'ttsReady', granularity: granularity || 'sentence' })
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
