# Analiza aplikacji referencyjnych (paczki od użytkownika)

Przejrzane 2026‑10‑21. Cztery archiwa — tylko jedno to aplikacja Android,
pozostałe to panele PHP dla "białych" apek IPTV (Smarters/Purple/XCIPTV/TiviMate).

| Paczka | Co to jest | Przydatność dla nas |
|---|---|---|
| `Streambox_app.zip` | **Aplikacja Android (Java)** "Streambox" firmy Nemosofts — komercyjny szablon (CodeCanyon). | **Wysoka** — gotowa logika Xtream/XUI, catch‑up, EPG, multi‑screen, fallbacki odtwarzacza. |
| `streambox.zip` | Panel PHP/MySQL do Streambox (admin: DNS, reklamy, użytkownicy, powiadomienia, polityki). | Niska — potrzebny tylko, gdy apka ma być "biała" dla klientów. My mamy jeden serwer. |
| `OnePanel-v1.4.0.zip` | Panel PHP/SQLite "OnePanel": zarządza DNS/konfiguracją dla XCIPTV, TiviMate, Smarters, Purple, LTQ, ImPlayer, Femto. Proxy `player_api.php` → prawdziwy panel. | Średnia — pokazuje, **jak klienci (TiviMate itp.) odpytują panel** i jak proxy przepuszcza `player_api.php` oraz strumienie. |
| `smarters.zip` | Backend PHP/SQLite dla zmodyfikowanego IPTV Smarters Pro v4 (DNS, reklamy, VPN, sport, raporty). | Niska — mechanika reklam/DNS; zawiera zaciemniony kod (`dns_.php`…`dns_____.php`) i plik `.ovpn`. Nie używać. |

> ⚠️ `Streambox_app.zip` zawiera **sekrety**: keystore `Key/streamdo.jks`,
> `app/google-services.json`, a w `gradle.properties` `BASE_URL`, `ENC_KEY`, `IV`.
> Nic z tego nie trafia do naszego repo. Kod Nemosofts jest komercyjny (licencja
> CodeCanyon) — traktujemy go jako **referencję logiki**, nie kopiujemy plików.

---

## Streambox (Android) — co robi i jak

**Stack:** Java 17, minSdk 23 / target 34, XML + RecyclerView (bez Leanback, bez
Compose), Media3 1.3.1 (ExoPlayer) + własne AAR‑y `lib-decoder-ffmpeg`,
`lib-decoder-av1`, OkHttp 4, Gson, Picasso, Firebase, OneSignal, AdMob, YouTube
player. Dane w `SharedPreferences` + ręczny `SQLiteOpenHelper`.
Sieć przez `AsyncTask` (przestarzałe). Jeden moduł `app`.

**Manifest TV:** `leanback required=false`, `touchscreen required=false`,
`android:banner`, `LAUNCHER` + `LEANBACK_LAUNCHER` na jednej Activity — czyli
apka "mobile‑first" z trybem TV, nie odwrotnie.

### Logowanie (LoadLogin)
- `POST {base}/player_api.php` z `username`, `password` (multipart form, nie GET).
- Czyta `user_info`: `auth`, `status`, `exp_date`, `is_trial`, `active_cons`,
  `max_connections`, `allowed_output_formats`.
- Czyta `server_info`: `url`, `port`, `https_port`, `server_protocol`, `rtmp_port`,
  `timestamp_now`, **`xui` (bool), `version`, `revision`** — te trzy pola są
  specyficzne dla XUI.one i pozwalają wykryć typ panelu.
- Base URL budowany z `server_protocol` + `url` + (`port` | `https_port`), a nie z
  tego, co wpisał użytkownik.

### URL strumienia live (PlayerLiveActivity.setMediaSource)
```
format = (live_format == 1) ? ".ts" : ".m3u8"      // ustawienie użytkownika, domyślnie m3u8
if (server_info.xui)  url = {base}/{user}/{pass}/{stream_id}{format}
else                  url = {base}/live/{user}/{pass}/{stream_id}{format}
```
Na XUI.one działają obie formy; wniosek: **honorować `allowed_output_formats`,
a prefiks `/live/` traktować jako opcję z fallbackiem.**

### Odtwarzacz
- `MediaSource` wybierany po `Util.inferContentType(uri)`: HLS / DASH / SS / RTSP /
  Progressive (TS).
- `DefaultHttpDataSource` z konfigurowalnym **User‑Agent** (`agent_name` w
  ustawieniach) i cookie managerem.
- `onPlayerError`: **5 prób** z opóźnieniem 600 ms, potem przycisk "Try again".
- `DefaultRenderersFactory` z rozszerzeniami FFmpeg (ac3/eac3/dts/truehd…) — bez
  tego część kanałów gra bez dźwięku na Android TV.
- Osobne Activity dla live / movie / episodes / local / single URL / YouTube —
  duplikacja ~80% kodu (u nas: jeden `PlayerScreen` + `PlaybackEngine`).

