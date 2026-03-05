package macro.topography;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ProxyConfig {

    private static List<ProxyEntry> proxies = new ArrayList<>();
    public static volatile boolean lastConnectionUsedProxy = false;
    public static volatile String lastProxyAddress = "";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_FILE = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("topography_proxy.json");

    public static class ProxyEntry {
        public String name = "";
        public String host = "";
        public int port = 8000;
        public String username = "";
        public String password = "";
        public boolean enabled = true;
        public String linkedAccountUuid;

        public ProxyEntry() {}

        public ProxyEntry(String name, String host, int port, String username, String password) {
            this.name = name;
            this.host = host;
            this.port = port;
            this.username = username != null ? username : "";
            this.password = password != null ? password : "";
            this.enabled = true;
            this.linkedAccountUuid = null;
        }

        public boolean hasAuth() {
            return username != null && !username.isEmpty();
        }

        public String asString() {
            return host + ":" + port;
        }
    }

    public static void load() {
        File file = CONFIG_FILE.toFile();
        if (!file.exists()) return;

        try (Reader reader = new FileReader(file)) {
            Data data = GSON.fromJson(reader, Data.class);
            if (data != null) {
                proxies = data.proxies != null ? data.proxies : new ArrayList<>();
            }
        } catch (IOException e) {
            System.err.println("[TopographyProxy] Failed to load config: " + e.getMessage());
        }
    }

    public static void save() {
        try (Writer writer = new FileWriter(CONFIG_FILE.toFile())) {
            Data data = new Data();
            data.proxies = proxies;
            GSON.toJson(data, writer);
        } catch (IOException e) {
            System.err.println("[TopographyProxy] Failed to save config: " + e.getMessage());
        }
    }

    public static List<ProxyEntry> getProxies() {
        return proxies;
    }

    public static void addProxy(String name, String host, int port, String username, String password) {
        proxies.add(new ProxyEntry(name, host, port, username, password));
    }

    public static void removeProxy(int index) {
        if (index < 0 || index >= proxies.size()) return;
        proxies.remove(index);
    }

    public static boolean isEnabled() {
        String uuid = getCurrentUuid();
        if (uuid == null) return false;
        for (ProxyEntry entry : proxies) {
            if (uuid.equals(entry.linkedAccountUuid) && entry.enabled) return true;
        }
        return false;
    }

    public static ProxyEntry getActiveEntry() {
        String uuid = getCurrentUuid();
        if (uuid == null) return null;
        for (ProxyEntry entry : proxies) {
            if (uuid.equals(entry.linkedAccountUuid) && entry.enabled) return entry;
        }
        return null;
    }

    public static InetSocketAddress getAddress() {
        ProxyEntry entry = getActiveEntry();
        if (entry == null) return null;
        return new InetSocketAddress(entry.host, entry.port);
    }

    public static ProxyEntry getLinkedProxy() {
        String uuid = getCurrentUuid();
        if (uuid == null) return null;
        for (ProxyEntry entry : proxies) {
            if (uuid.equals(entry.linkedAccountUuid)) return entry;
        }
        return null;
    }

    public static void linkCurrentAccountTo(int index) {
        if (index < 0 || index >= proxies.size()) return;
        String uuid = getCurrentUuid();
        if (uuid == null) return;

        for (ProxyEntry entry : proxies) {
            if (uuid.equals(entry.linkedAccountUuid)) {
                entry.linkedAccountUuid = null;
            }
        }
        proxies.get(index).linkedAccountUuid = uuid;
        proxies.get(index).enabled = true;
    }

    public static boolean enableForCurrentAccount() {
        ProxyEntry entry = getLinkedProxy();
        if (entry == null) return false;
        entry.enabled = true;
        return true;
    }

    public static boolean disableForCurrentAccount() {
        ProxyEntry entry = getLinkedProxy();
        if (entry == null) return false;
        entry.enabled = false;
        return true;
    }

    private static String getCurrentUuid() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getSession() == null) return null;
        var uuid = client.getSession().getUuidOrNull();
        return uuid != null ? uuid.toString() : null;
    }

    private static class Data {
        List<ProxyEntry> proxies = new ArrayList<>();
    }
}
