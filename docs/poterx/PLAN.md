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

## Etap 1 — pierwsze wydanie testowe (teraz)
- [ ] Sekrety w repo: `POTERX_SERVER`, `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
- [ ] Tag `v0.1.0` → wydanie → test na Google TV Streamer 4K: logowanie, lista, EPG, catch‑up,
      zapping, powrót z uśpienia, start na ostatnim kanale, karta aktualizacji.
- [ ] Lista uwag z testu (wygląd, szybkość, stabilność) → poprawki punkt po punkcie.
- [ ] Krótki kod Downloadera (aftv.news) i podmiana w instrukcji.

## Etap 2 — zamknięcie linii i obsługa klienta
- [ ] **Własny User‑Agent** (`PoterxTV/<wersja>`) na źródle i w odtwarzaczu + na panelu XUI.one
      blokada innych agentów → linia działa tylko w tej aplikacji (TiviMate/Smarters odpadają).
- [ ] Ekran **Konto** w Ustawieniach: status i data ważności z `user_info` (XUI zwraca `exp_date`,
      `max_connections`, `active_cons`), czytelny komunikat „konto wygasło” z kontaktem do dostawcy.
- [ ] **Wyloguj / zmień dane logowania** (usunięcie źródła i powrót do ekranu logowania) z PIN‑em,
      żeby klient nie zrobił tego przypadkiem.
- [ ] **Dane kontaktowe dostawcy** wbudowane przy budowaniu (telefon/Telegram) i pokazywane przy
      błędach logowania oraz w sekcji Więcej.
- [ ] Testy jednostkowe `PoterxLink` (parser linijek) i `LockedSource` (szyfrowanie), uruchamiane w CI.

## Etap 3 — komunikaty i zdalna konfiguracja
- [ ] Mały plik JSON na Twoim serwerze (adres wbudowany w APK): ogłoszenia dla klientów („przerwa
      techniczna 22:00–23:00”), minimalna wymagana wersja (wymuszenie aktualizacji), opcjonalnie
      alternatywny adres serwera na wypadek zmiany domeny. Wzorzec z paneli Smarters/OnePanel, ale
      bez reklam.
- [ ] Zgłaszanie błędów: eksport dziennika OwnTV jest ręczny; dodać wysyłkę raportu jednym
      przyciskiem na Twój serwer (bez danych osobowych, tylko log odtwarzania i wersja).

## Etap 4 — VPS, własny hosting i VPN (odłożone decyzją z 2026‑10‑21)
- [ ] VPS (np. Hetzner CX22) z HTTPS: APK, plik aktualizacji, instrukcja; repo GitHub na prywatne.
- [ ] Własny updater (JSON: wersja, adres APK, opis zmian) zamiast API GitHuba.
- [ ] **WireGuard**: biblioteka `com.wireguard.android:tunnel`, przełącznik w Ustawieniach,
      autostart, tunel tylko dla tej aplikacji (`addAllowedApplication`), konfiguracja per klient
      pobierana z Twojego serwera, skrypt na VPS do generowania kluczy. HTTPS na panelu XUI.one.

## Etap 5 — wygląd i wygoda (po pierwszych testach)
- [ ] Własna grafika: logo/ikona od Ciebie zamiast wygenerowanego znaku, domyślny akcent i motyw.
- [ ] Domyślne ustawienia dla skrzynki TV: podgląd kanału, tryb opóźnienia, numeracja kanałów,
      ekran główny vs. prosty tryb „tylko Live TV” (ukrycie Filmów/Seriali, gdy panel ich nie ma).
- [ ] Kanał „Następne” na ekranie głównym Google TV (OwnTV to wspiera; sprawdzić w trybie zamkniętym).
- [ ] Opcjonalnie wymuszony polski przy pierwszym uruchomieniu niezależnie od języka systemu.

## Stałe
- Co kilka tygodni `git merge` z upstreamu OwnTV i podbicie `owntvCore`; nasze zmiany są małe i
  oznaczone komentarzem `Poterx:`.
- Każde wydanie: tag `vX.Y.Z` → CI → wydanie; klienci dostają kartę aktualizacji.
