package dev.android.dynamicisland.island.source;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.SystemClock;
import dev.android.dynamicisland.island.graphics.GlyphIcon;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.settings.ModuleSettings;
import dev.android.dynamicisland.xposed.Refl;
import dev.android.dynamicisland.xposed.XLog;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.List;
import java.util.Locale;

/**
 * The hotspot, as a card that stays for as long as it is on.
 *
 * <p>A hotspot is a state, not a moment, so unlike the quick-setting toggles this card has no expiry. It
 * shows what the hotspot is doing while it runs: how many devices are connected, what the session has
 * transferred and how long it has been up.
 *
 * <p>Everything is read from the phone itself, in System UI's own process: the state from the AP-state
 * broadcast (with a reflection call as the initial value), the counters from {@code /proc/net/dev} for the
 * access-point interfaces, and the client count from {@code /proc/net/arp} or a hidden WiFi API. Each of
 * those is optional — a phone that refuses one still gets a card with whatever it does allow.
 */
public final class HotspotSource {

    private static final String HOTSPOT_ACTION = "android.net.wifi.WIFI_AP_STATE_CHANGED";
    private static final int HOTSPOT_ENABLED = 13;
    /** Interfaces an access point can appear as, across vendors. */
    private static final String[] AP_INTERFACES = {"ap0", "ap1", "swlan0", "softap0", "wlan1", "wlan2", "rndis0"};
    /**
     * Interfaces that are never the access point: loopback, the station WiFi link, mobile data, tunnels and
     * Wi-Fi Direct. Everything else in {@code /proc/net/dev} counts as hotspot traffic, so a vendor name that
     * is not in the list above (Samsung does not always use ap0) is still measured instead of reading 0 B.
     */
    private static final String[] NOT_AP_PREFIXES = {
            "lo", "wlan0", "rmnet", "ccmni", "pdp", "eth0", "tun", "p2p", "sit", "dummy", "ip6tnl", "gre", "bond",
            "v4-", "clat", "nat", "ipsec", "xfrm", "erspan", "cfg80211"};
    private static final long TICK_MS = 1000L;

    private final Context ctx;
    private final Handler main;
    private final Runnable onChange;

