# Poterx TV

Zamknięta, przebrandowana wersja [OwnTV](https://github.com/ahXN00/OwnTV) na Android TV / Google TV /
Fire TV dla jednego panelu XUI.one / Xtream Codes. Dwa warianty, wybierane przy budowaniu:

| Wariant | Co jest wbudowane | Co robi użytkownik |
|---|---|---|
| **Konto wbudowane** | serwer + login + hasło | instaluje i ogląda |
| **Serwer wbudowany** | tylko serwer (ukryty) | przy pierwszym uruchomieniu wpisuje login i hasło |

W obu adres serwera nigdy nie jest pokazywany; nie da się go podejrzeć, zmienić, wyeksportować
ani zsynchronizować na inne urządzenie z poziomu aplikacji.

Licencja: **GPL-3.0** (po OwnTV). Kod tego forka jest publiczny; konto **nie jest** w kodzie —
trafia do APK dopiero przy budowaniu (zaszyfrowane AES‑GCM, R8 w wydaniu release).

> Uczciwie: to utrudnienie, nie gwarancja. Ktoś z APK i narzędziami może konto odzyskać, a
> adresy strumieni i tak idą siecią (Twój panel działa po HTTP). Włącz HTTPS na panelu i limit
> połączeń na koncie — to realna ochrona.

## Jak zbudować APK z wbudowanym kontem

### A. GitHub Actions (zalecane, nie potrzebujesz Android Studio)

1. W repozytorium: **Settings → Secrets and variables → Actions → New repository secret**
   - konto wbudowane: `POTERX_PLAYLIST_URL` = linijka z panelu, np.
     `http://host:80/playlist/UZYTKOWNIK/HASLO/m3u_plus` (albo trzy osobne: `POTERX_SERVER`,
     `POTERX_USERNAME`, `POTERX_PASSWORD`);
   - serwer wbudowany: tylko `POTERX_SERVER` = `http://host:80` — aplikacja poprosi o login i hasło.
   - (opcjonalnie, do podpisu wydań) `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
2. Zakładka **Actions → Android CI → Run workflow**. Możesz też podać linijkę (albo sam serwer) w
   polu formularza — wtedy buduje się APK dla konkretnego klienta bez zmiany sekretów.
3. Po kilku minutach pobierz artefakt `PoterxTV-….apk` i zainstaluj na TV
   (`adb install -r PoterxTV.apk` lub Downloader na Fire TV).

Bez sekretu konto nie jest wbudowane i aplikacja zachowuje się jak zwykłe OwnTV (kreator źródeł).

### B. Lokalnie

```sh
cp poterx.properties.example poterx.properties   # wpisz linijkę; plik jest w .gitignore
./gradlew :app:assembleStandardRelease            # podpis: patrz app/build.gradle.kts (owntv.keystore*)
```

Wynik: `app/build/outputs/apk/standard/release/*.apk`.

## Co jest zablokowane w wersji z kontem

| Miejsce | Zachowanie |
|---|---|
| Pierwsze uruchomienie | Bez kreatora: profil + import wbudowanej listy, potem od razu aplikacja; wariant „serwer wbudowany” pokazuje wcześniej tylko ekran logowania (login, hasło) |
| Ustawienia › Playlisty | „Lista kanałów”: tylko **Odśwież teraz** i **Automatyczne odświeżanie** (wył. / przy starcie / 6 h / 12 h / co N dni, domyślnie 12 h); bez dodawania, edycji, usuwania i bez adresu serwera |
| EPG | Przewodnik z `xmltv.php` Twojego panelu pobiera się sam zaraz po zalogowaniu i odświeża według ustawień Ustawienia › Źródła EPG |
| Więcej › Kopia zapasowa, Synchronizacja lokalna | Ukryte (plik `.own` / sync przeniósłby konto) |
| Dodawanie źródła z telefonu (QR) | Niedostępne (część ukrytego ekranu źródeł) |
| Aktualizacje | Wbudowany updater sprawdza **tylko wydania tego repo** (tag `v*`): karta „Dostępna aktualizacja” przy starcie, instalacja jednym OK. Wymaga stałego klucza (`KEYSTORE_*`) |
| Ikona aplikacji | Wybór ukryty (pozostałe ikony niosą markę OwnTV) |
| Nowy profil | Automatycznie podpina wbudowaną listę |

Implementacja: `app/src/main/java/tv/own/owntv/poterx/` (`LockedSource`, `LockedOnboarding`,
`LockedInfoScreen`) + punkty w `MainActivity`, `SettingsScreen`, `MoreScreen`, `OwnTVShell`,
`OwnTVApp`, `BrandLockup`. Każdy jest opatrzony komentarzem `Poterx:`.

## Aktualizacje z OwnTV

Rdzeń (dane, synchronizacja, odtwarzacz) przychodzi jako artefakty `tv.own.owntv:core` /
`:player-core` z Mavena OwnTV — podbicie `owntvCore` w `gradle/libs.versions.toml` wystarcza na
większość poprawek. Zmiany w warstwie aplikacji wciągamy przez `git merge` z upstreamu; nasze
modyfikacje są małe i oznaczone.

Dokumentacja projektu: [`docs/poterx/`](docs/poterx/) (plan, analiza aplikacji referencyjnych,
oryginalne README OwnTV).
