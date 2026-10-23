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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;

/**
 * team-clocks: matches the cities in its settings against this machine's time zone database and saves the list for
 * its tab on Elf Desk, which shows each city's time live and lets you plan a time. Without --save it prints the
 * cities with their time now (the desk's dry run). With --action=add-city it adds a city picked in its tab to Team
 * cities in its settings, and with --action=add-person a team mate to Team mates. It reads nothing outside this
 * machine and sends nothing.
 */
@Command(name = "team-clocks", mixinStandardHelpOptions = true, version = "0.3.0", description = "Matches your team's cities to time zones for Elf Desk")
public class TeamClocks implements Callable<Integer> {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Path CLOCKS = Config.DIR.resolve("clocks").resolve("clocks.json");
    /** The countries and their cities, for the tab's picker. */
    static final Path PLACES = Config.DIR.resolve("clocks").resolve("places.json");
    /** Where the time zone database keeps its list of each country's zones, on Linux and macOS. */
    static final Path ZONE_TAB = Path.of("/usr/share/zoneinfo/zone.tab");
    /** More rows than this is no longer something you can read at a glance (and more than 200 characters fit). */
    static final int MAX_CITIES = 40;

    @Option(names = "--save", description = "Save the matched cities under ~/.config/team-clocks/clocks for Elf Desk")
    boolean save;

    @Option(names = "--action", description = "add-city: add the city on stdin ({\"zone\": …, \"label\": …}) to Team cities;"
            + " add-person: add the team mate on stdin ({\"name\": …, \"place\": …}) to Team mates")
    String action;

    /** A row: a city, and the team mates who are there. */
    record Clock(String label, String zone, List<String> people) {
        Clock(String label, String zone) {
            this(label, zone, new ArrayList<>());
        }
    }

    record Unmatched(String text, String hint) {
    }

    @Override
    public Integer call() throws Exception {
        if (action != null) return act();
        Config config;
        try {
            config = Config.load();
        } catch (IllegalStateException e) {
            // The end of a failed run's log is what its tab shows: say what to do, not a stack trace.
            System.err.println(e.getMessage());
            return 1;
        }
        System.out.println("Matching your cities against this machine's time zones...");
        Matched m = matchAll(config);
        System.out.println("Matched " + m.cities().size() + (m.cities().size() == 1 ? " city" : " cities")
                + (m.unmatched().isEmpty() ? "" : ", " + m.unmatched().size() + " to fix"));

        List<Country> places = places();
        System.out.println("Found " + places.size() + " countries to pick cities from");
        if (!save) {
            print(config, m);
            return 0;
        }
        saveClocks(config, m);
        write(PLACES, JSON.valueToTree(places));
        System.out.println("Saved for the Team clocks tab");
        return 0;
    }

    record Matched(Clock home, boolean homeFromMachine, List<Clock> cities, List<Unmatched> unmatched) {
    }

    static Matched matchAll(Config config) {
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
        matchPeople(config, home, cities, unmatched);
        return new Matched(home, fromMachine, cities, unmatched);
    }

    /**
     * Team mates, "Anna=Berlin, Raj=Bangalore": each goes under the row whose name is their place, else the first row
     * (or your own time) in its time zone, else a new row for that city.
     */
    static void matchPeople(Config config, Clock home, List<Clock> cities, List<Unmatched> unmatched) {
        for (String entry : config.people.split(",")) {
            entry = entry.strip();
            if (entry.isEmpty()) continue;
            int eq = entry.indexOf('=');
            String name = eq < 0 ? "" : clip(entry.substring(0, eq).strip(), 40);
            String place = eq < 0 ? "" : entry.substring(eq + 1).strip();
            if (name.isEmpty() || place.isEmpty()) {
                unmatched.add(new Unmatched(clip(entry, 100), "Write a team mate as Name=City, like Anna=Berlin."));
                continue;
            }
            Clock row = rowFor(place, home, cities).orElse(null);
            if (row == null) {
                Optional<Clock> c = match(place);
                if (c.isEmpty()) {
                    unmatched.add(new Unmatched(clip(entry, 100), "\"" + clip(place, 60) + "\" isn't one of your cities or a time"
                            + " zone. Add the city first, or write it as " + name + "=Area/City, like " + name + "=Asia/Kolkata."));
                    continue;
                }
                if (cities.size() + unmatched.size() >= MAX_CITIES) {
                    System.out.println("Only the first " + MAX_CITIES + " cities are shown");
                    break;
                }
                row = c.get();
                cities.add(row);
            }
            row.people().add(name);
        }
    }

