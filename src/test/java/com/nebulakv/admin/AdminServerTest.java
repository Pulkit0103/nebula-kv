package com.nebulakv.admin;

import com.nebulakv.cluster.ClusterNode;
import com.nebulakv.cluster.MembershipManager;
import com.nebulakv.metrics.MetricsRegistry;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AdminServer — HTTP management endpoints")
class AdminServerTest {

    private AdminServer server;
    private MembershipManager membership;
    private MetricsRegistry metrics;
    private HttpClient client;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        membership = new MembershipManager();
        metrics    = new MetricsRegistry();
        // Port 0 → OS assigns a free port (avoids conflicts in CI).
        server = new AdminServer(membership, metrics, 0);
        server.start();
        port   = server.port();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    @DisplayName("GET /health returns 200 with UP when active nodes exist")
    void healthUpWhenActiveNodes() throws Exception {
        membership.join(ClusterNode.active("node1", "localhost", 7001));

        HttpResponse<String> resp = get("/health");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"status\":\"UP\""),
                "Body: " + resp.body());
    }

    @Test
    @DisplayName("GET /health returns DEGRADED when no active nodes")
    void healthDegradedWhenNoActive() throws Exception {
        HttpResponse<String> resp = get("/health");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"status\":\"DEGRADED\""),
                "Body: " + resp.body());
    }

    @Test
    @DisplayName("GET /metrics returns Prometheus text format")
    void metricsReturnsPrometheusText() throws Exception {
        metrics.increment("write_ops_total");
        metrics.gauge("active_connections", 3);

        HttpResponse<String> resp = get("/metrics");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("write_ops_total"),
                "Must contain counter name. Body: " + resp.body());
        assertTrue(resp.body().contains("active_connections"),
                "Must contain gauge name. Body: " + resp.body());
    }

    @Test
    @DisplayName("GET /cluster/nodes returns JSON array of active nodes")
    void clusterNodesReturnsJson() throws Exception {
        membership.join(ClusterNode.active("node1", "localhost", 7001));
        membership.join(ClusterNode.active("node2", "localhost", 7002));

        HttpResponse<String> resp = get("/cluster/nodes");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("node1"), "Must include node1");
        assertTrue(resp.body().contains("node2"), "Must include node2");
    }

    @Test
    @DisplayName("GET /cluster/status returns node counts")
    void clusterStatusReturnsCounts() throws Exception {
        membership.join(ClusterNode.active("n1", "localhost", 7001));
        membership.join(ClusterNode.active("n2", "localhost", 7002));

        HttpResponse<String> resp = get("/cluster/status");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"active\":2"), "Body: " + resp.body());
    }

    @Test
    @DisplayName("non-GET returns 405")
    void nonGetReturns405() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/health"))
                .method("POST", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(405, resp.statusCode());
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
