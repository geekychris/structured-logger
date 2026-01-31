#!/bin/bash

# Generate Kafka topic creation script from JSON configs
# Usage: ./generate-topic-script.sh <config-dir> <output-script>

CONFIG_DIR=${1:-"../log-configs"}
OUTPUT_SCRIPT=${2:-"./create-kafka-topics.sh"}
TEMP_FILE=$(mktemp)

echo "#!/bin/bash" > "$OUTPUT_SCRIPT"
echo "# Auto-generated Kafka topic creation script" >> "$OUTPUT_SCRIPT"
echo "# Generated at: $(date)" >> "$OUTPUT_SCRIPT"
echo "" >> "$OUTPUT_SCRIPT"
echo "KAFKA_CONTAINER=\"kafka\"" >> "$OUTPUT_SCRIPT"
echo "BOOTSTRAP_SERVER=\"localhost:9092\"" >> "$OUTPUT_SCRIPT"
echo "" >> "$OUTPUT_SCRIPT"
echo "echo \"Creating Kafka topics from log configs...\"" >> "$OUTPUT_SCRIPT"
echo "" >> "$OUTPUT_SCRIPT"

# Process each JSON config and collect unique topics
> "$TEMP_FILE"

for config_file in "$CONFIG_DIR"/*.json; do
    if [[ -f "$config_file" ]]; then
        name=$(jq -r '.name' "$config_file")
        topic=$(jq -r '.kafka.topic' "$config_file")
        partitions=$(jq -r '.kafka.partitions' "$config_file")
        replication=$(jq -r '.kafka.replication_factor' "$config_file")
        retention=$(jq -r '.kafka.retention_ms' "$config_file")
        
        # Output: topic|partitions|replication|retention|config_name
        echo "$topic|$partitions|$replication|$retention|$name" >> "$TEMP_FILE"
    fi
done

# Sort and deduplicate topics, keeping the one with highest partitions
sort -t'|' -k1,1 -k2,2nr -u "$TEMP_FILE" | while IFS='|' read -r topic partitions replication retention name; do
    echo "" >> "$OUTPUT_SCRIPT"
    echo "# Topic: $topic (from $name)" >> "$OUTPUT_SCRIPT"
    echo "echo \"Creating topic: $topic (${partitions} partitions, replication: ${replication})...\"" >> "$OUTPUT_SCRIPT"
    echo "docker exec \$KAFKA_CONTAINER kafka-topics \\" >> "$OUTPUT_SCRIPT"
    echo "  --bootstrap-server \$BOOTSTRAP_SERVER \\" >> "$OUTPUT_SCRIPT"
    echo "  --create \\" >> "$OUTPUT_SCRIPT"
    echo "  --if-not-exists \\" >> "$OUTPUT_SCRIPT"
    echo "  --topic $topic \\" >> "$OUTPUT_SCRIPT"
    echo "  --partitions $partitions \\" >> "$OUTPUT_SCRIPT"
    echo "  --replication-factor $replication \\" >> "$OUTPUT_SCRIPT"
    
    if [[ "$retention" != "null" && -n "$retention" ]]; then
        echo "  --config retention.ms=$retention \\" >> "$OUTPUT_SCRIPT"
    fi
    
    echo "  || echo \"  ✗ Failed (topic may already exist)\"" >> "$OUTPUT_SCRIPT"
    echo "echo \"  ✓ Topic $topic configured\"" >> "$OUTPUT_SCRIPT"
done

echo "" >> "$OUTPUT_SCRIPT"
echo "echo \"\"" >> "$OUTPUT_SCRIPT"
echo "echo \"Topic creation complete. Listing topics:\"" >> "$OUTPUT_SCRIPT"
echo "docker exec \$KAFKA_CONTAINER kafka-topics --bootstrap-server \$BOOTSTRAP_SERVER --list" >> "$OUTPUT_SCRIPT"

rm "$TEMP_FILE"
chmod +x "$OUTPUT_SCRIPT"

echo "Generated topic creation script: $OUTPUT_SCRIPT"
echo "Run it with: $OUTPUT_SCRIPT"
