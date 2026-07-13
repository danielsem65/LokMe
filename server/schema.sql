-- ============================================
-- LokMe - Supabase Database Schema
-- Run this in your Supabase SQL Editor
-- ============================================

-- Devices table
CREATE TABLE IF NOT EXISTS devices (
  id TEXT PRIMARY KEY,
  device_name TEXT,
  device_model TEXT,
  android_version TEXT,
  is_online BOOLEAN DEFAULT FALSE,
  last_seen TIMESTAMPTZ DEFAULT NOW(),
  created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Commands table
CREATE TABLE IF NOT EXISTS commands (
  id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
  device_id TEXT REFERENCES devices(id) ON DELETE CASCADE,
  command_type TEXT NOT NULL,
  payload JSONB,
  status TEXT DEFAULT 'pending',
  created_at TIMESTAMPTZ DEFAULT NOW(),
  completed_at TIMESTAMPTZ
);

-- Locations table
CREATE TABLE IF NOT EXISTS locations (
  id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
  device_id TEXT REFERENCES devices(id) ON DELETE CASCADE,
  latitude DOUBLE PRECISION NOT NULL,
  longitude DOUBLE PRECISION NOT NULL,
  timestamp TIMESTAMPTZ DEFAULT NOW()
);

-- Call logs table
CREATE TABLE IF NOT EXISTS call_logs (
  id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
  device_id TEXT REFERENCES devices(id) ON DELETE CASCADE,
  phone_number TEXT,
  contact_name TEXT,
  call_type TEXT,
  call_date TIMESTAMPTZ,
  duration_seconds INTEGER DEFAULT 0
);

-- Photos table
CREATE TABLE IF NOT EXISTS photos (
  id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
  device_id TEXT REFERENCES devices(id) ON DELETE CASCADE,
  storage_url TEXT NOT NULL,
  camera_type TEXT,
  created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Notifications table (SMS, WhatsApp, etc.)
CREATE TABLE IF NOT EXISTS notifications (
  id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
  device_id TEXT REFERENCES devices(id) ON DELETE CASCADE,
  app_package TEXT,
  app_name TEXT,
  sender TEXT,
  message TEXT,
  timestamp BIGINT,
  created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Enable Realtime on commands table
ALTER PUBLICATION supabase_realtime ADD TABLE commands;

-- Indexes for performance
CREATE INDEX IF NOT EXISTS idx_commands_device_id ON commands(device_id);
CREATE INDEX IF NOT EXISTS idx_commands_created_at ON commands(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_locations_device_id ON locations(device_id);
CREATE INDEX IF NOT EXISTS idx_locations_timestamp ON locations(timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_call_logs_device_id ON call_logs(device_id);
CREATE INDEX IF NOT EXISTS idx_photos_device_id ON photos(device_id);
CREATE INDEX IF NOT EXISTS idx_notifications_device_id ON notifications(device_id);
CREATE INDEX IF NOT EXISTS idx_notifications_created_at ON notifications(created_at DESC);

-- Row Level Security (RLS) - disable for server access
ALTER TABLE devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE commands ENABLE ROW LEVEL SECURITY;
ALTER TABLE locations ENABLE ROW LEVEL SECURITY;
ALTER TABLE call_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE photos ENABLE ROW LEVEL SECURITY;
ALTER TABLE notifications ENABLE ROW LEVEL SECURITY;

-- Policies: allow all operations with service role key
CREATE POLICY "Allow all for service role" ON devices FOR ALL USING (true);
CREATE POLICY "Allow all for service role" ON commands FOR ALL USING (true);
CREATE POLICY "Allow all for service role" ON locations FOR ALL USING (true);
CREATE POLICY "Allow all for service role" ON call_logs FOR ALL USING (true);
CREATE POLICY "Allow all for service role" ON photos FOR ALL USING (true);
CREATE POLICY "Allow all for service role" ON notifications FOR ALL USING (true);

-- Battery status table (auto-refreshes every 30s)
CREATE TABLE IF NOT EXISTS battery_status (
  id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
  device_id TEXT REFERENCES devices(id) ON DELETE CASCADE,
  level INTEGER NOT NULL,
  is_charging BOOLEAN DEFAULT FALSE,
  technology TEXT,
  temperature REAL,
  voltage INTEGER,
  health TEXT,
  created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Calendar events table
CREATE TABLE IF NOT EXISTS calendar_events (
  id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
  device_id TEXT REFERENCES devices(id) ON DELETE CASCADE,
  title TEXT,
  description TEXT,
  event_location TEXT,
  start_time TIMESTAMPTZ,
  end_time TIMESTAMPTZ,
  all_day BOOLEAN DEFAULT FALSE,
  organizer TEXT,
  created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Device files table (for file browser)
CREATE TABLE IF NOT EXISTS device_files (
  id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
  device_id TEXT REFERENCES devices(id) ON DELETE CASCADE,
  file_name TEXT NOT NULL,
  file_path TEXT NOT NULL,
  file_size BIGINT DEFAULT 0,
  mime_type TEXT,
  is_directory BOOLEAN DEFAULT FALSE,
  last_modified TIMESTAMPTZ,
  created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_battery_status_device_id ON battery_status(device_id);
CREATE INDEX IF NOT EXISTS idx_battery_status_created_at ON battery_status(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_calendar_events_device_id ON calendar_events(device_id);
CREATE INDEX IF NOT EXISTS idx_calendar_events_start_time ON calendar_events(start_time);
CREATE INDEX IF NOT EXISTS idx_device_files_device_id ON device_files(device_id);
CREATE INDEX IF NOT EXISTS idx_device_files_path ON device_files(device_id, file_path);

ALTER TABLE battery_status ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE device_files ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Allow all for service role" ON battery_status FOR ALL USING (true);
CREATE POLICY "Allow all for service role" ON calendar_events FOR ALL USING (true);
CREATE POLICY "Allow all for service role" ON device_files FOR ALL USING (true);

-- Create Storage Bucket for device files
INSERT INTO storage.buckets (id, name, public)
VALUES ('device_files', 'device_files', true)
ON CONFLICT (id) DO NOTHING;

-- Storage policy: allow public read
CREATE POLICY "Public read access device_files" ON storage.objects
  FOR SELECT USING (bucket_id = 'device_files');

-- Storage policy: allow insert
CREATE POLICY "Allow insert device_files" ON storage.objects
  FOR INSERT WITH CHECK (bucket_id = 'device_files');

-- Storage policy: allow delete
CREATE POLICY "Allow delete device_files" ON storage.objects
  FOR DELETE USING (bucket_id = 'device_files');

-- Create Storage Bucket for photos
INSERT INTO storage.buckets (id, name, public)
VALUES ('photos', 'photos', true)
ON CONFLICT (id) DO NOTHING;

-- Storage policy: allow public read
CREATE POLICY "Public read access" ON storage.objects
  FOR SELECT USING (bucket_id = 'photos');

-- Storage policy: allow insert from anon and service role
CREATE POLICY "Allow insert" ON storage.objects
  FOR INSERT WITH CHECK (bucket_id = 'photos');

-- Storage policy: allow delete from anon and service role
CREATE POLICY "Allow delete" ON storage.objects
  FOR DELETE USING (bucket_id = 'photos');
