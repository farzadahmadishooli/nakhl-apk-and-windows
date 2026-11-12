const { app, BrowserWindow, Menu, ipcMain, dialog } = require('electron');
const path = require('path');
const fs = require('fs');

// Keep data in a fixed folder so it survives app updates / re-installs
app.setPath('userData', path.join(app.getPath('appData'), 'NakhlAccounting'));

let win = null;
const KEEP_DAYS = 30;

// ---------- Automatic backup ----------
const cfgFile = () => path.join(app.getPath('userData'), 'backup-config.json');
function readCfg() { try { return JSON.parse(fs.readFileSync(cfgFile(), 'utf8')); } catch (e) { return {}; } }
function writeCfg(c) {
  try {
    fs.mkdirSync(path.dirname(cfgFile()), { recursive: true });
    fs.writeFileSync(cfgFile(), JSON.stringify(c));
  } catch (e) { /* ignore */ }
}
function backupDir() {
  const c = readCfg();
  return c.folder || path.join(app.getPath('documents'), 'NakhlBackup');
}
const pad = (n) => String(n).padStart(2, '0');
function todayName() {
  const d = new Date();
  return 'nakhl-backup-' + d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + '.json';
}
function writeAtomic(file, data) {
  const tmp = file + '.tmp';
  fs.writeFileSync(tmp, data, 'utf8');
  fs.renameSync(tmp, file);
}
function pruneOld(dir) {
  try {
    const days = fs.readdirSync(dir).filter((f) => /^nakhl-backup-\d{4}-\d{2}-\d{2}\.json$/.test(f)).sort();
    while (days.length > KEEP_DAYS) {
      try { fs.unlinkSync(path.join(dir, days.shift())); } catch (e) { /* ignore */ }
    }
  } catch (e) { /* ignore */ }
}
function saveBackup(json) {
  try {
    const dir = backupDir();
    fs.mkdirSync(dir, { recursive: true });
    writeAtomic(path.join(dir, 'nakhl-backup-latest.json'), json);
    writeAtomic(path.join(dir, todayName()), json);
    pruneOld(dir);
    return { ok: true, dir };
  } catch (e) {
    return { ok: false, error: String((e && e.message) || e) };
  }
}

ipcMain.on('ab-save', (event, json) => {
  if (typeof json !== 'string' || !json) return;
  const result = saveBackup(json);
  try { event.sender.send('ab-result', result); } catch (e) { /* window closed */ }
});
ipcMain.handle('ab-info', () => 'پوشه بکاپ: ' + backupDir());
ipcMain.handle('ab-choose', async () => {
  const r = await dialog.showOpenDialog(win, {
    title: 'انتخاب پوشه بکاپ خودکار',
    defaultPath: backupDir(),
    properties: ['openDirectory', 'createDirectory']
  });
  if (!r.canceled && r.filePaths && r.filePaths[0]) writeCfg({ folder: r.filePaths[0] });
  return 'پوشه بکاپ: ' + backupDir();
});
ipcMain.handle('ab-latest', () => {
  try { return fs.readFileSync(path.join(backupDir(), 'nakhl-backup-latest.json'), 'utf8'); } catch (e) { return ''; }
});

// ---------- Window ----------
function createWindow() {
  win = new BrowserWindow({
    width: 1280,
    height: 800,
    title: 'حسابداری نخل',
    icon: path.join(__dirname, 'build', 'icon.png'),
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false
    }
  });
  Menu.setApplicationMenu(null);
  win.maximize();
  win.loadFile(path.join(__dirname, 'index.html'));
  win.on('closed', () => { win = null; });
}

// only one copy of the app at a time (two copies would overwrite each other's data)
if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (win) { if (win.isMinimized()) win.restore(); win.focus(); }
  });
  app.whenReady().then(createWindow);
  app.on('window-all-closed', () => app.quit());
}
