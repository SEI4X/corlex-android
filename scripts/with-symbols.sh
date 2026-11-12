#!/bin/sh
# MIT. Build-only credentials must never be copied into the application bundle.
# Run from the app repository: sh <SDK>/scripts/with-symbols.sh xcodebuild ...
# CI can inject these variables directly; the local file is optional and gitignored.
set +x
set -eu
if [ "$#" -eq 0 ]; then
  echo 'Usage: with-symbols.sh <build command> [arguments...]' >&2
  exit 64
fi
symbols_file="${CORLEX_SYMBOLS_ENV_FILE:-.corlex/symbols.env}"
if [ -f "$symbols_file" ]; then
  set -a
  . "$symbols_file"
  set +a
elif [ -z "${CORLEX_UPLOAD_TOKEN:-}" ]; then
  echo 'Corlex: no build-only symbol token; build continues without symbol upload.' >&2
fi
exec "$@"
