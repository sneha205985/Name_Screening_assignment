package com.example.screening;

import com.example.screening.JsonModels.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.time.OffsetDateTime;
import java.util.*;

public class ScreeningService {

    private final HttpServer server;
    private final ObjectMapper mapper;

    public ScreeningService(int port) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

        // Route: POST /process/{userId}/{requestId}
        server.createContext("/process", this::handleProcess);
    }

    public void start() {
        System.out.println("Server running on http://127.0.0.1:" + server.getAddress().getPort());
        server.start();
    }

    private void handleProcess(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, Map.of("ok", false, "error", "Method Not Allowed"));
            return;
        }

        String path = ex.getRequestURI().getPath(); // /process/user1/req1
        String[] parts = path.split("/");
        if (parts.length != 4) {
            sendJson(ex, 400, Map.of("ok", false, "error", "Expected /process/{userId}/{requestId}"));
            return;
        }

        String userId = parts[2];
        String requestId = parts[3];

        try {
            Map<String, Object> result = processRequest(userId, requestId);
            sendJson(ex, 200, result);
        } catch (ScreeningException se) {
            log(requestId, "ERROR: " + se.getMessage());
            sendJson(ex, 400, Map.of("ok", false, "error", se.getMessage()));
        } catch (Exception e) {
            log(requestId, "ERROR: Unexpected failure: " + e.getMessage());
            sendJson(ex, 500, Map.of("ok", false, "error", "Internal Server Error"));
        }
    }

    private Map<String, Object> processRequest(String userId, String requestId) throws IOException {
        Path inputPath = Paths.get("data", userId, requestId, "input", "input.json");
        Path watchlistPath = Paths.get("watchlist.json");
        Path outputDir = Paths.get("data", userId, requestId, "output");
        Path detailedPath = outputDir.resolve("detailed.json");
        Path consolidatedPath = outputDir.resolve("consolidated.json");

        log(requestId, "Start processing userId=" + userId);

        // Skip if output exists
        if (Files.exists(detailedPath) && Files.exists(consolidatedPath)) {
            log(requestId, "Skipping reprocessing: outputs already exist");
            return Map.of("ok", true, "status", "skipped", "outputPath", outputDir.toString());
        }

        // Read input/watchlist (stop on error)
        InputRecord input = readJsonOrFail(inputPath, InputRecord.class, requestId,
                "Missing or invalid input.json");
        WatchlistEntry[] watchlistArr = readJsonOrFail(watchlistPath, WatchlistEntry[].class, requestId,
                "Missing or invalid watchlist.json");

        List<WatchlistEntry> watchlist = Arrays.asList(watchlistArr);

        // Collect candidate names (fullName + aliases)
        List<String> candidateNames = new ArrayList<>();
        if (input.fullName != null && !input.fullName.isBlank()) candidateNames.add(input.fullName);

        if (input.aliases != null) {
            for (String a : input.aliases) {
                if (a != null && !a.isBlank()) candidateNames.add(a);
            }
        }

        if (candidateNames.isEmpty()) {
            throw new ScreeningException("No valid names found in input (fullName/aliases).");
        }

        log(requestId, "Loaded input names=" + candidateNames.size() + ", watchlist=" + watchlist.size());

        // Normalize input names -> JsonModels.NamePair
        List<JsonModels.NamePair> inputNames = new ArrayList<>();
        for (String raw : candidateNames) {
            inputNames.add(new JsonModels.NamePair(raw, NameUtils.normalize(raw)));
        }

        // Pre-normalize watchlist names
        List<WatchlistNorm> watchNorm = new ArrayList<>();
        for (WatchlistEntry w : watchlist) {
            watchNorm.add(new WatchlistNorm(w.id, w.name, NameUtils.normalize(w.name)));
        }

        // Compare all input names vs all watchlist names
        Match best = null;
        List<ScoredMatch> allComparisons = new ArrayList<>();

        for (JsonModels.NamePair in : inputNames) {
            for (WatchlistNorm w : watchNorm) {
                double s = Similarity.score(in.normalized, w.normalized);

                // ScoredMatch assumed to have this constructor:
                // (String watchlistId, String watchlistName, double score, String inputRaw, String inputNormalized)
                ScoredMatch sm = new ScoredMatch(w.id, w.name, round4(s), in.raw, in.normalized);
                allComparisons.add(sm);

                if (best == null || s > best.score) {
                    best = new Match(w.id, w.name, w.normalized, s, in.raw, in.normalized);
                }
            }
        }

        allComparisons.sort((a, b) -> Double.compare(b.score, a.score));

        // Top 3 closest UNIQUE watchlist entries
        List<ScoredMatch> top3 = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ScoredMatch sm : allComparisons) {
            if (seen.add(sm.watchlistId)) {
                top3.add(sm);
                if (top3.size() == 3) break;
            }
        }

        double bestScore = best.score;
        String matchType = classify(bestScore);

        log(requestId, "Best match id=" + best.watchlistId + " score=" + round4(bestScore) + " type=" + matchType);

        // Only now create output directory + files
        Files.createDirectories(outputDir);

        DetailedOutput detailed = new DetailedOutput();
        detailed.requestId = (input.requestId != null && !input.requestId.isBlank()) ? input.requestId : requestId;
        detailed.country = input.country;
        detailed.inputNames = inputNames;

        detailed.bestMatch = new BestMatch();
        detailed.bestMatch.watchlistId = best.watchlistId;
        detailed.bestMatch.watchlistName = best.watchlistName;
        detailed.bestMatch.score = round4(bestScore);
        detailed.bestMatch.matchType = matchType;
        detailed.bestMatch.matchedUsingInput = new JsonModels.NamePair(best.inputRaw, best.inputNormalized);

        detailed.top3Matches = top3;

        ConsolidatedOutput consolidated = new ConsolidatedOutput();
        consolidated.requestId = detailed.requestId;
        consolidated.matchType = matchType;
        consolidated.bestMatchId = "NO_MATCH".equals(matchType) ? null : best.watchlistId;
        consolidated.score = round4(bestScore);
        consolidated.timestamp = OffsetDateTime.now().toString();

        mapper.writeValue(detailedPath.toFile(), detailed);
        mapper.writeValue(consolidatedPath.toFile(), consolidated);

        log(requestId, "Wrote output to " + outputDir);

        return Map.of("ok", true, "status", "processed", "outputPath", outputDir.toString());
    }

    private <T> T readJsonOrFail(Path path, Class<T> clazz, String requestId, String errMsg) {
        if (!Files.exists(path)) {
            throw new ScreeningException(errMsg + " (missing: " + path + ")");
        }
        try (InputStream in = Files.newInputStream(path)) {
            return mapper.readValue(in, clazz);
        } catch (Exception e) {
            throw new ScreeningException(errMsg + " (" + path + ")");
        }
    }

    private void sendJson(HttpExchange ex, int status, Object obj) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(obj);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void log(String requestId, String msg) {
        System.out.println("[requestId=" + requestId + "] " + msg);
    }

    private String classify(double score) {
        if (score >= 0.90) return "EXACT_MATCH";
        if (score >= 0.75) return "POSSIBLE_MATCH";
        return "NO_MATCH";
    }

    private double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static class ScreeningException extends RuntimeException {
        ScreeningException(String m) { super(m); }
    }

    // Local helper types (NOT part of output models)
    private record WatchlistNorm(String id, String name, String normalized) {}
    private record Match(String watchlistId, String watchlistName, String watchlistNormalized,
                         double score, String inputRaw, String inputNormalized) {}
}