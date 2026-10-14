/* PeerZap panel — the bottom sheet opened from the Quick Settings tile.
   Sends and receives without opening the full app, and has its own file picker
   so choosing files stays inside the panel. Same design language as the app. */
(function () {
  'use strict';

  var QS = window.QuickShare || null;
  var $ = function (id) { return document.getElementById(id); };

  var CATS = [
    { id: 'recent',      label: 'Recent' },
    { id: 'images',      label: 'Images' },
    { id: 'documents',   label: 'Documents' },
    { id: 'audio',       label: 'Audio' },
    { id: 'downloads',   label: 'Downloads' },
    { id: 'screenshots', label: 'Screenshots' },
    { id: 'apk',         label: 'APK' },
    { id: 'archive',     label: 'Archive' },
    { id: 'files',       label: 'Files' },
    { id: 'folder',      label: 'Folder' }
  ];

  var S = { mode: 'send', devices: [], pending: [], incoming: [], transfers: [], localIp: '', running: false,
            picker: false,

            settings: { deviceName: 'This device', visible: true, glass: true } };

  // in-panel file picker state
  var P = { cat: 'recent', path: '', stack: [], list: [], sel: {}, seq: 0, uid: {}, loading: false };

  /* ------------------------------------------------------------- helpers */

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function fmtSize(n) {
    n = Number(n) || 0;
    if (n < 1024) return n + ' B';
    if (n < 1048576) return (n / 1024).toFixed(0) + ' KB';
    if (n < 1073741824) return (n / 1048576).toFixed(n < 10485760 ? 1 : 0) + ' MB';
    return (n / 1073741824).toFixed(2) + ' GB';
  }
  function fmtSpeed(bps) { if (!bps || bps < 1) return ''; return fmtSize(bps) + '/s'; }
  function fmtEta(sec) {
    if (sec == null || sec < 0) return '';
    sec = Math.round(sec);
    if (sec < 60) return sec + 's left';
    return Math.floor(sec / 60) + 'm ' + (sec % 60) + 's left';
  }
  function ext(name) { var i = String(name || '').lastIndexOf('.'); return i >= 0 ? name.slice(i + 1).toLowerCase() : ''; }
  function kindOf(e) {
    if (e.kind === 'dir') return 'folder';
    var x = ext(e.name), m = String(e.mime || '');
    if (m.indexOf('image/') === 0 || ['jpg','jpeg','png','gif','webp','heic','bmp','svg'].indexOf(x) >= 0) return 'image';
    if (m.indexOf('video/') === 0 || ['mp4','mov','mkv','webm','3gp','avi'].indexOf(x) >= 0) return 'video';
    if (m.indexOf('audio/') === 0 || ['mp3','wav','ogg','m4a','flac','aac'].indexOf(x) >= 0) return 'audio';
    if (['zip','rar','7z','tar','gz','xz','bz2','iso'].indexOf(x) >= 0) return 'archive';
    if (x === 'apk') return 'apk';
    if (['pdf','doc','docx','txt','md','ppt','pptx','xls','xlsx','csv','json','xml'].indexOf(x) >= 0) return 'doc';
    return 'file';
  }
  function baseName(p) { var a = String(p || '').split('/').filter(Boolean); return a.length ? a[a.length - 1] : 'Storage'; }
  function catLabel(id) { for (var i = 0; i < CATS.length; i++) if (CATS[i].id === id) return CATS[i].label; return 'Files'; }

  var ICON = {
    device: '<rect x="5" y="2" width="14" height="20" rx="3"/><line x1="12" y1="18" x2="12.01" y2="18"/>',
    folder: '<path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/>',
    image: '<rect x="3" y="3" width="18" height="18" rx="3"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/>',
    video: '<polygon points="23 7 16 12 23 17 23 7"/><rect x="1" y="5" width="15" height="14" rx="2"/>',
    audio: '<path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/>',
    archive: '<polyline points="21 8 21 21 3 21 3 8"/><rect x="1" y="3" width="22" height="5"/><line x1="10" y1="12" x2="14" y2="12"/>',
    apk: '<rect x="4" y="4" width="16" height="16" rx="4"/><line x1="9" y1="12" x2="15" y2="12"/>',
    doc: '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/>',
    file: '<path d="M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"/><polyline points="13 2 13 9 20 9"/>',
    back: '<polyline points="15 18 9 12 15 6"/>',
    chev: '<polyline points="9 18 15 12 9 6"/>',
    check: '<polyline points="20 6 9 17 4 12"/>'
  };
  function svg(name, cls) {
    return '<svg' + (cls ? ' class="' + cls + '"' : '') +
      ' viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
      (ICON[name] || ICON.file) + '</svg>';
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
      // ease-out cubic, so it settles rather than stopping dead
      var e = 1 - Math.pow(1 - k, 3);
      el.textContent = Math.round(from + (to - from) * e) + '%';
      if (k < 1) requestAnimationFrame(step);
    }
    requestAnimationFrame(step);
  }

  function toast(msg) {
    // Toasts are hidden by request: the app no longer pops up confirmations for
    // file selection or anything else. Kept as a function so callers are unchanged.
    return;
  }

  /* The panel uses the same theme, accent and glow as the app. */
  function applyTheme() {
    var st = S.settings || {};
    var theme = st.theme || 'light';
    var accent = st.accent || 'blue';
    var glow = (typeof st.glow === 'boolean') ? st.glow : true;
    document.body.classList.remove('dark', 'pitch');
    if (theme === 'dark') document.body.classList.add('dark');
    if (theme === 'pitch') document.body.classList.add('pitch');
    document.documentElement.setAttribute('data-accent', accent);
    document.documentElement.setAttribute('data-glow', glow ? 'on' : 'off');
    document.documentElement.setAttribute('data-glass',
      (typeof st.glass === 'boolean' ? st.glass : true) ? 'on' : 'off');
  }

  /* --------------------------------------------------------------- views */

  var chipsBuilt = false;
  var lastModeKey = '';

  function chipHtml(c) {
    return '<button class="lblpickb' + (P.cat === c.id ? ' on' : '') + '" data-act="pickcat" data-id="' + c.id + '">' + esc(c.label) + '</button>';
  }

  function render(animate) {
    document.body.classList.toggle('picking', !!S.picker);

    // The tab strip and the category chips are static: only their state changes,
    // so switching Send / Receive no longer rebuilds them - no jump, no flicker.
    var segEl = $('pseg');
    segEl.hidden = !!S.picker;
    var segs = segEl.querySelectorAll('.segb');
    for (var i = 0; i < segs.length; i++) {
      segs[i].classList.toggle('on', segs[i].getAttribute('data-v') === S.mode);
    }
    var ind = $('psegind');
    if (ind) ind.style.transform = 'translateX(' + (S.mode === 'receive' ? '100%' : '0') + ')';

    var chips = $('pchips');
    chips.hidden = !S.picker;
    if (S.picker) {
      if (!chipsBuilt) { chips.innerHTML = CATS.map(chipHtml).join(''); chipsBuilt = true; }
      else {
        var cs = chips.querySelectorAll('[data-act="pickcat"]');
        for (var j = 0; j < cs.length; j++) cs[j].classList.toggle('on', cs[j].getAttribute('data-id') === P.cat);
      }
    } else if (chipsBuilt) { chips.innerHTML = ''; chipsBuilt = false; }

    $('psub').textContent = S.picker
      ? 'Pick files to send'
      : (S.mode === 'send' ? 'Direct. Fast. Effortless.' : 'Direct. Fast. Effortless.');

    var body = $('pbody');
    body.innerHTML = S.picker ? pickerView() : (S.mode === 'send' ? sendView() : receiveView());
  }

  function seg() {
    return '<div class="seg pseg">' +
      '<button class="segb' + (S.mode === 'send' ? ' on' : '') + '" data-act="mode" data-v="send">Send</button>' +
      '<button class="segb' + (S.mode === 'receive' ? ' on' : '') + '" data-act="mode" data-v="receive">Receive</button>' +
      '</div>';
  }

  /* dir 'send' or 'recv'; omit for either. A receive must never show under Send. */
  function activeTransfers(dir) {
    return (S.transfers || []).filter(function (t) {
      var live = t.status === 'active' || t.status === 'waiting';
      if (!live) return false;
      if (!dir) return true;
      var d = t.dir === 'recv' ? 'recv' : 'send';
      return d === dir;
    });
  }

  function progressCard(t) {
    var pct = t.size > 0 ? Math.min(100, Math.round((t.done || 0) / t.size * 100)) : 0;
    return '<div class="ptprog" data-tid="' + esc(t.id) + '">' +
      '<span class="ring" style="--p:' + pct + '"><b>' + pct + '%</b></span>' +
      '<div class="lmain"><div class="ltitle">' + (t.dir === 'send' ? 'Sending to ' : 'Receiving from ') +
      esc(t.peer || 'device') + '</div>' +
      '<div class="lsub tsize">' + (t.count || 0) + ' file(s) · ' + fmtSize(t.done || 0) + ' of ' + fmtSize(t.size || 0) + '</div>' +
      '<div class="lsub tmeta">' + (fmtSpeed(t.speed) ? fmtSpeed(t.speed) + ' · ' : '') + fmtEta(t.eta) + '</div></div>' +
      '</div><div class="upbar"><i style="width:' + pct + '%"></i></div>';
  }

  /** Live progress for any running transfer, shown in both Send and Receive. */
  function progressHtml() {
    var a = activeTransfers('recv');
    return a.length ? a.map(progressCard).join('') : '';
  }

  /** Updates the rings/bars in place so they ease instead of rebuilding. */
  function updateProgressInPlace() {
    var a = activeTransfers();
    var nodes = document.querySelectorAll('#pbody [data-tid]');
    if (!a.length || nodes.length !== a.length) return false;
    var byId = {};
    a.forEach(function (t) { byId[t.id] = t; });
    for (var i = 0; i < nodes.length; i++) {
      if (!byId[nodes[i].getAttribute('data-tid')]) return false;
    }
    a.forEach(function (t) {
      var n = document.querySelector('#pbody [data-tid="' + t.id + '"]');
      if (!n) return;
      var pct = t.size > 0 ? Math.min(100, Math.round((t.done || 0) / t.size * 100)) : 0;
      var ring = n.querySelector('.ring');
      if (ring) {
        ring.style.setProperty('--p', pct);
        setPct(ring.querySelector('b'), pct);
      }
      var bar = n.querySelector('.upbar i');
      if (bar) bar.style.width = pct + '%';
      var ti = n.querySelector('.ltitle');
      if (ti) ti.textContent = (t.dir === 'send' ? 'Sending to ' : 'Receiving from ') + (t.peer || 'device');
      var sz = n.querySelector('.tsize');
      if (sz) sz.textContent = (t.count || 0) + ' file(s) · ' + fmtSize(t.done || 0) + ' of ' + fmtSize(t.size || 0);
      var meta = n.querySelector('.tmeta');
      if (meta) meta.textContent = (fmtSpeed(t.speed) ? fmtSpeed(t.speed) + ' · ' : '') + fmtEta(t.eta);
    });
    return true;
  }

  function sendView() {
    // once the receiver has accepted, the send shows the same centred ring as a
    // receive - the card at the top is replaced, not duplicated
    var sending = activeTransfers('send');
    // only a send belongs here; progressHtml() renders receives and must not be
    // used as a fallback, or an inbound transfer shows up under Send
    var h = sending.length ? centeredProgress(sending[0]) : '';
    if (S.pending.length) {
      var total = S.pending.reduce(function (a, f) { return a + (Number(f.size) > 0 ? Number(f.size) : 0); }, 0);
      h += '<div class="pbadge">' + svg('file') +
        S.pending.length + ' file(s) · ' + (total ? fmtSize(total) : 'size unknown') + '</div>' +
        '<div class="pact"><button class="btn ghost" data-act="clear">Clear</button>' +
        '<button class="btn ghost" data-act="pick">Add files</button></div>';
    } else {
      h += '<div class="pempty">Pick files, then tap a nearby device to send them.</div>' +
        '<div class="pact"><button class="btn primary" data-act="pick">Choose files</button></div>';
    }
    h += '<div class="sect" style="margin:18px 2px 6px">Nearby devices' +
      (S.devices.length ? ' · ' + S.devices.length : '') + '</div>';
    if (S.devices.length) {
      h += S.devices.map(function (d) {
        return '<div class="pdev" data-act="send" data-id="' + esc(d.id) + '">' +
          '<span class="devav">' + svg('device') + '</span>' +
          '<div class="lmain"><div class="ltitle">' + esc(d.name || 'Device') + '</div>' +
          '<div class="lsub">' + esc(d.ip || '') + (d.direct ? ' · Direct link' : '') + (d.stock ? ' · Quick Share' : '') + '</div></div>' +
          '<span class="chip" style="' + (S.pending.length ? '' : 'opacity:.55') + '">' + (S.pending.length ? 'Send' : '—') + '</span></div>';
      }).join('');
    } else {
      h += '<div class="pempty">' + (S.running ? 'Scanning the network…' : 'Starting…') +
        '<br>Make sure the other device has PeerZap open on the same Wi-Fi.</div>';
    }
    return h;
  }

  /* While receiving: one big centred ring and the file name, nothing else. */
  function centeredProgress(t) {
    var pct = t.size > 0 ? Math.min(100, Math.round((t.done || 0) / t.size * 100)) : 0;
    return '<div class="centerprog centerprog-in">' +
      '<span class="ring bigring" style="--p:' + pct + '"><b data-pct="' + pct + '">' + pct + '%</b></span>' +
      '<div class="lsub" style="margin-top:14px;text-align:center">' +
        (t.dir === 'send' ? 'Sending to ' : 'Receiving from ') + esc(t.peer || 'device') + '</div>' +
      '<div class="lsub" style="margin-top:4px;text-align:center;white-space:normal">' +
        esc(t.current || '') + '</div>' +
      '</div>';
  }

  function receiveView() {
    // A request waiting on the user's answer shows only the card - no progress, since
    // nothing is being received yet.
    var pending = (S.incoming || []).some(function (i) { return !i.auto; });
    var active = activeTransfers('recv');
    var h = (pending || !active.length) ? '' : centeredProgress(active[0]);

    h += '<div class="pbadge" style="margin-top:10px">' +
      (S.running ? '<span class="pulse" style="width:8px;height:8px;display:inline-block"></span>' : '') +
      (S.running ? 'Ready to receive' : 'Starting…') + '</div>' +
      '<div class="psub" style="margin:8px 2px 0">' + esc(S.settings.deviceName || 'This device') +
      (S.localIp ? ' · ' + esc(S.localIp) : '') + '</div>';

    h += '<div class="sect" style="margin:18px 2px 6px">Incoming requests</div>';
    h += S.incoming.length
      ? S.incoming.map(function (inc) {
          var names = (inc.files || []).slice(0, 4).map(function (n) {
            return '<div class="lsub" style="white-space:normal">' + esc(n) + '</div>';
          }).join('');
          return '<div class="pdev reqcard" style="flex-direction:column;align-items:stretch">' +
            (inc.pin && !inc.auto
              ? '<div class="pinbig">PIN ' + esc(inc.pin) + '</div>' : '') +
            '<div class="lsub" style="text-align:center">' + (inc.count || 0) + ' file(s)</div>' +
            '<div style="margin-top:6px;text-align:center">' + names + '</div>' +
            (inc.auto
              ? '<div class="pact"><span class="chip">Receiving\u2026</span></div>'
              : '<div class="pact"><button class="btn danger" data-act="reject" data-id="' + esc(inc.id) + '">Decline</button>' +
                '<button class="btn primary" data-act="accept" data-id="' + esc(inc.id) + '">Accept</button></div>') +
            '</div>';
        }).join('')
      : '<div class="pempty">Nothing waiting. Keep this panel open, or leave the app running, to receive files.</div>';
    return h;
  }

  /* ----------------------------------------------------- in-panel picker */

  function uidOf(e) {
    // uri + name so two entries can never collide, even if a uri repeats
    var key = (e.uri || e.path || '') + '|' + (e.name || '');
    if (P.uid[key] == null) P.uid[key] = key;
    return key;
  }
  function selCount() { var n = 0; for (var k in P.sel) if (P.sel[k]) n++; return n; }

  function pickerView() {
    var h = '<div class="pkhead">' +
      '<button class="iconbtn" data-act="pickback" aria-label="Back">' + svg('back') + '</button>' +
      '<div class="pktitle">' + esc(P.cat === 'folder' && P.path ? baseName(P.path) : catLabel(P.cat)) + '</div>' +
      '<button class="btn primary sm" data-act="pickadd"' + (selCount() ? '' : ' disabled') + '>Add' + (selCount() ? ' (' + selCount() + ')' : '') + '</button>' +
      '</div>';

    if (P.loading && !P.list.length) {
      return h + '<div class="skelwrap"><div class="skel lead"></div><div class="skel"></div>' +
        '<div class="skel"></div><div class="skel"></div><div class="skel"></div></div>' + pkFoot();
    }

    if (!P.list.length) {
      return h + '<div class="pempty">' +
        (P.cat === 'folder' && !P.path ? 'Choose a storage location.' : 'Nothing here to send.') +
        '</div>' + pkFoot();
    }

    h += '<div class="pklist' + (P.loading ? ' busy' : '') + '">' + P.list.map(pkRow).join('') + '</div>';
    return h + pkFoot();
  }

  function pkFoot() {
    return '<div class="pkfoot">' +
      '<button class="btn ghost sm" data-act="picksystem">System picker</button>' +
      '<span class="pksel">' + selCount() + ' selected</span></div>';
  }

  function pkRow(e) {
    if (e.kind === 'dir') {
      return '<div class="pkrow" data-act="pickdir" data-path="' + esc(e.path) + '">' +
        '<span class="devav">' + svg('folder') + '</span>' +
        '<div class="lmain"><div class="ltitle">' + esc(e.name) + '</div>' +
        '<div class="lsub">' + (e.removable ? 'SD card / removable storage' : 'Folder') + '</div></div>' +
        svg('chev', 'chev') + '</div>';
    }
    var u = uidOf(e);
    var on = !!P.sel[u];
    return '<div class="pkrow' + (on ? ' on' : '') + '" data-act="picktoggle" data-u="' + esc(u) + '">' +
      '<span class="devav">' + svg(kindOf(e)) + '</span>' +
      '<div class="lmain"><div class="ltitle">' + esc(e.name) + '</div>' +
      '<div class="lsub">' + fmtSize(e.size) + '</div></div>' +
      '<span class="pkcheck">' + svg('check') + '</span></div>';
  }

  function openPicker() {
    S.picker = true;
    P.cat = 'recent'; P.path = ''; P.stack = []; P.list = []; P.sel = {}; P.uid = {}; chipsBuilt = false;
    render();
    pkLoad();
  }

  function pkLoad() {
    P.loading = true;
    render();
    setTimeout(function () {
      try {
        if (P.cat === 'folder') {
          if (!P.path) {
            var roots = JSON.parse((QS && QS.storageRoots && QS.storageRoots()) || '[]');
            P.list = roots.map(function (r) { return { kind: 'dir', name: r.name, path: r.path, removable: !!r.removable }; });
          } else {
            P.list = JSON.parse((QS && QS.listFolder && QS.listFolder(P.path)) || '[]');
          }
        } else {
          P.list = JSON.parse((QS && QS.listFiles && QS.listFiles(P.cat)) || '[]')
            .filter(function (e) { return e.kind !== 'dir'; });
        }
      } catch (e) { P.list = []; }
      P.loading = false;
      render();
    }, 20);
  }

  function pkAdd() {
    var out = [];
    for (var k in P.sel) if (P.sel[k]) out.push(P.sel[k]);
    if (!out.length) { toast('Select some files first'); return; }
    var payload = out.map(function (e) {
      return { uri: e.uri || e.path || '', path: e.path || '', name: e.name, size: e.size || 0, mime: e.mime || '' };
    });
    if (QS && QS.pickRefs) QS.pickRefs(JSON.stringify(payload));
    S.picker = false;
    S.mode = 'send';
    render();
  }

  /* -------------------------------------------------------------- actions */

  function onAction(act, el) {
    var id = el.getAttribute('data-id');
    var v = el.getAttribute('data-v');
    var path = el.getAttribute('data-path');
    var u = el.getAttribute('data-u');
    switch (act) {
      case 'mode': S.picker = false; S.mode = v; render(true); break;
      case 'pick': openPicker(); break;
      case 'pickback':
        if (P.cat === 'folder' && P.path) {
          // walk back up the stack we came down; empty means the storage roots
          P.stack.pop();
          P.path = P.stack.length ? P.stack[P.stack.length - 1] : '';
          pkLoad();
        } else { S.picker = false; render(true); }
        break;
      case 'pickcat':
        P.cat = id; P.path = ''; P.stack = []; P.sel = {}; pkLoad(); break;
      case 'pickdir': P.path = path; P.stack.push(path); pkLoad(); break;
      case 'picktoggle':
        if (P.sel[u]) delete P.sel[u];
        else {
          for (var i = 0; i < P.list.length; i++) {
            var e = P.list[i];
            if (e.kind !== 'dir' && uidOf(e) === u) { P.sel[u] = e; break; }
          }
        }
        render(); break;
      case 'pickadd': pkAdd(); break;
      case 'picksystem': if (QS && QS.pickFiles) QS.pickFiles(); S.picker = false; render(); break;
      case 'clear': if (QS) QS.clearPending(); S.pending = []; render(); break;
      case 'send': {
        if (!S.pending.length) { toast('Choose files first'); S.mode = 'send'; render(); break; }
        var dev = null;
        for (var di = 0; di < S.devices.length; di++) {
          if (S.devices[di].id === id) { dev = S.devices[di]; break; }
        }
        var isStock = dev && (dev.stock === true || String(dev.id || '').indexOf('qs:') === 0);
        if (isStock) {
          var r = QS.qsSendTo(id);
          if (r === 'started') { toast('Sending to ' + (dev.name || 'device') + '…'); S.pending = []; S.mode = 'send'; render(); }
          else if (r === 'no-files') toast('Choose files first');
          else if (r === 'not-found') toast('That device is no longer nearby');
          else if (r === 'no-address') toast('Could not reach that device');
          else toast('Could not send (' + r + ')');
          break;
        }
        if (String(id || '').indexOf('p2p:') === 0) {
          var r = QS.qsDirectSend(id);
          if (r === 'linking') { toast('Linking directly…'); S.pending = []; S.mode = 'send'; render(); }
          else if (r === 'no-files') toast('Choose files first');
          else if (r === 'not-found') toast('That device is no longer nearby');
          else toast('Could not link (' + r + ')');
          break;
        }
        if (QS && QS.sendTo(id)) { toast('Sending…'); S.pending = []; S.mode = 'send'; render(); }
        else toast('That device is not available for a direct send');
        break;
      }
      case 'accept':
        if (id === 'qsconsent') { if (QS) QS.qsConsentAccept(); }
        else if (QS) QS.accept(id);
        break;
      case 'reject':
        if (id === 'qsconsent') { if (QS) QS.qsConsentReject(); }
        else if (QS) QS.reject(id);
        break;
      case 'visible':
        S.settings.visible = !S.settings.visible;
        if (QS) QS.setSetting('visible', String(S.settings.visible));
        render(); break;
      case 'close': if (QS) QS.closePanel(); break;
    }
  }

  window.qsEvent = function (name, payload) {
    try {
      if (name === 'devices') { S.devices = payload || []; render(); }
      else if (name === 'picked') {
        S.pending = payload || [];
        S.picker = false;
        S.mode = 'send';
        render();          // no toast: the queued badge is the feedback
      }
      else if (name === 'incoming') {
        var inc = payload;
        if (inc && !S.incoming.some(function (x) { return x.id === inc.id; })) {
          S.incoming.push(inc);
          S.picker = false;
          S.mode = 'receive';
          S.transfers = (S.transfers || []);
        }
        toast('Incoming files from ' + ((inc && inc.peer) || 'a device'));
        render();
      }
      else if (name === 'transfers') {
        S.transfers = payload || [];
        if (!updateProgressInPlace()) render();
      }
      else if (name === 'toast') { toast(String(payload || '')); }
    } catch (e) {}
  };

  window.qsCloseAnim = function () {
    document.body.classList.add('panel-closing');
  };

  var firstFetch = true;
  function refresh() {
    if (!QS || !QS.getState) return;
    try {
      var st = JSON.parse(QS.getState() || '{}');
      S.devices = st.devices || [];
      S.incoming = st.incoming || [];
      S.transfers = st.transfers || [];
      S.localIp = st.localIp || '';
      S.running = !!st.running;
      if (st.settings) S.settings = Object.assign(S.settings, st.settings);
      if (st.pending && st.pending.length && !S.pending.length) S.pending = st.pending;
      // A new request switches the panel to Receive by itself, so the user does not have
      // to go and tap the tab - but only when they are not mid-send. The compat path
      // publishes into getState rather than pushing an event, so this has to be noticed
      // here rather than in the event handler.
      var inc = st.incoming || [];
      var fresh = inc.some(function (i) {
        return !(S.incoming || []).some(function (x) { return x.id === i.id; });
      });
      var sending = (S.pending || []).length > 0 || activeTransfers('send').length > 0;
      if ((firstFetch || fresh) && inc.length && !sending) {
        S.mode = 'receive';
      }
      if (firstFetch) firstFetch = false;
    } catch (e) {}
    applyTheme();
  }

  function boot() {
    // opened because something is arriving? land on Receive, not Send
    try { if (S.incoming && S.incoming.length) S.mode = 'receive'; } catch (e) {}
    $('pscrim').addEventListener('click', function () { if (QS) QS.closePanel(); });
    $('pclose').addEventListener('click', function () { if (QS) QS.closePanel(); });
    document.addEventListener('click', function (e) {
      var a = e.target.closest('[data-act]');
      if (a) { e.preventDefault(); onAction(a.getAttribute('data-act'), a); }
    });

    refresh();
    render();
    if (QS) { try { QS.startDiscovery(); } catch (e) {} }
    setInterval(function () {
      if (document.hidden) return;
      var b = JSON.stringify([S.devices, S.incoming, S.pending]);
      var tb = JSON.stringify(S.transfers);
      refresh();
      if (JSON.stringify(S.transfers) !== tb && !updateProgressInPlace()) render();
      else if (JSON.stringify([S.devices, S.incoming, S.pending]) !== b) render();
    }, 1500);
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();
