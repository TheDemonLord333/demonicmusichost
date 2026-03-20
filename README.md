# DemonicMusicHost

Ein Android-App für gemeinsames Musik-Hören – wie Spotify Jam, aber mit YouTube, Spotify und lokalen Dateien.
Web-Gäste können über den integrierten Ktor-Backend beitreten (Browser, kein App-Download nötig).

> **Plattformen:** Android (Host & Gast) · Web-Browser (nur Gast / Host via Spotify Premium)
> **iOS wird nicht unterstützt** – die App ist in Kotlin für Android geschrieben.

---

## Farbdesign

Kombination aus **Spotify** (Grün `#1DB954`) und **Discord** (Blurple `#5865F2` / Dark `#2C2F33`).

---

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
| **Web-Frontend** | Browser-Gäste via QR-Code einladen |
| **Ktor-Backend** | Selbst-gehosteter Server (Debian 12) |

---

## Schnellübersicht: Was brauche ich zum Bauen?

| Was | Werkzeug |
|-----|----------|
| Android-APK | Android Studio / `./gradlew` + JDK 17 |
| Backend-JAR (Server) | JDK 17 + `./gradlew :backend:buildFatJar` |
| Web-Frontend | Kein Build nötig – wird vom Backend als statische Dateien ausgeliefert |
| iOS | **Nicht möglich** (Kotlin/Android only) |

---

## Voraussetzungen (Entwicklungs-PC)

### Windows

