# 🛡️ LokMe — Nexus Dashboard

> **Device Admin & Monitoring System** — Android Kotlin app + futuristic web dashboard + Node.js server on Render + Supabase backend.

<div align="center">
  <img src="https://img.shields.io/badge/Kotlin-2.3.21-purple?logo=kotlin" alt="Kotlin">
  <img src="https://img.shields.io/badge/Android-API_36-3DDC84?logo=android" alt="Android">
  <img src="https://img.shields.io/badge/Node.js-Express-339933?logo=nodedotjs" alt="Node.js">
  <img src="https://img.shields.io/badge/Supabase-Postgres-3ECF8E?logo=supabase" alt="Supabase">
  <img src="https://img.shields.io/badge/Build-Gradle_8.12.1-02303A?logo=gradle" alt="Gradle">
</div>

---

## 🌟 Features

| Category | Feature | Description |
|----------|---------|-------------|
| 🔐 **Device Control** | Remote Lock | Lock screen instantly via Device Admin API |
| 💬 **Communication** | Push Dialog | Display custom alert on target device |
| 📍 **Tracking** | GPS Location | Real-time coordinates via Supabase + Leaflet map |
| 📸 **Surveillance** | Camera Capture | Front/back camera photos on demand |
| 📞 **Data Harvest** | Call Log Reader | Extract last 50 call logs |
| 🖥️ **Monitoring** | Screen Capture | Silent screenshot via MediaProjection API |
| 🔔 **Interception** | Notification Capture | Real-time SMS, WhatsApp, Telegram capture |
| 📺 **Streaming** | Live Video | Real-time camera stream to dashboard |
| 🔊 **Audio** | Play Alarm | Remote alarm at max volume |
| 📳 **Haptics** | Vibrate Device | Remote vibration control |
| 🖼️ **Media** | Gallery Browser | Browse & download photos/videos via MediaStore |
| 📅 **Calendar** | Calendar Reader | Fetch last 7 days of events |
| 🔋 **Power** | Battery Status | Auto-sends every 10s via WebSocket |
| ⚡ **Bypass** | Battery Optimization | Whitelist from doze mode |
| ♻️ **Persistence** | Keep-Alive Service | AccessibilityService auto-restarts on kill |
| 👻 **Stealth** | Hide/Show App | Toggle launcher icon visibility |

---

## 🏗️ Architecture

```
┌──────────────────┐     ┌──────────────────────────────┐     ┌──────────────────────┐
│                  │     │                              │     │                      │
│   📱 Android     │◄───►│   🌐 Node.js Server          │◄───►│   🗄️ Supabase        │
│   App (Kotlin)   │  WS │   (Express + WebSocket)      │ REST│   (Postgres + S3)    │
│                  │     │                              │     │                      │
└──────────────────┘     └──────────┬───────────────────┘     └──────────────────────┘
                                    │
                                    ▼
                          ┌──────────────────┐
                          │                  │
                          │   🖥️ Dashboard   │
                          │   (HTML/CSS/JS)  │
                          │                  │
                          └──────────────────┘
```

---

## 🚀 Quick Start

### 1️⃣ Supabase Setup

```bash
# Go to SQL Editor → paste & run:
server/schema.sql
```

### 2️⃣ Deploy Server (Render)

```bash
# Push to GitHub → New Web Service on render.com
# Build: npm install
# Start: node index.js
# Env vars:
#   SUPABASE_URL   = https://your-project.supabase.co
#   SUPABASE_KEY   = your-service-role-key
```

### 3️⃣ Build Android App

```bash
# Open android/ in Android Studio
# Edit LokMeApp.kt → fill in:
#   - SUPABASE_URL
#   - SUPABASE_KEY
#   - SERVER_URL (https://your-server.onrender.com)
# Build APK → install on device
```

### 4️⃣ Open Dashboard

```
https://your-server.onrender.com
```

---

