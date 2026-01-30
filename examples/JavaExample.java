import com.logging.generated.UserEventsLogger;
import com.logging.generated.ApiMetricsLogger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Example usage of generated structured loggers in Java.
 */
public class JavaExample {

    public static void main(String[] args) {
        logUserEvents();
        logApiMetrics();
    }

    private static void logUserEvents() {
        Random random = new Random();
        String[] eventTypes = {"page_view", "click", "search", "add_to_cart", "purchase"};
        String[] pages = {"/products/laptop", "/products/phone", "/checkout", "/cart", "/home"};
        String[] devices = {"desktop", "mobile", "tablet"};
        
        // Create logger instance
        try (UserEventsLogger logger = new UserEventsLogger()) {
            
            // Generate 5 unique user events
            for (int i = 0; i < 5; i++) {
                String userId = "user_" + UUID.randomUUID().toString().substring(0, 8);
                String sessionId = "session_" + UUID.randomUUID().toString().substring(0, 8);
                String eventType = eventTypes[random.nextInt(eventTypes.length)];
                String pageUrl = pages[random.nextInt(pages.length)];
                String deviceType = devices[random.nextInt(devices.length)];
                long durationMs = 100L + random.nextInt(5000);
                
                Map<String, String> properties = new HashMap<>();
                properties.put("request_id", UUID.randomUUID().toString());
                properties.put("source", random.nextBoolean() ? "organic" : "campaign");
                
                logger.log(
                    Instant.now(),
                    LocalDate.now(),
                    userId,
                    sessionId,
                    eventType,
                    pageUrl,
                    properties,
                    deviceType,
                    durationMs
                );
                
                // Small delay to ensure unique timestamps
                try { Thread.sleep(10); } catch (InterruptedException ignored) {}
            }

            System.out.println("User events logged successfully (5 unique events)");
        } catch (Exception e) {
            System.err.println("Error logging user events: " + e.getMessage());
        }
    }

    private static void logApiMetrics() {
        Random random = new Random();
        String[] services = {"user-service", "payment-service", "order-service", "inventory-service", "notification-service"};
        String[] endpoints = {"/api/v1/users", "/api/v1/payments", "/api/v1/orders", "/api/v1/inventory", "/api/v1/notifications"};
        String[] methods = {"GET", "POST", "PUT", "DELETE"};
        int[] statusCodes = {200, 201, 400, 404, 500, 503};
        
        try (ApiMetricsLogger logger = new ApiMetricsLogger()) {
            
            // Generate 5 unique API metrics
            for (int i = 0; i < 5; i++) {
                String serviceName = services[random.nextInt(services.length)];
                String endpoint = endpoints[random.nextInt(endpoints.length)];
                String method = methods[random.nextInt(methods.length)];
                int statusCode = statusCodes[random.nextInt(statusCodes.length)];
                long responseTimeMs = 10L + random.nextInt(2000);
                Long requestSize = random.nextBoolean() ? (long) random.nextInt(10000) : null;
                Long responseSize = random.nextBoolean() ? (long) random.nextInt(50000) : null;
                String userId = "user_" + UUID.randomUUID().toString().substring(0, 8);
                String clientIp = "192.168." + random.nextInt(256) + "." + random.nextInt(256);
                String errorMessage = statusCode >= 400 ? "Error occurred: " + UUID.randomUUID().toString().substring(0, 8) : null;
                
                logger.log(
                    Instant.now(),
                    LocalDate.now(),
                    serviceName,
                    endpoint,
                    method,
                    statusCode,
                    responseTimeMs,
                    requestSize,
                    responseSize,
                    userId,
                    clientIp,
                    errorMessage
                );
                
                // Small delay to ensure unique timestamps
                try { Thread.sleep(10); } catch (InterruptedException ignored) {}
            }

            System.out.println("API metrics logged successfully (5 unique metrics)");
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
