package com.example.screening;

import java.util.*;


public final class Similarity {
    private Similarity() {}

    public static double score(String a, String b) {
        if (a == null || b == null) return 0.0;
        a = a.trim();
        b = b.trim();
        if (a.isEmpty() || b.isEmpty()) return 0.0;

        // quick wins
        if (a.equals(b)) return 1.0;

        // 1) Token overlap gate (very important for realism)
        List<String> ta = tokens(a);
        List<String> tb = tokens(b);

        if (ta.isEmpty() || tb.isEmpty()) return 0.0;

        // Overlap based on simple token equality + initial handling (a vs alex)
        double overlap = tokenOverlapScore(ta, tb);

        // If there is almost no token relationship, cap the score hard.
        // This prevents "Amina Yusuf Bello" showing as "close" to "Arvind Narain Iyer".
        if (overlap < 0.34) {
            // Still allow some score (typos on single word cases), but keep it low.
            double base = levenshteinRatio(a, b);
            return clamp(0.15 + 0.55 * base * overlap);
        }

        // 2) Build comparable string forms to handle reordering
        String aSort = NameUtils.tokenSort(a);
        String bSort = NameUtils.tokenSort(b);

        String aSet = tokenSetForm(ta);
        String bSet = tokenSetForm(tb);

        // 3) Character-level similarity on multiple forms
        double c1 = levenshteinRatio(a, b);
        double c2 = levenshteinRatio(aSort, bSort);
        double c3 = levenshteinRatio(aSet, bSet);

        // 4) Blend (weights chosen to prioritise realistic matching)
        double charScore = 0.45 * c1 + 0.35 * c2 + 0.20 * c3;

        // 5) Final score: overlap influences but does not fully dominate
        // This keeps strong typos still matchable if tokens align.
        double finalScore = 0.70 * charScore + 0.30 * overlap;

        // Additional gentle bonus for strong first/last token alignment
        finalScore += edgeTokenBonus(ta, tb);

        return clamp(finalScore);
    }

    // -----------------------------
    // Token helpers
    // -----------------------------

    private static List<String> tokens(String s) {
        // input strings are already "normalized" upstream in your pipeline,
        // but we still do a safe split and remove blanks.
        String[] parts = s.toLowerCase(Locale.ROOT).trim().split("\\s+");
        List<String> out = new ArrayList<>(parts.length);
        for (String p : parts) {
            if (!p.isBlank()) out.add(p);
        }
        return out;
    }

    
    private static double tokenOverlapScore(List<String> a, List<String> b) {
        // Use multiset-like matching (each token can match once)
        List<String> bPool = new ArrayList<>(b);

        double hit = 0.0;
        for (String t : a) {
            int idx = findExact(bPool, t);
            if (idx >= 0) {
                hit += 1.0;
                bPool.remove(idx);
                continue;
            }

            // initial support: "a" matches "alex" / "arvind" partially
            if (t.length() == 1) {
                int j = findStartsWith(bPool, t);
                if (j >= 0) {
                    hit += 0.55; // partial credit
                    bPool.remove(j);
                }
            }
        }

        // normalise by average token count (keeps score stable for short vs long names)
        double denom = (a.size() + b.size()) / 2.0;
        if (denom <= 0.0) return 0.0;
        return clamp(hit / denom);
    }

    private static int findExact(List<String> pool, String token) {
        for (int i = 0; i < pool.size(); i++) {
            if (pool.get(i).equals(token)) return i;
        }
        return -1;
    }

    private static int findStartsWith(List<String> pool, String initial) {
        for (int i = 0; i < pool.size(); i++) {
            String t = pool.get(i);
            if (!t.isEmpty() && t.startsWith(initial)) return i;
        }
        return -1;
    }

    private static String tokenSetForm(List<String> tokens) {
        // Unique + sorted -> stable string
        TreeSet<String> set = new TreeSet<>(tokens);
        return String.join(" ", set);
    }

    private static double edgeTokenBonus(List<String> a, List<String> b) {
        // Bonus if last name aligns strongly; common in screening.
        String aLast = a.get(a.size() - 1);
        String bLast = b.get(b.size() - 1);

        double bonus = 0.0;

        if (aLast.equals(bLast)) bonus += 0.05;
        else if (aLast.length() == 1 && bLast.startsWith(aLast)) bonus += 0.02;
        else if (bLast.length() == 1 && aLast.startsWith(bLast)) bonus += 0.02;

        // Small bonus if first token aligns
        String aFirst = a.get(0);
        String bFirst = b.get(0);
        if (aFirst.equals(bFirst)) bonus += 0.03;
        else if (aFirst.length() == 1 && bFirst.startsWith(aFirst)) bonus += 0.015;
        else if (bFirst.length() == 1 && aFirst.startsWith(bFirst)) bonus += 0.015;

        return bonus;
    }

    private static double levenshteinRatio(String s1, String s2) {
        if (s1.equals(s2)) return 1.0;
        int len1 = s1.length();
        int len2 = s2.length();
        if (len1 == 0 || len2 == 0) return 0.0;

        int dist = levenshteinDistance(s1, s2);

        int maxLen = Math.max(len1, len2);
        double ratio = 1.0 - (dist / (double) maxLen);

        return clamp(ratio);
    }

    // DP with O(min(n,m)) memory
    private static int levenshteinDistance(String a, String b) {
        // Ensure b is the shorter one to minimise memory
        if (a.length() < b.length()) {
            String tmp = a; a = b; b = tmp;
        }

        int n = a.length();
        int m = b.length();

        int[] prev = new int[m + 1];
        int[] curr = new int[m + 1];

        for (int j = 0; j <= m; j++) prev[j] = j;

        for (int i = 1; i <= n; i++) {
            curr[0] = i;
            char ca = a.charAt(i - 1);

            for (int j = 1; j <= m; j++) {
                char cb = b.charAt(j - 1);
                int cost = (ca == cb) ? 0 : 1;

                int del = prev[j] + 1;
                int ins = curr[j - 1] + 1;
                int sub = prev[j - 1] + cost;

                curr[j] = Math.min(Math.min(del, ins), sub);
            }

            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }

        return prev[m];
    }

    private static double clamp(double v) {
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }
}