    private boolean enabled;
    private boolean started;
    private long since;          // uptime when the hotspot was seen on
    private long baseRx, baseTx; // counters when it came up, so the card shows this session
    private long rx, tx;
    private int clients = -1;    // -1 = the phone does not tell us
    private boolean countersReadable; // false when /proc/net/dev is not readable here
    private String ssid;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!enabled) {
                return;
            }
            readCounters();
            readClients();
            onChange.run();
            main.removeCallbacks(this);
            main.postDelayed(this, TICK_MS);
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (i == null) {
                return;
            }
            int state = i.getIntExtra("wifi_state", -1);
            if (state == HOTSPOT_ENABLED) {
                setEnabled(true);
            } else if (state == 11 /* disabled */ || state == 14 /* failed */) {
                setEnabled(false);
            }
        }
    };

    public HotspotSource(Context ctx, Handler main, Runnable onChange) {
        this.ctx = ctx;
        this.main = main;
        this.onChange = onChange;
    }

    public void start() {
        if (started) {
            return;
        }
        started = true;
        try {
            ctx.registerReceiver(receiver, new IntentFilter(HOTSPOT_ACTION));
        } catch (Throwable t) {
            XLog.e("hotspot receiver not registered", t);
        }
        setEnabled(queryEnabled());
    }

    private void setEnabled(boolean on) {
        if (on == enabled) {
            return;
        }
        enabled = on;
        main.removeCallbacks(tick);
        if (on) {
            since = SystemClock.elapsedRealtime();
            readCounters();
            baseRx = rx;
            baseTx = tx;
            readClients();
            readSsid();
            main.postDelayed(tick, TICK_MS);
        } else {
            clients = -1;
            rx = 0;
            tx = 0;
        }
        onChange.run();
    }

    /** Switches the hotspot off, trying the WiFi APIs the vendors actually expose. */
    private void turnOff() {
        Object wifi = ctx.getSystemService(Context.WIFI_SERVICE);
        Object sem = ctx.getSystemService("sem_wifi");
        try {
            // the AOSP call takes a WifiConfiguration and the new state
            Refl.call(wifi, "setWifiApEnabled", (Object) null, false);
            if (!queryEnabled()) {
                return;
            }
            Refl.call(wifi, "setSoftApEnabled", false);
            if (!queryEnabled()) {
                return;
            }
            if (sem != null) {
                Refl.call(sem, "setSoftApEnabled", false);
                if (!queryEnabled()) {
                    return;
                }
                Refl.call(sem, "setWifiApEnabled", (Object) null, false);
            }
        } catch (Throwable t) {
            XLog.once("hotspot off", t);
        }
        // whatever happened, refresh: the state broadcast tells the truth
        main.postDelayed(() -> setEnabled(queryEnabled()), 400L);
    }

    /** Current AP state, asked of the WiFi service; false when the phone will not say. */
    private boolean queryEnabled() {
        try {
            Object wifi = ctx.getSystemService(Context.WIFI_SERVICE);
            Object state = Refl.call(wifi, "getWifiApState");
            if (state instanceof Integer) {
                return (Integer) state == HOTSPOT_ENABLED;
            }
        } catch (Throwable t) {
            XLog.once("hotspot state", t);
        }
        return false;
    }

    /** Adds the card while the hotspot is on. No expiry: it stays until the hotspot goes off. */
    public void collect(List<IslandItem> out) {
        if (!enabled || !ModuleSettings.hotspotCard()) {
            return;
        }
        IslandItem it = new IslandItem("hotspot:info");
        it.kind = IslandItem.Kind.EVENT;
        it.iconIsImage = false;
        it.pillColor = 0;
        it.icon = GlyphIcon.of(GlyphIcon.Kind.HOTSPOT);
        it.accent = 0xFF34C759;
        it.title = t("핫스팟 켜짐", "Hotspot on");
        // the description carries every line: a card with a description does not draw its subtitle, which is
        // why the devices line was missing from the expanded card
        it.description = devicesLine() + "\n" + dataLine() + "\n" + uptimeLine();
        it.trailing = IslandItem.Trailing.TEXT;
        it.trailingText = clients > 0 ? String.valueOf(clients) : null;
        it.postTime = since;
        // a button on the card, so the hotspot can be switched off without leaving what you are doing
        it.actions.add(new IslandItem.Action(t("끄기", "Turn off"), null, null, this::turnOff, true));
        out.add(it);
    }

    private String devicesLine() {
        String devices = clients < 0
                ? t("기기 수 알 수 없음", "devices unknown")
                : clients == 0 ? t("연결된 기기 없음", "no devices connected")
                : t(clients + "대 연결됨", clients + (clients == 1 ? " device connected" : " devices connected"));
        // The name of the connected device - never this phone's own hotspot name, which is what it used to
        // show ("NOTA's S22" is the phone you are holding, not the device that joined).
        String joined = clientNames();
        return joined == null ? devices : devices + " · " + joined;
    }

    /** Names of the joined devices, when the phone's WiFi service exposes them; null otherwise. */
    private String clientNames() {
        try {
            Object wifi = ctx.getSystemService(Context.WIFI_SERVICE);
            Object sem = ctx.getSystemService("sem_wifi");
            for (Object service : new Object[]{wifi, sem}) {
                Object list = service == null ? null : Refl.call(service, "getSoftApConnectedStaList");
                if (!(list instanceof List)) {
                    continue;
                }
                StringBuilder names = new StringBuilder();
                for (Object station : (List<?>) list) {
                    String name = stationName(station);
                    if (name == null) {
                        continue;
                    }
                    if (names.length() > 0) {
                        names.append(", ");
                    }
                    names.append(name);
                    if (names.length() > 48) {
                        break;
                    }
                }
                if (names.length() > 0) {
                    return names.toString();
                }
            }
        } catch (Throwable ignored) {
            // no names: the line keeps just the count
        }
        return null;
    }

    /** A station object is a MAC string or an object with a name getter; both are tried. */
    private static String stationName(Object station) {
        if (station == null) {
            return null;
        }
        if (station instanceof CharSequence) {
            return null; // a bare MAC address is not a name the user asked for
        }
        for (String getter : new String[]{"getDeviceName", "getName", "getHostname", "getSsid"}) {
            Object value = Refl.call(station, getter);
            if (value instanceof CharSequence && ((CharSequence) value).length() > 0) {
                return value.toString();
            }
        }
        return null;
    }

    private String dataLine() {
        if (!countersReadable) {
            return t("데이터 ", "Data ") + "—";
        }
        // one figure: what the hotspot has carried in this session, both directions together. The separate
        // download/upload split read as if the phone were downloading, which is not what a hotspot does.
        long total = Math.max(0L, rx - baseRx) + Math.max(0L, tx - baseTx);
        return t("데이터 ", "Data ") + size(total);
    }

    private String uptimeLine() {
        long seconds = Math.max(0L, (SystemClock.elapsedRealtime() - since) / 1000L);
        long h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        String time = h > 0 ? String.format(Locale.US, "%dh %02dm", h, m)
                : m > 0 ? String.format(Locale.US, "%dm %02ds", m, s)
                : s + "s";
        return t("켜진 지 ", "On for ") + time;
    }

    /** Bytes as a short human figure: 512 B, 84.2 KB, 1.4 MB, 12 GB. */
    private static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.US, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.US, "%.1f MB", mb);
        }
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    /** Counts this session's traffic on the access-point interfaces ({@code /proc/net/dev}). */
    private void readCounters() {
        long totalRx = 0, totalTx = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/net/dev"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int colon = line.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String name = line.substring(0, colon).trim();
                if (!isApInterface(name)) {
                    continue;
                }
                String[] parts = line.substring(colon + 1).trim().split("\\s+");
                if (parts.length >= 9) {
                    totalRx += parse(parts[0]);   // received
                    totalTx += parse(parts[8]);   // transmitted
                }
            }
            rx = totalRx;
            tx = totalTx;
            countersReadable = true;
        } catch (Throwable t) {
            countersReadable = false; // a phone that hides /proc says so instead of claiming 0 B
        }
    }

    /** True for an access-point interface: a known name, or anything that is not a known non-AP one. */
    private static boolean isApInterface(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        for (String ap : AP_INTERFACES) {
            if (ap.equals(name)) {
                return true;
            }
        }
        for (String not : NOT_AP_PREFIXES) {
            if (name.equals(not) || name.startsWith(not)) {
                return false;
            }
        }
        return true;
    }

    private static long parse(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** Connected devices: the vendor/hidden API first, then the ARP table of the access-point interfaces. */
    private void readClients() {
        try {
            Object wifi = ctx.getSystemService(Context.WIFI_SERVICE);
            Object sta = Refl.call(wifi, "getSoftApConnectedStaList");
            if (sta instanceof List) {
                clients = ((List<?>) sta).size();
                return;
            }
            Object connected = Refl.call(wifi, "getConnectedClients");
            if (connected instanceof List) {
                clients = ((List<?>) connected).size();
                return;
            }
            // Samsung keeps the list on its own service
            Object sem = ctx.getSystemService("sem_wifi");
            Object semList = sem == null ? null : Refl.call(sem, "getSoftApConnectedStaList");
            if (semList instanceof List) {
                clients = ((List<?>) semList).size();
                return;
            }
        } catch (Throwable ignored) {
            // fall through to the ARP table
        }
        clients = arpClients();
    }

    private static int arpClients() {
        int count = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/net/arp"))) {
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (header) {
                    header = false;
                    continue;
                }
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 6 && isApInterface(parts[5]) && !"0x0".equals(parts[2])) {
                    count++;
                }
            }
            return count;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** The hotspot's own name, when the phone will tell us. */
    private void readSsid() {
        try {
            Object wifi = ctx.getSystemService(Context.WIFI_SERVICE);
            Object config = Refl.call(wifi, "getSoftApConfiguration");
            if (config != null) {
                Object name = Refl.call(config, "getSsid");
                if (name instanceof String) {
                    ssid = (String) name;
                    return;
                }
            }
        } catch (Throwable ignored) {
            // no name: the card simply leaves it out
        }
        ssid = null;
    }

    private String t(String ko, String en) {
        Locale locale = ctx.getResources().getConfiguration().getLocales().get(0);
        return "ko".equals(locale.getLanguage()) ? ko : en;
    }
}
