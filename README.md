# Digital Signage – serwer na VPS + odtwarzacz na tablety Android

System do wyświetlania treści (zdjęcia, filmy, strony www, przewijany tekst) na tabletach z Androidem.
Treść, harmonogram i tablety obsługuje się z panelu www na serwerze. Tam jest też zdalne sterowanie
tabletem: podgląd ekranu na żywo, dotyk, restart i aktualizacja aplikacji.

```
 panel www (przeglądarka) ──HTTPS──▶  serwer na VPS (Docker: Node.js + Caddy)
                                         ▲            │
                         WebSocket (stan, polecenia)  │ HTTPS (manifest, pliki, APK)
                                         │            ▼
                                   tablety Android – aplikacja Signage Player
```

## 1. Serwer na VPS

Wymagania: VPS z Debianem/Ubuntu i domena (rekord A, np. `signage.firma.pl` → IP serwera),
otwarte porty 80 i 443.

```sh
git clone https://github.com/gpa-32/signage.git
cd signage/server
sudo ./install.sh          # pyta o domenę i hasło, instaluje Dockera, uruchamia serwer
```

Panel: `https://signage.firma.pl`, login `admin`. Certyfikat HTTPS ustawia się sam (Let's Encrypt).
Gdy nie podasz hasła, losowe jest w `server/data/admin-password.txt`. Zmienisz je w zakładce **Konto**.

Dane (baza, pliki, zrzuty ekranu, APK) leżą w `server/data/`. Kopia zapasowa = skopiowanie tego katalogu.
Aktualizacja serwera: `git pull && docker compose up -d --build`.

Jeśli na VPS działa już serwer www (np. Caddy/nginx serwera Domofon SIP), pomiń kontener `caddy`
i przekieruj domenę na port 3000 kontenera `signage` (potrzebna obsługa WebSocket i plików do 4 GB).

## 2. Aplikacja na tablet

Plik **signage-player.apk** jest w zakładce **Releases** repozytorium (GitHub Actions buduje go
po każdej zmianie w `android/`).

1. Zainstaluj APK i uruchom. Na tablecie z root zatwierdź dostęp (Magisk: **Zezwól na stałe**).
   Aplikacja sama nada sobie uprawnienia, ustawi się jako ekran główny, wyłączy blokadę ekranu
   i uruchomi zewnętrzny watchdog.
2. Wpisz adres serwera. Tablet dodasz na jeden z dwóch sposobów:
   - **kod z ekranu tabletu** – tablet pokazuje 6 znaków; w panelu: *Tablety → Dodaj tablety →
     Kod z ekranu tabletu*, wpisz kod, nazwę i grupę;
   - **kod QR / kod rejestracji** – w panelu *Dodaj tablety → Utwórz kod*, na tablecie
     *Skanuj kod QR* (albo wpisz kod). Jeden kod działa dla wielu tabletów przez wybrany czas,
     a nowe tablety od razu trafiają do wybranej grupy.

**Bez wpisywania adresu:** w pliku `default_server.txt` w katalogu głównym repozytorium (albo w
zmiennej repozytorium GitHub `DEFAULT_SERVER`) wpisz adres serwera. Kolejne APK będą go miały
wbudowanego, więc po instalacji tablet od razu pokaże kod.

**Menu serwisowe na tablecie:** 7 szybkich dotknięć w lewy górny róg ekranu (informacje, wyjście
z trybu kiosku na 15 min, ustawienia Androida, odłączenie od serwera).

Tablet bez root też działa (odtwarzanie, harmonogram, pasek, zdalna zmiana treści). Nie działają wtedy:
dotyk na zdalnym ekranie, wyłączanie ekranu w trybie uśpienia (zamiast tego jest czarny ekran),
restart tabletu i cicha aktualizacja. Uprawnienia trzeba wtedy nadać przyciskiem na ekranie rejestracji.

## 3. Panel www

| Zakładka | Co robi |
|---|---|
| **Tablety** | lista ze stanem online/offline, tym, co teraz gra, baterią i wersją; kliknięcie otwiera szczegóły |
| ↳ Stan i sterowanie | informacje o tablecie, polecenia: odśwież treść, pokaż nazwę na ekranie, włącz/wyłącz ekran, restart aplikacji i tabletu, wyczyść pamięć, dziennik, **Pokaż teraz** (dowolne medium lub strona na N minut), polecenie powłoki root |
| ↳ Zdalny ekran | podgląd ekranu na żywo; kliknięcie = dotyk, przeciągnięcie = przesunięcie, przyciski Wstecz / Ekran główny / Ostatnie / Zasilanie, wpisywanie tekstu |
| ↳ Treść / Ustawienia | nazwa, grupa, własna treść lub własne ustawienia zamiast grupowych |
| **Multimedia** | przeciągnij pliki (JPG, PNG, GIF, WEBP, MP4, WEBM…) albo dodaj stronę www; czas wyświetlania; długość filmu jest odczytywana automatycznie |
| **Playlisty** | kolejność i czas każdego elementu |
| **Grupy i harmonogram** | playlista domyślna + reguły (dni tygodnia, godziny od–do, playlista; także przez północ), orientacja, dopasowanie obrazu, przejście, głośność, jasność, godziny włączenia i wyłączenia ekranu, codzienny restart, tryb kiosku, pasek z przewijanym tekstem |
| **Aktualizacja aplikacji** | wgraj nowe APK i wyślij na wybrane tablety; instalują je same i uruchamiają ponownie |
| **Dziennik** | połączenia, polecenia, błędy zgłoszone przez tablety |

Zmiany w treści trafiają na tablety od razu (powiadomienie przez WebSocket). Tablety pobierają pliki
do swojej pamięci (sprawdzając sumę SHA-256), więc odtwarzanie działa także bez internetu.
Nieużywane pliki są usuwane.

## 4. Odporność na zawieszenia

| Poziom | Co robi |
|---|---|
| Usługa pierwszoplanowa `START_STICKY` | system podnosi usługę po ubiciu procesu |
| Start po włączeniu i po aktualizacji | `BootReceiver` |
| Alarm co 5 min | uruchamia usługę, jeśli nie działa |
| Watchdog wewnętrzny | wątek główny nie odpowiada 45 s → restart aplikacji |
| Obsługa awarii | nieobsłużony wyjątek → zapis w dzienniku i ponowne uruchomienie po 3 s |
| Watchdog zewnętrzny (root) | skrypt poza aplikacją: brak „tętna” przez 120 s → `force-stop` i ponowny start |
| Tryb kiosku | gdy ktoś wyjdzie z odtwarzacza, po N sekundach wraca on na wierzch; aplikacja jest ekranem głównym |
| Połączenie z serwerem | ponawianie z rosnącym odstępem (do 60 s), ping co 25 s; serwer wykrywa zerwane połączenia |
| Treść offline | ostatni manifest i pliki w pamięci tabletu, pobieranie wznawiane co minutę |
| Codzienny restart (opcjonalny) | o wybranej godzinie |

## 5. Budowanie APK

Każdy `push` zmieniający `android/` uruchamia GitHub Actions: buduje i podpisuje APK, numeruje
wersję (`1.0.<nr builda>`) i publikuje w **Releases**. Klucz podpisu (`android/keystore/release.jks`)
jest w repozytorium, żeby kolejne wersje instalowały się jako aktualizacja bez utraty ustawień,
dlatego repozytorium powinno być **prywatne**.

Lokalnie: Android Studio (JDK 17) → otwórz folder `android` → Build → Build APK.

## Struktura

```
server/                     serwer (Node.js 22, bez bazy SQL – plik data/db.json)
  src/server.js             API panelu, API tabletów, WebSocket, rejestracja, polecenia
  src/db.js                 zapis danych (atomowy)
  public/                   panel www (HTML/JS bez bibliotek)
  Dockerfile, docker-compose.yml, deploy/Caddyfile, install.sh
android/                    aplikacja Signage Player (Kotlin, min. Android 5.0)
  PlayerActivity            odtwarzanie: obrazy z przenikaniem, wideo, www, pasek, menu serwisowe
  SignageService            połączenie z serwerem, harmonogram ekranu, kiosk, watchdogi
  Sync, Mf                  manifest, harmonogram, pobieranie plików
  Commands                  polecenia zdalne (zrzut ekranu, dotyk, restart, aktualizacja…)
  SetupActivity             dodawanie tabletu (kod, kod QR)
  Root, Net, Cfg, L, Receivers, App
```
