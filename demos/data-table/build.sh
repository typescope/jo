#!/bin/bash

# Build script for row-level security demo

set -e  # Exit on error

echo "🔨 Building Data Table Access Control Demo"
echo "============================================"
echo ""

# Check Node.js version
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

# Clean previous build
rm -rf out
mkdir -p out/api out/runtime

echo "📦 Step 1: Compile Database API"
../../bin/jo build-lib DatabaseAPI.jo -d out/api
echo "✅ API compiled"
echo ""

echo "📦 Step 2: Compile Runtime"
../../bin/jo build-lib Runtime.jo \
  -lib ../../libs/runtime-js:out/api \
  -d out/runtime
echo "✅ Runtime compiled"
echo ""

echo "📦 Step 3: Compile User Application"
../../bin/jo build -js \
  -no-detect-main \
  -link jo.Main.main=DatabaseRuntime.platformMain \
  -link DatabaseRuntime.analyzeDocuments=UserApp.analyzeDocuments \
  -lib out/api \
  -runtime out/runtime \
  UserApp.jo \
  -o out/app.js
echo "✅ User app compiled"
echo ""

echo "✅ Build complete!"
echo ""
echo "Run with:"
echo "  node out/app.js 1  # Alice's documents"
echo "  node out/app.js 2  # Bob's documents"
echo "  node out/app.js 3  # Carol's documents"
