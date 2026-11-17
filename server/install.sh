#!/bin/bash
# Instalacja serwera Digital Signage na VPS (Debian/Ubuntu). Uruchom jako root w katalogu server/.
# Sam wykrywa, czy porty 80/443 są wolne (własny Caddy z HTTPS), czy zajęte przez istniejący
# serwer www (wtedy podpina się pod niego jako dodatkowa domena).
set -e
cd "$(dirname "$0")"
[ "$(id -u)" = 0 ] || { echo "Uruchom jako root (sudo -i)"; exit 1; }

if ! command -v docker >/dev/null; then
  echo "== Instaluję Dockera…"; curl -fsSL https://get.docker.com | sh
fi

if [ ! -f .env ]; then
  read -rp "Domena serwera signage (np. signage.nettrade.pl): " DOMAIN
  read -rp "Hasło administratora (min. 8 znaków, Enter = losowe): " PASS
  printf "DOMAIN=%s\nADMIN_PASSWORD=%s\n" "$DOMAIN" "$PASS" > .env
fi
DOMAIN=$(grep ^DOMAIN= .env | cut -d= -f2)
mkdir -p data

IP=$(curl -s4 https://api.ipify.org || true)
DNSIP=$(getent ahostsv4 "$DOMAIN" | awk 'NR==1{print $1}')
if [ -n "$IP" ] && [ "$IP" != "$DNSIP" ]; then
  echo "!! UWAGA: $DOMAIN wskazuje na '${DNSIP:-brak}', a ten serwer ma IP $IP."
  echo "!! Dodaj w DNS rekord A: $DOMAIN -> $IP (bez tego nie będzie certyfikatu HTTPS)."
fi

BUSY=$(ss -ltnpH '( sport = :80 or sport = :443 )' 2>/dev/null || true)
if [ -z "$BUSY" ]; then
  echo "== Porty 80/443 wolne – uruchamiam serwer z własnym Caddy (HTTPS automatycznie)"
  docker compose up -d --build
else
  echo "== Porty 80/443 zajęte przez:"; echo "$BUSY" | awk '{print "   " $4 "  " $6}'
  PORT=3000; while ss -ltnH "( sport = :$PORT )" | grep -q .; do PORT=$((PORT+1)); done
  BIND=127.0.0.1
  # serwer www w kontenerze Dockera nie widzi 127.0.0.1 hosta – wtedy wystaw na bramie Dockera
  if echo "$BUSY" | grep -q docker-proxy; then BIND=$(ip -4 addr show docker0 | awk '/inet /{print $2}' | cut -d/ -f1); fi
  grep -q ^SIGNAGE_PORT= .env || printf "SIGNAGE_PORT=%s\nSIGNAGE_BIND=%s\n" "$PORT" "$BIND" >> .env
  PORT=$(grep ^SIGNAGE_PORT= .env | cut -d= -f2); BIND=$(grep ^SIGNAGE_BIND= .env | cut -d= -f2)
  docker compose -f docker-compose.shared.yml up -d --build
  UP="$BIND:$PORT"
  if echo "$BUSY" | grep -q '"caddy"' && [ -f /etc/caddy/Caddyfile ]; then
    if ! grep -q "^$DOMAIN" /etc/caddy/Caddyfile; then
      cp /etc/caddy/Caddyfile /etc/caddy/Caddyfile.bak-$(date +%s)
      printf "\n%s {\n\tencode gzip\n\trequest_body {\n\t\tmax_size 4GB\n\t}\n\treverse_proxy %s\n}\n" "$DOMAIN" "$UP" >> /etc/caddy/Caddyfile
    fi
    caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null && systemctl reload caddy
    echo "== Dodano $DOMAIN do istniejącego Caddy (/etc/caddy/Caddyfile)"
  elif echo "$BUSY" | grep -q '"nginx"' && [ -d /etc/nginx ]; then
    CONF=/etc/nginx/sites-available/signage; [ -d /etc/nginx/sites-available ] || CONF=/etc/nginx/conf.d/signage.conf
    cat > "$CONF" <<NGX
server {
    listen 80;
    server_name $DOMAIN;
    client_max_body_size 4g;
    location / {
        proxy_pass http://$UP;
        proxy_http_version 1.1;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host \$host;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_read_timeout 3600s;
        proxy_request_buffering off;
    }
}
NGX
    [ -d /etc/nginx/sites-enabled ] && ln -sf "$CONF" /etc/nginx/sites-enabled/signage
    nginx -t && systemctl reload nginx
    command -v certbot >/dev/null || apt-get install -y certbot python3-certbot-nginx
    certbot --nginx -d "$DOMAIN" --non-interactive --agree-tos --register-unsafely-without-email --redirect || echo "!! Certyfikat nie powstał – sprawdź rekord DNS i uruchom: certbot --nginx -d $DOMAIN"
    echo "== Dodano $DOMAIN do istniejącego nginx ($CONF)"
  else
    echo
    echo "!! Nie rozpoznałem serwera www (np. działa w kontenerze). Dodaj w nim domenę $DOMAIN"
    echo "!! z przekierowaniem (reverse proxy, z obsługą WebSocket) na adres: http://$UP"
    echo "!! Przykład dla Caddyfile:   $DOMAIN { reverse_proxy $UP }"
  fi
fi

sleep 5
echo
echo "Gotowe. Panel: https://$DOMAIN"
[ -f data/admin-password.txt ] && cat data/admin-password.txt
CF=""; [ -n "$BUSY" ] && CF="-f docker-compose.shared.yml"
echo "Logi: cd $(pwd) && docker compose $CF logs -f signage"
