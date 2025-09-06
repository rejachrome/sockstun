/*
 ============================================================================
 Name        : TProxyService.java
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2024 xyz
 Description : TProxy Service
 ============================================================================
 */

package hev.sockstun;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.DatagramSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import android.util.Log;
import android.content.Context;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ServiceInfo;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;

import androidx.core.app.NotificationCompat;

public class TProxyService extends VpnService {
	private static native void TProxyStartService(String config_path, int fd);
	private static native void TProxyStopService();
	private static native long[] TProxyGetStats();

	public static final String ACTION_CONNECT = "hev.sockstun.CONNECT";
	public static final String ACTION_DISCONNECT = "hev.sockstun.DISCONNECT";

	static {
		System.loadLibrary("hev-socks5-tunnel");
	}

	private ParcelFileDescriptor tunFd = null;

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
			stopService();
			return START_NOT_STICKY;
		}
		startService();
		return START_STICKY;
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
	}

	@Override
	public void onRevoke() {
		stopService();
		super.onRevoke();
	}

	public void startService() {
		if (tunFd != null)
		  return;

		Preferences prefs = new Preferences(this);

		/* VPN */
		String session = new String();
		VpnService.Builder builder = new VpnService.Builder();
		builder.setBlocking(false);
		builder.setMtu(prefs.getTunnelMtu());
		if (prefs.getIpv4()) {
			String addr = prefs.getTunnelIpv4Address();
			int prefix = prefs.getTunnelIpv4Prefix();
			String dns = prefs.getDnsIpv4();
			builder.addAddress(addr, prefix);
			builder.addRoute("0.0.0.0", 0);
			if (!prefs.getRemoteDns() && !dns.isEmpty())
			  builder.addDnsServer(dns);
			session += "IPv4";
		}
		if (prefs.getIpv6()) {
			String addr = prefs.getTunnelIpv6Address();
			int prefix = prefs.getTunnelIpv6Prefix();
			String dns = prefs.getDnsIpv6();
			builder.addAddress(addr, prefix);
			builder.addRoute("::", 0);
			if (!prefs.getRemoteDns() && !dns.isEmpty())
			  builder.addDnsServer(dns);
			if (!session.isEmpty())
			  session += " + ";
			session += "IPv6";
		}
		if (prefs.getRemoteDns()) {
			builder.addDnsServer(prefs.getMappedDns());
		}
		boolean disallowSelf = true;
		if (prefs.getGlobal()) {
			session += "/Global";
		} else {
			for (String appName : prefs.getApps()) {
				try {
					builder.addAllowedApplication(appName);
					disallowSelf = false;
				} catch (NameNotFoundException e) {
				}
			}
			session += "/per-App";
		}
		if (disallowSelf) {
			String selfName = getApplicationContext().getPackageName();
			try {
				builder.addDisallowedApplication(selfName);
			} catch (NameNotFoundException e) {
			}
		}
		builder.setSession(session);
		tunFd = builder.establish();
		if (tunFd == null) {
			stopSelf();
			return;
		}

		/* TProxy */
		File tproxy_file = new File(getCacheDir(), "tproxy.conf");
		try {
			tproxy_file.createNewFile();
			FileOutputStream fos = new FileOutputStream(tproxy_file, false);

			String tproxy_conf = "misc:\n" +
				"  task-stack-size: " + prefs.getTaskStackSize() + "\n" +
				"tunnel:\n" +
				"  mtu: " + prefs.getTunnelMtu() + "\n";

			tproxy_conf += "socks5:\n" +
				"  port: " + prefs.getSocksPort() + "\n" +
				"  address: '" + prefs.getSocksAddress() + "'\n" +
				"  udp: '" + (prefs.getUdpInTcp() ? "tcp" : "udp") + "'\n";

			if (!prefs.getSocksUsername().isEmpty() &&
				!prefs.getSocksPassword().isEmpty()) {
				tproxy_conf += "  username: '" + prefs.getSocksUsername() + "'\n";
				tproxy_conf += "  password: '" + prefs.getSocksPassword() + "'\n";
			}

			if (prefs.getRemoteDns()) {
				tproxy_conf += "mapdns:\n" +
					"  address: " + prefs.getMappedDns() + "\n" +
					"  port: 53\n" +
					"  network: 240.0.0.0\n" +
					"  netmask: 240.0.0.0\n" +
					"  cache-size: 10000\n";
			}

			fos.write(tproxy_conf.getBytes());
			fos.close();
		} catch (IOException e) {
			return;
		}
		TProxyStartService(tproxy_file.getAbsolutePath(), tunFd.getFd());
		prefs.setEnable(true);

		String channelName = "socks5";
		initNotificationChannel(channelName);
		createNotification(channelName);

		/* register network callback to watch for tether/hotspot changes */
		registerNetworkCallback();
	}

	public void stopService() {
		if (tunFd == null)
		  return;

		stopForeground(true);

		/* TProxy */
		TProxyStopService();

		/* cleanup hotspot routing if present */
		cleanupHotspotRouting();

		/* VPN */
		try {
			tunFd.close();
		} catch (IOException e) {
		}
		tunFd = null;

		System.exit(0);
	}

	private void createNotification(String channelName) {
		Intent i = new Intent(this, TProxyService.class);
		PendingIntent pi = PendingIntent.getService(this, 0, i, PendingIntent.FLAG_IMMUTABLE);
		NotificationCompat.Builder notification = new NotificationCompat.Builder(this, channelName);
		Notification notify = notification
				.setContentTitle(getString(R.string.app_name))
				.setSmallIcon(android.R.drawable.sym_def_app_icon)
				.setContentIntent(pi)
				.build();
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
			startForeground(1, notify);
		} else {
			startForeground(1, notify, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
		}
	}

	// create NotificationChannel
	private void initNotificationChannel(String channelName) {
		NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			CharSequence name = getString(R.string.app_name);
			NotificationChannel channel = new NotificationChannel(channelName, name, NotificationManager.IMPORTANCE_DEFAULT);
			notificationManager.createNotificationChannel(channel);
		}
	}

    // Add hotspot traffic handling
	private void setupHotspotRouting() {
	    String hotspotInterface = getHotspotInterface();

	    if (hotspotInterface != null && !hotspotInterface.isEmpty()) {
	        // These commands typically require root. They are executed as simple shell commands here.
	        executeCommand("ip rule add iif " + hotspotInterface + " lookup 1000");
	        executeCommand("ip route add default dev tun0 table 1000");
	        executeCommand("iptables -I FORWARD -o " + hotspotInterface + " -i tun0 -j ACCEPT");
	        executeCommand("iptables -I FORWARD -i " + hotspotInterface + " -o tun0 -j ACCEPT");
	        executeCommand("iptables -t nat -I POSTROUTING -o tun0 -j MASQUERADE");
	    }
	}

	private String getHotspotInterface() {
	    try {
	        // Use ip -o link to get one-line-per-interface output
	        Process proc = Runtime.getRuntime().exec(new String[] { "sh", "-c", "ip -o link" });
	        InputStream is = proc.getInputStream();
	        BufferedReader br = new BufferedReader(new InputStreamReader(is));
	        String line;
	        while ((line = br.readLine()) != null) {
	            // typical line: "3: wlan0: <...> mtu 1500 ..."
	            String[] parts = line.split(":");
	            if (parts.length >= 2) {
	                String ifname = parts[1].trim();
	                // Heuristics: hotspot/tether interfaces often contain wlan, ap, rndis, tether
	                String lname = ifname.toLowerCase();
	                if (lname.startsWith("wlan") || lname.startsWith("ap") || lname.contains("tether") || lname.contains("rndis")) {
	                    // Return first candidate
	                    proc.destroy();
	                    return ifname;
	                }
	            }
	        }
	        br.close();
	        proc.waitFor();
	    } catch (Exception e) {
	        // ignore and return null
	    }
	    return null;
	}

	private void cleanupHotspotRouting() {
	    // Remove iptables rules and routing entries; try multiple common iface names
	    String[] candidates = new String[] { "wlan0", "ap0", "rndis0" };
	    for (String iface : candidates) {
	        executeCommand("ip rule del iif " + iface + " lookup 1000");
	        executeCommand("iptables -D FORWARD -o " + iface + " -i tun0 -j ACCEPT");
	        executeCommand("iptables -D FORWARD -i " + iface + " -o tun0 -j ACCEPT");
	    }
	    executeCommand("ip route del default dev tun0 table 1000");
	    executeCommand("iptables -t nat -D POSTROUTING -o tun0 -j MASQUERADE");
	}

	private int executeCommand(String cmd) {
	    Process proc = null;
	    try {
	        // Try with su if available (comment/uncomment depending on environment)
	        // proc = Runtime.getRuntime().exec(new String[] { "su", "-c", cmd });
	        proc = Runtime.getRuntime().exec(new String[] { "sh", "-c", cmd });
	        InputStream is = proc.getInputStream();
	        InputStream es = proc.getErrorStream();
	        // consume streams to avoid blocking
	        consumeStream(is);
	        consumeStream(es);
	        int rc = proc.waitFor();
	        return rc;
	    } catch (Exception e) {
	        e.printStackTrace();
	        if (proc != null) proc.destroy();
	        return -1;
	    }
	}

	private void consumeStream(final InputStream stream) {
	    if (stream == null) return;
	    new Thread(() -> {
	        try (BufferedReader br = new BufferedReader(new InputStreamReader(stream))) {
	            while (br.readLine() != null) { /* consume */ }
	        } catch (IOException ignored) { }
	    }).start();
	}

	private void registerNetworkCallback() {
	    ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
	    if (cm == null) return;

	    NetworkRequest.Builder builder = new NetworkRequest.Builder();
	    try {
	        cm.registerNetworkCallback(builder.build(), new ConnectivityManager.NetworkCallback() {
	            @Override
	            public void onAvailable(Network network) {
	                // Check if hotspot is active and update routing
	                if (isHotspotActive()) {
	                    setupHotspotRouting();
	                }
	            }

	            @Override
	            public void onLost(Network network) {
	                // cleanup when network lost
	                cleanupHotspotRouting();
	            }
	        });
	    } catch (SecurityException | IllegalArgumentException e) {
	        // ignore registration failure on some platforms
	    }
	}

	private boolean isHotspotActive() {
	    // Simple check: if we can find a hotspot-like interface, assume hotspot active
	    String iface = getHotspotInterface();
	    return iface != null && !iface.isEmpty();
	}
    // ---------- HOTSPOT-VPN-COMPAT HELPERS (add to TProxyService class) ----------
