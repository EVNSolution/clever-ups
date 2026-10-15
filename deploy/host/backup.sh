#!/usr/bin/env bash
# Dumps the database, compressed, to the backup bucket (kept 30 days by its lifecycle rule).
#   ups-backup [label]     the label is "nightly" from the timer, "before-release" from ups-release.
set -euo pipefail
umask 077
label="${1:-nightly}"
token="$(curl -sf -X PUT http://169.254.169.254/latest/api/token -H 'X-aws-ec2-metadata-token-ttl-seconds: 60')"
AWS_DEFAULT_REGION="$(curl -sf -H "X-aws-ec2-metadata-token: $token" http://169.254.169.254/latest/meta-data/placement/region)"
export AWS_DEFAULT_REGION
bucket="$(aws ssm get-parameter --name /clever-ups/backup-bucket --query Parameter.Value --output text)"
file="/srv/ups/backups/ups-$(date -u +%Y%m%dT%H%M%SZ)-$label.sql.gz"
docker compose -f /srv/ups/app/deploy/compose.yml --env-file /srv/ups/site.env --env-file /srv/ups/release.env \
  exec -T postgres pg_dump -U ups -d ups --no-owner | gzip > "$file"
aws s3 cp --only-show-errors --sse AES256 "$file" "s3://$bucket/postgres/$(basename "$file")"
# Local copies are only for the last few days.
find /srv/ups/backups -name 'ups-*.sql.gz' -mtime +3 -delete
echo "backup $(basename "$file")"
