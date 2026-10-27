package es.tlskins;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.skinsrestorer.api.property.SkinVariant;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Descarga la skin que un nick tiene registrada en TLauncher.
 */
public class TLauncherClient {

    /** Skin descargada: la imagen PNG y si el modelo es de brazos finos (slim). */
    public record Skin(byte[] png, SkinVariant variant) {
    }

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G'};

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final Logger logger;
    private final String profileUrl;
    private final String skinUrl;

    public TLauncherClient(Logger logger, String profileUrl, String skinUrl) {
        this.logger = logger;
        this.profileUrl = profileUrl;
        this.skinUrl = skinUrl;
    }

    /**
     * Devuelve la skin del nick, o vacío si no tiene ninguna en TLauncher.
     */
    public Optional<Skin> fetch(String name) throws IOException, InterruptedException {
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8);
        String pngUrl = skinUrl.replace("{name}", encoded);
        SkinVariant variant = SkinVariant.CLASSIC;

        // Primero el perfil: da la dirección real de la imagen y el modelo (classic/slim)
        try {
            HttpResponse<String> response = http.send(request(profileUrl.replace("{name}", encoded)),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonElement root = JsonParser.parseString(response.body());
                if (root.isJsonObject() && root.getAsJsonObject().has("SKIN")) {
                    JsonObject skin = root.getAsJsonObject().getAsJsonObject("SKIN");
                    if (skin.has("url")) {
                        pngUrl = skin.get("url").getAsString();
                    }
                    if (skin.has("metadata") && skin.getAsJsonObject("metadata").has("model")
                            && "slim".equalsIgnoreCase(skin.getAsJsonObject("metadata").get("model").getAsString())) {
                        variant = SkinVariant.SLIM;
                    }
                } else {
                    return Optional.empty();
                }
            }
        } catch (IOException | RuntimeException e) {
            logger.fine("No se pudo leer el perfil de TLauncher de " + name + ": " + e.getMessage());
        }

        HttpResponse<byte[]> response = http.send(request(pngUrl), HttpResponse.BodyHandlers.ofByteArray());
        byte[] png = response.body();
        if (response.statusCode() != 200 || !isPng(png)) {
            return Optional.empty();
        }
        return Optional.of(new Skin(png, variant));
    }

    private static HttpRequest request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "TLauncherSkins/1.0")
                .GET()
                .build();
    }

    private static boolean isPng(byte[] data) {
        if (data == null || data.length < PNG_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < PNG_MAGIC.length; i++) {
            if (data[i] != PNG_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }
}
