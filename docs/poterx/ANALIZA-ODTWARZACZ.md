# Odtwarzacz Live TV — jak działa dziś i jak dodać EPG kanału podczas oglądania

Stan na 2026‑10‑21, OwnTV v5.2.1 / core 1.0.69. Pliki: `app/.../player/PlayerHud.kt` (klawisze, warstwy),
`PlayerHudChrome.kt` (górny pasek, karty), `features/shell/OwnTVShell.kt` (co odtwarzacz dostaje z aplikacji),
`features/live/LiveViewModel.kt` (dane kanału/EPG, catch‑up), core `live/LiveEpgReader.kt` (odczyt EPG).

## Pilot podczas oglądania (pełny ekran, kontrolki ukryte)

| Przycisk | Dziś |
|---|---|
| ▲ / CH+ / Media Next | następny kanał (wg numeru); dolny pasek Poterx na 5 s |
| ▼ / CH− / Media Prev | poprzedni kanał; jw. |
| cyfry | bezpośrednie strojenie numeru (karta z cyframi, auto‑zatwierdzenie) |
| ◀ | **lista kanałów nad obrazem** (`ChannelListOverlay`, z programem „teraz”); przy włączonym „◀▶ przewija” na kanale z archiwum: cofnięcie o krok |
| ▶ | **historia kanałów nad obrazem**; jw. krok do przodu |
| OK / Enter | pokazuje kontrolki (HUD) |
| Back | wyjście z pełnego ekranu do listy |
| Guide / kolorowe / wolne przyciski | akcje ze „Skrótów pilota” (np. OTWÓRZ PRZEWODNIK = zakładka Przewodnik, poza pełnym ekranem) |
| Play/Pause, Stop | transport |

Z widocznymi kontrolkami: ▲▼ chodzą po przyciskach, ◀▶ przewijają oś czasu (kanał z archiwum:
oś ostatnich godzin z podziałką programów), OK zatwierdza, Back chowa HUD.

Układ HUD: górny pasek (Wstecz · picon · parametry strumienia · numer+nazwa · zegar · karta
Teraz/Następnie), środek (transport), dolny dok: oś czasu + narzędzia (Na żywo, głośność, prędkość,
napisy, audio, ulubione, **„Wróć do…”** = lista przesunięć czasu w archiwum, poprzedni kanał, inne
kanały meczu, zoom, info, raport, PiP, tylko audio, multiview, nagraj, timer snu).

**Czego nie ma:** programu bieżącego kanału (lista/oś programów z opisem) wysuwanego nad obrazem;
„Wróć do…” operuje na minutach wstecz, nie na programach; pełny przewodnik otwiera się poza
pełnym ekranem.

## Dane, które już są
- `LiveEpgReader.providerProgrammes(kanał, …)` – wszystkie zapisane programy kanału (do 7 dni, cache),
  z korektą przesunięcia czasu; `catchupProgrammes(…)` – programy już nadane w oknie archiwum, od
  najnowszego; `nowNext(…)`; `programmeDescription(id)`.
- `EpgProgrammeEntity`: tytuł, opis, start/stop, kategorie, rok, ocena, długość.
- `ChannelEntity.catchup` / `catchupDays` – czy i jak daleko sięga archiwum.
- `LiveViewModel.playCatchupProgramme(kanał, program)` – start odtwarzania programu z archiwum
  (buduje URL timeshift, obsługuje blokadę rodzicielską, loguje).
- `LiveViewModel.zapGuide` (Teraz/Następnie dla paska), `timelineProgrammes` (podziałka osi).

## Propozycja: „Program kanału” nad obrazem (Poterx)

**Wywołanie:** ▶ podczas oglądania (dziś: historia, rzadko używana) oraz przycisk Guide, jeśli pilot
go ma. ◀ zostaje listą kanałów. OK dalej pokazuje kontrolki.

**Wygląd:** dolny pasek Poterx (picon, numer, nazwa, zegar) + nad nim **pozioma taśma programów**
tego kanału: wczoraj ← … [TERAZ, podświetlony, z paskiem postępu] … → jutro. Zaznaczona karta rozwija
opis, godziny i długość; programy z archiwum mają znacznik ⟲, przyszłe ikonę zegara. Półprzezroczyste
tło, obraz dalej widoczny.

**Klawisze w taśmie:** ◀▶ przesuwają program, długie ◀▶ skaczą o godzinę, ▲▼ nadal zmieniają kanał
(taśma przeładowuje się dla nowego), **OK** na programie z archiwum = odtwórz z archiwum
(`playCatchupProgramme`), OK na „teraz” = wróć na żywo, OK na przyszłym = nic (później: przypomnienie),
Back = zamknij.

**Implementacja (ok. 1 dnia + poprawki po testach):**
1. `LiveViewModel`: `channelProgrammes(ch): Flow<List<EpgProgrammeEntity>>` z
   `providerProgrammes` ograniczone do [teraz − catchupDays, teraz + 48 h]; odświeżanie przy zmianie
   kanału i co minutę dla znacznika „teraz”.
2. `PlayerHud`: nowy slot `channelGuide: (@Composable (onClose) -> Unit)?` i stan `showChannelGuide`;
   w obsłudze klawiszy: `!controlsVisible && ▶` → otwórz (zamiast historii, tylko w trybie Poterx);
   warstwa rysowana nad dolnym paskiem, przejmuje fokus, Back zamyka.
3. `poterx/PoterxChannelGuide.kt`: taśma (LazyRow z fokusem na „teraz”), karta programu, opis;
   kolory i czcionki jak w pasku.
4. `OwnTVShell`: dostarcza slot z `liveVm.channelProgrammes`, `liveVm.playCatchupProgramme`,
   `liveVm.goToLive`.
5. Później (v0.4): ten sam komponent rozszerzony pionowo o inne kanały = pełna siatka nad obrazem.
