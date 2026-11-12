const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('NakhlDesktop', {
  autoBackup: (json) => ipcRenderer.send('ab-save', json),
  getInfo: () => ipcRenderer.invoke('ab-info'),
  chooseFolder: () => ipcRenderer.invoke('ab-choose'),
  getLatest: () => ipcRenderer.invoke('ab-latest'),
  onBackupResult: (cb) => { ipcRenderer.on('ab-result', (_e, r) => cb(r)); }
});
