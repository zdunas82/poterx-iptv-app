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
- **v0.2.1**: „O aplikacji” bez odniesień do OwnTV: własna grupa Telegram z kodem QR, bez chipa
  GitHub, blok tłumaczeń ukryty, opisy aktualizacji, metadanych i napisów bez nazwy projektu. Własny klucz TMDB (`POTERX_TMDB_KEY`)
  wbudowany w aplikację, bo wspólna usługa OwnTV odpowiada 403. Konto: statusy po polsku, pola
  tylko do odczytu bez strzałek. Wyszukiwanie: kanał o nazwie równej zapytaniu na górze.
- **v0.2.2** (w toku): adres panelu nigdzie na ekranie: filtr `PoterxPrivacy` maskuje host w
  informacjach o strumieniu (wiersz „źródło” usunięty), w błędach odtwarzacza, synchronizacji i
  logowania, w dzienniku błędów i jego eksporcie; zakładka EPG w wersji zamkniętej to tylko
  odświeżanie przewodnika, bez adresu i edycji. „O aplikacji” bez linii o licencji i pochodzeniu
  kodu (decyzja właściciela z 2026-10-22). Po audycie v0.2.1: logi informacyjne (Log.v/d/i/w)
  wycięte z wydania przez R8, bo klient HTTP rdzenia logował `api_key`; lista kanałów przechodzi
  do następnego programu (wpis z minionym końcem liczy się jako brak); metadane TMDB domyślnie po
  polsku (opisy, gatunki, zwiastuny); sekcja „Teraz popularne”, której pierwsze pobranie padło,
  próbuje ponownie 25 s po starcie. Film bez szczegółów TMDB dostaje jedną ponowną próbę przy
  otwarciu menu (poza opóźnieniem fokusu; zapamiętany „brak dopasowania” jest czyszczony), a
  „Szczegóły filmu” bez danych pobierają je zamiast nic nie robić. Nagłówek Filmów liczy wyniki
  wyszukiwania, data pod zegarem jaśniejsza, podgląd TV gra dalej przy przeglądaniu kategorii.
  OK na plakacie filmu otwiera stronę tytułu (opis, obsada, przyciski Odtwórz, Zwiastun,
  Ulubione, Pobierz szczegóły); film startuje dopiero z „Odtwórz”.
- **v0.2.3** (w toku, po audycie v0.2.2): etykiety karty archiwum bez łamania liter, szerszy panel
  informacji o strumieniu; stare wpisy TMDB z czasu awarii usługi czyszczone raz przy aktualizacji,
  a niepełne (bez tła i obsady) pobierane ponownie; wygaszenie górnej krawędzi siatki filmów;
  podpowiedź „OK Otwórz”; strona filmu otwiera się od góry, zwiastun wraca na stronę filmu; podgląd
  TV uzbrojony od startu; wyszukiwarka filmów nie pokazuje w banerze tytułu spoza wyników; opis na
  Home z playlisty (po polsku) przed angielskim z TMDB; zapping strzałkami po całej liście w
  kolejności numerów (decyzja: jak w dekoderze).
- **v0.2.4** (w toku, po audycie v0.2.3): zapping po całej liście także z listy (nie tylko z
  wczytanej strony 120 pozycji) i po wpisaniu numeru; podgląd TV ma kanał od startu (ostatni
  oglądany); ◀ na wierszu przewodnika cofa w czasie zamiast wychodzić do menu; karta w odtwarzaczu
  mieści się w prawej połowie („16:25 – 17:05 · pozostało 25 min”, etykieta „Później”, chipy bez
  łamania); strona filmu nie przesuwa się przy fokusie na przyciskach.
- **v0.2.5** (w toku): poprzedni kanał — Back dwa razy (do 0,4 s) na kanale na żywo wraca do
  kanału oglądanego wcześniej (pojedynczy Back nadal wychodzi z odtwarzacza); klawisz „ostatni
  kanał” na pilotach, które go mają, robi to samo. Karta „Subskrypcja wygasa za N dni” raz
  dziennie przy starcie (7 dni przed datą z panelu i po niej) z datą, kontaktem i kodem QR Telegrama.
