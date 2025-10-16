#!/bin/bash

# Build script for row-level security demo

set -e  # Exit on error

# Get the directory where this script is located
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "🔨 Building Data Table Access Control Demo"
echo "============================================"
echo ""

# Clean previous build
rm -rf "$SCRIPT_DIR/out"
mkdir -p "$SCRIPT_DIR/out/api" "$SCRIPT_DIR/out/runtime"

echo "📦 Step 1: Compile Database API"
bin/jo build-lib "$SCRIPT_DIR/DatabaseAPI.jo" -d "$SCRIPT_DIR/out/api"
echo "✅ API compiled"
echo ""

echo "📦 Step 2: Compile Runtime"
bin/jo build-lib "$SCRIPT_DIR/Runtime.jo" \
  -lib libs/runtime-js:"$SCRIPT_DIR/out/api" \
  -d "$SCRIPT_DIR/out/runtime"
echo "✅ Runtime compiled"
echo ""

echo "📦 Step 3: Compile User Application"
bin/jo build -js \
  -no-detect-main \
  -link jo.Main.main=DatabaseRuntime.platformMain \
  -link DatabaseAPI.analyzeDocuments=UserApp.analyzeDocuments \
  -lib "$SCRIPT_DIR/out/api" \
  -runtime "$SCRIPT_DIR/out/runtime" \
  "$SCRIPT_DIR/UserApp.jo" \
  -o "$SCRIPT_DIR/out/app.js"
echo "✅ User app compiled"
echo ""

echo "✅ Build complete!"
echo ""

# Check Node.js version before running
echo "🔍 Checking Node.js version..."
if ! command -v node &> /dev/null; then
    echo "❌ Error: Node.js is not installed"
    echo "Please install Node.js v22.5.0 or higher"
    exit 1
fi

NODE_VERSION=$(node -v | cut -d'v' -f2)
MAJOR=$(echo $NODE_VERSION | cut -d'.' -f1)
MINOR=$(echo $NODE_VERSION | cut -d'.' -f2)

if [ "$MAJOR" -lt 22 ] || ([ "$MAJOR" -eq 22 ] && [ "$MINOR" -lt 5 ]); then
    echo "❌ Error: Node.js version too old"
    echo "node:sqlite requires Node.js v22.5.0 or higher"
    echo "Current version: v$NODE_VERSION"
    echo "Please upgrade Node.js"
    exit 1
fi

echo "✅ Node.js v$NODE_VERSION detected"
echo ""

# Check if node:sqlite is available
if ! node -e "require('node:sqlite')" 2>/dev/null; then
    echo "❌ Error: node:sqlite module not available"
    echo "Please ensure you have Node.js v22.5.0 or higher"
    exit 1
fi

echo "✅ node:sqlite module available"
echo ""

# Initialize database
echo "📊 Initializing database..."
DB_PATH="$SCRIPT_DIR/database.db"
node "$SCRIPT_DIR/init-db.js" "$DB_PATH"
echo "✅ Database initialized"
echo ""

# Run demo with different users
echo "🚀 Running Demo"
echo "============================================"
echo ""

echo "=========================================="
echo "Running as User 1 (Alice)"
echo "=========================================="
node "$SCRIPT_DIR/out/app.js" 1 "$DB_PATH"
echo ""

echo "=========================================="
echo "Running as User 2 (Bob)"
echo "=========================================="
node "$SCRIPT_DIR/out/app.js" 2 "$DB_PATH"
echo ""

echo "=========================================="
echo "Running as User 3 (Carol)"
echo "=========================================="
node "$SCRIPT_DIR/out/app.js" 3 "$DB_PATH"
echo ""

echo "✅ Demo complete!"
