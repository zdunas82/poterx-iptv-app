# Poterx TV — plan rozwoju

Stan na 2026‑10‑21. Aplikacja jest forkiem OwnTV v5.2.1 (GPL‑3.0) z trybem zamkniętym:
serwer wbudowany i zaszyfrowany w APK, użytkownik loguje się loginem i hasłem, lista i EPG z panelu
XUI.one pobierają się same, start na ostatnim kanale, aktualizacje z wydań tego repozytorium.
Pierwotny plan budowy od zera (Compose for TV + Media3) został zarzucony na rzecz forka; analiza
projektów open source i paczek referencyjnych jest w `REFERENCE-APPS.md`.

## Zrobione
- Import OwnTV, rebranding (nazwa, identyfikator, ikona, baner, splash, wordmark), język polski.
- `LockedSource` (AES‑GCM z `BuildConfig`), `LockedOnboarding` (logowanie, import, EPG w tle),
  `LockedSourcesScreen` (Odśwież teraz, automatyczne odświeżanie), ukrycie backupu, synchronizacji
  lokalnej, dodawania źródeł i wyboru ikony. Updater wskazuje wyłącznie to repo.
- CI: testy + lint, APK release (R8), konto z sekretów lub formularza, podpis z sekretów
  `KEYSTORE_*` (bez nich klucz jednorazowy), wydanie na tag `v*`.
- Instrukcja dla klienta (`instrukcja-klienta.html`).

## Wydania
- **v0.1.0** (2026‑10‑21): pierwsze wydanie testowe, logowanie, lista, EPG, start na ostatnim kanale.
- **v0.1.1**: polski start, Auto frame rate wyłączone, EPG pobierane od razu po zalogowaniu,
  „Poterx TV” we wszystkich komunikatach, dolny pasek kanału (picon, teraz/następnie, zegar).

## v0.1.2 — po testach na Streamerze (dni)
- [ ] Poprawki z listy uwag: dolny pasek (rozmiary, czas, pokazywanie po OK), EPG, start.
- [ ] Pomiar zimnego startu i zappingu z logów `OwnTVPerf` na urządzeniu; lista wąskich gardeł.
- [ ] Krótki kod Downloadera (aftv.news) i podmiana w instrukcji.

## v0.2 — odchudzenie i zamknięcie (1–2 tygodnie)
- [ ] Wyłączone domyślnie w wersji zamkniętej: Sport Hub, pogoda, Trending, metadane TMDB,
      OpenSubtitles (serwery OwnTV). Mniej zapytań przy starcie, szybszy ekran główny.
- [ ] Mniejszy APK: tylko `pl` i `en` w `localeFilters`, usunięcie nieużywanych czcionek.
- [ ] Własny User‑Agent `PoterxTV/<wersja>` + blokada innych agentów na panelu XUI.one.
- [ ] Ekran **Konto** (status, ważność, limit połączeń z `user_info`) i **Wyloguj** za PIN‑em.
- [ ] Dane kontaktowe dostawcy wbudowane przy budowaniu; pokazywane przy błędach logowania.
- [ ] Testy jednostkowe `PoterxLink` i `LockedSource` w CI.

## v0.3 — obsługa klientów na odległość
- [ ] Plik JSON na serwerze dostawcy: ogłoszenia, minimalna wersja (wymuszona aktualizacja),
      zapasowy adres serwera.
- [ ] Wysyłka raportu błędu jednym przyciskiem.

## v0.4 — tryb „Poterx klasyczny” (TiviMate)
- [ ] OK na obrazie → lista kanałów nałożona na wideo (grupy, teraz/następnie, postęp),
      lewo/prawo → program kanału, Przewodnik → siatka nad obrazem, ulubione, ostatni kanał pod Back.

## v0.5 — infrastruktura (odłożone decyzją z 2026‑10‑21)
- [ ] VPS z HTTPS: APK, aktualizacje, instrukcja; repo prywatne; własny updater (JSON).
- [ ] WireGuard: `com.wireguard.android:tunnel`, tunel tylko dla aplikacji, klucze per klient.

## Stałe
- Co kilka tygodni `git merge` z upstreamu OwnTV i podbicie `owntvCore`; nasze zmiany są małe i
  oznaczone komentarzem `Poterx:`.
- Każde wydanie: tag `vX.Y.Z` → CI → wydanie; klienci dostają kartę aktualizacji.
