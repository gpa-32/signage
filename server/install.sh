#!/bin/sh
# Instalacja serwera Digital Signage na VPS (Debian/Ubuntu). Uruchom jako root w katalogu server/.
set -e
if ! command -v docker >/dev/null; then
  echo "Instaluję Dockera…"; curl -fsSL https://get.docker.com | sh
fi
if [ ! -f .env ]; then
  printf "Domena serwera (np. signage.firma.pl): "; read DOMAIN
  printf "Hasło administratora (min. 8 znaków, Enter = losowe): "; read PASS
  printf "DOMAIN=%s\nADMIN_PASSWORD=%s\n" "$DOMAIN" "$PASS" > .env
fi
mkdir -p data
docker compose up -d --build
sleep 5
echo
echo "Gotowe. Panel: https://$(grep ^DOMAIN .env | cut -d= -f2)"
[ -f data/admin-password.txt ] && cat data/admin-password.txt
echo "Logi: docker compose logs -f signage"