## 🎮 Command Reference

| Command | Description |
|---------|-------------|
| `🔒 LOCK_DEVICE` | Lock screen immediately |
| `💬 SHOW_DIALOG` | Display alert dialog |
| `📍 GET_LOCATION` | Capture GPS coordinates |
| `📞 GET_CALL_LOG` | Read last 50 calls |
| `📸 CAPTURE_PHOTO` | Take photo (front/back) |
| `🖥️ CAPTURE_SCREEN` | Silent screenshot |
| `📺 START_VIDEO_STREAM` | Start live video stream |
| `⏹️ STOP_VIDEO_STREAM` | Stop live video stream |
| `🔊 PLAY_ALARM` | Play alarm ringtone |
| `📳 VIBRATE_DEVICE` | Vibrate device |
| `🖼️ LIST_MEDIA` | Browse device gallery |
| `⬇️ DOWNLOAD_FILE` / `DOWNLOAD_VIDEO` | Download to Supabase Storage |
| `📅 GET_CALENDAR` | Fetch 7 days of events |
| `👻 HIDE_APP` / `SHOW_APP` | Hide/unhide launcher icon |

---

## ⚙️ Build Config

```
🔧 AGP 8.9.1     |  🅺 Kotlin 2.3.21
📱 compileSdk 36  |  🎯 targetSdk 34
🏗️ Gradle 8.12.1 |  📦 Supabase BOM 3.6.0
📷 CameraX 1.4.1  |  📝 kotlinx-serialization-json 1.8.1
```

---

## 📚 Tech Stack

<div align="center">

| Layer | Technology |
|-------|-----------|
| 📱 **Mobile** | Kotlin, CameraX, FusedLocation, Device Admin API, OkHttp WebSocket, MediaProjection, MediaStore |
| 🌐 **Server** | Node.js, Express, ws, Supabase JS SDK |
| 🖥️ **Dashboard** | HTML5, CSS3 (glassmorphism), Vanilla JS, Chart.js 4.4.7, Leaflet.js 1.9.4 |
| 🗄️ **Database** | Supabase (PostgreSQL + Storage / S3-compatible) |

</div>

---

## 📖 Project Journey — The Blood, Sweat & Tears

### 🔰 Phase 1 — Foundation
```
✅ Android project with Device Admin API
✅ Supabase integration
✅ CommandService lifecycle
✅ WebSocket connection with auto-reconnect
```
> *No major issues — smooth start.*

---

### ⚡ Phase 2 — Dashboard & Commands
```
✅ Real-time web dashboard
✅ Location tracking, camera capture, call logs
```
> **😤 Struggle:** Commands sent before WebSocket connected never reached the device. **Fix:** Added a pending command queue on the server that replays on device connect.
>
> **😤 Struggle:** Supabase `created_at` column was `NULL` on insert. **Fix:** Added `DEFAULT timezone('utc'::text, now())` to the schema.

---

### 🖥️ Phase 3 — Screen Capture (The Great Struggle)
```
✅ Silent screenshot via MediaProjection
✅ VirtualDisplay + ImageReader pipeline
```
> **💥 Error:** `MediaProjection` token expired after service restart — VirtualDisplay was `null`. Initially created VirtualDisplay once on startup, but it was released in `onDestroy()`. **Fix:** Recreate VirtualDisplay right before each capture + re-init from stored token if `mediaProjection` is null.
>
> **💥 Error:** `queryMiniThumbnail()` doesn't exist on `MediaStore.Images.Thumbnails` — **build failed**. **Fix:** Query the thumbnails `ContentUri` directly using `IMAGE_ID` / `VIDEO_ID` columns instead. Added `getImageThumbnailPath()` and `getVideoThumbnailPath()` helper functions.

---

