# SafeFinder Test System

This repository contains:

- `android-app`: Kotlin native Android test app.
- `api`: Vercel Serverless Functions for deployed receiving.
- `index.html`: Simple Vercel monitor page that shows the latest received data.
- `server`: Node.js HTTP receiver for SafeFinder readings.

## Android App

Open `android-app` in Android Studio, sync Gradle, and run it on a device or emulator.

The first line of the app shows the connected WiFi AP name. Android requires location permission to expose the WiFi SSID. Some Android versions also require Location services to be enabled.

Buttons:

- `Send`: sends one reading immediately.
- `Auto`: starts a foreground service and sends one reading every 10 minutes, continuing while the app is minimized.
- `Stop Auto`: stops the foreground service.

Default server URL:

```text
https://YOUR-VERCEL-PROJECT.vercel.app/api/readings
```

After deploying to Vercel, replace `YOUR-VERCEL-PROJECT` in the app field with your actual Vercel domain.

For an Android emulator with the local Node server, use:

```text
http://10.0.2.2:8080/readings
```

For a physical phone with the local Node server, use the PC LAN IP:

```text
http://192.168.0.10:8080/readings
```

## Vercel Server

Deploy the project root, `C:\Github\safeFinder`, to Vercel.

Endpoints:

```text
GET  https://YOUR-VERCEL-PROJECT.vercel.app/
GET  https://YOUR-VERCEL-PROJECT.vercel.app/api/health
GET  https://YOUR-VERCEL-PROJECT.vercel.app/api/readings
POST https://YOUR-VERCEL-PROJECT.vercel.app/api/readings
```

Open the root page in a browser and keep it open. It refreshes every 2 seconds and displays the latest payload received from the phone.

The Vercel function responds with:

```json
{
  "success": true,
  "data": {
    "accepted": 2,
    "duplicated": 1,
    "serverTime": "2026-06-02T21:25:01+09:00"
  }
}
```

Duplicate readings and the monitor page data are kept in warm serverless memory. For production-level duplicate detection or history, use durable storage such as Vercel KV, Redis, or a database.

## Local Node Server

You can still run the local server:

```powershell
cd C:\Github\safeFinder\server
npm start
```

Health check:

```text
GET http://localhost:8080/health
```

Receiver endpoint:

```text
POST http://localhost:8080/readings
```
