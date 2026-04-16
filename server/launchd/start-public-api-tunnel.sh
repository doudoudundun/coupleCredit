#!/bin/zsh
set -euo pipefail

state_dir="${STATE_DIR:-/tmp/couplecredit-public-tunnel}"
log_file="${LOG_FILE:-$state_dir/localhost-run.log}"
url_file="${URL_FILE:-$state_dir/public-url.txt}"

mkdir -p "$state_dir"
: > "$log_file"
rm -f "$url_file"

/usr/bin/ssh \
  -o StrictHostKeyChecking=accept-new \
  -o ServerAliveInterval=30 \
  -o ExitOnForwardFailure=yes \
  -R 80:127.0.0.1:8082 \
  nokey@localhost.run 2>&1 | while IFS= read -r line; do
    print -r -- "$line"
    print -r -- "$line" >> "$log_file"

    case "$line" in
      *" tunneled with tls termination, https://"*)
        url="https://${line#*https://}"
        url="${url%%[[:space:]]*}"
        print -r -- "$url" > "$url_file"
        ;;
    esac
  done
