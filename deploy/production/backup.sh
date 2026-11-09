#!/usr/bin/env bash
# 仅备份 DocWeave；文件卷启用后短暂停止本应用，生成数据库与文件一致的快照。
set -euo pipefail
umask 077
backup_dir=/opt/apps/docweave/backups
mkdir -p "$backup_dir"
exec 9>"$backup_dir/.backup.lock"
flock -n 9 || exit 0
stamp=$(date -u +%Y%m%dT%H%M%SZ)
file="$backup_dir/docweave-prod-$stamp.sql.gz"
restart=false
cleanup() {
  rm -f -- "${file}.tmp" "${file}.files.tmp"
  if [ "$restart" = true ]; then docker start docweave-app >/dev/null; fi
}
trap cleanup EXIT
if [ -d /opt/apps/docweave/files ]; then
  if [ "$(docker inspect -f '{{.State.Running}}' docweave-app)" = true ]; then
    restart=true
    docker stop --time 30 docweave-app >/dev/null
  fi
  tar -czf "${file}.files.tmp" -C /opt/apps/docweave/files .
fi
docker exec projects-mysql mysqldump \
  --defaults-extra-file=/run/mysql-clients/root.cnf \
  --single-transaction --no-tablespaces --set-gtid-purged=OFF \
  --databases docweave_prod | gzip > "${file}.tmp"
gzip -t "${file}.tmp"
mv -- "${file}.tmp" "$file"
if [ -f "${file}.files.tmp" ]; then mv -- "${file}.files.tmp" "${file%.sql.gz}.files.tar.gz"; fi
# 只有 complete 标记存在的同一时间戳数据库/文件组合才可用于整套恢复。
touch "$backup_dir/docweave-prod-$stamp.complete"
find "$backup_dir" -maxdepth 1 -type f -name 'docweave-prod-*.sql.gz' -mtime +14 -delete
find "$backup_dir" -maxdepth 1 -type f -name 'docweave-prod-*.files.tar.gz' -mtime +14 -delete
find "$backup_dir" -maxdepth 1 -type f -name 'docweave-prod-*.complete' -mtime +14 -delete
printf 'DocWeave database backup completed\n'
