package com.tiam.challenge.service;

/**
 * A coarse, human-readable description of the device behind a User-Agent ("Android · Chrome", "iPhone · Safari").
 * Enough to tell "the same phone in two browsers" from "nine different phones" when reading the Desafío device
 * counts; deliberately nothing finer (no versions, no models): it is not a fingerprint.
 *
 * <p>In-app browsers are named after the app (Instagram, Facebook, WhatsApp) because a link opened from them keeps
 * its own storage, so the same person shows up as a second device there.
 */
final class DeviceLabels {

    static final int MAX_LENGTH = 80;

    private DeviceLabels() {
    }

    static String describe(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "Desconocido";
        }
        String label = os(userAgent) + " · " + browser(userAgent);
        return label.length() > MAX_LENGTH ? label.substring(0, MAX_LENGTH) : label;
    }

    private static String os(String ua) {
        if (ua.contains("iPhone")) {
            return "iPhone";
        }
        if (ua.contains("iPad")) {
            return "iPad";
        }
        if (ua.contains("Android")) {
            return "Android";
        }
        if (ua.contains("Windows")) {
            return "Windows";
        }
        if (ua.contains("Macintosh") || ua.contains("Mac OS X")) {
            return "Mac";
        }
        if (ua.contains("CrOS")) {
            return "ChromeOS";
        }
        if (ua.contains("Linux")) {
            return "Linux";
        }
        return "Otro";
    }

    /** App webviews first (they also say Chrome/Safari), then the browsers that also say Chrome, then the rest. */
    private static String browser(String ua) {
        if (ua.contains("Instagram")) {
            return "Instagram";
        }
        if (ua.contains("FBAN") || ua.contains("FBAV") || ua.contains("FB_IAB")) {
            return "Facebook";
        }
        if (ua.contains("WhatsApp")) {
            return "WhatsApp";
        }
        if (ua.contains("Edg/") || ua.contains("EdgA/") || ua.contains("EdgiOS")) {
            return "Edge";
        }
        if (ua.contains("SamsungBrowser")) {
            return "Samsung Internet";
        }
        if (ua.contains("OPR/") || ua.contains("Opera")) {
            return "Opera";
        }
        if (ua.contains("Firefox") || ua.contains("FxiOS")) {
            return "Firefox";
        }
        if (ua.contains("CriOS")) {
            return "Chrome";
        }
        if (ua.contains("; wv)")) {
            return "WebView";
        }
        if (ua.contains("Chrome/")) {
            return "Chrome";
        }
        if (ua.contains("Safari/")) {
            return "Safari";
        }
        return "Otro";
    }
}