private static final String TAG = "TProxyService"; // or reuse existing tag if present

/**
 * Return whether the user has enabled Accept-hotspot toggle (read from Preferences helper).
 */
private boolean isAcceptHotspotEnabled() {
    try {
        return hev.sockstun.Preferences.getAcceptHotspotClients(this);
    } catch (Exception e) {
        Log.w(TAG, "Failed reading preference, default false", e);
        return false;
    }
}

/**
 * Bind a ServerSocket to IPv4/IPv6 wildcard if accept-hotspot is enabled.
 * Usage: replace direct new ServerSocket(port) or bind(...) calls with this method.
 */
private ServerSocket bindWildcardServerSocket(int port) {
    if (!isAcceptHotspotEnabled()) {
        try {
            ServerSocket s = new ServerSocket(port);
            Log.i(TAG, "Bound ServerSocket local-only on port " + port);
            return s;
        } catch (Exception e) {
            Log.e(TAG, "Failed binding loopback/local ServerSocket on " + port, e);
            return null;
        }
    }

    // Try IPv4 wildcard 0.0.0.0
    try {
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), port));
        Log.i(TAG, "Bound ServerSocket to 0.0.0.0:" + port);
        return s;
    } catch (Exception e) {
        Log.w(TAG, "IPv4 wildcard bind failed, trying IPv6 :: on port " + port, e);
    }

    // Try IPv6 wildcard ::
    try {
        ServerSocket s6 = new ServerSocket();
        s6.setReuseAddress(true);
        s6.bind(new InetSocketAddress(InetAddress.getByName("::"), port));
        Log.i(TAG, "Bound ServerSocket to :::" + port);
        return s6;
    } catch (Exception e) {
        Log.e(TAG, "IPv6 wildcard bind failed on port " + port, e);
        return null;
    }
}

