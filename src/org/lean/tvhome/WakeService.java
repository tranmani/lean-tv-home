package org.lean.tvhome;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Wake-on-LAN for boxes whose firmware keeps it switched off. While on, this service keeps the
 * processor awake when the screen is off, so the network stays up, and listens on UDP port 9009
 * for a standard magic packet. (Ports 9 and 7, the usual ones, are below 1024, which Android only
 * lets system apps use; port 9 is still tried in case the box allows it.) A matching packet turns the screen on, which also wakes the
 * TV through HDMI-CEC.
 *
 * Cost: the box no longer goes into deep sleep while the screen is off.
 */
public class WakeService extends Service {
    static final int PORT = 9009;
    private static final int[] PORTS = {PORT, 9};
    private static final String CHANNEL = "wake";

    /** Ports this process is listening on, for the Customize screen. Empty when off. */
    static final List<Integer> listening = new ArrayList<>();
    /** Time of the last packet that woke the box, 0 if none since start. */
    static long lastWake;

    private final List<DatagramSocket> sockets = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock awake;

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("wake", Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) {
        return prefs(c).getBoolean("on", false);
    }

    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("on", on).apply();
        sync(c);
    }

    /** The box's network address (MAC). Packets for any other address are ignored. Empty = any. */
    static String mac(Context c) {
        return prefs(c).getString("mac", "");
    }

    static void setMac(Context c, String mac) {
        String clean = mac.trim().toLowerCase(Locale.ROOT).replace('-', ':');
        if (!clean.isEmpty() && !clean.matches("([0-9a-f]{2}:){5}[0-9a-f]{2}")) return;
        prefs(c).edit().putString("mac", clean).apply();
    }

    /** Android 14 lets an app turn the screen on only with this app-op, which only adb can grant. */
    static boolean canTurnScreenOn(Context c) {
        if (Build.VERSION.SDK_INT < 34) return true;
        try {
            int mode = c.getSystemService(android.app.AppOpsManager.class).unsafeCheckOpNoThrow(
                "android:turn_screen_on", android.os.Process.myUid(), c.getPackageName());
            return mode == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (RuntimeException e) {
            return true; // Unknown op on this build: assume the old rules.
        }
    }

    /** Starts or stops the service to match the saved setting. */
    static void sync(Context c) {
        Intent i = new Intent(c, WakeService.class);
        try {
            if (enabled(c)) c.startForegroundService(i);
            else c.stopService(i);
        } catch (RuntimeException ignored) {
            // Not allowed from the background right now; the next home screen visit retries.
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Wake-on-LAN",
            NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_power_off)
            .setContentTitle("Wake-on-LAN is on")
            .setContentText("Lean TV Home wakes the box when it gets a magic packet.")
            .setOngoing(true)
            .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }

        awake = getSystemService(PowerManager.class)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvhome:wol-listen");
        awake.setReferenceCounted(false);
        awake.acquire();

        for (int port : PORTS) listen(port);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        synchronized (sockets) {
            for (DatagramSocket s : sockets) s.close();
            sockets.clear();
        }
        synchronized (listening) {
            listening.clear();
        }
        if (awake != null && awake.isHeld()) awake.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void listen(int port) {
        Thread t = new Thread(() -> {
            DatagramSocket s;
            try {
                s = new DatagramSocket(null);
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(port));
            } catch (Exception e) {
                return; // Port taken or not allowed; the other port may still work.
            }
            synchronized (sockets) {
                sockets.add(s);
            }
            synchronized (listening) {
                listening.add(port);
            }
            byte[] buf = new byte[1024];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            while (!s.isClosed()) {
                try {
                    p.setLength(buf.length);
                    s.receive(p);
                    String target = magicTarget(buf, p.getLength());
                    if (target == null) continue;
                    String want = mac(this);
                    if (want.isEmpty() || want.equals(target)) main.post(this::wake);
                } catch (Exception e) {
                    if (s.isClosed()) break;
                }
            }
        }, "wol-" + port);
        t.setDaemon(true);
        t.start();
    }

    /**
     * Returns the address a magic packet is for ("aa:bb:cc:dd:ee:ff"), or null if the data is not
     * one. A magic packet is six 0xFF bytes followed by the 6-byte address repeated 16 times.
     */
    static String magicTarget(byte[] b, int len) {
        for (int start = 0; start + 102 <= len; start++) {
            boolean sync = true;
            for (int i = 0; i < 6 && sync; i++) sync = (b[start + i] & 0xff) == 0xff;
            if (!sync) continue;
            int mac = start + 6;
            boolean ok = true;
            for (int rep = 1; rep < 16 && ok; rep++) {
                for (int i = 0; i < 6 && ok; i++) ok = b[mac + rep * 6 + i] == b[mac + i];
            }
            if (!ok) continue;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                if (i > 0) sb.append(':');
                sb.append(String.format(Locale.ROOT, "%02x", b[mac + i] & 0xff));
            }
            return sb.toString();
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private void wake() {
        lastWake = System.currentTimeMillis();
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm.isInteractive()) return;
        PowerManager.WakeLock screen = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
            | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "tvhome:wol-wake");
        screen.acquire(5000);
        try {
            startActivity(new Intent(this, WakeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) {
        }
    }

    /** Starts the service after a reboot when Wake-on-LAN is on. */
    public static class Boot extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            sync(c);
        }
    }
}
