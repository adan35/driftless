package io.driftless.recon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * A controllable, standalone HTTP stand-in for the partner-simulator, used by the recon module's
 * in-process tests and the property gate to inject partner fault modes <strong>deterministically</strong>.
 * It mirrors the auth module's test stub (the right tool for the in-process property test, per Spec
 * 07) and speaks the partner's wire contract ({@code /partner/authorize|capture|reverse} and {@code
 * /partner/state/{id}}).
 *
 * <p>Per test (or per operation, since the property test drives the saga sequentially) it can be told
 * how the authorize leg behaves: approve, decline, time out (hang past the caller's read timeout then
 * eventually approve — the "late response"), or fail-before-response (do the work and record a
 * partnerRef, then 500). It is idempotent on {@code requestId}, mirroring the real partner.
 */
final class ControllablePartner {

    enum AuthorizeMode {
        APPROVE,
        DECLINE,
        TIMEOUT,
        FAIL_BEFORE_RESPONSE
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpServer server;
    private final Map<String, Record> records = new ConcurrentHashMap<>();

    private volatile AuthorizeMode authorizeMode = AuthorizeMode.APPROVE;
    private volatile long timeoutSleepMillis = 2_000L;
    private volatile boolean reverseFails = false;

    private record SideEffect(String partnerRef, boolean delivered) {}

    private static final class Record {
        private final List<SideEffect> sideEffects = new ArrayList<>();
        private ObjectNode response;
    }

    ControllablePartner() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("failed to start controllable partner", e);
        }
        server.createContext("/partner/authorize", this::handleAuthorize);
        server.createContext("/partner/capture", this::handleCapture);
        server.createContext("/partner/reverse", this::handleReverse);
        server.createContext("/partner/state/", this::handleState);
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void setAuthorizeMode(AuthorizeMode mode) {
        this.authorizeMode = mode;
    }

    void setTimeoutSleepMillis(long millis) {
        this.timeoutSleepMillis = millis;
    }

    void setReverseFails(boolean reverseFails) {
        this.reverseFails = reverseFails;
    }

    void reset() {
        authorizeMode = AuthorizeMode.APPROVE;
        reverseFails = false;
        records.clear();
    }

    boolean reversed(String requestId) {
        Record record = records.get(requestId + "|reverse");
        return record != null
                && record.response != null
                && record.response.path("reversed").asBoolean(false);
    }

    private void handleAuthorize(HttpExchange exchange) throws IOException {
        Map<String, Object> body = readBody(exchange);
        String requestId = (String) body.get("requestId");
        Record existing = records.get(requestId);
        if (existing != null && existing.response != null) {
            send(exchange, 200, existing.response);
            return;
        }
        String partnerRef = "AUTH-" + UUID.randomUUID();
        switch (authorizeMode) {
            case APPROVE -> {
                ObjectNode response = approveNode(requestId, partnerRef);
                store(requestId, partnerRef, response, true);
                send(exchange, 200, response);
            }
            case DECLINE -> {
                ObjectNode response = mapper.createObjectNode();
                response.put("requestId", requestId);
                response.put("partnerRef", partnerRef);
                response.put("approved", false);
                response.put("declineReason", "PARTNER_RULE");
                response.put("duplicate", false);
                store(requestId, partnerRef, response, true);
                send(exchange, 200, response);
            }
            case TIMEOUT -> {
                sleep(timeoutSleepMillis);
                // The caller's read timeout has long since fired; the partner "eventually" approves.
                ObjectNode response = approveNode(requestId, partnerRef);
                store(requestId, partnerRef, response, true);
                send(exchange, 200, response);
            }
            case FAIL_BEFORE_RESPONSE -> {
                // Do the work (assign + record a partnerRef) but fail WITHOUT a successful response.
                records.computeIfAbsent(requestId, k -> new Record())
                        .sideEffects
                        .add(new SideEffect(partnerRef, false));
                send(exchange, 500, errorNode("fail-before-response"));
            }
        }
    }

    private void handleCapture(HttpExchange exchange) throws IOException {
        Map<String, Object> body = readBody(exchange);
        String requestId = (String) body.get("requestId");
        String partnerRef = (String) body.get("partnerRef");
        ObjectNode response = mapper.createObjectNode();
        response.put("requestId", requestId);
        response.put("partnerRef", partnerRef);
        response.put("captured", true);
        response.put("duplicate", false);
        store(requestId, partnerRef, response, true);
        send(exchange, 200, response);
    }

    private void handleReverse(HttpExchange exchange) throws IOException {
        Map<String, Object> body = readBody(exchange);
        String requestId = (String) body.get("requestId");
        String partnerRef = (String) body.get("partnerRef");
        if (reverseFails) {
            send(exchange, 500, errorNode("reverse-unavailable"));
            return;
        }
        ObjectNode response = mapper.createObjectNode();
        response.put("requestId", requestId);
        response.put("partnerRef", partnerRef);
        response.put("reversed", true);
        response.put("duplicate", false);
        store(requestId, partnerRef, response, true);
        send(exchange, 200, response);
    }

    private void handleState(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String requestId = path.substring(path.lastIndexOf('/') + 1);
        Record record = records.get(requestId);
        ObjectNode view = mapper.createObjectNode();
        view.put("requestId", requestId);
        if (record == null) {
            view.put("known", false);
            send(exchange, 404, view);
            return;
        }
        view.put("known", true);
        var sideEffects = view.putArray("sideEffects");
        for (SideEffect effect : record.sideEffects) {
            ObjectNode node = sideEffects.addObject();
            node.put("partnerRef", effect.partnerRef());
            node.put("delivered", effect.delivered());
        }
        if (record.response != null) {
            view.set("response", record.response);
        }
        send(exchange, 200, view);
    }

    private ObjectNode approveNode(String requestId, String partnerRef) {
        ObjectNode response = mapper.createObjectNode();
        response.put("requestId", requestId);
        response.put("partnerRef", partnerRef);
        response.put("approved", true);
        response.putNull("declineReason");
        response.put("duplicate", false);
        return response;
    }

    private ObjectNode errorNode(String message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("error", message);
        return node;
    }

    private void store(String requestId, String partnerRef, ObjectNode response, boolean delivered) {
        Record record = records.computeIfAbsent(requestId, k -> new Record());
        record.sideEffects.add(new SideEffect(partnerRef, delivered));
        record.response = response;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readBody(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        if (bytes.length == 0) {
            return Map.of();
        }
        return mapper.readValue(bytes, Map.class);
    }

    private void send(HttpExchange exchange, int status, ObjectNode body) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