- **v0.2.6** (w toku, po audycie v0.2.5): „Teraz popularne” nie podstawia filmu z playlisty o tym
  samym tytule, ale innym roku; baner kanału także po powrocie do poprzedniego kanału i po wyborze
  z listy; baner z „od – do · pozostało”; karta „na żywo” w odtwarzaczu z tłem (czytelna na jasnym
  obrazie), chipy nie są ucinane w środku wartości, panel informacji szerszy; dymek synchronizacji
  nad podpowiedziami; przy wpisywaniu numeru widać nazwę kanału.
- **v0.2.7** (w toku): karta aktualizacji bez adresów GitHuba w opisie wydania (linki i wiersze
  „Full Changelog” wycinane w wersji zamkniętej); plan VPS pod aktualizacje (`VPS.md`); propozycja
  Info bar 2 zapisana w planie.
- **v0.3.3** (w toku, pre-release do testów na Shieldzie): VPN w aplikacji (Ustawienia › Sieć › VPN): tunel WireGuard (biblioteka
  `com.wireguard.android:tunnel`) tylko dla pakietu Poterx, import profilu z pliku `.conf` lub
  wklejonego tekstu (np. Surfshark › konfiguracja ręczna), zapisane profile szyfrowane kluczem z
  Android Keystore, Połącz/Rozłącz ze stanem (handshake, ruch), „Łącz przy starcie”. Bez systemowego
  kill switcha; zewnętrzny odtwarzacz poza tunelem. Konto Surfshark: logowanie, rejestracja klucza,
  wybór najmniej obciążonego serwera w kraju, odnowienie klucza. **Konto dostawcy wbudowane**
  (`POTERX_VPN_USER`/`POTERX_VPN_PASS` w blobie): klient widzi tylko suwak, stan i kraj; suwak sam
  loguje, rejestruje klucz, łączy i zostaje włączony po restarcie.
- **v0.3.2** (2026‑10‑23, tag f5a58ab, opublikowana dla klientów): siatki filmów i seriali dosuwają rząd do górnej krawędzi,
  więc podpisy poprzedniego rzędu nie zostają pod paskiem; na stronie serialu ustępuje przycisk
  odtwarzania, nie „Pobierz sezon”.
- **v0.3.1** (2026‑10‑23, tag 6d3ac04): pierwsza wersja dostarczona z serwera aktualizacji (kanał
  testowy), bez zmian w funkcjach.
- **v0.3.0** (2026‑10‑23, tag 40e1015, na kanale testowym): własny serwer aktualizacji (`POTERX_UPDATE_URL`, `PoterxUpdater`: `update.json`,
  SHA‑256, `minVersionCode` → karta „Wymagana aktualizacja”); kanał testowy za PIN‑em w Ustawieniach ›
  Konto; CI po tagu wgrywa APK, changelog i `update-test.json` na VPS (`rsync`), opis wydania z
  `docs/poterx/changelog/<wersja>.md`; workflow „Promote update” publikuje wersję dla klientów.

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

## Procedura wydania (od testów u klientów, 2026‑10‑23)
1. Nowy commit przechodzi CI (Android CI, gałąź `claude/android-tv-m3u-app-41s6di`).
2. Wydanie na GitHubie z tagiem `vX.Y.Z` **z zaznaczonym „Set as a pre-release”** (od v0.3.0 CI
   i tak oznacza je jako pre-release, a opis bierze z `docs/poterx/changelog/X.Y.Z.md`). Opis
   po polsku, bez linków; nie klikać „Generate release notes”. Aplikacja
   pyta o `releases/latest`, a GitHub nie liczy tam pre-release — klienci nic nie widzą.
3. Instalacja APK z pre-release na własnym Shieldzie/Streamerze, test według listy niżej.
4. Po testach: Actions › „Promote update” › wersja. Workflow zapisuje `update.json` na serwerze
   (klienci od v0.3.0) i odznacza „pre-release” na GitHubie (klienci na v0.2.x). Od tej chwili
   wszyscy dostają kartę „Nowa wersja” przy starcie.
