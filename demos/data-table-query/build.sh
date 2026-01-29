#!/bin/bash

# Get the directory where this script is located
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Get the project root (two levels up from demos/data-table-query)
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

set -e  # Exit on any error

echo "🏗️  Building Data Table Query DSL Demo"
echo "======================================"
echo ""

# Clean previous build
echo "🧹 Cleaning previous build..."
rm -rf "$SCRIPT_DIR/out"
mkdir -p "$SCRIPT_DIR/out/api"
mkdir -p "$SCRIPT_DIR/out/runtime"

# Stage 1: Compile Database API (with query DSL)
echo "📦 Stage 1: Compiling Database API..."
"$PROJECT_ROOT/bin/jo" build-lib "$SCRIPT_DIR/DatabaseAPI.jo" -d "$SCRIPT_DIR/out/api"
echo "✅ API compiled"
echo ""

# Stage 2: Compile Runtime (with SQL generation)
echo "📦 Stage 2: Compiling Runtime..."
"$PROJECT_ROOT/bin/jo" build-lib "$SCRIPT_DIR/Runtime.jo" \
  -lib "$PROJECT_ROOT/libs/runtime-js":"$SCRIPT_DIR/out/api" \
  -d "$SCRIPT_DIR/out/runtime"
echo "✅ Runtime compiled"
echo ""

# Stage 3: Compile User Application
echo "📦 Stage 3: Compiling User Application..."
"$PROJECT_ROOT/bin/jo" build -js \
  -no-detect-main \
  -link jo.main=DatabaseRuntime.platformMain \
  -link DatabaseAPI.analyzeDocuments=UserApp.analyzeDocuments \
  -lib "$SCRIPT_DIR/out/api" \
  -runtime "$SCRIPT_DIR/out/runtime" \
  "$SCRIPT_DIR/UserApp.jo" \
  -o "$SCRIPT_DIR/out/app.js"
echo "✅ User application compiled"
echo ""

echo "✅ Build successful!"
echo ""

# Check Node.js version before running
echo "🔍 Checking Node.js version..."
NODE_VERSION=$(node -v | cut -d'v' -f2 | cut -d'.' -f1)
NODE_MINOR=$(node -v | cut -d'v' -f2 | cut -d'.' -f2)

if [ "$NODE_VERSION" -lt 22 ] || ([ "$NODE_VERSION" -eq 22 ] && [ "$NODE_MINOR" -lt 5 ]); then
    echo "⚠️  Warning: This demo requires Node.js v22.5.0 or higher (for built-in node:sqlite)"
    echo "   Your version: $(node -v)"
    echo "   The demo may not run correctly."
    echo ""
else
    echo "✅ Node.js version OK: $(node -v)"
    echo ""
fi

# Initialize database (reuse from data-table demo)
echo "📊 Initializing database..."
DB_PATH="$SCRIPT_DIR/database.db"
if [ ! -f "$DB_PATH" ]; then
    node "$SCRIPT_DIR/init-db.js" "$DB_PATH"
    echo "✅ Database initialized"
else
    echo "✅ Database already exists"
fi
echo ""

# Run demo with different users
echo "🚀 Running Query DSL Demo"
echo "======================================"
echo ""

echo "👤 User 1 (Alice):"
echo "-------------------"
node "$SCRIPT_DIR/out/app.js" 1 "$DB_PATH"
echo ""

echo "👤 User 2 (Bob):"
echo "-------------------"
node "$SCRIPT_DIR/out/app.js" 2 "$DB_PATH"
echo ""

echo "👤 User 3 (Carol):"
echo "-------------------"
node "$SCRIPT_DIR/out/app.js" 3 "$DB_PATH"
echo ""

echo "✅ Demo completed successfully!"
