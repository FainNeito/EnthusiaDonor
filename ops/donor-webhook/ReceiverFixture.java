import com.enthusia.donors.sandbox.official.notifications.TebexHttpReceiver;
import com.enthusia.donors.sandbox.official.notifications.TebexWebhookEndpoint;
import java.net.InetSocketAddress;

/** Actual receiver and signature implementation; no DB, API client or dispatch bridge. */
public final class ReceiverFixture {
    public static void main(String[] args) throws Exception {
        try (var receiver = new TebexHttpReceiver(new InetSocketAddress("127.0.0.1", 0),
                new TebexWebhookEndpoint("isolated-route-fixture-secret-not-production", null))) {
            receiver.start();
            System.out.println(receiver.port());
            System.out.flush();
            System.in.read();
        }
    }
}
