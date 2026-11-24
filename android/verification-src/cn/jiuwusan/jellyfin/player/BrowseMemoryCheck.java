package cn.jiuwusan.jellyfin.player;

/** Host-side checks: no Android, server or credentials required. */
public final class BrowseMemoryCheck {
    private static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }
    public static void main(String[] args) {
        BrowseMemory memory = new BrowseMemory(2);
        memory.put("home", "cards", 100);
        check("cards".equals(memory.get("home")), "Return must reuse loaded data");
        check(!memory.stale("home", 150, 100), "Fresh return must not fetch again");
        check(memory.stale("home", 201, 100), "Expired data should refresh silently");
        memory.position("home", 430);
        check(memory.position("home") == 430, "Return lost browsing position");
        memory.put("detail", "movie", 120);
        memory.get("home");
        memory.put("episodes", "season", 130);
        check(memory.get("detail") == null, "Least recently used entry not evicted");
        check(memory.stale("detail", 150, 100), "Missing data must load");
        memory.clear();
        check(memory.get("home") == null && memory.position("home") == 0,
                "Account change must clear data and positions");
        System.out.println("ALL_PASS: reuse / expiry / scroll / bounded LRU / account clear");
    }
}
