// team-clocks' tab on Elf Desk: your time and each team mate's city, with who is there, ticking live, and a planner that shows one moment
// of your day in every city. The elf only matches the cities to time zones; the clocks are worked out here.
// It runs in the desk's sandboxed frame: everything goes through `elf`, and only what elf.yaml declares.
import { LitElement, html, css, shared, cardStyles, spinner, ago, settingsButton, elf } from '/elf-kit/1/kit.js';

const KEYS = ['CITIES', 'PEOPLE', 'HOME', 'WORK_START', 'WORK_END', 'HOUR_24'];
const DAY = 86_400_000;

/** The parts of a moment in a time zone, as numbers: {y, mo, d, h, mi, wd} (wd: 0 is Sunday). */
const partsFormats = new Map();
function partsOf(ms, zone) {
    let f = partsFormats.get(zone);
    if (!f) {
        f = new Intl.DateTimeFormat('en-US', { timeZone: zone, hourCycle: 'h23', year: 'numeric', month: 'numeric',
            day: 'numeric', hour: 'numeric', minute: 'numeric', weekday: 'short' });
        partsFormats.set(zone, f);
    }
    const p = Object.fromEntries(f.formatToParts(ms).map(x => [x.type, x.value]));
    return { y: +p.year, mo: +p.month, d: +p.day, h: +p.hour % 24, mi: +p.minute,
        wd: ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'].indexOf(p.weekday) };
}

/** How far ahead of UTC a zone is at that moment, in minutes. */
function offsetOf(ms, zone) {
    const p = partsOf(ms, zone);
    return Math.round((Date.UTC(p.y, p.mo - 1, p.d, p.h, p.mi) - Math.floor(ms / 60_000) * 60_000) / 60_000);
}

/** The moment it's this wall time in a zone (twice over, for the hour daylight saving moves). */
function momentOf(y, mo, d, h, mi, zone) {
    const wall = Date.UTC(y, mo - 1, d, h, mi);
    const first = wall - offsetOf(wall, zone) * 60_000;
    return wall - offsetOf(first, zone) * 60_000;
}

function timeText(ms, zone, hour24) {
    return new Intl.DateTimeFormat(hour24 ? 'en-GB' : 'en-US', { timeZone: zone, hour: hour24 ? '2-digit' : 'numeric',
        minute: '2-digit', hour12: !hour24 }).format(ms);
}

function dayText(ms, zone) {
    return new Intl.DateTimeFormat('en-US', { timeZone: zone, weekday: 'short' }).format(ms);
}

function dateText(ms, zone) {
    return new Intl.DateTimeFormat('en-GB', { timeZone: zone, weekday: 'short', day: 'numeric', month: 'short' }).format(ms);
}

/** "+6 h, tomorrow", "−5 h 30 min", "Same time". */
function diffText(ms, zone, home) {
    const minutes = offsetOf(ms, zone) - offsetOf(ms, home);
    const abs = Math.abs(minutes);
    const d = minutes === 0 ? 'Same time' : `${minutes > 0 ? '+' : '−'}${Math.floor(abs / 60)} h${abs % 60 ? ` ${abs % 60} min` : ''}`;
    const a = partsOf(ms, zone), b = partsOf(ms, home);
    const days = Math.round((Date.UTC(a.y, a.mo - 1, a.d) - Date.UTC(b.y, b.mo - 1, b.d)) / DAY);
    return d + (days > 0 ? ', tomorrow' : days < 0 ? ', yesterday' : '');
}

/** The same rule as the dry run: working hours on weekdays, night from 22 to 7, after hours the rest. */
function shadeOf(ms, zone, start, end) {
    const p = partsOf(ms, zone);
    const weekend = p.wd === 0 || p.wd === 6;
    const work = start < end ? p.h >= start && p.h < end : p.h >= start || p.h < end;
    if (work && !weekend) return { key: 'work', label: 'Working hours' };
    if (p.h >= 22 || p.h < 7) return { key: 'night', label: 'Night' };
    return { key: 'after', label: weekend ? 'Weekend' : 'After hours' };
}

/** Its settings, with the hours as numbers. */
async function settings() {
    const v = await elf.values();
    const hour = (s, d, min, max) => { const n = parseInt(s, 10); return n >= min && n <= max ? n : d; };
    return { cities: v.CITIES ?? '', people: v.PEOPLE ?? '', home: v.HOME ?? '', start: hour(v.WORK_START, 9, 0, 23),
        end: hour(v.WORK_END, 17, 1, 24), hour24: v.HOUR_24 !== 'false' };
}

/** "Berlin (Anna, Ben)", or just "Berlin" when nobody's listed there. */
function withPeople(r) {
    return r.people?.length ? `${r.label} (${r.people.join(', ')})` : r.label;
}

/** Clocks only show minutes: redraw when the minute turns, not every second. */
class MinuteTick {
    constructor(host) { this.host = host; host.addController(this); }
    hostConnected() {
        const next = () => {
            this.timer = setTimeout(() => { this.host.requestUpdate(); next(); }, 60_000 - Date.now() % 60_000 + 50);
        };
        next();
    }
    hostDisconnected() { clearTimeout(this.timer); }
}

class TeamClocksTab extends LitElement {

    static properties = {
        _clocks: { state: true },
        _settings: { state: true },
        _state: { state: true },
        _plan: { state: true },     // null for now, or {day: 0..6 days from today, minutes: 0..1439}, in your time
        _loading: { state: true },
        _error: { state: true },
        _places: { state: true },   // the countries and their cities, for Add a city
        _pick: { state: true },     // {country, zone, label} as picked so far
        _adding: { state: true },
        _added: { state: true },    // what the last Add said: {added} or {error}
        _mate: { state: true },     // {name, place} for Add a team mate
        _mateAdding: { state: true },
        _mateAdded: { state: true },
    };

    static styles = [shared, css`
        .page { display: grid; gap: 26px; }
        h1 { font-size: 34px; line-height: 1.15; font-weight: 600; letter-spacing: -0.022em; }
        .sub { margin: 6px 0 0; color: var(--muted); font-size: 14px; }
        .home { padding: 20px 22px; display: flex; flex-wrap: wrap; align-items: baseline; gap: 6px 18px; }
        .home .time { font-size: 44px; font-weight: 600; letter-spacing: -0.02em; font-variant-numeric: tabular-nums; }
        .home .day { font-size: 18px; }
        .home .where { color: var(--muted); font-size: 14px; flex-basis: 100%; }
        .plan { padding: 16px 22px; display: grid; gap: 12px; }
        .plan .controls { display: flex; flex-wrap: wrap; align-items: center; gap: 10px 14px; }
        .plan input[type=range] { width: 100%; }
        .plan .said { margin: 0; font-size: 14px; color: var(--muted); }
        .plan .said strong { color: var(--ink); }
        ul { list-style: none; margin: 0; padding: 0; display: grid; gap: 8px; }
        li { padding: 12px 18px; display: grid; grid-template-columns: minmax(0, 1fr) auto auto; gap: 2px 18px; align-items: center; }
        li .who { font-weight: 600; }
        li .zone { grid-column: 1; font-size: 13px; color: var(--muted); }
        li .people { grid-column: 1; font-size: 13.5px; }
        .home .people { flex-basis: 100%; font-size: 14px; }
        li .time { grid-row: 1 / span 2; grid-column: 2; font-size: 24px; font-weight: 600; font-variant-numeric: tabular-nums; text-align: right; }
        li .info { grid-row: 1 / span 2; grid-column: 3; font-size: 13.5px; text-align: right; min-width: 9rem; }
        li .diff { color: var(--muted); }
        li.work { background: color-mix(in srgb, var(--accent) 13%, transparent); border-color: color-mix(in srgb, var(--accent) 35%, transparent); }
        li.night { background: color-mix(in srgb, var(--ink) 9%, transparent); }
        li.night .time, li.night .who { opacity: 0.75; }
        .tag { display: inline-block; font-size: 12px; padding: 1px 8px; border-radius: 999px; border: 1px solid var(--line); }
        li.work .tag { border-color: var(--accent); }
        .fix { padding: 14px 18px; display: grid; gap: 8px; }
        .fix p { margin: 0; }
        .fix ul { gap: 6px; }
        .fix li { display: block; padding: 0; }
        .empty { color: var(--muted); padding: 18px; margin: 0; }
        .add { padding: 16px 22px; display: grid; gap: 10px; }
        .add .controls { display: flex; flex-wrap: wrap; align-items: end; gap: 10px 14px; }
        .add label { display: grid; gap: 4px; }
        .add select { max-width: 16rem; }
        .add .said { margin: 0; font-size: 14px; color: var(--muted); }
        .skeleton.big { height: 40vh; }
        @media (max-width: 600px) {
            li { grid-template-columns: minmax(0, 1fr) auto; }
            li .info { grid-row: auto; grid-column: 1 / span 2; text-align: left; }
            .home .time { font-size: 36px; }
        }
    `];

    constructor() {
        super();
        new MinuteTick(this);
        this._plan = null;
        this._loading = true;
        this._pick = { country: '', zone: '', label: '' };
        this._mate = { name: '', place: '' };
    }

    connectedCallback() {
        super.connectedCallback();
        this._load();
        // A run started or ended, or the settings pop-up closed: read again, and match again if the cities changed.
        this._off = elf.onChange(() => this._load());
    }

    disconnectedCallback() {
        this._off?.();
        super.disconnectedCallback();
    }

    async _load() {
        this._error = null;
        try {
            const [clocks, s, state] = await Promise.all([elf.readJson('clocks', 'clocks.json'), settings(), elf.state()]);
            this._clocks = clocks;
            this._settings = s;
            this._state = state;
            this._matchIfChanged();
            // Saved by the same run as the clocks; an older version's run didn't save it, so it may not be there yet.
            this._places = await elf.readJson('clocks', 'places.json').catch(() => null);
        } catch (e) {
            this._error = e.message;
        } finally {
            this._loading = false;
        }
    }

    /** The cities in Settings aren't the ones it matched: match again, once for each change (not again if it fails). */
    async _matchIfChanged() {
        const s = this._settings, c = this._clocks;
        if (!s || this._state?.running?.match) return;
        if (c && c.citiesSetting === s.cities && c.homeSetting === s.home && c.peopleSetting === s.people) return;
        const key = `${s.cities}\n${s.home}\n${s.people}`;
        if (this._tried === key) return;
        this._tried = key;
        try {
            this._state = await elf.run('match');
        } catch (e) {
            this._error = `Couldn't match the cities: ${e.message}`;
        }
    }

    /** Pick a country, then one of its cities, then +: the city goes on the end of Team cities in Settings. */
    _adder() {
        const places = this._places;
        if (!places?.length) return '';
        const pick = this._pick;
        const country = places.find(c => (c.code || c.name) === pick.country);
        const cities = country?.places ?? [];
        return html`<section class="panel add" aria-label="Add a city">
            <div class="controls">
                <strong>Add a city</strong>
                <label class="small">Country
                    <select @change=${e => {
                        const c = places.find(x => (x.code || x.name) === e.target.value);
                        // A country with one time zone has nothing more to pick.
                        this._pick = { ...pick, country: e.target.value, zone: c?.places.length === 1 ? c.places[0].zone : '' };
                        this._added = null;
                    }}>
                        <option value="" ?selected=${!pick.country}>Pick a country</option>
                        ${places.map(c => html`<option value=${c.code || c.name} ?selected=${pick.country === (c.code || c.name)}>${c.name}</option>`)}
                    </select></label>
                <label class="small">City
                    <select ?disabled=${!country} @change=${e => { this._pick = { ...pick, zone: e.target.value }; this._added = null; }}>
                        ${cities.length === 1 ? '' : html`<option value="" ?selected=${!pick.zone}>Pick a city</option>`}
                        ${cities.map(p => html`<option value=${p.zone} ?selected=${pick.zone === p.zone}>${p.city}${p.note ? ` (${p.note})` : ''}</option>`)}
                    </select></label>
                <label class="small">Name it (optional)
                    <input type="text" maxlength="60" placeholder=${cities.find(p => p.zone === pick.zone)?.city ?? 'A team mate'}
                        .value=${pick.label} @input=${e => { this._pick = { ...pick, label: e.target.value }; }}></label>
                <button ?disabled=${!pick.zone || this._adding} aria-label="Add this city" @click=${() => this._add()}>${this._adding ? spinner : '+'}</button>
            </div>
            <p class="said" role="status">${this._added?.error ? html`<span class="error">${this._added.error}</span>`
                : this._added?.added ? html`Added <strong>${this._added.added}</strong> to your cities in Settings.`
                : 'Your country only lists the cities with a time zone of their own: pick the nearest one, and give it your team mate\'s or your town\'s name.'}</p>
        </section>`;
    }

    /** A name, then one of the rows (or your own time), then Add: it goes on the end of Team mates in Settings. */
    _mateAdder() {
        const c = this._clocks;
        if (!c) return '';
        // The place is written as the row's name; your own time from this machine is written as its time zone.
        const places = [{ value: c.homeFromMachine ? c.home.zone : c.home.label, text: c.homeFromMachine ? 'Your time' : `${c.home.label} (your city)` },
            ...(c.cities ?? []).map(r => ({ value: r.label, text: r.label }))];
        const mate = this._mate;
        return html`<section class="panel add" aria-label="Add a team mate">
            <div class="controls">
                <strong>Add a team mate</strong>
                <label class="small">Name
                    <input type="text" maxlength="40" placeholder="Anna" .value=${mate.name}
                        @input=${e => { this._mate = { ...mate, name: e.target.value }; this._mateAdded = null; }}></label>
                <label class="small">Where
                    <select @change=${e => { this._mate = { ...mate, place: e.target.value }; this._mateAdded = null; }}>
                        <option value="" ?selected=${!mate.place}>Pick a city</option>
                        ${places.map(p => html`<option value=${p.value} ?selected=${mate.place === p.value}>${p.text}</option>`)}
                    </select></label>
                <button ?disabled=${!mate.name.trim() || !mate.place || this._mateAdding} @click=${() => this._addMate()}>${this._mateAdding ? spinner : 'Add team mate'}</button>
            </div>
            <p class="said" role="status">${this._mateAdded?.error ? html`<span class="error">${this._mateAdded.error}</span>`
                : this._mateAdded?.added ? html`Added <strong>${this._mateAdded.added}</strong> to your team mates in Settings.`
                : 'Their name shows under their city, so you can see who is where. Not there yet? Add the city first.'}</p>
        </section>`;
    }

    async _addMate() {
        const name = this._mate.name.trim(), place = this._mate.place;
        if (/[,=]/.test(name)) {
            this._mateAdded = { error: 'A name can\'t have a comma or = in it.' };
            return;
        }
        this._mateAdding = true;
        try {
            const answer = await elf.act('add-person', { name, place });
            this._mateAdded = answer && typeof answer === 'object' ? answer : { error: 'It didn\'t say whether it added your team mate.' };
            if (this._mateAdded.added) {
                this._mate = { ...this._mate, name: '' };
                await this._load();
            }
        } catch (e) {
            if (e.message !== 'cancelled') this._mateAdded = { error: `Couldn't add your team mate: ${e.message}` };
        } finally {
            this._mateAdding = false;
        }
    }

    async _add() {
        const { zone, label } = this._pick;
        const name = label.trim();
        if (/[,=]/.test(name)) {
            this._added = { error: 'A name can\'t have a comma or = in it.' };
            return;
        }
        this._adding = true;
        try {
            const answer = await elf.act('add-city', name ? { zone, label: name } : { zone });
            this._added = answer && typeof answer === 'object' ? answer : { error: 'It didn\'t say whether it added the city.' };
            if (this._added.added) {
                this._pick = { ...this._pick, label: '' };
                await this._load();
            }
        } catch (e) {
            if (e.message !== 'cancelled') this._added = { error: `Couldn't add the city: ${e.message}` };
        } finally {
            this._adding = false;
        }
    }

    /** The moment the rows show: now, or the one you picked. */
    _moment(home) {
        if (!this._plan) return Date.now();
        const today = partsOf(Date.now(), home);
        const day = new Date(Date.UTC(today.y, today.mo - 1, today.d) + this._plan.day * DAY);
        return momentOf(day.getUTCFullYear(), day.getUTCMonth() + 1, day.getUTCDate(),
            Math.floor(this._plan.minutes / 60), this._plan.minutes % 60, home);
    }

    _setPlan(change) {
        const home = this._clocks?.home?.zone;
        if (!home) return;
        let plan = this._plan;
        if (!plan) {
            // Start from now, on the quarter hour.
            const p = partsOf(Date.now(), home);
            plan = { day: 0, minutes: Math.floor((p.h * 60 + p.mi) / 15) * 15 };
        }
        this._plan = { ...plan, ...change };
    }

    _planner(home, ms, rows) {
        const s = this._settings;
        const today = partsOf(Date.now(), home);
        const days = [...Array(7).keys()].map(i => {
            const noon = momentOf(today.y, today.mo, today.d, 12, 0, home) + i * DAY;
            return { i, label: i === 0 ? 'Today' : i === 1 ? 'Tomorrow' : dateText(noon, home) };
        });
        const p = partsOf(ms, home);
        const minutes = this._plan?.minutes ?? p.h * 60 + p.mi;
        const hhmm = `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`;
        const working = rows.filter(r => shadeOf(ms, r.zone, s.start, s.end).key === 'work');
        return html`<section class="panel plan" aria-label="Plan a time">
            <div class="controls">
                <strong>Plan a time</strong>
                <label class="small">Day
                    <select @change=${e => this._setPlan({ day: +e.target.value })}>
                        ${days.map(d => html`<option value=${d.i} ?selected=${(this._plan?.day ?? 0) === d.i}>${d.label}</option>`)}
                    </select></label>
                <label class="small">Your time
                    <input type="time" step="900" .value=${hhmm}
                        @change=${e => { const [h, m] = e.target.value.split(':').map(Number); if (h >= 0 && m >= 0) this._setPlan({ minutes: h * 60 + m }); }}></label>
                ${this._plan ? html`<button class="quiet" @click=${() => { this._plan = null; }}>Now</button>` : ''}
            </div>
            <input type="range" min="0" max="1425" step="15" .value=${String(Math.floor(minutes / 15) * 15)}
                aria-label="Your time of day" aria-valuetext=${timeText(ms, home, s.hour24)}
                @input=${e => this._setPlan({ minutes: +e.target.value })}>
            <p class="said">${this._plan
                ? html`At <strong>${timeText(ms, home, s.hour24)} ${dateText(ms, home)}</strong> your time,
                    ${working.length === rows.length && rows.length ? 'every city is in working hours'
                        : working.length ? html`<strong>${working.map(withPeople).join(', ')}</strong> ${working.length === 1 ? 'is' : 'are'} in working hours`
                        : 'no city is in working hours'}.`
                : 'Drag the slider or pick a day and time in your time to see it in every city.'}</p>
        </section>`;
    }

    _row(r, ms, home) {
        const s = this._settings;
        const shade = shadeOf(ms, r.zone, s.start, s.end);
        return html`<li class="panel ${shade.key}">
            <span class="who">${r.label}</span>
            <span class="zone">${r.zone.replaceAll('_', ' ')}</span>
            ${r.people?.length ? html`<span class="people">${r.people.join(', ')}</span>` : ''}
            <span class="time">${timeText(ms, r.zone, s.hour24)}</span>
            <span class="info">${dayText(ms, r.zone)} · <span class="diff">${diffText(ms, r.zone, home)}</span><br>
                <span class="tag">${shade.label}</span></span>
        </li>`;
    }

    render() {
        const c = this._clocks, s = this._settings;
        const running = this._state?.running?.match;
        const home = c?.home?.zone;
        const rows = c?.cities ?? [];
        const ms = home ? this._moment(home) : Date.now();
        return html`
            <div class="page">
                <div class="page-top"><div>
                    <h1>Team clocks</h1>
                    <p class="sub">${this._plan ? 'Planning a time. Now goes back to the live clocks.'
                        : 'Your time and your team\'s, live. Rows are shaded for working hours, after hours and night.'}</p>
                </div>${settingsButton(KEYS, 'Team clocks settings')}</div>
                <elf-run-bar command="match" label="Match the cities"
                    note="Matches the cities in Settings against this machine's time zones. It runs by itself when you change them, and sends nothing."
                    @run-finished=${() => this._load()}></elf-run-bar>
                ${this._error ? html`<p class="error" role="alert">${this._error}</p>` : ''}
                ${this._loading && !c ? html`<div class="skeleton big"></div>` : ''}
                ${!c && !this._loading ? html`<p class="panel empty">${running
                    ? html`${spinner} Matching your cities, started ${ago(running)}.`
                    : 'Add your team\'s cities in Settings, then Match the cities.'}</p>` : ''}
                ${c && s ? html`
                    <section class="panel home" aria-label="Your time">
                        <span class="time">${timeText(ms, home, s.hour24)}</span>
                        <span class="day">${dateText(ms, home)}</span>
                        <span class="where">${c.homeFromMachine ? `Your time, from this machine (${home.replaceAll('_', ' ')})`
                            : `Your time in ${c.home.label} (${home.replaceAll('_', ' ')})`}</span>
                        ${c.home.people?.length ? html`<span class="people">Here too: ${c.home.people.join(', ')}</span>` : ''}
                    </section>
                    ${c.unmatched?.length ? html`<section class="panel fix" role="status">
                        <p><strong>${c.unmatched.length === 1 ? 'One place needs' : `${c.unmatched.length} places need`} fixing in Settings:</strong></p>
                        <ul>${c.unmatched.map(u => html`<li><strong>${u.text}</strong>: ${u.hint}</li>`)}</ul>
                        <div>${settingsButton(['CITIES', 'PEOPLE', 'HOME'], 'Your team\'s cities')}</div>
                    </section>` : ''}
                    ${rows.length ? html`
                        ${this._planner(home, ms, rows)}
                        <ul aria-label="Your team's cities">${rows.map(r => this._row(r, ms, home))}</ul>`
                        : html`<p class="panel empty">No cities yet. Add your team's cities below, or in Settings, like London, Berlin, New York.</p>`}
                    ${this._adder()}
                    ${this._mateAdder()}
                ` : ''}
            </div>
        `;
    }
}

/** The dashboard card: each city's time and day, in one line. It never needs you. */
class TeamClocksCard extends LitElement {

    static properties = { _clocks: { state: true }, _settings: { state: true }, _loaded: { state: true } };
    static styles = [shared, cardStyles, css`.say { font-variant-numeric: tabular-nums; }`];

    constructor() {
        super();
        new MinuteTick(this);
    }

    connectedCallback() {
        super.connectedCallback();
        this._load();
        this._off = elf.onChange(() => this._load());
    }

    disconnectedCallback() {
        this._off?.();
        super.disconnectedCallback();
    }

    async _load() {
        try {
            [this._clocks, this._settings] = await Promise.all([elf.readJson('clocks', 'clocks.json'), settings()]);
        } catch (e) {
            this._clocks = null;
        }
        this._loaded = true;
        elf.card({ attention: false });
    }

    render() {
        if (!this._loaded) return html`<div class="skeleton say-sk"></div>`;
        const c = this._clocks, s = this._settings;
        if (!c?.cities?.length) return html`<p class="say">No cities yet. <a href="#">Add your team's cities</a>.</p>`;
        const now = Date.now();
        const home = c.home.zone;
        return html`<p class="say"><a href="#">${c.cities.map(r => `${r.label} ${timeText(now, r.zone, s.hour24)} ${dayText(now, r.zone)}`).join(' · ')}</a></p>
            <div class="meta"><span>Your time ${timeText(now, home, s.hour24)} ${dayText(now, home)}</span>
                ${c.unmatched?.length ? html`<span>${c.unmatched.length === 1 ? 'One place needs' : `${c.unmatched.length} places need`} fixing in Settings</span>` : ''}</div>`;
    }
}

customElements.define('team-clocks-tab', TeamClocksTab);
customElements.define('team-clocks-card', TeamClocksCard);
document.body.append(document.createElement(elf.view === 'card' ? 'team-clocks-card' : 'team-clocks-tab'));
