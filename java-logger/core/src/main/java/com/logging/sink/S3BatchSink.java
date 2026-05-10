package com.logging.sink;

import com.logging.sink.avro.AvroEnvelopeSchema;
import org.apache.avro.Schema;
import org.apache.avro.file.CodecFactory;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.DatumWriter;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;

/**
 * Batches envelopes in memory and flushes them to S3-compatible object storage
 * as Avro+Snappy or Parquet+Zstd files. Counterpart of the Python S3BatchSink
 * and the bench's standalone sidecar.
 *
 * Bounded by max_records (memory safety), rotate_bytes, and rotate_seconds —
 * first one to fire triggers the flush.
 *
 * Lazily depends on avro / parquet-avro / hadoop-common / aws-sdk:s3 — none of
 * those are loaded unless this sink is actually instantiated.
 */
public final class S3BatchSink implements LogSink {

    private static final Logger LOG = LoggerFactory.getLogger(S3BatchSink.class);
    private static final DateTimeFormatter PATH_FMT = DateTimeFormatter
            .ofPattern("'y='yyyy/'m='MM/'d='dd/'h='HH").withZone(ZoneOffset.UTC);

    public enum Encoding { AVRO, PARQUET }

    public static final class Config {
        public String bucket;
        public Encoding encoding = Encoding.PARQUET;
        public String endpoint;        // null = AWS default
        public String region = "us-east-1";
        public boolean pathStyle;
        public String accessKey;
        public String secretKey;
        public int rotateSeconds = 60;
        public long rotateBytes = 64L * 1024L * 1024L;
        public int maxRecords = 50_000;
        public String keyPrefix = "";
        public Schema avroSchema;      // required: produced from log config

        public Config() {}

        public Config bucket(String b) { this.bucket = b; return this; }
        public Config encoding(Encoding e) { this.encoding = e; return this; }
        public Config endpoint(String e) { this.endpoint = e; return this; }
        public Config region(String r) { this.region = r; return this; }
        public Config pathStyle(boolean v) { this.pathStyle = v; return this; }
        public Config credentials(String access, String secret) {
            this.accessKey = access;
            this.secretKey = secret;
            return this;
        }
        public Config rotateSeconds(int v) { this.rotateSeconds = v; return this; }
        public Config rotateBytes(long v) { this.rotateBytes = v; return this; }
        public Config maxRecords(int v) { this.maxRecords = v; return this; }
        public Config keyPrefix(String v) { this.keyPrefix = v; return this; }
        public Config avroSchema(Schema s) { this.avroSchema = s; return this; }
        public Config avroSchemaFromConfig(String logClass, List<Map<String, Object>> fields) {
            this.avroSchema = AvroEnvelopeSchema.derive(logClass, fields);
            return this;
        }
    }

    private final Config cfg;
    private final S3Client s3;
    private final ReentrantLock lock = new ReentrantLock();
    private final List<GenericRecord> batch = new ArrayList<>();
    private long rawBytes;
    private long lastRotateNanos;
    private final ScheduledExecutorService timer;

