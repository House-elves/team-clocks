<img src="https://raw.githubusercontent.com/House-elves/elf-desk/main/docs/brand/mark.svg" width="72" alt="House Elves logo: a checkmark that ends in an elf's hat">

# team-clocks

A [House Elves](https://github.com/House-elves) elf for [Elf Desk](https://github.com/House-elves/elf-desk):
**Team clocks** shows the time and day of the week in each of your team mates' cities next to your own, so you can
pick a time that works for everyone.

You list your team's cities in its settings: a city's own name, like `Berlin` or `New York`, or a label with a time
zone, like `Bangalore=Asia/Kolkata` or `Priya=Asia/Kolkata`, for a place the time zone list doesn't name or to see a
team mate's name. Its tab shows your own time at the top, then one row per city with its local time, its day, and
how far ahead or behind you it is ("+6 h, tomorrow"). The clocks tick live, and each row is shaded for working
hours, after hours (and weekends) or night there. To plan a time, drag the slider or pick a day and time in your
time: every row shows that moment in its city, and the tab says which cities are in working hours. **Now** goes back
to the live clocks. Its dashboard card is one line: "Berlin 14:05 Thu · New York 08:05 Thu · Bangalore 18:35 Thu".

It runs once after you add it, to match your cities against the time zone database on your machine, and again
whenever its tab sees you changed the cities. Anything it can't match is shown with a hint on how to write it.
Daylight saving is handled by the time zone database.

- **Reads:** its own settings (the cities you list and your own city), and the time zone database already on this
  machine.
- **Writes:** nothing outside this machine. The matched cities are saved in `~/.config/team-clocks/clocks/`.
  It never sends anything anywhere.

## Adding it

On Elf Desk, click **+**, find **Team clocks**, and add it. Then open its Settings and list your team's cities.
Leave **Your city** empty to use this machine's time zone.

## Running it by hand

    mkdir -p ~/.config/team-clocks && echo 'CITIES=London, Berlin, New York, Bangalore=Asia/Kolkata' > ~/.config/team-clocks/config
    jbang TeamClocks.java            # prints each city's time now, saves nothing
    jbang TeamClocks.java --save     # saved for its tab on Elf Desk

Check it with `jbang https://github.com/House-elves/elf-runner/blob/main/ElfRunner.java --check-elf .`.
[AGENTS.md](AGENTS.md) says how an elf is built.
