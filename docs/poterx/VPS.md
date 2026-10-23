# Serwer aktualizacji Poterx TV — plan konfiguracji VPS

Cel: aplikacja u klientów nie dotyka GitHuba. APK, `update.json`, changelog, komunikaty od dostawcy
i instrukcja instalacji leżą na własnym serwerze z HTTPS. Panel XUI zostaje na swoim adresie;
serwer aktualizacji ma **osobną domenę**, żeby ruch aplikacji nie zdradzał adresu panelu.

## 1. Założenia

| Element | Decyzja |
| --- | --- |
| Maszyna | VPS w Polsce (ten, który masz), Ubuntu 24.04 LTS, 1 vCPU / 1 GB RAM wystarczy |
| Domena | np. `app.<twoja-domena>` (rekord A na IP VPS); nie subdomena panelu |
| Serwer WWW | Caddy 2 — sam wystawia i odnawia certyfikat Let's Encrypt |
| Pliki | katalog `/srv/poterx/www`, serwowany statycznie, bez listowania katalogów |
| Wgrywanie | CI (GitHub Actions) po tagu `vX.Y.Z` przez `rsync` po SSH na osobnego użytkownika |
| Kanały | `test` (Twoje urządzenia) i `stable` (klienci); promocja = jedno polecenie na VPS |

## 2. Układ plików na serwerze

```
/srv/poterx/www/
├── update.json            # kanał stable – to czytają klienci
├── update-test.json       # kanał test – Twoje urządzenia
├── apk/
│   ├── poterx-tv-0.2.6.apk
│   └── poterx-tv-0.2.6.apk.sha256
├── changelog/
│   ├── 0.2.6.md           # po polsku, bez linków
│   └── latest.md
├── messages.json          # komunikaty od dostawcy (przerwa techniczna, nowe kanały)
└── install/
    ├── index.html         # instrukcja dla klientów + QR do APK
    └── qr.png
```

### `update.json`

```json
{
  "versionCode": 206,
  "versionName": "0.2.6",
  "apkUrl": "https://app.<domena>/apk/poterx-tv-0.2.6.apk",
  "sha256": "…64 znaki hex…",
  "sizeBytes": 55700000,
  "notes": "- Baner kanału po powrocie do poprzedniego kanału\n- Nazwa kanału przy wpisywaniu numeru",
  "minVersionCode": 200,
  "publishedAt": "2026-10-23T18:00:00Z"
}
```

- `minVersionCode`: wersje starsze dostają kartę „wymagana aktualizacja” bez przycisku „Później”.
- `notes`: treść `changelog/X.Y.Z.md` wklejona przez CI (markdown jak dziś: nagłówki `###`, punkty `- `).
- `update-test.json` ma tę samą postać; aplikacja w trybie testowym czyta ten plik.

### `messages.json`

```json
{
  "items": [
    {
      "id": "2026-10-25-przerwa",
      "from": "2026-10-25T20:00:00Z",
      "to": "2026-10-26T06:00:00Z",
      "title": "Przerwa techniczna",
      "body": "W nocy z soboty na niedzielę 02:00–04:00 możliwe krótkie przerwy.",
      "level": "warning"
    }
  ]
}
```

Aplikacja pokazuje komunikat raz (po `id`) na ekranie głównym, w oknie czasu `from`–`to`.

## 3. Konfiguracja VPS krok po kroku (ok. 1 godzina)

1. **System i dostęp**
   ```bash
   apt update && apt -y upgrade
   adduser --disabled-password poterx          # właściciel plików, bez hasła
   apt -y install ufw fail2ban unattended-upgrades rsync
   ufw allow OpenSSH && ufw allow 80 && ufw allow 443 && ufw enable
   ```
   W `/etc/ssh/sshd_config`: `PasswordAuthentication no`, `PermitRootLogin prohibit-password`.
2. **Caddy** (repozytorium Caddy, `apt install caddy`), `/etc/caddy/Caddyfile`:
   ```
   app.<domena> {
       root * /srv/poterx/www
       file_server
       encode gzip
       header {
           Strict-Transport-Security "max-age=31536000"
           X-Content-Type-Options nosniff
           -Server
       }
       @json path *.json
       header @json Cache-Control "no-cache"
       @apk path /apk/*
       header @apk Cache-Control "public, max-age=31536000, immutable"
       log {
           output file /var/log/caddy/app.log
           format json
       }
       handle_errors {
           respond "Not found" 404
       }
   }
   ```
   `systemctl reload caddy`. Certyfikat pojawi się sam po ustawieniu rekordu A.
3. **Katalogi**
   ```bash
   mkdir -p /srv/poterx/www/{apk,changelog,install}
   chown -R poterx:poterx /srv/poterx
   ```
4. **Klucz dla CI**: na swoim komputerze `ssh-keygen -t ed25519 -f poterx-ci -N ""`.
   Klucz publiczny do `/home/poterx/.ssh/authorized_keys` **z ograniczeniem do rsync**:
   ```
   command="/usr/bin/rrsync /srv/poterx/www",no-pty,no-port-forwarding,no-agent-forwarding ssh-ed25519 AAAA… poterx-ci
   ```
   (`rrsync` jest w pakiecie `rsync`, w `/usr/share/doc/rsync/scripts/` — skopiować do `/usr/bin`
   i nadać prawa wykonywania). Klucz prywatny trafia do sekretów GitHuba jako `VPS_SSH_KEY`,
   do tego `VPS_HOST=app.<domena>` i `VPS_USER=poterx`.