    public S3BatchSink(Config cfg) {
        if (cfg.bucket == null || cfg.bucket.isBlank())
            throw new IllegalArgumentException("S3BatchSink: bucket is required");
        if (cfg.avroSchema == null)
            throw new IllegalArgumentException("S3BatchSink: avroSchema is required");
        this.cfg = cfg;
        this.s3 = buildS3(cfg);
        this.lastRotateNanos = System.nanoTime();
        this.timer = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "s3-batch-rotate");
            t.setDaemon(true);
            return t;
        });
        this.timer.scheduleAtFixedRate(this::timerTick, 1, 1, TimeUnit.SECONDS);
    }

    private static S3Client buildS3(Config c) {
        var b = S3Client.builder().region(Region.of(c.region));
        if (c.endpoint != null) b.endpointOverride(URI.create(c.endpoint));
        b.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(c.pathStyle).build());
        if (c.accessKey != null && c.secretKey != null) {
            b.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(c.accessKey, c.secretKey)));
        } else {
            b.credentialsProvider(DefaultCredentialsProvider.create());
        }
        return b.build();
    }

    @Override public String name() {
        return "s3(" + cfg.bucket + ", " + cfg.encoding + ")";
    }

    @Override
    public void publish(LogEnvelope envelope, BiConsumer<Boolean, Throwable> callback) {
        boolean shouldFlush = false;
        try {
            GenericRecord rec = toGenericRecord(envelope);
            int size = approxRawBytes(envelope);
            lock.lock();
            try {
                batch.add(rec);
                rawBytes += size;
                shouldFlush = batch.size() >= cfg.maxRecords || rawBytes >= cfg.rotateBytes;
            } finally {
                lock.unlock();
            }
        } catch (Throwable t) {
            LOG.error("S3BatchSink encode failed", t);
            if (callback != null) callback.accept(false, t);
            return;
        }
        if (shouldFlush) {
            try {
                flush();
            } catch (Throwable t) {
                LOG.error("S3BatchSink flush failed", t);
                if (callback != null) callback.accept(false, t);
                return;
            }
        }
        if (callback != null) callback.accept(true, null);
    }

    private void timerTick() {
        try {
            boolean stale;
            lock.lock();
            try {
                long elapsedS = (System.nanoTime() - lastRotateNanos) / 1_000_000_000L;
                stale = !batch.isEmpty() && elapsedS >= cfg.rotateSeconds;
            } finally {
                lock.unlock();
            }
            if (stale) flush();
        } catch (Throwable t) {
            LOG.warn("timer flush failed", t);
        }
    }

    @Override
    public void flush() {
        List<GenericRecord> snapshot;
        long rawSnapshot;
        lock.lock();
        try {
            if (batch.isEmpty()) return;
            snapshot = new ArrayList<>(batch);
            rawSnapshot = rawBytes;
            batch.clear();
            rawBytes = 0;
            lastRotateNanos = System.nanoTime();
        } finally {
            lock.unlock();
        }
        long t0 = System.nanoTime();
        byte[] body;
        String ext;
        try {
            if (cfg.encoding == Encoding.AVRO) {
                body = encodeAvro(snapshot);
                ext = "avro";
            } else {
                body = encodeParquet(snapshot);
                ext = "parquet";
            }
        } catch (Exception e) {
            LOG.error("S3 encode failed", e);
            // Records lost — log loudly. In production you'd want a DLQ here.
            return;
        }
        long encodeNanos = System.nanoTime() - t0;
        String key = (cfg.keyPrefix.isEmpty() ? "" : cfg.keyPrefix.replaceAll("/+$", "") + "/")
                + PATH_FMT.format(Instant.now())
                + "/" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8) + "." + ext;
        long t1 = System.nanoTime();
        try {
            s3.putObject(
                    PutObjectRequest.builder().bucket(cfg.bucket).key(key).build(),
                    RequestBody.fromBytes(body));
        } catch (Exception e) {
            LOG.error("S3 put failed key={}", key, e);
            return;
        }
        long putNanos = System.nanoTime() - t1;
        double ratio = (double) body.length / Math.max(rawSnapshot, 1);
        LOG.info("S3 PUT {} records={} raw_bytes={} object_bytes={} ratio={} encode_ms={} put_ms={}",
                key, snapshot.size(), rawSnapshot, body.length, String.format("%.3f", ratio),
                encodeNanos / 1_000_000L, putNanos / 1_000_000L);
    }

    private byte[] encodeAvro(List<GenericRecord> records) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DatumWriter<GenericRecord> writer = new GenericDatumWriter<>(cfg.avroSchema);
        try (DataFileWriter<GenericRecord> dfw = new DataFileWriter<>(writer)) {
            dfw.setCodec(CodecFactory.snappyCodec());
            dfw.create(cfg.avroSchema, out);
            for (GenericRecord r : records) dfw.append(r);
        }
        return out.toByteArray();
    }

    private byte[] encodeParquet(List<GenericRecord> records) throws Exception {
        // Parquet writer requires a Path. Use a temp file then read bytes back.
        File tmp = Files.createTempFile("s3sink-", ".parquet").toFile();
        // Parquet writer wants to create the file itself
        if (!tmp.delete()) LOG.debug("temp prepare delete returned false");
        try {
            try (ParquetWriter<GenericRecord> pw = AvroParquetWriter.<GenericRecord>builder(new Path(tmp.getAbsolutePath()))
                    .withSchema(cfg.avroSchema)
                    .withCompressionCodec(CompressionCodecName.ZSTD)
                    .withConf(new Configuration())
                    .build()) {
                for (GenericRecord r : records) pw.write(r);
            }
            return Files.readAllBytes(tmp.toPath());
        } finally {
            if (tmp.exists() && !tmp.delete()) LOG.debug("temp file cleanup delete returned false");
        }
    }

    private int approxRawBytes(LogEnvelope env) {
        // Very rough: serialize-as-JSON length (used only for rotate_bytes thresholding)
        try {
            return EnvelopeSerializer.toJson(env).length();
        } catch (Exception e) {
            return 256;
        }
    }

    private GenericRecord toGenericRecord(LogEnvelope env) {
        Schema dataSchema = cfg.avroSchema.getField("data").schema();
        GenericRecord dataRec = new GenericData.Record(dataSchema);
        Object dataObj = env.toMap().get("data");
        if (dataObj instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) dataObj;
            for (Schema.Field f : dataSchema.getFields()) {
                dataRec.put(f.name(), m.get(f.name()));
            }
        }
        GenericRecord envRec = new GenericData.Record(cfg.avroSchema);
        envRec.put("_log_type", env.getLogType());
        envRec.put("_log_class", env.getLogClass());
        envRec.put("_version", env.getVersion());
        envRec.put("data", dataRec);
        return envRec;
    }

    @Override
    public void close() {
        timer.shutdown();
        try { timer.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        try { flush(); } catch (Throwable t) { LOG.warn("close flush failed", t); }
        try { s3.close(); } catch (Throwable t) { LOG.warn("s3 close failed", t); }
    }
}
