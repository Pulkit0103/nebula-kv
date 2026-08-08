package com.nebulakv.admin;

import com.nebulakv.cluster.MembershipManager;
import com.nebulakv.metrics.MetricsRegistry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * Lightweight built-in HTTP admin server.
 *
 * Exposes management endpoints over HTTP using the JDK's built-in
 * com.sun.net.httpserver.HttpServer (no external dependencies).
 * In production, these would typically be served by Spring Boot Actuator
 * or Vert.x, but this approach avoids adding a full web framework for
 * a portfolio project.
 *
 * Endpoints:
 *   GET  /health           — returns {"status":"UP"} or {"status":"DOWN"}
 *   GET  /metrics          — Prometheus text format
 *   GET  /cluster/nodes    — JSON array of known cluster members
 *   GET  /cluster/status   — active/suspect/down counts
 *
 * Default port: 7778
 */
public final class AdminServer {

    public static final int DEFAULT_PORT = 7778;

    private final HttpServer httpServer;
    private final MembershipManager membership;
    private final MetricsRegistry metrics;

    public AdminServer(MembershipManager membership, MetricsRegistry metrics) throws IOException {
        this(membership, metrics, DEFAULT_PORT);
    }

    public AdminServer(MembershipManager membership, MetricsRegistry metrics, int port)
            throws IOException {
        this.membership = membership;
        this.metrics    = metrics;

        httpServer = HttpServer.create(new InetSocketAddress(port), /*backlog*/ 10);
        httpServer.setExecutor(Executors.newFixedThreadPool(2,
                r -> { Thread t = new Thread(r, "nebula-admin"); t.setDaemon(true); return t; }));

        httpServer.createContext("/health",        this::handleHealth);
        httpServer.createContext("/metrics",       this::handleMetrics);
        httpServer.createContext("/cluster/nodes", this::handleClusterNodes);
        httpServer.createContext("/cluster/status",this::handleClusterStatus);
    }

    public void start() {
        httpServer.start();
    }

    public void stop() {
        httpServer.stop(0);
    }

    public int port() {
        return httpServer.getAddress().getPort();
    }

    // -------------------------------------------------------------------------
    // Handlers
    // -------------------------------------------------------------------------

    private void handleHealth(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            respond(ex, 405, "text/plain", "Method Not Allowed");
            return;
        }
        int active = membership.activeMembers().size();
        String body = active > 0
                ? "{\"status\":\"UP\",\"activeNodes\":" + active + "}"
                : "{\"status\":\"DEGRADED\",\"activeNodes\":0}";
        respond(ex, 200, "application/json", body);
    }

    private void handleMetrics(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            respond(ex, 405, "text/plain", "Method Not Allowed");
            return;
        }
        respond(ex, 200, "text/plain; version=0.0.4; charset=utf-8", metrics.scrape());
    }

    private void handleClusterNodes(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            respond(ex, 405, "text/plain", "Method Not Allowed");
            return;
        }
        StringBuilder sb = new StringBuilder("[");
        var members = membership.activeMembers();
        for (int i = 0; i < members.size(); i++) {
            var node = members.get(i);
            sb.append("{\"nodeId\":\"").append(node.nodeId())
              .append("\",\"host\":\"").append(node.host())
              .append("\",\"port\":").append(node.port())
              .append(",\"status\":\"").append(node.status()).append("\"}");
            if (i < members.size() - 1) sb.append(",");
        }
        sb.append("]");
        respond(ex, 200, "application/json", sb.toString());
    }

    private void handleClusterStatus(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            respond(ex, 405, "text/plain", "Method Not Allowed");
            return;
        }
        // Count nodes per status by walking the active members.
        int active = membership.activeMembers().size();
        String body = "{\"total\":" + membership.size()
                + ",\"active\":" + active + "}";
        respond(ex, 200, "application/json", body);
    }

    private static void respond(HttpExchange ex, int code, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