1. **JDK 17** installieren
   - [Adoptium Temurin 17](https://adoptium.net/) herunterladen und installieren
   - Überprüfen: `java -version` → `openjdk 17.x.x`

2. **Android Studio** installieren
   - [developer.android.com/studio](https://developer.android.com/studio)
   - Beim ersten Start: SDK-Wizard durchlaufen (API 34 auswählen)
   - `ANDROID_HOME` wird automatisch gesetzt (`C:\Users\<Name>\AppData\Local\Android\Sdk`)

3. **Git** installieren
   - [git-scm.com](https://git-scm.com/download/win)

### macOS

```bash
# JDK 17 via Homebrew
brew install temurin@17

# Android Studio
brew install --cask android-studio

# Git (meist schon vorhanden)
git --version
```

### Linux (Debian/Ubuntu)

```bash
sudo apt update
sudo apt install -y openjdk-17-jdk git

# Android Studio: .tar.gz von developer.android.com herunterladen
# oder via snap:
sudo snap install android-studio --classic
```

---

## Schritt 1 – Repository klonen

```bash
git clone https://github.com/TheDemonLord333/demonicmusichost.git
cd demonicmusichost
```

---

## Schritt 2 – API-Keys & Credentials einrichten

### 2.1 Firebase

1. Gehe zu [Firebase Console](https://console.firebase.google.com)
2. Neues Projekt erstellen
3. **Android-App** hinzufügen: Package `com.demonicmusichost.app`
4. `google-services.json` herunterladen → ins Projekt-Verzeichnis legen:
   ```
   demonicmusichost/app/google-services.json
   ```
5. Realtime Database aktivieren (Start im Test-Modus)
6. Authentication aktivieren (Anonymous oder Email)

### 2.2 Spotify Developer App

1. Gehe zu [Spotify Developer Dashboard](https://developer.spotify.com/dashboard)
2. Neue App erstellen
3. **Redirect URIs** hinzufügen:
   - `demonicmusichost://callback` (Android)
   - `https://<DEINE_DOMAIN>/auth/callback` (Backend/Web)
4. **Client ID** und **Client Secret** kopieren
5. In `app/build.gradle` eintragen:
   ```groovy
   buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"DEINE_CLIENT_ID\"")
   ```
6. Für das Backend: in `deploy/demonicmusichost.env` eintragen (siehe Schritt 5)

### 2.3 YouTube Data API v3

1. Gehe zu [Google Cloud Console](https://console.cloud.google.com)
2. YouTube Data API v3 aktivieren
3. API-Key erstellen (ohne Einschränkungen für Entwicklung, oder IP-beschränkt für Produktion)
4. In `app/build.gradle` eintragen:
   ```groovy
   buildConfigField("String", "YOUTUBE_API_KEY", "\"DEIN_API_KEY\"")
   ```
5. Für das Backend: ebenfalls in `deploy/demonicmusichost.env` eintragen

### 2.4 Fonts (optional)

Lade **Inter** von [Google Fonts](https://fonts.google.com/specimen/Inter) herunter:
- `Inter-Regular.ttf` → `app/src/main/res/font/regular.ttf`
- `Inter-Medium.ttf`  → `app/src/main/res/font/medium.ttf`
- `Inter-Bold.ttf`    → `app/src/main/res/font/bold.ttf`

---

## Schritt 3 – Android-App bauen

### Option A: Android Studio (empfohlen für Entwicklung)

1. Android Studio öffnen → **Open** → Projektordner auswählen
2. Gradle-Sync abwarten (erster Start dauert einige Minuten)
3. Gerät/Emulator auswählen (API 26+ empfohlen)
4. **Run** (▶) drücken

### Option B: Kommandozeile (Debug-APK)

```bash
# Windows
gradlew.bat assembleDebug

# macOS / Linux
./gradlew assembleDebug
```

Die APK liegt danach unter:
```
app/build/outputs/apk/debug/app-debug.apk
```

### Option C: Release-APK (signiert, für Verteilung)

```bash
# Keystore erstellen (einmalig)
keytool -genkeypair -v -keystore release.jks -alias demonicmusichost \
        -keyalg RSA -keysize 2048 -validity 10000

# In app/build.gradle signingConfigs eintragen:
# signingConfigs {
#     release {
#         storeFile file("../release.jks")
#         storePassword "DEIN_PASSWORT"
#         keyAlias "demonicmusichost"
#         keyPassword "DEIN_PASSWORT"
#     }
# }

# Release-APK bauen
./gradlew assembleRelease
```

APK liegt unter:
```
app/build/outputs/apk/release/app-release.apk
```

### APK auf Android-Gerät installieren

```bash
# Via ADB (USB-Debugging am Gerät aktivieren)
adb install app/build/outputs/apk/debug/app-debug.apk

# Oder: APK-Datei per USB auf das Gerät kopieren und dort öffnen
# (Einstellung "Unbekannte Quellen" / "Install unknown apps" muss erlaubt sein)
```

---

## Schritt 4 – Backend (Ktor-Server) bauen

Das Backend ist ein eigenständiger Gradle-Submodule (`:backend`).

```bash
# Fat JAR bauen (enthält alle Dependencies)
./gradlew :backend:buildFatJar
```

Die JAR liegt danach unter:
```
backend/build/libs/demonicmusichost-backend.jar
```

### Lokal testen

```bash
# Umgebungsvariablen setzen (Beispiel für Linux/macOS)
export PORT=8080
export SPOTIFY_CLIENT_ID=deine_client_id
export SPOTIFY_CLIENT_SECRET=dein_client_secret
export SPOTIFY_REDIRECT_URI=http://localhost:8080/auth/callback
export YOUTUBE_API_KEY=dein_api_key
export SESSION_SECRET=$(openssl rand -hex 32)
export BASE_URL=http://localhost:8080

# Backend starten
java -jar backend/build/libs/demonicmusichost-backend.jar
```

Unter Windows (PowerShell):
```powershell
$env:PORT = "8080"
$env:SPOTIFY_CLIENT_ID = "deine_client_id"
# ... weitere Variablen
java -jar backend\build\libs\demonicmusichost-backend.jar
```

Backend läuft dann auf `http://localhost:8080`.
Health-Check: `http://localhost:8080/health` → `{"status":"ok"}`

In der Android-App unter **Einstellungen** die URL einstellen:
- Emulator: `http://10.0.2.2:8080`
- Echtes Gerät im selben WLAN: `http://192.168.x.x:8080`

---

## Schritt 5 – Auf Debian 12 Server deployen

Vollständige Anleitung: [`deploy/README.md`](deploy/README.md)

Kurzfassung:
```bash
# 1. Umgebungsvariablen auf dem Server konfigurieren
sudo cp deploy/demonicmusichost.env /opt/demonicmusichost/demonicmusichost.env
sudo nano /opt/demonicmusichost/demonicmusichost.env   # alle <REPLACE_ME> ausfüllen

# 2. systemd-Service installieren
sudo cp deploy/demonicmusichost.service /etc/systemd/system/
sudo systemctl enable demonicmusichost

# 3. nginx + Let's Encrypt einrichten
sudo cp deploy/nginx.conf /etc/nginx/sites-available/demonicmusichost
# <YOUR_DOMAIN> in der Datei ersetzen
sudo certbot --nginx -d deine-domain.de

# 4. Deployment vom Entwicklungs-PC aus
./deploy/deploy.sh deploy@deine-domain.de
```

---

## Architektur

```
┌─────────────────────────────────────┐
│              UI Layer               │
│  HomeFragment | HostFragment        │
│  GuestFragment | SearchFragment     │
│  SettingsFragment                   │
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
│  BackendSyncManager (Ktor Backend)  │
└─────────────────────────────────────┘
               │ HTTP / WebSocket
┌──────────────▼──────────────────────┐
│         Ktor Backend (:backend)     │
│  AuthRoutes | SessionRoutes         │
│  SearchRoutes | WsRoutes            │
│  SessionHub (WebSocket Broadcast)   │
│  SpotifyService | YouTubeService    │
│  Static Web-Frontend (HTML/CSS/JS)  │
└─────────────────────────────────────┘
```

---

## Tech Stack

**Android App:**
- **Kotlin** + Coroutines / Flow
- **MVVM** Architektur + Hilt Dependency Injection
- **Firebase** Realtime Database (Echtzeit-Session-Sync)
- **Spotify Android SDK** + Spotify Web API
- **YouTube Data API v3** + WebView Player
- **ExoPlayer / Media3** für lokale Dateien
- **Retrofit** + OkHttp für HTTP
- **Glide** für Bilder
- **Navigation Component** mit Safe Args
- **ZXing** für QR-Code-Generierung

**Backend:**
- **Ktor 3.0.3** (Netty Engine)
- **kotlinx-serialization** für JSON
- **WebSockets** für Echtzeit-Sync mit Web-Clients
- **Spotify OAuth** Authorization Code Flow (server-seitig)

**Web-Frontend:**
- Vanilla HTML / CSS / JavaScript (kein Framework, kein Build-Tool)
- **Spotify Web Playback SDK** (Browser, erfordert Premium)
- Auto-reconnect WebSocket Client

---

## Bekannte Einschränkungen

- **YouTube-Streaming**: Direktes Streamen über ExoPlayer ist nicht offiziell von YouTube erlaubt. Die App verwendet einen WebView-basierten Embedded Player.
- **Spotify**: Wiedergabe erfordert Spotify Premium. Android nutzt Spotify App Remote; Web nutzt Spotify Web Playback SDK.
- **Gäste hören Spotify**: Gäste können nur sehen, was abgespielt wird – die Wiedergabe erfolgt immer auf dem Host-Gerät.
- **iOS**: Nicht unterstützt (Kotlin/Android only).
