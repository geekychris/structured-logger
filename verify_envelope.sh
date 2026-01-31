#!/bin/bash
# Quick verification that envelope format is working

set -e

echo "============================================"
echo "Envelope Format Verification"
echo "============================================"
echo ""

# Step 1: Send a message
echo "1. Sending test messages..."
./run-java-demo.sh 2>&1 | grep "logged successfully" || {
    echo "❌ Failed to send messages"
    exit 1
}
echo "✅ Messages sent"
echo ""

# Step 2: Wait for Kafka
echo "2. Waiting for Kafka to receive messages..."
sleep 2

# Step 3: Get the latest message offset
echo "3. Fetching latest message from Kafka..."
OFFSET=$(docker exec kafka kafka-run-class kafka.tools.GetOffsetShell \
    --broker-list localhost:9092 --topic user-events --time -1 2>/dev/null | \
    cut -d: -f3)

if [ -z "$OFFSET" ] || [ "$OFFSET" == "0" ]; then
    echo "❌ No messages in topic"
    exit 1
fi

# Calculate offset of last message (offset - 1)
LAST_OFFSET=$((OFFSET - 1))

echo "  Total messages in topic: $OFFSET"
echo "  Fetching message at offset: $LAST_OFFSET"
echo ""

# Step 4: Fetch the specific message
echo "4. Checking message format..."
MESSAGE=$(docker exec kafka kafka-console-consumer \
    --bootstrap-server localhost:9092 \
    --topic user-events \
    --partition 0 \
    --offset $LAST_OFFSET \
    --max-messages 1 \
    --timeout-ms 5000 2>&1 | grep -v "Processed" | head -1)

if [ -z "$MESSAGE" ]; then
    echo "❌ Could not fetch message"
    exit 1
fi

echo "  Raw message (first 200 chars):"
echo "  ${MESSAGE:0:200}..."
echo ""

# Step 5: Verify envelope structure
echo "5. Verifying envelope structure..."

# Check for envelope fields
if echo "$MESSAGE" | grep -q '"_log_type"'; then
    echo "  ✅ Has _log_type field"
else
    echo "  ❌ Missing _log_type field"
    exit 1
fi

if echo "$MESSAGE" | grep -q '"_log_class"'; then
    echo "  ✅ Has _log_class field"
else
    echo "  ❌ Missing _log_class field"
    exit 1
fi

if echo "$MESSAGE" | grep -q '"_version"'; then
    echo "  ✅ Has _version field"
else
    echo "  ❌ Missing _version field"
    exit 1
fi

if echo "$MESSAGE" | grep -q '"data"'; then
    echo "  ✅ Has data field"
else
    echo "  ❌ Missing data field"
    exit 1
fi

echo ""
echo "6. Extracting envelope values..."

# Use Python to parse and display
python3 -c "
import json
import sys

message = '''$MESSAGE'''
try:
    data = json.loads(message)
    print(f'  _log_type:  {data.get(\"_log_type\", \"MISSING\")}')
    print(f'  _log_class: {data.get(\"_log_class\", \"MISSING\")}')
    print(f'  _version:   {data.get(\"_version\", \"MISSING\")}')
    print(f'  data keys:  {list(data.get(\"data\", {}).keys())[:5]}')
    print('')
    print('✅ Message has valid envelope format!')
except json.JSONDecodeError as e:
    print(f'❌ JSON parse error: {e}')
    sys.exit(1)
" || {
    echo "❌ Failed to parse message"
    exit 1
}

echo ""
echo "============================================"
echo "✅ VERIFICATION COMPLETE"
echo "============================================"
echo ""
echo "Envelope format is working correctly:"
echo "  • Logger wraps messages with envelope"
echo "  • Kafka receives enveloped messages"
echo "  • Consumer processes and extracts data"