/**
 * Bind a DatagramSocket similarly.
 */
private DatagramSocket bindWildcardDatagramSocket(int port) {
    if (!isAcceptHotspotEnabled()) {
        try {
            DatagramSocket ds = new DatagramSocket(port);
            Log.i(TAG, "Bound DatagramSocket local-only on port " + port);
            return ds;
        } catch (Exception e) {
            Log.e(TAG, "Failed binding local DatagramSocket on " + port, e);
            return null;
        }
    }

    try {
        DatagramSocket ds = new DatagramSocket(null);
        ds.setReuseAddress(true);
        ds.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), port));
        Log.i(TAG, "Bound DatagramSocket to 0.0.0.0:" + port);
        return ds;
    } catch (Exception e) {
        Log.w(TAG, "IPv4 datagram wildcard bind failed, trying IPv6 :: on port " + port, e);
    }

    try {
        DatagramSocket ds6 = new DatagramSocket(null);
        ds6.setReuseAddress(true);
        ds6.bind(new InetSocketAddress(InetAddress.getByName("::"), port));
        Log.i(TAG, "Bound DatagramSocket to :::" + port);
        return ds6;
    } catch (Exception e) {
        Log.e(TAG, "IPv6 datagram wildcard bind failed on port " + port, e);
        return null;
    }
}

