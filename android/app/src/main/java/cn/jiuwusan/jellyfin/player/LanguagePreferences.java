package cn.jiuwusan.jellyfin.player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Language labels for account preferences. Track selection belongs to Jellyfin Server. */
final class LanguagePreferences {
    static final String DEFAULT = "zho";
    static final String[] CODES;
    static final String[] LABELS;
    static {
        Map<String, String> languages = new LinkedHashMap<>();
        languages.put("zho", "Chinese");
        languages.put("eng", "English");
        languages.put("jpn", "Japanese");
        languages.put("kor", "Korean");
        List<Locale> others = new ArrayList<>();
        for (String language : Locale.getISOLanguages()) others.add(new Locale(language));
        Collections.sort(others, (left, right) -> left.getDisplayLanguage(Locale.ENGLISH).compareTo(right.getDisplayLanguage(Locale.ENGLISH)));
        for (Locale locale : others) {
            try {
                String code = locale.getISO3Language();
                if (!languages.containsKey(code)) languages.put(code, locale.getDisplayLanguage(Locale.ENGLISH));
            }
            catch (java.util.MissingResourceException ignored) { /* Not a selectable ISO language. */ }
        }
        languages.put("", "不指定（沿用服务端）");
        CODES = languages.keySet().toArray(new String[0]);
        LABELS = languages.values().toArray(new String[0]);
    }
    static String normalize(String value) {
        String code = value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('_', '-').split("-", 2)[0];
        switch (code) {
            case "chi": return "zho";
            case "fre": return "fra";
            case "ger": return "deu";
            case "dut": return "nld";
            case "cze": return "ces";
            case "rum": return "ron";
            case "gre": return "ell";
            case "per": return "fas";
            case "may": return "msa";
            case "slo": return "slk";
            case "wel": return "cym";
            case "alb": return "sqi";
            case "arm": return "hye";
            case "baq": return "eus";
            case "bur": return "mya";
            case "geo": return "kat";
            case "ice": return "isl";
            case "mac": return "mkd";
            case "tib": return "bod";
            case "und": return "";
            default:
                if (code.length() == 2) try { return new Locale(code).getISO3Language(); }
                catch (java.util.MissingResourceException ignored) { return code; }
                return code;
        }
    }
    static int optionIndex(String value) {
        String code = normalize(value == null ? DEFAULT : value);
        for (int i = 0; i < CODES.length; i++) if (CODES[i].equals(code)) return i;
        return 0;
    }
    private LanguagePreferences() {}
}
