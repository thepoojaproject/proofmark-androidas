/**
 * Injected once per page load (see MainActivity.onPageFinished).
 *
 * This does not alter the web app's own logic — it only teaches the
 * WebView how to fulfil the download it already asks for. Chromium
 * WebView doesn't resolve `<a download href="blob:...">` / `data:...`
 * clicks into real files the way a full browser tab does, so this
 * listener catches those clicks, reads the content as base64 in-page,
 * and hands it to the native AndroidDownloader bridge to write to the
 * device's Downloads collection.
 */
(function () {
  if (window.__proofmarkDownloadHookInstalled) return;
  window.__proofmarkDownloadHookInstalled = true;

  function guessMime(filename, fallback) {
    var ext = (filename.split('.').pop() || '').toLowerCase();
    var map = {
      png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg',
      webp: 'image/webp', svg: 'image/svg+xml', pdf: 'application/pdf',
    };
    return map[ext] || fallback || 'application/octet-stream';
  }

  function blobToBase64(blob) {
    return new Promise(function (resolve, reject) {
      var reader = new FileReader();
      reader.onloadend = function () {
        var result = reader.result || '';
        var commaIndex = result.indexOf(',');
        resolve(commaIndex >= 0 ? result.slice(commaIndex + 1) : result);
      };
      reader.onerror = reject;
      reader.readAsDataURL(blob);
    });
  }

  document.addEventListener('click', function (event) {
    var el = event.target;
    while (el && el.tagName !== 'A') el = el.parentElement;
    if (!el || !el.hasAttribute('download')) return;

    var href = el.getAttribute('href') || '';
    var filename = el.getAttribute('download') || 'download';

    if (href.indexOf('blob:') === 0) {
      event.preventDefault();
      fetch(href)
        .then(function (r) { return r.blob(); })
        .then(function (blob) {
          return blobToBase64(blob).then(function (base64) {
            window.AndroidDownloader.saveFile(base64, filename, guessMime(filename, blob.type));
          });
        })
        .catch(function (err) { console.error('Proofmark download failed', err); });
    } else if (href.indexOf('data:') === 0) {
      event.preventDefault();
      var match = href.match(/^data:([^;]+);base64,(.*)$/);
      if (match) {
        window.AndroidDownloader.saveFile(match[2], filename, guessMime(filename, match[1]));
      }
    }
    // Regular http(s) URLs are left alone — the WebView's normal download
    // listener handles those.
  }, true);
})();
