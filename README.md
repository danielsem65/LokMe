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

## Tech Stack

- **Android:** Kotlin, CameraX, FusedLocation, Device Admin API, OkHttp WebSocket
- **Server:** Node.js, Express, ws, Supabase JS
- **Dashboard:** HTML/CSS/JS, Leaflet.js maps
- **Database:** Supabase (PostgreSQL + Storage)
