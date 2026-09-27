package me.obvgreen.buildffa.placeholders;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.obvgreen.buildffa.BuildFFAPlugin;
import me.obvgreen.buildffa.commands.BuildFFACommand;
import me.obvgreen.buildffa.managers.StatsManager;
import org.bukkit.OfflinePlayer;

public class BuildFFAPlaceholders extends PlaceholderExpansion {

    private final BuildFFAPlugin plugin;
    private final StatsManager statsManager;

    public BuildFFAPlaceholders(BuildFFAPlugin plugin, StatsManager statsManager) {
        this.plugin = plugin;
        this.statsManager = statsManager;
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public String getAuthor() {
        return plugin.getDescription().getAuthors().toString();
    }

    @Override
    public String getIdentifier() {
        return "buildffa";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (params == null) return null;
        String lowerParams = params.toLowerCase();

        // 1. Direct equality checks (these are already fast)
        if (lowerParams.equals("leaderboard_remainingtime")) return statsManager.getRemainingTimeFormatted();
        if (lowerParams.equals("top_player")) return statsManager.getLeaderboardName("highest_killstreak", 1);
        if (lowerParams.equals("top_streak")) return String.valueOf(statsManager.getLeaderboardValue("highest_killstreak", 1));
        if (lowerParams.equals("current_highest_killstreak")) return String.valueOf(statsManager.getHighestCurrentKillstreak());
        if (lowerParams.equals("current_highest_killstreak_player")) return statsManager.getHighestCurrentKillstreakPlayer();
        if (lowerParams.equals("playing")) return String.valueOf(BuildFFACommand.playersInFFA.size());

        // 2. Optimized parsing for dynamic placeholders
        // Format: [stat]_[type]_[index]
        // Example: kills_name_1
        int lastUnderscore = lowerParams.lastIndexOf('_');
        if (lastUnderscore != -1) {
            String suffix = lowerParams.substring(lastUnderscore + 1); // "1"
            String prefix = lowerParams.substring(0, lastUnderscore);   // "kills_name"

            int secondLastUnderscore = prefix.lastIndexOf('_');
            if (secondLastUnderscore != -1) {
                String stat = prefix.substring(0, secondLastUnderscore); // "kills"
                String type = prefix.substring(secondLastUnderscore + 1); // "name" or "value"

                // Validate that index is a number
                try {
                    int position = Integer.parseInt(suffix);
                    // Validate that stat is allowed
                    if (isStatValid(stat)) {
                        if (type.equals("name")) return statsManager.getLeaderboardName(stat, position);
                        if (type.equals("value")) return String.valueOf(statsManager.getLeaderboardValue(stat, position));
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        // 3. Player Stats (only if player is valid)
        if (player == null) return null;

        if (lowerParams.equals("kills")) return String.valueOf(statsManager.getKills(player.getUniqueId()));
        if (lowerParams.equals("deaths")) return String.valueOf(statsManager.getDeaths(player.getUniqueId()));
        if (lowerParams.equals("killstreak")) return String.valueOf(statsManager.getKillstreak(player.getUniqueId()));
        if (lowerParams.equals("daily_kills")) return String.valueOf(statsManager.getDailyKills(player.getUniqueId()));

        if (lowerParams.startsWith("pos_")) {
            String stat = lowerParams.substring(4);
            int pos = statsManager.getPlayerPosition(player.getUniqueId(), stat);
            return pos > 0 ? String.valueOf(pos) : "None";
        }

        return null;
    }

    // Helper method to validate stats without regex
    private boolean isStatValid(String stat) {
        return stat.equals("kills") ||
                stat.equals("deaths") ||
                stat.equals("highest_killstreak") ||
                stat.equals("daily_kills") ||
                stat.equals("daily_highest_killstreak");
    }
}
