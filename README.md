# DemonicMusicHost

Ein Android-App für gemeinsames Musik-Hören – wie Spotify Jam, aber mit YouTube, Spotify und lokalen Dateien.

## Farbdesign

Kombination aus **Spotify** (Grün `#1DB954`) und **Discord** (Blurple `#5865F2` / Dark `#2C2F33`).

## Features

| Feature | Beschreibung |
|---|---|
| **Host-Session** | Erstelle eine Session mit 6-stelligem Code |
| **Gäste beitreten** | Via Code joinen, wie bei Spotify Jam |
| **Queue-Management** | Gäste fügen Songs hinzu, Host kontrolliert |
| **Spotify Premium** | Spotify-Songs über Host's Premium-Konto |
| **YouTube** | YouTube-Videos als Musik (via WebView) |
| **Lokale Dateien** | MP3/FLAC etc. vom Gerät des Hosts |
| **Echtzeit-Sync** | Firebase Realtime Database |
| **Drag-to-reorder** | Host kann Queue umsortieren |
| **Guest-Lock** | Host kann Song-Hinzufügen deaktivieren |

## Setup-Schritte

### 1. Firebase einrichten
1. Gehe zu [Firebase Console](https://console.firebase.google.com)
2. Erstelle ein neues Projekt
3. Android-App hinzufügen: Package `com.demonicmusichost.app`
4. `google-services.json` herunterladen → `app/google-services.json`
5. Realtime Database aktivieren (Start im Test-Modus)
6. Authentication aktivieren (Anonymous oder Email)

### 2. Spotify Developer App registrieren
1. Gehe zu [Spotify Developer Dashboard](https://developer.spotify.com/dashboard)
2. Neue App erstellen
3. Redirect URI hinzufügen: `demonicmusichost://callback`
4. Client ID kopieren
5. In `app/build.gradle` eintragen:
   ```groovy
   buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"DEINE_CLIENT_ID\"")
   ```

### 3. YouTube Data API v3
1. Gehe zu [Google Cloud Console](https://console.cloud.google.com)
2. YouTube Data API v3 aktivieren
3. API-Key erstellen
4. In `app/build.gradle` eintragen:
   ```groovy
   buildConfigField("String", "YOUTUBE_API_KEY", "\"DEIN_API_KEY\"")
   ```

### 4. Fonts (optional)
Lade **Inter** von [Google Fonts](https://fonts.google.com/specimen/Inter) herunter:
- `Inter-Regular.ttf` → `app/src/main/res/font/regular.ttf`
- `Inter-Medium.ttf` → `app/src/main/res/font/medium.ttf`
- `Inter-Bold.ttf` → `app/src/main/res/font/bold.ttf`

## Architektur

```
┌─────────────────────────────────────┐
│              UI Layer               │
│  HomeFragment | HostFragment        │
│  GuestFragment | SearchFragment     │
│  QueueAdapter | SearchResultAdapter │
└──────────────┬──────────────────────┘
               │ ViewModel (MVVM)
┌──────────────▼──────────────────────┐
│           Domain Layer              │
│  HomeViewModel | HostViewModel      │
│  GuestViewModel | SearchViewModel   │
└──────────────┬──────────────────────┘
               │ Repository Pattern
┌──────────────▼──────────────────────┐
│            Data Layer               │
│  SessionRepository (Firebase)       │
│  SpotifyRepository (Spotify API)    │
│  YouTubeRepository (YouTube API)    │
│  LocalMusicRepository (MediaStore)  │
└─────────────────────────────────────┘
```

## Tech Stack

- **Kotlin** + Coroutines / Flow
- **MVVM** Architektur
- **Hilt** Dependency Injection
- **Firebase** Realtime Database (Echtzeit-Session-Sync)
- **Spotify Android SDK** + Spotify Web API
- **YouTube Data API v3** + WebView Player
- **ExoPlayer / Media3** für lokale Dateien
- **Retrofit** für HTTP
- **Glide** für Bilder
- **Navigation Component** mit Safe Args

## Bekannte Einschränkungen

- **YouTube-Streaming**: Direktes Streamen über ExoPlayer ist nicht offiziell von YouTube erlaubt. Die App verwendet einen WebView-basierten Embedded Player.
- **Spotify**: Wiedergabe erfordert, dass die Spotify-App auf dem Host-Gerät installiert ist (Spotify App Remote) oder über die Web API gesteuert wird.
- **Gäste hören Spotify**: Gäste können nur sehen, was abgespielt wird – die Wiedergabe erfolgt immer auf dem Host-Gerät.
