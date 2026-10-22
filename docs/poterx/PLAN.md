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
- **v0.1.2**: przewodnik rejestrowany przy każdym starcie (naprawa „Nie dodano EPG”), start na
  zakładce TV z listą wszystkich kanałów.
- **v0.1.3**: długie OK w odtwarzaczu → program kanału nad obrazem (archiwum, powrót na żywo); lista
  kanałów w odtwarzaczu z EPG najpierw wokół grającego kanału; pogoda i Sport Hub wyłączone
  domyślnie; tylko `pl` i `en` w APK.
- **v0.1.4**: przewodnik kanału nie zamyka się pod trzymanym OK.
- **v0.2.0**: Ustawienia › Konto (login, stan, ważność, połączenia), Wyloguj (za PIN-em
  serwisowym, gdy wbudowany), kontakt do dostawcy przy błędzie logowania, User-Agent
  `PoterxTV/<wersja>` na playliście.
- **v0.2.1** (w toku): „O aplikacji” bez odniesień do OwnTV: własna grupa Telegram z kodem QR,
  bez chipa GitHub (nota GPLv3 ze źródłami jako jedna mała linia), blok tłumaczeń ukryty, opisy
  aktualizacji, metadanych i napisów bez nazwy projektu.

## v0.1.2 — po testach na Streamerze (dni)
- [ ] Poprawki z listy uwag: dolny pasek (rozmiary, czas, pokazywanie po OK), EPG, start.
- [ ] Pomiar zimnego startu i zappingu z logów `OwnTVPerf` na urządzeniu; lista wąskich gardeł.
- [ ] Krótki kod Downloadera (aftv.news) i podmiana w instrukcji.

## v0.2 — odchudzenie i zamknięcie (1–2 tygodnie)
- [x] Wyłączone domyślnie w wersji zamkniętej: Sport Hub, pogoda (v0.1.3). TMDB zostaje (filmy
      i seriale); OpenSubtitles łączy się tylko na żądanie, więc nie wymaga przełącznika.
- [x] Mniejszy APK: tylko `pl` i `en` w `localeFilters` (v0.1.3, 57,7 → 53,1 MB).
- [ ] Mniejszy APK, ciąg dalszy: osobne APK per architektura albo wycięcie nieużywanych (libmpv,
      ffmpeg to większość paczki); usunięcie nieużywanych czcionek.
- [x] Własny User‑Agent `PoterxTV/<wersja>` (v0.2.0). Blokada innych agentów: po stronie panelu.
- [x] Ekran **Konto** (status, ważność, połączenia z `user_info`) i **Wyloguj** za PIN‑em (v0.2.0).
- [x] Dane kontaktowe dostawcy wbudowane przy budowaniu (`POTERX_CONTACT`); przy błędach logowania
      i na ekranie Konto (v0.2.0).
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
