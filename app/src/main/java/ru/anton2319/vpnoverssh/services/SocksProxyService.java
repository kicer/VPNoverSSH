package ru.anton2319.vpnoverssh.services;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.IpPrefix;
import android.net.VpnService;
import android.os.AsyncTask;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import ru.anton2319.vpnoverssh.data.singleton.PortForward;
import ru.anton2319.vpnoverssh.data.singleton.SocksPersistent;
import ru.anton2319.vpnoverssh.data.singleton.StatusInfo;

public class SocksProxyService extends VpnService {

    private static final String TAG = "SocksProxyService";

    // Fake DNS settings. The values must match the tun2socks build produced
    // by .github/workflows/build-tun2socks-aar.yml: xjasonlyu/tun2socks PR
    // #374 (remote DNS: fake-ip pool + SOCKS5 domain-name relay) with the
    // Android adaptation from patches/tun2socks-android-fakedns/ - DNS is
    // answered inside the TUN stack, because an app process cannot bind UDP
    // port 53 on the system stack (privileged port, no CAP_NET_BIND_SERVICE).
    private static final String FAKE_DNS_NET_IPV4 = "198.18.0.0/15";

    private ExecutorService executor = Executors.newSingleThreadExecutor();

    private Thread vpnThread;
    SharedPreferences sharedPreferences;

    public Future<String> getDnsIp(SharedPreferences sharedPreferences) {
        return executor.submit(() -> {
            return sharedPreferences.getString("dns_resolver_ip", "1.1.1.1");
        });
    }

    private Future<Set<String>> getSelectedApps(SharedPreferences sharedPreferences) {
        return executor.submit(() -> {
            return sharedPreferences.getStringSet("included_apps", new HashSet<String>());
        });
    }

    private Future<Set<String>> getSelectedAppsFuture;

    Future<String> getDnsIpFuture;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        getSelectedAppsFuture = getSelectedApps(sharedPreferences);
        getDnsIpFuture = getDnsIp(sharedPreferences);
        // Start the VPN thread
        vpnThread = newVpnThread();
        SocksPersistent.getInstance().setVpnThread(vpnThread);
        vpnThread.start();

