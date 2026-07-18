(function () {
  if (window.__tvroomDownloaderHooked) return;
  window.__tvroomDownloaderHooked = true;

  function send(value) {
    try {
      if (window.TVRoomBridge && window.TVRoomBridge.postMessage) {
        window.TVRoomBridge.postMessage(JSON.stringify(value));
      }
    } catch (_) {}
  }

  function hex(value) {
    try {
      var bytes;
      if (value instanceof ArrayBuffer) bytes = new Uint8Array(value);
      else if (ArrayBuffer.isView(value)) bytes = new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
      else if (Array.isArray(value)) bytes = new Uint8Array(value);
      else if (typeof value === 'string' && /^[0-9a-f]{32,}$/i.test(value.replace(/^0x/i, ''))) {
        return value.replace(/^0x/i, '').toLowerCase();
      } else return '';
      return Array.from(bytes).map(function (b) { return b.toString(16).padStart(2, '0'); }).join('');
    } catch (_) { return ''; }
  }

  var capturedKey = '';
  var capturedIv = '';
  function reportCrypto() {
    if (capturedKey || capturedIv) send({ type: 'crypto', key: capturedKey, iv: capturedIv });
  }
  function rememberUrl(value) {
    try {
      var url = typeof value === 'string' ? value : value && value.url;
      if (url && /^https?:/i.test(url) && /(m3u8|segment_list|\.ts(?:[?#]|$)|\.m4s(?:[?#]|$)|\.key(?:[?#]|$)|\/key(?:[/?#]|$))/i.test(url)) {
        var referer = '';
        try { if (/^https?:/i.test(location.href)) referer = location.href; } catch (_) {}
        send({ type: 'url', url: url, referer: referer });
      }
    } catch (_) {}
  }

  try {
    var subtle = window.crypto && window.crypto.subtle;
    if (subtle) {
      var originalImportKey = subtle.importKey.bind(subtle);
      var originalDecrypt = subtle.decrypt.bind(subtle);
      subtle.importKey = async function () {
        var raw = hex(arguments[1]);
        var algorithm = arguments[2];
        if (raw.length >= 32 && String((algorithm && algorithm.name) || algorithm).toUpperCase().indexOf('AES') >= 0) {
          capturedKey = raw.substring(0, 32); reportCrypto();
        }
        return originalImportKey.apply(null, arguments);
      };
      subtle.decrypt = async function (algorithm, key, data) {
        var nextIv = algorithm && hex(algorithm.iv);
        if (nextIv.length >= 32) capturedIv = nextIv.substring(0, 32);
        try {
          var exported = await subtle.exportKey('raw', key);
          var nextKey = hex(exported);
          if (nextKey.length >= 32) capturedKey = nextKey.substring(0, 32);
        } catch (_) {}
        reportCrypto();
        return originalDecrypt(algorithm, key, data);
      };
    }
  } catch (_) {}

  try {
    var originalFetch = window.fetch;
    if (originalFetch) window.fetch = function () {
      rememberUrl(arguments[0]);
      return originalFetch.apply(this, arguments).then(function (response) {
        rememberUrl(response.url);
        try {
          var clone = response.clone();
          var type = (clone.headers.get('content-type') || '').toLowerCase();
          if (type.indexOf('json') >= 0 || type.indexOf('text') >= 0 || /m3u8/i.test(response.url)) {
            clone.text().then(function (body) {
              var matches = body.match(/https?:\/\/[^"'\s<>]+(?:m3u8|segment_list)[^"'\s<>]*/ig) || [];
              matches.forEach(rememberUrl);
            }).catch(function () {});
          }
        } catch (_) {}
        return response;
      });
    };
  } catch (_) {}

  try {
    var XHR = window.XMLHttpRequest;
    var originalOpen = XHR.prototype.open;
    XHR.prototype.open = function () { rememberUrl(arguments[1]); return originalOpen.apply(this, arguments); };
  } catch (_) {}

  function scan() {
    try {
      performance.getEntriesByType('resource').forEach(function (entry) { rememberUrl(entry.name); });
      document.querySelectorAll('video,source,iframe').forEach(function (node) {
        rememberUrl(node.currentSrc || node.src);
      });
      if (window.top === window) {
        var titleNode = document.querySelector('h1,.video-title,[class*="video-title"]');
        var ogTitle = document.querySelector('meta[property="og:title"]');
        var ogImage = document.querySelector('meta[property="og:image"]');
        send({ type: 'meta', title: (titleNode && titleNode.textContent) || (ogTitle && ogTitle.content) || document.title || '',
          thumbnail: (ogImage && ogImage.content) || '' });
      }
    } catch (_) {}
  }
  setInterval(scan, 1200);
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', scan);
  else scan();
})();
