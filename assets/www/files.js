/* Built-in file manager — same design language as the rest of the app.
   Categories: Recent, Images, Documents, Audio, Downloads, Screenshots, Apps,
   APK, Archive, Files, Folder, Contacts. */
(function () {
  'use strict';

  var QS = window.QuickShare || null;
  var $ = function (id) { return document.getElementById(id); };

  var CATS = [
    { id: 'recent',      label: 'Recent',      icon: 'clock' },
    { id: 'images',      label: 'Images',      icon: 'image' },
    { id: 'documents',   label: 'Documents',   icon: 'doc' },
    { id: 'audio',       label: 'Audio',       icon: 'audio' },
    { id: 'downloads',   label: 'Downloads',   icon: 'download' },
    { id: 'screenshots', label: 'Screenshots', icon: 'camera' },
    { id: 'apps',        label: 'Apps',        icon: 'apps' },
    { id: 'apk',         label: 'APK',         icon: 'apk' },
    { id: 'archive',     label: 'Archive',     icon: 'archive' },
    { id: 'files',       label: 'Files',       icon: 'file' },
    { id: 'folder',      label: 'Folder',      icon: 'folder' },
    { id: 'contacts',    label: 'Contacts',    icon: 'contact' }
  ];

  var F = {
    counts: null,
    list: [],
    key: '',          // route the list belongs to
    q: '',
    loading: false,
    allowed: false,
    contactsAllowed: false,
    path: '',
    crumbs: []
  };

  /* ------------------------------------------------------------- helpers */

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
  function timeAgo(ms) {
    if (!ms) return '';
    var d = Date.now() - ms;
    if (d < 60000) return 'just now';
    if (d < 3600000) return Math.floor(d / 60000) + 'm ago';
    if (d < 86400000) return Math.floor(d / 3600000) + 'h ago';
    if (d < 2592000000) return Math.floor(d / 86400000) + 'd ago';
    try { return new Date(ms).toLocaleDateString(); } catch (e) { return ''; }
  }
  function ext(name) { var i = String(name || '').lastIndexOf('.'); return i >= 0 ? name.slice(i + 1).toLowerCase() : ''; }
  function kindOf(e) {
    if (e.kind === 'dir') return 'folder';
    if (e.kind === 'app') return 'apps';
    if (e.kind === 'contact') return 'contact';
    var x = ext(e.name), m = String(e.mime || '');
    if (m.indexOf('image/') === 0 || ['jpg','jpeg','png','gif','webp','heic','bmp','svg'].indexOf(x) >= 0) return 'image';
    if (m.indexOf('video/') === 0 || ['mp4','mov','mkv','webm','3gp','avi'].indexOf(x) >= 0) return 'video';
    if (m.indexOf('audio/') === 0 || ['mp3','wav','ogg','m4a','flac','aac'].indexOf(x) >= 0) return 'audio';
    if (['zip','rar','7z','tar','gz','xz','bz2','iso'].indexOf(x) >= 0) return 'archive';
    if (x === 'apk') return 'apk';
    if (['pdf','doc','docx','txt','md','ppt','pptx','xls','xlsx','csv','json','xml','rtf','epub'].indexOf(x) >= 0) return 'doc';
    return 'file';
  }

  var ICON = {
    clock: '<circle cx="12" cy="12" r="9"/><polyline points="12 7 12 12 15 14"/>',
    image: '<rect x="3" y="3" width="18" height="18" rx="3"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/>',
    doc: '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/>',
    audio: '<path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/>',
    download: '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/>',
    camera: '<path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z"/><circle cx="12" cy="13" r="4"/>',
    apps: '<rect x="3" y="3" width="7" height="7" rx="2"/><rect x="14" y="3" width="7" height="7" rx="2"/><rect x="3" y="14" width="7" height="7" rx="2"/><rect x="14" y="14" width="7" height="7" rx="2"/>',
    apk: '<rect x="4" y="4" width="16" height="16" rx="4"/><line x1="9" y1="12" x2="15" y2="12"/>',
    archive: '<polyline points="21 8 21 21 3 21 3 8"/><rect x="1" y="3" width="22" height="5"/><line x1="10" y1="12" x2="14" y2="12"/>',
    file: '<path d="M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"/><polyline points="13 2 13 9 20 9"/>',
    folder: '<path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/>',
    contact: '<path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/>',
    video: '<polygon points="23 7 16 12 23 17 23 7"/><rect x="1" y="5" width="15" height="14" rx="2"/>',
    up: '<line x1="12" y1="19" x2="12" y2="5"/><polyline points="5 12 12 5 19 12"/>'
  };
  function svg(name) {
    return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' + (ICON[name] || ICON.file) + '</svg>';
  }
  function catMeta(id) {
    for (var i = 0; i < CATS.length; i++) if (CATS[i].id === id) return CATS[i];
    return { id: id, label: id, icon: 'file' };
  }

  /* --------------------------------------------------------------- views */

  function view(route) {
    if (route === 'files') return overview();
    var rest = route.slice('files/'.length);
    if (rest === 'folder') return folderView();
    return listView(rest);
  }

  function permCard() {
    if (F.allowed) return '';
    return '<div class="card" style="border:1px solid var(--accent)">' +
      '<div class="ltitle">Allow access to all files</div>' +
      '<div class="lsub" style="white-space:normal;margin-top:6px">To browse everything on this device — images, documents, audio, APKs, archives and folders — Quick Share needs the “All files access” permission. Nothing is uploaded anywhere; the files stay on your device.</div>' +
      '<div class="btnrow"><button class="btn primary" data-act="allow">Open permission</button></div>' +
      '</div>';
  }

  function overview() {
    var h = permCard();
    h += '<div class="sect">Browse</div>';
    h += '<div class="catgrid">';
    CATS.forEach(function (c) {
      var n = F.counts ? F.counts[c.id] : null;
      h += '<button class="cattile" data-act="cat" data-id="' + c.id + '">' +
        '<span class="catic">' + svg(c.icon) + '</span>' +
        '<span class="catl">' + esc(c.label) + '</span>' +
        (n == null ? '' : '<span class="catn">' + n + '</span>') +
        '</button>';
    });
    h += '</div>';
    h += '<div class="sect">Storage</div>';
    h += '<div class="card list" id="roots"><div class="empty">Loading…</div></div>';
    h += '<p class="tiny" style="text-align:left">Apps, Contacts and Folder open their own views. Tapping any file lets you open, share or send it with Quick Share.</p>';
    return h;
  }

  function listView(cat) {
    var c = catMeta(cat);
    var h = '';
    if (cat === 'contacts' && !F.contactsAllowed) {
      h += '<div class="card" style="border:1px solid var(--accent)">' +
        '<div class="ltitle">Allow access to contacts</div>' +
        '<div class="lsub" style="white-space:normal;margin-top:6px">To show your contacts here, Quick Share needs the Contacts permission.</div>' +
        '<div class="btnrow"><button class="btn primary" data-act="allowcontacts">Allow contacts</button></div></div>';
    }
    h += '<div class="searchbar" style="margin-top:2px">' +
      '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="7"/><line x1="21" y1="21" x2="16.5" y2="16.5"/></svg>' +
      '<input id="fsearch" placeholder="Search ' + esc(c.label) + '" value="' + esc(F.q) + '" autocomplete="off">' +
      '<button class="iconbtn" data-act="reload" aria-label="Refresh"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/></svg></button>' +
      '</div>';

    if (F.loading && !F.list.length) {
      return h + '<div class="skelwrap"><div class="skel lead"></div><div class="skel"></div>' +
        '<div class="skel"></div><div class="skel"></div><div class="skel"></div><div class="skel"></div></div>';
    }
    return h + '<div id="flist"' + (F.loading ? ' class="busy"' : '') + '>' + listBody() + '</div>';
  }

  function listBody() {
    var items = F.list;
    if (F.q) {
      var q = F.q.toLowerCase();
      items = items.filter(function (e) { return String(e.name || '').toLowerCase().indexOf(q) >= 0; });
    }
    if (!items.length) {
      return '<div class="card"><div class="empty">' +
        (F.q ? 'Nothing matches \u201c' + esc(F.q) + '\u201d.' : 'Nothing here yet.') + '</div></div>';
    }
    return '<div class="ntbar"><span>' + items.length + ' item' + (items.length === 1 ? '' : 's') + '</span></div>' +
      '<div class="card list">' + items.map(row).join('') + '</div>';
  }

  function filter(q) {
    F.q = q || '';
    var box = document.getElementById('flist');
    if (box) box.innerHTML = listBody();
  }

  function folderView() {
    var h = '';
    if (!F.allowed) h += permCard();
    h += '<div class="crumb" id="crumb">' + crumbHtml() + '</div>';
    if (F.loading && !F.list.length) {
      return h + '<div class="skelwrap"><div class="skel lead"></div><div class="skel"></div>' +
        '<div class="skel"></div><div class="skel"></div></div>';
    }
    if (!F.path) {
      return h + '<div class="card list" id="roots"><div class="empty">Loading…</div></div>';
    }
    h += '<div class="ntbar"><span>' + F.list.length + ' item' + (F.list.length === 1 ? '' : 's') + '</span>' +
      '<span class="ntbar-acts"><button class="btn ghost sm" data-act="up">Up</button></span></div>';
    h += F.list.length
      ? '<div class="card list">' + F.list.map(row).join('') + '</div>'
      : '<div class="card"><div class="empty">This folder is empty or not readable.</div></div>';
    return h;
  }

  function crumbHtml() {
    if (!F.path) return 'Storage';
    var parts = F.path.split('/').filter(Boolean);
    if (parts[0] === 'storage') parts = parts.slice(1);   // avoid "Storage › storage › …"
    var acc = F.path.indexOf('/storage/') === 0 ? '/storage' : '';
    if (acc) parts = F.path.slice('/storage'.length).split('/').filter(Boolean);
    var out = '<span class="crmb" data-act="croot">Storage</span>';
    parts.forEach(function (p) {
      acc += '/' + p;
      out += '<span class="crmb-sep">›</span><span class="crmb" data-act="cdir" data-path="' + esc(acc) + '">' + esc(p) + '</span>';
    });
    return out;
  }

  function row(e) {
    var k = kindOf(e);
    if (e.kind === 'contact') {
      var initials = String(e.name || '?').trim().charAt(0).toUpperCase();
      return '<div class="lrow" data-act="contactsheet" data-i="' + esc(e.name) + '" data-n="' + esc(e.number) + '">' +
        '<span class="cavt">' + esc(initials) + '</span>' +
        '<div class="lmain"><div class="ltitle">' + esc(e.name) + '</div>' +
        '<div class="lsub">' + esc(e.number || 'No number') + '</div></div>' +
        '<button class="iconbtn" data-act="dial" data-n="' + esc(e.number) + '" aria-label="Call">' +
        '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 16.9v3a2 2 0 0 1-2.2 2 19.8 19.8 0 0 1-8.6-3 19.5 19.5 0 0 1-6-6A19.8 19.8 0 0 1 2.1 4.2 2 2 0 0 1 4.1 2h3a2 2 0 0 1 2 1.7c.1.9.3 1.8.6 2.6a2 2 0 0 1-.5 2.1L8.1 9.6a16 16 0 0 0 6 6l1.2-1.1a2 2 0 0 1 2.1-.5c.8.3 1.7.5 2.6.6a2 2 0 0 1 1.7 2z"/></svg></button></div>';
    }
    if (e.kind === 'app') {
      return '<div class="lrow" data-act="appsheet" data-p="' + esc(e.package) + '" data-name="' + esc(e.name) + '">' +
        '<span class="devav">' + svg('apps') + '</span>' +
        '<div class="lmain"><div class="ltitle">' + esc(e.name) + '</div>' +
        '<div class="lsub">' + esc(e.package) + (e.version ? ' · v' + esc(e.version) : '') + '</div>' +
        '<div class="lbody">' + (e.size ? fmtSize(e.size) : '') + (e.system ? ' · System' : '') + '</div></div>' +
        '</div>';
    }
    var sub = (e.kind === 'dir') ? 'Folder'
      : (fmtSize(e.size) + (e.date ? ' · ' + timeAgo(e.date) : ''));
    return '<div class="lrow" data-act="filesheet" data-i="' + esc(uidOf(e)) + '">' +
      '<span class="devav ' + (e.kind === 'dir' ? 'dir' : '') + '">' + svg(k) + '</span>' +
      '<div class="lmain"><div class="ltitle">' + esc(e.name) + '</div>' +
      '<div class="lsub">' + esc(sub) + '</div></div>' +
      '</div>';
  }

  var uidSeq = 0, uidMap = {};
  function uidOf(e) {
    for (var k in uidMap) if (uidMap[k] === e) return k;
    var id = 'f' + (uidSeq++);
    uidMap[id] = e;
    return id;
  }
  function byUid(id) { return uidMap[id]; }

  /* ------------------------------------------------------------- loading */

  function refreshPerms() {
    try { F.allowed = QS && QS.hasAllFilesAccess ? !!QS.hasAllFilesAccess() : false; } catch (e) { F.allowed = false; }
    try { F.contactsAllowed = QS && QS.hasContactsAccess ? !!QS.hasContactsAccess() : false; } catch (e) { F.contactsAllowed = false; }
  }

  function after(route) {
    var wasAllowed = F.allowed;
    refreshPerms();
    if (F.allowed && !wasAllowed) { F.key = ''; F.counts = null; }   // access was just granted
    if (route === 'files') {
      if (F.counts == null) {
        setTimeout(function () {
          try { F.counts = JSON.parse((QS && QS.fileCounts && QS.fileCounts()) || '{}'); } catch (e) { F.counts = {}; }
          rerender();
        }, 20);
      }
      loadRoots();
      return;
    }
    var rest = route.slice('files/'.length);
    if (rest === 'folder') {
      if (!F.path) loadRoots();
      else if (F.key !== route) loadFolder(F.path, route);
      return;
    }
    if (F.key !== route) loadCategory(rest, route);
  }

  function loadCategory(cat, route) {
    F.key = route; F.loading = true; F.q = '';
    rerender();
    setTimeout(function () {
      try { F.list = JSON.parse((QS && QS.listFiles && QS.listFiles(cat)) || '[]'); } catch (e) { F.list = []; }
      F.loading = false;
      rerender();
    }, 20);
  }

  function loadFolder(path, route) {
    F.key = route; F.loading = true; F.path = path;
    F.crumbs = path.split('/').filter(Boolean);
    rerender();
    setTimeout(function () {
      try { F.list = JSON.parse((QS && QS.listFolder && QS.listFolder(path)) || '[]'); } catch (e) { F.list = []; }
      F.loading = false;
      rerender();
    }, 20);
  }

  function loadRoots() {
    setTimeout(function () {
      try {
        var roots = JSON.parse((QS && QS.storageRoots && QS.storageRoots()) || '[]');
        var box = $('roots');
        if (box) {
          box.innerHTML = roots.length
            ? roots.map(function (r) {
                return '<div class="lrow" data-act="root" data-path="' + esc(r.path) + '">' +
                  '<span class="devav">' + svg('folder') + '</span>' +
                  '<div class="lmain"><div class="ltitle">' + esc(r.name) + '</div>' +
                  '<div class="lsub">' + esc(r.path) + '</div></div>' +
                  (r.removable ? '<span class="chip">SD card</span>' : '') + '</div>';
              }).join('')
            : '<div class="empty">No storage found.</div>';
        }
      } catch (e) {}
    }, 20);
  }

  function rerender() {
    if (window.qsRerender) window.qsRerender();
    else if (window.qsNav) window.qsNav(window.qsRoute ? window.qsRoute() : 'files');
  }

  /* -------------------------------------------------------------- actions */

  function action(act, el) {
    var id = el.getAttribute('data-id');
    var path = el.getAttribute('data-path');
    var pkg = el.getAttribute('data-p');
    var num = el.getAttribute('data-n');
    switch (act) {
      case 'allow':
        if (QS && QS.requestAllFilesAccess) QS.requestAllFilesAccess();
        toast('Grant “All files access”, then come back');
        break;
      case 'allowcontacts':
        if (QS && QS.requestContactsAccess) QS.requestContactsAccess();
        break;
      case 'cat': if (window.qsNav) window.qsNav('files/' + id); break;
      case 'reload':
        if (F.path) loadFolder(F.path, 'files/folder');
        else loadCategory(F.key.slice('files/'.length), F.key);
        break;
      case 'root': loadFolder(path, 'files/folder'); break;
      case 'cdir': loadFolder(path, 'files/folder'); break;
      case 'croot': F.path = ''; F.list = []; F.key = 'files/folder'; rerender(); break;
      case 'up':
        var parent = F.path.split('/').slice(0, -1).join('/');
        if (parent.length < 8) { F.path = ''; F.key = 'files/folder'; rerender(); }
        else loadFolder(parent, 'files/folder');
        break;
      case 'filesheet': fileSheet(byUid(el.getAttribute('data-i'))); break;
      case 'openfile': var fe = byUid(el.getAttribute('data-i')); if (fe && QS) QS.openRef(refOf(fe)); break;
      case 'sharefile': var fs = byUid(el.getAttribute('data-i')); if (fs && QS) QS.shareRef(refOf(fs), fs.mime, fs.name); break;
      case 'sendfile':
        var fd = byUid(el.getAttribute('data-i'));
        if (fd && QS) { QS.sendRef(refOf(fd), fd.mime, fd.name); toast('Added to Quick Share'); if (window.qsNav) window.qsNav('devices'); }
        break;
      case 'appsheet': appSheet(pkg, el.getAttribute('data-name')); break;
      case 'openapp': if (QS) QS.openApp(pkg); break;
      case 'appinfo': if (QS) QS.appInfo(pkg); break;
      case 'shareapp': if (QS) QS.shareAppApk(pkg); toast('Preparing APK…'); break;
      case 'contactsheet': contactSheet(el.getAttribute('data-i'), num); break;
      case 'dial': if (QS && num) QS.dialNumber(num); break;
      case 'copynum':
        if (QS && QS.copy) QS.copy(num); toast('Number copied');
        break;
    }
  }

  function refOf(e) { return e.uri || e.path || ''; }

  function fileSheet(e) {
    if (!e) return;
    var isDir = e.kind === 'dir';
    var rows = '';
    if (!isDir) {
      rows = '<div class="btncol">' +
        '<button class="btn primary btnblock" data-act="openfile" data-i="' + esc(uidOf(e)) + '">Open</button>' +
        '<button class="btn ghost btnblock" data-act="sendfile" data-i="' + esc(uidOf(e)) + '">Send with Quick Share</button>' +
        '<button class="btn ghost btnblock" data-act="sharefile" data-i="' + esc(uidOf(e)) + '">Share with another app</button>' +
        '</div>';
    } else {
      rows = '<div class="btncol"><button class="btn primary btnblock" data-act="cdir" data-path="' + esc(e.path) + '">Open folder</button></div>';
    }
    window.qsSheet(
      '<div class="sheethead"><b>' + esc(e.name) + '</b>' +
      '<button class="iconbtn" data-act="closesheet">✕</button></div>' +
      '<div class="chips"><span class="chip">' + (isDir ? 'Folder' : fmtSize(e.size)) + '</span>' +
      (e.date ? '<span class="chip">' + timeAgo(e.date) + '</span>' : '') +
      (e.mime ? '<span class="chip">' + esc(e.mime) + '</span>' : '') + '</div>' +
      '<div class="crumb" style="padding:10px 2px">' + esc(e.path || e.uri || '') + '</div>' +
      rows
    );
  }

  function appSheet(pkg, name) {
    window.qsSheet(
      '<div class="sheethead"><b>' + esc(name || pkg) + '</b>' +
      '<button class="iconbtn" data-act="closesheet">✕</button></div>' +
      '<div class="crumb" style="padding:10px 2px">' + esc(pkg) + '</div>' +
      '<div class="btncol">' +
      '<button class="btn primary btnblock" data-act="openapp" data-p="' + esc(pkg) + '">Open app</button>' +
      '<button class="btn ghost btnblock" data-act="shareapp" data-p="' + esc(pkg) + '">Share APK</button>' +
      '<button class="btn ghost btnblock" data-act="appinfo" data-p="' + esc(pkg) + '">App info</button>' +
      '</div>'
    );
  }

  function contactSheet(name, number) {
    window.qsSheet(
      '<div class="sheethead"><b>' + esc(name) + '</b>' +
      '<button class="iconbtn" data-act="closesheet">✕</button></div>' +
      '<div class="crumb" style="padding:10px 2px">' + esc(number || 'No number') + '</div>' +
      '<div class="btncol">' +
      (number ? '<button class="btn primary btnblock" data-act="dial" data-n="' + esc(number) + '">Call</button>' : '') +
      (number ? '<button class="btn ghost btnblock" data-act="copynum" data-n="' + esc(number) + '">Copy number</button>' : '') +
      '</div>'
    );
  }

  function toast(msg) { if (window.qsToast) window.qsToast(msg); }

  /* --------------------------------------------------------------- export */

  window.QSFiles = {
    view: view,
    after: after,
    action: action,
    reset: function () { F.key = ''; F.q = ''; F.path = ''; F.list = []; F.counts = null; },
    titleFor: function (route) {
      if (route === 'files') return ['Files', 'Everything on this device'];
      var rest = route.slice('files/'.length);
      if (rest === 'folder') return ['Folder', F.path ? F.path : 'Choose a storage location'];
      return [catMeta(rest).label, 'Browse and send'];
    },
    isFilesRoute: function (route) { return route === 'files' || route.indexOf('files/') === 0; },
    setQuery: function (q) { F.q = q; },
    filter: filter
  };
})();