/**
 * Accept common hotspot subnets (best-effort). Returns true if this source should be accepted.
 * If toggle disabled, fallback to existing logic (you must integrate into your existing checks).
 */
private boolean isAllowedSourceAddress(String srcIp) {
    if (!isAcceptHotspotEnabled()) {
        // DEFAULT BEHAVIOR: preserve original allow logic.
        // Replace the following line with your original check or call to existing check method.
        return defaultAllowSource(srcIp);
    }

    // Accept hotspot / private ranges commonly used by Android hotspots:
    // 192.168.43.0/24, 192.168.42.0/24, 192.168.49.0/24, 192.168.8.0/24, 172.20.10.0/28, 192.168.1.0/24
    String[] allowedCidrs = new String[] {
        "192.168.43.0/24",
        "192.168.42.0/24",
        "192.168.49.0/24",
        "192.168.8.0/24",
        "192.168.1.0/24",
        "172.20.0.0/14",
        "10.0.0.0/8"
    };

    for (String cidr: allowedCidrs) {
        if (cidrContains(cidr, srcIp)) return true;
    }

    // fallback to existing check
    return defaultAllowSource(srcIp);
}

/**
 * Default allow check placeholder. Replace with original project's logic if present.
 */
private boolean defaultAllowSource(String srcIp) {
    // Basic default: allow loopback and local addresses; deny everything else.
    if (srcIp == null) return false;
    if (srcIp.startsWith("127.") || srcIp.equals("::1")) return true;
    if (srcIp.startsWith("192.168.") || srcIp.startsWith("10.") || srcIp.startsWith("172.")) return true; // permissive for debug
    return false;
}

/**
 * Check if an IPv4 address is in a CIDR block. (IPv4 only; minimal parser)
 */
private boolean cidrContains(String cidr, String ip) {
    try {
        String[] p = cidr.split("/");
        String network = p[0];
        int bits = Integer.parseInt(p[1]);

        byte[] networkBytes = InetAddress.getByName(network).getAddress();
        byte[] ipBytes = InetAddress.getByName(ip).getAddress();
        if (networkBytes.length != 4 || ipBytes.length != 4) return false; // only IPv4 here

        int networkInt = ((networkBytes[0] & 0xFF) << 24) | ((networkBytes[1] & 0xFF) << 16) |
                         ((networkBytes[2] & 0xFF) << 8) | (networkBytes[3] & 0xFF);
        int ipInt = ((ipBytes[0] & 0xFF) << 24) | ((ipBytes[1] & 0xFF) << 16) |
                    ((ipBytes[2] & 0xFF) << 8) | (ipBytes[3] & 0xFF);

        int mask = (bits == 0) ? 0 : (-1 << (32 - bits));
        return (networkInt & mask) == (ipInt & mask);
    } catch (Exception e) {
        Log.w(TAG, "cidrContains parse error for " + cidr + " / " + ip, e);
        return false;
    }
}

/**
 * Best-effort diagnostics: list network interfaces and addresses (no root required).
 * Call when VPN starts if diagnostics enabled.
 */
private void logNetworkDiagnostics() {
    try {
        if (!hev.sockstun.Preferences.getDiagnosticsEnabled(this)) return;
    } catch (Exception e) {
        // ignore and proceed
    }

    Log.i(TAG, "=== network diagnostics start ===");
    try {
        Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
        while (ifs.hasMoreElements()) {
            NetworkInterface nif = ifs.nextElement();
            try {
                String name = nif.getName();
                Log.i(TAG, "IFACE: " + name + " up=" + nif.isUp() + " loop=" + nif.isLoopback() + " mtu=" + nif.getMTU());
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress ia = addrs.nextElement();
                    Log.i(TAG, "  ADDR: " + ia.getHostAddress());
                }
            } catch (Exception e) {
                Log.w(TAG, "Error reading iface info", e);
            }
        }
    } catch (Exception e) {
        Log.w(TAG, "Failed to list network interfaces", e);
    }

    // Try to run 'ip route' for more info (best-effort; may be unavailable on some devices)
    try {
        Process p = Runtime.getRuntime().exec(new String[] { "sh", "-c", "ip route || cat /proc/net/route" });
        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String line;
        Log.i(TAG, "==== IP ROUTE / PROC.NET.ROUTE ====");
        while ((line = br.readLine()) != null) {
            Log.i(TAG, line);
        }
        br.close();
    } catch (Exception e) {
        Log.w(TAG, "Could not execute ip route (restricted without root?).", e);
    }

    Log.i(TAG, "=== network diagnostics end ===");
}
// ---------- END HOTSPOT-VPN-COMPAT HELPERS ----------

}
