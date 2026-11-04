/*
 * VideoCatcher - video detection script.
 *
 * The app injects this script into every frame (top page and iframes) at
 * document start, before any page script runs. It reports to the app, through
 * the `VideoCatcherBridge` JavaScript interface:
 *   - <video> elements that load or start playing (play / playing / loadedmetadata),
 *   - media-like network requests made with fetch() / XMLHttpRequest
 *     (HLS .m3u8, DASH .mpd and direct video files), which is how streaming
 *     players (hls.js, dash.js, ...) load the video behind a blob: URL.
 *
 * The script only observes. It never changes how the page behaves: every
 * wrapped function calls the original and returns its result untouched.
 */
(function () {
  'use strict';

  var FLAG = '__videoCatcherInstalled__';
  if (window[FLAG]) return;
  try {
    Object.defineProperty(window, FLAG, { value: true, enumerable: false, configurable: false, writable: false });
  } catch (e) {
    window[FLAG] = true;
  }

  // The app's Java object (WebView.addJavascriptInterface). It normally exists
  // before this script runs; if not, it is looked up again when needed.
  var bridge = null;
  function getBridge() {
    if (!bridge) {
      try {
        var candidate = window.VideoCatcherBridge;
        if (candidate && typeof candidate.postMessage === 'function') bridge = candidate;
      } catch (e) { /* ignore */ }
    }
    return bridge;
  }
  getBridge();

  // Keep private references: page scripts may replace these globals later.
  var stringify = JSON.stringify;
  var now = Date.now;
  var URLCtor = window.URL;
  var setTimer = window.setTimeout;

  var isTop = (function () {
    try { return window.top === window; } catch (e) { return false; }
  })();

  function post(payload) {
    var target = getBridge();
    if (!target) return;
    try {
      payload.frameUrl = String(location.href);
      payload.isTop = isTop;
      payload.title = String(document.title || '');
      target.postMessage(stringify(payload));
    } catch (e) { /* never break the page */ }
  }

  function absolute(url) {
    if (url === undefined || url === null || url === '') return '';
    try {
      return new URLCtor(String(url), document.baseURI || location.href).href;
    } catch (e) {
      return '';
    }
  }

  // ---------------------------------------------------------------------------
  // URL / MIME classification (mirrors MediaUrlClassifier.kt)
  // ---------------------------------------------------------------------------
  var DIRECT_EXT = /^(mp4|m4v|webm|mov|mkv|3gp|ogv|flv|avi|wmv|mpeg|mpg)$/;
  var HLS_MIME = /^(application\/(vnd\.apple\.mpegurl|x-mpegurl|mpegurl)|audio\/(x-)?mpegurl)$/;
  var DASH_MIME = /^application\/dash\+xml$/;
  var SEGMENT_MIME = /^video\/(mp2t|iso\.segment|vnd\.dlna\.mpeg-tts)$/;

  function extensionOf(href) {
    try {
      var path = new URLCtor(href).pathname;
      var last = path.substring(path.lastIndexOf('/') + 1);
      var dot = last.lastIndexOf('.');
      return dot > 0 ? last.substring(dot + 1).toLowerCase() : '';
    } catch (e) {
      return '';
    }
  }

  function classify(href, mime) {
    if (!/^https?:/i.test(href)) return null;
    var type = String(mime || '').split(';')[0].trim().toLowerCase();
    if (type) {
      if (HLS_MIME.test(type)) return 'hls';
      if (DASH_MIME.test(type)) return 'dash';
      if (type.indexOf('video/') === 0 && !SEGMENT_MIME.test(type)) return 'direct';
    }
    var ext = extensionOf(href);
    if (ext === 'm3u8') return 'hls';
    if (ext === 'mpd') return 'dash';
    if (DIRECT_EXT.test(ext)) return 'direct';
    return null;
  }

  // ---------------------------------------------------------------------------
  // Network requests (fetch / XMLHttpRequest)
  // ---------------------------------------------------------------------------
  var seenNetwork = Object.create(null);
  var seenCount = 0;
  var recentManifests = [];

  function noteNetwork(rawUrl, mime, via) {
    var href = absolute(rawUrl);
    if (!href) return;
    var kind = classify(href, mime);
    if (!kind) return;
    var key = kind + '|' + href;
    if (seenNetwork[key]) return;
    if (++seenCount > 2000) {
      seenNetwork = Object.create(null);
      seenCount = 1;
    }
    seenNetwork[key] = 1;
    if (kind === 'hls' || kind === 'dash') {
      recentManifests.push({ url: href, time: now() });
      if (recentManifests.length > 20) recentManifests.shift();
    }
    post({ type: 'network', url: href, kind: kind, mime: String(mime || ''), via: via });
  }

  var originalFetch = window.fetch;
  if (typeof originalFetch === 'function') {
    var patchedFetch = function fetch(input, init) {
      var requestUrl = '';
      try {
        requestUrl = typeof input === 'string' ? input : (input && input.url) ? input.url : String(input);
        noteNetwork(requestUrl, '', 'fetch');
      } catch (e) { /* ignore */ }
      var promise = originalFetch.apply(this, arguments);
      try {
        promise.then(function (response) {
          try {
            var type = response.headers.get('content-type');
            if (type) noteNetwork(response.url || requestUrl, type, 'fetch');
          } catch (e) { /* ignore */ }
        }, function () { /* the page handles its own errors */ });
      } catch (e) { /* ignore */ }
      return promise;
    };
    try {
      patchedFetch.toString = function () { return originalFetch.toString(); };
    } catch (e) { /* ignore */ }
    window.fetch = patchedFetch;
  }

  var xhrProto = window.XMLHttpRequest && window.XMLHttpRequest.prototype;
  if (xhrProto && typeof xhrProto.open === 'function' && typeof xhrProto.send === 'function') {
    var xhrUrls = new WeakMap();
    var xhrWatched = new WeakSet();
    var originalOpen = xhrProto.open;
    var originalSend = xhrProto.send;

    xhrProto.open = function open(method, url) {
      try {
        var href = absolute(url);
        xhrUrls.set(this, href);
        noteNetwork(href, '', 'xhr');
      } catch (e) { /* ignore */ }
      return originalOpen.apply(this, arguments);
    };

    xhrProto.send = function send() {
      var xhr = this;
      try {
        if (!xhrWatched.has(xhr)) {
          xhrWatched.add(xhr);
          xhr.addEventListener('readystatechange', function () {
            if (xhr.readyState !== 2) return; // HEADERS_RECEIVED
            try {
              var type = xhr.getResponseHeader('content-type');
              if (type) noteNetwork(xhr.responseURL || xhrUrls.get(xhr) || '', type, 'xhr');
            } catch (e) { /* ignore */ }
          });
        }
      } catch (e) { /* ignore */ }
      return originalSend.apply(this, arguments);
    };
  }

  // ---------------------------------------------------------------------------
  // <video> elements
  // ---------------------------------------------------------------------------
  var lastReport = new WeakMap();
  var drmElements = new WeakSet();

  function hasMediaKeys(el) {
    try { return !!el.mediaKeys; } catch (e) { return false; }
  }

  function sourcesOf(el) {
    var list = [];
    try {
      var nodes = el.getElementsByTagName('source');
      for (var i = 0; i < nodes.length && i < 10; i++) {
        var url = absolute(nodes[i].getAttribute('src'));
        if (url) list.push({ url: url, mime: String(nodes[i].type || '') });
      }
    } catch (e) { /* ignore */ }
    return list;
  }

  function reportElement(el, category) {
    if (!el || el.tagName !== 'VIDEO') return;

    var src = '';
    try { src = el.currentSrc || el.src || ''; } catch (e) { /* ignore */ }
    var sources = sourcesOf(el);
    if (!src && sources.length) src = sources[0].url;

    var stream = null;
    try { stream = el.srcObject || null; } catch (e) { /* ignore */ }
    if (stream && typeof MediaStream !== 'undefined' && stream instanceof MediaStream) return; // camera / WebRTC
    if (!src && !stream) return;

    // 'play' and 'playing' arrive together: report each category once per source.
    var state = lastReport.get(el);
    if (!state) {
      state = {};
      lastReport.set(el, state);
    }
    var previous = state[category];
    var time = now();
    if (previous && previous.src === src && time - previous.time < 1500) return;
    state[category] = { src: src, time: time };

    var isBlob = !!stream || /^(blob|mediasource):/i.test(src);
    var manifests = [];
    if (isBlob) {
      for (var i = Math.max(0, recentManifests.length - 5); i < recentManifests.length; i++) {
        manifests.push(recentManifests[i].url);
      }
    }
    var duration = 0;
    try {
      duration = el.duration === Infinity ? -1 : (isFinite(el.duration) ? el.duration : 0);
    } catch (e) { /* ignore */ }

    post({
      type: 'media',
      event: category,
      url: isBlob ? '' : absolute(src),
      blob: isBlob,
      sources: sources,
      width: el.videoWidth || 0,
      height: el.videoHeight || 0,
      duration: duration,
      poster: el.poster ? absolute(el.poster) : '',
      drm: drmElements.has(el) || hasMediaKeys(el),
      manifests: manifests,
      muted: !!el.muted
    });
  }

  function onMediaEvent(event) {
    var el = event.target;
    if (!el || el.tagName !== 'VIDEO') return;
    if (event.type === 'encrypted') {
      try { drmElements.add(el); } catch (e) { /* ignore */ }
    }
    var category = (event.type === 'play' || event.type === 'playing') ? 'play' : 'meta';
    reportElement(el, category);
  }

  // Media events do not bubble, but capture-phase listeners still see them.
  var MEDIA_EVENTS = ['loadedmetadata', 'play', 'playing', 'encrypted'];
  function watch(root) {
    for (var i = 0; i < MEDIA_EVENTS.length; i++) {
      try { root.addEventListener(MEDIA_EVENTS[i], onMediaEvent, true); } catch (e) { /* ignore */ }
    }
  }
  watch(document);

  // Events inside shadow trees do not reach the document, so watch each new shadow root too.
  var elementProto = window.Element && window.Element.prototype;
  if (elementProto && typeof elementProto.attachShadow === 'function') {
    var originalAttachShadow = elementProto.attachShadow;
    elementProto.attachShadow = function attachShadow() {
      var root = originalAttachShadow.apply(this, arguments);
      try { watch(root); } catch (e) { /* ignore */ }
      return root;
    };
  }

  // Programmatic play() also catches videos that are not attached to the document.
  var mediaProto = window.HTMLMediaElement && window.HTMLMediaElement.prototype;
  if (mediaProto && typeof mediaProto.play === 'function') {
    var originalPlay = mediaProto.play;
    mediaProto.play = function play() {
      var el = this;
      var result = originalPlay.apply(this, arguments);
      try {
        if (el && el.tagName === 'VIDEO') {
          if (result && typeof result.then === 'function') {
            result.then(function () { reportElement(el, 'play'); }, function () { /* blocked */ });
          } else {
            setTimer(function () { if (!el.paused) reportElement(el, 'play'); }, 0);
          }
        }
      } catch (e) { /* ignore */ }
      return result;
    };
  }

  // When injected late (old WebView fallback), report videos that already exist.
  function scanExisting() {
    try {
      var videos = document.getElementsByTagName('video');
      for (var i = 0; i < videos.length; i++) {
        var v = videos[i];
        if (!v.paused) reportElement(v, 'play');
        else if (v.readyState >= 1) reportElement(v, 'meta');
      }
    } catch (e) { /* ignore */ }
  }
  scanExisting();
})();
