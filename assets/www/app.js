/* PeerZap — UI logic.
   The visual language (One UI-inspired cards, sheets, glow, motion) is the
   original app's; only the content and the features are new. */
(function () {
  'use strict';

  var QS = window.QuickShare || null;   // native bridge (absent in a plain browser)
  var $ = function (id) { return document.getElementById(id); };

  var S = {
    route: 'home',
    devices: [],
    transfers: [],
    incoming: [],
    history: [],
    pending: [],
    localIp: '',
    running: false,
    settings: {
      deviceName: 'This device', visible: true, saveDir: 'QuickShare',
      notifyIncoming: true, notifyComplete: true, autoAccept: false,
      version: '', versionCode: '', pkg: 'com.peerzap'
    }
  };

  var prefs = { theme: 'light', accent: 'blue', glow: true, glass: true, strictMode: false, icon: 'light', autodl: false, autoinstall: true, qsCompat: false };

  /* ---------------------------------------------------------- utilities */

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function fmtSize(n) {
    n = Number(n) || 0;
    if (n < 1024) return n + ' B';
    if (n < 1048576) return (n / 1024).toFixed(n < 10240 ? 1 : 0) + ' KB';
    if (n < 1073741824) return (n / 1048576).toFixed(n < 10485760 ? 1 : 0) + ' MB';
    return (n / 1073741824).toFixed(2) + ' GB';
  }
  function fmtSpeed(bps) {
    if (!bps || bps < 1) return '';
    return fmtSize(bps) + '/s';
  }
  function fmtEta(sec) {
    if (sec == null || sec < 0) return '';
    sec = Math.round(sec);
    if (sec < 60) return sec + 's left';
    var m = Math.floor(sec / 60), s = sec % 60;
    return m + 'm ' + s + 's left';
  }
  function timeAgo(ms) {
    var d = Date.now() - (ms || 0);
    if (d < 60000) return 'just now';
    if (d < 3600000) return Math.floor(d / 60000) + 'm ago';
    if (d < 86400000) return Math.floor(d / 3600000) + 'h ago';
    return Math.floor(d / 86400000) + 'd ago';
  }
  function ext(name) {
    var i = String(name || '').lastIndexOf('.');
    return i >= 0 ? name.slice(i + 1).toLowerCase() : '';
  }
  function fileKind(name) {
    var e = ext(name);
    if (['jpg', 'jpeg', 'png', 'gif', 'webp', 'heic', 'bmp', 'svg'].indexOf(e) >= 0) return 'image';
    if (['mp4', 'mov', 'mkv', 'webm', '3gp', 'avi'].indexOf(e) >= 0) return 'video';
    if (['mp3', 'wav', 'ogg', 'm4a', 'flac', 'aac'].indexOf(e) >= 0) return 'audio';
    if (['zip', 'rar', '7z', 'tar', 'gz'].indexOf(e) >= 0) return 'archive';
    if (e === 'apk') return 'apk';
    if (['pdf', 'doc', 'docx', 'txt', 'md', 'ppt', 'pptx', 'xls', 'xlsx', 'csv'].indexOf(e) >= 0) return 'doc';
    return 'file';
  }

  var ICON = {
    device: '<rect x="5" y="2" width="14" height="20" rx="3"/><line x1="12" y1="18" x2="12.01" y2="18"/>',
    send: '<line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/>',
    recv: '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/>',
    image: '<rect x="3" y="3" width="18" height="18" rx="3"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/>',
    video: '<polygon points="23 7 16 12 23 17 23 7"/><rect x="1" y="5" width="15" height="14" rx="2"/>',
    audio: '<path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/>',
    archive: '<polyline points="21 8 21 21 3 21 3 8"/><rect x="1" y="3" width="22" height="5"/><line x1="10" y1="12" x2="14" y2="12"/>',
    apk: '<rect x="4" y="4" width="16" height="16" rx="4"/><line x1="9" y1="12" x2="15" y2="12"/>',
    doc: '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/>',
    file: '<path d="M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"/><polyline points="13 2 13 9 20 9"/>'
  };
  function svg(name) {
    return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' + (ICON[name] || ICON.file) + '</svg>';
  }
  function fileIcon(name) { return svg(fileKind(name)); }

  /* ---------------------------------------------------------- chrome */

  var toastT = null;
  /* Developer mode: seven taps on the build number, as on Android itself. */
  function devTaps() { try { return parseInt(localStorage.getItem('qs_devtaps') || '0', 10) || 0; } catch (e) { return 0; } }
  function devMode() { try { return localStorage.getItem('qs_devmode') === '1'; } catch (e) { return false; } }
  function devTap() {
    if (devMode()) return;
    var n = devTaps() + 1;
    try {
      if (n >= 7) { localStorage.setItem('qs_devmode', '1'); localStorage.removeItem('qs_devtaps'); }
      else localStorage.setItem('qs_devtaps', String(n));
    } catch (e) {}
    render();
  }


  /* Progress arrives in steps (the bridge is polled), so the number is tweened to the
     new value instead of snapping to it. The ring's --p is transitioned by CSS. */
  function setPct(el, to) {
    if (!el) return;
    var from = Number(el.getAttribute('data-pct') || '0');
    to = Number(to) || 0;
    el.setAttribute('data-pct', String(to));
    if (from === to || !window.requestAnimationFrame) { el.textContent = to + '%'; return; }
    var start = null, dur = 900;
    function step(now) {
      if (start === null) start = now;
      var k = Math.min(1, (now - start) / dur);
      var e = 1 - Math.pow(1 - k, 3);
      el.textContent = Math.round(from + (to - from) * e) + '%';
      if (k < 1) requestAnimationFrame(step);
    }
    requestAnimationFrame(step);
  }

  /* Ask GitHub for the newest release. Natively, because the page is file:// and a
     cross-origin fetch from that origin is not reliable. */
  function checkUpdate(byUser) {
    S.updLatest = 'Checking\u2026';
    if (byUser) render();
    try {
      var r = JSON.parse(QS.checkForUpdate() || '{}');
      if (!r.ok) { S.updLatest = 'Check failed'; S.updUrl = ''; render(); return; }
      S.updName = (r.url || '').split('/').pop();
      if (r.available) {
        S.updLatest = r.latest;
        S.updUrl = r.url || '';
        if (prefs.autodl && QS.downloadAndInstall && S.updDl !== r.latest) {
          S.updDl = r.latest;
          if (prefs.autoinstall !== false) QS.downloadAndInstall(r.url, S.updName);
        }
      } else {
        S.updLatest = r.latest ? ('Up to date \u00b7 ' + r.latest) : 'No releases yet';
        S.updUrl = '';
      }
    } catch (e) { S.updLatest = 'Check failed'; S.updUrl = ''; }
    render();
  }

  function savePrefs() {
    try { localStorage.setItem('qs_prefs', JSON.stringify(prefs)); } catch (e) {}
  }

  function canPopup() { try { return !!(QS && QS.canPopup && QS.canPopup()); } catch (e) { return true; } }

  function toast(msg) {
    // Toasts are hidden by request: the app no longer pops up confirmations for
    // file selection or anything else. Kept as a function so callers are unchanged.
    return;
  }

  function openSheet(html) {
    var sh = $('sheet');
    sh.innerHTML = '<div class="sheetcard">' + html + '</div>';
    sh.hidden = false;
    document.body.classList.add('sheetopen');
    sh.onclick = function (e) { if (e.target === sh) closeSheet(); };
  }
  function closeSheet() {
    var sh = $('sheet');
    sh.hidden = true;
    sh.innerHTML = '';
    document.body.classList.remove('sheetopen');
  }

  /* ---------------------------------------------------------- back gesture */

  /**
   * Android's back gesture asks the page first, through MainActivity.onBackPressed.
   * The page undoes whatever it owns - an open sheet, the Credits tab, a deeper
   * route - and only when there is genuinely nothing left does it hand control
   * back to Android, which closes the app.
   *
   * Before this existed, back was unhandled: it went straight to finishing the
   * activity, so backing out of Credits closed the whole app.
   */
  window.qsBack = function () {
    var sh = $('sheet');
    if (sh && !sh.hidden) { closeSheet(); return; }                 // a sheet is up
    if (S.route === 'settings' && STAB === 'credits') {             // Credits -> Settings
      STAB = 'general'; render(); $('scroller').scrollTop = 0; return;
    }
    if (S.route !== 'home') { nav('home'); return; }                // any route -> home
    if (QS && QS.exitApp) QS.exitApp();                             // nothing left: exit
  };

  function applyTheme() {
    document.body.classList.remove('dark', 'pitch');
    if (prefs.theme === 'dark') document.body.classList.add('dark');
    if (prefs.theme === 'pitch') document.body.classList.add('pitch');
    document.documentElement.setAttribute('data-accent', prefs.accent || 'blue');
    document.documentElement.setAttribute('data-glow', prefs.glow ? 'on' : 'off');
    document.documentElement.setAttribute('data-glass', prefs.glass === false ? 'off' : 'on');
    try { localStorage.setItem('qs_prefs', JSON.stringify(prefs)); } catch (e) {}
    // keep the choice natively too, so the PeerZap panel matches exactly
    if (QS && QS.setSetting) {
      try {
        QS.setSetting('theme', prefs.theme);
        QS.setSetting('accent', prefs.accent || 'blue');
        QS.setSetting('glow', String(!!prefs.glow));
        QS.setSetting('glass', String(prefs.glass !== false));
        QS.setSetting('qsCompat', String(!!prefs.qsCompat));
        QS.setSetting('strictMode', String(!!prefs.strictMode));
      } catch (e) {}
    }
    if (QS && QS.theme) {
      var bar = prefs.theme === 'light' ? '#F2F2F2' : (prefs.theme === 'pitch' ? '#000000' : '#161719');
      QS.theme(bar, prefs.theme === 'light');
    }
  }

  /* ---------------------------------------------------------- router */

  var TITLES = {
    home: ['PeerZap', 'Direct. Fast. Effortless.'],
    devices: ['Nearby devices', 'Devices on your Wi-Fi network'],
    transfers: ['Transfers', 'Active and completed transfers'],
    files: ['Files', 'Everything on this device'],
    settings: ['Settings', '']
  };
  var TABS = ['home', 'devices', 'transfers', 'files'];
  var navDir = 'fwd';
  function routeDepth(r) { return String(r || '').split('/').length; }

  function routeTitle(route) {
    if (window.QSFiles && QSFiles.isFilesRoute(route)) return QSFiles.titleFor(route);
    return TITLES[route] || TITLES.home;
  }

  function nav(route) {
    navDir = routeDepth(route) >= routeDepth(S.route) ? 'fwd' : 'back';
    S.route = route;
    var t = routeTitle(route);
    $('bigTitle').textContent = t[0];
    $('bigSub').textContent = t[1];
    $('appbarTitle').textContent = t[0];
    var isTab = TABS.indexOf(route) >= 0;
    $('backBig').hidden = isTab;
    $('backApp').hidden = isTab;
    var seg = route.split('/')[0];
    document.querySelectorAll('#navbar .navbtn').forEach(function (b) {
      b.classList.toggle('active', b.getAttribute('data-go') === '#/' + seg);
    });
    $('scroller').scrollTop = 0;
    render(true);
  }

  function render(entering) {
    var v = $('view');
    var html = '';
    if (S.route === 'home') html = viewHome();
    else if (S.route === 'devices') html = viewDevices();
    else if (S.route === 'transfers') html = viewTransfers();
    else if (S.route === 'settings') html = viewSettings();
    else if (window.QSFiles && QSFiles.isFilesRoute(S.route)) html = QSFiles.view(S.route);
    else html = viewHome();
    v.innerHTML = html;
    // A route change gets a whisper of a fade - it reads as a transition rather than
    // a refresh, because it is 400ms on transform+opacity only. Re-renders of the
    // same route do not animate at all.
    try {
      if (render._lastRoute !== S.route) {
        render._lastRoute = S.route;
        v.classList.remove('viewswap');
        void v.offsetWidth;              // restart the animation
        v.classList.add('viewswap');
      }
    } catch (e) {}
    if (window.QSFiles && QSFiles.isFilesRoute(S.route)) {
      var fs = $('fsearch');
      if (fs) fs.addEventListener('input', function () { QSFiles.filter(this.value); });
      QSFiles.after(S.route);
    }
  }

  /* Update the progress rings/bars in place so they can ease smoothly instead of
     the whole list being rebuilt on every progress tick. */
  function updateTransfersInPlace() {
    var active = S.transfers.filter(function (t) { return t.status === 'active' || t.status === 'waiting'; });
    var nodes = document.querySelectorAll('#view [data-tid]');
    if (!nodes.length || nodes.length !== active.length) return false;
    var byId = {};
    active.forEach(function (t) { byId[t.id] = t; });
    for (var i = 0; i < nodes.length; i++) {
      if (!byId[nodes[i].getAttribute('data-tid')]) return false;
    }
    active.forEach(function (t) {
      var n = document.querySelector('#view [data-tid="' + t.id + '"]');
      if (!n) return;
      var pct = t.size > 0 ? Math.min(100, Math.round((t.done || 0) / t.size * 100)) : 0;
      var ring = n.querySelector('.ring');
      if (ring) {
        ring.style.setProperty('--p', pct);
        var b = ring.querySelector('b');
        if (b) b.textContent = pct + '%';
      }
      var bar = n.querySelector('.upbar i');
      if (bar) bar.style.width = pct + '%';
      var sz = n.querySelector('.tsize');
      if (sz) sz.textContent = (t.count || 0) + ' file(s) · ' + fmtSize(t.done || 0) + ' of ' + fmtSize(t.size || 0);
      var meta = n.querySelector('.tmeta');
      if (meta) meta.textContent = (fmtSpeed(t.speed) ? fmtSpeed(t.speed) + ' · ' : '') + fmtEta(t.eta);
    });
    return true;
  }

  /* ---------------------------------------------------------- views */

  function viewHome() {
    var st = S.settings;
    var h = '';
    h += '<div class="card dlhero shero">' +
      '<div class="ltitle" style="font-size:19px;font-weight:700">' + esc(st.deviceName || 'This device') + '</div>' +
      '<div class="lsub" style="margin-top:4px;opacity:.92">' +
      (st.visible ? 'Visible to nearby devices' : 'Hidden from nearby devices') +
      (S.localIp ? ' · ' + esc(S.localIp) : '') + '</div>' +
      '<div class="chips" style="margin-top:12px">' +
      '<span class="chip">' + (S.running ? '<span class="pulse" style="display:inline-block;width:8px;height:8px;margin-right:6px;vertical-align:middle"></span>Listening' : 'Starting…') + '</span>' +
      '<span class="chip">' + S.devices.length + ' nearby</span>' +
      '</div>' +
      '<div class="btnrow">' +
      '<button class="btn ghost" data-act="pick">Choose files</button>' +
      '<button class="btn ghost" data-go="#/devices">' + (S.devices.length ? 'Send to a device' : 'Find devices') + '</button>' +
      '</div></div>';

    S.incoming.forEach(function (inc) { h += incomingCard(inc); });
    if (S.pending.length) h += pendingCard();

    h += '<div class="sect">Nearby devices</div>';
    h += S.devices.length
      ? '<div class="card list">' + S.devices.slice(0, 4).map(deviceRow).join('') + '</div>'
      : '<div class="card"><div class="empty">No devices yet.<br>Open PeerZap on the other device and keep both phones on the same Wi-Fi network.</div></div>';

    var recent = S.history.slice(0, 4);
    if (recent.length) {
      h += '<div class="sect">Recent transfers</div><div class="card list">' + recent.map(historyRow).join('') + '</div>';
    }
    return h;
  }

  function viewDevices() {
    var h = '';
    if (S.pending.length) h += pendingCard();
    h += '<div class="ntbar"><span>' + (S.devices.length ? S.devices.length + ' device(s) found' : 'Scanning the network…') + '</span>' +
      '<span class="ntbar-acts"><button class="btn ghost sm" data-act="scan">Refresh</button></span></div>';
    h += S.devices.length
      ? '<div class="card list">' + S.devices.map(deviceRow).join('') + '</div>'
      : '<div class="card"><div class="empty">No devices found yet.<br><br>Both devices must be on the same Wi-Fi network and have PeerZap open.</div></div>';
    h += '<div class="card"><div class="setrow"><div class="lmain"><div class="ltitle">Not seeing the other device?</div>' +
      '<div class="lsub" style="white-space:normal">Check that both phones are on the same Wi-Fi (not one on mobile data), and that "Visible to nearby devices" is on.</div></div></div></div>';
    return h;
  }

  function viewTransfers() {
    var h = '';
    if (S.incoming.length) {
      h += '<div class="sect">Incoming requests</div>';
      S.incoming.forEach(function (inc) { h += incomingCard(inc); });
    }
    var active = S.transfers.filter(function (t) { return t.status === 'active' || t.status === 'waiting'; });
    h += '<div class="sect">Active</div>';
    h += active.length
      ? active.map(transferCard).join('')
      : '<div class="card"><div class="empty">No transfers in progress.</div></div>';
    h += '<div class="sect">History</div>';
    if (!S.history.length) {
      h += '<div class="card"><div class="empty">Nothing yet. Files you send and receive will show up here.</div></div>';
    } else {
      h += '<div class="card list">' + S.history.map(historyRow).join('') + '</div>';
      h += '<button class="morebtn" data-act="clearHistory">Clear history</button>';
    }
    return h;
  }

  function qsLogText() {
    try {
      var t = QS.qsCompatLog();
      return (t && t.trim()) ? t : '(nothing yet)';
    } catch (e) { return '(log unavailable)'; }
  }

  function qsCompatStatusText() {
    if (!prefs.qsCompat) return 'Off';
    try {
      var st = JSON.parse(QS.qsCompatStatus());
      var base = st.running ? ('Listening on port ' + st.port) : 'Not listening yet';
      return base + (st.status ? ' - ' + st.status : '');
    } catch (e) { return 'Starting...'; }
  }

  var STAB = 'general';

  var BRAND = {
    telegram: '<svg viewBox="0 0 24 24" fill="#229ED9" width="20" height="20"><path d="M11.944 0A12 12 0 0 0 0 12a12 12 0 0 0 12 12 12 12 0 0 0 12-12A12 12 0 0 0 12 0a12 12 0 0 0-.056 0zm4.962 7.224c.1-.002.321.023.465.14a.506.506 0 0 1 .171.325c.016.093.036.306.02.472-.18 1.898-.962 6.502-1.36 8.627-.168.9-.499 1.201-.82 1.23-.696.065-1.225-.46-1.9-.902-1.056-.693-1.653-1.124-2.678-1.8-1.185-.78-.417-1.21.258-1.91.177-.184 3.247-2.977 3.307-3.23.007-.032.014-.15-.056-.212s-.174-.041-.249-.024c-.106.024-1.793 1.14-5.061 3.345-.48.33-.913.49-1.302.48-.428-.008-1.252-.241-1.865-.44-.752-.245-1.349-.374-1.297-.789.027-.216.325-.437.893-.663 3.498-1.524 5.83-2.529 6.998-3.014 3.332-1.386 4.025-1.627 4.476-1.635z"/></svg>',
    github: '<svg viewBox="0 0 24 24" fill="currentColor" width="20" height="20"><path d="M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12"/></svg>'
  };


  /* Cloned from Gitly's Credits tab: an app card, a developer card, connect
     buttons and a languages row - with our own content. */
  function viewCredits() {
    var st = S.settings;
    var version = (st.version || 'unknown') + (st.versionCode ? ' (build ' + st.versionCode + ')' : '');
    var langs = [
      ['Java', 'The whole Android side - discovery, the Quick Share protocol, the receivers and services.'],
      ['JavaScript', 'The interface you see. It runs in a WebView and talks to Java through a small bridge.'],
      ['HTML', 'The structure of the app and the share panel.'],
      ['CSS', 'All of the styling, themes, accents and animation.'],
      ['XML', 'Android resources - the manifest, layouts, styles and icons.']
    ];
    var h = '';
    h += '<div class="card" style="text-align:center;padding:26px 20px">' +
      '<div style="font-size:32px;font-weight:800;letter-spacing:-0.5px">PeerZap</div>' +
      '<ul class="creditslist" style="list-style:none;text-align:left;margin:16px auto 0;padding:0;max-width:300px">' +
      '<li class="lsub" style="padding:7px 0 7px 18px;border-bottom:1px solid var(--divider);white-space:normal">Version ' + esc(version) + '</li>' +
      '<li class="lsub" style="padding:7px 0 7px 18px;border-bottom:1px solid var(--divider);white-space:normal">Version name is the release; the build number goes up with every build.</li>' +
      '<li class="lsub" style="padding:7px 0 7px 18px;border-bottom:1px solid var(--divider);white-space:normal">Transfers directly between nearby devices</li>' +
      '<li class="lsub" style="padding:7px 0 7px 18px;border-bottom:1px solid var(--divider);white-space:normal">Not affiliated with Google or Samsung</li>' +
      '<li class="lsub" style="padding:7px 0 7px 18px">Quick Share compatibility is an independent reimplementation</li></ul></div>';

    h += '<div class="sect">Developer</div>';
    h += '<div class="card" style="text-align:center">' +
      '<img src="dev.jpg" alt="BonkerUnkil (Bonki)" style="width:96px;height:96px;border-radius:32px;margin:0 auto 16px;box-shadow:0 8px 28px var(--glow)">' +
      '<div style="padding:8px 0;border-bottom:1px solid var(--divider)"><div class="ltitle">Name</div>' +
      '<div class="lsub" style="margin-top:2px">BonkerUnkil (Bonki)</div></div>' +
      '<div style="padding:8px 0;border-bottom:1px solid var(--divider)"><div class="ltitle">Age</div>' +
      '<div class="lsub" style="margin-top:2px">20</div></div>' +
      '<div style="padding:8px 0"><div class="ltitle">Profession</div>' +
      '<div class="lsub" style="margin-top:2px">AI Prompt Engineering, UI/UX Designer, Coding</div></div></div>' +
      '<div class="sect">Connect</div>' +
      '<div class="card">' +
      '<button class="btn ghost btnblock" data-act="ext" data-url="https://t.me/BonkerUnkilBonki" style="display:flex;align-items:center;justify-content:flex-start;gap:12px">' +
      BRAND.telegram + '<span>Telegram · @BonkerUnkilBonki</span></button>' +
      '<button class="btn ghost btnblock" data-act="ext" data-url="https://github.com/BonkerUnkilBonki" style="display:flex;align-items:center;justify-content:flex-start;gap:12px;margin-top:8px">' +
      BRAND.github + '<span>GitHub · @BonkerUnkilBonki</span></button></div>';

    h += '<div class="sect">Languages used</div>';
    h += '<div class="card">' + langs.map(function (l) {
      return '<div style="padding:10px 0;border-bottom:1px solid var(--divider)">' +
        '<div class="ltitle">' + esc(l[0]) + '</div>' +
        '<div class="lsub" style="white-space:normal;margin-top:3px">' + esc(l[1]) + '</div></div>';
    }).join('') + '<div class="chips" style="margin-top:12px">' +
      langs.map(function (l) { return '<span class="chip">' + esc(l[0]) + '</span>'; }).join('') +
      '</div></div>';

    h += '<div class="sect">Compatibility</div>';
    h += '<div class="card">' +
      '<div style="padding:10px 0;border-bottom:1px solid var(--divider)"><div class="ltitle">Google PeerZap</div>' +
      '<div class="lsub" style="white-space:normal;margin-top:3px">Receiving from the stock app over the local network, and sending to it, by reimplementing the nearby protocol.</div></div>' +
      '<div style="padding:10px 0"><div class="ltitle">This app to this app</div>' +
      '<div class="lsub" style="white-space:normal;margin-top:3px">Over Wi-Fi, Wi-Fi Direct when there is no shared network, or Bluetooth when there is no network at all.</div></div></div>';
    return h;
  }

  function viewSettings() {
    var st = S.settings;
    var h = '';
    h += '<div class="seg" style="margin:0 0 14px">' +
      segBtn('stab', 'general', 'Settings', STAB) +
      segBtn('stab', 'credits', 'Credits', STAB) + '</div>';
    if (STAB === 'credits') return h + viewCredits();
    h += '<div class="sect">Device</div>';
    h += '<div class="card">' +
      '<label class="fldlabel" for="setName">Device name</label>' +
      '<input id="setName" class="fld" type="text" value="' + esc(st.deviceName) + '" maxlength="40">' +
      '<button class="btn primary btnblock" data-act="saveName" style="margin-top:12px">Save name</button>' +
      '<div class="setrow" style="margin-top:18px"><div class="lmain"><div class="ltitle">Visible to nearby devices</div>' +
      '<div class="lsub">Let other phones discover this device</div></div>' +
      toggle('visible', st.visible) + '</div>' +
      '</div>';

    h += '<div class="sect">Receiving</div>';
    h += '<div class="card">' +
      '<label class="fldlabel" for="setDir">Save received files to</label>' +
      '<input id="setDir" class="fld" type="text" value="' + esc(st.saveDir) + '" maxlength="30">' +
      '<p class="tiny" style="text-align:left;margin:10px 4px 0">Files are saved in your Downloads folder under this name.</p>' +
      '<button class="btn ghost btnblock" data-act="saveDir" style="margin-top:12px">Save folder</button>' +
      '<div class="setrow" style="margin-top:18px"><div class="lmain"><div class="ltitle">Notify on incoming files</div>' +
      '<div class="lsub">Show a notification with Accept and Decline</div></div>' + toggle('notifyIncoming', st.notifyIncoming) + '</div>' +
      '<div class="setrow" style="margin-top:16px"><div class="lmain"><div class="ltitle">Notify when a transfer finishes</div>' +
      '<div class="lsub">Sent and received</div></div>' + toggle('notifyComplete', st.notifyComplete) + '</div>' +
      '<div class="setrow" style="margin-top:16px"><div class="lmain"><div class="ltitle">Auto-accept files</div>' +
      '<div class="lsub">Receive without asking (only with people you trust)</div></div>' + toggle('autoAccept', st.autoAccept) + '</div>' +
      '</div>';

    h += '<div class="sect">Background</div>';
    h += '<div class="card">' +
      '<div class="setrow"><div class="lmain"><div class="ltitle">PeerZap only runs while it is open</div>' +
      '<div class="lsub" style="white-space:normal">There is no background service. Closing the panel - or leaving this screen - shuts PeerZap down. The one exception is a transfer that is still running: it is kept alive just long enough to finish (with its live progress notification), and then PeerZap stops itself.</div></div>' +
      '</div>' +
      '</div>';

    h += '<div class="sect">Background</div>';
    h += '<div class="card">' +
      '<div class="setrow"><div class="lmain"><div class="ltitle">Strict mode</div>' +
      '<div class="lsub" style="white-space:normal">Nothing runs in the background. The listener stops the moment you leave the app or the panel, so a transfer can only arrive while PeerZap is open. Saves battery, but a share sent while the app is closed will not reach you.</div></div>' +
      '<div class="switch' + (prefs.strictMode ? ' on' : '') + '" data-act="strictmode"></div></div>' +
      (canPopup() ? '' :
        '<div class="setrow" style="margin-top:16px"><div class="lmain"><div class="ltitle">Show the panel when a file arrives</div>' +
        '<div class="lsub" style="white-space:normal">Needs "Display over other apps". Without it the incoming notification is the only prompt.</div></div>' +
        '<button class="btn ghost" data-act="overlay">Allow</button></div>') +
      '</div>';

    h += '<div class="sect">App updates</div>';
    h += '<div class="card">' +
      '<div class="setrow"><div class="lmain"><div class="ltitle">Auto-download app updates</div>' +
      '<div class="lsub" style="white-space:normal">When a new PeerZap release is published on GitHub, the update APK is downloaded to your Downloads folder automatically.</div></div>' +
      '<div class="switch' + (prefs.autodl ? ' on' : '') + '" data-act="autodl"></div></div>' +
      '<div class="setrow" style="margin-top:16px;border-top:1px solid var(--divider);padding-top:14px"><div class="lmain"><div class="ltitle">Install updates automatically</div>' +
      '<div class="lsub" style="white-space:normal">Opens the Android installer as soon as the update finishes downloading, so the update actually lands. Android asks once to allow PeerZap to install unknown apps. Turn this off to download only.</div></div>' +
      '<div class="switch' + (prefs.autoinstall !== false ? ' on' : '') + '" data-act="autoinstall"></div></div>' +
      '<div style="display:flex;gap:10px;align-items:center;padding:12px 0 2px;margin-top:12px;border-top:1px solid var(--divider)">' +
      '<div class="lmain" style="flex:1"><div class="ltitle">Installed</div><div class="lsub">Version ' + esc(st.version || '') + '</div></div>' +
      '<div class="lmain" style="flex:1;text-align:right"><div class="ltitle" id="updLatest">' + esc(S.updLatest || 'Not checked') + '</div><div class="lsub">latest on GitHub</div></div></div>' +
      '<button class="btn ghost btnblock" style="margin-top:12px" data-act="checkupd">Check for updates</button>' +
      (S.updUrl ? '<button class="btn primary btnblock" style="margin-top:8px" data-act="doinstall">Download and install</button>' : '') +
      '</div>';

    h += '<div class="sect">Google PeerZap</div>';
    h += '<div class="card">' +
      '<div class="setrow"><div class="lmain"><div class="ltitle">Quick Share compatibility</div>' +
      '<div class="lsub" style="white-space:normal">Experimental. Makes this device visible to stock Google Quick Share on the same Wi-Fi network and receives files from it. Wi-Fi LAN only - both devices must be on the same network, with client isolation off.</div></div>' +
      '<div class="switch' + (prefs.qsCompat ? ' on' : '') + '" data-act="qscompat"></div></div>' +
      '<div class="setrow" style="margin-top:14px"><div class="lmain"><div class="ltitle">Status</div>' +
      '<div class="lsub" style="white-space:normal">' + esc(qsCompatStatusText()) + '</div></div></div>' +
      '</div>';

    if (devMode()) h += '<div class="sect">Diagnostics</div>';
    if (devMode()) h += '<div class="card">' +
      '<div class="setrow"><div class="lmain"><div class="ltitle">Protocol log</div>' +
      '<div class="lsub" style="white-space:normal">Everything the receiver did on the last connections, newest last. If a transfer fails, copy this and send it on.</div></div></div>' +
      '<pre class="qslog" id="qslogbox">' + esc(qsLogText()) + '</pre>' +
      '<div class="btnrow" style="margin-top:12px">' +
      '<button class="btn" data-act="qslogcopy">Copy log</button>' +
      '<button class="btn" data-act="qslogclear">Clear</button>' +
      '</div></div>';

    h += '<div class="sect">App icon</div>';
    h += '<div class="card">' +
      '<div class="lsub" style="white-space:normal;margin-bottom:12px">Pick the launcher icon. Android may take a moment to refresh the home screen.</div>' +
      '<div class="iconrow">' +
      [['light', 'Light'], ['light_ios', 'Light (iOS)'], ['dark', 'Dark'], ['dark_ios', 'Dark (iOS)']].map(function (o) {
        return '<button class="iconpick' + (prefs.icon === o[0] ? ' on' : '') + '" data-act="icon" data-v="' + o[0] + '">' +
          '<img src="ic_' + o[0] + '.png" alt="' + esc(o[1]) + '">' +
          '<span class="lsub" style="white-space:normal">' + esc(o[1]) + '</span></button>';
      }).join('') + '</div></div>';

    h += '<div class="sect">Appearance</div>';
    h += '<div class="card">' +
      '<div class="seg" style="margin:0">' +
      segBtn('theme', 'light', 'Light', prefs.theme) +
      segBtn('theme', 'dark', 'Dark', prefs.theme) +
      segBtn('theme', 'pitch', 'Pitch black', prefs.theme) + '</div>' +
      '<div class="accentrow">' +
      ['blue', 'purple', 'green', 'pink', 'amber', 'teal', 'red', 'indigo'].map(function (a) {
        return '<button class="accentdot' + (prefs.accent === a ? ' on' : '') + '" data-act="accent" data-v="' + a + '" style="background:' + accentHex(a) + '"></button>';
      }).join('') + '</div>' +
      '<div class="setrow" style="margin-top:18px"><div class="lmain"><div class="ltitle">Navigation glow</div>' +
      '<div class="lsub">Soft glow around the buttons and cards</div></div>' +
      '<div class="switch' + (prefs.glow ? ' on' : '') + '" data-act="glow"></div></div>' +
      '<div class="setrow" style="margin-top:16px"><div class="lmain"><div class="ltitle">Glass effect</div>' +
      '<div class="lsub">Frosted, see-through panel and sheets</div></div>' +
      '<div class="switch' + (prefs.glass !== false ? ' on' : '') + '" data-act="glass"></div></div>' +
      '</div>';

    h += '<div class="sect">About</div>';
    h += '<div class="card">' +
      '<div class="setrow"><div class="lmain"><div class="ltitle">PeerZap</div>' +
      '<div class="lsub">A standalone nearby file-sharing client</div></div></div>' +
      '<div class="setrow" data-act="devtap" style="margin-top:14px"><div class="lmain"><div class="ltitle">App version</div>' +
      '<div class="lsub">' + esc(st.version || 'unknown') +
      (st.versionCode ? ' (build ' + esc(st.versionCode) + ')' : '') +
      (devMode() ? '' : (devTaps() > 0 ? ' \u00b7 ' + (7 - devTaps()) + ' more taps' : '')) +
      '</div></div></div>' +
      '<div class="setrow" style="margin-top:14px"><div class="lmain"><div class="ltitle">Package</div>' +
      '<div class="lsub">' + esc(st.pkg || 'com.peerzap') + '</div></div></div>' +
      '<div class="lrow" data-act="about" style="padding:14px 0"><div class="lmain"><div class="ltitle">How transfers work</div>' +
      '<div class="lsub">Protocol, limits and compatibility</div></div>' +
      '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="width:20px;height:20px;color:var(--text2)"><polyline points="9 18 15 12 9 6"/></svg></div>' +
      '</div>';
    return h;
  }

  function accentHex(a) {
    return { blue: '#1B6EF3', purple: '#8B5CF6', green: '#12B76A', pink: '#EC4899', amber: '#F59E0B',
      teal: '#14B8A6', red: '#EF4444', indigo: '#6366F1' }[a] || '#1B6EF3';
  }
  function segBtn(act, v, label, cur) {
    return '<button class="segb' + (cur === v ? ' on' : '') + '" data-act="' + act + '" data-v="' + v + '">' + label + '</button>';
  }
  function toggle(key, on) {
    return '<div class="switch' + (on ? ' on' : '') + '" data-act="toggle" data-key="' + key + '"></div>';
  }

  function deviceRow(d) {
    var hasFiles = S.pending.length > 0;
    return '<div class="lrow" data-act="' + (hasFiles ? 'send' : 'nodev') + '" data-id="' + esc(d.id) + '">' +
      '<span class="devav">' + svg('device') + '</span>' +
      '<div class="lmain"><div class="ltitle">' + esc(d.name || 'Device') + '</div>' +
      '<div class="lsub">' + esc(d.ip || '') + (d.direct ? ' · Direct link' : '') + (d.model && !d.direct ? ' · ' + esc(d.model) : '') + (d.stock ? ' · Quick Share' : '') + '</div></div>' +
      '<span class="chip ' + (hasFiles ? '' : '') + '" style="' + (hasFiles ? '' : 'opacity:.6') + '">' + (hasFiles ? 'Send' : 'Select files') + '</span>' +
      '</div>';
  }

  function pendingCard() {
    var total = S.pending.reduce(function (a, f) { return a + (Number(f.size) > 0 ? Number(f.size) : 0); }, 0);
    return '<div class="card">' +
      '<div class="ltitle">Ready to send · ' + S.pending.length + ' file(s)</div>' +
      '<div class="lsub">' + (total ? fmtSize(total) : 'Size unknown') + ' · pick a device to send to</div>' +
      '<div style="margin-top:8px">' + S.pending.map(function (f) {
        return '<div class="filerow"><span class="devav" style="width:34px;height:34px">' + fileIcon(f.name) + '</span>' +
          '<div class="fname"><div class="ltitle" style="font-size:13.5px">' + esc(f.name) + '</div>' +
          '<div class="lsub">' + (Number(f.size) > 0 ? fmtSize(f.size) : 'Unknown size') + '</div></div></div>';
      }).join('') + '</div>' +
      '<div class="btnrow"><button class="btn ghost" data-act="clearPending">Clear</button>' +
      '<button class="btn primary" data-go="#/devices">Choose device</button></div>' +
      '</div>';
  }

  function incomingCard(inc) {
    var names = (inc.files || []).map(function (n) { return '<div class="lsub" style="white-space:normal">' + esc(n) + '</div>'; }).join('');
    return '<div class="card" style="border:1px solid var(--accent)">' +
      (inc.pin && !inc.auto ? '<div class="pinbig">PIN ' + esc(inc.pin) + '</div>' : '') +
      '<div class="lsub" style="text-align:center">' + (inc.count || 0) + ' file(s)</div>' +
      (inc.pin ? '<div class="lsub" style="white-space:normal;font-weight:600">PIN ' + esc(inc.pin) + ' · check it matches the sender, then Accept</div>' : '') +
      '<div style="margin-top:6px">' + names + '</div>' +
      (inc.auto
        ? '<div class="btnrow"><span class="chip">Receiving…</span></div>'
        : '<div class="btnrow"><button class="btn danger" data-act="reject" data-id="' + esc(inc.id) + '">Decline</button>' +
          '<button class="btn primary" data-act="accept" data-id="' + esc(inc.id) + '">Accept</button></div>') +
      '</div>';
  }

  function transferCard(t) {
    var pct = t.size > 0 ? Math.min(100, Math.round((t.done || 0) / t.size * 100)) : 0;
    var dir = t.dir === 'send' ? 'Sending to ' : 'Receiving from ';
    return '<div class="card" data-tid="' + esc(t.id) + '">' +
      '<div class="lrow" style="padding:0;border:none">' +
      '<span class="ring" style="--p:' + pct + '"><b>' + pct + '%</b></span>' +
      '<div class="lmain"><div class="ltitle">' + dir + esc(t.peer || 'Device') + '</div>' +
      '<div class="lsub tsize">' + (t.count || 0) + ' file(s) · ' + fmtSize(t.done || 0) + ' of ' + fmtSize(t.size || 0) + '</div>' +
      '<div class="lsub tmeta">' + (fmtSpeed(t.speed) ? fmtSpeed(t.speed) + ' · ' : '') + fmtEta(t.eta) + '</div></div>' +
      '</div>' +
      '<div class="upbar"><i style="width:' + pct + '%"></i></div>' +
      '<div class="btnrow"><button class="btn ghost" data-act="cancel" data-id="' + esc(t.id) + '">Cancel</button></div>' +
      '</div>';
  }

  function statusChip(s) {
    var map = { completed: 'ok', failed: 'del', cancelled: 'warn', rejected: 'warn', active: 'pr', waiting: '' };
    return '<span class="chip ' + (map[s] || '') + '">' + esc(s) + '</span>';
  }

  function historyRow(e) {
    var dirIcon = e.dir === 'recv' ? 'recv' : 'send';
    var names = (e.files || []).join(', ');
    return '<div class="lrow" data-act="hdetail" data-id="' + esc(e.id) + '">' +
      '<span class="devav ' + (e.dir === 'recv' ? 'recv' : 'send') + '">' + svg(dirIcon) + '</span>' +
      '<div class="lmain"><div class="ltitle">' + (e.dir === 'recv' ? 'From ' : 'To ') + esc(e.peer || 'Device') + '</div>' +
      '<div class="lsub">' + (e.count || 0) + ' file(s) · ' + fmtSize(e.size || 0) + ' · ' + timeAgo(e.time) + '</div>' +
      '<div class="lbody">' + esc(names) + '</div></div>' +
      statusChip(e.status) +
      '</div>';
  }

  /* ---------------------------------------------------------- actions */

  function onAction(act, el) {
    var id = el.getAttribute('data-id');
    var v = el.getAttribute('data-v');
    var key = el.getAttribute('data-key');
    switch (act) {
      case 'pick': if (QS) QS.pickFiles(); break;
      case 'clearPending': if (QS) QS.clearPending(); S.pending = []; render(); break;
      case 'send': {
        if (!S.pending.length) { toast('Choose files first'); nav('home'); break; }
        var dev = null;
        for (var i = 0; i < (S.devices || []).length; i++) {
          if (S.devices[i].id === id) { dev = S.devices[i]; break; }
        }
        // Real stock Quick Share peers are flagged by the bridge and their ids are
        // prefixed "qs:". Fall back to the prefix so a missing flag can never
        // route a stock device down the app's own protocol, which fails silently.
        var isStock = dev && (dev.stock === true || String(dev.id || '').indexOf('qs:') === 0);
        if (isStock) {
          var r = QS.qsSendTo(id);
          if (r === 'started') { toast('Sending to ' + (dev.name || 'device') + '…'); nav('transfers'); }
          else if (r === 'no-files') toast('Choose files first');
          else if (r === 'not-found') toast('That device is no longer nearby');
          else if (r === 'no-address') toast('Could not reach that device');
          else toast('Could not send (' + r + ')');
          break;
        }
        if (String(id || '').indexOf('p2p:') === 0) {
          var dr = QS.qsDirectSend(id);
          if (dr === 'linking') { toast('Linking directly…'); nav('transfers'); }
          else if (dr === 'no-files') toast('Choose files first');
          else if (dr === 'not-found') toast('That device is no longer nearby');
          else toast('Could not link (' + dr + ')');
          break;
        }
        if (QS) {
          var tid = QS.sendTo(id);
          if (tid) { toast('Sending…'); nav('transfers'); }
          else toast('That device is not available for a direct send');
        }
        break;
      }
      case 'nodev': toast('Choose files first, then tap a device'); break;
      case 'accept':
        if (id === 'qsconsent') { if (QS) QS.qsConsentAccept(); }
        else if (QS) QS.accept(id);
        break;
      case 'reject':
        if (id === 'qsconsent') { if (QS) QS.qsConsentReject(); }
        else if (QS) QS.reject(id);
        break;
      case 'cancel': if (QS) QS.cancel(id); break;
      case 'scan': toast('Scanning…'); if (QS) QS.startDiscovery(); break;
      case 'clearHistory':
        openSheet('<div class="sheethead"><b>Clear history?</b><button class="iconbtn" data-act="closesheet">✕</button></div>' +
          '<p class="lsub" style="white-space:normal;margin:0 4px 14px">This removes the record of past transfers. Received files are not deleted.</p>' +
          '<div class="btnrow"><button class="btn ghost" data-act="closesheet">Cancel</button>' +
          '<button class="btn danger" data-act="doClearHistory">Clear</button></div>');
        break;
      case 'doClearHistory': if (QS) QS.clearHistory(); S.history = []; closeSheet(); render(); toast('History cleared'); break;
      case 'hdetail': showHistoryDetail(id); break;
      case 'removeHistory': if (QS) QS.removeHistory(id); S.history = S.history.filter(function (x) { return x.id !== id; }); closeSheet(); render(); break;
      case 'open': if (QS) QS.openFile(el.getAttribute('data-ref')); break;
      case 'closesheet': closeSheet(); break;
      case 'openfile': if (QS && QS.openHistoryFile) QS.openHistoryFile(id); break;
      case 'saveName':
        var n = ($('setName') && $('setName').value || '').trim() || 'This device';
        S.settings.deviceName = n; if (QS) QS.setSetting('deviceName', n); toast('Device name saved'); render(); break;
      case 'saveDir':
        var d = ($('setDir') && $('setDir').value || '').trim() || 'QuickShare';
        S.settings.saveDir = d; if (QS) QS.setSetting('saveDir', d); toast('Save folder set'); break;
      case 'toggle':
        S.settings[key] = !S.settings[key];
        if (QS) QS.setSetting(key, String(S.settings[key]));
        render(); break;
      case 'theme': prefs.theme = v; applyTheme(); render(); break;
      case 'accent': prefs.accent = v; applyTheme(); render(); break;
      case 'glow': prefs.glow = !prefs.glow; applyTheme(); render(); break;
      case 'glass': prefs.glass = !(prefs.glass !== false); applyTheme(); render(); break;
      case 'strictmode':
        prefs.strictMode = !prefs.strictMode;
        if (QS) QS.setSetting('strictMode', String(prefs.strictMode));
        render(); break;
      case 'overlay': if (QS) QS.openOverlaySettings(); break;
      case 'qscompat':
        prefs.qsCompat = !prefs.qsCompat;
        // Stock Quick Share sends without our consent prompt, so compatibility only
        // works if we accept. Turning it on turns auto-accept on with it.
        if (prefs.qsCompat && !S.settings.autoAccept) {
          S.settings.autoAccept = true;
          if (QS) QS.setSetting('autoAccept', 'true');
        }
        applyTheme(); render(); break;
      case 'devtap': devTap(); break;
      case 'stab': STAB = v; render(); break;
      case 'ext': if (QS && QS.openUrl) QS.openUrl(el.getAttribute('data-url')); break;
      case 'icon': prefs.icon = v; if (QS && QS.setIcon) QS.setIcon(v); savePrefs(); render(); break;
      case 'autodl': prefs.autodl = !prefs.autodl; savePrefs(); render(); break;
      case 'autoinstall': prefs.autoinstall = prefs.autoinstall === false; savePrefs(); render(); break;
      case 'checkupd': checkUpdate(true); break;
      case 'doinstall': if (QS && QS.downloadAndInstall && S.updUrl) QS.downloadAndInstall(S.updUrl, S.updName || 'QuickShare-update.apk'); break;
      case 'qslogcopy': try { QS.copy(qsLogText()); } catch (e) {} break;
      case 'qslogclear': try { QS.qsCompatLogClear(); } catch (e) {} render(); break;
      case 'about': showAbout(); break;
      default:
        // the built-in file manager handles its own actions
        if (window.QSFiles && QSFiles.action) QSFiles.action(act, el);
    }
  }

  function showHistoryDetail(id) {
    var e = S.history.filter(function (x) { return x.id === id; })[0];
    if (!e) return;
    var files = (e.files || []).map(function (n) { return '<div class="filerow"><span class="devav" style="width:32px;height:32px">' + fileIcon(n) + '</span><div class="fname"><div class="ltitle" style="font-size:13.5px">' + esc(n) + '</div></div></div>'; }).join('');
    var saved = e.saved || [];
    var openBtns = saved.map(function (p, i) {
      return '<button class="btn ghost btnblock" style="margin-top:8px" data-act="open" data-ref="' + esc(p) + '">Open ' + esc((e.files || [])[i] || ('file ' + (i + 1))) + '</button>';
    }).join('');
    // PeerZap entries store no uri - their files sit in Downloads/QuickShare, so
    // they are looked up by name instead
    if (!saved.length && (e.files || []).length) {
      openBtns = (e.files || []).slice(0, 6).map(function (n) {
        return '<button class="btn ghost btnblock" style="margin-top:8px" data-act="openfile" data-id="' + esc(e.id) + '">Open ' + esc(n) + '</button>';
      }).join('');
    }
    openSheet(
      '<div class="sheethead"><b>' + (e.dir === 'recv' ? 'Received' : 'Sent') + ' · ' + esc(e.peer || '') + '</b>' +
      '<button class="iconbtn" data-act="closesheet">✕</button></div>' +
      '<div class="chips"><span class="chip">' + esc(e.status) + '</span><span class="chip">' + fmtSize(e.size || 0) + '</span>' +
      '<span class="chip">' + timeAgo(e.time) + '</span></div>' +
      (e.error ? '<p class="lsub" style="white-space:normal;margin:10px 4px;color:var(--danger)">' + esc(e.error) + '</p>' : '') +
      '<div style="margin-top:6px">' + files + '</div>' +
      openBtns +
      (saved.length ? '<p class="tiny" style="text-align:left">Saved in ' + esc(S.settings.saveDir) + ' (Downloads).</p>' : '') +
      '<button class="btn danger btnblock" style="margin-top:14px" data-act="removeHistory" data-id="' + esc(e.id) + '">Remove from history</button>'
    );
  }

  function showAbout() {
    openSheet(
      '<div class="sheethead"><b>How transfers work</b><button class="iconbtn" data-act="closesheet">✕</button></div>' +
      '<div class="md" style="font-size:14px">' +
      '<p>PeerZap finds other phones on the same Wi-Fi network and moves files directly between them - nothing goes through a server or the internet.</p>' +
      '<p><b>Discovery</b> uses a small broadcast beacon on the local network (UDP port 8890). <b>Transfers</b> use a direct connection (TCP port 8891): the sender offers the files, the receiver accepts, and the files stream across.</p>' +
      '<p><b>Compatibility.</b> PeerZap talks to other copies of PeerZap over its own protocol. It can also <b>receive</b> from stock Google Quick Share on the same Wi-Fi network, once <i>Quick Share compatibility</i> is turned on in Settings - this is confirmed working on real hardware. Sending <i>to</i> Google Quick Share is implemented but has not yet been confirmed on hardware, and Windows Quick Share is not supported.</p>' +
      '<p><b>Privacy.</b> Files travel only over your local network. Nothing is uploaded anywhere. Received files are saved to your Downloads folder.</p>' +
      '</div>');
  }

  /* ---------------------------------------------------------- native events */

  window.qsEvent = function (name, payload) {
    try {
      if (name === 'devices') { S.devices = payload || []; if (S.route === 'home' || S.route === 'devices') render(); }
      else if (name === 'transfers') {
        var beforeIds = (S.transfers || []).map(function (t) { return t.id; }).join(',');
        S.transfers = payload || [];
        var afterIds = S.transfers.map(function (t) { return t.id; }).join(',');
        if (S.route === 'transfers') { if (!updateTransfersInPlace()) render(); }
        else if (S.route === 'home' && beforeIds !== afterIds) render();
      }
      else if (name === 'history') { S.history = payload || []; if (S.route === 'transfers' || S.route === 'home') render(); }
      else if (name === 'picked') { S.pending = payload || []; toast(S.pending.length + ' file(s) ready'); render(); }
      else if (name === 'incoming') {
        var inc = payload;
        if (inc && !S.incoming.some(function (x) { return x.id === inc.id; })) S.incoming.push(inc);
        toast('Incoming files from ' + (inc && inc.peer || 'a device'));
        render();
      }
      else if (name === 'toast') { toast(String(payload || '')); }
    } catch (e) {}
  };

  /* ---------------------------------------------------------- boot */

  function refreshState() {
    if (!QS || !QS.getState) return;
    try {
      var st = JSON.parse(QS.getState() || '{}');
      S.devices = st.devices || [];
      S.transfers = st.transfers || [];
      S.history = st.history || [];
      S.pending = (st.pending && st.pending.length) ? st.pending : S.pending;
      S.localIp = st.localIp || '';
      S.running = !!st.running;
      if (!S.updChecked) { S.updChecked = true; setTimeout(function () { try { checkUpdate(false); } catch (e) {} }, 600); }
      if (st.settings) S.settings = Object.assign(S.settings, st.settings);
      // incoming list: keep only ones still pending
      var inc = st.incoming || [];
      S.incoming = inc;
    } catch (e) {}
  }

  function boot() {
    refreshState();
    // native settings are the source of truth (so the panel matches); localStorage
    // is only a fallback for a choice made before this version stored them natively
    var s = S.settings || {};
    var p = {};
    try { p = JSON.parse(localStorage.getItem('qs_prefs') || '{}'); } catch (e) { p = {}; }
    var nativeDefault = (s.theme === 'light' && s.accent === 'blue' && s.glow === true);
    if (nativeDefault && p.theme) {
      prefs.theme = p.theme;
      prefs.accent = p.accent || 'blue';
      prefs.glow = (typeof p.glow === 'boolean') ? p.glow : true;
      prefs.glass = (typeof p.glass === 'boolean') ? p.glass : true;
      prefs.qsCompat = !!p.qsCompat;
    } else {
      if (s.theme) prefs.theme = s.theme;
      if (s.accent) prefs.accent = s.accent;
      if (typeof s.glow === 'boolean') prefs.glow = s.glow;
      if (typeof s.glass === 'boolean') prefs.glass = s.glass;
      if (typeof s.qsCompat === 'boolean') prefs.qsCompat = s.qsCompat;
      if (typeof s.strictMode === 'boolean') prefs.strictMode = s.strictMode;
      if (typeof s.iconStyle === 'string') prefs.icon = s.iconStyle;
    }
    applyTheme();

    if (QS) { try { QS.startDiscovery(); } catch (e) {} }

    document.addEventListener('click', function (e) {
      var go = e.target.closest('[data-go]');
      if (go) { e.preventDefault(); nav(go.getAttribute('data-go').replace('#/', '')); return; }
      var act = e.target.closest('[data-act]');
      if (act) { e.preventDefault(); onAction(act.getAttribute('data-act'), act); return; }
    });

    // hooks the built-in file manager (files.js) uses
    window.qsNav = nav;
    window.qsToast = toast;
    window.qsSheet = openSheet;
    window.qsRerender = function () { render(false); };
    window.qsRoute = function () { return S.route; };

    nav('home');
    render(true);

    // fallback poll in case an event was missed while the page was hidden
    setInterval(function () {
      if (document.hidden) return;
      var before = JSON.stringify([S.transfers, S.history, S.devices, S.incoming]);
      refreshState();
      var after = JSON.stringify([S.transfers, S.history, S.devices, S.incoming]);
      if (before !== after && (S.route === 'transfers' || S.route === 'home' || S.route === 'devices')) render();
    }, 2500);

    document.addEventListener('visibilitychange', function () {
      if (!document.hidden) { refreshState(); render(); }
    });
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();
