(function () {
  // Only numbered links belonging to the current work or an explicit episode list.
  var heading = document.querySelector('h1,.video-title,[class*="video-title"]');
  var episode = /(?:제\s*)?(\d+)\s*(?:회차|화|회)|(?:episode|ep)\s*\.?\s*(\d+)/i;
  function series(text) {
    return text.replace(episode, '').replace(/다시\s*보기|티비룸/g, '')
      .replace(/[\s|:·\-–—()[\]]/g, '').toLowerCase();
  }
  var work = series(heading ? heading.textContent : '');
  var found = new Map();
  document.querySelectorAll('a[href]').forEach(function (a) {
    var url;
    try { url = new URL(a.href, location.href); } catch (_) { return; }
    if (url.origin !== location.origin || !/\/video\//i.test(url.pathname)) return;
    var label = (a.textContent || a.title || '').trim().replace(/\s+/g, ' ');
    var match = label.match(episode);
    if (!match) return;
    var scope = a.closest('[class*="episode"],[id*="episode"],[class*="season"],[id*="season"]');
    var key = series(label);
    if (!(work && key === work) && !(scope && (!key || key === work))) return;
    url.hash = ''; url.search = '';
    found.set(url.href, {url:url.href, title:label, number:Number(match[1] || match[2])});
  });
  return JSON.stringify(Array.from(found.values()).sort(function(a,b){return a.number-b.number;}));
})();