5. Wpadka po publikacji: ponowne zaznaczenie „pre-release” cofa ofertę aktualizacji dla
   klientów, którzy jeszcze jej nie pobrali.

Lista testów przed publikacją: start zimny (logowanie bez pytania o dane), lista kanałów + EPG,
zapping ▲▼ i podwójny Back, przewodnik (długie OK), archiwum, film → strona szczegółów → Play,
serial, Ustawienia › Konto (status, ważność), O aplikacji (brak adresów), aktualizacja z
poprzedniej wersji bez utraty danych.

## v0.3 — obsługa klientów na odległość
- [x] **Własny serwer aktualizacji** zamiast GitHub Releases — plan w `docs/poterx/VPS.md`, wdrożone
      w v0.3.0 (bez `messages.json` i strony `install/`, które zostają w planie):
      - plik `https://<domena>/poterx/update.json` z polami `versionCode`, `versionName`,
        `apkUrl`, `sha256`, `notes` (PL), `minVersionCode` (wymuszona aktualizacja),
        `channel` (`test`/`stable` — kanał testowy dla własnych urządzeń);
      - APK obok (`poterx-tv-X.Y.Z.apk`), publikowane przez CI (`scp`/`rsync` po tagu);
      - w aplikacji `PoterxUpdater` (zastępuje `UpdateManager` z core w wersji zamkniętej):
        sprawdzenie przy starcie, pobranie, instalacja przez `PackageInstaller`, suma SHA‑256,
        `User-Agent: PoterxTV/<wersja>`; adres z `POTERX_UPDATE_URL` (sekret, nigdy na ekranie);
      - serwer: VPS z HTTPS (Caddy daje certyfikat sam) albo hosting statyczny (Cloudflare R2,
        Bunny); osobna domena od panelu, żeby aplikacja nie zdradzała adresu panelu w ruchu.
- [ ] Plik JSON na serwerze dostawcy: ogłoszenia, minimalna wersja (wymuszona aktualizacja),
      zapasowy adres serwera (ten sam `update.json`).
- [ ] Wysyłka raportu błędu jednym przyciskiem.

## Info bar 2 — propozycja (makieta: https://claude.ai/artifact/WFy91YgHj3ucnj39YZ7HnC)
- Otwieranie: INFO, albo OK na pasku 1; zamykanie: INFO, Back, albo samo po 12 s (czas w
  Ustawieniach: 8 / 12 / 20 s / aż zamknę). OK na gołym obrazie nadal daje sterowanie odtwarzacza.
  Długie OK (dzisiejszy przewodnik kanału) wchodzi do Info bar 2 w trybie przeglądania programów.
- Układ (dolne 40% ekranu, ciemna szyba): nagłówek (logo, numer, nazwa, plakietki: rozdzielczość
  i kl/s, proporcje, silnik, „Archiwum 7 dni”, „Napisy”, ulubiony; zegar); TERAZ (tytuł,
  „od – do · pozostało”, pasek postępu z godzinami, opis z EPG do 3 wierszy, ▼ rozwija);
  PÓŹNIEJ (4 audycje); przyciski: Od początku, Przewodnik, Przypomnij, Nagraj, Dźwięk, Napisy,
  Informacje, Ulubione.
- Pilot: ◀ ▶ programy tego kanału w czasie (miniona → OK odtwarza z archiwum, przyszła →
  Przypomnij / Nagraj); ▲ ▼ podgląd sąsiedniego kanału bez przełączania (numer na pomarańczowo,
  OK przełącza, Back wraca); kolorowe klawisze: czerwony Nagraj, zielony Przypomnij, żółty Dźwięk,
  niebieski Napisy.
- Etapy: (1) plansza + przyciski + otwieranie i auto-ukrywanie, ~1 dzień; (2) ◀ ▶ z archiwum,
  przypomnieniem i nagraniem, ~0,5–1 dnia; (3) ▲ ▼ i kolorowe klawisze, ~0,5 dnia.
- Do decyzji: sposób otwierania, czy ▲ ▼ podgląda czy przełącza, czas znikania.

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
