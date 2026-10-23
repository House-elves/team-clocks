///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS info.picocli:picocli:4.7.7
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//SOURCES Config.java

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * team-clocks: matches the cities in its settings against this machine's time zone database and saves the list for
 * its tab on Elf Desk, which shows each city's time live and lets you plan a time. Without --save it prints the
 * cities with their time now (the desk's dry run). It reads nothing outside this machine and sends nothing.
 */
@Command(name = "team-clocks", mixinStandardHelpOptions = true, version = "0.1.0", description = "Matches your team's cities to time zones for Elf Desk")
public class TeamClocks implements Callable<Integer> {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Path CLOCKS = Config.DIR.resolve("clocks").resolve("clocks.json");
    /** More rows than this is no longer something you can read at a glance (and more than 200 characters fit). */
    static final int MAX_CITIES = 40;

    @Option(names = "--save", description = "Save the matched cities under ~/.config/team-clocks/clocks for Elf Desk")
    boolean save;

    record Clock(String label, String zone) {
    }

    record Unmatched(String text, String hint) {
    }

    @Override
    public Integer call() throws Exception {
        Config config;
        try {
            config = Config.load();
        } catch (IllegalStateException e) {
            // The end of a failed run's log is what its tab shows: say what to do, not a stack trace.
            System.err.println(e.getMessage());
            return 1;
        }
        System.out.println("Matching your cities against this machine's time zones...");
        List<Clock> cities = new ArrayList<>();
        List<Unmatched> unmatched = new ArrayList<>();
        for (String entry : config.cities.split(",")) {
            entry = entry.strip();
            if (entry.isEmpty()) continue;
            if (cities.size() + unmatched.size() >= MAX_CITIES) {
                System.out.println("Only the first " + MAX_CITIES + " cities are shown");
                break;
            }
            Optional<Clock> c = match(entry);
            if (c.isPresent()) cities.add(c.get());
            else unmatched.add(new Unmatched(clip(entry, 100), hint(entry)));
        }

        boolean fromMachine = config.home.isBlank();
        Clock home = fromMachine ? null : match(config.home.strip()).orElse(null);
        if (!fromMachine && home == null) {
            unmatched.add(0, new Unmatched(clip(config.home.strip(), 100), "Your city: " + hint(config.home.strip())));
            fromMachine = true;
        }
        if (home == null) home = new Clock("Your time", ZoneId.systemDefault().getId());

        System.out.println("Matched " + cities.size() + (cities.size() == 1 ? " city" : " cities")
                + (unmatched.isEmpty() ? "" : ", " + unmatched.size() + " to fix"));

        if (!save) {
            print(config, home, cities, unmatched);
            return 0;
        }
        ObjectNode out = JSON.createObjectNode().put("generatedAt", Instant.now().toString())
                .put("citiesSetting", config.cities).put("homeSetting", config.home);
        out.set("home", JSON.valueToTree(home));
        out.put("homeFromMachine", fromMachine);
        out.set("cities", JSON.valueToTree(cities));
        out.set("unmatched", JSON.valueToTree(unmatched));
        write(CLOCKS, out);
        System.out.println("Saved for the Team clocks tab");
        return 0;
    }

    // ---- matching ----

    /** "Berlin", "New York", "Europe/Berlin", or "Label=Area/City" (the right side may be a city's name too). */
    static Optional<Clock> match(String entry) {
        int eq = entry.indexOf('=');
        if (eq >= 0) {
            String label = entry.substring(0, eq).strip();
            return zone(entry.substring(eq + 1).strip())
                    .map(z -> new Clock(clip(label.isEmpty() ? cityOf(z) : label, 60), z));
        }
        return zone(entry).map(z -> new Clock(clip(entry.contains("/") ? cityOf(z) : entry, 60), z));
    }

    /** A zone id as written (any case), or the zone whose city is this name, from this machine's time zone database. */
    static Optional<String> zone(String text) {
        if (text.isEmpty()) return Optional.empty();
        List<String> ids = ZoneId.getAvailableZoneIds().stream()
                .filter(id -> !id.startsWith("SystemV/") && !id.startsWith("Etc/GMT"))
                .toList();
        for (String id : ids) if (id.equalsIgnoreCase(text)) return Optional.of(id);
        String name = plain(text);
        // The same city can be in the database twice (an old name kept as a link): take the shortest id.
        return ids.stream().filter(id -> id.contains("/") && plain(id.substring(id.lastIndexOf('/') + 1)).equals(name))
                .min(Comparator.comparingInt((String id) -> id.split("/").length).thenComparingInt(String::length).thenComparing(id -> id));
    }

    /** "São Paulo", "sao_paulo" and "Sao Paulo" are the same city to the database. */
    static String plain(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").replaceAll("[_\\-\\s]+", " ")
                .strip().toLowerCase(Locale.ROOT);
    }

    static String cityOf(String zone) {
        return zone.substring(zone.lastIndexOf('/') + 1).replace('_', ' ');
    }

    static String hint(String entry) {
        int eq = entry.indexOf('=');
        if (eq >= 0) {
            return "\"" + clip(entry.substring(eq + 1).strip(), 60) + "\" isn't a time zone. Write it as Area/City, like "
                    + clip(entry.substring(0, eq).strip(), 40) + "=Asia/Kolkata.";
        }
        return "There's no time zone named after it. Write it with the nearest big city's zone, like " + clip(entry, 40)
                + "=Asia/Kolkata or " + clip(entry, 40) + "=America/Chicago.";
    }

    // ---- the dry run ----

    static void print(Config config, Clock home, List<Clock> cities, List<Unmatched> unmatched) {
        Instant now = Instant.now();
        DateTimeFormatter time = DateTimeFormatter.ofPattern(config.hour24 ? "HH:mm" : "h:mm a", Locale.ENGLISH);
        ZonedDateTime base = now.atZone(ZoneId.of(home.zone()));
        System.out.println();
        System.out.println(home.label() + " (" + home.zone() + "): " + base.format(time) + " "
                + base.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH));
        for (Clock c : cities) {
            ZonedDateTime t = now.atZone(ZoneId.of(c.zone()));
            System.out.println("- " + c.label() + " (" + c.zone() + "): " + t.format(time) + " "
                    + t.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + ", " + diff(base, t)
                    + ", " + shade(t, config));
        }
        for (Unmatched u : unmatched) System.out.println("? " + u.text() + ": " + u.hint());
        System.out.println("\nDry run: nothing saved.");
    }

    static String diff(ZonedDateTime base, ZonedDateTime t) {
        int minutes = (t.getOffset().getTotalSeconds() - base.getOffset().getTotalSeconds()) / 60;
        String d = minutes == 0 ? "same time" : (minutes > 0 ? "+" : "−") + Math.abs(minutes) / 60 + " h"
                + (Math.abs(minutes) % 60 == 0 ? "" : " " + Math.abs(minutes) % 60 + " min");
        int days = t.toLocalDate().compareTo(base.toLocalDate());
        return d + (days > 0 ? ", tomorrow" : days < 0 ? ", yesterday" : "");
    }

    /** The same rule as its tab: working hours on weekdays, night from 22 to 7, after hours the rest. */
    static String shade(ZonedDateTime t, Config c) {
        int h = t.getHour();
        boolean weekend = t.getDayOfWeek() == DayOfWeek.SATURDAY || t.getDayOfWeek() == DayOfWeek.SUNDAY;
        boolean work = c.workStart < c.workEnd ? h >= c.workStart && h < c.workEnd : h >= c.workStart || h < c.workEnd;
        if (work && !weekend) return "working hours";
        if (h >= 22 || h < 7) return "night";
        return weekend ? "weekend" : "after hours";
    }

    static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // ---- files ----

    /** Written whole or not at all: the tab may read it at any moment. */
    static void write(Path file, JsonNode n) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".new");
        Files.writeString(tmp, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(n));
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new TeamClocks()).execute(args));
    }
}
