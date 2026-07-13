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
