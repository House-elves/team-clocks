<img src="https://raw.githubusercontent.com/House-elves/elf-desk/main/docs/brand/mark.svg" width="72" alt="House Elves logo: a checkmark that ends in an elf's hat">

# team-clocks

A [House Elves](https://github.com/House-elves) elf for [Elf Desk](https://github.com/House-elves/elf-desk):
**Team clocks** shows the time and day of the week in each of your team mates' cities next to your own, with who is
where, so you can pick a time that works for everyone.

You list your team's cities in its settings: a city's own name, like `Berlin` or `New York`, or a label with a time
zone, like `Bangalore=Asia/Kolkata` or `Priya=Asia/Kolkata`, for a place the time zone list doesn't name or to see a
team mate's name. Or add them from its tab: under **Add a city**, pick a country, then one of its cities, give it
a name if you like, and click **+**. The city goes on the end of your cities in Settings. A country only lists the
cities with a time zone of their own, so for any other town pick the nearest one and give it the town's name.

To see who is where, list your team mates under **Team mates** in its settings, like
`Anna=Berlin, Ben=Berlin, Raj=Bangalore`, or add them from its tab: under **Add a team mate**, give a name, pick one
of your cities (or your own time) and click **Add team mate**. Their names show under their city's clock, several to
a city if they share one, and the planner names them too ("Berlin (Anna, Ben) is in working hours"). A team mate's
place can also be any time zone, like `Kim=Asia/Seoul`: if it isn't one of your cities yet, it gets a row of its
own. Its
tab shows your own time at the top, then one row per city with its country's flag, its local time, its day, and
how far ahead or behind you it is ("+6 h, tomorrow"). The clocks tick live, and each row is shaded for working
hours, after hours (and weekends) or night there. To plan a time, drag the slider or pick a day and time in your
time: every row shows that moment in its city, and the tab says which cities are in working hours. **Now** goes back
to the live clocks. Its dashboard card is one line: "Berlin 14:05 Thu · New York 08:05 Thu · Bangalore 18:35 Thu".

It runs once after you add it, to match your cities against the time zone database on your machine, and again
whenever its tab sees you changed the cities. Anything it can't match is shown with a hint on how to write it.
Daylight saving is handled by the time zone database.

- **Reads:** its own settings (the cities you list, your team mates and where they are, and your own city), and the time zone database already on this
  machine, with its list of each country's time zones (`/usr/share/zoneinfo/zone.tab`; without it, the picker
  groups the cities by area, like Europe or America, and the rows have no flags). The flags come from the
  [flag-icons](https://github.com/lipis/flag-icons) package (`org.mvnpm:flag-icons`), which jbang fetches from
  Maven Central with its other libraries; nothing is fetched from its tab.
- **Writes:** nothing outside this machine. The matched cities, the flags of their countries and the countries to pick from are saved in
  `~/.config/team-clocks/clocks/`. Adding a city from its tab changes **Team cities** in its own settings
  (only that line of `~/.config/team-clocks/config`), and adding a team mate changes **Team mates** (only its
  `PEOPLE` line). It never sends anything anywhere.

## Adding it

On Elf Desk, click **+**, find **Team clocks**, and add it. Then open its Settings and list your team's cities.
Leave **Your city** empty to use this machine's time zone.

## Running it by hand

    mkdir -p ~/.config/team-clocks && echo 'CITIES=London, Berlin, New York, Bangalore=Asia/Kolkata' > ~/.config/team-clocks/config
    jbang TeamClocks.java            # prints each city's time now, saves nothing
    jbang TeamClocks.java --save     # saved for its tab on Elf Desk
    echo 'PEOPLE=Anna=Berlin, Ben=Berlin, Raj=Bangalore' >> ~/.config/team-clocks/config
    echo '{"zone":"America/Chicago","label":"Anna"}' | jbang TeamClocks.java --action=add-city   # what + does
    echo '{"name":"Kim","place":"New York"}' | jbang TeamClocks.java --action=add-person          # what Add team mate does

Check it with `jbang https://github.com/House-elves/elf-runner/blob/main/ElfRunner.java --check-elf .`.
[AGENTS.md](AGENTS.md) says how an elf is built.
