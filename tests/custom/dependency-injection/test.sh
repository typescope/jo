#!/bin/bash

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NAME="$(basename "$DIR")"

echo "Testing $TEST_NAME"

# Build directory
BUILD="$DIR/build"

# Clean up previous build artifacts
rm -rf "$BUILD" "$DIR/actual.out" "$DIR"/*.run "$DIR"/*.js "$DIR"/*.rb "$DIR"/*.py

# Build the database interface library
echo "  - Building database library"
bin/jo compile --sast "$DIR/database.jo" -d "$BUILD/database"

# Build the service library (depends on database interface)
echo "  - Building service library"
bin/jo compile --sast "$DIR/service.jo" --lib "$BUILD/database" -d "$BUILD/service"

# Build the mock implementation library
echo "  - Building mock database library"
bin/jo compile --sast "$DIR/mockdb.jo" --lib "$BUILD/database" -d "$BUILD/mockdb"

# Link flags to inject mock database into service
LINK_FLAGS="--link Database.connect=MockDB.connect \
            --link Database.query=MockDB.query \
            --link Database.insert=MockDB.insert \
            --link Database.disconnect=MockDB.disconnect"

# Test with interpreter
echo "  - Running with interpreter"
bin/jo eval "$DIR/app.jo" --lib "$BUILD/database:$BUILD/service:$BUILD/mockdb" $LINK_FLAGS > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] Interpreter test failed for $TEST_NAME"
    exit 1
}

# Test with register machine
echo "  - Building with register machine"
bin/jo compile --reg "$DIR/app.jo" --lib "$BUILD/database:$BUILD/service:$BUILD/mockdb" $LINK_FLAGS -o "$DIR/app.run"
"$DIR/app.run" > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] Register machine test failed for $TEST_NAME"
    exit 1
}

# Test with stack machine
echo "  - Building with stack machine"
bin/jo compile --stack "$DIR/app.jo" --lib "$BUILD/database:$BUILD/service:$BUILD/mockdb" $LINK_FLAGS -o "$DIR/app.run"
"$DIR/app.run" > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] Stack machine test failed for $TEST_NAME"
    exit 1
}

# Test with JavaScript
echo "  - Building with JavaScript"
bin/jo compile --js "$DIR/app.jo" --lib "$BUILD/database:$BUILD/service:$BUILD/mockdb" $LINK_FLAGS -o "$DIR/app.js"
node "$DIR/app.js" > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] JavaScript test failed for $TEST_NAME"
    exit 1
}

# Test with Ruby
echo "  - Building with Ruby"
bin/jo compile --ruby "$DIR/app.jo" --lib "$BUILD/database:$BUILD/service:$BUILD/mockdb" $LINK_FLAGS -o "$DIR/app.rb"
ruby "$DIR/app.rb" > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] Ruby test failed for $TEST_NAME"
    exit 1
}

# Test with Python
echo "  - Building with Python"
bin/jo compile --python "$DIR/app.jo" --lib "$BUILD/database:$BUILD/service:$BUILD/mockdb" $LINK_FLAGS -o "$DIR/app.py"
python "$DIR/app.py" > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] Python test failed for $TEST_NAME"
    exit 1
}

# Clean up
rm -rf "$BUILD" "$DIR/actual.out" "$DIR"/*.run "$DIR"/*.js "$DIR"/*.rb "$DIR"/*.py

echo "  ✓ All tests passed for $TEST_NAME"