    /** The row a team mate's place names: one of the cities by its name, your own city, or a row in its time zone. */
    static Optional<Clock> rowFor(String place, Clock home, List<Clock> cities) {
        String p = plain(place);
        for (Clock c : cities) if (plain(c.label()).equals(p)) return Optional.of(c);
        if (plain(home.label()).equals(p)) return Optional.of(home);
        Optional<String> z = place.contains("=") ? Optional.empty() : zone(place);
        if (z.isEmpty()) return Optional.empty();
        for (Clock c : cities) if (c.zone().equals(z.get())) return Optional.of(c);
        return home.zone().equals(z.get()) ? Optional.of(home) : Optional.empty();
    }

    static void saveClocks(Config config, Matched m) throws IOException {
        ObjectNode out = JSON.createObjectNode().put("generatedAt", Instant.now().toString())
                .put("citiesSetting", config.cities).put("homeSetting", config.home).put("peopleSetting", config.people);
        out.set("home", JSON.valueToTree(m.home()));
        out.put("homeFromMachine", m.homeFromMachine());
        out.set("cities", JSON.valueToTree(m.cities()));
        out.set("unmatched", JSON.valueToTree(m.unmatched()));
        write(CLOCKS, out);
    }

    // ---- adding a city from its tab ----

    /** The tab's Add: {"zone": "Europe/Berlin", "label": "Anna"} on stdin. Adds it to Team cities and matches again. */
    Integer act() throws IOException {
        if ("add-person".equals(action)) return addPerson();
        if (!"add-city".equals(action)) {
            System.err.println("There's no action " + action);
            return 1;
        }
        Config config;
        try {
            config = Config.load();
        } catch (IllegalStateException e) {
            return answer("error", e.getMessage());
        }
        JsonNode in = JSON.readTree(System.in);
        String zone = in.path("zone").asText("").strip();
        String label = in.path("label").asText("").strip();
        // The desk checked both against elf.yaml; check again here, since they end up in the config file.
        if (!ZoneId.getAvailableZoneIds().contains(zone)) return answer("error", "\"" + clip(zone, 60) + "\" isn't a time zone on this machine.");
        if (!label.matches("[^,=\\p{Cntrl}]{0,60}")) return answer("error", "A name can't have a comma or = in it.");

        // A city's own name reads best in Settings ("Berlin"); anything else needs its zone ("Anna=Europe/Berlin").
        String city = cityOf(zone);
        boolean plainCity = (label.isEmpty() || plain(label).equals(plain(city))) && zone(city).orElse("").equals(zone);
        String entry = plainCity ? (label.isEmpty() ? city : label) : (label.isEmpty() ? city : label) + "=" + zone;

        Matched before = matchAll(config);
        String shown = label.isEmpty() ? city : label;
        if (before.cities().stream().anyMatch(c -> c.zone().equals(zone) && plain(c.label()).equals(plain(shown)))) {
            return answer("error", shown + " is already in your cities.");
        }
        if (before.cities().size() + before.unmatched().size() >= MAX_CITIES) {
            return answer("error", "You have " + MAX_CITIES + " cities already: remove one in Settings first.");
        }
        String cities = config.cities.isBlank() ? entry : config.cities.strip() + ", " + entry;
        if (cities.length() > 200) return answer("error", "Team cities is full (200 characters): shorten it in Settings first.");

        Config.set("CITIES", cities);
        config.cities = cities;
        saveClocks(config, matchAll(config));
        System.out.println("Added " + entry + " to your cities");
        return answer("added", entry);
    }

