#!/bin/bash
# Porta Proxy startup script (used by LaunchAgent)
set -e

cd /Volumes/980PRO/Users/iwan/porta/packages/proxy

# Load environment from .env
set -a
source ../../.env
set +a

exec /opt/homebrew/bin/node node_modules/.bin/tsx src/index.ts
