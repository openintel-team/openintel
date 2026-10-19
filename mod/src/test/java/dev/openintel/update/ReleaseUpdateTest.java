package dev.openintel.update;

/**
 * A newer GitHub release for this Minecraft version is announced once per launch.
 * The same version, another game version, and a prerelease stay quiet.
 */
public final class ReleaseUpdateTest {
    public static void main(String[] args) {
        sameReleaseStaysQuiet();
        newerReleaseForThisGame();
        otherGameVersionStaysQuiet();
        prereleaseAndDraftStayQuiet();
        highestReleaseWins();
        unreadableResponseStaysQuiet();
        noticeIsOncePerLaunch();
        if (!ReleaseUpdate.RELEASES_PAGE.equals("https://github.com/takoyakifan/openintel/releases")) {
            throw new AssertionError("Update notice links to the releases page");
        }
        System.out.println("ReleaseUpdateTest passed");
    }

    private static void sameReleaseStaysQuiet() {
        String json = releases("""
                {"tag_name":"v3.1.0","draft":false,"prerelease":false,"assets":[
                  {"name":"openintel-takoyakifan-fabric-3.1.0-mc26.3.jar"}]}
                """);
        check(ReleaseUpdate.newerVersion("3.1.0+takoyakifan.mc26.3", "26.3", json) == null,
                "The installed release is not announced again");
    }

    private static void newerReleaseForThisGame() {
        String json = releases("""
                {"tag_name":"v3.2.0","draft":false,"prerelease":false,"assets":[
                  {"name":"openintel-takoyakifan-fabric-3.2.0-mc26.3.jar"},
                  {"name":"openintel-takoyakifan-fabric-3.2.0-mc1.21.11.jar"}]}
                """);
        check("3.2.0".equals(ReleaseUpdate.newerVersion("3.1.0+takoyakifan.mc26.3", "26.3", json)),
                "A newer jar for this Minecraft version is announced");
        check("3.2.0".equals(ReleaseUpdate.newerVersion("3.1.0+takoyakifan.mc1.21.11", "1.21.11", json)),
                "The same release is announced on 1.21.11");
    }

    private static void otherGameVersionStaysQuiet() {
        String json = releases("""
                {"tag_name":"v3.2.0","draft":false,"prerelease":false,"assets":[
                  {"name":"openintel-takoyakifan-fabric-3.2.0-mc26.2.jar"}]}
                """);
        check(ReleaseUpdate.newerVersion("3.1.0+takoyakifan.mc26.3", "26.3", json) == null,
                "A jar for another Minecraft version is not an update for this one");
    }

    private static void prereleaseAndDraftStayQuiet() {
        String json = releases("""
                {"tag_name":"v3.4.0","draft":true,"prerelease":false,"assets":[
                  {"name":"openintel-takoyakifan-fabric-3.4.0-mc26.3.jar"}]},
                {"tag_name":"v3.3.0","draft":false,"prerelease":true,"assets":[
                  {"name":"openintel-takoyakifan-fabric-3.3.0-mc26.3.jar"}]}
                """);
        check(ReleaseUpdate.newerVersion("3.1.0+takoyakifan.mc26.3", "26.3", json) == null,
                "Drafts and prereleases are not announced");
    }

    private static void highestReleaseWins() {
        String json = releases("""
                {"tag_name":"v3.2.0","draft":false,"prerelease":false,"assets":[
                  {"name":"openintel-takoyakifan-fabric-3.2.0-mc26.3.jar"}]},
                {"tag_name":"v3.0.9","draft":false,"prerelease":false,"assets":[
                  {"name":"openintel-takoyakifan-fabric-3.0.9-mc26.3.jar"}]},
                {"tag_name":"v3.2.1","draft":false,"prerelease":false,"assets":[
                  {"name":"openintel-takoyakifan-fabric-3.2.1-mc26.3.jar"}]}
                """);
        check("3.2.1".equals(ReleaseUpdate.newerVersion("3.1.0+takoyakifan.mc26.3", "26.3", json)),
                "The newest applicable release is the one announced");
        check(ReleaseUpdate.newerVersion("3.3.0+takoyakifan.mc26.3", "26.3", json) == null,
                "A newer local build is not told to downgrade");
    }

    private static void unreadableResponseStaysQuiet() {
        check(ReleaseUpdate.newerVersion("3.1.0+takoyakifan.mc26.3", "26.3", "{\"message\":\"limit\"}") == null,
                "An error document is not an update");
        check(ReleaseUpdate.newerVersion("3.1.0+takoyakifan.mc26.3", "26.3", "not json") == null,
                "A broken response is not an update");
        check(ReleaseUpdate.newerVersion("unknown", "26.3", "[]") == null,
                "An unreadable local version is not compared");
    }

    private static void noticeIsOncePerLaunch() {
        UpdateSession afterJoin = new UpdateSession();
        afterJoin.onJoin();
        check(afterJoin.claim() == null, "Joining before the check finishes stays quiet");
        afterJoin.onChecked("3.2.0");
        check("3.2.0".equals(afterJoin.claim()), "The first join still hears the result");
        afterJoin.onJoin();
        check(afterJoin.claim() == null, "A later join in the same launch stays quiet");

        UpdateSession beforeJoin = new UpdateSession();
        beforeJoin.onChecked("3.2.0");
        check(beforeJoin.claim() == null, "The result waits until a server join");
        beforeJoin.onJoin();
        check("3.2.0".equals(beforeJoin.claim()), "The first join reports a result that arrived earlier");
        beforeJoin.onJoin();
        check(beforeJoin.claim() == null, "Joining again does not repeat it");

        UpdateSession current = new UpdateSession();
        current.onChecked(null);
        current.onJoin();
        check(current.claim() == null, "A current install says nothing on join");
        current.onJoin();
        check(current.claim() == null, "Later joins stay quiet when there is no update");

        UpdateSession failed = new UpdateSession();
        failed.onJoin();
        failed.onChecked(null);
        check(failed.claim() == null, "A failed check does not invent an update");
        failed.onChecked("9.9.9");
        check(failed.claim() == null, "A second check result is ignored");
    }

    private static String releases(String objects) {
        return "[" + objects + "]";
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