### EPG
- Banner: `get_short_epg` (POST, `stream_id`, limit) → `epg_listings[]`
  z `title` (base64), `start`, `end`, `start_timestamp`, `stop_timestamp`.
- Dwa widoki EPG (`EPGOneActivity`, `EPGTwoActivity`) — oba to **listy
  RecyclerView**, nie prawdziwa siatka timeline. Brak parsera XMLTV — EPG jest
  pobierane per kanał na żądanie, więc "siatka" jest wolna. U nas: XMLTV do Room.

### Catch‑up (CatchUpEpgActivity)
- Lista kanałów z `tv_archive=1`, zakładki per dzień z `tv_archive_duration`.
- `get_simple_data_table` → programy z `has_archive`.
- URL:
  `{base}/streaming/timeshift.php?username=U&password=P&stream=ID&start=YYYY-MM-DD:HH:mm:ss&duration=MIN`
  (`start` w strefie serwera, `duration` liczone z `end - start` w minutach).

### Baza lokalna (DBHelper)
`users` (wiele kont), `single` (pojedyncze URL‑e), `tbl_dns_xui`, `tbl_dns_stream`,
`fav_live|movie|series`, `recent_live|movie|series`, `movie_seek`, `epi_seek`
(pozycje wznawiania), `download_movie`. To dokładnie zestaw tabel, który i my
potrzebujemy — tylko w Room.

### Obsługa pilota
`dispatchKeyEvent` z: `MEDIA_PLAY/PAUSE/PLAY_PAUSE`, `MEDIA_NEXT/PREVIOUS`
(zmiana kanału), `DPAD_CENTER/ENTER` (pokaż kontrolki/listę), `BACK`, `VOLUME_*`
(własny `LoudnessEnhancer`), `BUTTON_A/START` (pady). Brak wpisywania numeru
kanału z klawiatury numerycznej — dodamy.

### Funkcje warte przeniesienia
- Multi‑screen (2/4 kanały jednocześnie) — `MultipleScreenActivity`.
- Wiele profili/kont (`UsersListActivity`) i tryb "Single stream URL".
- Ustawienia: format live (ts/m3u8), User‑Agent, auto‑odświeżanie list (co N dni),
  format czasu 12/24 h, wybór odtwarzacza zewnętrznego.
- Filtr dorosłych/kids (`AdultsCountDialog`, `ChildCountDialog`).
- Test prędkości sieci, pobieranie VOD offline (u nas faza 8, opcjonalnie).

### Czego **nie** przenosimy
Reklamy (AdMob, GDPR), Firebase/OneSignal, motywy sezonowe
(Christmas/Halloween/BlackPanther…), YouTube, szyfrowanie pobranych plików,
`HttpsTrustManager` akceptujący każdy certyfikat (dziura bezpieczeństwa),
`AsyncTask`, zmienne globalne `Callback.arrayListLive` jako "stan" aplikacji.

---

## OnePanel / Smarters / streambox‑panel — wnioski

- Wszystkie trzy to ten sam wzorzec: apka pyta "panel DNS" o listę hostów, potem
  loguje się na każdy przez `player_api.php?username&password`, przyjmuje
  pierwszy z `user_info.auth == 1 && status == "Active"`. My mamy jeden serwer,
  ale ta logika "lista hostów + pierwszy działający" to tani **failover**, jeśli
  kiedyś dodasz drugą domenę/port.
- OnePanel w trybie proxy przepuszcza strumienie z User‑Agentem
  `VLC/3.1.21-rc2 LibVLC/3.1.21-rc2` i podąża za `Location` (301) — panele
  XUI.one potrafią przekierowywać na load‑balancer; **ExoPlayer musi mieć
  włączone `setAllowCrossProtocolRedirects(true)`**.
- Timeout 5 s na `player_api.php` — rozsądny default dla "testu połączenia".
- OnePanel ma osobne konfiguracje dla TiviMate (`includes/tivimate.php`) —
  potwierdza, że TiviMate pobiera ten sam `player_api.php` + `xmltv.php`, nic
  więcej.

---

## Co z tego trafia do planu

1. Detekcja XUI po `server_info.xui/version/revision`; URL live z/bez `/live/`
   jako fallback; format z `allowed_output_formats`.
2. Retry odtwarzania (5× z backoffem) + przełączenie formatu po nieudanych próbach.
3. Catch‑up przez `streaming/timeshift.php` (format `YYYY-MM-DD:HH:mm:ss`),
   alternatywnie ścieżka `/timeshift/U/P/MIN/START/ID.ts`.
4. Konfigurowalny User‑Agent + cross‑protocol redirects w `HttpDataSource`.
5. Zestaw tabel Room = tabele Streambox + `programmes` (XMLTV) + `profiles`.
6. Multi‑screen i "wiele kont" jako fazy 8/opcjonalne.
7. Dekoder FFmpeg (ac3/eac3/dts) od razu w fazie 7, bo Streambox też go wozi.
