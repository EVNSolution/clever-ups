#!/usr/bin/env bash
# One-time (and repeatable) setup of the UPS server, run as root through SSM Run Command:
#   bash setup.sh <source-sha>
# Installs Docker and Compose, lays out /srv/ups, writes the secrets from SSM Parameter Store as files only
# the services can read, installs the release command and the nightly backup. Prints no secret.
set -euo pipefail
umask 077
sha="${1:?source sha}"
[[ "$sha" =~ ^[0-9a-f]{40}$ ]] || { echo "source sha must be 40 hex characters" >&2; exit 2; }
region="$(TOKEN=$(curl -sf -X PUT http://169.254.169.254/latest/api/token -H 'X-aws-ec2-metadata-token-ttl-seconds: 60') \
  && curl -sf -H "X-aws-ec2-metadata-token: $TOKEN" http://169.254.169.254/latest/meta-data/placement/region)"
export AWS_DEFAULT_REGION="$region"

compose_version=v5.6.0
compose_sha256=733ec76717ceb59052a9609b9dadfb523b2df8eab57a54212872d10a58078ea2

dnf install -y -q docker
systemctl enable --now docker
plugin=/usr/local/lib/docker/cli-plugins/docker-compose
if ! echo "$compose_sha256  $plugin" | sha256sum -c --status 2>/dev/null; then
  mkdir -p "$(dirname "$plugin")"
  curl -fsSL -o "$plugin.part" "https://github.com/docker/compose/releases/download/$compose_version/docker-compose-linux-aarch64"
  echo "$compose_sha256  $plugin.part" | sha256sum -c --status
  chmod 755 "$plugin.part" && mv "$plugin.part" "$plugin"
fi

install -d -m 755 /srv/ups /srv/ups/route /srv/ups/caddy
install -d -m 700 /srv/ups/secrets /srv/ups/pgdata /srv/ups/backups
install -d -m 700 -o 10001 -g 10001 /srv/ups/secrets/api /srv/ups/photos
install -d -m 700 -o 70 -g 70 /srv/ups/secrets/postgres

# The source of this release: the public repository at the exact commit.
curl -fsSL "https://codeload.github.com/EVNSolution/clever-ups/tar.gz/$sha" -o /tmp/ups-src.tgz
rm -rf /srv/ups/app.new && mkdir -p /srv/ups/app.new
tar -xzf /tmp/ups-src.tgz -C /srv/ups/app.new --strip-components=1 && rm -f /tmp/ups-src.tgz
rm -rf /srv/ups/app && mv /srv/ups/app.new /srv/ups/app
install -m 755 /srv/ups/app/deploy/host/release.sh /usr/local/bin/ups-release
install -m 755 /srv/ups/app/deploy/host/backup.sh /usr/local/bin/ups-backup
install -m 644 /srv/ups/app/deploy/host/ups-backup.service /srv/ups/app/deploy/host/ups-backup.timer /etc/systemd/system/
systemctl daemon-reload && systemctl enable --now ups-backup.timer

# Secrets: SSM SecureString parameters under /clever-ups/. Each file belongs to the service that reads it.
secret() { # name owner target...
  local name="$1" owner="$2"; shift 2
  local value
  value="$(aws ssm get-parameter --with-decryption --name "/clever-ups/$name" --query Parameter.Value --output text)"
  for target in "$@"; do
    printf '%s' "$value" > "$target.part" && chown "$owner" "$target.part" && chmod 600 "$target.part" && mv "$target.part" "$target"
  done
}
secret db-password 10001:10001 /srv/ups/secrets/api/db-password
secret db-password 70:70 /srv/ups/secrets/postgres/db-password
secret service-token 10001:10001 /srv/ups/secrets/api/service-token
secret ours24-api-key 10001:10001 /srv/ups/secrets/api/ours24-api-key
secret vworld-api-key 10001:10001 /srv/ups/secrets/api/vworld-api-key

# The road map of South Korea for the route engine, built once on this server.
if [ ! -f /srv/ups/route/south-korea-latest.osrm.mldgr ]; then
  rm -rf /srv/ups/app/infra/route/data && ln -s /srv/ups/route /srv/ups/app/infra/route/data
  bash /srv/ups/app/infra/route/prepare.sh
fi

# The address the site answers on: the Elastic IP.
token="$(curl -sf -X PUT http://169.254.169.254/latest/api/token -H 'X-aws-ec2-metadata-token-ttl-seconds: 60')"
ip="$(curl -sf -H "X-aws-ec2-metadata-token: $token" http://169.254.169.254/latest/meta-data/public-ipv4)"
default_center="$(aws ssm get-parameter --name /clever-ups/default-center --query Parameter.Value --output text 2>/dev/null || true)"
printf 'UPS_SITE=%s\nUPS_TLS=acme\nUPS_DEFAULT_CENTER=%s\n' "$ip" "$default_center" > /srv/ups/site.env
chmod 600 /srv/ups/site.env
echo "setup done for $sha; site $ip"
