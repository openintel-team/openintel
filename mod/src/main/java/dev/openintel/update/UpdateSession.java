package dev.openintel.update;

/**
 * One update notice per Minecraft launch. The check can finish before or after
 * the first multiplayer join; a later join stays quiet once the notice was claimed.
 */
public final class UpdateSession {
    private boolean joined;
    private boolean checked;
    private boolean shown;
    private String newer = "";

    public synchronized void onJoin() {
        joined = true;
    }

    /** Empty or null means this install is current, or the check could not be read. */
    public synchronized void onChecked(String newerVersion) {
        if (checked) return;
        checked = true;
        newer = newerVersion == null ? "" : newerVersion;
    }

    /** Version to say now, or null. Claiming it means this launch has been told. */
    public synchronized String claim() {
        if (shown || !joined || !checked || newer.isEmpty()) return null;
        shown = true;
        return newer;
    }
}
