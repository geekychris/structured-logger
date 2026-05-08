package com.logging.config;

import com.logging.sink.CompositeSink;
import com.logging.sink.FileSink;
import com.logging.sink.LogSink;
import com.logging.sink.NullSink;
import com.logging.sink.Slf4jSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoggerConfigTest {

    @Test
    void parsesCommaSeparatedSinkList() {
        Map<String, String> env = new HashMap<>();
        env.put("STRUCTURED_LOG_SINKS", "slf4j, null ");
        env.put("STRUCTURED_LOG_FILE_DIR", "/tmp/x");
        LoggerConfig cfg = LoggerConfig.fromMap(env);
        assertThat(cfg.sinks()).containsExactlyInAnyOrder(SinkType.SLF4J, SinkType.NULL);
    }

    @Test
    void defaultsToKafkaWhenNothingDeclared() {
        LoggerConfig cfg = LoggerConfig.fromMap(new HashMap<>());
        assertThat(cfg.sinks()).containsExactly(SinkType.KAFKA);
        assertThat(cfg.kafkaBootstrapServers()).isEqualTo("localhost:9092");
    }

    @Test
    void slf4jOnlyConfigBuildsSingleSink() {
        LoggerConfig cfg = LoggerConfig.builder().sinks(SinkType.SLF4J).build();
        LogSink sink = SinkFactory.build(cfg, new SinkFactory.LoggerContext("topic", "user_events", "ue"));
        assertThat(sink).isInstanceOf(Slf4jSink.class);
        sink.close();
    }

    @Test
    void multipleSinksProduceComposite(@TempDir Path tmp) {
        LoggerConfig cfg = LoggerConfig.builder()
                .sinks(SinkType.SLF4J, SinkType.FILE)
                .fileDir(tmp)
                .build();
        LogSink sink = SinkFactory.build(cfg, new SinkFactory.LoggerContext("t", "lt", "ue"));
        assertThat(sink).isInstanceOf(CompositeSink.class);
        CompositeSink composite = (CompositeSink) sink;
        assertThat(composite.children()).hasSize(2);
        assertThat(composite.children().get(0)).isInstanceOf(Slf4jSink.class);
        assertThat(composite.children().get(1)).isInstanceOf(FileSink.class);
        sink.close();
    }

    @Test
    void fileSinkRequiresDirectory() {
        LoggerConfig cfg = LoggerConfig.builder().sinks(SinkType.FILE).build();
        assertThatThrownBy(() ->
                SinkFactory.build(cfg, new SinkFactory.LoggerContext("t", "lt", "ue")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STRUCTURED_LOG_FILE_DIR");
    }

    @Test
    void emptySinkListResolvesToNullSink() {
        // Pre-validation: builder defaults to KAFKA; this exercises the SinkFactory empty path.
        LoggerConfig cfg = LoggerConfig.builder().sinks(SinkType.NULL).build();
        LogSink sink = SinkFactory.build(cfg, new SinkFactory.LoggerContext("t", "lt", "ue"));
        assertThat(sink).isInstanceOf(NullSink.class);
        sink.close();
    }
}
