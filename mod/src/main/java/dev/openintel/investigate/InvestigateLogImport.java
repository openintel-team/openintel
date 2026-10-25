package dev.openintel.investigate;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Bounded UTF-8 input; exports stay local and never enter the live tracker. */
public final class InvestigateLogImport {
    public static final int MAX_BYTES = 2 * 1024 * 1024;

    private InvestigateLogImport() { }

    public static String read(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("Choose a regular text file");
        byte[] bytes;
        try (var input = Files.newInputStream(path)) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES) throw new IOException("Log file exceeds 2 MiB");
        String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }
}
