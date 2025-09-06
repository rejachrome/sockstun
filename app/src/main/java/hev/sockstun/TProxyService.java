/*
 ============================================================================
 Name        : TProxyService.java
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2024 xyz
 Description : TProxy Service
 ============================================================================
 */

package hev.sockstun;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.app.Notification;
import android.app.Notification.Builder;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;

import androidx.core.app.NotificationCompat;

public class TProxyService extends VpnService {
        private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback defaultNetworkCallback;
    
    // Add network callback for API 28+
    @RequiresApi(Build.VERSION_CODES.P)
    private void setupNetworkCallbacks() {
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        
        NetworkRequest.Builder builder = new NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);
            
        NetworkRequest defaultNetworkRequest = builder.build();
        
        defaultNetworkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                // Update underlying networks to support hotspot routing
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setUnderlyingNetworks(new Network[]{network});
                }
            }
            
            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
                // Refresh network capabilities for hotspot clients
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setUnderlyingNetworks(new Network[]{network});
                }
            }
            
            @Override
            public void onLost(Network network) {
                // Clear underlying networks when connection lost
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setUnderlyingNetworks(null);
                }
            }
        };
        
        connectivityManager.requestNetwork(defaultNetworkRequest, defaultNetworkCallback);
    }
    
    private void cleanupNetworkCallbacks() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && 
            connectivityManager != null && defaultNetworkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(defaultNetworkCallback);
            } catch (Exception ignored) {
                // Ignore cleanup errors
            }
        }
    }
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

    /* Enhanced VPN Builder Configuration */
    String session = new String();
    VpnService.Builder builder = new VpnService.Builder();
    builder.setBlocking(false);
    builder.setMtu(prefs.getTunnelMtu());
    
    // IPv4 Configuration
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
    
    // IPv6 Configuration
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
    
    // Remote DNS Configuration
    if (prefs.getRemoteDns()) {
        builder.addDnsServer(prefs.getMappedDns());
    }
    
    // Per-App Proxy Configuration (Enhanced)
    configurePerAppProxy(builder, prefs);
    
    // Platform-Specific Features
    configurePlatformFeatures(builder);
    
    builder.setSession(session);
    tunFd = builder.establish();
    if (tunFd == null) {
        stopSelf();
        return;
    }

    // Setup network callbacks for hotspot support
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        setupNetworkCallbacks();
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
	}

	public void stopService() {
		if (tunFd == null)
		  return;

		stopForeground(true);

		/* TProxy */
		TProxyStopService();

		/* VPN */
		try {
			tunFd.close();
		} catch (IOException e) {
		}
		tunFd = null;

		System.exit(0);
	}

private void configurePerAppProxy(VpnService.Builder builder, Preferences prefs) {
    String selfPackageName = getApplicationContext().getPackageName();
    
    if (prefs.getGlobal()) {
        // Global mode - exclude self package
        try {
            builder.addDisallowedApplication(selfPackageName);
        } catch (PackageManager.NameNotFoundException e) {
            // Handle error
        }
    } else {
        // Per-app mode
        Set<String> apps = prefs.getApps();
        if (apps.isEmpty()) {
            // If no apps selected, exclude self
            try {
                builder.addDisallowedApplication(selfPackageName);
            } catch (PackageManager.NameNotFoundException e) {
                // Handle error
            }
        } else {
            // Add allowed applications (exclude self from list)
            for (String appName : apps) {
                if (!appName.equals(selfPackageName)) {
                    try {
                        builder.addAllowedApplication(appName);
                    } catch (PackageManager.NameNotFoundException e) {
                        // Handle error - app not found
                    }
                }
            }
        }
    }
}

private void configurePlatformFeatures(VpnService.Builder builder) {
    // Android Q (API 29) and above: Configure metering
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        builder.setMetered(false);
    }
    
    // Android M (API 23) and above: Clear underlying networks initially
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        setUnderlyingNetworks(null);
    }
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
}
