package org.mcstats.decoder;

import com.google.common.collect.ImmutableMap;
import org.eclipse.jetty.server.Request;
import org.mcstats.MCStats;
import org.mcstats.model.Plugin;
import org.mcstats.util.URLUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

public class LegacyRequestDecoder implements RequestDecoder {

    private MCStats mcstats;

    public LegacyRequestDecoder(MCStats mcstats) {
        this.mcstats = mcstats;
    }

    /**
     * {@inheritDoc}
     */
    public DecodedRequest decode(Plugin plugin, Request request) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(request.getInputStream(), "UTF-8"));
        String content = "";

        String line;
        while ((line = reader.readLine()) != null) {
            content += line;
        }
        reader.close();

        Map<String, String> post = processPostRequest(content);

        if (!post.containsKey("guid")) {
            return null;
        }

        DecodedRequest decoded = new DecodedRequest();
        decoded.guid = post.get("guid");
        decoded.serverVersion = post.get("server");
        decoded.pluginVersion = post.get("version");
        decoded.isPing = post.containsKey("ping");

        try {
            decoded.revision = post.containsKey("revision") ? Integer.parseInt(post.get("revision")) : 4;
            decoded.playersOnline = post.containsKey("players") ? Integer.parseInt(post.get("players")) : 0;
        } catch (NumberFormatException e) {
            return null;
        }

        if (decoded.guid == null || decoded.serverVersion == null || decoded.pluginVersion == null) {
            return null;
        }

        if (decoded.playersOnline < 0 || decoded.playersOnline > 2000) {
            decoded.playersOnline = 0;
        }

        if (decoded.revision >= 6) {
            decoded.osname = post.get("osname");
            decoded.osarch = post.get("osarch");
            decoded.osversion = post.get("osversion");
            decoded.javaName = "";
            decoded.javaVersion = post.get("java_version");
            if (decoded.osname == null) {
                decoded.osname = "Unknown";
                decoded.osversion = "Unknown";
            }
            if (decoded.osversion == null) {
                decoded.osversion = "Unknown";
            }
            if (decoded.javaVersion == null) {
                decoded.javaVersion = "Unknown";
            } else if (decoded.javaVersion.startsWith("1.") && decoded.javaVersion.length() > 3) {
                decoded.javaName = decoded.javaVersion.substring(0, decoded.javaVersion.indexOf('.', decoded.javaVersion.indexOf('.') + 1));
                decoded.javaVersion = decoded.javaVersion.substring(decoded.javaName.length() + 1);
            }
            if (decoded.osname != null) {
                try {
                    decoded.cores = Integer.parseInt(post.get("cores"));
                    decoded.authMode = Boolean.parseBoolean(post.get("online-mode")) ? 1 : 0;
                } catch (Exception e) {
                    decoded.cores = 0;
                    decoded.authMode = -1;
                }
            }
        }

        if (decoded.revision >= 5) {
            decoded.customData = ImmutableMap.copyOf(extractCustomData(post));
        } else {
            decoded.customData = ImmutableMap.copyOf(extractCustomDataLegacy(post));
        }

        return decoded;
    }

    private Map<String, String> processPostRequest(String content) {
        Map store = new HashMap();
        String arr[] = content.split("&");

        for (String entry : arr) {
            String data[] = entry.split("=");
            if (data.length == 2) {
                String key = URLUtils.decode(data[0]);
                String value = URLUtils.decode(data[1]);
                store.put(key, value);
            }
        }

        return store;
    }

    /**
     * Extract the custom data from the post request
     *
     * @param post
     * @return
     */
    private Map<String, Map<String, Long>> extractCustomData(Map<String, String> post) {
        Map<String, Map<String, Long>> result = new HashMap<>();

        for (Map.Entry<String, String> entry : post.entrySet()) {
            String postKey = entry.getKey();
            String postValue = entry.getValue();

            if (!postKey.startsWith("C")) {
                continue;
            }

            long value;

            try {
                value = Integer.parseInt(postValue);
            } catch (NumberFormatException e) {
                continue;
            }

            String graphData[] = postKey.split("~~");

            if (graphData.length == 3) {
                String graphName = graphData[1];
                String columnName = graphData[2];

                Map<String, Long> values = result.get(graphName);

                if (values == null) {
                    values = new HashMap<>();
                    result.put(graphName, values);
                }

                values.put(columnName, value);
            }
        }

        return result;
    }

    /**
     * Extract legacy custom data (no custom graphs just one graph)
     *
     * @param post
     * @return
     */
    private Map<String, Map<String, Long>> extractCustomDataLegacy(Map<String, String> post) {
        Map<String, Map<String, Long>> result = new HashMap<>();

        Map<String, Long> values = new HashMap<>();

        for (Map.Entry<String, String> entry : post.entrySet()) {
            String postKey = entry.getKey();
            String postValue = entry.getValue();

            if (!postKey.startsWith("C")) {
                continue;
            }

            long value;
            try {
                value = Integer.parseInt(postValue);
            } catch (NumberFormatException e) {
                continue;
            }

            if (postKey.startsWith("Custom")) {
                String columnName = postKey.substring(6).replaceAll("_", " ");

                values.put(columnName, value);
            }
        }

        result.put("Default", values);

        return result;
    }

}
