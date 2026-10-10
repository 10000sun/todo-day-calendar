#!/usr/bin/env bash
# 공휴일 데이터(hyunbinseo/holidays-kr, MIT, 우주항공청 월력요항 기반)를 받아 앱에 포함시킨다. 날짜를 손으로 넣지 않는다.
# 사용: scripts/fetch-holidays.sh   (릴리스 빌드 전에 실행해 최신화)
set -u
dir="$(dirname "$0")/../app/src/main/assets/holidays"
mkdir -p "$dir"
y=$(date +%Y)
for year in $((y - 1)) "$y" $((y + 1)) $((y + 2)); do
  curl -fsSL "https://raw.githubusercontent.com/hyunbinseo/holidays-kr/main/public/$year.json" -o "$dir/$year.json.tmp" \
    && mv "$dir/$year.json.tmp" "$dir/$year.json" && echo "ok $year" || { rm -f "$dir/$year.json.tmp"; echo "skip $year (미공표)"; }
done
