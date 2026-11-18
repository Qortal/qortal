package org.qortal.rngit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.reticulum.destination.Response;
import io.reticulum.identity.Identity;
import org.apache.commons.codec.binary.Hex;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Answers the A/B requests of {@code reticulum/rngit-livetest/pages-ab.sh} with
 * {@link RngitPages}, writing each response as the reference side does: the
 * page bytes, {@code FILE <name>} and the contents for a file, or {@code NONE}.
 * <p>
 * Usage: {@code RngitPagesDump <fixture-root> <requests.json> <outdir>}
 */
public class RngitPagesDump {

    static final String DEST_HEX = "0123456789abcdef0123456789abcdef";
    static final String LINK_ID = "00112233445566778899aabbccddeeff";

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Path outdir = Files.createDirectories(Path.of(args[2]));

        RngitRepositories repositories = new RngitRepositories(Map.of(), Map.of(), Set.of());
        repositories.loadGroup("demo", root.resolve("demo"));
        RngitPages pages = new RngitPages(new Identity(), "Test Node", 0, repositories, Hex.decodeHex(DEST_HEX),
                outdir.resolve("config"), RngitConfig.parse(""), "VERSION");
        Map<String, RngitPages.Handler> handlers = pages.handlers();

        List<Map<String, Object>> requests = new ObjectMapper().readValue(Path.of(args[1]).toFile(), List.class);
        for (int i = 0; i < requests.size(); i++) {
            Map<String, Object> request = requests.get(i);
            byte[] out;
            try {
                Response response = handlers.get((String) request.get("path"))
                        .apply(new RngitPages.PageRequest((Map<Object, Object>) request.get("data"), null, LINK_ID));
                if (response == null) {
                    out = "NONE".getBytes(StandardCharsets.UTF_8);
                } else if (response.isFileResponse()) {
                    ByteArrayOutputStream file = new ByteArrayOutputStream();
                    file.write("FILE ".getBytes(StandardCharsets.UTF_8));
                    file.write((byte[]) ((Map<String, Object>) response.getMetadata()).get("name"));
                    file.write('\n');
                    file.write(Files.readAllBytes(response.getFile().toPath()));
                    out = file.toByteArray();
                } else {
                    out = response.getData();
                }
            } catch (Exception e) {
                out = ("EXCEPTION " + e).getBytes(StandardCharsets.UTF_8);
            }
            Files.write(outdir.resolve(String.format("%03d.out", i)), out);
        }
    }
}
