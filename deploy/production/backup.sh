#!/usr/bin/env bash
# 仅备份 DocWeave 生产库；凭据由服务器现有管理客户端配置提供。
set -euo pipefail
umask 077
backup_dir=/opt/apps/docweave/backups
mkdir -p "$backup_dir"
file="$backup_dir/docweave-prod-$(date -u +%Y%m%dT%H%M%SZ).sql.gz"
trap 'rm -f -- "${file}.tmp"' EXIT
docker exec projects-mysql mysqldump \
  --defaults-extra-file=/run/mysql-clients/root.cnf \
  --single-transaction --no-tablespaces --set-gtid-purged=OFF \
  --databases docweave_prod | gzip > "${file}.tmp"
gzip -t "${file}.tmp"
mv -- "${file}.tmp" "$file"
find "$backup_dir" -maxdepth 1 -type f -name 'docweave-prod-*.sql.gz' -mtime +14 -delete
printf 'DocWeave database backup completed\n'
