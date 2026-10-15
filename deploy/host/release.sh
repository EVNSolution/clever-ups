#!/usr/bin/env bash
# Activates a tested release, or goes back to the previous one. Run as root by the SSM document
# CleverUps-Release only:
#   ups-release release <source-sha> <api-digest> <web-digest>
#   ups-release rollback
# The images are pulled by digest from ECR. The database is dumped first. The new API must answer its health
# check within three minutes; otherwise the previous release is started again and the command fails.
set -euo pipefail
umask 077
cd /srv/ups
token="$(curl -sf -X PUT http://169.254.169.254/latest/api/token -H 'X-aws-ec2-metadata-token-ttl-seconds: 60')"
meta() { curl -sf -H "X-aws-ec2-metadata-token: $token" "http://169.254.169.254/latest/meta-data/$1"; }
region="$(meta placement/region)"
account="$(curl -sf -H "X-aws-ec2-metadata-token: $token" http://169.254.169.254/latest/dynamic/instance-identity/document | sed -n 's/.*"accountId" *: *"\([0-9]*\)".*/\1/p')"
registry="$account.dkr.ecr.$region.amazonaws.com"
export AWS_DEFAULT_REGION="$region"

up() { docker compose -f /srv/ups/app/deploy/compose.yml --env-file /srv/ups/site.env --env-file "$1" up -d --remove-orphans; }
healthy() {
  for _ in $(seq 1 36); do
    if docker compose -f /srv/ups/app/deploy/compose.yml --env-file /srv/ups/site.env --env-file /srv/ups/release.env \
      exec -T api wget -qO- http://localhost:8080/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then return 0; fi
    sleep 5
  done
  return 1
}

case "${1:-}" in
  release)
    sha="${2:?}" api="${3:?}" web="${4:?}"
    [[ "$sha" =~ ^[0-9a-f]{40}$ && "$api" =~ ^sha256:[0-9a-f]{64}$ && "$web" =~ ^sha256:[0-9a-f]{64}$ ]] \
      || { echo "bad release arguments" >&2; exit 2; }
    aws ecr get-login-password | docker login -u AWS --password-stdin "$registry" >/dev/null
    printf 'UPS_SOURCE_SHA=%s\nUPS_API_IMAGE=%s/clever-ups-api@%s\nUPS_WEB_IMAGE=%s/clever-ups-web@%s\n' \
      "$sha" "$registry" "$api" "$registry" "$web" > release.next.env
    docker compose -f /srv/ups/app/deploy/compose.yml --env-file site.env --env-file release.next.env pull -q api web
    # Compose and the VROOM settings come from the same commit as the images.
    if [ "$(sed -n 's/^UPS_SOURCE_SHA=//p' release.env 2>/dev/null)" != "$sha" ]; then
      curl -fsSL "https://codeload.github.com/EVNSolution/clever-ups/tar.gz/$sha" -o /tmp/ups-src.tgz
      rm -rf app.new && mkdir app.new && tar -xzf /tmp/ups-src.tgz -C app.new --strip-components=1 && rm -f /tmp/ups-src.tgz
      rm -rf app.prev && { [ -d app ] && mv app app.prev || true; } && mv app.new app
      ln -sfn /srv/ups/route app/infra/route/data
    fi
    [ -f release.env ] && /usr/local/bin/ups-backup before-release
    [ -f release.env ] && cp release.env release.prev.env
    mv release.next.env release.env
    up release.env
    if healthy; then echo "released $sha"; exit 0; fi
    echo "the new API did not become healthy; going back" >&2
    if [ -f release.prev.env ]; then
      [ -d app.prev ] && { rm -rf app.failed; mv app app.failed; mv app.prev app; }
      cp release.prev.env release.env && up release.env
    fi
    exit 1
    ;;
  rollback)
    [ -f release.prev.env ] || { echo "no previous release" >&2; exit 1; }
    [ -d app.prev ] && { rm -rf app.newer; mv app app.newer; mv app.prev app; }
    cp release.env release.newer.env && cp release.prev.env release.env
    up release.env
    healthy && echo "rolled back" || { echo "previous release is not healthy either" >&2; exit 1; }
    ;;
  *) echo "usage: ups-release release <sha> <api-digest> <web-digest> | rollback" >&2; exit 2 ;;
esac
