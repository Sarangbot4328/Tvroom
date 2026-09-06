(function () {
  // A work owns /video/<work>/<episode>; labels may contain dates, or just "본편".
  function parts(url) {
    try { return decodeURIComponent(url.pathname).split('/').filter(Boolean); }
    catch (_) { return []; }
  }
  var page = new URL(location.href);
  var current = parts(page);
  if (current[0] !== 'video' || current.length < 2) return JSON.stringify([]);
  var found = new Map();
  document.querySelectorAll('.episode-list a[href],a.episode-item[href]').forEach(function (a) {
    var url;
    try { url = new URL(a.href, page.href); } catch (_) { return; }
    var path = parts(url);
    if (url.origin !== page.origin || path.length !== 3
        || path[0] !== 'video' || path[1] !== current[1]) return;
    var label = (a.textContent || a.title || path[2]).trim().replace(/\s+/g, ' ');
    var match = label.match(/(?:제\s*)?(\d+)\s*(?:회차|화|회)|(?:episode|ep)\s*\.?\s*(\d+)/i);
    url.hash = ''; url.search = '';
    var key = path.join('/');
    if (!found.has(key)) found.set(key, {
      url: url.href, title: label, number: match ? Number(match[1] || match[2]) : null,
      order: found.size
    });
  });
  return JSON.stringify(Array.from(found.values()).sort(function(a, b) {
    // Numbered episodes first in numeric order; named specials keep their displayed order.
    if (a.number !== null && b.number !== null) return a.number - b.number || a.order - b.order;
    if (a.number !== null) return -1;
    if (b.number !== null) return 1;
    return a.order - b.order;
  }));
})();