### 🔔 Phase 4 — Notification Capture & Deduplication
```
✅ Real-time SMS/WhatsApp/Telegram capture
✅ Color-coded badges
```
> **💥 Error:** Double notifications — Android inserted to Supabase via REST **AND** server inserted on WS receive. **Fix:** Removed server-side insert; dashboard deduplicates via `seenNotifIds` `Set`.
>
> **💥 Error:** Raw package names like `com.google.android.apps.messaging` instead of "SMS". **Fix:** Added `getNotifBadgeClass()` + `getAppDisplay()` mapping logic.

---

### 📁→🖼️ Phase 5 — File Browser → Gallery
```
✅ Replaced broken file browser with MediaStore gallery
✅ Thumbnails for images & videos
✅ Direct download via Supabase Storage
```
> **💥 Error:** Android 11+ scoped storage — `File.listFiles()` returned only directories, no files. Tried `MANAGE_EXTERNAL_STORAGE` (requires manual user grant).
>
> **😤 Struggle:** Rewrote entirely with `MediaStore` queries for images & videos. Thumbnails from `MediaStore.Images.Thumbnails` and `MediaStore.Video.Thumbnails`.
>
> **💥 Error:** The HTML popup `id="filesPopup"` wasn't updated to `id="galleryPopup"` — gallery button did nothing. **Fix:** Matched HTML IDs with JS references.

---

### 📺 Phase 6 — Live Video Stream
```
✅ Real-time camera stream via binary WebSocket
✅ Audio stream in parallel
```
> **😤 Struggle:** High resolution caused massive lag. **Fix:** Reduced to **320×240** at **JPEG quality 30** for smooth frames.

---

### 🔋 Phase 7 — Auto Battery Status
```
✅ Phone auto-sends battery every 30s (later 10s)
✅ No manual command needed
```
> **😤 Struggle:** Battery showed `--` until first WS message arrived. **Fix:** Cached last value per device in `deviceBatteryCache` map — displayed instantly on device select.

---

### ⏱️ Phase 8 — Battery Interval Fix
```
✅ Changed 30s → 10s
✅ Updated dashboard label
```

---

### 🎨 Phase 9 — Notification Colors & Labels
```
WhatsApp  → 🟢 Green   (#25d366)
SMS       → 🔵 Blue    (#3b82f6)
Snapchat  → 🟡 Yellow  (#fffc00)
TikTok    → 🔴 Red     (#ff2d55)
Telegram  → 🩵 Cyan    (#0088cc)
```

---

### ♻️ Phase 10 — Keep-Alive & Persistence
```
✅ AccessibilityService watchdog auto-restarts CommandService
✅ BootReceiver starts on device boot
✅ App can hide its launcher icon (stealth mode)
```

---

## 🧗 Persistent Challenges

| # | Challenge | Solution |
|---|-----------|----------|
| 1️⃣ | **📁 Scoped Storage (Android 11+)** — `File` API is severely restricted | Use `MediaStore` for media files; `MANAGE_EXTERNAL_STORAGE` for full access (manual grant) |
| 2️⃣ | **🔄 MediaProjection Token** — Invalid after process restart | Store token in SharedPreferences; recreate VirtualDisplay on each capture |
| 3️⃣ | **💀 Background Process Limits** — Android kills services aggressively | Foreground service + foregroundServiceType="connectedDevice" + AccessibilityService watchdog |
| 4️⃣ | **🔕 NotificationListenerService** — Can't programmatically re-bind | User must toggle in system settings — no workaround |
| 5️⃣ | **📶 WebSocket Stability** — Drops on network changes | Exponential backoff reconnect logic (1s → 2s → 4s → 8s → max 60s) |

---

## 👨‍💻 Made by

<div align="center">
  
**SemDev Studio**
  
<br>
  
[![GitHub](https://img.shields.io/badge/GitHub-danielsem65-181717?logo=github)](https://github.com/danielsem65/LokMe)

</div>

---

<div align="center">
  <sub>Built with ☕, 😤, and many sleepless nights</sub>
</div>
