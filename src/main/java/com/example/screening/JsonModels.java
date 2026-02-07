package com.example.screening;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

public final class JsonModels {
    private JsonModels() {}


    public static class InputRecord {
        public String requestId;
        public String fullName;
        public String country;
        public List<String> aliases;
    }

    public static class WatchlistEntry {
        public String id;
        public String name;
    }


    public static class NamePair {
        public String raw;
        public String normalized;

        public NamePair() {}

        public NamePair(String raw, String normalized) {
            this.raw = raw;
            this.normalized = normalized;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ConsolidatedOutput {
        public String requestId;
        public String matchType;
        public String bestMatchId; // null when NO_MATCH
        public double score;
        public String timestamp;
    }

    public static class DetailedOutput {
        public String requestId;
        public String country;
        public List<NamePair> inputNames;      
        public BestMatch bestMatch;
        public List<ScoredMatch> top3Matches; 
    }

    public static class BestMatch {
        public String watchlistId;
        public String watchlistName;
        public double score;
        public String matchType;
        public NamePair matchedUsingInput; 
    }

    public static class ScoredMatch {
        public String watchlistId;
        public String watchlistName;
        public double score;

        // keeps what input string produced this match
        public String matchedUsingInputRaw;
        public String inputNormalized;

        public ScoredMatch() {}

        public ScoredMatch(String wid, String wname, double score, String inputRaw, String inNorm) {
            this.watchlistId = wid;
            this.watchlistName = wname;
            this.score = score;
            this.matchedUsingInputRaw = inputRaw;
            this.inputNormalized = inNorm;
        }
    }
}