        return START_STICKY;
    }

    @Override
    public void onRevoke() {
        StatusInfo.getInstance().setActive(false);
        Thread sshThread = PortForward.getInstance().getSshThread();
        if (sshThread != null) {
            sshThread.interrupt();
        }
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Shutting down gracefully");
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                Intent sshIntent = StatusInfo.getInstance().getSshIntent();
                stopService(sshIntent);
            }
        });
        try {
            ParcelFileDescriptor pfd = SocksPersistent.getInstance().getVpnInterface();
            if (pfd != null) {
                pfd.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
            Log.d(TAG, "Cannot handle graceful shutdown, killing the service");
            android.os.Process.killProcess(android.os.Process.myPid());
        }

    }

    private void startVpn() throws IOException {
        try {
            // Get the FileDescriptor for the VPN interface
            ParcelFileDescriptor vpnInterface;
            Builder builder = new VpnService.Builder();
            builder.setMtu(1500).addAddress("26.26.26.1", 24);
            boolean remoteDns = sharedPreferences.getBoolean("remote_dns_enabled", false);
            Set<String> selectedApps = getSelectedAppsFuture.get();
            if (remoteDns) {
                // The patched engine answers DNS queries that arrive inside
                // the tunnel from a local fake-IP pool. The resolver IP here
                // is only the mailbox address apps send queries to - it must
                // NOT get an exclude route; the real resolving happens on the
                // SSH server side, as connections are relayed by domain name.
                builder.addRoute("0.0.0.0", 0);
                builder.addDnsServer(getDnsIpFuture.get());
            } else if (android.os.Build.VERSION.SDK_INT >= 33) {
                builder.addRoute(new IpPrefix(InetAddress.getByName("0.0.0.0"), 0));
                builder.excludeRoute(new IpPrefix(InetAddress.getByName(getDnsIpFuture.get()), 32));
                if (selectedApps.isEmpty()) {
                    builder.addDnsServer(getDnsIpFuture.get());
                }
            } else {
                ArrayList<Long> excludedIps = new ArrayList<>();
                excludedIps.add(ipATON(getDnsIpFuture.get()));
                addRoutesExcluding(builder, excludedIps);
                if (selectedApps.isEmpty()) {
                    builder.addDnsServer(getDnsIpFuture.get());
                }
            }
            if (selectedApps.isEmpty()) {
                builder.addDisallowedApplication("ru.anton2319.vpnoverssh");
            }
            else {
                for (String packageName : selectedApps) {
                    builder.addAllowedApplication(packageName);
                }
            }

            vpnInterface = builder.establish();

            SocksPersistent.getInstance().setVpnInterface(vpnInterface);

            String socksHostname = "127.0.0.1";
            int socksPort = Integer.parseInt(Optional.of(sharedPreferences.getString("forwarder_port", "1080")).orElse("1080"));

            // Initialize proxy
            engine.Key key = new engine.Key();
            key.setMark(0);
            key.setMTU(1500);
            key.setDevice("fd://" + vpnInterface.getFd());
            key.setInterface("");
            key.setLogLevel("warning");
            key.setProxy("socks5://127.0.0.1:"+socksPort);
            key.setRestAPI("");
            key.setTCPSendBufferSize("");
            key.setTCPReceiveBufferSize("");
            key.setTCPModerateReceiveBuffer(false);
            key.setFakeDNS(remoteDns);
            key.setFakeDNSNetIPv4(FAKE_DNS_NET_IPV4);
            // Empty on purpose: it disables PR #374's system-stack UDP/53
            // listener (which apps cannot bind); the Android-adapted engine
            // registers the fake-ip pool and answers inside the TUN stack.
            key.setFakeDNSListenAddress("");

            engine.Engine.insert(key);
            engine.Engine.start();

            while (true) {
                if (Thread.interrupted()) {
                    Log.d(TAG, "Interruption signal received");
                    throw new InterruptedException();
                }
            }
        } catch (InterruptedException e) {
            Log.d(TAG, "Stopping service");
            onDestroy();
        } catch (Exception e) {
            Log.e(TAG, "VPN thread error: ", e);
            e.printStackTrace();
            stopSelf();
        }
    }

    private Thread newVpnThread() {
        return new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    startVpn();
                } catch (IOException e) {
                    Log.d(TAG, "Failed to release system resources! This behaviour may lead to memory leaks!");
                } finally {
                    stopSelf();
                }
            }
        });
    }

    public static void addRoutesExcluding(Builder builder, ArrayList<Long> excludedIpsAton) {
        Collections.sort(excludedIpsAton);
        // bypass local subnet
        long currentAddress = ipATON("0.0.0.0");
        long endAddress = ipATON("126.255.255.255");

        while(currentAddress <= endAddress) {
            int mask = getMaximumMask(currentAddress, excludedIpsAton.isEmpty() ? endAddress : excludedIpsAton.get(0));
            long resultingAddress = currentAddress + subnetSize(mask);
            if(excludedIpsAton.contains(currentAddress)) {
                Log.d(TAG, "Excluding: "+ipNTOA(currentAddress)+"/"+mask);
                excludedIpsAton.remove(0);
            }
            else {
                Log.v(TAG, "Adding: "+ipNTOA(currentAddress)+"/"+mask);
                builder.addRoute(ipNTOA(currentAddress), mask);
            }
            currentAddress = resultingAddress;
        }

        // bypass multicast
        currentAddress = ipATON("128.0.0.0");
        endAddress = ipATON("223.255.255.255");


        while(currentAddress <= endAddress) {
            int mask = getMaximumMask(currentAddress, excludedIpsAton.isEmpty() ? endAddress : excludedIpsAton.get(0));
            long resultingAddress = currentAddress + subnetSize(mask);
            if(excludedIpsAton.contains(currentAddress)) {
                Log.d(TAG, "Excluding: "+ipNTOA(currentAddress)+"/"+mask);
                excludedIpsAton.remove(0);
            }
            else {
                Log.v(TAG, "Adding: "+ipNTOA(currentAddress)+"/"+mask);
                builder.addRoute(ipNTOA(currentAddress), mask);
            }
            currentAddress = resultingAddress;
        }

        // all other addresses
        currentAddress = ipATON("240.0.0.0");
        endAddress = ipATON("255.255.255.255");


        while(currentAddress <= endAddress) {
            int mask = getMaximumMask(currentAddress, excludedIpsAton.isEmpty() ? endAddress : excludedIpsAton.get(0));
            long resultingAddress = currentAddress + subnetSize(mask);
            if(excludedIpsAton.contains(currentAddress)) {
                Log.d(TAG, "Excluding: "+ipNTOA(currentAddress)+"/"+mask);
                excludedIpsAton.remove(0);
            }
            else {
                Log.v(TAG, "Adding: "+ipNTOA(currentAddress)+"/"+mask);
                builder.addRoute(ipNTOA(currentAddress), mask);
            }
            currentAddress = resultingAddress;
        }
    }

    public static int getMaximumMask(long startingAddress, long maximumAddress) {
        int subnetMask = 32;
        final byte[] octets = intToByteArrayBigEndian((int) startingAddress);

        while (subnetMask > 0) {
            long subnetSize = subnetSize(subnetMask - 1);
            long nextResultingAddress = startingAddress + subnetSize;

            if (nextResultingAddress > maximumAddress) {
                break;
            } else {
                subnetMask--;
            }
        }

        while (subnetMask < 32) {
            byte[] copyOctets = new byte[octets.length];
            System.arraycopy(octets, 0, copyOctets, 0, octets.length);
            if (maskValid(subnetMask, copyOctets)) {
                break;
            } else {
                subnetMask++;
            }
        }
        return subnetMask;
    }

    public static boolean maskValid(int subnetMask, byte[] octets) {
        int offset = subnetMask / 8;
        if (offset < octets.length) {
            for (octets[offset] <<= subnetMask % 8; offset < octets.length; ++offset) {
                if (octets[offset] != 0) {
                    return false;
                }
            }
            return true;
        }
        return true;
    }

    public static long subnetSize(long subnetMask) {
        return (long) Math.pow(2, 32 - subnetMask);
    }

    public static long ipATON(String ip) {
        String[] addrArray = ip.split("\\.");
        long num = 0;
        for (int i = 0; i < addrArray.length; i++)
        {
            int power = 3 - i;
            num += ((Integer.parseInt(addrArray[i]) % 256 * Math.pow(256, power)));
        }
        return num;
    }

    public static String ipNTOA(long binaryIp) {
        StringBuilder dottedDecimal = new StringBuilder();
        for (int i = 3; i >= 0; i--) {
            long octet = (binaryIp >> (i * 8)) & 0xFF;
            dottedDecimal.append(octet);
            if (i > 0) {
                dottedDecimal.append(".");
            }
        }
        return dottedDecimal.toString();
    }

    public static byte[] intToByteArrayBigEndian(int value) {
        byte[] bytes = new byte[4];
        bytes[0] = (byte) (value >> 24);
        bytes[1] = (byte) (value >> 16);
        bytes[2] = (byte) (value >> 8);
        bytes[3] = (byte) value;
        return bytes;
    }
}