5. **Skrypt promocji** `/usr/local/bin/poterx-promote` (uruchamiany przez Ciebie po testach):
   ```bash
   #!/bin/sh
   # użycie: poterx-promote 0.2.6
   set -e
   cd /srv/poterx/www
   test -f "apk/poterx-tv-$1.apk"
   cp update-test.json update.json
   cp "changelog/$1.md" changelog/latest.md
   echo "Opublikowano $1 dla klientów"
   ```
   Alternatywa bez logowania na VPS: workflow `promote.yml` w GitHubie (`workflow_dispatch`
   z polem `version`), który robi to samo przez SSH.

## 4. CI: co się dzieje po tagu

1. Build i podpis APK tym samym keystore co dziś (inny podpis = Android odmówi aktualizacji).
2. `sha256sum` APK, rozmiar, data.
3. Złożenie `update-test.json` z `docs/poterx/changelog/X.Y.Z.md` (plik piszę razem z kodem,
   po polsku, bez linków).
4. `rsync` do `/srv/poterx/www/apk/` i `/srv/poterx/www/changelog/`, podmiana `update-test.json`.
5. Wydanie na GitHubie zostaje jako kopia zapasowa, ale aplikacja już go nie czyta.

## 5. Aplikacja (po mojej stronie, ok. 1 dzień)

- `PoterxUpdater` zamiast `UpdateManager` z core w wersji zamkniętej: pobiera `update.json`
  (`User-Agent: PoterxTV/<wersja>`), porównuje `versionCode`, pobiera APK do pamięci aplikacji,
  sprawdza SHA‑256, instaluje przez `PackageInstaller` (jak dziś). Błędy po polsku, bez adresów.
- Adres bazowy z sekretu `POTERX_UPDATE_URL` zapieczętowany w blobie (jak serwer panelu).
- Tryb testowy: w Ustawieniach › Konto przełącznik „Kanał testowy” za PIN‑em serwisowym.
  Włączony czyta `update-test.json`.
- `minVersionCode`: karta „Ta wersja nie jest już obsługiwana” z jednym przyciskiem „Aktualizuj”.
- `messages.json` sprawdzany przy starcie razem z `update.json`.
- Logi i eksport raportu nadal maskują adresy.

## 6. Bezpieczeństwo i utrzymanie

- Tylko HTTPS; HTTP przekierowuje Caddy. HSTS włączone.
- Użytkownik `poterx` bez powłoki, klucz CI ograniczony do `rrsync` jednego katalogu.
- Keystore nigdy nie leży na VPS — podpis robi CI.
- Aplikacja sprawdza SHA‑256 z JSON‑a, a Android sprawdza podpis APK; podmiana pliku na serwerze
  bez klucza podpisu nie zainstaluje się.
- `unattended-upgrades` dla łatek systemu, `fail2ban` dla SSH.
- Kopia: `/srv/poterx` to tylko pliki odtwarzalne z CI; wystarczy `tar` raz w tygodniu do
  `/root/backup` albo na drugi serwer.
- Statystyki: z logu Caddy po `User-Agent` widać, ile urządzeń pyta o aktualizację i w jakich
  wersjach (bez żadnych danych osobowych): `grep -o 'PoterxTV/[0-9.]*' /var/log/caddy/app.log | sort | uniq -c`.
- Monitoring: darmowy UptimeRobot na `https://app.<domena>/update.json`.

## 6a. Stan wdrożenia (v0.3.0)

- Zrobione po stronie aplikacji i CI: `POTERX_UPDATE_URL` w blobie, `PoterxUpdater` (SHA‑256,
  `minVersionCode`, kanał testowy za PIN‑em), krok „Publish to the update server” w `android.yml`
  (po tagu, na kanał testowy), workflow „Promote update” (`promote.yml`, pole `version`), opis wydania
  z `docs/poterx/changelog/<wersja>.md`.
- Sekrety repo: `POTERX_UPDATE_URL`, `VPS_HOST`, `VPS_USER`, `VPS_SSH_KEY`. Zmienna repo (Settings ›
  Variables) `POTERX_MIN_VERSION_CODE` — kod wersji, poniżej której aplikacja wymusza aktualizację
  (np. `300` dla 0.3.0; puste = nic nie wymusza).
- Promocja bez logowania na VPS: Actions › „Promote update” › Run workflow › wersja. Workflow
  sprawdza SHA‑256 pliku APK na serwerze, zapisuje `update.json` i `changelog/latest.md`, a potem
  przestawia wydanie `vX.Y.Z` na GitHubie na „latest” (bez „pre-release”) — klienci na v0.2.x
  czytają jeszcze GitHub, nowsi serwer, jeden przycisk obsługuje obie grupy. Build z tagu tworzy
  wydanie jako pre-release, więc do promocji nikt nic nie dostaje.
- Sekret `VPS_KNOWN_HOSTS`: wynik `ssh-keyscan -H app.poter.live` zapisany raz; bez niego CI ufa
  pierwszej odpowiedzi serwera (ostrzeżenie w logu).
- Jeszcze nie: `messages.json` w aplikacji, strona `install/` z QR.

## 7. Kolejność wdrożenia

1. Ty: domena + rekord A, kroki 1–4 z sekcji 3 (albo dajesz mi dostęp SSH i robię to sam).
2. Ja: `POTERX_UPDATE_URL`, `PoterxUpdater`, kanał testowy, karta wymaganej aktualizacji,
   komunikaty, workflow CI z `rsync` i `promote.yml`, instrukcja `install/index.html` z QR.
3. Test: wydanie `vX.Y.Z` → `update-test.json` → Twój Shield widzi aktualizację → `poterx-promote`
   → klienci.
4. Dopiero po tym wyłączamy sprawdzanie GitHub Releases w aplikacji.

Czego potrzebuję od Ciebie, żeby zacząć część aplikacyjną: nazwę domeny (sam adres, bez serwera
jeszcze) i decyzję, czy kanał testowy ma być za PIN‑em w Ustawieniach (proponuję tak).
