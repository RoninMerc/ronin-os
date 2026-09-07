package com.ronin.vanta;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Category-specific strongest-first ordering for Vanta. */
public final class ModelRanker {
    private ModelRanker() {}

    public static boolean matchesCategory(ModelInfo m, String category) {
        if (m == null) return false;
        if ("code".equals(category)) return "text".equals(m.type);
        if ("image".equals(category)) {
            if (!"image".equals(m.type)) return false;
            String s = haystack(m);
            return !s.contains("-edit") && !s.contains(" edit") && !s.contains("upscale") &&
                    !s.contains("upscaler") && !s.contains("background remover") && !s.contains("bg-remover");
        }
        if ("video".equals(category)) {
            if (!"video".equals(m.type)) return false;
            String s = haystack(m);
            return !s.contains("image-to-video") && !s.contains("reference-to-video") &&
                    !s.contains("video-to-video") && !s.contains("motion-control") &&
                    !s.contains("transition") && !s.contains("upscale");
        }
        return category.equals(m.type);
    }

    public static int score(ModelInfo m, String category) {
        String s = haystack(m);
        if ("image".equals(category)) return imageScore(s);
        if ("video".equals(category)) return videoScore(s);
        return textScore(s, "code".equals(category));
    }

    private static int textScore(String s, boolean code) {
        int score = 1000;
        if (has(s, "gpt-6", "gpt 6")) score += 9000;
        else if (has(s, "claude opus 5", "claude-opus-5")) score += 8750;
        else if (has(s, "kimi k3", "kimi-k3")) score += 8600;
        else if (has(s, "gpt-5.6", "gpt-56")) score += 8450;
        else if (has(s, "grok 4.20", "grok-4-20")) score += 8350;
        else if (has(s, "qwen 3.8", "qwen-3-8", "qwen3-8")) score += 8250;
        else if (has(s, "glm 5.3", "glm-5-3")) score += 8150;
        else if (has(s, "claude opus 4.8", "claude-opus-4-8")) score += 8050;
        else if (has(s, "deepseek v4 pro", "deepseek-v4-pro")) score += 7950;
        else if (has(s, "qwen 3.7", "qwen-3-7")) score += 7850;
        else if (has(s, "gpt-5.5", "gpt-55")) score += 7750;
        else if (has(s, "gemini 3.1 pro", "gemini-3-1-pro")) score += 7650;
        else if (has(s, "claude sonnet 5", "claude-sonnet-5")) score += 7550;
        else if (has(s, "glm 5.2", "glm-5-2")) score += 7450;
        else if (has(s, "grok 4.6", "grok-4-6")) score += 7350;
        else if (has(s, "grok 4.5", "grok-4-5")) score += 7250;
        else if (has(s, "qwen 3.6", "qwen-3-6", "qwen3-6")) score += 7150;
        else if (has(s, "gpt-5.4", "gpt-54")) score += 7050;
        else if (has(s, "claude opus 4.7", "claude-opus-4-7")) score += 6950;
        else if (has(s, "deepseek v4", "deepseek-v4")) score += 6850;
        else if (has(s, "minimax m3", "minimax-m3")) score += 6750;
        else if (has(s, "qwen 3.5 397", "qwen3-5-397")) score += 6650;
        else if (has(s, "gpt-5.3", "gpt-53")) score += 6550;
        else if (has(s, "claude sonnet 4.6", "claude-sonnet-4-6")) score += 6450;
        else if (has(s, "glm 5", "glm-5")) score += 6350;
        else if (has(s, "kimi k2.7", "kimi-k2-7")) score += 6250;
        else if (has(s, "kimi k2.6", "kimi-k2-6")) score += 6150;
        else if (has(s, "qwen 3 coder 480", "qwen3-coder-480")) score += 6050;
        else score += genericFamilyVersionScore(s);

        if (has(s, " reasoning", "thinking")) score += 260;
        if (has(s, " pro", "-pro")) score += 260;
        if (has(s, " max", "-max")) score += 190;
        if (has(s, " opus", "-opus")) score += 180;
        if (has(s, " terra", "-terra")) score += 150;
        if (has(s, " sol", "-sol")) score += 120;
        if (has(s, "multi-agent", "multi agent")) score += 170;

        if (code) {
            if (has(s, "codex")) score += 1100;
            if (has(s, "coder")) score += 1000;
            if (has(s, " code", "-code")) score += 850;
            if (has(s, "build")) score += 420;
            if (has(s, "reasoning", "thinking")) score += 220;
        }

        if (has(s, " mini", "-mini")) score -= 650;
        if (has(s, " nano", "-nano")) score -= 850;
        if (has(s, " lite", "-lite")) score -= 700;
        if (has(s, " small", "-small")) score -= 520;
        if (has(s, " flash-lite", "flash lite")) score -= 700;
        else if (has(s, " flash", "-flash")) score -= 260;
        if (has(s, "3b", "7b", "9b")) score -= 280;
        return score;
    }

