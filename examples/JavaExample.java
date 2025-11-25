import com.logging.generated.UserEventsLogger;
import com.logging.generated.ApiMetricsLogger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * Example usage of generated structured loggers in Java.
 */
public class JavaExample {

    public static void main(String[] args) {
        logUserEvents();
        logApiMetrics();
    }

    private static void logUserEvents() {
        // Create logger instance
        try (UserEventsLogger logger = new UserEventsLogger()) {
            
            // Example 1: Direct logging with all parameters
            logger.log(
                Instant.now(),
                LocalDate.now(),
                "user_12345",
                "session_abc123",
                "page_view",
                "/products/laptop",
                createProperties("category", "electronics", "source", "search"),
                "desktop",
                2500L
            );

            // Example 2: Using builder pattern for more readable code
            Map<String, String> properties = new HashMap<>();
            properties.put("button_id", "checkout");
            properties.put("cart_value", "299.99");
            
            UserEventsLogger.builder()
                .timestamp(Instant.now())
                .eventDate(LocalDate.now())
                .userId("user_67890")
                .sessionId("session_xyz789")
                .eventType("click")
                .pageUrl("/checkout")
                .properties(properties)
                .deviceType("mobile")
                .durationMs(1200L)
                .build();

            System.out.println("User events logged successfully");
        } catch (Exception e) {
            System.err.println("Error logging user events: " + e.getMessage());
        }
    }

    private static void logApiMetrics() {
        try (ApiMetricsLogger logger = new ApiMetricsLogger()) {
            
            // Example: Log API metrics
            logger.log(
                Instant.now(),
                LocalDate.now(),
                "user-service",
                "/api/v1/users",
                "GET",
                200,
                45L,
                null,
                1024L,
                "user_12345",
                "192.168.1.100",
                null
            );

            // Example: Log API error
            logger.log(
                Instant.now(),
                LocalDate.now(),
                "payment-service",
                "/api/v1/payments",
                "POST",
                500,
                1523L,
                2048L,
                0L,
                "user_67890",
                "10.0.0.5",
                "Database connection timeout"
            );

            System.out.println("API metrics logged successfully");
        } catch (Exception e) {
            System.err.println("Error logging API metrics: " + e.getMessage());
        }
    }

    private static Map<String, String> createProperties(String... keyValues) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