    /** The tab's Add a team mate: {"name": "Anna", "place": "Berlin"} on stdin, the place being one of its rows. */
    Integer addPerson() throws IOException {
        Config config;
        try {
            config = Config.load();
        } catch (IllegalStateException e) {
            return answer("error", e.getMessage());
        }
        JsonNode in = JSON.readTree(System.in);
        String name = in.path("name").asText("").strip();
        String place = in.path("place").asText("").strip();
        // The desk checked both against elf.yaml; check again here, since they end up in the config file.
        if (!name.matches("[^,=\\p{Cntrl}]{1,40}")) return answer("error", "Give your team mate a name, with no comma or = in it.");
        if (!place.matches("[^,=\\p{Cntrl}]{1,60}")) return answer("error", "Pick where your team mate is.");

        Matched before = matchAll(config);
        if (rowFor(place, before.home(), before.cities()).isEmpty()) {
            return answer("error", "\"" + clip(place, 60) + "\" isn't one of your cities: add it first.");
        }
        for (String entry : config.people.split(",")) {
            int eq = entry.indexOf('=');
            if (eq > 0 && plain(entry.substring(0, eq)).equals(plain(name))) {
                return answer("error", name + " is already in your team mates: change where they are in Settings.");
            }
        }
        String entry = name + "=" + place;
        String people = config.people.isBlank() ? entry : config.people.strip() + ", " + entry;
        if (people.length() > 200) return answer("error", "Team mates is full (200 characters): shorten it in Settings first.");

        Config.set("PEOPLE", people);
        config.people = people;
        saveClocks(config, matchAll(config));
        System.out.println("Added " + entry + " to your team mates");
        return answer("added", name + " in " + place);
    }

    static Integer answer(String key, String text) throws IOException {
        // The last line printed is the tab's answer.
        System.out.println(JSON.writeValueAsString(JSON.createObjectNode().put(key, text)));
        return 0;
    }

    // ---- the places to pick from ----

    record Place(String zone, String city, String note) {
    }

    record Country(String code, String name, List<Place> places) {
    }

    /**
     * Each country with its time zones, from the zone.tab that comes with the time zone database on this machine.
     * Without one (as on Windows) the zones are grouped by their area instead: Europe, America, Asia.
     */
    static List<Country> places() {
        Set<String> known = ZoneId.getAvailableZoneIds();
        Map<String, List<Place>> byCode = new TreeMap<>();
        if (Files.isReadable(ZONE_TAB)) {
            try {
                for (String line : Files.readAllLines(ZONE_TAB)) {
                    if (line.startsWith("#")) continue;
                    String[] f = line.split("\t");
                    if (f.length < 3 || !f[0].matches("[A-Z]{2}") || !known.contains(f[2])) continue;
                    byCode.computeIfAbsent(f[0], k -> new ArrayList<>())
                            .add(new Place(f[2], cityOf(f[2]), f.length > 3 ? clip(f[3], 80) : ""));
                }
            } catch (IOException e) {
                byCode.clear();
            }
        }
        List<Country> out = new ArrayList<>();
        if (!byCode.isEmpty()) {
            byCode.forEach((code, list) -> {
                list.sort(Comparator.comparing(Place::city));
                out.add(new Country(code, Locale.of("", code).getDisplayCountry(Locale.ENGLISH), list));
            });
        } else {
            Map<String, List<Place>> byArea = new TreeMap<>();
            for (String id : known) {
                int slash = id.indexOf('/');
                if (slash < 0 || id.startsWith("SystemV/") || id.startsWith("Etc/") || id.startsWith("US/")) continue;
                byArea.computeIfAbsent(id.substring(0, slash), k -> new ArrayList<>()).add(new Place(id, cityOf(id), ""));
            }
            byArea.forEach((area, list) -> {
                list.sort(Comparator.comparing(Place::city));
                out.add(new Country("", area, list));
            });
        }
        out.sort(Comparator.comparing(Country::name));
        return out;
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

    static void print(Config config, Matched m) {
        Clock home = m.home();
        Instant now = Instant.now();
        DateTimeFormatter time = DateTimeFormatter.ofPattern(config.hour24 ? "HH:mm" : "h:mm a", Locale.ENGLISH);
        ZonedDateTime base = now.atZone(ZoneId.of(home.zone()));
        System.out.println();
        System.out.println(home.label() + " (" + home.zone() + "): " + base.format(time) + " "
                + base.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + who(home));
        for (Clock c : m.cities()) {
            ZonedDateTime t = now.atZone(ZoneId.of(c.zone()));
            System.out.println("- " + c.label() + " (" + c.zone() + "): " + t.format(time) + " "
                    + t.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + ", " + diff(base, t)
                    + ", " + shade(t, config) + who(c));
        }
        for (Unmatched u : m.unmatched()) System.out.println("? " + u.text() + ": " + u.hint());
        System.out.println("\nDry run: nothing saved.");
    }

    static String who(Clock c) {
        return c.people().isEmpty() ? "" : ": " + String.join(", ", c.people());
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