    private static int imageScore(String s) {
        int score;
        if (has(s, "gpt-image-2", "gpt image 2")) score = 20000;
        else if (has(s, "grok-imagine-image-2-0", "grok imagine 2.0")) score = 19850;
        else if (has(s, "grok-imagine-image-quality", "grok imagine high quality", "grok-imagine-quality")) score = 19800;
        else if (has(s, "recraft-v4-pro", "recraft v4 pro")) score = 19600;
        else if (has(s, "seedream-v5-pro", "seedream v5 pro")) score = 19400;
        else if (has(s, "qwen-image-3-pro", "qwen image 3 pro")) score = 19200;
        else if (has(s, "nano-banana-pro", "nano banana pro")) score = 19000;
        else if (has(s, "flux-2-max", "flux 2 max")) score = 18800;
        else if (has(s, "ideogram-v4", "ideogram v4")) score = 18600;
        else if (has(s, "wan-2-7-pro-text-to-image", "wan 2.7 pro")) score = 18400;
        else if (has(s, "imagineart-1.5-pro", "imagineart 1.5 pro")) score = 18200;
        else if (has(s, "luma-uni-1-max", "luma uni-1 max")) score = 18000;
        else if (has(s, "qwen-image-3", "qwen image 3")) score = 17800;
        else if (has(s, "seedream-v5-lite", "seedream v5 lite")) score = 17600;
        else if (has(s, "recraft-v4", "recraft v4")) score = 17400;
        else if (has(s, "gpt-image-1-5", "gpt image 1.5")) score = 17200;
        else if (has(s, "nano-banana-2", "nano banana 2")) score = 17000;
        else if (has(s, "qwen-image-2-pro", "qwen image 2 pro")) score = 16800;
        else if (has(s, "flux-2-pro", "flux 2 pro")) score = 16600;
        else if (has(s, "seedream-v4", "seedream v4")) score = 16400;
        else if (has(s, "hunyuan-image-v3", "hunyuan image 3")) score = 16200;
        else score = 9000 + genericFamilyVersionScore(s);
        if (has(s, " pro", "-pro")) score += 80;
        if (has(s, " max", "-max")) score += 60;
        if (has(s, " quality", "sota")) score += 50;
        if (has(s, " lite", "-lite")) score -= 250;
        if (has(s, " turbo", "-turbo")) score -= 80;
        return score;
    }

    private static int videoScore(String s) {
        int score;
        if (has(s, "wan-3-0-prime", "wan 3.0 prime")) score = 24000;
        else if (has(s, "kling-v3-4k", "kling v3 4k")) score = 23800;
        else if (has(s, "kling-v3-pro", "kling v3 pro")) score = 23700;
        else if (has(s, "veo3.1-full", "veo 3.1 full")) score = 23600;
        else if (has(s, "seedance-2-5", "seedance 2.5")) score = 23400;
        else if (has(s, "runway-gen4-5", "runway gen-4.5")) score = 23200;
        else if (has(s, "minimax-h3-max", "minimax h3 max")) score = 23000;
        else if (has(s, "ltx-2-5-pro", "ltx video 2.5 pro")) score = 22800;
        else if (has(s, "grok-imagine-1-5", "grok imagine 1.5")) score = 22600;
        else if (has(s, "flux-3", "flux 3")) score = 22400;
        else if (has(s, "wan-3-0", "wan 3.0")) score = 22200;
        else if (has(s, "kling-o3-4k", "kling o3 4k")) score = 22000;
        else if (has(s, "kling-o3-pro", "kling o3 pro")) score = 21900;
        else if (has(s, "pixverse-c1", "pixverse c1")) score = 21700;
        else if (has(s, "veo3-full", "veo 3 full")) score = 21500;
        else if (has(s, "kling-2.6-pro", "kling 2.6 pro")) score = 21300;
        else if (has(s, "seedance-2-0", "seedance 2.0")) score = 21100;
        else if (has(s, "wan-2-7-enhanced", "wan 2.7 enhanced")) score = 20900;
        else if (has(s, "wan-2-7", "wan 2.7")) score = 20700;
        else if (has(s, "pixverse-v5.6", "pixverse v5.6")) score = 20500;
        else if (has(s, "ltx-2-v2-3-full", "ltx video 2.3 full")) score = 20300;
        else if (has(s, "veo3.1-fast", "veo 3.1 fast")) score = 20100;
        else if (has(s, "kling-v3-turbo-pro", "kling v3 turbo pro")) score = 19900;
        else if (has(s, "veo3-fast", "veo 3 fast")) score = 19700;
        else score = 10000 + genericFamilyVersionScore(s);
        if (has(s, "4k")) score += 120;
        if (has(s, " prime", "-prime")) score += 100;
        if (has(s, " full", "-full")) score += 90;
        if (has(s, " pro", "-pro")) score += 80;
        if (has(s, " max", "-max")) score += 70;
        if (has(s, " enhanced", "-enhanced")) score += 50;
        if (has(s, " standard", "-standard")) score -= 220;
        if (has(s, " mini", "-mini")) score -= 350;
        if (has(s, " fast", "-fast")) score -= 100;
        if (has(s, " turbo", "-turbo")) score -= 80;
        return score;
    }

    private static int genericFamilyVersionScore(String s) {
        int score = 1000;
        Matcher m = Pattern.compile("(?:v|gen[- ]?|gpt[- ]?|qwen[- ]?|glm[- ]?|kling[- ]?|wan[- ]?)(\\d+)(?:[.\\-_ ](\\d+))?", Pattern.CASE_INSENSITIVE).matcher(s);
        int best = 0;
        while (m.find()) {
            int major = safeInt(m.group(1));
            int minor = safeInt(m.group(2));
            best = Math.max(best, major * 140 + minor * 18);
        }
        score += Math.min(best, 2200);
        if (has(s, "reasoning", "thinking")) score += 180;
        if (has(s, "vision", "vl")) score += 70;
        if (has(s, "pro")) score += 140;
        if (has(s, "max")) score += 120;
        return score;
    }

    private static int safeInt(String s) {
        if (s == null) return 0;
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }

    private static String haystack(ModelInfo m) {
        return (m.id + " " + m.name + " " + m.description).toLowerCase(Locale.US);
    }

    private static boolean has(String s, String... needles) {
        for (String n : needles) if (s.contains(n.toLowerCase(Locale.US))) return true;
        return false;
    }
}
