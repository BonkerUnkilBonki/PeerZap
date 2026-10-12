/* Quick Share — UI logic.
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
      notifyIncoming: true, notifyComplete: true, autoAccept: false, version: '1.7'
    }
  };

  var prefs = { theme: 'light', accent: 'blue', glow: true, glass: true };

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
  function toast(msg) {
    var box = $('toasts');
    var t = document.createElement('div');
    t.className = 'toast';
    t.textContent = msg;
    box.appendChild(t);
    requestAnimationFrame(function () { t.classList.add('show'); });
    setTimeout(function () {
      t.classList.remove('show');
      setTimeout(function () { try { box.removeChild(t); } catch (e) {} }, 300);
    }, 2200);
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

  function applyTheme() {
    document.body.classList.remove('dark', 'pitch');
    if (prefs.theme === 'dark') document.body.classList.add('dark');
    if (prefs.theme === 'pitch') document.body.classList.add('pitch');
    document.documentElement.setAttribute('data-accent', prefs.accent || 'blue');
    document.documentElement.setAttribute('data-glow', prefs.glow ? 'on' : 'off');
    document.documentElement.setAttribute('data-glass', prefs.glass === false ? 'off' : 'on');
    try { localStorage.setItem('qs_prefs', JSON.stringify(prefs)); } catch (e) {}
    // keep the choice natively too, so the Quick Share panel matches exactly
    if (QS && QS.setSetting) {
      try {
        QS.setSetting('theme', prefs.theme);
        QS.setSetting('accent', prefs.accent || 'blue');
        QS.setSetting('glow', String(!!prefs.glow));
        QS.setSetting('glass', String(prefs.glass !== false));
      } catch (e) {}
    }
    if (QS && QS.theme) {
      var bar = prefs.theme === 'light' ? '#F2F2F2' : (prefs.theme === 'pitch' ? '#000000' : '#161719');
      QS.theme(bar, prefs.theme === 'light');
    }
  }

  /* ---------------------------------------------------------- router */

  var TITLES = {
    home: ['Quick Share', 'Send and receive files nearby'],
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
    // navigation is instant: no whole-page entrance animation (it read as a
    // refresh). Motion lives in the controls, sheets and progress instead.
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
      : '<div class="card"><div class="empty">No devices yet.<br>Open Quick Share on the other device and keep both phones on the same Wi-Fi network.</div></div>';

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
      : '<div class="card"><div class="empty">No devices found yet.<br><br>Both devices must be on the same Wi-Fi network and have Quick Share open.</div></div>';
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

  function viewSettings() {
    var st = S.settings;
    var h = '';
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
      '<div class="setrow"><div class="lmain"><div class="ltitle">Quick Share only runs while it is open</div>' +
      '<div class="lsub" style="white-space:normal">There is no background service. Closing the panel - or leaving this screen - shuts Quick Share down. The one exception is a transfer that is still running: it is kept alive just long enough to finish (with its live progress notification), and then Quick Share stops itself.</div></div>' +
      '</div>' +
      '</div>';

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
      '<div class="setrow"><div class="lmain"><div class="ltitle">Quick Share v' + esc(st.version) + '</div>' +
      '<div class="lsub">A standalone nearby file-sharing client</div></div></div>' +
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
      '<div class="lsub">' + esc(d.ip || '') + (d.model ? ' · ' + esc(d.model) : '') + '</div></div>' +
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
      '<div class="ltitle">Incoming from ' + esc(inc.peer || 'Device') + '</div>' +
      '<div class="lsub">' + (inc.count || 0) + ' file(s) · ' + fmtSize(inc.size || 0) + '</div>' +
      '<div style="margin-top:6px">' + names + '</div>' +
      '<div class="btnrow"><button class="btn danger" data-act="reject" data-id="' + esc(inc.id) + '">Decline</button>' +
      '<button class="btn primary" data-act="accept" data-id="' + esc(inc.id) + '">Accept</button></div>' +
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
      case 'send':
        if (!S.pending.length) { toast('Choose files first'); nav('home'); break; }
        if (QS) { var tid = QS.sendTo(id); if (tid) { toast('Sending…'); nav('transfers'); } }
        break;
      case 'nodev': toast('Choose files first, then tap a device'); break;
      case 'accept': if (QS) QS.accept(id); break;
      case 'reject': if (QS) QS.reject(id); break;
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
      '<p>Quick Share finds other phones on the same Wi-Fi network and moves files directly between them - nothing goes through a server or the internet.</p>' +
      '<p><b>Discovery</b> uses a small broadcast beacon on the local network (UDP port 8890). <b>Transfers</b> use a direct connection (TCP port 8891): the sender offers the files, the receiver accepts, and the files stream across.</p>' +
      '<p><b>Compatibility.</b> This app talks to other copies of this app. It is a standalone client and does <b>not</b> use Google\'s proprietary Quick Share protocol, so it cannot send to or receive from Google Quick Share or Windows Quick Share.</p>' +
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
    } else {
      if (s.theme) prefs.theme = s.theme;
      if (s.accent) prefs.accent = s.accent;
      if (typeof s.glow === 'boolean') prefs.glow = s.glow;
      if (typeof s.glass === 'boolean') prefs.glass = s.glass;
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
