# Poterx IPTV — plan aplikacji na Android TV

Prywatny odtwarzacz IPTV na Android TV / Google TV / Fire TV dla jednej listy
z panelu **XUI.one** (API Xtream Codes). Cel: wrażenia jak w TiviMate
(szybki zapping, EPG w siatce, catch‑up, ulubione, VOD/seriale), ale
własny kod, bez reklam i bez zależności od cudzych serwerów.

Data planu: 2026‑10‑21.

---

## 1. Wnioski z researchu (open source, stan na 10/2026)

| Projekt | Licencja | Stack | Źródła | Co warto podpatrzeć |
|---|---|---|---|---|
| [OwnTV](https://github.com/ahXN00/OwnTV) (615★) | GPL‑3.0 | Kotlin, Compose for TV (tv‑material 1.1.0), Media3 1.11.1 **+ libmpv**, Koin, Room+Paging+FTS4, OkHttp 5, Coil 3 | Xtream, M3U, Stalker | Architektura "remote‑first", dwa silniki za jednym interfejsem `PlaybackEngine`, catch‑up do 7 dni, profile, mapowanie klawiszy pilota, stream‑parsing XMLTV/JSON |
| [OpenTV](https://github.com/opentvproject/opentv) (102★) | GPL‑3.0 | Kotlin, Compose, Media3 | Xtream, M3U | Siatka EPG odświeżana przyrostowo, catch‑up, przypomnienia, PiP, DVR |
| [onair‑tv](https://github.com/jasmedia/onair-tv) | AGPL‑3.0 | Compose for TV, Media3, OkHttp, Coil, bez DI/DB | M3U | Najprostszy, czytelny punkt startowy: `M3uParser`, `XmltvParser`, `ChannelsScreen`, `PlayerScreen` |
| [StreamVault](https://github.com/Davidona/StreamVault-IPTV) (826★) | source‑available, **non‑commercial** (nie OSI) | Compose, Media3 + FFmpeg ext, Hilt, Room | Xtream, Stalker, M3U, Jellyfin | Numeracja kanałów, pełna siatka EPG, podział na `app/core/data/domain/feature/player` |
| [M3UAndroid](https://github.com/oxyroid/M3UAndroid) (1.3k★) | GPL‑3.0 | Compose | M3U, Xtream | **Zarchiwizowane 8.10.2026** — tylko do czytania |
| [Zapette](https://github.com/Bysimeit/Zapette) | PolyForm NC | Compose, Media3 + Jellyfin FFmpeg | Xtream, M3U | Passthrough audio (AC‑3/E‑AC‑3/DTS) |
| [android/tv‑samples](https://github.com/android/tv-samples) | Apache‑2.0 | Compose for TV | — | `JetStreamCompose` (architektura), `TvMaterialCatalog` (komponenty) |

Dokumentacja API: [xui‑one‑api‑docs (player API)](https://github.com/worldofiptvcom/xui-one-api-docs/blob/main/xtream-codes-player-api-documentation.md),
[xtream‑codes‑api‑documentation](https://github.com/worldofiptvcom/xtream-codes-api-documentation),
[Xtream‑Masters player_api](https://xtream-masters.com/api-doc/player_api.php).

**Decyzje wynikające z researchu**

1. Piszemy **własny kod** (Apache‑2.0 lub MIT), a OwnTV/OpenTV/onair‑tv traktujemy
   jako referencję architektury. Kopiowanie kodu GPL/AGPL zmusiłoby nas do GPL — na
   prywatną apkę to nie problem, ale własny kod daje czystą sytuację.
2. UI: **Jetpack Compose for TV** (`androidx.tv:tv-material:1.1.0`). Leanback
   (`androidx.leanback`) jest oficjalnie w trybie migracji — nie zaczynamy od niego.
   Nie mieszamy `androidx.tv.material3` z mobilnym `androidx.compose.material3`.
3. Player: **Media3 / ExoPlayer 1.11.1** (HLS + TS przez `media3-exoplayer-hls`).
   libmpv dokładamy dopiero, gdy trafimy na strumienie, których ExoPlayer nie gryzie
   (faza 7). Dekoder FFmpeg (Jellyfin `media3-ffmpeg-decoder`) jako plan B dla audio.
4. Źródło danych: **Xtream `player_api.php`** jako podstawowe (daje kategorie,
   numery kanałów, `epg_channel_id`, `tv_archive`, VOD, seriale). Parser M3U
   zostaje jako fallback / import drugiej listy.
5. EPG: `xmltv.php` (pełny XMLTV, gzip‑aware, parsowany strumieniowo do Room) +
   `get_short_epg` do szybkiego "teraz/następnie" w bannerze.

---

## 2. Stack techniczny

| Warstwa | Wybór | Wersja (10/2026) |
|---|---|---|
| Język | Kotlin | 2.2.x (stable) |
| Build | AGP 8.x / Gradle 8.x, Version Catalog (`gradle/libs.versions.toml`) | — |
| UI | Compose BOM 2026.09.00, `androidx.tv:tv-material` | 1.1.0 |
| Nawigacja | `androidx.navigation:navigation-compose` | 2.9.x |
| Player | `media3-exoplayer`, `media3-exoplayer-hls`, `media3-ui-compose`, `media3-session` | 1.11.1 |
| Sieć | OkHttp 5 + Retrofit 3 (lub czysty OkHttp) + kotlinx.serialization | — |
| DB | Room 2.8 + Paging 3 (FTS4 do wyszukiwarki) | — |
| Obrazki | Coil 3 | — |
| DI | Koin 4 (lżejszy niż Hilt, bez kapt/ksp‑narzutu) | — |
| Ustawienia | DataStore (Preferences) | — |
| Praca w tle | WorkManager (odświeżanie listy i EPG) | — |
| Testy | JUnit5, kotlinx‑coroutines‑test, Turbine, MockWebServer | — |
| SDK | minSdk 26, targetSdk 36, compileSdk 36 | — |

Manifest (wymagania Android TV):
- `<uses-feature android:name="android.software.leanback" android:required="true"/>`
- `<uses-feature android:name="android.hardware.touchscreen" android:required="false"/>`
- Activity z `MAIN` + `LEANBACK_LAUNCHER`, `android:banner` 320×180 xhdpi
- `wifi`, `microphone`, `camera` itd. jako `required="false"`
- Playback zatrzymywany w `onStop()`

---

## 3. Architektura (moduły Gradle)

```
poterx-iptv-app/
├── app/                 # Activity, nawigacja, DI root, zasoby TV (banner, ikona)
├── core/
│   ├── model/           # Channel, Category, Programme, Movie, Series, Episode, Profile
│   ├── network/         # XtreamApi (Retrofit/OkHttp), M3uParser, XmltvParser (stream)
│   ├── database/        # Room: encje, DAO, migracje, FTS
│   ├── data/            # Repozytoria, synchronizacja (WorkManager), DataStore
│   └── ui/              # Theme (tv.material3), wspólne komponenty, focus utils
├── player/              # PlaybackEngine interface + ExoPlayerEngine (+ MpvEngine później)
└── feature/
    ├── setup/           # Logowanie do XUI.one (host, user, pass) / M3U URL
    ├── live/            # Lista kanałów + odtwarzacz + banner info + zapping
    ├── guide/           # Siatka EPG (timeline), catch‑up z siatki
    ├── vod/             # Filmy (kategorie, szczegóły, odtwarzanie, resume)
    ├── series/          # Seriale (sezony/odcinki)
    ├── search/          # Wyszukiwarka FTS po kanałach/VOD/serialach
    └── settings/        # Silnik, bufor, format strumienia, EPG offset, mapowanie pilota
```

Wzorzec: MVVM + UDF (`ViewModel` → `StateFlow<UiState>` → Compose), repozytoria
jako jedyne źródło prawdy (Room), sieć tylko w synchronizacji.

---

## 4. Model danych (Room)

- `sources` — id, typ (XTREAM/M3U), host, port, https, user, pass, nazwa, ostatnia sync
- `categories` — id, sourceId, typ (LIVE/VOD/SERIES), nazwa, parentId, hidden, order
- `channels` — streamId, sourceId, num, nazwa, logo, categoryId, epgChannelId,
  tvArchive, tvArchiveDays, directSource, favorite, lastWatched
- `programmes` — epgChannelId, start, stop, tytuł, opis, kategoria (indeks na
  `epgChannelId + start`)
- `movies` — streamId, nazwa, okładka, categoryId, containerExt, rating, added, plot…
- `series` / `seasons` / `episodes`
- `watch_progress` — (typ, id) → pozycja, czas, timestamp
- `profiles` — nazwa, PIN, kidsMode (faza 8)

---

## 5. Kontrakt API XUI.one (do zweryfikowania na Twoim panelu)

```
# auth + user_info/server_info
GET {base}/player_api.php?username=U&password=P

# kategorie / listy
?action=get_live_categories | get_vod_categories | get_series_categories
?action=get_live_streams[&category_id=X]
?action=get_vod_streams[&category_id=X]
?action=get_vod_info&vod_id=ID
?action=get_series[&category_id=X]
?action=get_series_info&series_id=ID

# EPG
?action=get_short_epg&stream_id=ID&limit=N        # title/description w base64 (UTF‑8!)
?action=get_simple_data_table&stream_id=ID        # pełny EPG jednego kanału
GET {base}/xmltv.php?username=U&password=P        # pełny XMLTV, bywa gzip

# M3U (fallback)
GET {base}/get.php?username=U&password=P&type=m3u_plus&output=ts|m3u8

# strumienie
live:     {base}/live/U/P/{stream_id}.ts | .m3u8   (XUI: /live/, klasyczny XC: bez /live/)
movie:    {base}/movie/U/P/{stream_id}.{container_extension}
series:   {base}/series/U/P/{episode_id}.{container_extension}
catch‑up: {base}/timeshift/U/P/{duration_min}/{YYYY-MM-DD:HH-MM}/{stream_id}.ts
   alt:   {base}/streaming/timeshift.php?username=U&password=P&stream=ID&start=YYYY-MM-DD:HH-MM&duration=MIN
```

Uwagi:
- `user_info.allowed_output_formats` mówi, czy można `.m3u8` czy tylko `.ts`.
- `server_info.timezone` + `timestamp_now` → korekta offsetu EPG.
- `tv_archive=1` i `tv_archive_duration` (dni) → pokazujemy catch‑up w siatce.
- `max_connections` / `active_cons` → ostrzeżenie zamiast błędu 403 przy zappingu.
- Panel może wymagać konkretnego `User-Agent` — ustawialny w Settings.

---

## 6. Plan realizacji — fazy (po kolei)

### Faza 0 — Szkielet projektu ✅ (następny krok)
- [ ] Gradle (Kotlin DSL), version catalog, moduły `app`, `core/*`, `player`
- [ ] Manifest TV (leanback, banner, LEANBACK_LAUNCHER)
- [ ] Theme na `androidx.tv.material3`, ekran "Hello TV" z fokusem D‑pad
- [ ] GitHub Actions: `./gradlew assembleDebug` + `lint` + testy jednostkowe
- [ ] README z instrukcją instalacji APK przez ADB na TV

### Faza 1 — Źródło Xtream i synchronizacja
- [ ] Ekran Setup: host/port/https, user, pass (klawiatura TV), test połączenia
- [ ] `XtreamApi` + modele kotlinx.serialization (tolerancyjne na `String`/`Int`)
- [ ] Synchronizacja kategorii + kanałów live do Room (WorkManager, co 12 h)
- [ ] Parser M3U (m3u_plus: `tvg-id`, `tvg-logo`, `group-title`, `catchup`) jako fallback
- [ ] Testy: MockWebServer z zrzutami odpowiedzi z Twojego panelu (zanonimizowane)

### Faza 2 — Live TV MVP
- [ ] Ekran Live: kolumna kategorii → lista kanałów (logo, numer, nazwa)
- [ ] `PlaybackEngine` + `ExoPlayerEngine` (HLS/TS, bufor konfigurowalny)
- [ ] Odtwarzacz pełnoekranowy, banner info (numer, nazwa, teraz/następnie) znikający po 5 s
- [ ] Zapping: CH+/CH‑, góra/dół, wpisywanie numeru kanału z pilota, ostatni kanał (BACK)
- [ ] Obsługa błędów: retry, przełączenie ts↔m3u8, komunikat "max connections"
- [ ] Ulubione (długie OK) + grupa "Ulubione" na górze

### Faza 3 — EPG
- [ ] Pobieranie `xmltv.php` (gzip), parser strumieniowy (XmlPullParser) → `programmes`
- [ ] Dopasowanie `epg_channel_id` ↔ `tvg-id`, offset czasu
- [ ] Mini‑EPG w bannerze (teraz/następnie z paskiem postępu)
- [ ] Siatka EPG (timeline, 30‑min siatka, fokus D‑pad, skok ±dzień, "teraz")
- [ ] Szczegóły programu, przypomnienia (opcjonalnie)

### Faza 4 — Catch‑up / Timeshift
- [ ] Z siatki EPG: odtwarzanie programu archiwalnego (URL `timeshift`)
- [ ] Przewijanie, pozycja, powrót do live
- [ ] Oznaczenie kanałów z archiwum w liście

### Faza 5 — VOD i Seriale
- [ ] Katalog filmów (siatka okładek, kategorie, szczegóły z `get_vod_info`)
- [ ] Seriale: sezony → odcinki, "kontynuuj oglądanie"
- [ ] Zapis postępu (`watch_progress`), wznawianie
- [ ] Napisy wbudowane / wybór ścieżki audio

### Faza 6 — Wyszukiwarka i UX
- [ ] FTS po kanałach, filmach, serialach (klawiatura TV + głos, jeśli dostępny)
- [ ] Ostatnio oglądane, ukrywanie kategorii, własna kolejność grup
- [ ] Ekran ustawień: silnik, bufor, format strumienia, User‑Agent, EPG offset, motyw

### Faza 7 — Odtwarzanie "trudnych" strumieni
- [ ] `media3-ffmpeg-decoder` (AC‑3/E‑AC‑3/DTS w software) + passthrough
- [ ] Opcjonalnie `MpvEngine` (libmpv) jako drugi silnik z automatycznym fallbackiem

### Faza 8 — Dopieszczanie
- [ ] Profile + PIN rodzicielski
- [ ] PiP / multiview (2 kanały)
- [ ] Backup/restore ustawień, mapowanie kolorowych klawiszy pilota
- [ ] Kanał "Watch Next" / Home channels na Google TV
- [ ] Baseline Profile, zimny start < 1,5 s

---

## 7. Co jest potrzebne od Ciebie

1. Adres panelu XUI.one (host:port) i czy działa po HTTPS — do testów na żywo
   (dane logowania trzymamy lokalnie, nie w repo; dodamy `local.properties`/`.env`).
2. Potwierdzenie `allowed_output_formats` (ts/m3u8) i czy panel ma włączony
   catch‑up (`tv_archive`) na części kanałów.
3. Model TV / przystawki (Android TV 11+? Fire TV?) — wpływa na minSdk i dekodery.

Zaczynamy od **Fazy 0** w następnym kroku.
